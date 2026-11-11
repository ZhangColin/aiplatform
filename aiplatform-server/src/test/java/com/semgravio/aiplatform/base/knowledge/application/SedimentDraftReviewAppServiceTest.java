package com.semgravio.aiplatform.base.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.request.Pagination;

import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeSedimentDraftDetailResponse;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;
import com.semgravio.aiplatform.base.knowledge.domain.error.KnowledgeMessage;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftProposal;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftQueueResult;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftReceipt;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;
import com.semgravio.aiplatform.base.knowledge.domain.repository.SedimentDraftStore;

/**
 * {@link SedimentDraftAppService}「数据标注」审结面（#464——照
 * {@code SkillDraftAppServiceTest} 先例：store 用内存替身直测判定链，JDBC 落库
 * 真面在 REST 缝覆盖）：
 *
 * <p>{@link SedimentDraftAppService#queue}：过滤维度透传（SQL 真面归 seam）＋
 * 类别过滤值解析（空白＝不过滤、非法 400 KNW_011 同写入校验口径）＋分页回显
 * （items/total/page/size 框架原值）。</p>
 *
 * <p>三键（{@link SedimentDraftAppService#activate}/{@link SedimentDraftAppService#reject}/
 * {@link SedimentDraftAppService#relabel}）：守卫序（寻址 404 → 在途 409 → 操作者 400
 * → 键特有校验）＋终态留痕形状（理由 trim 落档/操作者两列/审结时刻）＋CAS 零行
 * 命中不吞并为成功（并发审结 → 409）＋改标签占目标类别最低空位（排除自身）＋
 * 目标类别满拒（预检与并发撞索引归一为 KNW_012）。</p>
 */
class SedimentDraftReviewAppServiceTest {

    private static final Operator OPERATOR = new Operator("700300", "运营·数据标注员");

    /** 落库替身：记录插入形状（供断言）；审结 CAS 与并发窗可编程。 */
    private static final class RecordingSedimentStore implements SedimentDraftStore {
        final List<SedimentDraftRecord> inserted = new ArrayList<>();

        /** 在途序号占用编程位（propose 查腿）：空＝无在途。 */
        List<Integer> pendingSlots = List.of();

        /** 改标签查腿编程位：目标类别在途序号占用（不含自身）。 */
        List<Integer> pendingSlotsExcluding = List.of();

        /** 审结/改标签 CAS 编程位：false＝模拟并发审结抢先（零行命中）。 */
        boolean reviewOutcome = true;

        /** 并发窗编程位：true＝updateKind 抛 DuplicateKeyException（部分唯一索引兜底）。 */
        boolean duplicateOnUpdateKind;

        @Override
        public List<Integer> findPendingSlots(long projectId, String kindKey) {
            return pendingSlots;
        }

        @Override
        public void insert(SedimentDraftRecord record) {
            // 真库 created_at 走 DEFAULT——替身归一为 now()（队列倒序可比较）
            inserted.add(record.createdAt() == null
                    ? new SedimentDraftRecord(record.id(), record.kind(), record.title(),
                            record.summary(), record.content(), record.payload(),
                            record.projectId(), record.runId(), record.slot(),
                            record.inflightSlot(), record.scanVerdict(), record.scanFindings(),
                            record.status(), record.operatorId(), record.operatorName(),
                            record.reviewedAt(), record.rejectReason(), LocalDateTime.now())
                    : record);
        }

        @Override
        public SedimentDraftRecord find(long id) {
            return inserted.stream().filter(r -> r.id() == id).findFirst().orElse(null);
        }

        @Override
        public SedimentDraftQueueResult searchQueue(Long projectId, String kindKey,
                SedimentDraftStatus status, int offset, int limit) {
            // 内存真语义（过滤＋倒序＋分页），让本缝可断言维度组合行为；SQL 真面另归 seam
            List<SedimentDraftRecord> matched = inserted.stream()
                    .filter(r -> projectId == null || r.projectId() == projectId)
                    .filter(r -> kindKey == null || r.kind().key().equals(kindKey))
                    .filter(r -> status == null || r.status() == status)
                    .sorted(Comparator.comparing(SedimentDraftRecord::createdAt).reversed()
                            .thenComparing(Comparator.comparing(SedimentDraftRecord::id).reversed()))
                    .toList();
            List<SedimentDraftRecord> page = matched.stream()
                    .skip(offset).limit(limit).toList();
            return new SedimentDraftQueueResult(page, matched.size());
        }

