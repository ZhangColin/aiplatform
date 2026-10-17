package com.aieducenter.aiplatform.business.order.application;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.order.application.dto.response.DeliveryStatusResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentPlan;
import com.aieducenter.aiplatform.business.order.domain.enums.DeliveryState;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.repository.ContractRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.order.domain.repository.PaymentPlanRepository;
import com.aieducenter.aiplatform.business.project.application.ProductionHandoffAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;

/**
 * 交付状态读用例（#338 前台六态纯推导，spec #320 实施序④）：项目维度一次
 * 读齐订单＋合同＋付款＋承接四源（＋「需用户补充」项目级标志），现算六态
 * 返回——<b>不存储</b>（无列无双写漂移）。推导单点在本服务（判定跟职责走：
 * 开工门谓词在 {@link PaymentPlan#kickoffGatesPassed} 聚合单源，本服务只组
 * 合分岔）；跨 BC 读面走 project 应用服务软引用（承接事实形状＋标志，同
 * {@code OrderAppService} 读项目的既有口径）。
 *
 * <p>六态优先级：需用户补充（项目级标志盖帽）＞ 生产两态（已支付＝开工条件
 * 满足，承接两态即前台生产两态）＞ 款项核验中 ＞ 待付款 ＞ 待签约。订单终态
 * 不进本口（{@code findActiveByProject} 只取未终结）；待报价＝state null
 * （订单面「待确认报价」占位态照旧，六态不覆盖）。遗留兜底：已支付但无承接
 * （mock 时代单）归「待安排生产」——已过门的既成事实不虚构流程回退。</p>
 */
@Service
public class DeliveryStatusAppService {

    private final OrderRepository orderRepository;
    private final ContractRepository contractRepository;
    private final PaymentPlanRepository paymentPlanRepository;
    private final ProjectQueryAppService projectQueryAppService;
    private final ProductionHandoffAppService productionHandoffAppService;

    public DeliveryStatusAppService(OrderRepository orderRepository,
            ContractRepository contractRepository, PaymentPlanRepository paymentPlanRepository,
            ProjectQueryAppService projectQueryAppService,
            ProductionHandoffAppService productionHandoffAppService) {
        this.orderRepository = orderRepository;
        this.contractRepository = contractRepository;
        this.paymentPlanRepository = paymentPlanRepository;
        this.projectQueryAppService = projectQueryAppService;
        this.productionHandoffAppService = productionHandoffAppService;
    }

    /**
     * 项目交付状态（前台六态投影，纯现算）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；ORD_001 项目名下无未终结
     *                              订单（无交付生命周期可呈现——前端缺席＝不渲染）
     */
    public DeliveryStatusResponse statusOf(Long projectId) {
        boolean needsSupplement = projectQueryAppService.needsUserSupplement(projectId);
        Order order = orderRepository.findActiveByProject(projectId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_NOT_FOUND));
        boolean contractSigned = contractRepository
                .findLatestSignedByOrderId(order.getId()).isPresent();
        PaymentPlan plan = paymentPlanRepository.findActiveWithNodesByOrderId(order.getId()).orElse(null);
        ProductionHandoffAppService.ProductionHandoffFacts handoff =
                productionHandoffAppService.handoffFactsOf(projectId);
        DeliveryState state = derive(order, contractSigned, plan, handoff, needsSupplement);
        return DeliveryStatusResponse.of(order, state, needsSupplement, handoff);
    }

    /**
     * 六态推导（真值表正本）：
     * <pre>
     * 需用户补充（标志盖帽，任意非终态订单）
     * 已支付 → 承接生产中？生产中：待安排生产（无承接＝遗留单兜底待安排）
     * 待报价 → null（六态不覆盖，「待确认报价」占位态照旧）
     * 已报价 → 合同未签署完成（无/草稿/签署中/旁落终态）→ 待签约
     *         合同已签 → 有节点待核验 → 款项核验中
     *                    开工门全到账 → 待安排生产（判定即时转已支付，本行为兜底瞬态）
     *                    其余（无计划/未录节点/门未到账）→ 待付款
     * </pre>
     */
    private static DeliveryState derive(Order order, boolean contractSigned, PaymentPlan plan,
            ProductionHandoffAppService.ProductionHandoffFacts handoff, boolean needsSupplement) {
        if (needsSupplement) {
            return DeliveryState.NEEDS_USER_SUPPLEMENT;
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return handoff != null && handoff.inProduction()
                    ? DeliveryState.IN_PRODUCTION : DeliveryState.AWAITING_PRODUCTION;
        }
        if (order.getStatus() == OrderStatus.PENDING_QUOTE) {
            return null;
        }
        if (!contractSigned) {
            return DeliveryState.AWAITING_CONTRACT;
        }
        if (plan != null && plan.anyNodeAwaitingVerification()) {
            return DeliveryState.VERIFYING_PAYMENT;
        }
        if (plan != null && plan.kickoffGatesPassed()) {
            return DeliveryState.AWAITING_PRODUCTION;
        }
        return DeliveryState.AWAITING_PAYMENT;
    }
}
