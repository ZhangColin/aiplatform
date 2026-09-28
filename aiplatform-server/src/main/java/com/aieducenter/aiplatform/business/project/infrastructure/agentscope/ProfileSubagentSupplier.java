package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentSubagentSupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

/**
 * 按智能体配置的子智能体装配（委派位接线，#95）：{@link AgentProfile#EXECUTOR run
 * 执行体} = 自测子智能体（{@code workspaceMode(ISOLATED)}——框架自带 per-agent 工作
 * 区布局：自动创建 {@code agents/<name>/workspace/}、namespace 隔离，隔离根已进非
 * 交付目录集 {@code WorkspaceLayout#AGENTS_DIR}，零新机制）；{@link AgentProfile#MAIN
 * 主智能体} 与无配置语境 = 空集（主智能体永不委派——委派是 run 内机制，不与主智能
 * 体并列，ADR 0006）。子智能体任务自包含、结果回交执行体，不直接面对用户；事件带
 * source 归属（过程呈现分角色播，用户面无角色标签）。
 *
 * <p>自测子智能体为 run 自检段的执行侧升级（#96：自检现为最简探活收口 → 自测子
 * 智能体跑交付代码测试）：正文指令逐项 ✅/❌ 清单式播报（解说经 source=self-test
 * 归属进工作消息）+ 报告写隔离根 + 结果回交执行体。平台侧收尾统计（closing.selfTest）
 * 由 run 尝试环从自测 command 动作终态观测，不解析子智能体自由文本。</p>
 *
 * <p><b>技能面（#260 subagent 槽装配一等化——撤哨兵接真视图）</b>：声明不再挂
 * 技能过滤（#249 哨兵 allowlist 已撤），子智能体技能面＝subagent 槽位装配视图
 * （{@code ProfileSkillRepositorySupplier} 按本声明名发键——已指派且启用的库技能，
 * 动态查库）。构建接线在平台工厂（{@code AgentscopeHarnessAgentFactory} 把声明转
 * 自定义 subagentFactory——框架 2.0.1 declared 工厂只继承父级技能仓库，子智能体
 * 槽位视图接不进去，详见工厂方法 javadoc）；条目描述在 2.0.1 退化为名，路由由
 * 执行体工作协议点名 {@code agent_id=self-test} 承担。技能脚本资源按槽位
 * {@code hasShell} 口径发放（子智能体带 shell——同执行体开放面）。</p>
 *
 * <p><b>自荐工具（#263 三槽位齐开）</b>：propose_skill 经声明 allowlist 接入——
 * 平台工厂按子智能体键取工具视图（{@code ProfileToolkitSupplier} 的 self-test 键
 * ＝subagent 槽血统实例）合并进子级工具面，同名后写胜出覆盖父级继承的执行体槽
 * 实例（血统不串槽）；软指引绑任务回交前（正文第 5 条），无经验不调用。</p>
 */
@Component
public class ProfileSubagentSupplier implements AgentSubagentSupplier {

    /** 自测子智能体声明名（事件 source 归属值 + 引擎委派寻址键 + 技能装配视图键）。 */
    public static final String SELF_TEST_NAME = "self-test";

    /** 自测子智能体工具白名单：只读交付代码 + 跑测试命令（内核 shell 注册名
     *  execute）+ 报告写隔离根 + 技能自荐（#263 声明白名单接入——平台工厂按
     *  子键视图发 subagent 槽血统实例，挂载治理归本清单）——排除 edit_file
     *  （不改交付代码）与 finish_edit（执行体收口工具）。 */
    /** public＝工具面清单读面（#252 subagent 槽位呈现口径）与本声明同源引用。 */
    public static final List<String> SELF_TEST_TOOLS = List.of(
            "read_file", "grep_files", "glob_files", "list_files", "write_file", "execute",
            "propose_skill");

    /** 自测角色正文（任务自包含、结果回交执行体；读交付代码只读、报告写隔离根）。 */
    private static final String SELF_TEST_BODY = "你是 run 执行体委派的自测子智能体，专项负责运行自测。"
            + "工作协议：\n"
            + "1. 只读工作区内的系统代码（应用代码与 docs/），不修改任何交付文件。\n"
            + "2. 用 execute 命令工具逐项运行测试/探活命令，验证系统可用；每跑一项就播报一句"
            + "清单式结果（如「首页可访问 ✅」「留言板数据落库 ✅」「8081 服务常驻 ❌」）"
            + "——逐项 ✅/❌ 播报，失败项如实 ❌，不粉饰、不省略。\n"
            + "3. 把自测结果（逐项 ✅/❌）写成报告写入你的隔离工作区（write_file）。\n"
            + "4. 结果以简短文本回交 run 执行体，不直接面对用户。\n"
            + "5. 经验沉淀（软指引非必做）：回交结果前，若本轮测试清单有可复用经验（某类"
            + "系统值得逐项验证的要点、探活命令的稳妥写法等），用 propose_skill 工具把它"
            + "写成技能草稿（name/description/body 三参数）供后续复用——后台审核采纳后"
            + "才生效；没有可沉淀的就不调用，不为凑数硬写。\n"
            + "全程使用中文。";

    private final SubagentDeclaration selfTest;

    public ProfileSubagentSupplier() {
        this.selfTest = SubagentDeclaration.builder()
                .name(SELF_TEST_NAME)
                .description("运行自测：读取系统代码（只读），运行测试验证系统可用，报告回交执行体。")
                .workspaceMode(WorkspaceMode.ISOLATED)
                .inlineAgentsBody(SELF_TEST_BODY)
                .tools(SELF_TEST_TOOLS)
                .build();
    }

    /** 测试缝注入构造（单测替身声明）。 */
    ProfileSubagentSupplier(SubagentDeclaration selfTest) {
        this.selfTest = selfTest;
    }

    @Override
    public List<SubagentDeclaration> subagentsFor(String agentKey, AgentWorkspace workspace) {
        if (AgentProfile.EXECUTOR.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectDev) {
            return List.of(selfTest);
        }
        return List.of();
    }
}
