package com.aieducenter.aiplatform.business.order.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;

/**
 * 订单 REST 面（#28 交易环①用户面 + #30 交易环③支付）：确认下单（纯按钮零输入
 * ——读当前 PRD 冻结快照入单）/ 订单详情 / 取消 / mock 支付。后台机机面（清单/
 * 详情/源码包/报价改价）归 {@link BackofficeOrderController}（#29，
 * /api/backoffice/*，五头 HMAC）。路由横跨 /api/projects 与 /api/orders 两前缀
 * （spec API 面定盘），故不设类级 {@code @RequestMapping}、逐方法写全路径。
 */
@RestController
@Validated
@Tag(name = "Orders", description = "订单：确认下单 / 详情 / 取消 / mock 支付")
public class OrderController {

    private final OrderAppService appService;

    public OrderController(OrderAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/api/projects/{projectId}/orders")
    @Operation(summary = "确认下单（冻结 PRD 快照入单）",
            description = "纯按钮零输入：读当前 PRD 全文冻结为订单快照（此后 PRD 修订不影响本单，"
                    + "取消再下 = 新单新快照），待报价起步。下单即冻结迭代——指令区停止受理意见"
                    + "（409 ORD_006），取消订单即解冻回迭代。同项目至多一张未终结订单"
                    + "（重复下单 409 ORD_003，库侧唯一索引兜底）。金额随后台报价落（#29）。"
                    + "项目不存在 404 PRJ_001；PRD 从未产出 409 PRJ_015；项目已归档 409 ORD_004")
    public ApiResponse<OrderResponse> place(@PathVariable String projectId) {
        return ApiResponse.ok(appService.place(OrderIds.parseProject(projectId)));
    }

    @GetMapping("/api/orders/{id}")
    @Operation(summary = "订单详情（用户面）",
            description = "状态（Integer code：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消）"
                    + "+ 报价面（总价/币种/后台备注/改价历史新→旧，#29）+ 下单/取消时点"
                    + "+ 支付/归档时点（#30——已支付为瞬态，paidAt 与 archivedAt 同拍）。"
                    + "订单不存在 404 ORD_001")
    public ApiResponse<OrderResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(OrderIds.parseOrder(id)));
    }

    @PostMapping("/api/orders/{id}/payment")
    @Operation(summary = "mock 支付（确认后同步成功，订单与项目一并归档）",
            description = "v1 平台内模拟支付，只走成功路径（真实渠道接入归 PaymentPort 切换边界，"
                    + "#32）。支付成功在一个事务内完成：订单 已支付→已归档（paidAt/archivedAt/"
                    + "paymentNo 落值）+ 项目归档（ADR-0002）；提交后知识沉淀（取归档时最新 PRD "
                    + "入知识库，失败降级不影响支付）+ 订单态变化 SSE 通知。归档后界面转只读终态"
                    + "（指令区关闭、源码包可取、完整记录含改价历史）。仅已报价（=待支付）态可支付；"
                    + "非待支付 409 ORD_011；订单不存在 404 ORD_001；项目已被手动归档 409 PRJ_013"
                    + "（事务回滚，订单留待支付态）")
    public ApiResponse<OrderResponse> pay(@PathVariable String id) {
        return ApiResponse.ok(appService.pay(OrderIds.parseOrder(id)));
    }

    @PostMapping("/api/orders/{id}/cancel")
    @Operation(summary = "取消订单（未支付态，取消即解冻回迭代）",
            description = "自待报价/已报价可达：取消后项目回迭代态（指令区恢复受理意见），"
                    + "同项目可再下新单（新单重新冻结下单时快照）。已支付或已终结 409 ORD_005；"
                    + "订单不存在 404 ORD_001")
    public ApiResponse<OrderResponse> cancel(@PathVariable String id) {
        return ApiResponse.ok(appService.cancel(OrderIds.parseOrder(id)));
    }
}
