package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识沉淀编排（业务侧缝）：入库/检索/清理的存储语义归 base.knowledge
 * （{@link KnowledgePort}），「什么时机沉淀什么素材」是业务知识，在此落定。
 * v1 的沉淀触发点 = 每笔成交自动沉淀 PRD（交易环，#30）；命中注入在需求环
 * （BA 会话建立）与生成环（生成下发）接线。本片只保留项目删除的级联清理与
 * 分块成形的公共件。
 *
 * <p><b>降级契约</b>：摄取/清理一律失败不炸——记日志跳过、不阻断主流程；
 * embedding 不可用由底座先行降级（旧块保留/检索空列表）。</p>
 */
@Service
@Slf4j
public class ProjectKnowledgeAppService {

    /** 段落级分块目标长度（~800 字符，实现定）。 */
    static final int CHUNK_TARGET_CHARS = 800;

    private final KnowledgePort knowledgePort;

    public ProjectKnowledgeAppService(KnowledgePort knowledgePort) {
        this.knowledgePort = knowledgePort;
    }

    // ---------- 级联清理 ----------

    /** 项目 DELETE 级联清空 knw_chunks：失败记日志不阻断删除（残留可重删收敛）。 */
    void purgeByProject(Long projectId) {
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
