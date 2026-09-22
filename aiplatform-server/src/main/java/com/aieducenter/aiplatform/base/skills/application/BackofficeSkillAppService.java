package com.aieducenter.aiplatform.base.skills.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.skills.application.dto.command.SkillInstallCommand;
import com.aieducenter.aiplatform.base.skills.application.dto.command.SkillSlotAssignCommand;
import com.aieducenter.aiplatform.base.skills.application.dto.command.SkillUpdateCommand;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDetailResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillSummaryResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillUpdateResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillUpdateTraceResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSlotAssignmentResponse;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.error.SkillMessage;
import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.ParsedSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillPackageSnapshot;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillUpdateTrace;
import com.aieducenter.aiplatform.base.skills.domain.port.BuiltinSkillCatalog;
import com.aieducenter.aiplatform.base.skills.domain.port.SkillPackageFetcher;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 后台技能库用例（#247 读面＋#248 写口＋#249 槽位指派＋#250 更新检查与显式
 * 更新）：清单（内置 classpath 合成 ∪ 库行同权呈现，空库仍非空）/ 详情
 * （frontmatter 与正文全文可读——审核面）。寻址柄两形制在此分解：
 * {@code builtin:<技能名>} 走内置目录、TSID 走库——id 是 opaque 串的口径由本层
 * 单点定形（清单行合成与详情分解同源）。
 *
 * <p>排序服务端定死：内置在前（名称序）、安装在后（来源包、名称序——跨包同名
 * 区分呈现、同包聚簇审阅）。写口（#248）：安装＝快照固化（clone 解析全部
 * SKILL.md 原子入库，版本＝装时 HEAD commit，同源去重先行；排除名单随包行
 * 持久化——更新重拉同口径）、启停＝可逆开关（操作者＝最近动作者，重复幂等）、
 * 卸载＝行删除（有指派守卫 SKL_008；包行随最后一行卸净而清）。槽位指派
 * （#249）：三职能槽位（主智能体/run 执行体/子智能体）各自独立的整包替换
 * （PUT 全量语义），装配合成动态查库——变更下一轮自然生效。更新线（#250，
 * ADR-0021）：定期只读检查远端 HEAD 打「有新版」标记（永不自动跟新）、显式
 * 更新＝按来源包重拉快照（同名行原地翻新保留 id/状态/指派，消失行删除——有
 * 指派在身拒 SKL_013）＋版本留痕追加（历史版本可查）。安装/更新事务内含 clone
 * 网络等待——照知识沉淀（embedding 调用）同款取舍，后台低频动作不为此拆事务。</p>
 */
@Service
public class BackofficeSkillAppService {

    private static final Logger logger = LoggerFactory.getLogger(BackofficeSkillAppService.class);

    private final BuiltinSkillCatalog builtinSkillCatalog;
    private final SkillPackageFetcher skillPackageFetcher;
    private final SkillStore skillStore;

    public BackofficeSkillAppService(BuiltinSkillCatalog builtinSkillCatalog,
            SkillPackageFetcher skillPackageFetcher, SkillStore skillStore) {
        this.builtinSkillCatalog = builtinSkillCatalog;
        this.skillPackageFetcher = skillPackageFetcher;
        this.skillStore = skillStore;
    }

    /**
     * 技能清单：内置（名称序）在前、安装（来源包、名称序）在后；不分页——技能
     * 库是有界目录（装什么是运营决策），对齐账号清单先例。
     */
    @Transactional(readOnly = true)
    public List<BackofficeSkillSummaryResponse> skills() {
        List<BackofficeSkillSummaryResponse> items = new ArrayList<>(
                builtinSkillCatalog.findAll().stream()
                        .map(BackofficeSkillSummaryResponse::builtin).toList());
        items.addAll(skillStore.findAll().stream()
                .map(BackofficeSkillSummaryResponse::of).toList());
        return items;
    }

    /**
     * 技能详情（审核面）：元数据＋frontmatter 全量＋正文全文。
     *
     * @throws ApplicationException SKL_001 技能不存在（未寻址内置名/TSID、畸形柄同语义）
     */
    @Transactional(readOnly = true)
    public BackofficeSkillDetailResponse detail(String id) {
        if (id.startsWith(BackofficeSkillSummaryResponse.BUILTIN_ID_PREFIX)) {
            BuiltinSkill skill = builtinSkillCatalog.findByName(
                    id.substring(BackofficeSkillSummaryResponse.BUILTIN_ID_PREFIX.length()));
            if (skill == null) {
                throw new ApplicationException(SkillMessage.SKILL_NOT_FOUND);
            }
            return BackofficeSkillDetailResponse.builtin(skill);
        }
        return BackofficeSkillDetailResponse.of(requireSkill(
                Tsid.resolve(id, SkillMessage.SKILL_NOT_FOUND)));
    }

