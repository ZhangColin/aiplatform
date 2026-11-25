package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 模型配置＝语义键（ADR-0031，{@code prj_model_configs} 一行）：模型配置域概念，
 * 与 agentscope 框架 callOptions 的 modelConfig（重试旋钮族，#555）无涉——字面
 * 撞车纯阅读面，两类对象零交集。运营短名
 * （flash/pro）指向实际模型名＋所属模型供应商。模型版本升级
 * ＝改一处指向、全部引用自动跟上；
 * 被智能体运营配置（两座 model_config_key）与边缘调用点引用，引用值＝
 * {@link #configKey()}——后台改绑定时即时校验配置键存在且启用（#367，错值
 * 写不进去，不再靠留痕回滚兜底）。
 *
 * @param configKey         语义键（运营短名，引用腿）
 * @param providerKey     所属通道键（模型串前缀腿）
 * @param modelName       实际模型名（如 deepseek-v4-flash-ga-260731）
 * @param displayName     运营显示名（后台绑定下拉的人话标签；null＝回落语义键）
 * @param enabled         启用态；停用＝引用它的装配明确报错、新绑定被即时校验拒绝
 * @param thinkingEnabled 深度思考开关（#370：模型行为属性跟配置/模型走；缺省
 *                        false＝显式关——不配置不默认拖慢首 token）
 * @param operatorId      最近写者 id（admin 侧 TSID；null＝种子/迁移写入）
 * @param operatorName    最近写者名（直读展示）
 * @param updatedAt       最近写入时刻（读模型列——回读填充；写路径构造时 null）
 */
public record ModelConfig(String configKey, String providerKey, String modelName, String displayName,
        boolean enabled, boolean thinkingEnabled, String operatorId, String operatorName,
        LocalDateTime updatedAt) {
}
