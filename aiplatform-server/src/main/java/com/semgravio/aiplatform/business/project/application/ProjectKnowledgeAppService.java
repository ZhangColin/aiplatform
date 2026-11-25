package com.semgravio.aiplatform.business.project.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeChunker;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeRetrieval;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识沉淀编排（业务侧缝）：入库/检索/清理/留痕的存储语义归 base.knowledge
 * （{@link KnowledgePort}），「什么时机沉淀什么素材」是业务知识，在此落定。
 * 沉淀触发点 = 每笔成交自动沉淀 PRD（交易环，#30）；知识命中的唯一作用点 =
 * 主智能体会话建立尾注（需求环，#19/#639——生成/修正下发前置注入已退场，
 * 生成执行体只读当前项目需求文档）。#465 起检索面为多类同池混检（PRD＋激活
 * 升格的 CASE/MODULE/RULE 沉淀物），#466 起注入块命中条带来源 kind 区分。
 *
 * <p><b>降级契约</b>：摄取/清理/命中检索/留痕一律失败不炸——记日志跳过、降级
 * 为空注入不阻断主流程；embedding 不可用由底座先行降级（旧块保留/检索降级）。</p>
 */
@Service
@Slf4j
public class ProjectKnowledgeAppService {

    /** 命中检索 query 上限（bge-small-zh 512 维模型，超长截断）。 */
    static final int MAX_QUERY_CHARS = 2000;

    /** 沉淀素材类别（幂等键之一；v1 唯一业务口径 = 成交项目 PRD）。 */
    static final String KIND_PRD = "PRD";

    /** 沉淀条目标题（命中条目展示：〔来源｜类别〕标题 + 片段）。 */
    static final String TITLE_PRD = "PRD";

    /**
     * 命中条目类别展示名（#466 命中条带来源 kind 区分）：PRD＝历史需求（成交
     * 项目归档，业务侧类别），CASE/MODULE/RULE＝激活升格的沉淀物（孤儿，来源恒
     * 「平台沉淀」；键形单源 {@link SedimentKind#key()}）。kind 开放（#451）——
     * 未知类别不映射，原键透传（新类生长零改注入面）。插入序即块头枚举序。
     */
    private static final Map<String, String> KIND_LABELS = kindLabels();

