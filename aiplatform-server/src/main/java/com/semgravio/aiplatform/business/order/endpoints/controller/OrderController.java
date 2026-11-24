package com.semgravio.aiplatform.business.order.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.order.application.ContractAppService;
import com.semgravio.aiplatform.business.order.application.DeliveryStatusAppService;
import com.semgravio.aiplatform.business.order.application.OrderAppService;
import com.semgravio.aiplatform.business.order.application.dto.response.ContractResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.DeliveryStatusResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.OrderResponse;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.support.Tsid;

/**
 * 订单 REST 面（#28 交易环①用户面 + #336 合同确认与预览）：下单（过确认门
 * ——PRD 已产出＋已确认用户面版本〔#629，旧 PRD/Demo 版本表随 #630 退场〕，
 * 冻结当前 PRD 全文冗余拷贝＋单一版本号入单）/ 订单详情（含范围文本三段）/ 取消 /
 * 确认范围并准备合同（范围快照冻结·合同创建口）/ 合同预览（订单名下最近合同）。
 * #611 设计线退役后设计资产包下载端点退场（交付物只剩系统单，取件走源码包，
 * 归后台面）。后台机机面（清单/详情/源码包/报价改价/范围录入）归
 * {@link BackofficeOrderController}（#29，/api/backoffice/*，五头 HMAC）；
 * 合同承接面归 {@link BackofficeContractController}；付款面（计划查询/凭证上传）
 * 归 {@link OrderPaymentController}。mock 支付端点已随 #337 退役（支付语义并入
 * 付款节点凭证核验路径——不留两轨并存）。路由横跨 /api/projects 与
 * /api/orders 两前缀（spec API 面定盘），故不设类级
 * {@code @RequestMapping}、逐方法写全路径。
 */
@RestController
@Validated
@Tag(name = "Orders", description = "订单：下单 / 详情 / 取消 / 确认范围并准备合同 / 合同预览")
public class OrderController {

    private final OrderAppService appService;
    private final ContractAppService contractAppService;
    private final DeliveryStatusAppService deliveryStatusAppService;

    public OrderController(OrderAppService appService, ContractAppService contractAppService,
            DeliveryStatusAppService deliveryStatusAppService) {
        this.appService = appService;
        this.contractAppService = contractAppService;
        this.deliveryStatusAppService = deliveryStatusAppService;
    }

