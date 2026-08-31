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
    FIX_RUN_NOT_GENERATED(409, "PRJ_019", "系统还没做好，等系统生成完成后再提修改意见");

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
