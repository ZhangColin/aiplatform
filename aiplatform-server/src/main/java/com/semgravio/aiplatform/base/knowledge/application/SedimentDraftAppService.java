package com.semgravio.aiplatform.base.knowledge.application;

import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeSedimentDraftDetailResponse;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeSedimentDraftSummaryResponse;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;
import com.semgravio.aiplatform.base.knowledge.domain.error.KnowledgeMessage;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeChunker;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;
import com.semgravio.aiplatform.base.knowledge.domain.repository.SedimentDraftStore;

/**
 * 沉淀草稿用例（#460 数据飞轮采集层，现由 #641 事后提炼写入候选经验）：草稿带血统
 * （来源项目/run/槽位）、扫描回执、终态留痕。候选经验的来源五件由事后提炼填写
 * （#641：来源项目名快照/版本号/提交哈希/原句/提炼批次），人审据此回溯。
 *
 * <p><b>写入链</b>：无应用服务写入入口——#638 拆自荐写入（旧「模型自荐沉淀」
 * propose 方法随死代码清场移除），生产候选经验由事后提炼（
 * {@code ExperienceDistillationAppService}）校验＋静态扫描后经
 * {@code ExperienceDistillationLedgerAppService#land} 直写 store。
 * run 失败不回滚已写入的草稿（照 ADR-0022 同口径：工具执行即事实落库，无 run
 * 级回滚概念）。</p>
 *
 * <p><b>purgeByProject</b>：项目删除清理未终结草稿（采集层不留僵尸行）——只清在途，
 * 终态留档；编排与失败语义归 {@code ProjectLifecycleAppService} 删除流程。</p>
 *
 * <p><b>「数据标注」审结面</b>（#464）：队列（按项目/类别/状态过滤＋分页，排序创建
 * 时间倒序）＋详情（任意状态——终态留档可查）＋三键——激活（原样升格不改稿：在途
 * 翻已激活＋操作者留痕＋升格 knw_materials，#465 接通——段落分块入库、kind 随草稿、
 * 与来源项目脱钩）/拒绝（理由必填＋终态留档，库级 CHECK 双锁）/改标签（在途内调
 * 类别——#642 起纯类别调整，不再占名额）。三键终态语义照技能草稿审核先例
 * （ADR-0022）：CAS 条件更新（{@code WHERE status=在途}，零行命中＝并发审结 →
 * 409 已审结）、已审结拒、操作者必留痕（缺 400 KNW_006）。</p>
 *
 * <p><b>采集埋点（审结侧）</b>：每次标注动作留结构化日志行 {@code [sediment-annotate]}
 * （草稿/类别/动作/操作者）——标注动作留痕可统计（#457 采集面口径）。</p>
 */
@Slf4j
@Service
public class SedimentDraftAppService {

    /**
     * 孤儿沉淀的登记面展示名（#465）：激活升格素材与来源项目脱钩（project_id
     * 置空），来源项目名不再可考也不承载语义——命中/登记面统一展示平台侧固定名。
     */
    static final String ORPHAN_PROJECT_NAME = "平台沉淀";

    private final SedimentDraftStore draftStore;
    private final KnowledgeAppService knowledgeAppService;

    public SedimentDraftAppService(SedimentDraftStore draftStore,
            KnowledgeAppService knowledgeAppService) {
        this.draftStore = draftStore;
        this.knowledgeAppService = knowledgeAppService;
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
     * 全量）；排序定死＝创建时间倒序（id 倒序稳定）；分页钳制/换算全部来自框架
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
     * 并发审结 → 409 已审结）＋升格 knw_materials（#465：kind 随草稿、source_ref
     * ＝草稿 id、project_id 置空＝孤儿沉淀与来源项目脱钩、正文段落级分块入库、
     * meta 带草稿血统；分块/混检口径与 PRD 沉淀同款）。升格失败口径＝知识线
     * 既有降级契约：embedding 不可用记日志跳过（丢失容忍，A5 §1）不翻回终态，
     * 向量库写失败上抛整体回滚（真实错误非降级范畴）。回执＝终态详情（留痕形状
     * 直读）。
     *
     * @throws ApplicationException KNW_008 草稿不存在；KNW_009 已审结；
     *         KNW_006 操作者缺
     */
    @Transactional
    public BackofficeSedimentDraftDetailResponse activate(long id, Operator operator) {
        SedimentDraftRecord record = requirePendingDraft(id);
        requireOperator(operator);
        if (!draftStore.markActivated(id, operator)) {
            // CAS 零行命中＝并发审结（激活/拒绝抢先落定）——抛即整体无事发生
            throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED);
        }
        promoteToMaterial(record);
        log.info("[sediment-annotate] 草稿 {} 激活（操作者 {} {}）", id, operator.id(),
                operator.name());
        return BackofficeSedimentDraftDetailResponse.of(requireDraft(id));
    }

    /**
     * 激活升格（#465）：草稿原样升格知识素材（不改稿——标题/正文即素材标题/块）——
     * 段落级分块（{@link KnowledgeChunker}，PRD 沉淀 #30 同款口径）＋孤儿脱钩
     * （project_id 置空，来源项目删除不影响已激活知识）。meta 带 sedimentDraftId/
     * summary 血统——经 source_ref＝草稿 id 与 knw_sediment_drafts 互溯。
     */
    private void promoteToMaterial(SedimentDraftRecord record) {
        List<String> chunks = KnowledgeChunker.byParagraph(record.content());
        knowledgeAppService.index(new KnowledgeSpec(
                record.kind().key(),
                String.valueOf(record.id()),
                null,
                ORPHAN_PROJECT_NAME,
                record.title(),
                chunks,
                Map.of("sedimentDraftId", String.valueOf(record.id()),
                        "summary", record.summary())));
        log.info("[sediment-annotate] 草稿 {} 激活升格 knw_materials（kind={} sourceRef={}，{} 块入料）",
                record.id(), record.kind().key(), record.id(), chunks.size());
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
     * 校验口径）；同类别幂等 no-op（200 原样回执）。<b>#642 起只调整类别</b>——不再
     * 检查或在途占位（名额机制已拆）。不翻终态、不动正文——纯队列分诊修正。回执＝
     * 详情（新类别直读）。<b>操作者留痕面＝采集埋点日志行</b>（{@code [sediment-annotate]}）：
     * 终态四列是审结语义、在途行恒 null 的 seam 断言钉死该形状，改标签不翻终态不落
     * 终态列——持久留痕如需独立列（V60 生长面）由后续票裁决（ADR-0038 补记）。
     *
     * @throws ApplicationException KNW_008 草稿不存在；KNW_009 已审结；
     *         KNW_011 类别非法；KNW_006 操作者缺
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
            if (!draftStore.updateKind(id, target.key())) {
                // CAS 零行命中＝并发审结抢先落定
                throw new ApplicationException(
                        KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_ALREADY_REVIEWED);
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
}
