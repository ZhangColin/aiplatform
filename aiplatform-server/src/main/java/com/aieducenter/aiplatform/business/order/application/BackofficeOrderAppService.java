package com.aieducenter.aiplatform.business.order.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.specification.ConditionSpecifications;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.business.identity.application.AccountAppService;
import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountBriefResponse;
import com.aieducenter.aiplatform.business.order.application.dto.query.BackofficeOrderQuery;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderDetailResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderSummaryResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 后台订单读面（#29 交易环②，/api/backoffice/* 机机签名四端点的三读端点）：
 * 四维检索分页拉单（运营工作清单，#156 扩）/ 详情（PRD 快照 + 项目名 + 下单
 * 账号摘要）/ 源码包（复用 project 上下文打包，排除 node_modules 等）。报价写
 * 动作归 {@link OrderAppService#submitQuote}。
 *
 * <p>跨 BC 事实（项目名/账号摘要/源码包/externalId 换算）经 project/identity
 * 应用层软引用——与 {@link OrderAppService} 同方向（order → project/identity），
 * 不与 project → {@link OrderQueryAppService} 的读面反向成环。</p>
 */
@Service
public class BackofficeOrderAppService {

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
     * 过滤在数据库侧完成（Specification），不入内存全量。分页钳制/换算全部来自
     * 框架 {@link Pagination}（1 基、缺省 1/20、上界 100 静默贴边）；排序定死
     * id 倒序（TSID 时间有序 = 下单新在前），客户端 sort 被 withSort 覆盖静默
     * 忽略，不开放客户端排序。
     *
     * <p>externalId 换算不到（用户在我方无建档）与非数值订单号都如实返回空清单
     * （200、total 0，页码原样回显）——两者都是检索维度上的「无命中」，不是错误；
     * 用户没登录过平台＝确实无单可检。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeOrderSummaryResponse> orders(List<OrderStatus> statuses,
                                                               LocalDateTime createdFrom,
                                                               LocalDateTime createdTo,
                                                               String externalId,
                                                               String orderId,
                                                               Pagination pagination) {
        Long ownerAccountId = null;
        if (externalId != null && !externalId.isBlank()) {
            ownerAccountId = accountAppService.accountIdOf(externalId).orElse(null);
            if (ownerAccountId == null) {
                return PageResponse.empty(pagination);
            }
        }
        Long parsedOrderId = Tsid.parseOrNull(orderId);
        if (orderId != null && !orderId.isBlank() && parsedOrderId == null) {
            return PageResponse.empty(pagination);
        }

        BackofficeOrderQuery query = new BackofficeOrderQuery(
                statuses, createdFrom, createdTo, ownerAccountId, parsedOrderId);
        Specification<Order> specification = ConditionSpecifications.fromAnnotation(query);
        Pageable pageable = pagination.toPageRequest()
                .withSort(Sort.by(Sort.Direction.DESC, "id"));
        Page<Order> result = orderRepository.findAll(specification, pageable);
        Map<Long, String> projectNames = projectQueryAppService.namesOf(
                result.getContent().stream().map(Order::getProjectId).toList());
        Map<Long, AccountBriefResponse> owners = accountAppService.briefsOf(
                result.getContent().stream().map(Order::getOwnerAccountId).toList());
        return PageResponse.of(result.map(order -> BackofficeOrderSummaryResponse.of(order,
                projectNames.get(order.getProjectId()),
                order.getOwnerAccountId() == null ? null
                        : owners.get(order.getOwnerAccountId()))));
    }

    /**
     * 后台订单详情：报价依据全量——PRD 快照正文、项目名、下单账号摘要
     * （externalId＋昵称）、金额与最新备注、状态时点组。项目名/账号摘要软引用
     * 容缺（null 呈现）——订单及其快照是交易记录，不因关联档缺失而 404
     * （同清单口径）。
     *
     * @throws ApplicationException ORD_001 订单不存在
     */
    public BackofficeOrderDetailResponse detail(Long orderId) {
        Order order = requireOrder(orderId);
        return BackofficeOrderDetailResponse.of(order,
                projectQueryAppService.namesOf(List.of(order.getProjectId()))
                        .get(order.getProjectId()),
                accountAppService.briefOf(order.getOwnerAccountId()));
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
