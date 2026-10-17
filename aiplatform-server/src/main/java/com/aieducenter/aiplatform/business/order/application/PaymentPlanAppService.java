package com.aieducenter.aiplatform.business.order.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.BaseCodeMessage;

import com.aieducenter.aiplatform.business.order.application.dto.command.RejectPaymentCommand;
import com.aieducenter.aiplatform.business.order.application.dto.command.VerifyPaymentCommand;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficePaymentPlanDetailResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.PaymentPlanResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.PaymentVoucherContent;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentPlan;
import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentVoucher;
import com.aieducenter.aiplatform.business.order.domain.entity.PaymentNode;
import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentVoucherFile;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;
import com.aieducenter.aiplatform.business.order.domain.model.PaymentNodeSpec;
import com.aieducenter.aiplatform.business.order.domain.model.PaymentVouchers;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.PaymentPlanRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.PaymentVoucherFileRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.PaymentVoucherRepository;

/**
 * 付款计划用例（#337 凭证核验路径，spec #320 实施序④）：付款计划挂订单下、
 * 随合同冻结同刻创建（创建口在 {@link ContractAppService}——plan 行与合同行
 * 同一事务落库）；本服务承节点录入/开工门设定（后台）、凭证上传（用户）、
 * 到账核验与打回（后台，留痕 append-only）与两面读面。
 *
 * <p>v1 只走凭证核验路径：待支付→（用户上传凭证）线下转账待核验→（后台
 * 核验）已到账/打回；「处理中/失败」枚举立着、路径 v1 不通（渠道真接后启用
 * ——渠道接口位＝节点上的渠道类型枚举＋流水号可空字段，既有支付端口维持
 * 现状不加宽）。节点转已到账要求全额——不足留痕＋回待支付（累计已核到账
 * 不清零，前台推导「还需支付金额与对应节点」）。</p>
 *
 * <p>事务取舍：凭证上传与核验/打回是「节点状态（计划聚合）＋凭证行/留痕行
 * （聚合外）」的原子对——经 {@link TransactionTemplate} 单事务收口（加载与
 * 变更同会话，懒集合并发初始化安全）。读面单查询无跨聚合写，仓储自带事务
 * 即足。</p>
 */
@Service
public class PaymentPlanAppService {

    private final PaymentPlanRepository paymentPlanRepository;
    private final PaymentVoucherRepository voucherRepository;
    private final PaymentVoucherFileRepository voucherFileRepository;
    private final OrderRepository orderRepository;
    private final KickoffAppService kickoffAppService;
    private final TransactionTemplate transactionTemplate;

