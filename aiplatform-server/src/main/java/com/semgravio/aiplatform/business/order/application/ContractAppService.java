package com.semgravio.aiplatform.business.order.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.specification.ConditionSpecifications;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.business.order.application.dto.query.BackofficeContractQuery;
import com.semgravio.aiplatform.business.order.application.dto.response.BackofficeContractDetailResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.BackofficeContractSummaryResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.ContractResponse;
import com.semgravio.aiplatform.business.order.domain.aggregate.Contract;
import com.semgravio.aiplatform.business.order.domain.aggregate.Order;
import com.semgravio.aiplatform.business.order.domain.aggregate.PaymentPlan;
import com.semgravio.aiplatform.business.order.domain.entity.OrderPriceEntry;
import com.semgravio.aiplatform.business.order.domain.enums.ContractParty;
import com.semgravio.aiplatform.business.order.domain.enums.ContractStatus;
import com.semgravio.aiplatform.business.order.domain.enums.OrderStatus;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;
import com.semgravio.aiplatform.business.order.domain.repository.ContractRepository;
import com.semgravio.aiplatform.business.order.domain.repository.OrderRepository;
import com.semgravio.aiplatform.business.order.domain.repository.PaymentPlanRepository;
import com.semgravio.aiplatform.support.Tsid;

import lombok.extern.slf4j.Slf4j;

/**
 * 合同用例（#336 六态，spec #320 实施序④）：「确认范围并准备合同」＝合同创建
 * 口（用户确认动作与后台重建共用同一冻结正本——冻结时点最新价目行＋订单范围
 * 文本工作副本现值＋双版本引用冗余）；六态全部后台人工推进（推进/失败/撤回/
 * 作废/签署标记）＋草稿重录，每次动作 append-only 留痕（操作人/时间/依据
 * 必填）。
 *
 * <p>「同订单至多一份活跃（非终态）合同」双保险：预检
 * （{@code findActiveByOrderId}，409 ORD_024）＋库侧部分唯一索引兜底（并发
 * 漏过预检时约束拒绝，同译 ORD_024）。事务取舍同 {@link OrderAppService}：
 * 单聚合保存（级联追加留痕行）由仓储自带事务保证，动作方法刻意不加
 * {@code @Transactional}——约束撞错的吸收（catch 后翻译）要求 save 的事务已
 * 独立结束。</p>
 *
 * <p>「需更新」为派生标志（对照订单现值现算，不落库、无双写漂移）——改价/改
 * 范围后草稿合同置位，后台重录（重新冻结）即清零；签署中/终态恒 false（变动
 * 处置分立：签署中撤回重建、已签变更沟通不碰原合同）。</p>
 */
@Service
@Slf4j
public class ContractAppService {

    /** 活跃合同唯一索引名（约束撞错的判别键，与 V38__contract_domain.sql 对齐）。 */
    private static final String ACTIVE_CONTRACT_INDEX = "uk_ord_contracts_active";

    private final ContractRepository contractRepository;
    private final OrderRepository orderRepository;
    private final PaymentPlanRepository paymentPlanRepository;
    private final KickoffAppService kickoffAppService;
    private final TransactionTemplate transactionTemplate;

