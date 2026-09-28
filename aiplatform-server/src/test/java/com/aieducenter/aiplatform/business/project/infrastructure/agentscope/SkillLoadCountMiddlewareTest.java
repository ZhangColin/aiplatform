package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.ParsedSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillUpdateTrace;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.runtime.HarnessSkillEntry;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import reactor.core.publisher.Flux;

/**
 * 技能加载计数中间件（#261 观测面，装配缝单测）：口径＝load 实际发生——
 * {@code load_skill_through_path} 工具调用且 skillId 命中本轮装配 catalog 才
 * 计数（锚＝AgentSkill 的 name+source，即装配视图发放的库行唯一键）；非 load
 * 工具、catalog 未命中、无 catalog 均不计；计数失败降级不阻断工具流（真链路
 * 的「目录重建不计数」由口径结构性保证——中间件只挂工具调用缝，见 REST 缝
 * 与活体验证）。
 */
class SkillLoadCountMiddlewareTest {

    private final RecordingSkillStore skillStore = new RecordingSkillStore();

    private SkillLoadCountMiddleware middleware() {
        return new SkillLoadCountMiddleware(skillStore);
    }

    /** 本轮装配 catalog：一条库技能（tdd@matt 包）＋一条内置（source=builtin）。 */
    private static SkillCatalog catalog() {
        return SkillCatalog.of(List.of(
                HarnessSkillEntry.of(AgentSkill.builder()
                        .name("tdd").description("技能简介")
                        .skillContent("正文")
                        .source("https://github.com/matt/skills").build(), null),
                HarnessSkillEntry.of(AgentSkill.builder()
                        .name("prd-writing").description("技能简介")
                        .skillContent("正文")
                        .source("builtin").build(), null)));
    }

    private static ToolUseBlock loadCall(String skillId) {
        return new ToolUseBlock("call-1", SkillLoadTool.TOOL_NAME,
                Map.of("skillId", skillId, "path", "SKILL.md"));
    }

    private static RuntimeContext ctxWithCatalog() {
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SkillCatalog.class, catalog());
        return ctx;
    }

    // ---------- 口径：load 工具调用＋catalog 命中 → 按唯一键计数 ----------

    @Test
    void given_load_call_hitting_catalog_when_acting_then_record_load_by_name_and_source() {
        // 库技能 skillId＝name_source（装配视图发放即 name+来源包）
        middleware().onActing(null, ctxWithCatalog(),
                new ActingInput(List.of(loadCall("tdd_https://github.com/matt/skills"))),
                input -> Flux.empty());

        assertThat(skillStore.loads).containsExactly(
                new Load("tdd", "https://github.com/matt/skills"));
    }

    // ---------- 口径：内置（无库行锚）同链路到达 store，落库零行静默是 store 侧语义 ----------

    @Test
    void given_builtin_skill_load_when_acting_then_record_reaches_store_same_as_library() {
        middleware().onActing(null, ctxWithCatalog(),
                new ActingInput(List.of(loadCall("prd-writing_builtin"))),
                input -> Flux.empty());

        // 观测面对全库一致：中间件不区分来源，(name, builtin) 落库零行命中即静默
        assertThat(skillStore.loads).containsExactly(new Load("prd-writing", "builtin"));
    }

    // ---------- 口径：catalog 未命中 / 无 catalog → 不计（load 不会发生） ----------

    @Test
    void given_skill_id_missing_from_catalog_when_acting_then_not_recorded() {
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SkillCatalog.class, SkillCatalog.empty());

        middleware().onActing(null, ctx,
                new ActingInput(List.of(loadCall("ghost_https://x"))), input -> Flux.empty());

        assertThat(skillStore.loads).isEmpty();
    }

    @Test
    void given_no_catalog_in_context_when_acting_then_not_recorded() {
        middleware().onActing(null, RuntimeContext.empty(),
                new ActingInput(List.of(loadCall("tdd_https://github.com/matt/skills"))),
                input -> Flux.empty());

        assertThat(skillStore.loads).isEmpty();
    }

    // ---------- 口径：非 load 工具不触计数 ----------

    @Test
    void given_other_tool_call_when_acting_then_not_recorded() {
        ToolUseBlock other = new ToolUseBlock("call-2", "execute",
                Map.of("command", "mvn test"));

        middleware().onActing(null, ctxWithCatalog(), new ActingInput(List.of(other)),
                input -> Flux.empty());

        assertThat(skillStore.loads).isEmpty();
    }

    // ---------- best-effort：计数失败降级记日志，工具流照常 ----------

    @Test
    void given_store_throwing_when_acting_then_degraded_without_breaking_flow() {
        skillStore.failure = new IllegalStateException("库不可达");
        boolean[] nextInvoked = { false };

        assertThatCode(() -> middleware().onActing(null, ctxWithCatalog(),
                new ActingInput(List.of(loadCall("tdd_https://github.com/matt/skills"))),
                input -> {
                    nextInvoked[0] = true;
                    return Flux.empty();
                })).doesNotThrowAnyException();

        assertThat(nextInvoked[0]).as("计数失败不阻断——next（工具执行流）照常进入").isTrue();
    }

    // ---------- 夹具 ----------

    private record Load(String name, String source) {
    }

    /**
     * 计数记录假库：只支持 {@code recordLoad}（记录调用序列、可注入失败），
     * 其余口不触（中间件面只读写口这一处——装配视图读写另有缝测覆盖）。
     */
    private static final class RecordingSkillStore implements SkillStore {

        final List<Load> loads = new ArrayList<>();
        RuntimeException failure;

        @Override
        public void recordLoad(String name, String sourcePackage) {
            if (failure != null) {
                throw failure;
            }
            loads.add(new Load(name, sourcePackage));
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
        public boolean existsByName(String name) {
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
        public List<SkillRecord> findAssigned(SkillSlot slot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SkillRecord> findEnabledAssigned(SkillSlot slot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceAssignments(SkillSlot slot, List<Long> skillIds, Operator operator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsAssignmentForSkill(long skillId) {
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
}
