package com.aieducenter.aiplatform.business.order.application;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.business.identity.application.AccountAppService;
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
 * 按状态分页拉单（报价工作清单）/ 详情（PRD 快照 + 项目名 + 用户昵称）/
 * 源码包（复用 project 上下文打包，排除 node_modules 等）。报价写动作归
 * {@link OrderAppService#submitQuote}。
 *
 * <p>跨 BC 事实（项目名/用户昵称/源码包）经 project/identity 应用层软引用——
 * 与 {@link OrderAppService} 同方向（order → project/identity），不与
 * project → {@link OrderQueryAppService} 的读面反向成环。</p>
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
     * 后台订单清单（按状态过滤，新单在前）：page 1 基（ADR-0001 分页口径），
     * 缺省第 1 页 20 条；排序定死 id 倒序（TSID 时间有序 = 下单新在前），
     * 不开放客户端排序。
     */
    public PageResponse<BackofficeOrderSummaryResponse> orders(OrderStatus status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "id"));
        Page<Order> result = status == null
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        Map<Long, String> projectNames = projectQueryAppService.namesOf(
                result.getContent().stream().map(Order::getProjectId).toList());
        return new PageResponse<>(
                result.getContent().stream()
                        .map(order -> BackofficeOrderSummaryResponse.of(order,
                                projectNames.get(order.getProjectId())))
                        .toList(),
                result.getTotalElements(),
                safePage,
                safeSize);
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
