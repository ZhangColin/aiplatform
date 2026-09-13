package com.aieducenter.aiplatform.base.knowledge.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.aieducenter.aiplatform.base.knowledge.domain.error.KnowledgeMessage;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.model.Operator;
import com.aieducenter.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.aieducenter.aiplatform.base.knowledge.domain.repository.KnowledgeStore;

/**
 * 知识用例（票 #17）：入库（幂等删后插）/ 检索（全局余弦相似）/ 项目级联清理；
 * 素材状态（#153）：停用⇄启用可逆开关，停用素材的块退出检索命中。
 *
 * <p>降级次序刻意「先向量化、后替换」：embedding 不可用时旧块原样保留（只记日志
 * 跳过，不删不插——丢失容忍，A5 §1），检索降级为空列表；外部 HTTP 调用也因此在
 * 存储事务之外（A5 §1：embedding 不进门操作事务）。向量库写失败属真实错误，
 * 照常上抛（非降级范畴）。</p>
 */
@Service
@Slf4j
public class KnowledgeAppService {

    private final EmbeddingClient embeddingClient;
    private final KnowledgeStore knowledgeStore;

    public KnowledgeAppService(EmbeddingClient embeddingClient, KnowledgeStore knowledgeStore) {
        this.embeddingClient = embeddingClient;
        this.knowledgeStore = knowledgeStore;
    }

    /**
     * 素材分块入库（幂等）：(kind, sourceRef) 删后插。两类降级跳过、不抛错：
     * 空素材（chunks 空/含空白块，如空 PRD——A5 §1「记日志跳过」）与 embedding
     * 不可用（空返回或数量不符，旧块保留）。
     */
    public void index(KnowledgeSpec spec) {
        requireComplete(spec);
        if (hasBlankChunk(spec.chunks())) {
            log.warn("素材无有效分块，跳过入库：kind={}, sourceRef={}", spec.kind(), spec.sourceRef());
            return;
        }
        List<float[]> vectors = embeddingClient.embed(spec.chunks());
        if (vectors.size() != spec.chunks().size()) {
            log.warn("embedding 服务不可用（{} 块 → {} 向量），跳过入库：kind={}, sourceRef={}",
                    spec.chunks().size(), vectors.size(), spec.kind(), spec.sourceRef());
            return;
        }
        knowledgeStore.replace(spec, vectors);
    }

    /**
     * 语义检索：embedding 不可用降级为空列表（空注入，A5 §3），不抛错。
     */
    public List<KnowledgeHit> retrieve(String query, int topK) {
        if (isBlank(query)) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_QUERY_REQUIRED);
        }
        if (topK <= 0) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_TOP_K_INVALID);
        }
        List<float[]> vectors = embeddingClient.embed(List.of(query));
        if (vectors.isEmpty()) {
            log.warn("embedding 服务不可用，检索降级为空结果：query 长度={}", query.length());
            return List.of();
        }
        return knowledgeStore.findSimilar(vectors.get(0), topK);
    }

    /**
     * 停用素材（#153）：全部块退出命中、落操作者；可逆（{@link #enable} 再启用恢复）。
     * 重复停用幂等（无守卫拒绝，操作者留最近一次）。
     */
    public void disable(String kind, String sourceRef, Operator operator) {
        setStatus(kind, sourceRef, MaterialStatus.DISABLED, operator);
    }

    /**
     * 启用素材：恢复参与命中、落操作者。重复启用幂等。
     */
    public void enable(String kind, String sourceRef, Operator operator) {
        setStatus(kind, sourceRef, MaterialStatus.ENABLED, operator);
    }

    /** 身份必填校验共用：幂等键两肢即素材定位，缺任一即字段不完整。 */
    private void setStatus(String kind, String sourceRef, MaterialStatus status, Operator operator) {
        if (isBlank(kind) || isBlank(sourceRef)) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SPEC_FIELDS_INCOMPLETE);
        }
        if (operator == null || isBlank(operator.id()) || isBlank(operator.name())) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_OPERATOR_REQUIRED);
        }
        if (!knowledgeStore.setStatus(kind, sourceRef, status, operator)) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_MATERIAL_NOT_FOUND);
        }
    }

    /**
     * 按项目级联清理（项目 DELETE 入口）。
     */
    public void purgeByProject(String projectId) {
        if (isBlank(projectId)) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_PROJECT_ID_REQUIRED);
        }
        knowledgeStore.deleteByProject(projectId);
    }

    /** 结构性校验（调用方编程错误，上抛）：定位与展示字段必填；chunks 只查非 null。 */
    private static void requireComplete(KnowledgeSpec spec) {
        if (spec == null || isBlank(spec.kind()) || isBlank(spec.sourceRef()) || isBlank(spec.projectId())
                || isBlank(spec.projectName()) || isBlank(spec.title()) || spec.chunks() == null) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SPEC_FIELDS_INCOMPLETE);
        }
    }

    /** 素材性检查（运行期条件，降级）：空清单或含空白块 = 无有效内容可入库。 */
    private static boolean hasBlankChunk(List<String> chunks) {
        return chunks.isEmpty() || chunks.stream().anyMatch(KnowledgeAppService::isBlank);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
