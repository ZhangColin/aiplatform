package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;

/**
 * {@link ConfirmingShellTool}（#83 权限确认触发面）：破坏性命令自检 ASK（挂确认卡），
 * 其余放行（与内核 shell 无确认时行为同构）；注册名 = 播报封闭表的 command
 * （动作卡「运行命令」行）。清单是封闭小表（宁漏勿滥——沙箱可重建兜底）。
 */
class ConfirmingShellToolTest {

    private final ConfirmingShellTool tool = new ConfirmingShellTool("aiplatform-dev-test");

    @Test
    void given_destructive_commands_when_check_permissions_then_ask() {
        for (String command : new String[] {
                "rm -rf /workspace/data",
                "rm -fr ./build",
                "cd /workspace && rm -r -f node_modules",
                "sudo apt-get install curl",
                "mkfs.ext4 /dev/sda1",
                "dd if=/dev/zero of=/dev/sda bs=1M",
                "shutdown -h now",
                "reboot",
                ":(){ :|:& };:"}) {
            PermissionDecision decision = tool.checkPermissions(
                    Map.of("command", command), null).block();
            assertThat(decision.getBehavior()).as("应挂确认卡：%s", command)
                    .isEqualTo(PermissionBehavior.ASK);
        }
    }

    @Test
    void given_routine_commands_when_check_permissions_then_passthrough() {
        // 常规构建/安装/清理不拦（确认卡是过程透明面不是安全边界；非递删的 rm 同样放行）
        for (String command : new String[] {
                "ls -la",
                "npm install",
                "git status",
                "node server.js &",
                "curl -s -o /dev/null http://localhost:8081",
                "rm notes.txt",
                "mkdir -p data"}) {
            PermissionDecision decision = tool.checkPermissions(
                    Map.of("command", command), null).block();
            assertThat(decision.getBehavior()).as("不应拦：%s", command)
                    .isNotEqualTo(PermissionBehavior.ASK);
        }
    }

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        // 注册名对齐部件播报封闭表（command——内核名 execute 不在表内）；schema 契约同
        // 内核（command 必填，working_directory/timeout 可选）
        assertThat(tool.getName()).isEqualTo("command");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) tool.getParameters().get("properties");
        assertThat(properties).containsKeys("command", "working_directory", "timeout");
        assertThat(tool.isReadOnly()).isFalse();
    }
}
