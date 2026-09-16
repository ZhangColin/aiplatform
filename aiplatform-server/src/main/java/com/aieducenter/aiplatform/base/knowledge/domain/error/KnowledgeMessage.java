package com.aieducenter.aiplatform.base.knowledge.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.knowledge 错误定义（前缀 KNW_，ADR-0001 注册表既定位）。
 */
public enum KnowledgeMessage implements CodeMessage {

    KNOWLEDGE_SPEC_FIELDS_INCOMPLETE(400, "KNW_001", "知识素材字段不完整"),

    KNOWLEDGE_QUERY_REQUIRED(400, "KNW_002", "检索 query 不能为空"),

    KNOWLEDGE_TOP_K_INVALID(400, "KNW_003", "topK 必须为正数"),

    KNOWLEDGE_PROJECT_ID_REQUIRED(400, "KNW_004", "projectId 不能为空"),

    KNOWLEDGE_MATERIAL_NOT_FOUND(404, "KNW_005", "知识素材不存在"),

    KNOWLEDGE_OPERATOR_REQUIRED(400, "KNW_006", "操作者不能为空");

    // KNW_007 曾是「无效的知识素材过滤参数」，随过滤参数绑定失败骑框架（#199）注销，码位不复用

    private final int httpStatus;
    private final String code;
    private final String message;

    KnowledgeMessage(int httpStatus, String code, String message) {
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
