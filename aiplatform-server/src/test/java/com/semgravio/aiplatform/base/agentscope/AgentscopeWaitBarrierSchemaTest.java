package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tool.WaitAsyncResultsTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * 内置等待屏障件 schema 形态锁定（#482）：agentscope 2.0.1 的
 * {@code wait_async_results} 把 {@code timeout_seconds} 声明为必填
 * （{@code @ToolParam} 缺省 required=true，对比 task_ids/wait_all 显式
 * required=false）——而内核描述文本自称 "Default 60, max 120"、实现
 * {@code normalizeTimeout(null)→60} 兜底完备，schema 声明与描述/实现自相
 * 矛盾：模型按描述省略参数即 {@code ToolValidator} 参数校验失败
 * （{@code Parameter validation failed for tool ...}，#480 同族形态）。
 *
 * <p>缝（公开面，非内核内部）：</p>
 * <ul>
 * <li>真内核件裸注册（{@code Toolkit.registerTool}）→ {@code getTool(..)
 * .getParameters()}——锁上游 schema 原始形态（required 含 timeout_seconds；
 * 上游修版此断言红＝提醒撤本覆写）；</li>
 * <li>{@link ToolValidator#validateInput}（内核公开校验器）——坑行为实证
 * （缺参必报）＋覆写后放行；</li>
 * <li>{@link AgentscopeHarnessAgentFactory#relaxWaitBarrierTimeoutSchema}
 * （平台缝）——required 摘除语义（保留其余必填项／仅剩即移除 key）、幂等
 * （不涉即原实例不动）、执行面全委托；</li>
 * <li>subagentFactory 真构建（mock 模型，无需通道 key）＋工厂 obtain 真构建
 * （需 key，同既有口径 assumption）——构建收口后内核件已被覆写（接线守护）。</li>
 * </ul>
 */
class AgentscopeWaitBarrierSchemaTest {

    private static final String WAIT_TOOL = "wait_async_results";
    private static final String TIMEOUT_PARAM = "timeout_seconds";

    /** 真内核件裸注册（独立 Toolkit，不经工厂——锁的是上游形态非平台装配）。 */
    private static Toolkit bareKernelToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WaitAsyncResultsTool(mock(MessageBus.class)));
        return toolkit;
    }

    /** required 列表形态视图（非列表/缺省→空表——元素级断言统一 Object 面）。 */
    @SuppressWarnings("unchecked")
    private static List<Object> requiredOf(AgentTool tool) {
        Object required = tool.getParameters().get("required");
        return required instanceof List<?> list ? (List<Object>) list : List.of();
    }

    // ---------- 缝 A：真内核件 schema 原始形态（上游坑实证，防上游静默变脸） ----------

    @Test
    void given_kernel_2_0_1_when_bare_registered_then_timeout_seconds_required() {
        // 取证依据固化：agentscope-harness 2.0.1 WaitAsyncResultsTool 的
        // timeout_seconds @ToolParam 无 required=false（ToolParam.required 缺省
        // true，字节码 AnnotationDefault 实证）——required 列表含之；task_ids/
        // wait_all 显式 required=false 不在。上游修版此断言红＝撤平台覆写的信号。
        AgentTool kernel = bareKernelToolkit().getTool(WAIT_TOOL);

        assertThat(kernel).isNotNull();
        assertThat(requiredOf(kernel)).contains(TIMEOUT_PARAM);
        assertThat(requiredOf(kernel)).doesNotContain("task_ids", "wait_all");
        // 属性面保留定义（模型可显式传，>120 由内核 clamp）——只在 required 半边动刀
        assertThat(kernel.getParameters().get("properties"))
                .as("properties 面保有 timeout_seconds 定义")
                .isInstanceOfSatisfying(Map.class, properties ->
                        assertThat(((Map<?, ?>) properties).containsKey(TIMEOUT_PARAM)).isTrue());
    }

    // ---------- 缝 B：校验器行为（坑实证＋覆写后放行） ----------

    @Test
    void given_kernel_schema_when_timeout_seconds_omitted_then_validation_fails() {
        // 坑行为实证：#480 同族——模型不传 timeout_seconds 即参数校验失败
        // （错误以 ToolResultBlock.error 回模型，日志仅 debug 级——日志取证
        // 零样例≠零发生，本断言为读码实证的行为级固化）
        Map<String, Object> kernelSchema = bareKernelToolkit().getTool(WAIT_TOOL).getParameters();

        String error = ToolValidator.validateInput("{\"task_ids\":\"t1\"}", kernelSchema);

        assertThat(error).as("内核原始 schema 下省略 timeout_seconds 必报校验失败").isNotNull();
        assertThat(error).contains(TIMEOUT_PARAM);
        // 对照：传了即过（校验只针对缺失，不在数值面上另设卡）
        assertThat(ToolValidator.validateInput("{\"timeout_seconds\":30}", kernelSchema)).isNull();
    }

    @Test
    void given_relaxed_schema_when_timeout_seconds_omitted_then_validation_passes() {
        // 修复后半边：覆写后 schema 对省略形态放行（内核实现 normalizeTimeout(null)
        // →60 兜底，行为零损失）；显式传值照常过
        Toolkit toolkit = bareKernelToolkit();
        AgentscopeHarnessAgentFactory.relaxWaitBarrierTimeoutSchema(toolkit);
        Map<String, Object> relaxed = toolkit.getTool(WAIT_TOOL).getParameters();

        assertThat(ToolValidator.validateInput("{\"task_ids\":\"t1\"}", relaxed)).isNull();
        assertThat(ToolValidator.validateInput("{\"timeout_seconds\":30}", relaxed)).isNull();
        assertThat(ToolValidator.validateInput("{}", relaxed)).isNull();
    }

    // ---------- 缝 C：覆写方法语义（required 摘除／幂等／执行面委托） ----------

    /** 带 schema 的桩件（required 可指定；callAsync 记录调用并回固定结果实例）。 */
    private static final class SchemaToolStub implements AgentTool {

        private final String name;
        private final Map<String, Object> parameters;
        private final ToolResultBlock result = ToolResultBlock.text("stub-result");
        private final List<ToolCallParam> calls = new ArrayList<>();

        SchemaToolStub(String name, Map<String, Object> parameters) {
            this.name = name;
            this.parameters = parameters;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "桩：" + name;
        }

        @Override
        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.add(param);
            return Mono.just(result);
        }
    }

    /** 内核同形 schema（required 列表形态照上游 JSON Schema 面）。 */
    private static Map<String, Object> schemaWithRequired(List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                TIMEOUT_PARAM, Map.of("type", "integer"),
                "task_ids", Map.of("type", "string")));
        if (!required.isEmpty()) {
            schema.put("required", new ArrayList<>(required));
        }
        return schema;
    }

    @Test
    void given_other_required_members_when_relaxed_then_only_timeout_removed() {
        // 摘除语义：只摘 timeout_seconds，其余必填项保留（不因平台覆写放宽别的约束）
        Toolkit toolkit = new Toolkit();
        SchemaToolStub stub = new SchemaToolStub(WAIT_TOOL,
                schemaWithRequired(List.of(TIMEOUT_PARAM, "task_ids")));
        toolkit.registerAgentTool(stub);

        AgentscopeHarnessAgentFactory.relaxWaitBarrierTimeoutSchema(toolkit);

        AgentTool relaxed = toolkit.getTool(WAIT_TOOL);
        assertThat(relaxed).isNotSameAs(stub); // 后写胜：覆写件顶替原注册
        assertThat(relaxed.getParameters().get("required")).isEqualTo(List.of("task_ids"));
        // schema 其余面原样透传（type/properties 不动）
        assertThat(relaxed.getParameters().get("type")).isEqualTo("object");
        Map<?, ?> properties = (Map<?, ?>) relaxed.getParameters().get("properties");
        assertThat(properties.containsKey(TIMEOUT_PARAM)).isTrue();
        assertThat(properties.containsKey("task_ids")).isTrue();
    }

    @Test
    void given_timeout_sole_required_member_when_relaxed_then_required_key_removed() {
        // 仅剩 timeout_seconds 一项：摘除后 required key 整体移除（不留空数组
        // ——最少惊异；JSON Schema 无 required 即全可选，语义等价且干净）
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new SchemaToolStub(WAIT_TOOL,
                schemaWithRequired(List.of(TIMEOUT_PARAM))));

        AgentscopeHarnessAgentFactory.relaxWaitBarrierTimeoutSchema(toolkit);

        assertThat(toolkit.getTool(WAIT_TOOL).getParameters()).doesNotContainKey("required");
    }

    @Test
    void given_schema_without_timeout_required_when_relaxed_then_noop() {
        // 幂等：required 不含 timeout_seconds（上游修版后形态／重复调用）——
        // 不包覆写件、原实例原样在册（isSameAs 钉死不叠 wrapper）
        Toolkit toolkit = new Toolkit();
        SchemaToolStub stub = new SchemaToolStub(WAIT_TOOL,
                schemaWithRequired(List.of("task_ids")));
        toolkit.registerAgentTool(stub);

        AgentscopeHarnessAgentFactory.relaxWaitBarrierTimeoutSchema(toolkit);

        assertThat(toolkit.getTool(WAIT_TOOL)).isSameAs(stub);
    }

    @Test
    void given_tool_absent_when_relaxed_then_noop() {
        // 工具不在面（声明 allowlist 摘名后的子级面／只读面装配序）——静默 no-op
        Toolkit toolkit = new Toolkit();

        AgentscopeHarnessAgentFactory.relaxWaitBarrierTimeoutSchema(toolkit);

        assertThat(toolkit.getToolNames()).isEmpty();
    }

    @Test
    void given_wrapped_tool_when_called_then_execution_delegates() {
        // 执行面全委托：覆写只动 schema 声明面，callAsync 直通内核件（行为不变）
        Toolkit toolkit = new Toolkit();
        SchemaToolStub stub = new SchemaToolStub(WAIT_TOOL,
                schemaWithRequired(List.of(TIMEOUT_PARAM, "task_ids")));
        toolkit.registerAgentTool(stub);

        AgentscopeHarnessAgentFactory.relaxWaitBarrierTimeoutSchema(toolkit);

        ToolResultBlock result = toolkit.getTool(WAIT_TOOL).callAsync(null).block();
        assertThat(result).isSameAs(stub.result);
        assertThat(stub.calls).hasSize(1);
    }

    // ---------- 缝 D：子智能体真构建接线（mock 模型，无需通道 key） ----------

    @Test
    void given_child_built_when_final_toolkit_then_wait_schema_relaxed(
            @TempDir Path tempDir) throws Exception {
        // 接线守护：框架 build() 在子级工具面合并后整体注册内置件（含
        // wait_async_results）——构建收口后平台覆写必须已生效（校验面与模型面
        // 同源 AgentTool.getParameters()，一覆两得）
        SubagentDeclaration noAllowlist = SubagentDeclaration.builder()
                .name("verify")
                .description("probe")
                .inlineAgentsBody("probe body")
                .build();
        HarnessAgent child = (HarnessAgent) AgentscopeHarnessAgentFactory.subagentFactory(
                noAllowlist, new AgentWorkspace.Local(tempDir), null, mock(Model.class), mock(Model.class),
                new Toolkit(), (agentKey, workspace, toolSpec) -> new Toolkit(),
                new InMemoryAgentStateStore(), (agentKey, workspace) -> List.of(), List.of())
                .apply("verify");

        try {
            AgentTool waitTool = child.getToolkit().getTool(WAIT_TOOL);
            assertThat(waitTool).as("空 allowlist 声明＝全放行，内置件应在面").isNotNull();
            assertThat(requiredOf(waitTool)).doesNotContain(TIMEOUT_PARAM);
        } finally {
            child.close();
        }
    }

    // ---------- 缝 E：主装配真构建接线（需通道 key，同既有口径 assumption） ----------

    /** 主通道真构建语境（同 AgentscopeHarnessAgentFactoryTest 口径）。 */
    private static void requireVolcengineKey() {
        assumeTrue(System.getenv("VOLCENGINE_API_KEY") != null
                        && !System.getenv("VOLCENGINE_API_KEY").isBlank(),
                "无 VOLCENGINE_API_KEY，跳过真构建断言（主通道 volcengine，#369）");
    }

    @Test
    void given_real_build_when_built_then_wait_schema_relaxed() {
        // 主装配（obtain → buildAgent）接线守护：全部形态构建后内核件 schema 已覆写
        requireVolcengineKey();
        VolcengineModelRegistrar.register();
        EdgeModelSupplier.EdgeModel flash = new EdgeModelSupplier.EdgeModel(
                "volcengine:deepseek-v4-flash-ga-260731",
                new ModelChannel("https://ark.cn-beijing.volces.com/api/v3",
                        System.getenv("VOLCENGINE_API_KEY"), false));
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), (agentKey, workspace, toolSpec) -> new Toolkit(),
                (agentKey, workspace) -> List.of(), (agentKey, workspace) -> List.of(),
                (agentKey, workspace) -> List.of(),
                new EdgeModelSupplier() {
                    @Override
                    public EdgeModel compactionModel() {
                        return flash;
                    }

                    @Override
                    public EdgeModel precheckModel() {
                        return flash;
                    }

                    @Override
                    public EdgeModel subagentModel() {
                        return null;
                    }
                }, new AgentscopeProperties());

        HarnessAgent agent = factory.obtain("platform-agent-wait", "sys",
                "volcengine:deepseek-v4-flash-ga-260731",
                new ModelChannel("https://ark.cn-beijing.volces.com/api/v3",
                        System.getenv("VOLCENGINE_API_KEY"), false),
                new AgentWorkspace.Local(null), null, null);

        try {
            AgentTool waitTool = agent.getToolkit().getTool(WAIT_TOOL);
            assertThat(waitTool).isNotNull();
            assertThat(requiredOf(waitTool)).doesNotContain(TIMEOUT_PARAM);
        } finally {
            agent.close();
        }
    }
}
