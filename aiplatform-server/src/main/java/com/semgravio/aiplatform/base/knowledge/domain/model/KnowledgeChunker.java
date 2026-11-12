package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落级分块（沉淀入料公共件，#30 起消费、#465 激活升格复用）：按空行切段落，
 * 相邻段落并入至目标长度；超长单段按目标长度硬切（保底不丢内容）。空白输入
 * 返回空清单（调用方跳过）。
 *
 * <p>住底座而非业务侧的理由（#465）：PRD 成交沉淀（业务编排触发）与激活升格
 * （底座审结链触发）共用同一分块口径——分块是入库面知识，不是触发面知识。</p>
 */
public final class KnowledgeChunker {

    /** 段落级分块目标长度（~800 字符，实现定）。 */
    public static final int CHUNK_TARGET_CHARS = 800;

    private KnowledgeChunker() {
    }

    /**
     * 段落级分块：按空行切段落并邻并至 {@link #CHUNK_TARGET_CHARS}，超长单段硬切。
     *
     * @return 块清单（空白输入返回空清单）
     */
    public static List<String> byParagraph(String text) {
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
}
