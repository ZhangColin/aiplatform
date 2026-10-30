package com.semgravio.aiplatform.business.order.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * Order Context 错误码（前缀 {@code ORD_}，#4 决议注册表位）。
 */
public enum OrderMessage implements CodeMessage {

    ORDER_NOT_FOUND(404, "ORD_001", "订单不存在"),

    ORDER_FIELDS_INCOMPLETE(400, "ORD_002", "订单字段不完整"),

    ORDER_ALREADY_ACTIVE(409, "ORD_003", "该项目已有未终结订单（取消或完成后再下单）"),

    ORDER_PROJECT_ARCHIVED(409, "ORD_004", "项目已归档，无法下单（新需求请新建项目）"),

    ORDER_CANCEL_NOT_ALLOWED(409, "ORD_005", "订单已支付或已终结，无法取消"),

    ORDER_FROZEN(409, "ORD_006", "订单处理中——如需继续修改，请取消订单"),

    ORDER_QUOTE_NOT_ALLOWED(409, "ORD_007", "订单已支付或已终结，无法报价或改价"),

    ORDER_QUOTE_AMOUNT_INVALID(400, "ORD_008", "报价金额无效（须为正整数，单位分）"),

    ORDER_QUOTE_NOTE_TOO_LONG(400, "ORD_009", "报价备注超长（至多 1000 字）"),

    // ORD_010 曾是「无效的订单过滤参数」，随过滤参数绑定失败骑框架（#199）注销，码位不复用

    ORDER_PAY_NOT_ALLOWED(409, "ORD_011", "订单非待支付状态，无法支付"),

    ORDER_ARCHIVE_NOT_ALLOWED(409, "ORD_012", "订单非已支付状态，无法归档"),

    ORDER_CANCEL_REASON_REQUIRED(400, "ORD_013", "取消原因必填（运营取消须填写原因）"),

    ORDER_CANCEL_REASON_TOO_LONG(400, "ORD_014", "取消原因超长（至多 1000 字）"),

    /** 下载支付门守卫（#287，ADR-0027「平台上随便体验、带走才付费」）：项目名下从未出现已支付/已归档订单——门只盖用户面下载（单文件＋源码包＋设计资产包），点看/预览自由、后台运营面不受门。 */
    ORDER_DOWNLOAD_NOT_PAID(402, "ORD_015", "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付"),

    /** 设计资产包取件的类型面（#297）：系统单交付物不含设计资产包（源码包实时取）——订单寻址成功而交付物类型不符，如实 404 不假装有包。 */
    ORDER_DESIGN_PACKAGE_NOT_DELIVERABLE(404, "ORD_016", "该订单的交付物不含设计资产包（系统单交付物是源码包）"),

    /**
     * 签约门（#328）：未登录绑定（访客身份）走下单入口被拦——签约前置＝账号绑定
     * （M01 登录与项目绑定），联系人/签约主体顺势收集。前端认本码弹 M01；
     * 绑定后重试放行（账户会话在场即过闸）。
     */
    ORDER_BINDING_REQUIRED(403, "ORD_017", "签约前请先登录绑定账号（保存项目可跨设备继续并签署合同）"),

    /**
     * 下单前置门·PRD 腿（#335，spec #320 ④「加门」）：名下无已确认 PRD 版本
     * （prj_prd_versions 无行——确认制前的遗留项目）不可下单——如实拦截并指路
     * 确认动作（按钮语对齐正本「确认此版 PRD 并生成 Demo」），前端 toast 提示
     * （拦截与提示）。
     */
    ORDER_PRD_VERSION_REQUIRED(409, "ORD_018", "下单前请先确认 PRD 版本（点「确认此版 PRD 并生成 Demo」后再下单）"),

