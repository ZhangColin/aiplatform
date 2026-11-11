package com.semgravio.aiplatform.base.knowledge.application;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftProposal;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftReceipt;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;
import com.semgravio.aiplatform.base.knowledge.domain.repository.SedimentDraftStore;

import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;

/**
 * 沉淀草稿用例（#460 数据飞轮采集层）：智能体自荐需求案例（CASE）/功能模块
 * （MODULE）/业务规则（RULE）进采集层——照技能草稿先例（ADR-0022）带血统
 * （来源项目/run/槽位）、扫描回执、终态留痕与在途上限。
 *
 * <p><b>propose 写入链</b>（拒绝一律回执原因、不抛——智能体可读可修正重提）：
 * 校验正本（{@link SedimentDraftProposal#violation()}——kind 三类/title/summary/
 * 正文上限）→ 静态安全扫描（{@link SkillSecurityScanner}——依赖内纯静态件，
 * 扫描面＝标题＋摘要＋正文合成文本，与草稿真实内容一致；DANGEROUS 拒写——scanner
 * 是廉价哨兵非安全边界，安全边界是后台「数据标注」人审门）→ 在途上限判定
 * （每项目每 kind 上限 {@value #INFLIGHT_CAP}——防单一项目淹没标注队列的辅闸，
 * 人审是主闸：应用层先查在途序号占用取最低空位，落库撞在途部分唯一索引
 * （DuplicateKeyException 归一为上限回执）→ 落库（TSID、在途态、verdict＋
 * findings 随草稿留档、载荷一期恒空面）。run 失败不回滚已写入的自荐（照
 * ADR-0022 同口径：工具执行即事实落库，无 run 级回滚概念）。</p>
 *
 * <p><b>采集埋点</b>：每次自荐（受理与拒因两形）留结构化日志行
 * {@code [sediment-propose]}（项目/类别/槽位/扫描判定/结果）——自荐事件可统计；
 * 受理行本身即采集层留痕（血统＋扫描回执随档），拒因行（校验/DANGEROUS/超限）
 * 不落库、日志是其在观测面的唯一形态。</p>
 *
 * <p><b>purgeByProject</b>：项目删除清理未终结草稿（采集层不留僵尸行）——只清
 * 在途，终态留档；编排与失败语义归 {@code ProjectLifecycleAppService} 删除流程。</p>
 *
 * <p>「数据标注」审结面（激活/拒绝/改标签三键）归 #464，升格 knw_materials 归
 * #465——本服务只承载采集层的自荐写入与清理。</p>
 */
@Slf4j
@Service
public class SedimentDraftAppService {

    /** 每项目每 kind 在途上限（防泛滥辅闸；与迁移 V59 在途序号 CHECK 1-3 同值）。 */
    public static final int INFLIGHT_CAP = 3;

    private final SedimentDraftStore draftStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SedimentDraftAppService(SedimentDraftStore draftStore) {
        this.draftStore = draftStore;
    }

    /**
     * 自荐写入：全判定链见类 javadoc；回执见 {@link SedimentDraftReceipt}。
     */
    public SedimentDraftReceipt propose(SedimentDraftProposal proposal) {
        var violation = proposal.violation();
        if (violation.isPresent()) {
            log.info("[sediment-propose] 项目 {} 类别 {} 槽位 {} 校验拒：{}", proposal.projectId(),
                    proposal.kind(), proposal.slot(), violation.get());
            return SedimentDraftReceipt.rejected(violation.get());
        }
        SedimentKind kind = SedimentKind.byKey(proposal.kind()).orElseThrow();
        SkillSecurityScanner.ScanResult scan = SkillSecurityScanner.scan(proposal.title(),
                scanSurfaceOf(proposal), Map.of());
        if (scan.verdict() == SkillSecurityScanner.Verdict.DANGEROUS) {
            log.info("[sediment-propose] 项目 {} 类别 {} 槽位 {} 扫描拒 DANGEROUS（{}）",
                    proposal.projectId(), kind.key(), proposal.slot(), patternIdsOf(scan));
            return SedimentDraftReceipt.rejected("安全扫描判定 DANGEROUS，拒写不入库。触发的"
                    + "规则：" + patternIdsOf(scan) + "——请移除相应危险内容后重提。");
        }
        List<Integer> occupied = draftStore.findPendingSlots(proposal.projectId(), kind.key());
        if (occupied.size() >= INFLIGHT_CAP) {
            log.info("[sediment-propose] 项目 {} 类别 {} 槽位 {} 在途上限拒（在途 {}/{}）",
                    proposal.projectId(), kind.key(), proposal.slot(), occupied.size(),
                    INFLIGHT_CAP);
            return SedimentDraftReceipt.rejected("该项目该类别的在途沉淀草稿已满 "
                    + INFLIGHT_CAP + " 条（后台「数据标注」审结落定后释放名额）——本条未写入。");
        }
        int inflightSlot = lowestFreeSlot(occupied);
        SedimentDraftRecord record = new SedimentDraftRecord(
                TsidGenerator.newInstance().generate(),
                kind,
                proposal.title(),
                proposal.summary(),
                proposal.content(),
                Map.of(),
                proposal.projectId(),
                proposal.runId(),
                proposal.slot(),
                inflightSlot,
                scan.verdict().name(),
                findingsOf(scan),
                SedimentDraftStatus.PENDING,
                null, null, null, null, null);
        try {
            draftStore.insert(record);
        }
        catch (DuplicateKeyException e) {
            // 并发窗内同项目同类别在途草稿抢先占满（部分唯一索引兜底）——归一为上限回执
            log.info("[sediment-propose] 项目 {} 类别 {} 槽位 {} 并发撞索引归一为上限拒",
                    proposal.projectId(), kind.key(), proposal.slot());
            return SedimentDraftReceipt.rejected("该项目该类别的在途沉淀草稿已满 "
                    + INFLIGHT_CAP + " 条（并发写入撞在途名额）——本条未写入，可稍后重试。");
        }
        log.info("[sediment-propose] 项目 {} 类别 {} 槽位 {} 扫描 {} 在途序号 {} 已留档（草稿 {}）",
                proposal.projectId(), kind.key(), proposal.slot(), scan.verdict().name(),
                inflightSlot, record.id());
        return SedimentDraftReceipt.accepted(record.id(),
                "沉淀草稿已留档待审（后台「数据标注」人工审核激活后才入知识库；未审核"
                        + "草稿不参与任何检索）。感谢沉淀。");
    }

    /**
     * 项目删除清理入口（#460，对齐技能草稿清理先例）：清该项目全部<b>在途</b>草稿
     * ——血统不留悬空（未审内容无可追责面），采集层不留僵尸行；终态留档不随删
     * （审结事实独立于项目存续）。激活升格后的知识素材与来源项目脱钩（孤儿沉淀），
     * 本口不触 knw_materials。
     */
    public void purgeByProject(long projectId) {
        draftStore.deletePendingByProject(projectId);
    }

    /** 在途序号分配：最低空位（1-3）。 */
    private static int lowestFreeSlot(List<Integer> occupied) {
        for (int slot = 1; slot <= INFLIGHT_CAP; slot++) {
            if (!occupied.contains(slot)) {
                return slot;
            }
        }
        throw new IllegalStateException("在途序号分配越界（占用=" + occupied + "）");
    }

    /** 扫描面＝标题＋摘要＋正文合成的草稿全文：与留档真实内容一致。 */
    private static String scanSurfaceOf(SedimentDraftProposal proposal) {
        return proposal.title() + "\n" + proposal.summary() + "\n\n" + proposal.content();
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
