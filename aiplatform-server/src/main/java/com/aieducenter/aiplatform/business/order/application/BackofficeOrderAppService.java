package com.aieducenter.aiplatform.business.order.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.specification.ConditionSpecifications;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.business.identity.application.AccountAppService;
import com.aieducenter.aiplatform.business.order.application.dto.query.BackofficeOrderQuery;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderDetailResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderSummaryResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;

/**
 * 后台订单读面（#29 交易环②，/api/backoffice/* 机机签名四端点的三读端点）：
 * 四维检索分页拉单（运营工作清单，#156 扩）/ 详情（PRD 快照 + 项目名 + 用户
 * 昵称）/ 源码包（复用 project 上下文打包，排除 node_modules 等）。报价写动作
 * 归 {@link OrderAppService#submitQuote}。
 *
 * <p>跨 BC 事实（项目名/用户昵称/源码包/externalId 换算）经 project/identity
 * 应用层软引用——与 {@link OrderAppService} 同方向（order → project/identity），
 * 不与 project → {@link OrderQueryAppService} 的读面反向成环。</p>
 */
@Service
public class BackofficeOrderAppService {

    /** 页大小上界（防一次性拉穿；报价清单一屏用不到更大）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final ProjectQueryAppService projectQueryAppService;
    private final ProjectLifecycleAppService projectLifecycleAppService;
    private final AccountAppService accountAppService;

    public BackofficeOrderAppService(OrderRepository orderRepository,
                                     ProjectQueryAppService projectQueryAppService,
                                     ProjectLifecycleAppService projectLifecycleAppService,
                                     AccountAppService accountAppService) {
        this.orderRepository = orderRepository;
        this.projectQueryAppService = projectQueryAppService;
        this.projectLifecycleAppService = projectLifecycleAppService;
        this.accountAppService = accountAppService;
    }

    /**
     * 后台订单清单（四维检索，新单在前）：状态多选（空选＝全量）、创建时间区间
     * （含两端）、下单账号（externalId 入参，服务端换算 accountId）、订单号精确。
     * 过滤在数据库侧完成（Specification），不入内存全量。page 1 基（ADR-0001
     * 分页口径），缺省第 1 页 20 条；排序定死 id 倒序（TSID 时间有序 = 下单新
     * 在前），不开放客户端排序。
     *
     * <p>externalId 换算不到（用户在我方无建档）与非数值订单号都如实返回空清单
     * （200、total 0）——两者都是检索维度上的「无命中」，不是错误；用户没登录过
     * 平台＝确实无单可检。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeOrderSummaryResponse> orders(List<OrderStatus> statuses,
                                                               LocalDateTime createdFrom,
                                                               LocalDateTime createdTo,
                                                               String externalId,
                                                               String orderId,
                                                               int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Long ownerAccountId = null;
        if (externalId != null && !externalId.isBlank()) {
            ownerAccountId = accountAppService.accountIdOf(externalId).orElse(null);
            if (ownerAccountId == null) {
                return emptyPage(safePage, safeSize);
            }
        }
        Long parsedOrderId = parseOrderId(orderId);
        if (orderId != null && !orderId.isBlank() && parsedOrderId == null) {
            return emptyPage(safePage, safeSize);
        }

        BackofficeOrderQuery query = new BackofficeOrderQuery(
                statuses, createdFrom, createdTo, ownerAccountId, parsedOrderId);
        Specification<Order> specification = ConditionSpecifications.fromAnnotation(query);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "id"));
        Page<Order> result = orderRepository.findAll(specification, pageable);
        Map<Long, String> projectNames = projectQueryAppService.namesOf(
                result.getContent().stream().map(Order::getProjectId).toList());
        Map<Long, String> ownerNames = accountAppService.displayNamesOf(
                result.getContent().stream().map(Order::getOwnerAccountId).toList());
        return new PageResponse<>(
                result.getContent().stream()
                        .map(order -> BackofficeOrderSummaryResponse.of(order,
                                projectNames.get(order.getProjectId()),
                                order.getOwnerAccountId() == null ? null
                                        : ownerNames.get(order.getOwnerAccountId())))
                        .toList(),
                result.getTotalElements(),
                safePage,
                safeSize);
    }

    /**
     * 订单号解析（lenient）：订单号＝订单 TSID 十进制字符串，非数值/非正数不可
     * 能命中任何存量单 → 返 null 由调用面短路空清单（寻址语义的 404 不适用于
     * 过滤值，同 externalId 未命中的「无命中」口径）。
     */
    private static Long parseOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(orderId.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static PageResponse<BackofficeOrderSummaryResponse> emptyPage(int page, int size) {
        return new PageResponse<>(List.of(), 0, page, size);
    }

    /**
     * 后台订单详情：报价依据全量——PRD 快照正文、项目名、下单用户昵称、金额
     * 与最新备注、状态时点组。项目名/用户昵称软引用容缺（null 呈现）——订单
     * 及其快照是交易记录，不因关联档缺失而 404（同清单口径）。
     *
     * @throws ApplicationException ORD_001 订单不存在
     */
    public BackofficeOrderDetailResponse detail(Long orderId) {
        Order order = requireOrder(orderId);
        return BackofficeOrderDetailResponse.of(order,
                projectQueryAppService.namesOf(List.of(order.getProjectId()))
                        .get(order.getProjectId()),
                accountAppService.displayNameOf(order.getOwnerAccountId()));
    }

    /**
     * 订单源码包（tar.gz）：源码不快照，交付实时取——经 project 上下文打包
     * （排除 node_modules/.env 等），归档后工作区原样保留即可取件。
     *
     * @throws ApplicationException ORD_001 订单不存在；WSP 打包失败原样透传
     */
    public byte[] sourcePackage(Long orderId) {
        Order order = requireOrder(orderId);
        return projectLifecycleAppService.sourcePackage(order.getProjectId());
    }

    private Order requireOrder(Long orderId) {
        // 带价目历史：详情拼装要读最新备注（价目集合），取齐再出会话
        return orderRepository.findWithHistory(orderId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_NOT_FOUND));
    }
}
