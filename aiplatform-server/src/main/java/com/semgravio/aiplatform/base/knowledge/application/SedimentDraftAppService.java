package com.semgravio.aiplatform.base.knowledge.application;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeSedimentDraftDetailResponse;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeSedimentDraftSummaryResponse;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;
import com.semgravio.aiplatform.base.knowledge.domain.error.KnowledgeMessage;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
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
 * <p><b>「数据标注」审结面</b>（#464）：队列（按项目/类别/状态过滤＋分页，排序
 * 自荐时间倒序）＋详情（任意状态——终态留档可查）＋三键——激活（原样升格不改稿：
 * 在途翻已激活＋操作者留痕；升格 knw_materials 归 #465，本键先落审结终态）/拒绝
 * （理由必填＋终态留档，库级 CHECK 双锁）/改标签（在途内调类别＋占目标类别在途
 * 序号最低空位——目标类别满即拒）。三键终态语义照技能草稿审核先例（ADR-0022）：
 * CAS 条件更新（{@code WHERE status=在途}，零行命中＝并发审结 → 409 已审结）、
 * 已审结拒、操作者必留痕（缺 400 KNW_006）。</p>
 *
 * <p><b>采集埋点（审结侧）</b>：每次标注动作留结构化日志行 {@code [sediment-annotate]}
 * （草稿/类别/动作/操作者）——标注动作留痕可统计（#457 采集面口径）。</p>
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

    // ---------- #464 后台「数据标注」审结面 ----------

    /**
     * 标注队列：三过滤维度（项目精确/类别单选/状态单选）可组合、均可缺省（缺省＝
     * 全量）；排序定死＝自荐时间倒序（id 倒序稳定）；分页钳制/换算全部来自框架
     * {@link Pagination}（1 基、缺省 1/20、上界 100 静默贴边），页码原样回显零
     * 手写算术（照 {@code BackofficeKnowledgeAppService#materials} 先例）。类别
     * 过滤值经 {@link SedimentKind} 解析（大小写不敏感），非法即 400 KNW_011
     * （与写入校验同口径）；项目过滤值由控制器侧宽松解析（{@code Tsid.parseOrNull}
     * 既定口径——畸形＝缺省不过滤返回全量 200，与订单/素材过滤面同）。
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeSedimentDraftSummaryResponse> queue(Long projectId,
            String kindKey, SedimentDraftStatus status, Pagination pagination) {
        String normalizedKind = normalizeKindFilter(kindKey);
        // offset 收窄 long→int：存储接口形参是 int，与旧 (safePage-1)*size 整型算术
        // 同 overflow 语义，不额外设防（#191 同口径）
        var result = draftStore.searchQueue(projectId, normalizedKind, status,
                (int) pagination.offset(), pagination.limit());
        return new PageResponse<>(
                result.items().stream().map(BackofficeSedimentDraftSummaryResponse::of).toList(),
                result.total(), pagination.page(), pagination.size());
    }

    /**
     * 草稿详情（任意状态——终态留档可查）：审核面所见即写入时扫描回执＋终态留痕。
     *
     * @throws ApplicationException KNW_008 草稿不存在（未寻址/畸形 TSID 同语义）
     */
    @Transactional(readOnly = true)
    public BackofficeSedimentDraftDetailResponse draft(long id) {
        return BackofficeSedimentDraftDetailResponse.of(requireDraft(id));
    }

    /**
     * 激活（#464 三键之一，人审采纳）：在途翻已激活＋操作者留痕（CAS 零行命中＝
     * 并发审结 → 409 已审结）。激活原样升格不改稿——升格 knw_materials（段落分块
     * 入库＋混检注入）归 #465，本键先落审结终态（#465 接通前「已激活」草稿已出
     * 在途队列、不占在途名额，升格动作随 #465 落地）。回执＝终态详情（留痕形状直读）。
     *
     * @throws ApplicationException KNW_008 草稿不存在；KNW_009 已审结；
     *         KNW_006 操作者缺
     */
    @Transactional
    public BackofficeSedimentDraftDetailResponse activate(long id, Operator operator) {
        requirePendingDraft(id);
        requireOperator(operator);
        if (!draftStore.markActivated(id, operator)) {
            // CAS 零行命中＝并发审结（激活/拒绝抢先落定）——抛即整体无事发生
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED);
        }
        log.info("[sediment-annotate] 草稿 {} 激活（操作者 {} {}）", id, operator.id(),
                operator.name());
        return BackofficeSedimentDraftDetailResponse.of(requireDraft(id));
    }

    /**
     * 拒绝（#464 三键之一，终态留档）：理由必填（应用层预检 400＋库级 CHECK 双锁）
     * ＋在途翻已拒绝＋操作者留痕（CAS 同激活）。拒绝即终态：不再列在途队列、详情
     * 仍可查；不改稿不复活——重提只能靠未来会话产生新草稿。回执＝终态详情。
     *
     * @throws ApplicationException KNW_008 草稿不存在；KNW_009 已审结；
     *         KNW_010 理由空；KNW_006 操作者缺
     */
    @Transactional
    public BackofficeSedimentDraftDetailResponse reject(long id, String reason,
            Operator operator) {
        requirePendingDraft(id);
        requireOperator(operator);
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SEDIMENT_REJECT_REASON_REQUIRED);
        }
        if (!draftStore.markRejected(id, normalized, operator)) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED);
        }
        log.info("[sediment-annotate] 草稿 {} 拒绝（操作者 {} {}）", id, operator.id(),
                operator.name());
        return BackofficeSedimentDraftDetailResponse.of(requireDraft(id));
    }

    /**
     * 改标签（#464 三键之一，在途内调类别）：目标类别限一期三类（KNW_011 同写入
     * 校验口径）；占目标类别在途序号的最低空位（排除自身——自改自的序号不算占用；
     * 目标类别在途已满 3 即拒 409 KNW_012，部分唯一索引拦并发窗归一为同码）。同类
     * 别幂等 no-op（200 原样回执）。不翻终态、不动正文——纯队列分诊修正。回执＝
     * 详情（新类别直读）。<b>操作者留痕面＝采集埋点日志行</b>（{@code [sediment-annotate]}）：
     * 终态四列是审结语义、在途行恒 null 的 seam 断言钉死该形状，改标签不翻终态不落
     * 终态列——持久留痕如需独立列（V60 生长面）由后续票裁决（ADR-0038 补记）。
     *
     * @throws ApplicationException KNW_008 草稿不存在；KNW_009 已审结；
     *         KNW_011 类别非法；KNW_012 目标类别在途满；KNW_006 操作者缺
     */
    @Transactional
    public BackofficeSedimentDraftDetailResponse relabel(long id, String kindKey,
            Operator operator) {
        SedimentDraftRecord record = requirePendingDraft(id);
        requireOperator(operator);
        SedimentKind target = SedimentKind.byKey(kindKey)
                .orElseThrow(() -> new ApplicationException(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_KIND_INVALID));
        if (record.kind() != target) {
            List<Integer> occupied = draftStore.findPendingSlotsExcluding(id,
                    record.projectId(), target.key());
            if (occupied.size() >= INFLIGHT_CAP) {
                throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SEDIMENT_TARGET_KIND_FULL);
            }
            int inflightSlot = lowestFreeSlot(occupied);
            try {
                if (!draftStore.updateKind(id, target.key(), inflightSlot)) {
                    // CAS 零行命中＝并发审结抢先落定
                    throw new ApplicationException(
                            KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED);
                }
            }
            catch (DuplicateKeyException e) {
                // 并发窗内目标类别同序号抢先占住（部分唯一索引兜底）——归一为目标满拒
                throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SEDIMENT_TARGET_KIND_FULL);
            }
        }
        log.info("[sediment-annotate] 草稿 {} 改标签 {} → {}（操作者 {} {}）", id,
                record.kind().key(), target.key(), operator.id(), operator.name());
        return BackofficeSedimentDraftDetailResponse.of(requireDraft(id));
    }

    /** 草稿寻址守卫（详情/三键共用前置）：查无 404。 */
    private SedimentDraftRecord requireDraft(long id) {
        SedimentDraftRecord record = draftStore.find(id);
        if (record == null) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND);
        }
        return record;
    }

    /** 在途草稿守卫（三键共用前置）：查无 404、终态 409（已审结拒——重提为新草稿）。 */
    private SedimentDraftRecord requirePendingDraft(long id) {
        SedimentDraftRecord record = requireDraft(id);
        if (record.status() != SedimentDraftStatus.PENDING) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED);
        }
        return record;
    }

    /** 操作者必留痕（审结动作可追责——照技能链 SKL_009/素材治理 KNW_006 口径）。 */
    private static void requireOperator(Operator operator) {
        if (operator == null || operator.id() == null || operator.name() == null) {
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_OPERATOR_REQUIRED);
        }
    }

    /** 类别过滤值归一：空白＝不过滤；非法键 400（与写入校验同口径，回执带合法取值）。 */
    private static String normalizeKindFilter(String kindKey) {
        if (kindKey == null || kindKey.isBlank()) {
            return null;
        }
        return SedimentKind.byKey(kindKey)
                .orElseThrow(() -> new ApplicationException(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_KIND_INVALID))
                .key();
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