    /**
     * 快照安装（#248，ADR-0021）：规范化地址先同源去重（不必等 clone）→ 拉取
     * 快照（clone＋解析）→ fail-fast 校验（零技能/包内重名）→ 全部条目同事务
     * 原子入库（来源包＝规范化地址、版本＝装时 HEAD、状态＝启用、操作者＝装者）
     * ＋包行落库（#250：排除名单持久化——显式更新重拉同口径；检查态初始化——
     * 安装即一次事实上的检查，标记复位）。回执＝本次装入的条目清单。
     *
     * @throws ApplicationException SKL_002 地址空；SKL_003 同源重复安装；
     *         SKL_004 克隆失败；SKL_005 未解析到技能；SKL_006 SKILL.md 不合格；
     *         SKL_007 包内重名；SKL_009 操作者缺
     */
    @Transactional
    public List<BackofficeSkillSummaryResponse> install(SkillInstallCommand command, Operator operator) {
        String sourcePackage = normalizeSourcePackage(command.repoUrl());
        if (sourcePackage == null) {
            throw new ApplicationException(SkillMessage.SKILL_INSTALL_URL_REQUIRED);
        }
        requireOperator(operator);
        if (skillStore.existsBySourcePackage(sourcePackage)) {
            throw new ApplicationException(SkillMessage.SKILL_SOURCE_ALREADY_INSTALLED);
        }
        List<String> excludeDirs = normalizeExcludes(command.excludeDirs());
        SkillPackageSnapshot snapshot = skillPackageFetcher.fetch(sourcePackage, excludeDirs);
        if (snapshot.skills().isEmpty()) {
            throw new ApplicationException(SkillMessage.SKILL_NO_SKILLS_PARSED);
        }
        requireUniqueNames(snapshot.skills());
        List<SkillRecord> records = snapshot.skills().stream()
                .map(skill -> recordOf(skill, sourcePackage, snapshot.version(), operator))
                .toList();
        skillStore.insertAll(records);
        skillStore.installPackage(sourcePackage, excludeDirs, snapshot.version());
        return records.stream().map(BackofficeSkillSummaryResponse::of).toList();
    }

    /**
     * 停用（#248 可逆开关）：退出装配候选（T3 指派面的生效语义）、不丢库行。
     * 操作者落最近管理动作；重复停用幂等（操作者留最近一次）。回执重读库行。
     *
     * @throws ApplicationException SKL_001 技能不存在（含内置柄——内置非库行无
     *         状态迁移）；SKL_009 操作者缺
     */
    @Transactional
    public BackofficeSkillSummaryResponse disable(long id, Operator operator) {
        return flipStatus(id, SkillStatus.DISABLED, operator);
    }

    /**
     * 启用（#248 可逆开关的另一侧）：恢复参与装配合成。
     *
     * @throws ApplicationException 同 {@link #disable}
     */
    @Transactional
    public BackofficeSkillSummaryResponse enable(long id, Operator operator) {
        return flipStatus(id, SkillStatus.ENABLED, operator);
    }

    /**
     * 卸载（#248）：快照行删除即彻底出库。回执＝删除前终态（确认移除了什么）。
     * 守卫：有指派在身拒绝（SKL_008，先解绑再卸）。包行随最后一行卸净而清
     * （#250：无行可查即无检查态可留）；更新留痕不删（历史事实不随行消失）。
     *
     * @throws ApplicationException SKL_001 技能不存在（含内置柄——内置非库行
     *         不可卸）；SKL_008 有指派在身（任一槽位）
     */
    @Transactional
    public BackofficeSkillSummaryResponse uninstall(long id) {
        SkillRecord record = requireSkill(id);
        if (skillStore.existsAssignmentForSkill(id)) {
            throw new ApplicationException(SkillMessage.SKILL_ASSIGNED);
        }
        if (!skillStore.delete(id)) {
            throw new ApplicationException(SkillMessage.SKILL_NOT_FOUND);
        }
        skillStore.deletePackageIfNoSkills(record.sourcePackage());
        return BackofficeSkillSummaryResponse.of(record);
    }

