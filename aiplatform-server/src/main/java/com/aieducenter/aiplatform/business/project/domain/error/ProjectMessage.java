package com.aieducenter.aiplatform.business.project.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * Project Context 错误码（前缀 {@code PRJ_}，ADR-0001 注册表既定位）。
 */
public enum ProjectMessage implements CodeMessage {

    PROJECT_NOT_FOUND(404, "PRJ_001", "项目不存在"),

    // PRJ_002 曾是「未知的开发智能体引擎」，随多引擎概念删除注销，码位不复用

    // PRJ_003/004 曾是角色卡解析（阶段默认角色），随主链/任务下发删除注销

    PROJECT_NAME_BLANK(400, "PRJ_005", "项目名不能为空白"),

    PROJECT_FIELDS_INCOMPLETE(400, "PRJ_006", "项目字段不完整"),

    // PRJ_007~PRJ_012 曾是门禁/期/驳回/需求池口径，随主链概念删除注销，码位不复用

    PROJECT_ALREADY_ARCHIVED(409, "PRJ_013", "项目已归档（归档是单向终点）"),

    PROJECT_FILTER_UNKNOWN(400, "PRJ_014", "无效的项目列表状态过滤参数"),

    /** PRD 读端点的「未产出」口径（工作区无 docs/PRD.md）——区别于项目不存在的 PRJ_001。 */
    PRD_NOT_PRODUCED(404, "PRJ_015", "PRD 尚未产出"),

    /** 「开始做系统」重复发起守卫：已生成（调整走指令区意见）或生成在途。 */
    GENERATION_ALREADY_REQUESTED(409, "PRJ_017", "系统已生成或正在生成中，请勿重复发起"),

    /** 「开始做系统」前置事实守卫：PRD 从未产出（待定项未清不设门，无 PRD 除外）。 */
    GENERATION_PRD_NOT_PRODUCED(409, "PRJ_018", "PRD 尚未产出，先和需求分析师聊出 PRD 再开始做系统"),

    /** 修正任务前置事实守卫：系统从未生成（迭代在首次生成完成后才开始）。 */
    FIX_RUN_NOT_GENERATED(409, "PRJ_019", "系统还没做好，等系统生成完成后再提修改意见"),

    /** 文件树浏览面（#27）：路径不可浏览——非工作区锚定形，或属非交付物/机密。 */
    FILE_PATH_INVALID(400, "PRJ_020", "该文件不在可浏览范围"),

    /** 文件树浏览面（#27）：工作区无该文件（或已不是文件）。 */
    FILE_NOT_FOUND(404, "PRJ_021", "文件不存在"),

    /** 文件树浏览面（#27）：超过在线查看大小上限（1 MiB，容器侧拦截不读取）。 */
    FILE_TOO_LARGE(400, "PRJ_022", "文件太大，暂不支持在线查看"),

    /** 文件树浏览面（#27）：非文本文件（正文含 NUL），在线查看只收文本。 */
    FILE_NOT_TEXTUAL(400, "PRJ_023", "该文件不是文本文件，暂不支持在线查看"),

    /** 挂起问答守卫（#40 / ADR-0004）：问答待答期间 /messages 不收新输入，指路作答通道。 */
    QUESTION_PENDING(409, "PRJ_024", "当前有问题待答复，请对问答卡作答后再发送新消息"),

    /** 修正恢复出口守卫（#48）：修正在途（进行中/排队中）无手动恢复面——链自动在跑。 */
    FIX_RESTART_IN_FLIGHT(409, "PRJ_025", "修正正在进行中，无需手动恢复"),

    /** 修正恢复出口守卫（#48）：无超限终态账（未派过/已成功收工/重启丢账），指路重提意见。 */
    FIX_RESTART_UNAVAILABLE(409, "PRJ_026", "没有可恢复的修正，请在指令区重新提意见");

    // PRJ_016 曾是需求确认门谓词，随门概念删除注销

    private final int httpStatus;
    private final String code;
    private final String message;

    ProjectMessage(int httpStatus, String code, String message) {
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