    public PaymentPlanAppService(PaymentPlanRepository paymentPlanRepository,
            PaymentVoucherRepository voucherRepository,
            PaymentVoucherFileRepository voucherFileRepository, OrderRepository orderRepository,
            KickoffAppService kickoffAppService, TransactionTemplate transactionTemplate) {
        this.paymentPlanRepository = paymentPlanRepository;
        this.voucherRepository = voucherRepository;
        this.voucherFileRepository = voucherFileRepository;
        this.orderRepository = orderRepository;
        this.kickoffAppService = kickoffAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 订单名下活跃付款计划（用户面付款面板取数面）：活跃＝订单名下最新行
     * （撤回重建/范围重冻后即新行）；节点＋本人凭证清单＋「还需支付」推导面。
     *
     * @throws ApplicationException ORD_036 该订单名下无付款计划（尚无合同即无
     *                              计划——前端缺席＝不渲染面板）；ORD_001 订单不存在
     */
    public PaymentPlanResponse planOfOrder(Long orderId) {
        requireOrder(orderId);
        return responseOf(requireActivePlan(orderId));
    }

    /**
     * 付款节点录入（#337 后台动作，「付款节点录入/开工门设定」）：整组替换
     * ——序号按列表序派生、开工门标志随行定、附言码自合同号定格。重录窗口＝
     * 支付活动未开始（聚合腿：全部待支付且无留痕；凭证腿：本服务预检——两腿
     * 同译 ORD_044）。
     *
     * @throws ApplicationException ORD_036 无付款计划；ORD_039 录入无效；
     *                              ORD_044 已开始支付；ORD_001 订单不存在
     */
    public PaymentPlanResponse enterNodes(Long orderId, List<PaymentNodeSpec> specs) {
        requireOrder(orderId);
        PaymentPlan plan = requireActivePlan(orderId);
        if (voucherRepository.countByNodeIdIn(nodeIdsOf(plan)) > 0) {
            // 凭证腿（聚合腿之外）：有凭证即支付活动已开始，与聚合守卫同译 ORD_044
            throw new ApplicationException(OrderMessage.PAYMENT_NODES_LOCKED);
        }
        plan.enterNodes(specs);
        return responseOf(paymentPlanRepository.save(plan));
    }

    /**
     * 上传转账凭证（#337 凭证路径第一跳·用户动作）：multipart 单文件（pdf/png/
     * jpg/jpeg、≤10MB 如实报错不静默压缩），节点翻线下转账待核验——上传仅代表
     * 已提交（核验结论在后台留痕行）。凭证行与节点状态同事务落库；多次上传
     * 追加保留不覆盖。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_038 订单已终结或节点
     *                              状态不可收凭证；ORD_036 无付款计划；ORD_037
     *                              节点不存在或不属活跃计划；ORD_040 文件格式/
     *                              大小无效
     */
    public PaymentPlanResponse uploadVoucher(Long orderId, Long nodeId, MultipartFile file) {
        // 守卫序：订单存在（ORD_001）→ 未终结（ORD_038——已归档/已取消不再收凭证）
        Order order = requireOrder(orderId);
        if (order.getStatus().isTerminal()) {
            throw new ApplicationException(OrderMessage.PAYMENT_ACTION_NOT_ALLOWED);
        }
        String originalName = file == null ? null : file.getOriginalFilename();
        if (!PaymentVouchers.isUploadableVoucher(originalName)) {
            throw new ApplicationException(OrderMessage.PAYMENT_VOUCHER_INVALID);
        }
        if (file.getSize() > PaymentVouchers.MAX_UPLOAD_BYTES) {
            throw new ApplicationException(OrderMessage.PAYMENT_VOUCHER_INVALID);
        }
        byte[] content = readBytes(file);
        String fileName = PaymentVouchers.sanitizeName(originalName);
        String contentType = PaymentVouchers.contentTypeOf(originalName);
        PaymentPlan plan = transactionTemplate.execute(status -> {
            PaymentPlan current = requireActivePlan(orderId);
            PaymentNode node = current.node(nodeId);
            node.submitVoucher();
            paymentPlanRepository.save(current);
            PaymentVoucher voucher = voucherRepository.save(
                    PaymentVoucher.upload(node.getId(), fileName, contentType, content.length));
            voucherFileRepository.save(PaymentVoucherFile.of(voucher.getId(), content));
            return current;
        });
        return responseOf(plan);
    }

    /**
     * 到账核验（#337 凭证路径第二跳·后台人工录入）：实到金额/实到时间/关联
     * 凭证随行留痕、渠道流水号可空落值。全额判定＝累计核到账 ≥ 节点金额：
     * 足额转已到账，不足回待支付留痕（累计不清零，前台推导差额）。
     * <b>核验落库后即触发开工判定</b>（#338 双门禁之付款腿：开工门节点全部
     * 到账且合同已签即开工——{@code KickoffAppService#settle} 自带幂等空转面，
     * 不足额/非开工门核验自然空转）。
     *
     * @throws ApplicationException ORD_036 无付款计划；ORD_037 节点不存在；
     *                              ORD_038 非待核验态；ORD_041 录入无效；
     *                              ORD_045 关联凭证不存在或不属该节点；
     *                              ORD_043 操作者必填
     */
    public PaymentPlanResponse verifyArrival(Long orderId, Long nodeId, VerifyPaymentCommand command,
            Operator operator) {
        Long voucherId = requireVoucherOfNode(command.voucherId(), nodeId);
        PaymentPlan plan = transactionTemplate.execute(status -> {
            PaymentPlan current = requireActivePlan(orderId);
            current.node(nodeId).verifyArrival(command.actualAmount(), command.arrivedAt(),
                    voucherId, command.channelRefNo(), command.basis(), operator);
            return paymentPlanRepository.save(current);
        });
        PaymentPlanResponse response = responseOf(plan);
        kickoffAppService.settle(orderId); // 付款腿触发点（save 落库后判定）
        return response;
    }

    /**
     * 打回（后台人工动作）：待核验态可达，回待支付＋依据必填留痕（ORD_042）。
     * 打回不动累计金额（核到账只经核验录入累积）。
     *
     * @throws ApplicationException ORD_036 无付款计划；ORD_037 节点不存在；
     *                              ORD_038 非待核验态；ORD_042 依据必填；
     *                              ORD_045 关联凭证不存在或不属该节点；
     *                              ORD_043 操作者必填
     */
    public PaymentPlanResponse reject(Long orderId, Long nodeId, RejectPaymentCommand command,
            Operator operator) {
        Long voucherId = requireVoucherOfNode(command.voucherId(), nodeId);
        PaymentPlan plan = transactionTemplate.execute(status -> {
            PaymentPlan current = requireActivePlan(orderId);
            current.node(nodeId).reject(voucherId, command.basis(), operator);
            return paymentPlanRepository.save(current);
        });
        return responseOf(plan);
    }

    /**
     * 后台付款计划详情（核验承接全量面）：活跃计划全量（节点＋凭证＋核验留痕
     * 时间线新 → 旧）＋历史计划行摘要（旧计划随旧合同留痕）。
     *
     * @throws ApplicationException ORD_036 无付款计划；ORD_001 订单不存在
     */
    public BackofficePaymentPlanDetailResponse detailForBackoffice(Long orderId) {
        requireOrder(orderId);
        List<PaymentPlan> plans = paymentPlanRepository.findWithNodesByOrderId(orderId);
        PaymentPlan active = firstPlan(plans);
        Function<Long, List<PaymentVoucher>> vouchersByNode = vouchersByNodeOf(active);
        return BackofficePaymentPlanDetailResponse.of(active, vouchersByNode, plans);
    }

    /**
     * 凭证内容点取（后台核验看证的唯一触字节口）：元数据＋字节同取，HTTP 形态
     * （文件名/响应头）归 REST 层。
     *
     * @throws ApplicationException ORD_045 凭证不存在（伴行字节缺行＝凭证不完整，
     *                              同译不顶替）
     */
    public PaymentVoucherContent voucherWithContent(Long voucherId) {
        PaymentVoucher voucher = voucherRepository.findById(voucherId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.PAYMENT_VOUCHER_NOT_FOUND));
        PaymentVoucherFile file = voucherFileRepository.findById(voucherId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.PAYMENT_VOUCHER_NOT_FOUND));
        return new PaymentVoucherContent(voucher.getFileName(), voucher.getContentType(),
                voucher.getByteSize(), file.getContent());
    }

    // ---------- 内部：寻址与取数 ----------

    private Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_NOT_FOUND));
    }

    /** 活跃计划＝订单名下最新行（带节点一次取齐）。 */
    private PaymentPlan requireActivePlan(Long orderId) {
        return firstPlan(paymentPlanRepository.findWithNodesByOrderId(orderId));
    }

    private PaymentPlan firstPlan(List<PaymentPlan> plans) {
        if (plans == null || plans.isEmpty()) {
            throw new ApplicationException(OrderMessage.PAYMENT_PLAN_NOT_FOUND);
        }
        return plans.get(0);
    }

    private static List<Long> nodeIdsOf(PaymentPlan plan) {
        return plan.getNodes().stream().map(PaymentNode::getId).toList();
    }

    /** 关联凭证校验（证据链引用）：可空直过；非空须存在且属该节点（ORD_045）。 */
    private Long requireVoucherOfNode(String voucherId, Long nodeId) {
        if (voucherId == null || voucherId.isBlank()) {
            return null;
        }
        Long parsed = parseVoucherId(voucherId);
        PaymentVoucher voucher = voucherRepository.findById(parsed)
                .orElseThrow(() -> new ApplicationException(OrderMessage.PAYMENT_VOUCHER_NOT_FOUND));
        if (!nodeId.equals(voucher.getNodeId())) {
            throw new ApplicationException(OrderMessage.PAYMENT_VOUCHER_NOT_FOUND);
        }
        return parsed;
    }

    private static Long parseVoucherId(String voucherId) {
        try {
            return Long.parseLong(voucherId.trim());
        } catch (NumberFormatException e) {
            throw new ApplicationException(OrderMessage.PAYMENT_VOUCHER_NOT_FOUND);
        }
    }

    /** 活跃计划各节点的凭证清单（按节点分组，上传序）。 */
    private Function<Long, List<PaymentVoucher>> vouchersByNodeOf(PaymentPlan plan) {
        List<Long> nodeIds = nodeIdsOf(plan);
        if (nodeIds.isEmpty()) {
            return nodeId -> List.of();
        }
        Map<Long, List<PaymentVoucher>> grouped = voucherRepository
                .findByNodeIdInOrderByIdAsc(nodeIds).stream()
                .collect(Collectors.groupingBy(PaymentVoucher::getNodeId));
        return nodeId -> grouped.getOrDefault(nodeId, List.of());
    }

    private PaymentPlanResponse responseOf(PaymentPlan plan) {
        return PaymentPlanResponse.of(plan, vouchersByNodeOf(plan));
    }

    /** 读上传内容（multipart 临时件读取失败是技术故障，如实 500 不伪装业务码；同物料上传口径）。 */
    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception readFailure) {
            throw new ApplicationException(BaseCodeMessage.INTERNAL_SERVER_ERROR,
                    "读取上传内容失败: " + readFailure.getMessage());
        }
    }
}