    /**
     * 槽位指派读面（#249）：该槽位当前指派清单（含停用行——启停是可逆开关，
     * 指派关系随行保留，运营可见实态）。
     *
     * @throws ApplicationException SKL_010 槽位不存在（未知/空键同语义）
     */
    @Transactional(readOnly = true)
    public BackofficeSlotAssignmentResponse slotAssignments(String slotKey) {
        SkillSlot slot = requireSlot(slotKey);
        return BackofficeSlotAssignmentResponse.of(slot.key(), skillStore.findAssigned(slot)
                .stream().map(BackofficeSkillSummaryResponse::of).toList());
    }

    /**
     * 槽位指派整包替换（#249，PUT 全量语义——清单即终态）：解析校验（内置柄
     * 拒 SKL_011、未寻址/畸形 TSID 同 404 SKL_001、去重）→ 该槽位行集同事务
     * delete＋insert（操作者＝最近动作者）。生效语义＝装配合成动态查库：变更后
     * 智能体下一轮自然反映、进行中 run 不定格。
     *
     * @throws ApplicationException SKL_010 槽位不存在；SKL_001 柄未寻址（库行
     *         不在）；SKL_011 内置柄不可指派；SKL_009 操作者缺
     */
    @Transactional
    public BackofficeSlotAssignmentResponse assignSlot(String slotKey, SkillSlotAssignCommand command,
            Operator operator) {
        SkillSlot slot = requireSlot(slotKey);
        requireOperator(operator);
        List<Long> skillIds = resolveAssignmentTargets(command);
        skillStore.replaceAssignments(slot, skillIds, operator);
        return slotAssignments(slot.key());
    }

    /**
     * 远端更新检查扫描轮（#250，ADR-0021「有新版」标记）：遍历全部已装来源包，
     * 只读探查远端 HEAD（ls-remote，不 clone）——与装时/最近更新版本不同即亮标
     * 记、相同即灭。逐包独立落库（单语句 upsert 原子，故意不裹整轮事务——一个
     * 远端拖不动他包）；逐包吞异常静默降级：检查失败不写状态（checked_at 不动
     * ＝「很久没查成」可观测）、不抛入运行面，留待下轮。永不自动跟新——远端删改
     * 不漂进平台，更新永远走 {@link #update} 显式点。定期轮由 scheduler 驱动
     * （测试直调本口触发）。
     *
     * @return 本轮检查后标记为「有新版」的来源包数（含此前已亮仍亮的——调度器
     *         日志面用，非增量计数）
     */
    public int checkRemoteUpdates() {
        Map<String, String> installedVersions = skillStore.findInstalledVersions();
        int marked = 0;
        for (Map.Entry<String, String> installed : installedVersions.entrySet()) {
            String sourcePackage = installed.getKey();
            try {
                String remoteHead = skillPackageFetcher.remoteHead(sourcePackage);
                boolean updateAvailable = !remoteHead.equals(installed.getValue());
                skillStore.recordCheckResult(sourcePackage, remoteHead, updateAvailable);
                if (updateAvailable) {
                    marked++;
                }
            }
            catch (RuntimeException e) {
                // 静默降级（#250 AC）：不写状态、不炸轮，留待下轮
                logger.warn("[skill-update] 远端检查未成（留待下轮）: {} ({})", sourcePackage,
                        e.getMessage());
            }
        }
        return marked;
    }

