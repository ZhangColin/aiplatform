package com.semgravio.aiplatform.base.knowledge.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeKnowledgeRetrievalResponse;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeMaterialDetailResponse;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeMaterialSummaryResponse;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeVectorHealthResponse;
import com.semgravio.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.semgravio.aiplatform.base.knowledge.domain.error.KnowledgeMessage;
import com.semgravio.aiplatform.base.knowledge.domain.model.MaterialRecord;
import com.semgravio.aiplatform.base.knowledge.domain.model.MaterialSearchResult;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
import com.semgravio.aiplatform.base.knowledge.domain.model.RetrievalSearchResult;
import com.semgravio.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.semgravio.aiplatform.base.knowledge.domain.repository.KnowledgeStore;

/**
 * 后台知识管理用例（#166 知识库管理；#639 增命中留痕与向量服务健康只读读面）：
 * 素材清单（状态单选＋沉淀时间闭区间＋来源项目 id 精确，沉淀时间倒序 SQL 侧
 * 分页）/ 详情（元数据＋块按 seq 拼全文）/ 停用⇄启用（以 id 解析身份后走域面
 * {@link KnowledgeAppService}——#153 对接口径，操作者必落、缺即 KNW_006）/ 删除
 * （治理移除登记行与全部块，不动来源项目；与项目删除级联正交，无行可留不留痕）/
 * 命中留痕清单（项目＋检索时间过滤）/ 向量服务健康探活。
 *
 * <p>管理单元＝素材＝{@code knw_materials} 一行（#153 落点）：清单/详情/分页在
 * 登记表上是单表查询；内容面零写（不编辑、不手动新增——沉淀唯一触发点不动），
 * 治理手段＝停用/删除。命中留痕与健康读面只读（#639）。</p>
 */
@Service
public class BackofficeKnowledgeAppService {

    private final KnowledgeAppService knowledgeAppService;
    private final KnowledgeStore knowledgeStore;
    private final EmbeddingClient embeddingClient;

    public BackofficeKnowledgeAppService(KnowledgeAppService knowledgeAppService,
            KnowledgeStore knowledgeStore, EmbeddingClient embeddingClient) {
        this.knowledgeAppService = knowledgeAppService;
        this.knowledgeStore = knowledgeStore;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 素材清单：三过滤维度可组合、均可缺省（缺省＝全量）；排序服务端定死＝沉淀
     * 时间倒序（新沉淀在前，id 倒序稳定，SQL 侧不动）；分页钳制/换算全部来自
     * 框架 {@link Pagination}（1 基、缺省 1/20、上界 100 静默贴边），页码原样
     * 回显零手写算术。
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeMaterialSummaryResponse> materials(MaterialStatus status,
            Instant sunkFrom, Instant sunkTo, String projectId, Pagination pagination) {
        // offset 收窄 long→int：存储接口形参是 int，与旧 (safePage-1)*size 整型算术
        // 同 overflow 语义，不额外设防（#191）
        MaterialSearchResult result = knowledgeStore.searchMaterials(status, sunkFrom, sunkTo,
                blankToNull(projectId), (int) pagination.offset(), pagination.limit());
        return new PageResponse<>(
                result.items().stream().map(BackofficeMaterialSummaryResponse::of).toList(),
                result.total(), pagination.page(), pagination.size());
    }

    /**
     * 素材详情：元数据＋PRD 全文（块按 seq 以空行拼接）。来源项目引用容缺直读
     * 登记面，不校验项目存在（不炸）。
     *
     * @throws ApplicationException KNW_005 素材不存在
     */
    @Transactional(readOnly = true)
    public BackofficeMaterialDetailResponse detail(long materialId) {
        MaterialRecord record = requireMaterial(materialId);
        String content = String.join("\n\n", knowledgeStore.chunksOf(record.kind(), record.sourceRef()));
        return BackofficeMaterialDetailResponse.of(record, content);
    }

    /**
     * 停用素材（可逆）：全部块退出检索命中（机制 #153），操作者落素材级。回执
     * 重读登记行取最新状态与操作者。
     *
     * @throws ApplicationException KNW_005 素材不存在；KNW_006 操作者不能为空
     */
    public BackofficeMaterialSummaryResponse disable(long materialId, Operator operator) {
        MaterialRecord record = requireMaterial(materialId);
        knowledgeAppService.disable(record.kind(), record.sourceRef(), operator);
        return BackofficeMaterialSummaryResponse.of(requireMaterial(materialId));
    }

    /**
     * 启用素材：恢复参与命中（可逆开关的另一侧）。
     *
     * @throws ApplicationException KNW_005 素材不存在；KNW_006 操作者不能为空
     */
    public BackofficeMaterialSummaryResponse enable(long materialId, Operator operator) {
        MaterialRecord record = requireMaterial(materialId);
        knowledgeAppService.enable(record.kind(), record.sourceRef(), operator);
        return BackofficeMaterialSummaryResponse.of(requireMaterial(materialId));
    }

    /**
     * 删除素材（治理移除）：登记行与全部块同事务移除、不动来源项目；无行可留、
     * 不留痕。回执＝删除前终态（确认移除了什么）。
     *
     * @throws ApplicationException KNW_005 素材不存在
     */
    public BackofficeMaterialSummaryResponse delete(long materialId) {
        MaterialRecord record = requireMaterial(materialId);
        if (!knowledgeStore.deleteMaterial(materialId)) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_MATERIAL_NOT_FOUND);
        }
        return BackofficeMaterialSummaryResponse.of(record);
    }

    /**
     * 命中留痕清单（#639 只读读面）：项目 id 精确（null/空白＝不过滤）＋检索
     * 时间闭区间（null 侧不限），检索时间倒序、SQL 侧分页。行字段＝项目、时间、
     * 命中条目、是否降级——「检索成功但 0 条」与「降级」由此可区分。
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeKnowledgeRetrievalResponse> retrievals(String projectId,
            Instant from, Instant to, Pagination pagination) {
        RetrievalSearchResult result = knowledgeStore.searchRetrievals(blankToNull(projectId),
                from, to, (int) pagination.offset(), pagination.limit());
        return new PageResponse<>(
                result.items().stream().map(BackofficeKnowledgeRetrievalResponse::of).toList(),
                result.total(), pagination.page(), pagination.size());
    }

    /**
     * 向量服务健康探活（#639 只读读面）：走服务已有健康路径、短超时；探活失败
     * 只报不可用，不影响检索或入库。
     */
    @Transactional(readOnly = true)
    public BackofficeVectorHealthResponse vectorHealth() {
        return BackofficeVectorHealthResponse.of(embeddingClient.health());
    }

    private MaterialRecord requireMaterial(long materialId) {
        MaterialRecord record = knowledgeStore.findMaterial(materialId);
        if (record == null) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_MATERIAL_NOT_FOUND);
        }
        return record;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
