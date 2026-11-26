package com.semgravio.aiplatform.base.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftQueueResult;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;
import com.semgravio.aiplatform.base.knowledge.domain.repository.SedimentDraftStore;

/**
 * {@link SedimentDraftAppService#purgeByProject}：项目删除级联——只清在途行，
 * 终态留档（审结事实独立于项目存续）。写入链无应用服务入口（#638 拆自荐、#641
 * 起由事后提炼直写 store——校验正本测试见 {@code SedimentDraftProposalTest}，
 * 提炼写入链见 {@code ExperienceDistillationAppServiceTest}），本类测试数据走 store
 * 直插。
 */
class SedimentDraftAppServiceTest {

    /** 落库替身：记录插入形状（供断言）。 */
    private static final class RecordingSedimentStore implements SedimentDraftStore {
        final List<SedimentDraftRecord> inserted = new ArrayList<>();

        @Override
        public void insert(SedimentDraftRecord record) {
            inserted.add(record);
        }

        @Override
        public SedimentDraftRecord find(long id) {
            return inserted.stream().filter(r -> r.id() == id).findFirst().orElse(null);
        }

        @Override
        public SedimentDraftQueueResult searchQueue(
                Long projectId, String kindKey, SedimentDraftStatus status, int offset, int limit) {
            throw new UnsupportedOperationException("队列读面归 #464 审结缝测试");
        }

        @Override
        public boolean markActivated(long id, Operator operator) {
            throw new UnsupportedOperationException("审结写口归 #464 审结缝测试");
        }

        @Override
        public boolean markRejected(long id, String reason, Operator operator) {
            throw new UnsupportedOperationException("审结写口归 #464 审结缝测试");
        }

        @Override
        public boolean updateKind(long id, String kindKey) {
            throw new UnsupportedOperationException("审结写口归 #464 审结缝测试");
        }

        @Override
        public void deletePendingByProject(long projectId) {
            inserted.removeIf(r -> r.projectId() == projectId
                    && r.status() == SedimentDraftStatus.PENDING);
        }
    }

    private final RecordingSedimentStore drafts = new RecordingSedimentStore();

    /** 升格写口替身（#465 起激活链路带升格；本类不触激活，纯占位）。 */
    private final KnowledgeAppService knowledgeAppService =
            org.mockito.Mockito.mock(KnowledgeAppService.class);

    private final SedimentDraftAppService appService =
            new SedimentDraftAppService(drafts, knowledgeAppService);

    /** 先插入一行在途草稿（store 直插——写入链归事后提炼，此处只要一行在途事实）。 */
    private long plantPending(long projectId, SedimentKind kind) {
        SedimentDraftRecord record = new SedimentDraftRecord(
                TsidGenerator.newInstance().generate(), kind, "在途" + kind.key(), "摘要。",
                "正文。", Map.of(), projectId, "run-test", "refinement", "SAFE", List.of(),
                SedimentDraftStatus.PENDING, null, null, null, null, null);
        drafts.insert(record);
        return record.id();
    }

    @Test
    void given_pending_and_terminal_rows_when_purge_by_project_then_pending_gone_terminal_intact() {
        long pendingId = plantPending(42L, SedimentKind.CASE);
        drafts.insert(new SedimentDraftRecord(920099900000000001L, SedimentKind.RULE,
                "已拒规则", "摘要。", "正文。", Map.of(), 42L, "run-x", "main",
                "SAFE", List.of(), SedimentDraftStatus.REJECTED, "op-1", "运营",
                LocalDateTime.now(), "与现有规则重叠", LocalDateTime.now()));
        drafts.insert(new SedimentDraftRecord(920099900000000002L, SedimentKind.CASE,
                "他项目在途", "摘要。", "正文。", Map.of(), 43L, "run-y", "main",
                "SAFE", List.of(), SedimentDraftStatus.PENDING, null, null, null, null,
                LocalDateTime.now()));

        appService.purgeByProject(42L);

        assertThat(drafts.find(pendingId)).isNull();
        assertThat(drafts.find(920099900000000001L).status())
                .isEqualTo(SedimentDraftStatus.REJECTED);
        assertThat(drafts.find(920099900000000002L)).isNotNull();
    }
}
