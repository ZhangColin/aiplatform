package com.aieducenter.aiplatform.business.order.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;

/**
 * 订单读面（#28 交易环①）：向 project 上下文供给「未终结订单事实」——项目
 * 详情/列表的嵌入字段（锁定式矩阵与四态过滤的推导输入）与对话区冻结守卫共用。
 * 与 {@link OrderAppService}（写面，依赖 project 读面）分立两 bean，避免
 * project ⇄ order 应用服务互相构造注入成环。
 */
@Service
public class OrderQueryAppService {

    /** 下载支付门放行态（#287）：已支付（归档前即可取）或已归档（终态）。 */
    private static final List<OrderStatus> DOWNLOAD_UNLOCKED_STATUSES =
            List.of(OrderStatus.PAID, OrderStatus.ARCHIVED);

    private final OrderRepository orderRepository;

    public OrderQueryAppService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 项目名下的未终结订单（至多一张，谓词同库侧部分唯一索引；无则空）。
     */
    public Optional<OrderBriefResponse> activeOrderOf(Long projectId) {
        return orderRepository.findActiveByProject(projectId)
                .map(OrderBriefResponse::of);
    }

    /**
     * 冻结守卫（#28「下单即冻结迭代」的判定面）：项目挂着未终结订单即抛
     * ORD_006——对话区意见受理（project 上下文）经此守门，错误语义归订单侧
     * 单点，调用方不触订单 domain 词汇。
     */
    public void requireNoActiveOrder(Long projectId) {
        if (orderRepository.findActiveByProject(projectId).isPresent()) {
            throw new ApplicationException(OrderMessage.ORDER_FROZEN);
        }
    }

    /**
     * 下载支付门守卫（#287，ADR-0027「体验免费、带走才付费」的判定面）：项目
     * 名下<strong>曾有</strong>已支付/已归档订单即放行——已支付是真实中间态
     * （归档前即可取件），已归档是终态；「曾有」按事实查询不取最近一张（迭代
     * 期间已购保持可取）。未支付（无单/待报价/已报价/已取消后未再购）抛
     * ORD_015，门语义如实告知。门只盖用户面下载（单文件＋源码包）；点看/预览
     * 自由，后台运营面不经本守卫。
     */
    public void requireDownloadable(Long projectId) {
        if (!orderRepository.existsByProjectIdAndStatusIn(projectId, DOWNLOAD_UNLOCKED_STATUSES)) {
            throw new ApplicationException(OrderMessage.ORDER_DOWNLOAD_NOT_PAID);
        }
    }

    /**
     * 项目最近一张订单（任意状态，#30）：归档终态项目页的「完整记录」嵌入面——
     * 支付归档后订单转终态、activeOrder 归空，项目详情改挂本嵌入供订单卡取单。
     */
    public Optional<OrderBriefResponse> latestOrderOf(Long projectId) {
        return orderRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .map(OrderBriefResponse::of);
    }

    /**
     * 一批项目 → 未终结订单摘要（项目列表批量嵌入；每项目至多一张，空批入空映射）。
     */
    public Map<Long, OrderBriefResponse> activeOrdersOf(Collection<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        return orderRepository.findByProjectIdInAndStatusNotIn(projectIds, OrderStatus.TERMINAL)
                .stream()
                .collect(Collectors.toMap(Order::getProjectId, OrderBriefResponse::of,
                        (left, right) -> left)); // 唯一索引保证单行，合并函数仅防御
    }
}
