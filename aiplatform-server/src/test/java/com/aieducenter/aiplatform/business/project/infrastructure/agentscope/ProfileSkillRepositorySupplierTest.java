package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.ParsedSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillUpdateTrace;
import com.aieducenter.aiplatform.base.skills.domain.port.BuiltinSkillCatalog;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;

/**
 * 按智能体配置的技能集装配（#94 技能位接线 → #249 槽位装配合成，缝 2 单测半
 * 面——库技能侧用可变假库钉死合成语义；真链路动态性在 {@code
 * BackofficeSkillSeamTest} REST 缝上验）。合成断言：某槽位装配面＝内置∪该槽位
 * <b>已指派且启用</b>的库技能（动态查库视图——同一仓库实例内容随库变）；他槽位
 * 技能不出现。
 */
class ProfileSkillRepositorySupplierTest {

    private final FakeSkillStore skillStore = new FakeSkillStore();

    private ProfileSkillRepositorySupplier supplier() {
        return new ProfileSkillRepositorySupplier(
                new StubBuiltinCatalog(List.of(new BuiltinSkill("prd-writing",
                        "撰写或修订 PRD 时使用——固定七章节模板与平实写法规范。",
                        Map.of("name", "prd-writing"), "# PRD 写作技能\n七章节模板。"))),
                skillStore);
    }

    // ---------- 装配合成：某槽位面＝内置∪该槽位已指派且启用；他槽位不出现 ----------

    @Test
    void given_assignments_when_skill_repositories_then_union_and_slot_scoped() {
        // main 槽指派 tdd；executor 槽指派 code-review；subagent 槽指派 review-pack
        skillStore.assign(SkillSlot.MAIN, record(101, "tdd", "matt 包"));
        skillStore.assign(SkillSlot.EXECUTOR, record(202, "code-review", "matt 包"));
        skillStore.assign(SkillSlot.SUBAGENT, record(303, "review-pack", "superpowers 包"));

        ProfileSkillRepositorySupplier supplier = supplier();
        AgentWorkspace dev = new AgentWorkspace.ProjectDev("42", "ws-42-dev");

        // 主智能体＝内置（prd-writing）∪ main 槽库技能（tdd）；executor/subagent 槽技能不出现
        List<AgentSkillRepository> mainRepos =
                supplier.skillRepositoriesFor(AgentProfile.MAIN.key(), dev);
        assertThat(mainRepos).hasSize(2);
        assertThat(names(mainRepos.get(0))).containsExactly("prd-writing");
        assertThat(names(mainRepos.get(1))).containsExactly("tdd");

        // 执行体＝executor 槽库技能（无内置——PRD 写作是需求侧资产，不全员摊内置）；
        // main/subagent 槽技能不出现
        assertThat(supplier.skillRepositoriesFor(AgentProfile.EXECUTOR.key(), dev))
                .hasSize(1);
        assertThat(names(supplier.skillRepositoriesFor(AgentProfile.EXECUTOR.key(), dev).get(0)))
                .containsExactly("code-review");

        // 子智能体槽视图（self-test 键）＝subagent 槽库技能；不继承执行体面
        assertThat(names(supplier.skillRepositoriesFor(ProfileSubagentSupplier.SELF_TEST_NAME, dev)
                .get(0)))
                .containsExactly("review-pack");

        // 无配置语境＝空集（无挂载即框架不注入 <available_skills>）
        assertThat(supplier.skillRepositoriesFor("naming", new AgentWorkspace.Local(null)))
                .isEmpty();
        assertThat(supplier.skillRepositoriesFor(null, dev)).isEmpty();
    }

    // ---------- 停用退出＋动态查库（同一仓库实例内容随库变，非装配时固化） ----------

