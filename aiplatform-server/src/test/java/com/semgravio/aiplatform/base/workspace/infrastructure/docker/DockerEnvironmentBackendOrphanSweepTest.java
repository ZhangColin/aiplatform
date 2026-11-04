package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无主工作区容器清扫的 CLI 假面（#179 触发器）：「记录照删、物理尽力而为」的销毁
 * 撞 daemon 抖动窗留下的孤儿，休眠扫描只遍历注册表、永久不可见——清扫面按命名
 * 求差（在跑 {@code ws-<id>} 容器 ∩ 保留集外）、宽限线外才删（fork「先建容器、
 * 后落记录」窗口保护）。卷刻意不删（无主卷可能仍承载用户数据，人工/脚本处置）；
 * 快照容器（ws-*-snap-*）与命名形不符，天然不在本面。
 */
class DockerEnvironmentBackendOrphanSweepTest {

    /** 宽限线：本地时区墙上时刻（对照 inspect 回执的 UTC 时刻按系统时区换算）。 */
    private static final LocalDateTime STARTED_BEFORE = LocalDateTime.of(2026, 11, 3, 12, 0);

    /** ws-1（判删）：宽限线前 4 小时；ws-2（判留）：线后 30 分钟。 */
    private static final String WS_1_STARTED = startedAt(STARTED_BEFORE.minusHours(4));
    private static final String WS_2_STARTED = startedAt(STARTED_BEFORE.plusMinutes(30));

    /** 夹具时刻按系统时区从宽限线相对推导成 UTC 回执串——本机（UTC+8）与 CI
     * runner（UTC）下「线前删/线后留」语义一致（硬编码 UTC 时刻只在 UTC+8 成立）。 */
    private static String startedAt(LocalDateTime localWallClock) {
        return DateTimeFormatter.ISO_INSTANT.format(
                localWallClock.atZone(ZoneId.systemDefault()).toInstant());
    }

    /** 假面：回执按命令全串可编（默认全成功），命令全记录。 */
    private static class ScriptedBackend extends DockerEnvironmentBackend {
        final List<String> commands = new ArrayList<>();

        @Override
        protected ExecResult runCapture(String... cmd) {
            commands.add(String.join(" ", cmd));
            return script(String.join(" ", cmd));
        }

        ExecResult script(String command) {
            if (command.equals("docker ps --format {{.Names}}")) {
                // 两个无主（ws-1 老、ws-2 新）＋保留集内 ws-42＋快照容器＋无关容器
                return new ExecResult(
                        "ws-1\nws-2\nws-42\nws-9-snap-1\nother-app\n", "", 0);
            }
            if (command.startsWith("docker inspect")) {
                return new ExecResult(
                        "/ws-1|" + WS_1_STARTED + "\n/ws-2|" + WS_2_STARTED + "\n", "", 0);
            }
            return new ExecResult("", "", 0);
        }
    }

    @Test
    void given_orphan_containers_when_sweep_then_only_grace_expired_unregistered_removed() {
        ScriptedBackend backend = new ScriptedBackend();

        int removed = backend.sweepOrphanWorkspaceContainers(
                List.of(42L), STARTED_BEFORE);

        assertThat(removed).isEqualTo(1);
        // 宽限线外的无主容器删；宽限线内的、保留集内的、快照/非 ws 命名的不动
        assertThat(backend.commands).contains("docker rm -f ws-1");
        assertThat(backend.commands).noneMatch(c -> c.equals("docker rm -f ws-2"));
        assertThat(backend.commands).noneMatch(c -> c.equals("docker rm -f ws-42"));
        assertThat(backend.commands).noneMatch(c -> c.equals("docker rm -f ws-9-snap-1"));
        assertThat(backend.commands).noneMatch(c -> c.equals("docker rm -f other-app"));
    }

    @Test
    void given_no_unregistered_containers_when_sweep_then_no_inspect_no_remove() {
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return command.equals("docker ps --format {{.Names}}")
                        ? new ExecResult("ws-42\nws-9-snap-1\n", "", 0)
                        : super.script(command);
            }
        };

        int removed = backend.sweepOrphanWorkspaceContainers(
                List.of(42L), STARTED_BEFORE);

        assertThat(removed).isZero();
        // 候选为空：连 inspect 都不发（静默轮询是常态，不给 daemon 加无谓负担）
        assertThat(backend.commands).noneMatch(c -> c.startsWith("docker inspect"));
    }

    @Test
    void given_unparseable_started_at_when_sweep_then_skipped_not_removed() {
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker inspect")) {
                    // 时刻解析不出：无从判宽限，宁可漏删不误杀窗口内新建资源
                    return new ExecResult("/ws-1|<invalid>\n", "", 0);
                }
                return super.script(command);
            }
        };

        int removed = backend.sweepOrphanWorkspaceContainers(
                List.of(42L), STARTED_BEFORE);

        assertThat(removed).isZero();
        assertThat(backend.commands).noneMatch(c -> c.equals("docker rm -f ws-1"));
    }

    @Test
    void given_candidate_vanished_between_ps_and_inspect_when_sweep_then_others_still_removed() {
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker inspect")) {
                    // ws-1 在 ps 与 inspect 之间消失：docker 把缺失点名列 stderr、
                    // stdout 照旧只含拿到的——幸存候选（ws-2，线前）照常收敛
                    return new ExecResult("/ws-2|" + WS_1_STARTED + "\n",
                            "Error: No such container: ws-1", 1);
                }
                return super.script(command);
            }
        };

        int removed = backend.sweepOrphanWorkspaceContainers(
                List.of(42L), STARTED_BEFORE);

        assertThat(removed).isEqualTo(1);
        assertThat(backend.commands).contains("docker rm -f ws-2");
        assertThat(backend.commands).noneMatch(c -> c.equals("docker rm -f ws-1"));
    }

    @Test
    void given_empty_registry_when_sweep_then_all_main_containers_are_orphans() {
        ScriptedBackend backend = new ScriptedBackend();

        int removed = backend.sweepOrphanWorkspaceContainers(
                List.of(), STARTED_BEFORE);

        assertThat(removed).isEqualTo(1);
        assertThat(backend.commands).contains("docker rm -f ws-1");
    }
}