    /**
     * 下单前置门·Demo 腿（#335，「确认此版本，准备签约」＝下单入口）：项目无
     * Demo 确认版本（prj_projects 确认指针空）不可下单——如实拦截并指路确认
     * 动作（Demo 版本上的确认标记，#333 选定动作）。
     */
    ORDER_DEMO_VERSION_REQUIRED(409, "ORD_019", "下单前请先确认 Demo 版本（「确认此版本，准备签约」后再下单）"),

    /** 合同寻址面（#336）：订单名下无合同（用户面合同预览卡缺席＝不渲染，如实 404 不假装有合同）。 */
    ORDER_CONTRACT_NOT_FOUND(404, "ORD_020", "合同不存在"),

    /** 状态机守卫（#336）：非法迁移/非法态动作统一拒绝——推进到头、终态再动、签署标记不在签署进行中、重录不在草稿态等。 */
    CONTRACT_ACTION_NOT_ALLOWED(409, "ORD_021", "合同当前状态不允许该操作"),

    /** 迁移依据必填（#336）：后台人工推进/撤回/作废/签署标记/重录的留痕依据＝必填自由文本。 */
    CONTRACT_BASIS_REQUIRED(400, "ORD_022", "操作依据必填（人工推进须填写依据留痕）"),

    CONTRACT_BASIS_TOO_LONG(400, "ORD_023", "操作依据超长（至多 1000 字）"),

    /** 活跃唯一（#336）：同订单至多一份活跃（非终态）合同——重复创建/确认拒（预检或并发撞索引同译）。 */
    CONTRACT_ALREADY_ACTIVE(409, "ORD_024", "该订单已有活跃合同（至多一份非终态合同）"),

    /** 确认范围前置·报价腿（#336）：「确认范围并准备合同」须后台已落定报价（范围快照冻结报价版本引用）。 */
    CONTRACT_QUOTE_REQUIRED(409, "ORD_025", "尚无报价，无法确认范围（后台落定报价后再确认）"),

    /** 确认范围前置·范围腿（#336）：范围文本（交付清单/验收方式/工期口径）未录入不可确认。 */
    CONTRACT_SCOPE_REQUIRED(409, "ORD_026", "范围文本未录入（后台录入交付清单/验收方式/工期口径后再确认）"),

    /** 签署完成守卫（#336）：「签署完成」＝双方签署位均已落签——缺任一方不可推进。 */
    CONTRACT_BOTH_PARTIES_NOT_SIGNED(409, "ORD_027", "双方尚未全部签署，不能标记签署完成"),

    /** 签署位幂等守卫（#336）：该方已落签，重复标记拒。 */
    CONTRACT_PARTY_ALREADY_SIGNED(409, "ORD_028", "该方已签署（签署位已落）"),

    /** 范围录入校验（#336）：三段（交付清单/验收方式/工期口径）必填——整组替换语义不收半组。 */
    ORDER_SCOPE_INCOMPLETE(400, "ORD_029", "范围文本三段必填（交付清单/验收方式/工期口径）"),

    ORDER_SCOPE_TOO_LONG(400, "ORD_030", "范围文本超长（每段至多 2000 字）"),

    /** 重录窗口（#336）：内容重录仅限草稿态（准备中/待核对与签署）——签署进行中起走撤回/作废重建。 */
    CONTRACT_RE_RECORD_NOT_ALLOWED(409, "ORD_031", "内容重录仅限草稿态（签署进行中起走撤回/作废重建）"),

    /** 合同预览字段长度（#336）：主体两段至多 200 字、范围/付款计划至多 2000 字。 */
    CONTRACT_CONTENT_TOO_LONG(400, "ORD_032", "合同内容字段超长（主体至多 200 字，其余至多 2000 字）"),

    /** 范围录入守卫（#336）：已支付/已终结订单不再受理范围录入（同报价守卫口径）。 */
    ORDER_SCOPE_NOT_ALLOWED(409, "ORD_033", "订单已支付或已终结，无法录入范围"),

