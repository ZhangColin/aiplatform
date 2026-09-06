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

    /** 生成重复发起守卫：已生成（调整走对话区意见）或生成在途。 */
    GENERATION_ALREADY_REQUESTED(409, "PRJ_017", "系统已生成或正在生成中，请勿重复发起"),

    /** 生成前置事实守卫：PRD 从未产出（待定项未清不设门，无 PRD 除外；#101 生成无门后兜直连调用）。 */
    GENERATION_PRD_NOT_PRODUCED(409, "PRJ_018", "PRD 尚未产出，先在对话区把需求聊出 PRD，系统会随后自动生成"),

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

    /** 挂起问答守卫（#40 / ADR-0005）：问答待答期间 /messages 不收新输入，指路作答通道。 */
    QUESTION_PENDING(409, "PRJ_024", "当前有问题待答复，请对问答卡作答后再发送新消息"),

    /** 修正恢复出口守卫（#48）：修正在途（进行中/排队中）无手动恢复面——链自动在跑。 */
    FIX_RESTART_IN_FLIGHT(409, "PRJ_025", "修正正在进行中，无需手动恢复"),

    /** 修正恢复出口守卫（#48）：无超限终态账（未派过/已成功收工/重启丢账），指路重提意见。 */
    FIX_RESTART_UNAVAILABLE(409, "PRJ_026", "没有可恢复的修正，请在对话区重新提意见"),

    /** 权限作答守卫（#83）：确认不存在或已落定（过期卡/平台重启丢账），指路刷新。 */
    PERMISSION_ANSWER_STALE(409, "PRJ_027", "该确认已失效（运行已收口或平台已重启），请刷新查看最新状态"),

    /** 版本详情守卫（#91）：ref 不是成版 commit（含非 hash 形态——用户可控入参不进 shell）。 */
    VERSION_NOT_FOUND(404, "PRJ_028", "版本不存在"),

    /** 查看当时并发上限（#92）：同项目并发快照会话数达上限（ADR 0007 建议 ≤2）。 */
    VERSION_VIEW_LIMIT(409, "PRJ_029", "同时查看的版本过多，请先关闭一个再查看"),

    /** 查看会话关闭守卫（#92）：viewId 不在在途会话内（已关闭/平台重启丢账）。 */
    VERSION_VIEW_NOT_FOUND(404, "PRJ_030", "该查看会话不存在或已关闭"),

    /** 回滚并发守卫（#100）：编码 run 在途（生成/修正）——回滚会丢其在途未提交改动。 */
    VERSION_ROLLBACK_RUN_IN_FLIGHT(409, "PRJ_031", "系统正在更新，请稍后再回滚"),

    /** 回滚脏树守卫（#100）：工作树有未提交 tracked 改动（如失败 run 残留）——restore 会静默丢弃。 */
    VERSION_ROLLBACK_DIRTY_TREE(409, "PRJ_032", "系统有未完成的改动，请稍后再回滚");

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