    public ContractAppService(ContractRepository contractRepository, OrderRepository orderRepository,
            PaymentPlanRepository paymentPlanRepository, KickoffAppService kickoffAppService,
            TransactionTemplate transactionTemplate) {
        this.contractRepository = contractRepository;
        this.orderRepository = orderRepository;
        this.paymentPlanRepository = paymentPlanRepository;
        this.kickoffAppService = kickoffAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 确认范围并准备合同（#336 合同创建口；#337 起付款计划同刻冻结创建）：用户
     * 确认动作（「确认范围并准备合同」）与后台重建（撤回/作废后换新号再立）
     * 共用——冻结集一次取齐（最新价目行＋范围文本三段现值＋PRD/Demo 双版本
     * 引用冗余），起步准备中；<b>合同行与付款计划行同一事务落库</b>（#337：
     * 计划挂订单下、合同号定格在行上——撤回重建走本口即新计划行、旧计划随旧
     * 合同留痕）。前置门：已报价（ORD_025）＋范围文本齐备（ORD_026）＋订单
     * 活跃（已报价态，ORD_021——已支付蕴含已签、已取消无合同流）＋无活跃合同
     * （ORD_024）。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_025 尚无报价；
     *                              ORD_026 范围文本未录入；ORD_021 订单非活跃
     *                              报价态；ORD_024 已有活跃合同（预检或并发撞
     *                              索引）
     */
    public ContractResponse confirmScope(Long orderId) {
        Order order = requireOrder(orderId);
        OrderPriceEntry quote = order.latestPriceEntry();
        if (quote == null) {
            throw new ApplicationException(OrderMessage.CONTRACT_QUOTE_REQUIRED);
        }
        if (!order.scopeEntered()) {
            throw new ApplicationException(OrderMessage.CONTRACT_SCOPE_REQUIRED);
        }
        if (order.getStatus() != OrderStatus.QUOTED) {
            throw new ApplicationException(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED);
        }
        // 已签守卫（#338 收口 #336 遗留缝）：最近合同已签署完成（终态——活跃
        // 预检查不到，谓词单点＝findLatestSignedByOrderId）即拒再建——已签走变更
        // 沟通不碰原合同；此守卫同时保证开工判定的「最近合同」读面不被新草稿
        // 污染（签署后等待付款的常态窗口）
        if (contractRepository.findLatestSignedByOrderId(orderId).isPresent()) {
            throw new ApplicationException(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED);
        }
        if (contractRepository.findActiveByOrderId(orderId).isPresent()) {
            throw new ApplicationException(OrderMessage.CONTRACT_ALREADY_ACTIVE);
        }
        Contract contract = Contract.create(order, quote);
        try {
            // 合同＋付款计划同一事务（#337 同刻冻结创建）——约束撞错（活跃合同
            // 唯一索引）在事务边界抛出，catch 翻译口径不变
            transactionTemplate.executeWithoutResult(status -> {
                Contract saved = contractRepository.save(contract);
                paymentPlanRepository.save(PaymentPlan.create(order, saved));
            });
        } catch (DataIntegrityViolationException e) {
            // 并发创建撞部分唯一索引（预检漏过）：后到者拒绝，同口径翻译；
            // 非本索引的完整性违例不冒名，原样上抛（同 OrderAppService.place 形制）
            if (violatesActiveContractIndex(e)) {
                log.info("订单 {} 并发建合同撞活跃合同唯一索引，拒绝", orderId);
                throw new ApplicationException(OrderMessage.CONTRACT_ALREADY_ACTIVE);
            }
            throw e;
        }
        return ContractResponse.of(contract, order);
    }

    /**
     * 推进（线性单步：准备中 → 待核对与签署 → 签署进行中 → 签署完成——末边带
     * 双方均签守卫）。依据必填、操作者随行落痕。<b>推进到「签署完成」即触发
     * 开工判定</b>（#338 双门禁之合同腿：save 落库后判定，付款腿未满足则空转
     * ——订单留已签合同的等待窗口）。
     *
     * @throws ApplicationException ORD_020 合同不存在；ORD_021 无推进边/双方未
     *                              均签；ORD_022/023 依据缺失/超长
     */
    public ContractResponse advance(Long contractId, String basis, Operator operator) {
        ActionResult result = act(contractId, contract -> contract.advance(basis, operator));
        if (result.contract().getStatus() == ContractStatus.SIGNED) {
            kickoffAppService.settle(result.contract().getOrderId()); // 合同腿触发点
        }
        return responseOf(result);
    }

    /**
     * 失败或过期（旁落终态）：自待核对与签署/签署进行中可达。
     *
     * @throws ApplicationException ORD_020 合同不存在；ORD_021 状态不允许；
     *                              ORD_022/023 依据缺失/超长
     */
    public ContractResponse markFailed(Long contractId, String basis, Operator operator) {
        return responseOf(act(contractId, contract -> contract.markFailed(basis, operator)));
    }

    /**
     * 撤回（旁落终态）：自任何非终态可达——此后重建＝新号新合同。
     *
     * @throws ApplicationException ORD_020 合同不存在；ORD_021 终态再动；
     *                              ORD_022/023 依据缺失/超长
     */
    public ContractResponse withdraw(Long contractId, String basis, Operator operator) {
        return responseOf(act(contractId, contract -> contract.withdraw(basis, operator)));
    }

    /**
     * 作废（旁落终态，与撤回同去向、动作语义分立）。
     *
     * @throws ApplicationException ORD_020 合同不存在；ORD_021 终态再动；
     *                              ORD_022/023 依据缺失/超长
     */
    public ContractResponse voidContract(Long contractId, String basis, Operator operator) {
        return responseOf(act(contractId, contract -> contract.voidContract(basis, operator)));
    }

    /**
     * 签署标记（电子签渠道真接前的后台承接口径）：仅签署进行中态，落一方签署
     * 位＋时点；不自动迁移——「签署完成」由 {@link #advance} 显式推进（双方
     * 均签守卫）。
     *
     * @throws ApplicationException ORD_020 合同不存在；ORD_021 非签署进行中；
     *                              ORD_028 该方已签；ORD_022/023 依据缺失/超长
     */
    public ContractResponse sign(Long contractId, ContractParty party, String basis, Operator operator) {
        return responseOf(act(contractId, contract -> contract.sign(party, basis, operator)));
    }

    /**
     * 草稿重录（「需更新」处置正路）：同号＋内容整组录入＋冻结集取订单现值
     * 重新定格＋版本 +1；<b>范围重冻→新付款计划</b>（#337——新计划行即活跃，
     * 旧计划随旧冻结集留痕、节点定格不可付）。仅草稿态（准备中/待核对与签署）。
     *
     * @throws ApplicationException ORD_020 合同不存在；ORD_031 非草稿态；
     *                              ORD_032 内容超长；ORD_022/023 依据缺失/超长
     */
    public ContractResponse record(Long contractId, String partyA, String partyB,
            String projectScope, String paymentPlan, Operator operator, String basis) {
        Contract contract = requireContract(contractId);
        // 重冻取订单现值——加载一次贯穿动作与响应（「需更新」对照同源不二读）
        Order order = currentOrderOf(contract);
        contract.record(partyA, partyB, projectScope, paymentPlan, order, operator, basis);
        // 重冻与新计划同一事务（#337 范围重冻→新付款计划）
        transactionTemplate.executeWithoutResult(status -> {
            contractRepository.save(contract);
            paymentPlanRepository.save(PaymentPlan.create(order, contract));
        });
        return ContractResponse.of(contract, order);
    }

    /**
     * 订单名下最近合同（#336 用户面合同预览卡取数面）：活跃合同在场即最新；
     * 撤回/作废重建后最新＝新活跃行；终态（签署完成/撤回作废）后仍可见——
     * 终态合同永不物理删除。
     *
     * @throws ApplicationException ORD_020 该订单名下无合同
     */
    public ContractResponse latestByOrder(Long orderId) {
        Contract contract = contractRepository
                .findFirstByOrderIdOrderByCreatedAtDescIdDesc(orderId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_CONTRACT_NOT_FOUND));
        return ContractResponse.of(contract, requireOrder(orderId));
    }