    /**
     * 显式更新（#250，ADR-0021 快照更新）：按来源包重拉快照（排除名单＝装时
     * 持久化口径）→ 远端未前进即幂等回执（from==to，不落痕不写行）→ 前进即
     * 同事务翻新：同名行原地翻新（id/状态/指派保留——TSID 柄稳定）、新技能
     * 插入（启用、操作者＝更新者）、消失行删除（有指派在身整体拒 SKL_013——
     * 先解绑再更新，卸载守卫同因）＋版本留痕追加（from→to＋操作者）＋标记复位。
     * 回执＝from/to 版本＋被移除技能名＋更新后终态行集。
     *
     * @throws ApplicationException SKL_014 来源包空；SKL_009 操作者缺；
     *         SKL_012 来源包未安装；SKL_004 克隆失败；SKL_005 远端未解析到
     *         技能；SKL_007 包内重名；SKL_013 更新将移除有指派在身的技能
     */
    @Transactional
    public BackofficeSkillUpdateResponse update(SkillUpdateCommand command, Operator operator) {
        String sourcePackage = normalizeSourcePackage(
                command == null ? null : command.sourcePackage());
        if (sourcePackage == null) {
            throw new ApplicationException(SkillMessage.SKILL_SOURCE_PACKAGE_REQUIRED);
        }
        requireOperator(operator);
        List<SkillRecord> current = skillStore.findBySourcePackage(sourcePackage);
        if (current.isEmpty()) {
            throw new ApplicationException(SkillMessage.SKILL_SOURCE_NOT_INSTALLED);
        }
        String fromVersion = current.stream().map(SkillRecord::version).max(String::compareTo).orElseThrow();
        SkillPackageSnapshot snapshot = skillPackageFetcher.fetch(sourcePackage,
                skillStore.findExcludeDirs(sourcePackage));
        if (snapshot.skills().isEmpty()) {
            throw new ApplicationException(SkillMessage.SKILL_NO_SKILLS_PARSED);
        }
        requireUniqueNames(snapshot.skills());
        // 远端未前进：幂等回执（同一 commit 内容恒同），不落痕不写行——只把
        // 检查态对齐到「已查、无新版」
        if (snapshot.version().equals(fromVersion)) {
            skillStore.recordCheckResult(sourcePackage, snapshot.version(), false);
            return updateResponse(sourcePackage, fromVersion, snapshot.version(),
                    List.of(), operator, current);
        }
        return applyUpdate(sourcePackage, fromVersion, snapshot, current, operator);
    }

    /**
     * 更新留痕读面（#250 历史版本可查）：该来源包全部更新留痕按时间倒序
     * （最近先）。留痕 append-only、卸载不删——装时版本经链条首个 from 回溯。
     *
     * @throws ApplicationException SKL_014 来源包空
     */
    @Transactional(readOnly = true)
    public List<BackofficeSkillUpdateTraceResponse> updateTraces(String sourcePackage) {
        String normalized = normalizeSourcePackage(sourcePackage);
        if (normalized == null) {
            throw new ApplicationException(SkillMessage.SKILL_SOURCE_PACKAGE_REQUIRED);
        }
        return skillStore.findTraces(normalized).stream()
                .map(BackofficeSkillUpdateTraceResponse::of).toList();
    }

    /** 更新落地（远端已前进）：三段翻新＋留痕＋标记复位，同事务原子。 */
    private BackofficeSkillUpdateResponse applyUpdate(String sourcePackage, String fromVersion,
            SkillPackageSnapshot snapshot, List<SkillRecord> current, Operator operator) {
        Map<String, SkillRecord> currentByName = new LinkedHashMap<>();
        current.forEach(record -> currentByName.put(record.name(), record));
        // 消失行守卫先行（fail-fast 整体不动）：有指派在身的行被远端删除即拒
        List<SkillRecord> removed = current.stream()
                .filter(record -> snapshot.skills().stream().noneMatch(
                        skill -> skill.name().equals(record.name())))
                .toList();
        for (SkillRecord record : removed) {
            if (skillStore.existsAssignmentForSkill(record.id())) {
                throw new ApplicationException(SkillMessage.SKILL_UPDATE_REMOVES_ASSIGNED);
            }
        }
        for (ParsedSkill skill : snapshot.skills()) {
            SkillRecord existing = currentByName.get(skill.name());
            if (existing == null) {
                skillStore.insertAll(List.of(recordOf(skill, sourcePackage, snapshot.version(), operator)));
            }
            else {
                skillStore.refreshFromSnapshot(existing.id(), skill, snapshot.version(), operator);
            }
        }
        removed.forEach(record -> skillStore.delete(record.id()));
        skillStore.insertTrace(new SkillUpdateTrace(TsidGenerator.newInstance().generate(),
                sourcePackage, fromVersion, snapshot.version(), operator.id(), operator.name(),
                LocalDateTime.now()));
        // 标记复位：更新即一次事实上的检查（remote_head＝新装版本）
        skillStore.recordCheckResult(sourcePackage, snapshot.version(), false);
        return updateResponse(sourcePackage, fromVersion, snapshot.version(),
                removed.stream().map(SkillRecord::name).toList(), operator,
                skillStore.findBySourcePackage(sourcePackage));
    }

