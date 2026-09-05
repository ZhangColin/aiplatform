package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

/**
 * 按配置的子智能体装配（#95 委派位接线）：run 执行体 = 自测子智能体（ISOLATED 隔离
 * 根落位平台目录下——进非交付目录集，框架自带 per-agent 工作区布局、零新机制）；
 * 主智能体与无配置语境 = 空集（委派是 run 内机制，不与主智能体并列，ADR 0006）。
 * 本类是委派位挂载点的结构守护——执行体拿得到声明、主智能体拿不到。
 */
class ProfileSubagentSupplierTest {

    private final ProfileSubagentSupplier supplier = new ProfileSubagentSupplier();

    @Test
    void given_executor_on_project_dev_when_subagents_then_self_test_declaration() {
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        assertThat(declaration.getName()).isEqualTo(ProfileSubagentSupplier.SELF_TEST_NAME);
        assertThat(declaration.getDescription()).isNotBlank();
        assertThat(declaration.getInlineAgentsBody()).contains("自测");
    }

    @Test
    void given_self_test_when_built_then_isolated_workspace_under_platform_dir() {
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        // 隔离 = 框架隔离根：ISOLATED 工作区布局（inline 正文、框架自动创建
        // agents/<name>/workspace/、namespace 隔离）；agents/ 已在非交付目录集
        // （源码包/文件树/版本跟踪三口径同源排除）——交付目录无子智能体脏写（隔离
        // 根外只读）
        assertThat(declaration.getWorkspaceMode()).isEqualTo(WorkspaceMode.ISOLATED);
        assertThat(declaration.getWorkspacePath()).isNull();
        assertThat(declaration.getInlineAgentsBody()).isNotBlank();
        assertThat(WorkspaceLayout.NON_DELIVERABLE_DIRS)
                .contains(WorkspaceLayout.AGENTS_DIR);
    }

    @Test
    void given_self_test_tools_when_built_then_read_write_command_no_executor_closure() {
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        // 只读交付代码 + 跑测试命令 + 报告写隔离根；排除改交付代码与执行体收口工具
        assertThat(declaration.getTools())
                .contains("read_file", "write_file", "command")
                .doesNotContain("edit_file", "finish_edit");
    }

    @Test
    void given_main_on_any_workspace_when_subagents_then_empty() {
        // 主智能体永不委派（对话姿态——委派是 run 内机制），任何工作区形态都拿不到声明
        assertThat(supplier.subagentsFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"))).isEmpty();
        assertThat(supplier.subagentsFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"))).isEmpty();
        assertThat(supplier.subagentsFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.Local(null))).isEmpty();
    }

    @Test
    void given_executor_on_non_dev_workspace_when_subagents_then_empty() {
        // 委派依赖项目 dev 工作区（子智能体读写交付代码），只读面/本地兜底不委派
        assertThat(supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"))).isEmpty();
        assertThat(supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.Local(null))).isEmpty();
    }

    @Test
    void given_unknown_or_absent_key_when_subagents_then_empty() {
        assertThat(supplier.subagentsFor(null,
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"))).isEmpty();
        assertThat(supplier.subagentsFor("naming",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"))).isEmpty();
    }
}
