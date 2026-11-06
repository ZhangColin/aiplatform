package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.business.project.domain.model.ProjectMaterials;

/**
 * 基座 lint 两档容器活体（#425；docker daemon 不在整类跳过，对偶设计规范活体
 * 口径）：真 dev 容器（基座 0.15 四件套随镜像就位）里 grep 判据真跑——判据面
 * 单测（mock grep 输出）不证 ERE 本身，本类是 ERE 行为的唯一实证面：
 * <ul>
 * <li>基座骨架开箱零违规（件目录排除生效——健壮件自身实现天然含 new Pool/
 * fetch/cookies/scrypt 原语，被误报即首个 run 空耗修轮）；</li>
 * <li>裸 pg/redis 客户端与裸 fetch 命中（file/line/rule 定位）＋手写 cookie 会话
 * 与裸密码原语命中预置档；</li>
 * <li>经基座件的合法用法不误报（httpFetch/httpJson/createSession 直用零命中）
 * ——修掉裸写后 passed（修轮回执环路的容器事实）。</li>
 * </ul>
 */
@IntegrationTest
@Tag("live")
class RobustKitLintAppServiceLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 600;

    @Autowired
    private RobustKitLintAppService appService;

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String workspaceId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        if (workspaceId != null) {
            try {
                workspaceLifecycleAppService.destroy(workspaceId);
            }
            catch (RuntimeException e) {
                // 物理清理尽力而为（记录已随 destroy 删；Docker 残留可重试销毁）
            }
            jdbcTemplate.update("DELETE FROM prj_projects WHERE workspace_id = ?",
                    Long.parseLong(workspaceId));
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_workspace_in_container_when_closing_scan_then_receipt_roundtrip() {
        // 灵魂用例：基座开箱合规（件目录排除活证）→ 裸写命中两档（定位与规则）→
        // 件合法用法零命中 → 修掉裸写后 passed
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        Long workspaceKey = Long.parseLong(workspaceId);

        // 基座骨架开箱零违规：健壮件自身（new Pool/fetch/cookies/scrypt 实现地）被
        // --exclude-dir=robust 排除——误报即首个 run 空耗修轮
        Map<String, Object> baselineScan = appService.lintClosingPayload(workspaceKey);
        assertThat(baselineScan).as("基座骨架开箱合规（不空耗修轮）")
                .containsEntry("status", "passed");

        // 执行体写了裸客户端与手写会话 → 两档回执带定位与规则（含 new pg.Pool
        // 命名空间形态——判据收紧面的活证）
        writeFile("src/app/orders/actions.ts", """
                import { Pool } from 'pg';
                const pool = new Pool({ connectionString: process.env.DATABASE_URL });
                const pool2 = new pg.Pool({ connectionString: process.env.DATABASE_URL });
                export async function listOrders() {
                  const r = await fetch('/api/orders');
                  return r.json();
                }
                """);
        writeFile("src/app/login/actions.ts", """
                import { cookies } from 'next/headers';
                import { scryptSync } from 'node:crypto';
                export async function login() {
                  const jar = await cookies();
                  const hash = scryptSync('pw', 'salt', 64);
                  return { jar, hash };
                }
                """);
        Map<String, Object> payload = appService.lintClosingPayload(workspaceKey);
        assertThat(payload.get("status")).isEqualTo("violations");
        List<Map<String, Object>> mandatory = tierOf(payload, "mandatory");
        assertThat(mandatory).extracting(violation -> violation.get("rule"))
                .containsExactlyInAnyOrder(
                        RobustKitLintAppService.RULE_BARE_DB_CLIENT,
                        RobustKitLintAppService.RULE_BARE_DB_CLIENT,
                        RobustKitLintAppService.RULE_BARE_FETCH);
        assertThat(mandatory).allSatisfy(violation -> {
            assertThat((String) violation.get("file")).isEqualTo("src/app/orders/actions.ts");
            assertThat((Integer) violation.get("line")).isPositive();
        });
        List<Map<String, Object>> preset = tierOf(payload, "preset");
        assertThat(preset).extracting(violation -> violation.get("rule"))
                .containsOnly(RobustKitLintAppService.RULE_HANDWRITTEN_SESSION);
        assertThat(preset).allSatisfy(violation ->
                assertThat((String) violation.get("file")).isEqualTo("src/app/login/actions.ts"));

        // 经基座件的合法用法不误报：预置件直用（httpFetch/httpJson/createSession/
        // query/cacheGet）零命中——词边界与件目录排除的活证
        writeFile("src/app/orders/actions.ts", """
                import { httpFetch } from '@/lib/robust/http';
                import { query, cacheGet } from '@/lib/robust/data-access';
                export async function listOrders() {
                  const r = await httpFetch('/api/orders');
                  const rows = await query('SELECT * FROM orders', []);
                  const cached = await cacheGet('orders');
                  return { r, rows, cached };
                }
                """);
        writeFile("src/app/login/actions.ts", """
                import { createSession, hashPassword } from '@/lib/robust/session';
                export async function login() {
                  await createSession({ id: 'u1' });
                  const hash = hashPassword('pw');
                  return { hash };
                }
                """);
        assertThat(appService.lintClosingPayload(workspaceKey))
                .as("改走预置件（件合法用法）后 passed")
                .containsEntry("status", "passed");
    }

    /** 载荷分档清单读口（档缺席＝失败——本用例两档都应在场或由断言面定位）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tierOf(Map<String, Object> payload, String tier) {
        assertThat(payload.get(tier)).as("档 %s 应在场", tier).isNotNull();
        return (List<Map<String, Object>>) ((Map<?, ?>) payload.get(tier)).get("violations");
    }

    /** 经 stdin 写工作区文件（上传通道同款命令——mkdir 兜底＋字节回执）。 */
    private void writeFile(String relativePath, String content) {
        ExecResultResponse result = workspaceLifecycleAppService.execWithStdin(workspaceId,
                new WorkspaceExecCommand(ProjectMaterials.uploadCommand(relativePath)),
                content.getBytes(StandardCharsets.UTF_8));
        assertThat(result.exitCode()).as("fixture 写入应成功：%s", result.stderr()).isZero();
    }

    private static boolean dockerAvailable() {
        try {
            // ②族纪律：不要输出就 DISCARD 直落 NUL——未读管道＋waitFor 在 Windows
            // 必死锁（docker info 输出可超匿名管道容量）
            return new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        }
        catch (Exception e) {
            return false;
        }
    }
}
