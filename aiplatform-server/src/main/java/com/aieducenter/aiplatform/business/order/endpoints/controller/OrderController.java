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
 * 订单 REST 面（#28 交易环①用户面）：确认下单（纯按钮零输入——读当前 PRD
 * 冻结快照入单）/ 订单详情 / 取消。后台面（报价/改价/源码包）归报价切片
 * （#29，/api/backoffice/*）。路由横跨 /api/projects 与 /api/orders 两前缀
 * （spec API 面定盘），故不设类级 {@code @RequestMapping}、逐方法写全路径。
 */
@RestController
@Validated
@Tag(name = "Orders", description = "订单：确认下单 / 详情 / 取消")
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
                    + "（重复下单 409 ORD_003，库侧唯一索引兜底）。金额随报价落（#29）。"
                    + "项目不存在 404 PRJ_001；PRD 从未产出 409 PRJ_015；项目已归档 409 ORD_004")
    public ApiResponse<OrderResponse> place(@PathVariable String projectId) {
        return ApiResponse.ok(appService.place(OrderIds.parseProject(projectId)));
    }

    @GetMapping("/api/orders/{id}")
    @Operation(summary = "订单详情（用户面）",
            description = "状态（Integer code：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消）"
                    + "+ 下单/取消时点；金额与价目留痕随报价切片（#29）增补。"
                    + "订单不存在 404 ORD_001")
    public ApiResponse<OrderResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(OrderIds.parseOrder(id)));
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
