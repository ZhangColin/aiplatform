package com.aieducenter.aiplatform.base.skills.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDraftDetailResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDraftSummaryResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillSummaryResponse;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillDraftStatus;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSource;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.error.SkillMessage;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftProposal;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftReceipt;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftRecord;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillDraftStore;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;

/**
 * 技能草稿用例（#259 T1 自荐写入＋只读面；#262 T2 人审晋升/拒绝，ADR-0022）。
 *
 * <p><b>propose 写入链</b>（拒绝一律回执原因、不抛——智能体可读可修正重提）：
 * 校验正本（{@link SkillDraftProposal#violation()}，照框架常量）→ 静态安全扫描
 * （{@link SkillSecurityScanner}——依赖内纯静态件，扫描面＝frontmatter(name/
 * description)＋正文合成的完整 SKILL.md，与晋升后真实内容一致；DANGEROUS 拒写
 * ——scanner 是廉价哨兵非安全边界，安全边界是后台人审门）→ 撞名拒（技能库 name
 * ∪ 在途草稿；已拒绝草稿不占名）→ 落库（TSID、在途态、verdict＋findings 随草稿
 * 留档）。插入单语句原子＋在途部分唯一索引兜底并发撞名窗（DuplicateKeyException
 * 归一为撞名回执）。run 失败不回滚已写入的自荐：工具执行即事实落库，无 run 级
 * 回滚概念。</p>
 *
 * <p><b>promote 晋升事务</b>（#262）：在途复查 → 撞名复查（审核期间库新增同名
 * 即拒——自荐时查过、晋升时再查，窗口内的同名入库在此拦）→ 插入启用库行
 * （来源＝自产、包名/版本＝固定虚拟值 {@code self}、frontmatter＝name/
 * description 合成、正文原样、resources 空面——a-only）→ 草稿标已晋升
 * （{@code WHERE status=在途} 条件更新即 CAS：并发审结零行命中回滚整体——库行
 * 插入随之撤销）。操作者留痕两处（库行＝晋升者、草稿＝审结者）。晋升即入池
 * 同权，<b>不自动指派</b>——生效仍走人工指派＋skillcheck 预检（入池与生效分离，
 * ADR-0022）。</p>
 *
 * <p><b>reject 拒绝</b>（#262）：理由必填（终态留档拒绝须有据）＋草稿标已拒绝
 * （CAS 同 promote）。拒绝即终态：内容留档可查、不再列活跃面、拒绝不改稿不
 * 复活——重提只能靠未来会话产生新草稿。</p>
 *
 * <p><b>读面</b>：活跃面＝在途草稿时间倒序（终态不列——审核面只见待办）；
 * 详情任意状态可查（终态留档）。草稿不参与任何装配：装配视图查询不触
 * {@code skl_skill_drafts}（结构性保证，装配缝测试钉死）。</p>
 */
@Service
public class SkillDraftAppService {

    /** 自产行的包名/版本固定虚拟值（#262）：套既有 uq_skl_skills_identity 唯一约束——自产同名唯一即结构性保证。 */
    static final String SELF_SOURCE_PACKAGE = "self";

    private final SkillStore skillStore;
    private final SkillDraftStore draftStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillDraftAppService(SkillStore skillStore, SkillDraftStore draftStore) {
        this.skillStore = skillStore;
        this.draftStore = draftStore;
    }

