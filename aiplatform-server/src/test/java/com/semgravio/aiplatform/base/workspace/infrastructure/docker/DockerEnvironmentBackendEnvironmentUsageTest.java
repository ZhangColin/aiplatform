package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.ApplicationException;
import com.semgravio.aiplatform.base.workspace.domain.model.EnvironmentUsage;
import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 环境用量一瞥的 CLI 假面（#489 资源面大盘采样源）：docker system df（非
 * verbose）四行 JSON 解析——计数兼容数字/字符串回执形（daemon 版本差异）、体量
 * 人类可读串按<b>十进制单位</b>换算（go-units HumanSize：4442639553 → "4.44GB"，
 * 与 docker 自身显示同基）、「可回收」字段不取（判定红线，休眠卷被一并计入不
 * 作准绳——ADR-0045）；工作区命名清单编号集（无主计数＝调用方对注册表求差）。
 * daemon 不可达（CLI 非 0）即抛——采样容缺但不吞成「零资源」假象。
 */
class DockerEnvironmentBackendEnvironmentUsageTest {

    /** 真机回执形（docker 27 实测）：TotalCount/Size 为字符串形、逐行 JSON。 */
    private static final String DF_LINES = """
            {"Active":"4","Reclaimable":"4.664GB (60%)","Size":"7.75GB","TotalCount":"14","Type":"Images"}
            {"Active":"6","Reclaimable":"0B (0%)","Size":"151.6kB","TotalCount":"6","Type":"Containers"}
            {"Active":"5","Reclaimable":"685.2MB (80%)","Size":"845.9MB","TotalCount":"73","Type":"Local Volumes"}
            {"Active":"0","Reclaimable":"0B","Size":"0B","TotalCount":"0","Type":"Build Cache"}
            """;

    /** 假面：回执按命令全串可编（默认全成功），命令全记录。 */
    private static class ScriptedBackend extends DockerEnvironmentBackend {
        final List<String> commands = new ArrayList<>();

        @Override
        protected ExecResult runCapture(String... cmd) {
            commands.add(String.join(" ", cmd));
            return script(String.join(" ", cmd));
        }

        ExecResult script(String command) {
            if (command.equals("docker system df --format {{json .}}")) {
                return new ExecResult(DF_LINES, "", 0);
            }
            // 在跑主容器：ws-7、快照容器（不进清单集）、无关容器、保留集无关命名
            if (command.equals("docker ps --format {{.Names}}")) {
                return new ExecResult("ws-7\nws-9-snap-1\nother-app\n", "", 0);
            }
            if (command.equals("docker volume ls --format {{.Name}}")) {
                return new ExecResult("vol-ws-7\nvol-ws-8\nother-vol\n", "", 0);
            }
            return new ExecResult("", "", 0);
        }
    }

    @Test
    void given_system_df_and_listings_when_usage_then_four_usages_and_workspace_ids() {
        ScriptedBackend backend = new ScriptedBackend();

        EnvironmentUsage usage = backend.environmentUsage();

        // 四类用量：计数原数、体量十进制换算（"7.75GB"→7.75e9，与 docker 显示同基）
        assertThat(usage.images()).isEqualTo(new EnvironmentUsage.Usage(14, 7_750_000_000L));
        assertThat(usage.containers()).isEqualTo(new EnvironmentUsage.Usage(6, 151_600L));
        assertThat(usage.localVolumes()).isEqualTo(new EnvironmentUsage.Usage(73, 845_900_000L));
        assertThat(usage.buildCache()).isEqualTo(new EnvironmentUsage.Usage(0, 0));
        // 工作区命名清单编号集：快照容器/无关命名不进（无主面只及主命名资源）
        assertThat(usage.workspaceContainerIds()).containsExactly(7L);
        assertThat(usage.workspaceVolumeIds()).containsExactlyInAnyOrder(7L, 8L);
        // 红线：不取「可回收」口径（无该面字段即结构性钉死）、无 prune 类命令
        assertThat(backend.commands).noneMatch(c -> c.contains("prune"));
        assertThat(backend.commands).noneMatch(c -> c.contains("Reclaimable"));
    }

    @Test
    void given_numeric_df_receipts_when_usage_then_counts_accepted() {
        // 旧 daemon 的数字回执形（TotalCount 为数字）：asLong 双形兼容
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.equals("docker system df --format {{json .}}")) {
                    return new ExecResult(
                            "{\"Type\":\"Images\",\"TotalCount\":3,\"Size\":\"1.5GB\"}\n", "", 0);
                }
                return super.script(command);
            }
        };

        EnvironmentUsage usage = backend.environmentUsage();

        assertThat(usage.images()).isEqualTo(new EnvironmentUsage.Usage(3, 1_500_000_000L));
        // 缺行类型如实零（零用量），非缺测
        assertThat(usage.containers()).isEqualTo(EnvironmentUsage.Usage.zero());
    }

    @Test
    void given_docker_cli_failure_when_usage_then_throws_not_zero_facade() {
        // daemon 不可达（system df 非 0）：抛环境操作失败——采样容缺但不吞成
        // 「零资源」假象（零与不可达在观测面上必须可分）
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return new ExecResult("", "docker daemon not running", 1);
            }
        };

        assertThatThrownBy(backend::environmentUsage)
                .isInstanceOf(ApplicationException.class);
    }

    // ---------- 体量解析纯函数（十进制单位表） ----------

    @Test
    void given_human_size_strings_when_parse_then_decimal_base() {
        assertThat(DockerEnvironmentBackend.parseDockerHumanSize("0B")).isZero();
        assertThat(DockerEnvironmentBackend.parseDockerHumanSize("151.6kB")).isEqualTo(151_600L);
        assertThat(DockerEnvironmentBackend.parseDockerHumanSize("4.44GB")).isEqualTo(4_440_000_000L);
        assertThat(DockerEnvironmentBackend.parseDockerHumanSize("1.05TB"))
                .isEqualTo(1_050_000_000_000L);
        assertThat(DockerEnvironmentBackend.parseDockerHumanSize(null)).isZero();
        assertThat(DockerEnvironmentBackend.parseDockerHumanSize("4.664GB (60%)")).isZero();
    }
}
