package com.aieducenter.aiplatform.business.order.application;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Contract;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentPlan;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractStatus;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.repository.ContractRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.PaymentPlanRepository;
import com.aieducenter.aiplatform.business.project.application.ProductionHandoffAppService;

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
 * 「最近合同」（{@code findFirstByOrderIdOrderByCreatedAtDescIdDesc}）——
 * SIGNED 是终态、活跃查询查不到已签合同；配套防线＝confirmScope 对已签订单
 * 拒再建合同（新草稿不污染最近合同读面）。</p>
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

    /** 承接唯一索引名（并发兜底腿的判别键，与 V40__production_handoff.sql 对齐）。 */
    private static final String HANDOFF_UNIQUE_INDEX = "uk_prj_production_handoffs_project";

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
        Contract contract = latestContractOf(orderId);
        if (contract == null || contract.getStatus() != ContractStatus.SIGNED) {
            return; // 合同腿未满足
        }
        PaymentPlan plan = activePlanOf(orderId);
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
            // 整体回滚（先行者已落支付与承接，无丢失），吸收为幂等空转
            if (violatesHandoffIndex(e)) {
                log.info("订单 {} 并发开工判定撞承接唯一索引，后到者幂等空转", orderId);
                return;
            }
            throw e;
        }
        publishNotification(order);
    }

    /** 最近合同（任意状态——SIGNED 是终态，活跃查询查不到已签合同）。 */
    private Contract latestContractOf(Long orderId) {
        return contractRepository.findFirstByOrderIdOrderByCreatedAtDescIdDesc(orderId).orElse(null);
    }

    /** 活跃付款计划（订单名下最新行，带节点；无计划即 null）。 */
    private PaymentPlan activePlanOf(Long orderId) {
        List<PaymentPlan> plans = paymentPlanRepository.findWithNodesByOrderId(orderId);
        return plans.isEmpty() ? null : plans.get(0);
    }

    /**
     * 「已支付」通知（口径同 {@code OrderAppService} 通知族：payload 两事件
     * 同形、归属路由键取自订单聚合——含后台触发路径，ADR-0018）。
     */
    private void publishNotification(Order order) {
        eventsAppService.publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, Map.of(
                OrderEventTypes.PROJECT_ID_FIELD, order.getProjectId().toString(),
                OrderEventTypes.ORDER_ID_FIELD, order.getId().toString(),
                OrderEventTypes.STATUS_FIELD, order.getStatus().getCode(),
                OrderEventTypes.STATUS_NAME_FIELD, order.getStatus().getName(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(order.getOwnerAccountId()),
                EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(order.getOwnerGuestId())));
    }

    private static boolean violatesHandoffIndex(DataIntegrityViolationException e) {
        return mentions(e.getMessage()) || mentions(e.getMostSpecificCause().getMessage());
    }

    private static boolean mentions(String message) {
        return message != null && message.contains(HANDOFF_UNIQUE_INDEX);
    }
}