    /**
     * 自荐写入：全判定链见类 javadoc；回执见 {@link SkillDraftReceipt}。
     */
    public SkillDraftReceipt propose(SkillDraftProposal proposal) {
        Optional<String> violation = SkillDraftProposal.contentViolation(
                proposal.name(), proposal.description(), proposal.content());
        if (violation.isPresent()) {
            return SkillDraftReceipt.rejected(violation.get());
        }
        SkillSecurityScanner.ScanResult scan = SkillSecurityScanner.scan(proposal.name(),
                skillMdOf(proposal), Map.of());
        if (scan.verdict() == SkillSecurityScanner.Verdict.DANGEROUS) {
            return SkillDraftReceipt.rejected("安全扫描判定 DANGEROUS，拒写不入库。触发的"
                    + "规则：" + patternIdsOf(scan) + "——请移除相应危险内容后重提。");
        }
        if (skillStore.existsByName(proposal.name())) {
            return SkillDraftReceipt.rejected("与技能库现有技能同名：\"" + proposal.name()
                    + "\"——请换名重提。");
        }
        if (draftStore.existsPendingByName(proposal.name())) {
            return pendingConflict(proposal.name());
        }
        SkillDraftRecord record = new SkillDraftRecord(
                TsidGenerator.newInstance().generate(),
                proposal.name(),
                proposal.description(),
                proposal.content(),
                proposal.projectId(),
                proposal.runId(),
                proposal.slot(),
                scan.verdict().name(),
                findingsOf(scan),
                SkillDraftStatus.PENDING,
                null, null, null, null, null);
        try {
            draftStore.insert(record);
        }
        catch (DuplicateKeyException e) {
            // 并发窗内同名在途草稿抢先落库（部分唯一索引兜底）——归一为撞名回执
            return pendingConflict(proposal.name());
        }
        return SkillDraftReceipt.accepted(record.id(),
                "技能草稿已留档待审（后台人工审核晋升后才入技能库；未审核草稿不参与"
                        + "任何装配）。感谢沉淀。");
    }

    /**
     * 活跃面列表（#259 管理读面）：在途草稿按时间倒序（最近先）——人审队列，
     * 不分页（草稿量的人审是天然瓶颈，对齐技能清单有界目录先例）。
     */
    public List<BackofficeSkillDraftSummaryResponse> drafts() {
        return draftStore.findPending().stream()
                .map(BackofficeSkillDraftSummaryResponse::of).toList();
    }

    /**
     * 草稿详情（任意状态——终态留档可查）。
     *
     * @throws ApplicationException SKL_015 草稿不存在（未寻址/畸形 TSID 同语义）
     */
    public BackofficeSkillDraftDetailResponse draft(long id) {
        SkillDraftRecord record = draftStore.find(id);
        if (record == null) {
            throw new ApplicationException(SkillMessage.SKILL_DRAFT_NOT_FOUND);
        }
        return BackofficeSkillDraftDetailResponse.of(record);
    }

    /**
     * 晋升（#262 T2，ADR-0022 单级晋升）：事务＝撞名复查＋插入启用库行（来源
     * ＝自产、包名/版本＝{@code self}、内容原样——审核者不改稿）＋草稿标已晋升
     * ＋操作者两处留痕。同事务原子：任一环失败整体回滚（CAS 零行命中即并发审结
     * →回滚库行插入）。回执＝新库行清单行（入池即同权——启停/指派/预检与安装
     * 技能同一套；晋升不自动指派，生效仍走人工指派＋skillcheck 预检）。
     *
     * @throws ApplicationException SKL_015 草稿不存在；SKL_016 技能库同名
     *         （审核期间新增同名入库）；SKL_017 草稿已审结；SKL_009 操作者缺
     */
    @Transactional
    public BackofficeSkillSummaryResponse promote(long id, Operator operator) {
        SkillDraftRecord draft = requirePendingDraft(id);
        requireOperator(operator);
        // 撞名复查（事务内）：自荐时查过，审核窗口内同名入库在此拦（含停用行——
        // 与自荐撞名同口径保守拒）
        if (skillStore.existsByName(draft.name())) {
            throw new ApplicationException(SkillMessage.SKILL_DRAFT_PROMOTE_NAME_CONFLICT);
        }
        SkillRecord promoted = new SkillRecord(TsidGenerator.newInstance().generate(),
                draft.name(), draft.description(),
                SELF_SOURCE_PACKAGE, SELF_SOURCE_PACKAGE, SkillSource.SELF_PRODUCED,
                SkillStatus.ENABLED,
                Map.of("name", draft.name(), "description", draft.description()),
                draft.content(), Map.of(),
                operator.id(), operator.name(),
                // 自产行无包行：「有新版」不适用（无远端），null 与重读口径一致
                null, 0, null);
        try {
            skillStore.insertAll(List.of(promoted));
        }
        catch (DuplicateKeyException e) {
            // 并发窗内自产行抢先入库名（uq (source_package, name) 兜底）——归一为撞名拒
            throw new ApplicationException(SkillMessage.SKILL_DRAFT_PROMOTE_NAME_CONFLICT);
        }
        if (!draftStore.markPromoted(id, operator)) {
            // CAS 零行命中＝并发审结（晋升/拒绝抢先落定）——抛回滚库行插入
            throw new ApplicationException(SkillMessage.SKILL_DRAFT_ALREADY_REVIEWED);
        }
        return BackofficeSkillSummaryResponse.of(promoted);
    }