    @Test
    void given_disabled_or_reassigned_when_reread_view_then_reflects_latest() {
        skillStore.assign(SkillSlot.EXECUTOR, record(101, "tdd", "matt 包"));
        skillStore.assign(SkillSlot.EXECUTOR, record(102, "systematic-debugging", "matt 包"));

        ProfileSkillRepositorySupplier supplier = supplier();
        AgentWorkspace dev = new AgentWorkspace.ProjectDev("42", "ws-42-dev");
        AgentSkillRepository executorView = supplier
                .skillRepositoriesFor(AgentProfile.EXECUTOR.key(), dev).get(0);

        // 初始：两技能在面
        assertThat(names(executorView)).containsExactly("tdd", "systematic-debugging");

        // 停用即退出装配候选（同一实例重读——动态查库，非固化快照）
        skillStore.setStatus(101, SkillStatus.DISABLED);
        assertThat(names(executorView)).containsExactly("systematic-debugging");

        // 解绑（整包替换语义）下一读即反映
        skillStore.unassign(SkillSlot.EXECUTOR, 102);
        assertThat(names(executorView)).isEmpty();

        // 启用恢复参与合成
        skillStore.setStatus(101, SkillStatus.ENABLED);
        assertThat(names(executorView)).containsExactly("tdd");
    }

    // ---------- 库技能同名覆盖内置（指派显式意图优先——顺序即框架覆盖语义） ----------

    @Test
    void given_library_skill_named_as_builtin_when_main_assembled_then_library_registered_after() {
        skillStore.assign(SkillSlot.MAIN, record(101, "prd-writing", "竞品对照包"));

        ProfileSkillRepositorySupplier supplier = supplier();
        List<AgentSkillRepository> repos = supplier.skillRepositoriesFor(
                AgentProfile.MAIN.key(), new AgentWorkspace.ProjectDev("42", "ws-42-dev"));

        // 内置在前（低优先级）、库在后（高优先级）——框架同名冲突按后注册覆盖，
        // 顺序即覆盖语义（框架合并行为本身不在此测）
        assertThat(repos).hasSize(2);
        assertThat(names(repos.get(0))).containsExactly("prd-writing");
        assertThat(repos.get(1).getSkill("prd-writing").getSource()).isEqualTo("竞品对照包");
    }

    // ---------- #253 scripts 开放面：有 shell 的槽位带资源，主智能体结构性 a-only ----------

    @Test
    void given_scripts_in_record_when_assembled_then_shell_slots_carry_resources_main_empty() {
        // 同一技能三槽同指（ADR-0021 内容面 c 开放面＝有 shell 的槽位）
        skillStore.assign(SkillSlot.MAIN, recordWithScripts(101, "tdd", "matt 包"));
        skillStore.assign(SkillSlot.EXECUTOR, recordWithScripts(102, "tdd", "matt 包"));
        skillStore.assign(SkillSlot.SUBAGENT, recordWithScripts(103, "tdd", "matt 包"));

        ProfileSkillRepositorySupplier supplier = supplier();
        AgentWorkspace dev = new AgentWorkspace.ProjectDev("42", "ws-42-dev");

        // executor 槽（容器内 shell）：scripts 随装配面发放——load 工具可读、写盘可跑
        AgentSkill executorSkill = supplier
                .skillRepositoriesFor(AgentProfile.EXECUTOR.key(), dev).get(0).getSkill("tdd");
        assertThat(executorSkill.getResources())
                .containsEntry("scripts/run-tests.sh", "#!/bin/bash\nset -e\n");

        // subagent 槽（self-test 白名单含跑测试命令的 shell）：同开
        AgentSkill subagentSkill = supplier
                .skillRepositoriesFor(ProfileSubagentSupplier.SELF_TEST_NAME, dev).get(0)
                .getSkill("tdd");
        assertThat(subagentSkill.getResources())
                .containsEntry("scripts/run-tests.sh", "#!/bin/bash\nset -e\n");

        // main 槽（ProjectReadOnly 禁 shell）：结构性 a-only——resources 恒空，
        // load 工具枚举无 scripts 入口，拿不到
        AgentSkill mainSkill = supplier
                .skillRepositoriesFor(AgentProfile.MAIN.key(), dev).get(1).getSkill("tdd");
        assertThat(mainSkill.getResources()).isEmpty();
    }

    // ---------- 行为回归锚：内置七章节（#94 起保持） ----------

