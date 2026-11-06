package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;

/**
 * #363 技能物化面宿主抑制回归：项目容器姿态（ProjectReadOnly 主智能体——issue
 * 现场：访谈会话每轮 WARN {@code Failed to stage skill 'prd-writing' ... Read-only
 * file system}）装配后，框架 {@code HarnessSkillMiddleware} 的
 * {@code MarketplaceStager} 必须被卸除——物化面是纯宿主 NIO（不走
 * abstractFilesystem），而容器根 {@code /workspace} 对它是宿主路径：宿主不可写即
 * 每轮 WARN，可写则静默落宿主垃圾（实测 {@code C:\workspace\.skills-cache\...}，
 * 容器内不存在）。
 *
 * <p>两断言互补：装配面（stager 字段卸除——全宿主确定性）＋症状面（真驱框架
 * 每轮钩子 {@code onSystemPrompt}，宿主可写环境下物化尝试必留痕）。模型经自注册
 * {@code probe363:} 桩工厂解析（SDK 公开 {@code ModelRegistry.registerFactory}，
 * 仿 VolcengineModelRegistrar——测试不依赖任何模型环境 key，桩不被调用）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentscopeSkillStageSuppressionTest {

    /** 探针技能名（唯一命名——宿主 /workspace/.skills-cache 下不与既有物重叠）。 */
    private static final String PROBE_SKILL = "probe-skill-363";

    private static final String PROBE_SOURCE = "probe-363";

    /** 桩模型串前缀（注册 {@code probe363:.+} 工厂——模型实例不被调用）。 */
    private static final String PROBE_MODEL = "probe363:stub-model";

    @TempDir
    static Path unused;

    @BeforeAll
    static void registerProbeModelFactory() {
        // 构建期模型解析不触网不依赖环境 key（同 VolcengineModelRegistrar 公开注册面）
        io.agentscope.core.model.ModelRegistry.registerFactory(
                "probe363:.+", (modelId, context) -> org.mockito.Mockito.mock(
                        io.agentscope.core.model.Model.class));
    }

    private static final AgentToolkitSupplier TOOLKITS = (agentKey, workspace, toolSpec) -> new Toolkit();

    private static final AgentSubagentSupplier SUBAGENTS = (agentKey, workspace) -> List.of();

    private static final AgentMiddlewareSupplier NO_MIDDLEWARES = (agentKey, workspace) -> List.of();

    /** 桩模型串（构建期解析即注册工厂直构——无通道语境、不触网）。 */
    private static final EdgeModelSupplier EDGE_MODELS = new EdgeModelSupplier() {
        @Override
        public EdgeModel compactionModel() {
            return new EdgeModel(PROBE_MODEL, null);
        }

        @Override
        public EdgeModel precheckModel() {
            return new EdgeModel(PROBE_MODEL, null);
        }

            @Override
            public EdgeModel subagentModel() {
                return null; // 随父级（#424 缺省同执行体档）
            }
    };

    /** 单技能桩仓库（a-only 形——resources 空；物化面照样无条件 createDirectories）。 */
    private static final class ProbeSkillRepository implements AgentSkillRepository {

        @Override
        public AgentSkill getSkill(String name) {
            return PROBE_SKILL.equals(name) ? probeSkill() : null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.of(PROBE_SKILL);
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.of(probeSkill());
        }

        private static AgentSkill probeSkill() {
            return AgentSkill.builder()
                    .metadata(Map.of())
                    .name(PROBE_SKILL)
                    .description("#363 探针技能")
                    .skillContent("# 探针\n\n#363 物化面探针。")
                    .source(PROBE_SOURCE)
                    .build();
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return PROBE_SKILL.equals(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("probe", PROBE_SOURCE, false);
        }

        @Override
        public String getSource() {
            return PROBE_SOURCE;
        }

        @Override
        public void setWriteable(boolean writeable) {
        }

        @Override
        public boolean isWriteable() {
            return false;
        }
    }

    private HarnessAgent buildMainReadOnlyAgent() {
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS,
                (agentKey, workspace) -> List.of(new ProbeSkillRepository()), SUBAGENTS,
                NO_MIDDLEWARES, EDGE_MODELS, new AgentscopeProperties());
        return factory.obtain("platform-agent-363", "sys", PROBE_MODEL,
                null, new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "main", null);
    }

    private static Optional<HarnessSkillMiddleware> skillMiddlewareOf(HarnessAgent agent) {
        return agent.getDelegate().getMiddlewares().stream()
                .filter(HarnessSkillMiddleware.class::isInstance)
                .map(HarnessSkillMiddleware.class::cast)
                .findFirst();
    }

    /** 反射读中间件私有 stager 字段（null＝未武装物化面——#363 抑制后的期望态）。 */
    private static Object stagerOf(HarnessSkillMiddleware middleware) throws Exception {
        Field field = middleware.getClass().getDeclaredField("stager");
        field.setAccessible(true);
        return field.get(middleware);
    }

    @Test
    void given_read_only_main_agent_when_built_then_host_side_stager_disarmed() throws Exception {
        // 装配面守护（全宿主确定性）：项目容器姿态构建后，技能中间件不得携带以
        // 容器根为落点的宿主物化器——修复前该字段非 null（框架 build() 只要
        // workspace＋技能仓库非空即武装），#363 的 WARN/宿主垃圾皆源于它
        HarnessAgent agent = buildMainReadOnlyAgent();
        try {
            HarnessSkillMiddleware skillMiddleware = skillMiddlewareOf(agent)
                    .orElseThrow(() -> new AssertionError("技能中间件不在链上（装配面变化？）"));
            assertThat(stagerOf(skillMiddleware)).isNull();
        }
        finally {
            agent.close();
        }
    }

    @Test
    void given_read_only_main_agent_when_system_prompt_built_then_no_host_workspace_write()
            throws Exception {
        // 症状面守护：onSystemPrompt 是框架每轮钩子（issue 的 WARN 即出自其
        // stager.stage 腿）。抑制后该钩子不得在宿主 /workspace/.skills-cache 下
        // 创建任何新路径。前置断言非存在（若历史残留已存在——未修复版本跑过本
        // 测试——按失败暴露，提示清理而非静默跳过）；宿主无权建 /workspace 的
        // 环境此前也从未成功创建，本断言恒可判。
        Path stagedPath = Path.of(AgentWorkspace.ProjectDev.CONTAINER_ROOT,
                ".skills-cache", PROBE_SOURCE, PROBE_SKILL);
        assertThat(Files.exists(stagedPath))
                .as("前置：宿主 %s 不应有历史残留（未修复版本物化所致，请清理）", stagedPath)
                .isFalse();

        HarnessAgent agent = buildMainReadOnlyAgent();
        try {
            HarnessSkillMiddleware skillMiddleware = skillMiddlewareOf(agent)
                    .orElseThrow(() -> new AssertionError("技能中间件不在链上（装配面变化？）"));
            skillMiddleware.onSystemPrompt(agent.getDelegate(), RuntimeContext.empty(), "sys")
                    .block();
            assertThat(Files.exists(stagedPath))
                    .as("宿主 %s 不应被技能物化面创建（#363）", stagedPath)
                    .isFalse();
        }
        finally {
            agent.close();
        }
    }
}
