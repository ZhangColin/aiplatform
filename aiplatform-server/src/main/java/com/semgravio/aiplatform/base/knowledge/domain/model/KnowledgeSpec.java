package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 知识入库素材（A5 §2）：一次 index 调用 = 一份素材的分块全集。
 *
 * <p>调用方（业务编排）完成采集与分块——「哪个阶段产出什么、按什么粒度切」是业务
 * 知识（B0 蓝图 §1）；底座只按 {@code (kind, sourceRef)} 幂等落库。kind 对底座
 * 不透明（业务口径＝PRD 成交沉淀 #30，#465 起＋激活升格的沉淀物 CASE/MODULE/
 * RULE；旧五类中的 QA/FEEDBACK/TEST_REPORT/BUG 随旧主链出局），meta 透传存储
 * （底座不解释）。</p>
 *
 * @param kind        素材类别（幂等键之一；业务口径＝PRD/CASE/MODULE/RULE）
 * @param sourceRef   素材来源标识（幂等键之一）
 * @param projectId   归属项目 id（级联清理入口；<b>可空＝孤儿沉淀</b>——#465 激活
 *                    升格与来源项目脱钩，NULL 行不被项目级联命中）
 * @param projectName 来源项目名（命中条目展示；孤儿沉淀＝平台侧固定展示名）
 * @param title       素材标题（命中条目展示）
 * @param chunks      分块全集（一次入库整体替换旧块）
 * @param meta        扩展元数据（可空）
 */
public record KnowledgeSpec(
        String kind,
        String sourceRef,
        String projectId,
        String projectName,
        String title,
        List<String> chunks,
        Map<String, Object> meta) {
}