    @Test
    void builtin_repository_contains_prd_writing_skill_with_seven_chapters() throws IOException {
        // 七章节模板不再活在角色卡 prompt，而在此内置技能资产——加载真目录断言
        // 正文仍含七章节（PRD 仍按七章节产出）；内置目录经端口适配进装配与后台
        // 清单同源（#249 装配缝归一），真目录断言保持直读
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            AgentSkill skill = repo.getSkill("prd-writing");
            assertThat(skill).isNotNull();
            assertThat(skill.getSkillContent())
                    .contains("需求背景", "目标用户", "核心场景", "范围边界",
                            "关键约束", "功能清单", "待定项");
        }
    }

    // ---------- 夹具 ----------

    private static List<String> names(AgentSkillRepository repo) {
        return repo.getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    private static SkillRecord record(long id, String name, String sourcePackage) {
        return new SkillRecord(id, name, "技能简介", sourcePackage, "commit-x",
                SkillStatus.ENABLED, Map.of("name", name, "description", "技能简介"),
                "正文", Map.of(), null, null, null);
    }

    /** 带 scripts 资源面的条目（#253 开放面夹具）。 */
    private static SkillRecord recordWithScripts(long id, String name, String sourcePackage) {
        return new SkillRecord(id, name, "技能简介", sourcePackage, "commit-x",
                SkillStatus.ENABLED, Map.of("name", name, "description", "技能简介"),
                "正文", Map.of("scripts/run-tests.sh", "#!/bin/bash\nset -e\n"),
                null, null, null);
    }

    /**
     * 可变假库（缝 2 单测半面）：条目行＋指派行分持，状态翻转/解绑即时可见——
     * 模拟动态查库（真链路动态性在 REST 缝验）。写口方法不支持（REST 缝的验面）。
     */
    private static final class FakeSkillStore implements SkillStore {

        private final Map<Long, SkillRecord> rows = new LinkedHashMap<>();
        private final Map<SkillSlot, List<Long>> assignments = new LinkedHashMap<>();

        void assign(SkillSlot slot, SkillRecord record) {
            rows.put(record.id(), record);
            assignments.computeIfAbsent(slot, key -> new ArrayList<>()).add(record.id());
        }

        void setStatus(long id, SkillStatus status) {
            SkillRecord row = rows.get(id);
            rows.put(id, new SkillRecord(row.id(), row.name(), row.description(),
                    row.sourcePackage(), row.version(), status, row.frontmatter(),
                    row.content(), row.resources(), row.operatorId(), row.operatorName(),
                    row.updateAvailable()));
        }

        void unassign(SkillSlot slot, long id) {
            assignments.getOrDefault(slot, List.of()).remove(id);
        }

        @Override
        public List<SkillRecord> findAssigned(SkillSlot slot) {
            return assignments.getOrDefault(slot, List.of()).stream()
                    .map(rows::get).toList();
        }

        @Override
        public List<SkillRecord> findEnabledAssigned(SkillSlot slot) {
            return findAssigned(slot).stream()
                    .filter(record -> record.status() == SkillStatus.ENABLED)
                    .toList();
        }

        @Override
        public void replaceAssignments(SkillSlot slot, List<Long> skillIds, Operator operator) {
            throw new UnsupportedOperationException("单测假库不覆盖写口（REST 缝验）");
        }

        @Override
        public boolean existsAssignmentForSkill(long skillId) {
            return assignments.values().stream().flatMap(List::stream)
                    .anyMatch(id -> id == skillId);
        }

        @Override
        public List<SkillRecord> findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillRecord find(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsBySourcePackage(String sourcePackage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertAll(List<SkillRecord> records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateStatus(long id, SkillStatus status, Operator operator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> findInstalledVersions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> findExcludeDirs(String sourcePackage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void installPackage(String sourcePackage, List<String> excludeDirs, String headVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordCheckResult(String sourcePackage, String remoteHead, boolean updateAvailable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deletePackageIfNoSkills(String sourcePackage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SkillRecord> findBySourcePackage(String sourcePackage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refreshFromSnapshot(long id, ParsedSkill skill, String version, Operator operator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertTrace(SkillUpdateTrace trace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SkillUpdateTrace> findTraces(String sourcePackage) {
            throw new UnsupportedOperationException();
        }
    }

    /** 内置目录桩（固定一份 prd-writing——真目录断言另见回归锚用例）。 */
    private record StubBuiltinCatalog(List<BuiltinSkill> skills) implements BuiltinSkillCatalog {

        @Override
        public List<BuiltinSkill> findAll() {
            return skills;
        }

        @Override
        public BuiltinSkill findByName(String name) {
            return skills.stream().filter(skill -> skill.name().equals(name)).findFirst()
                    .orElse(null);
        }
    }
}
