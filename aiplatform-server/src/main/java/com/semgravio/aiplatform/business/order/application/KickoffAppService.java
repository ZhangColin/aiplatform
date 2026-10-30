package com.semgravio.aiplatform.business.order.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.order.domain.aggregate.Contract;
import com.semgravio.aiplatform.business.order.domain.aggregate.Order;
import com.semgravio.aiplatform.business.order.domain.aggregate.PaymentPlan;
import com.semgravio.aiplatform.business.order.domain.enums.OrderStatus;
import com.semgravio.aiplatform.business.order.domain.repository.ContractRepository;
import com.semgravio.aiplatform.business.order.domain.repository.OrderRepository;
import com.semgravio.aiplatform.business.order.domain.repository.PaymentPlanRepository;
import com.semgravio.aiplatform.business.project.application.ProductionHandoffAppService;

import lombok.extern.slf4j.Slf4j;

/**
 * 开工判定用例（#338「已支付＝开工条件满足」重对位接通，spec #320 实施序④）：
 * 双门禁＝<b>合同签署完成 && 全部开工门节点已到账</b>——满足时刻同一事务内
 * 订单转「已支付」（{@code Order#pay} 缝隙，paymentNo＝合同号——无渠道流水，
 * 溯源正身即合同）＋创建生产承接记录（跨 BC 同事务先例＝{@code retryArchive}
 * 调 project BC）。双触发点＝{@link ContractAppService#advance}（推进到签署
 * 完成后）与 {@link PaymentPlanAppService#verifyArrival}（节点核验到账后），
 * 同判定器入口 {@link #settle}。
 *
 * <p>幂等三腿：①QUOTED 预检（已转已支付的后到触发自动空转）；②承接服务端
 * 事务内判定（已在即回读既有行）；③库侧 project_id 唯一索引兜底——并发双
 * 触发两个事务都过预检时，后到者撞索引整体回滚（订单 pay 与承接同事务，
 * 回滚不丢先行者成果），catch 吸收为幂等空转（同
 * {@code ContractAppService#confirmScope} 撞活跃唯一索引形制）。门判定读
 * 「最近合同」（{@code ContractRepository#findLatestSignedByOrderId} 单点，
 * 含终态——SIGNED 是终态、活跃查询查不到已签合同）；配套防线＝confirmScope
 * 对已签订单拒再建合同（新草稿不污染最近合同读面）。</p>
 *
 * <p>开工门节点集合口径：{@code kickoffGate} 标志过滤、全部已到账才过门；
 * <b>计划未录节点≠过门</b>（等待录入/等待付款同呈现「待付款」）；录了节点但
 * 无任何开工门标志＝空真过门（没设门＝无款门，门＝仅合同腿——后台录入面提示
 * 归 admin 侧）。「已支付」通知在事务提交后发射（SSE 与任何文件副作用留事务
 * 外的既有铁律）。</p>
 */
@Service
@Slf4j
public class KickoffAppService {

    private final OrderRepository orderRepository;
    private final ContractRepository contractRepository;
    private final PaymentPlanRepository paymentPlanRepository;
    private final ProductionHandoffAppService productionHandoffAppService;
    private final EventsAppService eventsAppService;
    private final TransactionTemplate transactionTemplate;

    public KickoffAppService(OrderRepository orderRepository, ContractRepository contractRepository,
            PaymentPlanRepository paymentPlanRepository, ProductionHandoffAppService productionHandoffAppService,
            EventsAppService eventsAppService, TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.contractRepository = contractRepository;
        this.paymentPlanRepository = paymentPlanRepository;
        this.productionHandoffAppService = productionHandoffAppService;
        this.eventsAppService = eventsAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 开工判定（双触发点共用入口，幂等空转面）：门未满足或已转已支付即静默
     * 返回；满足即同一事务落「订单已支付＋生产承接创建」，事务提交后发
     * 「已支付」通知（开工条件满足的状态语义）。
     *
     * @param orderId 判定挂靠订单（触发动作所在订单）
     */
    public void settle(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.QUOTED) {
            return; // 幂等门①：已支付/终结态不二转；订单缺档＝触发链异常，空转不炸
        }
        Contract contract = contractRepository.findLatestSignedByOrderId(orderId).orElse(null);
        if (contract == null) {
            return; // 合同腿未满足（谓词单点＝findLatestSignedByOrderId，含终态）
        }
        PaymentPlan plan = paymentPlanRepository.findActiveWithNodesByOrderId(orderId).orElse(null);
        if (plan == null || !plan.kickoffGatesPassed()) {
            return; // 付款腿未满足（谓词单源＝PaymentPlan#kickoffGatesPassed）
        }
        try {
            // 同一事务：订单转已支付＋创建承接（先例＝retryArchive 收订单＋项目）
            transactionTemplate.executeWithoutResult(status -> {
                order.pay(contract.getContractNo());
                orderRepository.save(order);
                productionHandoffAppService.createForKickoff(order.getProjectId(), orderId);
            });
        } catch (DataIntegrityViolationException e) {
            // 幂等门③：并发双触发撞承接唯一索引（两事务都过预检的后到者）——
            // 整体回滚（先行者已落支付与承接，无丢失），吸收为幂等空转（判别
            // 单点在 project BC 承接仓储，经应用服务跨 BC 读）
            if (productionHandoffAppService.violatesUniqueIndex(e)) {
                log.info("订单 {} 并发开工判定撞承接唯一索引，后到者幂等空转", orderId);
                return;
            }
            throw e;
        }
        publishNotification(order);
    }

    /**
     * 「已支付」通知（payload 构造单点＝{@code OrderEventTypes#notificationPayload}，
     * 与 {@code OrderAppService} 通知族同形同源——归属路由键取自订单聚合，ADR-0018）。
     */
    private void publishNotification(Order order) {
        eventsAppService.publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED,
                OrderEventTypes.notificationPayload(order));
    }
}