    /**
     * 合同动作操作者必填（#336）：合同动作是人工推进、留痕行以操作人为追责
     * 正身——缺操作者身份（无 X-User-Id 透传头）如实拒绝，不落无名留痕（与
     * 订单写口 #155 落空口径有意分立：那是状态变更的顺手落痕，这是审计本体）。
     */
    CONTRACT_OPERATOR_REQUIRED(400, "ORD_035", "操作者必填（合同人工动作须携带操作者身份）"),

    /** 付款计划寻址面（#337）：订单名下无付款计划（尚无合同即无计划——用户面付款面板缺席＝不渲染，如实 404）。 */
    PAYMENT_PLAN_NOT_FOUND(404, "ORD_036", "付款计划不存在"),

    /** 付款节点寻址面（#337）：节点不存在或不属活跃计划（旧计划节点随旧合同留痕、不可动作）。 */
    PAYMENT_NODE_NOT_FOUND(404, "ORD_037", "付款节点不存在"),

    /** 节点状态守卫（#337）：非法态动作统一拒绝——已到账再传凭证、非待核验态核验/打回、已终结订单上传等。 */
    PAYMENT_ACTION_NOT_ALLOWED(409, "ORD_038", "付款节点当前状态不允许该操作"),

    /** 节点录入校验（#337）：整组录入至少一条、名称必填（至多 200 字）、金额为正整数（分）。 */
    PAYMENT_NODES_INVALID(400, "ORD_039", "付款节点录入无效（至少一条、名称必填、金额为正）"),

    /** 凭证文件校验（#337）：只收 pdf/png/jpg/jpeg、单文件 ≤10MB（如实报错不静默压缩；multipart 容器层超限同归此码）。 */
    PAYMENT_VOUCHER_INVALID(400, "ORD_040", "凭证文件无效（支持 PDF/PNG/JPG，至多 10MB）"),

    /** 核验录入校验（#337）：实到金额须为正整数（分）、实到时间必填。 */
    PAYMENT_VERIFY_ENTRY_INVALID(400, "ORD_041", "核验录入无效（实到金额须为正、实到时间必填）"),

    /** 打回依据必填（#337）：打回是凭证主张的否定结论——依据留痕是审计正身，空白拒绝。 */
    PAYMENT_REJECT_BASIS_REQUIRED(400, "ORD_042", "打回依据必填（人工核验打回须填写依据留痕）"),

    /** 核验动作操作者必填（#337，口径同 ORD_035）：核验/打回是审计本体动作——不落无名留痕。 */
    PAYMENT_OPERATOR_REQUIRED(400, "ORD_043", "操作者必填（付款核验动作须携带操作者身份）"),

    /** 节点重录窗口（#337）：支付活动已开始（有凭证或核验留痕）不可重新录入——变动走合同侧撤回重建（新计划）。 */
    PAYMENT_NODES_LOCKED(409, "ORD_044", "付款节点已开始支付，不可重新录入"),

    /** 凭证寻址面（#337）：核验录入的关联凭证不存在或不属该节点（证据链引用校验，如实 404）。 */
    PAYMENT_VOUCHER_NOT_FOUND(404, "ORD_045", "凭证不存在或不属于该节点"),

    /** 付款字段长度（#337）：渠道流水号至多 100 字、依据/备注至多 1000 字（付款域专用，不混用合同依据码）。 */
    PAYMENT_FIELD_TOO_LONG(400, "ORD_046", "付款字段超长（渠道流水号至多 100 字，依据至多 1000 字）"),

    /**
     * 取消门收窄守卫（#338，spec #320 ④「取消边界收窄」）：最近合同已签署完成
     * ——取消关门，需求变化走对话区变更沟通（不建平台机制）。应用层守卫（聚合
     * 读不到合同）；非未支付态先归聚合 ORD_005（既有语义正身，先到先得）。
     */
    ORDER_CANCEL_SIGNED(409, "ORD_047", "合同已签署完成，订单无法取消；需求变化请在对话区沟通");

    private final int httpStatus;
    private final String code;
    private final String message;

    OrderMessage(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
