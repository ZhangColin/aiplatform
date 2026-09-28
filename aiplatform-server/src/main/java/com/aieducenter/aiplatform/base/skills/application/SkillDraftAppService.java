package com.aieducenter.aiplatform.base.skills.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDraftDetailResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDraftSummaryResponse;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillDraftStatus;
import com.aieducenter.aiplatform.base.skills.domain.error.SkillMessage;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftProposal;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftReceipt;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftRecord;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillDraftStore;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;

/**
 * 技能草稿用例（#259，ADR-0022 自产线 T1）：智能体自荐写入＋后台只读面。
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
 * <p><b>读面</b>：活跃面＝在途草稿时间倒序（终态不列——T2 审核面只见待办）；
 * 详情任意状态可查（终态留档）。草稿不参与任何装配：装配视图查询不触
 * {@code skl_skill_drafts}（结构性保证，装配缝测试钉死）。</p>
 */
@Service
public class SkillDraftAppService {

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
