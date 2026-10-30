package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

/**
 * 按配置的子智能体装配（#95 委派位接线）：run 执行体 = verify 验证子智能体
 * （#352 独立验证——自测位的升级：区别于旧 self-test 只读代码跑命令，verify
 * 真浏览器点流程＋查库＋看日志三面对质；ISOLATED 隔离根落位平台目录下——进
 * 非交付目录集，框架自带 per-agent 工作区布局、零新机制）；主智能体与无配置
 * 语境 = 空集（委派是 run 内机制，不与主智能体并列，ADR 0006）。本类是委派位
 * 挂载点的结构守护——执行体拿得到声明、主智能体拿不到。
 */
class ProfileSubagentSupplierTest {

    private final ProfileSubagentSupplier supplier = new ProfileSubagentSupplier();

    @Test
    void given_executor_on_project_dev_when_subagents_then_verify_declaration() {
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        assertThat(declaration.getName()).isEqualTo(ProfileSubagentSupplier.VERIFY_NAME);
        assertThat(declaration.getDescription()).isNotBlank();
        assertThat(declaration.getInlineAgentsBody()).contains("验证");
    }

    @Test
    void given_verify_body_when_built_then_three_faces_protocol() {
        // #352 三面在位钉死：浏览器真实点流程（playwright 配方）＋查库（psql）＋
        // 看日志——正文是验证子智能体的操作正本，三面缺一即红
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        assertThat(declaration.getInlineAgentsBody())
                .contains("浏览器")
                .contains("playwright")
                .contains("chromium")
                .contains("localhost:8081")
                .contains("psql")
                .contains("DATABASE_URL")
                .contains("日志");
    }

    @Test
    void given_verify_body_when_built_then_instructs_checklist_broadcast_and_report_back() {
        // 清单式播报（逐项 ✅/❌ 经 source=verify 解说进工作消息，失败项如实 ❌
        // 不粉饰）+ 报告写隔离根 + 结果回交执行体（失败项带证据）
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        assertThat(declaration.getInlineAgentsBody())
                .contains("逐项")
                .contains("✅")
                .contains("❌")
                .contains("播报")
                .contains("write_file")
                .contains("回交");
    }

    @Test
    void given_verify_body_when_built_then_instructs_report_verification_registration() {
        // #352 结果进收尾卡的事实源：一轮验证结束经 report_verification 一次性登记
        // 本轮全量（平台从工具调用事实观测，不解析自由文本）
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        assertThat(declaration.getInlineAgentsBody())
                .contains("report_verification")
                .contains("name")
                .contains("face")
                .contains("ok");
    }

    @Test
    void given_verify_when_built_then_isolated_workspace_under_platform_dir() {
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
    void given_verify_tools_when_built_then_three_faces_plus_registration_no_executor_closure() {
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        // 只读交付代码 + 跑命令（浏览器脚本/psql/读日志都经 execute）+ 脚本与报告
        // 写隔离根 + 验证结果登记（#352 report_verification 平台件经声明白名单接入）
        // + 技能自荐（#263）；排除改交付代码与执行体收口工具
        assertThat(declaration.getTools())
                .contains("read_file", "grep_files", "glob_files", "list_files", "write_file",
                        "execute", "report_verification", "propose_skill")
                .doesNotContain("edit_file", "finish_edit");
    }

    @Test
    void given_verify_body_when_built_then_instructs_propose_soft_guidance() {
        // #263 软指引落位：验证经验自荐绑任务回交前——非必做，无经验不调用
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        assertThat(declaration.getInlineAgentsBody())
                .contains("propose_skill")
                .contains("回交")
                .contains("软指引");
    }

    @Test
    void given_verify_when_built_then_no_skill_filtering() {
        // #260 装配一等化——哨兵已撤：声明不挂技能过滤（技能面由平台工厂按子智能体
        // 键接 subagent 槽位装配视图承担，不再经声明 allowlist 收窄）
        SubagentDeclaration declaration = supplier.subagentsFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev")).get(0);

        assertThat(declaration.getSkills()).isEmpty();
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