        @Override
        public List<Integer> findPendingSlotsExcluding(long excludeId, long projectId,
                String kindKey) {
            return pendingSlotsExcluding;
        }

        @Override
        public boolean markActivated(long id, Operator operator) {
            if (!reviewOutcome) {
                return false;
            }
            return terminalize(id, SedimentDraftStatus.ACTIVATED, operator, null);
        }

        @Override
        public boolean markRejected(long id, String reason, Operator operator) {
            if (!reviewOutcome) {
                return false;
            }
            return terminalize(id, SedimentDraftStatus.REJECTED, operator, reason);
        }

        @Override
        public boolean updateKind(long id, String kindKey, int inflightSlot) {
            if (!reviewOutcome) {
                return false;
            }
            if (duplicateOnUpdateKind) {
                throw new DuplicateKeyException("uq_knw_sediment_drafts_pending_slot");
            }
            SedimentDraftRecord current = find(id);
            if (current == null || current.status() != SedimentDraftStatus.PENDING) {
                return false;
            }
            inserted.set(inserted.indexOf(current), new SedimentDraftRecord(current.id(),
                    SedimentKind.byKey(kindKey).orElseThrow(), current.title(), current.summary(),
                    current.content(), current.payload(), current.projectId(), current.runId(),
                    current.slot(), inflightSlot, current.scanVerdict(), current.scanFindings(),
                    current.status(), current.operatorId(), current.operatorName(),
                    current.reviewedAt(), current.rejectReason(), current.createdAt()));
            return true;
        }

        @Override
        public void deletePendingByProject(long projectId) {
            inserted.removeIf(r -> r.projectId() == projectId
                    && r.status() == SedimentDraftStatus.PENDING);
        }

        /** 测试夹具：直接补一行（终态留档形状用）。 */
        void addTerminal(SedimentDraftRecord record) {
            inserted.add(record);
        }

        private boolean terminalize(long id, SedimentDraftStatus status, Operator operator,
                String reason) {
            SedimentDraftRecord current = find(id);
            if (current == null || current.status() != SedimentDraftStatus.PENDING) {
                return false;
            }
            inserted.set(inserted.indexOf(current), new SedimentDraftRecord(current.id(),
                    current.kind(), current.title(), current.summary(), current.content(),
                    current.payload(), current.projectId(), current.runId(), current.slot(),
                    current.inflightSlot(), current.scanVerdict(), current.scanFindings(),
                    status, operator.id(), operator.name(), LocalDateTime.now(), reason,
                    current.createdAt()));
            return true;
        }
    }

    private final RecordingSedimentStore drafts = new RecordingSedimentStore();
    private final SedimentDraftAppService appService = new SedimentDraftAppService(drafts);

    private static SedimentDraftProposal proposal(String kind, String title) {
        return new SedimentDraftProposal(kind, title, "摘要。", "正文。", 42L, "run-abc-1",
                "executor");
    }

    /** 种植＝propose 真链路（成功才可用）。 */
    private SedimentDraftReceipt plant(String kind, String title) {
        SedimentDraftReceipt receipt = appService.propose(proposal(kind, title));
        assertThat(receipt.accepted()).as("种植须成功: %s", receipt.message()).isTrue();
        return receipt;
    }

    // ---------- queue ----------

    @Test
    void given_drafts_when_queue_then_filters_paging_and_echo() {
        plant("CASE", "案例一");
        plant("RULE", "规则一");
        drafts.addTerminal(new SedimentDraftRecord(920099900000000001L, SedimentKind.MODULE,
                "已激活模块", "摘要。", "正文。", Map.of(), 42L, "run-x", "main", 2,
                "SAFE", List.of(), SedimentDraftStatus.ACTIVATED, "op-1", "运营",
                LocalDateTime.now(), null, LocalDateTime.now().minusDays(1)));

        // 缺省＝全量：3 行（任意状态都在队列读面——终态留档可见）
        var page = appService.queue(null, null, null, new Pagination(1, 20, null));
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);