    /**
     * 后台合同详情（承接全量面）：全字段＋「需更新」派生＋PRD 冻结全文＋动作
     * 留痕时间线（新 → 旧）。
     *
     * @throws ApplicationException ORD_020 合同不存在
     */
    public BackofficeContractDetailResponse detailForBackoffice(Long contractId) {
        Contract contract = requireContract(contractId);
        return BackofficeContractDetailResponse.of(contract, requireOrder(contract.getOrderId()));
    }

    /**
     * 后台合同清单（运营工作清单，新在前）：状态多选（空选＝全量）、创建时间
     * 区间（含两端）、订单号精确、合同号精确。过滤在数据库侧完成
     * （Specification）；排序定死 id 倒序（TSID 时间有序），不开放客户端排序。
     * 非数值订单号如实返回空清单（200、total 0——检索维度上的「无命中」，
     * 同 {@link BackofficeOrderAppService} 口径）。
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeContractSummaryResponse> contracts(List<ContractStatus> statuses,
            LocalDateTime createdFrom, LocalDateTime createdTo, String orderId, String contractNo,
            Pagination pagination) {
        Long parsedOrderId = Tsid.parseOrNull(orderId);
        if (orderId != null && !orderId.isBlank() && parsedOrderId == null) {
            return PageResponse.empty(pagination);
        }
        BackofficeContractQuery query = new BackofficeContractQuery(
                statuses, createdFrom, createdTo, parsedOrderId, contractNo);
        Specification<Contract> specification = ConditionSpecifications.fromAnnotation(query);
        Pageable pageable = pagination.toPageRequest()
                .withSort(Sort.by(Sort.Direction.DESC, "id"));
        Page<Contract> result = contractRepository.findAll(specification, pageable);
        return PageResponse.of(result.map(BackofficeContractSummaryResponse::of));
    }

    // ---------- 内部：动作共用骨架（加载→动作→保存→响应，同事务取舍） ----------

    /**
     * 动作结果（已落库聚合＋响应拼装已取齐的订单）。act 不再返回 REST 响应
     * DTO（#338 收敛）——advance 的开工判定读聚合本体（状态/orderId），REST
     * Long→string 序列化是出口约定、不回穿应用层内用；响应组装归各动作口。
     */
    private record ActionResult(Contract contract, Order order) {
    }

    private ActionResult act(Long contractId, Consumer<Contract> action) {
        Contract contract = requireContract(contractId);
        // 重录要读订单现值（重新冻结）；其余动作读面只需 needsUpdate 对照——
        // 统一带订单取齐再出会话（findWithHistory 一次取齐价目与范围）
        action.accept(contract);
        Order order = requireOrder(contract.getOrderId());
        return new ActionResult(contractRepository.save(contract), order);
    }

    private ContractResponse responseOf(ActionResult result) {
        return ContractResponse.of(result.contract(), result.order());
    }

    private Contract requireContract(Long contractId) {
        return contractRepository.findWithTrail(contractId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_CONTRACT_NOT_FOUND));
    }

    private Order requireOrder(Long orderId) {
        return orderRepository.findWithHistory(orderId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_NOT_FOUND));
    }

    /** 订单现值（重录的重冻取数源与「需更新」对照源）。 */
    private Order currentOrderOf(Contract contract) {
        return orderRepository.findWithHistory(contract.getOrderId())
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_NOT_FOUND));
    }

    private static boolean violatesActiveContractIndex(DataIntegrityViolationException e) {
        return mentions(e.getMessage()) || mentions(e.getMostSpecificCause().getMessage());
    }

    private static boolean mentions(String message) {
        return message != null && message.contains(ACTIVE_CONTRACT_INDEX);
    }
}
