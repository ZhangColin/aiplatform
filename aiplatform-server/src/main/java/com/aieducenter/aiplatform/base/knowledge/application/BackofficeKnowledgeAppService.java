package com.aieducenter.aiplatform.base.knowledge.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.knowledge.application.dto.response.BackofficeMaterialDetailResponse;
import com.aieducenter.aiplatform.base.knowledge.application.dto.response.BackofficeMaterialSummaryResponse;
import com.aieducenter.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.aieducenter.aiplatform.base.knowledge.domain.error.KnowledgeMessage;
import com.aieducenter.aiplatform.base.knowledge.domain.model.MaterialRecord;
import com.aieducenter.aiplatform.base.knowledge.domain.model.MaterialSearchResult;
import com.aieducenter.aiplatform.base.knowledge.domain.model.Operator;
import com.aieducenter.aiplatform.base.knowledge.domain.repository.KnowledgeStore;
import com.aieducenter.aiplatform.web.BackofficePages;

/**
 * 后台知识素材管理用例（#166 知识库管理）：清单（状态单选＋沉淀时间闭区间＋
 * 来源项目 id 精确，沉淀时间倒序 SQL 侧分页）/ 详情（元数据＋块按 seq 拼全文）/
 * 停用⇄启用（以 id 解析身份后走域面 {@link KnowledgeAppService}——#153 对接口径，
 * 操作者必落、缺即 KNW_006）/ 删除（治理移除登记行与全部块，不动来源项目；与
 * 项目删除级联正交，无行可留不留痕）。
 *
 * <p>管理单元＝素材＝{@code knw_materials} 一行（#153 落点）：清单/详情/分页在
 * 登记表上是单表查询；内容面零写（不编辑、不手动新增——沉淀唯一触发点不动），
 * 治理手段＝停用/删除。</p>
 */
@Service
public class BackofficeKnowledgeAppService {

    private final KnowledgeAppService knowledgeAppService;
    private final KnowledgeStore knowledgeStore;

    public BackofficeKnowledgeAppService(KnowledgeAppService knowledgeAppService,
            KnowledgeStore knowledgeStore) {
        this.knowledgeAppService = knowledgeAppService;
        this.knowledgeStore = knowledgeStore;
    }

    /**
     * 素材清单：三过滤维度可组合、均可缺省（缺省＝全量）；排序服务端定死＝沉淀
     * 时间倒序（新沉淀在前，id 倒序稳定）；page 1 基，size 上界 100（BackofficePages）。
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeMaterialSummaryResponse> materials(MaterialStatus status,
            Instant sunkFrom, Instant sunkTo, String projectId, int page, int size) {
        int safePage = BackofficePages.clampPage(page);
        int safeSize = BackofficePages.clampSize(size);
        MaterialSearchResult result = knowledgeStore.searchMaterials(status, sunkFrom, sunkTo,
                blankToNull(projectId), (safePage - 1) * safeSize, safeSize);
        return new PageResponse<>(
                result.items().stream().map(BackofficeMaterialSummaryResponse::of).toList(),
                result.total(), safePage, safeSize);
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