    /**
     * 拒绝（#262 T2，ADR-0022 拒绝即终态）：理由必填＋草稿标已拒绝（操作者/
     * 时刻/理由随行留档）。拒绝后不再列活跃面、详情仍可查；不改稿不复活——重提
     * 为新草稿。回执＝终态草稿详情（留痕形状直读）。
     *
     * @throws ApplicationException SKL_015 草稿不存在；SKL_017 草稿已审结；
     *         SKL_018 理由空；SKL_009 操作者缺
     */
    @Transactional
    public BackofficeSkillDraftDetailResponse reject(long id, String reason, Operator operator) {
        requirePendingDraft(id);
        requireOperator(operator);
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            throw new ApplicationException(SkillMessage.SKILL_DRAFT_REJECT_REASON_REQUIRED);
        }
        if (!draftStore.markRejected(id, normalized, operator)) {
            // CAS 零行命中＝并发审结——拒绝无伴写，抛即整体无事发生
            throw new ApplicationException(SkillMessage.SKILL_DRAFT_ALREADY_REVIEWED);
        }
        return BackofficeSkillDraftDetailResponse.of(draftStore.find(id));
    }

    /** 在途草稿守卫（晋升/拒绝共用前置）：查无 404、终态 409（重审拒绝）。 */
    private SkillDraftRecord requirePendingDraft(long id) {
        SkillDraftRecord record = draftStore.find(id);
        if (record == null) {
            throw new ApplicationException(SkillMessage.SKILL_DRAFT_NOT_FOUND);
        }
        if (record.status() != SkillDraftStatus.PENDING) {
            throw new ApplicationException(SkillMessage.SKILL_DRAFT_ALREADY_REVIEWED);
        }
        return record;
    }

    /** 操作者必留痕（审结动作可追责——口径同安装/启停，无落空通道）。 */
    private static void requireOperator(Operator operator) {
        if (operator == null || operator.id() == null || operator.name() == null) {
            throw new ApplicationException(SkillMessage.SKILL_OPERATOR_REQUIRED);
        }
    }

    /** 在途草稿撞名回执（预检与并发兜底共形）。 */
    private static SkillDraftReceipt pendingConflict(String name) {
        return SkillDraftReceipt.rejected("已有在途同名草稿：\"" + name
                + "\"——请换名重提（或等该草稿审核落定）。");
    }

    /** 扫描面＝合成完整 SKILL.md（frontmatter＋正文）：与晋升后真实内容一致。 */
    private static String skillMdOf(SkillDraftProposal proposal) {
        return "---\nname: " + proposal.name() + "\ndescription: " + proposal.description()
                + "\n---\n\n" + proposal.content();
    }

    /** scanner Finding record → 解析态键值（JSONB 通行，读模型口径）。 */
    private List<Map<String, Object>> findingsOf(SkillSecurityScanner.ScanResult scan) {
        return scan.findings().stream()
                .map(finding -> objectMapper.convertValue(finding,
                        new TypeReference<Map<String, Object>>() { }))
                .toList();
    }

    private static String patternIdsOf(SkillSecurityScanner.ScanResult scan) {
        return scan.findings().stream()
                .map(SkillSecurityScanner.Finding::patternId)
                .distinct()
                .toList()
                .toString();
    }
}
