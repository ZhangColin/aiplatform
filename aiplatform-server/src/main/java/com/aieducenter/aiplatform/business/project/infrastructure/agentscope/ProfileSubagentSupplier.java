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
 */
@Component
public class ProfileSubagentSupplier implements AgentSubagentSupplier {

    /** 自测子智能体声明名（事件 source 归属值 + 引擎委派寻址键）。 */
    public static final String SELF_TEST_NAME = "self-test";

    /** 自测子智能体工具白名单：只读交付代码 + 跑测试命令 + 报告写隔离根——排除
     *  edit_file（不改交付代码）与 finish_edit（执行体收口工具）。 */
    private static final List<String> SELF_TEST_TOOLS = List.of(
            "read_file", "grep_files", "glob_files", "list_files", "write_file", "command");

    /** 自测角色正文（任务自包含、结果回交执行体；读交付代码只读、报告写隔离根）。 */
    private static final String SELF_TEST_BODY = "你是 run 执行体委派的自测子智能体，专项负责运行自测。"
            + "工作协议：\n"
            + "1. 只读工作区内的系统代码（应用代码与 docs/），不修改任何交付文件。\n"
            + "2. 用 command 工具逐项运行测试/探活命令，验证系统可用；每跑一项就播报一句"
            + "清单式结果（如「首页可访问 ✅」「留言板数据落库 ✅」「8081 服务常驻 ❌」）"
            + "——逐项 ✅/❌ 播报，失败项如实 ❌，不粉饰、不省略。\n"
            + "3. 把自测结果（逐项 ✅/❌）写成报告写入你的隔离工作区（write_file）。\n"
            + "4. 结果以简短文本回交 run 执行体，不直接面对用户。\n"
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
