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
 * 无主工作区容器/卷收编的 CLI 假面（#179 触发器；#486 卷侧对称扩展，ADR-0045）：
 * 「记录照删、物理尽力而为」的销毁撞 daemon 抖动窗留下的无主资源，休眠扫描只
 * 遍历注册表、永久不可见——收编面按命名求差（{@code ws-<id>} 容器／{@code
 * vol-ws-<id>} 卷 ∩ 保留集外）、宽限线外才删（fork「先建资源、后落记录」窗口
 * 保护）。准绳＝注册表唯一正本：在册（含休眠保卷，ADR-0016）永不进收编面；
 * 只按注册表准绳点名删（{@code docker volume rm <名>}），无 prune 类通配
 * （红线）。快照容器（ws-*-snap-*）与主命名形不符，天然不在本面。
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

    // ---------- 无主卷收编（#486：#179 准绳对称扩卷侧，ADR-0045） ----------

    /** 卷清单命令（假面锚点）：与容器清单同理，只列名。卷侧夹具复用容器侧的
     * WS_1_STARTED/WS_2_STARTED 时刻串——同一 RFC3339 时刻形，卷回执同形可共用。 */
    private static final String VOLUME_LS = "docker volume ls --format {{.Name}}";

    /** 无主卷假面（两个无主＋保留集内＋无关卷）；inspect 回执＝卷名|创建时刻。 */
    private static class VolumeScriptedBackend extends ScriptedBackend {
        @Override
        ExecResult script(String command) {
            if (command.equals(VOLUME_LS)) {
                // vol-ws-1（判删：宽限线前）＋vol-ws-2（判留：线后）＋保留集内
                // vol-ws-42＋无关卷
                return new ExecResult("vol-ws-1\nvol-ws-2\nvol-ws-42\nother-vol\n", "", 0);
            }
            if (command.startsWith("docker volume inspect")) {
                return new ExecResult(
                        "vol-ws-1|" + WS_1_STARTED + "\nvol-ws-2|" + WS_2_STARTED + "\n", "", 0);
            }
            return super.script(command);
        }
    }

    @Test
    void given_orphan_volumes_when_sweep_then_only_grace_expired_unregistered_removed() {
        VolumeScriptedBackend backend = new VolumeScriptedBackend();

        int removed = backend.sweepOrphanWorkspaceVolumes(List.of(42L), STARTED_BEFORE);

        assertThat(removed).isEqualTo(1);
        // 宽限线外的无主卷点名删；宽限线内的、保留集内的、非 vol-ws 命名的不动；
        // 只按注册表准绳点名删——绝不 prune 类通配（AC 红线）
        assertThat(backend.commands).contains("docker volume rm vol-ws-1");
        assertThat(backend.commands).noneMatch(c -> c.equals("docker volume rm vol-ws-2"));
        assertThat(backend.commands).noneMatch(c -> c.equals("docker volume rm vol-ws-42"));
        assertThat(backend.commands).noneMatch(c -> c.contains("prune"));
        assertThat(backend.commands).noneMatch(c -> c.contains("system df"));
    }

    @Test
    void given_created_at_with_local_zone_offset_when_volume_sweep_then_still_judged() {
        // docker volume inspect 的 CreatedAt 在部分 daemon 上带本地时区偏移（Go
        // time.String 形如 2026-11-03T16:00:00.5+08:00）——按带偏移 ISO-8601 解析，
        // 不因「解析不出」误漏删（宽限线前 4 小时的真无主）
        String offsetForm = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                .withZone(ZoneId.systemDefault())
                .format(STARTED_BEFORE.minusHours(4).atZone(ZoneId.systemDefault()).toInstant());
        VolumeScriptedBackend backend = new VolumeScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker volume inspect")) {
                    return new ExecResult("vol-ws-1|" + offsetForm + "\n", "", 0);
                }
                return super.script(command);
            }
        };

        int removed = backend.sweepOrphanWorkspaceVolumes(List.of(42L), STARTED_BEFORE);

        assertThat(removed).isEqualTo(1);
        assertThat(backend.commands).contains("docker volume rm vol-ws-1");
    }

    @Test
    void given_no_unregistered_volumes_when_sweep_then_no_inspect_no_remove() {
        VolumeScriptedBackend backend = new VolumeScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return command.equals(VOLUME_LS)
                        ? new ExecResult("vol-ws-42\nother-vol\n", "", 0)
                        : super.script(command);
            }
        };

        int removed = backend.sweepOrphanWorkspaceVolumes(List.of(42L), STARTED_BEFORE);

        assertThat(removed).isZero();
        // 候选为空：连 inspect 都不发（常态静默轮询，不给 daemon 加无谓负担）
        assertThat(backend.commands).noneMatch(c -> c.startsWith("docker volume inspect"));
    }

    @Test
    void given_unparseable_created_at_when_volume_sweep_then_skipped_not_removed() {
        VolumeScriptedBackend backend = new VolumeScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker volume inspect")) {
                    // 创建时刻解析不出：无从判宽限，宁可漏删不误杀窗口内新建资源
                    return new ExecResult("vol-ws-1|<invalid>\n", "", 0);
                }
                return super.script(command);
            }
        };

        int removed = backend.sweepOrphanWorkspaceVolumes(List.of(42L), STARTED_BEFORE);

        assertThat(removed).isZero();
        assertThat(backend.commands).noneMatch(c -> c.equals("docker volume rm vol-ws-1"));
    }

    @Test
    void given_candidate_vanished_between_ls_and_inspect_when_volume_sweep_then_others_removed() {
        VolumeScriptedBackend backend = new VolumeScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker volume inspect")) {
                    // vol-ws-1 在 ls 与 inspect 之间消失：docker 点名 stderr、stdout
                    // 只含拿到的——幸存候选（vol-ws-2，线前）照常收编
                    return new ExecResult("vol-ws-2|" + WS_1_STARTED + "\n",
                            "Error: No such volume: vol-ws-1", 1);
                }
                return super.script(command);
            }
        };

        int removed = backend.sweepOrphanWorkspaceVolumes(List.of(42L), STARTED_BEFORE);

        assertThat(removed).isEqualTo(1);
        assertThat(backend.commands).contains("docker volume rm vol-ws-2");
        assertThat(backend.commands).noneMatch(c -> c.equals("docker volume rm vol-ws-1"));
    }
}
