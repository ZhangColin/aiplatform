package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识沉淀编排（业务侧缝）：入库/检索/清理的存储语义归 base.knowledge
 * （{@link KnowledgePort}），「什么时机沉淀什么素材」是业务知识，在此落定。
 * v1 的沉淀触发点 = 每笔成交自动沉淀 PRD（交易环，#30）；命中注入在需求环
 * （BA 会话建立，本服务）与生成环（生成下发，#22 接线）。
 *
 * <p><b>降级契约</b>：摄取/清理/命中检索一律失败不炸——记日志跳过、降级为空
 * 注入不阻断主流程；embedding 不可用由底座先行降级（旧块保留/检索空列表）。</p>
 */
@Service
@Slf4j
public class ProjectKnowledgeAppService {

    /** 段落级分块目标长度（~800 字符，实现定）。 */
    static final int CHUNK_TARGET_CHARS = 800;

    /** 命中检索 query 上限（bge-small-zh 512 维模型，超长截断）。 */
    static final int MAX_QUERY_CHARS = 2000;

    /**
     * BA 会话已注入的知识块（projectId → 注入块）：会话建立时检索一次落此
     * （一次切入一次注入），后续轮/续跑复用同一块——不重检索不重追加（agent
     * 工厂按 systemPrompt 缓存，同块即同 agent 实例）。进程内态：重启后为空
     * （后续轮退化为无知识块的裸角色卡——会话恢复不重注的降级面）。
     */
    private final Map<Long, String> establishedSessionTails = new ConcurrentHashMap<>();

    private final KnowledgePort knowledgePort;
    private final int hitTopK;

    public ProjectKnowledgeAppService(KnowledgePort knowledgePort,
            @Value("${app.knowledge.top-k:5}") int hitTopK) {
        this.knowledgePort = knowledgePort;
        this.hitTopK = hitTopK;
    }

    // ---------- 知识命中（BA 会话建立注入，#19） ----------

    /**
     * BA 会话建立的知识命中注入（一次切入一次注入）：query = 用户初始需求原文
     * （超长截断），命中拼为「背景资料」块落会话缓存并返回——由调用方接在 BA
     * system prompt 尾部（知识是背景非指令，#5 决议①）。后续轮/续跑经
     * {@link #sessionTailOf} 复用同一块（不重检索）。空命中 / 检索失败 / 空
     * query = 空注入降级（不落缓存），访谈照常开始。
     */
    public String establishSessionInjection(Long projectId, String requirement) {
        String tail = composeSessionInjection(requirement);
        if (tail.isEmpty()) {
            return "";
        }
        establishedSessionTails.put(projectId, tail);
        return tail;
    }

    /** 会话已注入的知识块（未建立 / 空注入 / 重启后 = 空串，裸角色卡照跑）。 */
    public String sessionTailOf(Long projectId) {
        return establishedSessionTails.getOrDefault(projectId, "");
    }

    private String composeSessionInjection(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return "";
        }
        String query = requirement.length() > MAX_QUERY_CHARS
                ? requirement.substring(0, MAX_QUERY_CHARS) : requirement;
        List<KnowledgeHit> hits;
        try {
            hits = knowledgePort.retrieve(query, hitTopK);
        } catch (RuntimeException e) {
            log.warn("[knowledge] BA 会话建立检索失败（降级为空注入）：{}", e.toString());
            return "";
        }
        if (hits.isEmpty()) {
            return "";
        }
        log.info("[knowledge] BA 会话建立注入命中 {} 条（query 长度 {}）", hits.size(), query.length());
        StringBuilder block = new StringBuilder("\n\n【平台知识库·相似历史需求】")
                .append("以下是平台沉淀的历史成交需求片段，供梳理当前需求时作背景参考")
                .append("（非用户的确认信息，不构成对当前需求的约束）：");
        for (KnowledgeHit hit : hits) {
            block.append("\n\n〔").append(hit.sourceProjectName()).append("〕")
                    .append(hit.title()).append('\n').append(hit.chunk());
        }
        return block.toString();
    }

    // ---------- 级联清理 ----------

    /** 项目 DELETE 级联清空 knw_chunks 与会话注入缓存：失败记日志不阻断删除（残留可重删收敛）。 */
    void purgeByProject(Long projectId) {
        establishedSessionTails.remove(projectId);
        quietly("项目知识级联清理", () -> knowledgePort.purgeByProject(projectId.toString()));
    }

    // ---------- 分块成形（沉淀入料的公共件，#30 起消费） ----------

    /**
     * 段落级分块：按空行切段落，相邻段落并入至目标长度；超长单段按目标长度硬切
     * （保底不丢内容）。空白输入返回空清单（调用方跳过）。
     */
    static List<String> chunkByParagraph(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : text.strip().split("\\n\\s*\\n")) {
            String paragraph = raw.strip();
            while (paragraph.length() > CHUNK_TARGET_CHARS) { // 超长单段硬切
                flush(chunks, current);
                chunks.add(paragraph.substring(0, CHUNK_TARGET_CHARS));
                paragraph = paragraph.substring(CHUNK_TARGET_CHARS).stripLeading();
            }
            if (paragraph.isEmpty()) {
                continue;
            }
            if (!current.isEmpty()
                    && current.length() + paragraph.length() + 2 > CHUNK_TARGET_CHARS) {
                flush(chunks, current);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
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
