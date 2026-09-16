package com.aieducenter.aiplatform.base.workspace.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.workspace 错误定义（前缀 WSP_，ADR-0001 注册表）。
 */
public enum WorkspaceMessage implements CodeMessage {

    WORKSPACE_NOT_FOUND(404, "WSP_001", "工作区不存在"),

    ENVIRONMENT_OPERATION_FAILED(500, "WSP_002", "环境后端操作失败"),

    WORKSPACE_ID_INVALID(400, "WSP_004", "工作区标识不合法"),

    WORKSPACE_FIELDS_INCOMPLETE(400, "WSP_005", "工作区字段不完整"),

    RESOURCE_FIELDS_INCOMPLETE(400, "WSP_006", "中间件资源字段不完整"),

    ENVIRONMENT_KIND_NOT_SUPPORTED(400, "WSP_007", "暂不支持的环境类型（Phase A 仅 DEV）"),

    WORKSPACE_STATE_INVALID(400, "WSP_009", "工作区置备状态不合法"),

    WORKSPACE_PROVISION_FAILED(500, "WSP_010", "环境置备失败，需要环境的能力暂不可用"),

    WORKSPACE_PROVISION_TIMEOUT(500, "WSP_011", "环境置备等待超时，请稍后重试"),

    /** #45 渐进预览：应用未起服是待期不是故障——前端轮询续探，非终态口径。 */
    PREVIEW_NOT_SERVING(503, "WSP_012", "预览应用尚未就绪"),

    /** #170 唤醒待期：沙箱置备/唤醒/应用拉起进行中（系统启动中）——非终态口径。 */
    WORKSPACE_STARTING(503, "WSP_013", "系统启动中"),

    // WSP_014 曾是「无效的工作区过滤参数」（#173 后台观测面），随过滤参数绑定失败骑框架（#199）注销，码位不复用

    /** #174 后台动作面：run 在途拒——管理员操作资源面，不打断用户正在进行的生成。 */
    WORKSPACE_ACTION_RUN_IN_FLIGHT(409, "WSP_015", "编码 run 进行中，沙箱动作被拒（先取消 run 或等收口）"),

    /** #174 后台下载面：封存态直取封存包，而包无记录或不可读（深度唤醒同因拒）。 */
    WORKSPACE_SEAL_PACKAGE_UNAVAILABLE(404, "WSP_016", "封存包不存在或不可读"),

    /** #174 后台动作面：触碰自愈/扫描封存等收敛任务在途，独占互斥让路（不排队）。 */
    WORKSPACE_ACTION_BUSY(409, "WSP_017", "沙箱有进行中的收敛任务，请稍后再试");

    private final int httpStatus;
    private final String code;
    private final String message;

    WorkspaceMessage(int httpStatus, String code, String message) {
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