    /** 更新回执合成（行集 → 清单行；幂等回执直用现读行集）。 */
    private static BackofficeSkillUpdateResponse updateResponse(String sourcePackage, String fromVersion,
            String toVersion, List<String> removedSkillNames, Operator operator,
            List<SkillRecord> records) {
        return new BackofficeSkillUpdateResponse(sourcePackage, fromVersion, toVersion,
                removedSkillNames, operator.id(), operator.name(),
                records.stream().map(BackofficeSkillSummaryResponse::of).toList());
    }

    /** 槽位键解析：未知/空键 404 SKL_010（三把稳定键外的寻址一律不存在）。 */
    private static SkillSlot requireSlot(String slotKey) {
        return SkillSlot.byKey(slotKey)
                .orElseThrow(() -> new ApplicationException(SkillMessage.SKILL_SLOT_NOT_FOUND));
    }

    /**
     * 指派目标解析（单点）：内置柄拒（SKL_011——内置随平台发版，装配按配置
     * 挂载无需指派）、TSID 严格解析（畸形/非正数同 404 SKL_001）、库行存在性
     * 校验（先卸载后指派的不变窗口防悬挂行）、保序去重。
     */
    private List<Long> resolveAssignmentTargets(SkillSlotAssignCommand command) {
        List<String> ids = command == null || command.skillIds() == null
                ? List.of()
                : command.skillIds();
        Set<Long> resolved = new LinkedHashSet<>();
        for (String id : ids) {
            // null 柄先拦（Tsid.resolve 对 null 是 NPE 非 404）——与畸形柄同语义
            if (id != null && id.startsWith(BackofficeSkillSummaryResponse.BUILTIN_ID_PREFIX)) {
                throw new ApplicationException(SkillMessage.SKILL_BUILTIN_NOT_ASSIGNABLE);
            }
            long skillId = Tsid.resolve(id == null ? "" : id, SkillMessage.SKILL_NOT_FOUND);
            requireSkill(skillId);
            resolved.add(skillId);
        }
        return List.copyOf(resolved);
    }

    private BackofficeSkillSummaryResponse flipStatus(long id, SkillStatus status, Operator operator) {
        requireSkill(id);
        requireOperator(operator);
        skillStore.updateStatus(id, status, operator);
        return BackofficeSkillSummaryResponse.of(requireSkill(id));
    }

    private SkillRecord requireSkill(long id) {
        SkillRecord record = skillStore.find(id);
        if (record == null) {
            throw new ApplicationException(SkillMessage.SKILL_NOT_FOUND);
        }
        return record;
    }

    /** 操作者必留痕（#248 全程）：缺头/缺肢即拦（知识治理同款，无落空通道）。 */
    private static void requireOperator(Operator operator) {
        if (operator == null || operator.id() == null || operator.name() == null) {
            throw new ApplicationException(SkillMessage.SKILL_OPERATOR_REQUIRED);
        }
    }

    /** 包内重名守卫：来源包＋名称是库唯一键，同包同名入库必炸——装前 fail-fast。 */
    private static void requireUniqueNames(List<ParsedSkill> skills) {
        Set<String> seen = new HashSet<>();
        for (ParsedSkill skill : skills) {
            if (!seen.add(skill.name())) {
                throw new ApplicationException(SkillMessage.SKILL_NAME_CONFLICT_IN_PACKAGE);
            }
        }
    }

    /**
     * 快照条目 → 库行：TSID 现生成、状态＝启用、操作者＝装者/更新者。新装行的
     * 「有新版」标记恒 false——装/更新时刻版本即远端 HEAD（快照锚与检查同源）。
     */
    private static SkillRecord recordOf(ParsedSkill skill, String sourcePackage, String version,
            Operator operator) {
        return new SkillRecord(TsidGenerator.newInstance().generate(), skill.name(),
                skill.description(), sourcePackage, version, SkillStatus.ENABLED,
                skill.frontmatter(), skill.content(), operator.id(), operator.name(), false);
    }

    /**
     * 来源包身份规范化（#248 同源判定）：trim → 去尾斜杠 → 去尾 {@code .git}
     * ——同一仓库的常见地址变体归一；https/ssh 指向同仓视为异源（去重按地址
     * 身份，不做远端等价解析）。空白即无效（返 null）。
     */
    private static String normalizeSourcePackage(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        String normalized = repoUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /** 排除名单归一：trim、去空白项、去重；null 容缺。 */
    private static List<String> normalizeExcludes(List<String> excludeDirs) {
        if (excludeDirs == null) {
            return List.of();
        }
        return excludeDirs.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