    @GetMapping("/api/projects/{projectId}/delivery-status")
    @Operation(summary = "交付状态（前台六态纯推导，#338）",
            description = "项目维度一次读齐订单＋合同＋付款＋生产承接四源现算的交付生命周期视图"
                    + "（不存储）。六态（Integer code）：1=待签约（合同未签署完成——无/草稿/"
                    + "签署中/旁落终态待重建）2=待付款（合同已签、开工门节点未全到账）"
                    + "3=款项核验中（有节点处线下转账待核验）4=待安排生产（开工条件满足＝"
                    + "已支付〔新语义〕，承接待排期；无承接的遗留已支付单同归此态）5=生产中"
                    + "（承接已推进）6=需用户补充（项目级标志盖帽——needsUserSupplement "
                    + "true 同报，处理入口在对话区）。state null＝待报价（六态不覆盖，订单面"
                    + "「待确认报价」占位态照旧）；handoff null＝承接未建。订单终态不进本口"
                    + "（订单面按终态呈现）。项目不存在 404 PRJ_001；无未终结订单 404 ORD_001"
                    + "（前端缺席＝不渲染卡片）")
    @ErrorCodes({"PRJ_001", "ORD_001"})
    public ApiResponse<DeliveryStatusResponse> deliveryStatus(@PathVariable String projectId) {
        return ApiResponse.ok(deliveryStatusAppService.statusOf(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @PostMapping("/api/projects/{projectId}/orders")
    @Operation(summary = "下单（过确认门冻结 PRD 快照与已确认用户面版本入单）",
            description = "纯按钮零输入：下单前置门＝PRD 已产出（缺 PRD 拦截 ORD_018）＋存在"
                    + "已确认用户面版本（「确认此版本，准备签约」#629；缺确认拦截 ORD_019），"
                    + "过门即冻结当前工作区 PRD 全文冗余拷贝＋已确认用户面版本号入单（此后"
                    + " PRD 修订不影响本单，取消再下 = 新单新引用；冻结字段结构与既有订单"
                    + "数据不动），待报价起步；交付物类型一律为系统单（#611 设计线退役后"
                    + "只有系统一种，交付物为源码包）。旧 PRD/Demo 版本表已随 #630 退场，"
                    + "新单只写单一已确认版本号；历史单的旧双版本引用照读。"
                    + "下单即冻结迭代——对话区停止受理意见（409 ORD_006），"
                    + "取消订单即解冻回迭代。同项目至多一张"
                    + "未终结订单（重复下单 409 ORD_003，库侧唯一索引兜底）。金额随后台报价落"
                    + "（#29）。项目不存在 404 PRJ_001；无 PRD 快照 409 ORD_018；无已确认"
                    + "用户面版本 409 ORD_019；项目已归档 409 ORD_004")
    public ApiResponse<OrderResponse> place(@PathVariable String projectId) {
        return ApiResponse.ok(appService.place(Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @GetMapping("/api/orders/{id}")
    @Operation(summary = "订单详情（用户面）",
            description = "状态（Integer code：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消）"
                    + "+ 报价面（总价/币种/后台备注/改价历史新→旧，#29）+ 范围文本三段"
                    + "（#336 后台录入工作副本——「核对范围与报价」子步骤的确认面）"
                    + "+ 下单/取消时点 + 支付/归档时点（#338 起已支付＝开工条件满足的真实"
                    + "中间态，paidAt 先落、archivedAt 归档步骤后落）。订单不存在 404 ORD_001")
    public ApiResponse<OrderResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND)));
    }

    @PostMapping("/api/orders/{id}/confirm-scope")
    @Operation(summary = "确认范围并准备合同（范围快照冻结，合同创建口）",
            description = "三子步骤「核对范围与报价」的确认动作（#336）：确认即冻结报价版本引用"
                    + "＋范围文本不可变副本（范围快照），同刻创建合同（准备中起步，编号 SG- "
                    + "风格全局唯一）——后台人工推进六态、双方签署位未签起步。前置门：尚无报价"
                    + " 409 ORD_025（后台落定报价后再确认）；范围文本未录入 409 ORD_026；已有"
                    + "活跃合同 409 ORD_024；订单非活跃报价态 409 ORD_021。确认后改价/改范围不"
                    + "回写快照——草稿合同转「需更新」标志，由后台重录重新冻结。订单不存在"
                    + " 404 ORD_001")
    @ErrorCodes({"ORD_001", "ORD_021", "ORD_024", "ORD_025", "ORD_026"})
    public ApiResponse<ContractResponse> confirmScope(@PathVariable String id) {
        return ApiResponse.ok(contractAppService.confirmScope(
                Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND)));
    }

    @GetMapping("/api/orders/{id}/contract")
    @Operation(summary = "合同预览（订单名下最近合同）",
            description = "合同预览卡取数面（#336 最小呈现）：预览字段集（双方主体/项目范围/交付物"
                    + "〔范围快照·交付清单〕/验收标准〔范围快照·验收方式〕/费用〔冻结金额〕/"
                    + "付款计划/交期约定〔范围快照·工期口径〕/合同版本）＋六态（1=准备中 "
                    + "2=待核对与签署 3=签署进行中 4=签署完成 5=签署失败或过期 6=已撤回或作废）"
                    + "＋双方签署位＋「需更新」派生标志（改价/改范围后待后台重录）。活跃合同在场"
                    + "即最新；终态合同永不物理删除（签署完成后仍可见）。订单名下无合同 404 "
                    + "ORD_020（前端缺席＝不渲染卡片）。订单不存在 404 ORD_001")
    @ErrorCodes({"ORD_001", "ORD_020"})
    public ApiResponse<ContractResponse> contract(@PathVariable String id) {
        return ApiResponse.ok(contractAppService.latestByOrder(
                Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND)));
    }

    @PostMapping("/api/orders/{id}/cancel")
    @Operation(summary = "取消订单（未支付态，取消即解冻回迭代）",
            description = "自待报价/已报价可达：取消后项目回迭代态（对话区恢复受理意见），"
                    + "同项目可再下新单（新单重新冻结下单时快照）。已支付或已终结 409 ORD_005；"
                    + "订单不存在 404 ORD_001")
    public ApiResponse<OrderResponse> cancel(@PathVariable String id) {
        return ApiResponse.ok(appService.cancel(Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND)));
    }
}