        // 维度组合：类别＋状态
        var filtered = appService.queue(null, "case", SedimentDraftStatus.PENDING,
                new Pagination(1, 20, null));
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.items().get(0).title()).isEqualTo("案例一");
        // 状态过滤到终态
        var terminal = appService.queue(null, null, SedimentDraftStatus.ACTIVATED,
                new Pagination(1, 20, null));
        assertThat(terminal.total()).isEqualTo(1);

        // 分页行走
        var second = appService.queue(null, null, null, new Pagination(2, 2, null));
        assertThat(second.items()).hasSize(1);

        // 类别空白＝不过滤；大小写不敏感归一
        assertThat(appService.queue(null, "  ", null, new Pagination(1, 20, null)).total())
                .isEqualTo(3);
        assertThat(appService.queue(null, "rule", null, new Pagination(1, 20, null)).total())
                .isEqualTo(1);
    }

    @Test
    void given_invalid_kind_filter_when_queue_then_400_kind_invalid() {
        assertThatThrownBy(() -> appService.queue(null, "PRD", null,
                new Pagination(1, 20, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_KIND_INVALID.message());
    }

    @Test
    void given_unknown_draft_when_draft_then_404_not_found() {
        assertThatThrownBy(() -> appService.draft(1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND.message());
    }

    // ---------- activate ----------

    @Test
    void given_pending_draft_when_activate_then_terminal_with_trace_and_queue_exit() {
        SedimentDraftReceipt receipt = plant("CASE", "激活案例");

        BackofficeSedimentDraftDetailResponse detail = appService.activate(
                receipt.draftId(), OPERATOR);

        assertThat(detail.status()).isEqualTo(SedimentDraftStatus.ACTIVATED.getCode());
        assertThat(detail.statusName()).isEqualTo("已激活");
        assertThat(detail.operatorId()).isEqualTo("700300");
        assertThat(detail.operatorName()).isEqualTo("运营·数据标注员");
        assertThat(detail.reviewedAt()).isNotNull();
        assertThat(detail.rejectReason()).isNull();
        // 队列在途面退出（状态过滤不再收）
        assertThat(appService.queue(null, null, SedimentDraftStatus.PENDING,
                new Pagination(1, 20, null)).total()).isZero();
        // 全量读面仍在（终态留档可查）
        assertThat(appService.draft(receipt.draftId()).status())
                .isEqualTo(SedimentDraftStatus.ACTIVATED.getCode());
    }

    @Test
    void given_concurrent_review_won_when_activate_then_not_swallowed_as_success() {
        SedimentDraftReceipt receipt = plant("CASE", "并发激活");
        drafts.reviewOutcome = false;

        assertThatThrownBy(() -> appService.activate(receipt.draftId(), OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED.message());
    }

    // ---------- reject ----------

    @Test
    void given_pending_draft_when_reject_then_terminal_with_reason_trimmed_and_trace() {
        SedimentDraftReceipt receipt = plant("RULE", "拒绝规则");

        BackofficeSedimentDraftDetailResponse detail = appService.reject(
                receipt.draftId(), "  与已激活知识重叠，无增量价值  ", OPERATOR);

        assertThat(detail.status()).isEqualTo(SedimentDraftStatus.REJECTED.getCode());
        assertThat(detail.rejectReason()).isEqualTo("与已激活知识重叠，无增量价值");
        assertThat(detail.operatorId()).isEqualTo("700300");
        assertThat(detail.reviewedAt()).isNotNull();
        assertThat(appService.queue(null, null, SedimentDraftStatus.PENDING,
                new Pagination(1, 20, null)).total()).isZero();
    }

    @Test
    void given_blank_or_null_reason_when_reject_then_required_error() {
        SedimentDraftReceipt receipt = plant("RULE", "空理由规则");

        assertThatThrownBy(() -> appService.reject(receipt.draftId(), "  ", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_REJECT_REASON_REQUIRED.message());
        assertThatThrownBy(() -> appService.reject(receipt.draftId(), null, OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_REJECT_REASON_REQUIRED.message());
        assertThat(drafts.find(receipt.draftId()).status()).isEqualTo(SedimentDraftStatus.PENDING);
    }

    // ---------- relabel ----------

    @Test
    void given_pending_draft_when_relabel_then_kind_changed_with_lowest_free_slot() {
        SedimentDraftReceipt receipt = plant("CASE", "改类案例");
        // 目标类别 RULE 在途已占 1、3——最低空位 2（排除自身语义由 store 查腿保证）
        drafts.pendingSlotsExcluding = List.of(1, 3);

        BackofficeSedimentDraftDetailResponse detail = appService.relabel(
                receipt.draftId(), "rule", OPERATOR);

        assertThat(detail.kind()).isEqualTo("RULE");
        assertThat(detail.inflightSlot()).isEqualTo(2);
        assertThat(detail.status()).isEqualTo(SedimentDraftStatus.PENDING.getCode());
        // 不翻终态：在途队列按新类别可收
        assertThat(appService.queue(null, "RULE", SedimentDraftStatus.PENDING,
                new Pagination(1, 20, null)).total()).isEqualTo(1);
    }

    @Test
    void given_same_kind_when_relabel_then_idempotent_noop() {
        SedimentDraftReceipt receipt = plant("MODULE", "同类模块");

        BackofficeSedimentDraftDetailResponse detail = appService.relabel(
                receipt.draftId(), "module", OPERATOR);

        assertThat(detail.kind()).isEqualTo("MODULE");
        assertThat(detail.inflightSlot()).isEqualTo(1);
    }

    @Test
    void given_target_kind_full_when_relabel_then_conflict_and_untouched() {
        SedimentDraftReceipt receipt = plant("CASE", "满员改类");
        drafts.pendingSlotsExcluding = List.of(1, 2, 3);

        assertThatThrownBy(() -> appService.relabel(receipt.draftId(), "RULE", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_TARGET_KIND_FULL.message());
        assertThat(drafts.find(receipt.draftId()).kind()).isEqualTo(SedimentKind.CASE);
    }

    @Test
    void given_concurrent_slot_grab_when_relabel_then_duplicate_key_normalized_to_full() {
        SedimentDraftReceipt receipt = plant("CASE", "并发改类");
        drafts.duplicateOnUpdateKind = true;

        assertThatThrownBy(() -> appService.relabel(receipt.draftId(), "RULE", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_TARGET_KIND_FULL.message());
    }

    @Test
    void given_invalid_or_blank_kind_when_relabel_then_400_kind_invalid() {
        SedimentDraftReceipt receipt = plant("CASE", "非法类别案例");

        assertThatThrownBy(() -> appService.relabel(receipt.draftId(), "PRD", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_KIND_INVALID.message());
        assertThatThrownBy(() -> appService.relabel(receipt.draftId(), " ", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_KIND_INVALID.message());
    }

    // ---------- 三键共面守卫 ----------

    @Test
    void given_terminal_draft_when_any_review_action_then_already_reviewed() {
        SedimentDraftReceipt receipt = plant("CASE", "已审结案例");
        appService.reject(receipt.draftId(), "不采纳", OPERATOR);

        assertThatThrownBy(() -> appService.activate(receipt.draftId(), OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED.message());
        assertThatThrownBy(() -> appService.reject(receipt.draftId(), "再拒", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED.message());
        assertThatThrownBy(() -> appService.relabel(receipt.draftId(), "RULE", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED.message());
        // 终态留档不可变：拒绝理由不被重审覆盖
        assertThat(drafts.find(receipt.draftId()).rejectReason()).isEqualTo("不采纳");
    }

    @Test
    void given_missing_operator_when_any_review_action_then_operator_required() {
        SedimentDraftReceipt activate = plant("CASE", "缺操作者激活");
        SedimentDraftReceipt reject = plant("RULE", "缺操作者拒绝");
        SedimentDraftReceipt relabel = plant("MODULE", "缺操作者改类");

        assertThatThrownBy(() -> appService.activate(activate.draftId(),
                new Operator(null, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_OPERATOR_REQUIRED.message());
        assertThatThrownBy(() -> appService.reject(reject.draftId(), "理由",
                new Operator("700300", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_OPERATOR_REQUIRED.message());
        assertThatThrownBy(() -> appService.relabel(relabel.draftId(), "RULE",
                new Operator(null, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_OPERATOR_REQUIRED.message());
        // 三行俱不动（守卫在写口之前）
        assertThat(drafts.find(activate.draftId()).status()).isEqualTo(SedimentDraftStatus.PENDING);
        assertThat(drafts.find(reject.draftId()).status()).isEqualTo(SedimentDraftStatus.PENDING);
        assertThat(drafts.find(relabel.draftId()).status()).isEqualTo(SedimentDraftStatus.PENDING);
    }

    @Test
    void given_unknown_draft_when_any_review_action_then_not_found() {
        assertThatThrownBy(() -> appService.activate(1L, OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.reject(1L, "理由", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.relabel(1L, "RULE", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND.message());
    }
}