    private static Map<String, String> kindLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(KIND_PRD, "历史需求");
        labels.put(SedimentKind.CASE.key(), "需求案例");
        labels.put(SedimentKind.MODULE.key(), "功能模块");
        labels.put(SedimentKind.RULE.key(), "业务规则");
        return Collections.unmodifiableMap(labels);
    }

    /**
     * 块头类别枚举行（#466 多类同池命中的展示面）：随 {@link #KIND_LABELS}
     * 插入序派生（展示名单源）——新类入表即进块头；实际类别以命中条目透传的
     * kind 为准（开放口径，未入表类别原键透传）。
     */
    private static final String KIND_SCOPE_LINE = "以下是平台知识库中与当前需求相似的沉淀片段（"
            + String.join("/", KIND_LABELS.values()) + "）";

    /** 非约束限定语（知识是背景非指令，#5 决议①）。 */
    private static final String NON_BINDING_QUALIFIER = "（非用户的确认信息，不构成对当前需求的约束）";

    /**
     * 跨项目保护句（#639）：命中内容来自平台其他项目——不向用户提及其他项目，
     * 不当成当前需求的结论（飞轮不碰内核的对话面口径，ADR-0058）。
     */
    private static final String CROSS_PROJECT_GUARD =
            "这些内容来自平台其他项目，不要向用户提及其他项目，也不要把它们当作当前需求的结论。";

    /** 注入块表头（锚＋保护句＋类别枚举行＋用途短语＋非约束限定语）。 */
    private static String blockHeader(String usePhrase) {
        return "【平台知识库·相似知识】" + CROSS_PROJECT_GUARD + KIND_SCOPE_LINE + "，"
                + usePhrase + NON_BINDING_QUALIFIER + "：";
    }

    private final KnowledgePort knowledgePort;
    private final ProjectQueryAppService projectQueryAppService;
    private final int hitTopK;

    public ProjectKnowledgeAppService(KnowledgePort knowledgePort,
            ProjectQueryAppService projectQueryAppService,
            @Value("${app.knowledge.top-k:5}") int hitTopK) {
        this.knowledgePort = knowledgePort;
        this.projectQueryAppService = projectQueryAppService;
        this.hitTopK = hitTopK;
    }

    // ---------- 知识命中（主智能体会话建立注入，#19/#639） ----------

    /**
     * 主智能体会话建立的知识命中注入（一次切入一次注入）：query = 访客真实填写的
     * 初始需求描述（超长截断），检索一次、写一条命中留痕，命中拼为「背景资料」块
     * 返回——由调用方接在主智能体 system prompt 尾部（知识是背景非指令，#5 决议①）。
     * 后续轮/续跑经 {@link #sessionTailOf} 从留痕重建同一块（不重检索）。
     *
     * <p>访客没填需求描述（走平台固定开场白）= 不检索、不写命中记录、不注入，
     * 访谈照常开始。检索成功但 0 条也留痕；检索失败/向量服务不可用记降级。</p>
     */
    public String establishSessionInjection(Long projectId, String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return "";
        }
        KnowledgeRetrieval retrieval = retrieve(requirement);
        recordQuietly(projectId, retrieval);
        log.info("[knowledge] 主智能体会话建立检索完成：命中 {} 条、降级={}",
                retrieval.hits().size(), retrieval.degraded());
        return composeBlock(retrieval.hits());
    }

    /**
     * 会话知识块（#639 起从该项目最近一次命中留痕重建）：服务重启后继续同一场
     * 访谈仍拿到同一份块，不重检索；无留痕 / 空命中 = 空串（裸配置照跑）。
     */
    public String sessionTailOf(Long projectId) {
        try {
            KnowledgeRetrieval latest = knowledgePort.latestRetrieval(projectId.toString());
            return latest == null ? "" : composeBlock(latest.hits());
        }
        catch (RuntimeException e) {
            log.warn("[knowledge] 命中留痕读取失败（降级为空注入）：{}", e.toString());
            return "";
        }
    }

    /** 检索（不抛错）：query 超长截 {@link #MAX_QUERY_CHARS}，异常一律降级。 */
    private KnowledgeRetrieval retrieve(String rawQuery) {
        String query = rawQuery.length() > MAX_QUERY_CHARS
                ? rawQuery.substring(0, MAX_QUERY_CHARS) : rawQuery;
        try {
            KnowledgeRetrieval retrieval = knowledgePort.retrieve(query, hitTopK);
            return retrieval == null ? KnowledgeRetrieval.empty() : retrieval;
        }
        catch (RuntimeException e) {
            log.warn("[knowledge] 主智能体会话建立检索失败（降级为空注入）：{}", e.toString());
            return KnowledgeRetrieval.unavailable();
        }
    }

    /** 命中留痕写入（失败降级不炸：留痕丢失不阻断访谈）。 */
    private void recordQuietly(Long projectId, KnowledgeRetrieval retrieval) {
        try {
            knowledgePort.recordRetrieval(projectId.toString(), retrieval);
        }
        catch (RuntimeException e) {
            log.warn("[knowledge] 命中留痕写入失败（降级跳过）：{}", e.toString());
        }
    }

    private String composeBlock(List<KnowledgeHit> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("\n\n")
                .append(blockHeader("供梳理当前需求时作背景参考"));
        appendHits(block, hits);
        return block.toString();
    }

    // ---------- 拼装私有件 ----------

    /**
     * 命中逐条拼进块（表头与收尾由调用方定调）：〔来源｜类别〕标题 + 片段
     * （#466 命中条带来源 kind 区分——类别展示名未知 kind 原键透传）。
     */
    private static void appendHits(StringBuilder block, List<KnowledgeHit> hits) {
        for (KnowledgeHit hit : hits) {
            block.append("\n\n〔").append(hit.sourceProjectName()).append('｜')
                    .append(KIND_LABELS.getOrDefault(hit.kind(), hit.kind()))
                    .append("〕").append(hit.title()).append('\n').append(hit.chunk());
        }
    }

    // ---------- 知识沉淀（订单归档成功触发：支付链与重试归档两路，#30/#158） ----------

    /**
     * 成交 PRD 沉淀（#30；#158 起沉淀跟随归档成功——支付链与重试归档两路触发、
     * 本口唯一；#338 取版对齐）：沉淀内容由调用方（归档链）裁决——合同冻结的
     * PRD 确认版优先（签约双方约定的正身），无合同（遗留/异常路径）回退最新
     * 确认版；本口只管分块入库，不再读工作区（归档链的 docker exec 依赖点
     * 随之退场）。幂等键 = {@code (PRD, projectId)} 删后插——同项目再成交自然
     * 覆盖旧块。失败降级记日志不炸（丢失容忍，A5 §1）：归档事务已成功，沉淀
     * 不允许把它拖回滚。项目名经 {@code namesOf} 单查询取（缺档即降级跳过，
     * 不走 detail 全量拼装）。
     *
     * @param projectId  所属项目
     * @param prdContent 沉淀正身全文（合同冻结版或最新确认版；空内容即跳过）
     */
    public void sinkPrd(Long projectId, String prdContent) {
        try {
            String projectName = projectQueryAppService.namesOf(List.of(projectId)).get(projectId);
            if (projectName == null) {
                log.warn("[knowledge] 项目 {} 缺档，成交 PRD 沉淀跳过", projectId);
                return;
            }
            List<String> chunks = chunkByParagraph(prdContent);
            if (chunks.isEmpty()) {
                log.info("[knowledge] 项目 {} 成交 PRD 为空，沉淀跳过", projectId);
                return;
            }
            knowledgePort.index(new KnowledgeSpec(KIND_PRD, projectId.toString(),
                    projectId.toString(), projectName, TITLE_PRD, chunks, null));
            log.info("[knowledge] 项目 {} 成交 PRD 沉淀入库（{} 块）", projectId, chunks.size());
        } catch (RuntimeException e) {
            log.warn("[knowledge] 项目 {} 成交 PRD 沉淀失败（降级跳过，丢失容忍）：{}",
                    projectId, e.toString());
        }
    }

    // ---------- 级联清理 ----------

    /** 项目 DELETE 级联清空 knw_chunks、命中留痕与登记行：失败记日志不阻断删除（残留可重删收敛）。 */
    void purgeByProject(Long projectId) {
        quietly("项目知识级联清理", () -> knowledgePort.purgeByProject(projectId.toString()));
    }

    // ---------- 分块成形（沉淀入料的公共件，#30 起消费；实现归底座 KnowledgeChunker） ----------

    /**
     * 段落级分块（底座 {@link KnowledgeChunker} 口径，激活升格 #465 同款复用）。
     * 保留为本类静态缝：PRD 沉淀路径（{@code #sediment}）与 ProjectKnowledgeAppServiceTest 共用此入口。
     */
    static List<String> chunkByParagraph(String text) {
        return KnowledgeChunker.byParagraph(text);
    }

    /** 降级包装：失败记日志跳过，不阻断调用方主流程。 */
    private static void quietly(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("[knowledge] {} 失败（降级跳过）：{}", what, e.toString());
        }
    }
}
