package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;

/**
 * 基座 lint 两档判据面（#425，#407 裁决——同设计规范 lint 先例 #296）：强制档
 * （数据访问与外部调用裸客户端直用）与预置档（会话存储件自写）两口径的收口扫描
 * 载荷——命中清单（file/line/rule/message）、经基座件的合法用法不误报（件目录
 * 剔除）、清单截断与 total 如实、扫描不可执行＝unavailable 不假装达标、存量
 * 工作区（健壮件不在场）零行为改变。容器保真（grep 判据真跑、真命中/真不误报）
 * 归 {@code RobustKitLintAppServiceLiveTest}；收口回路（修轮喂回/强制档拒绝）
 * 归编排测试缝（GenerationAppServiceTest/IterationAppServiceTest）。
 */
@IntegrationTest
class RobustKitLintAppServiceTest {

    private static final Long WORKSPACE = 910421L;

    @Autowired
    private RobustKitLintAppService appService;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    /**
     * 存量工作区（健壮件探针不在场）零行为改变：不扫描（探针外零 exec）、
     * null 不携带——同「无规范项目不扫设计规范 lint」的一等断言先例。
     */
    @Test
    void given_legacy_workspace_when_closing_scan_then_null_without_scan() {
        givenProbe(false);
        assertThat(appService.lintClosingPayload(WORKSPACE)).isNull();
        verify(workspaceLifecycleAppService, times(1)).exec(anyString(), any());
    }

    /** 干净工作区（两档 grep 零命中）＝passed（强制/预置分节不携带）。 */
    @Test
    void given_clean_workspace_when_scan_then_passed() {
        givenProbe(true);
        givenGrep("createClient", "", 1);
        givenGrep("pbkdf2", "", 1);
        assertThat(appService.lintClosingPayload(WORKSPACE))
                .containsExactly(Map.entry("status", "passed"));
    }

    /** 强制档灵魂用例：裸 pg/redis 客户端（含 new pg.Pool 命名空间形态）与裸 fetch
     *  命中为违规清单（file/line/rule/message）。 */
    @Test
    void given_bare_clients_and_fetch_when_scan_then_mandatory_listed() {
        givenProbe(true);
        givenGrep("createClient", """
                ./src/lib/orders.ts:12:const pool = new Pool({ connectionString });
                ./src/app/orders/page.tsx:40:const r = await fetch('/api/orders');
                ./src/lib/cache.ts:7:const client = createClient({ url });
                ./src/lib/orders2.ts:3:const p2 = new pg.Pool({});
                """, 0);
        givenGrep("pbkdf2", "", 1);
        Map<String, Object> payload = appService.lintClosingPayload(WORKSPACE);
        assertThat(payload.get("status")).isEqualTo("violations");
        assertThat(payload).doesNotContainKey("preset");
        List<Map<String, Object>> violations = tierViolations(payload, "mandatory");
        assertThat(violations).extracting(violation -> violation.get("rule"))
                .containsExactly(RobustKitLintAppService.RULE_BARE_DB_CLIENT,
                        RobustKitLintAppService.RULE_BARE_FETCH,
                        RobustKitLintAppService.RULE_BARE_DB_CLIENT,
                        RobustKitLintAppService.RULE_BARE_DB_CLIENT);
        assertThat(violations.get(1)).containsOnly(
                Map.entry("file", "src/app/orders/page.tsx"),
                Map.entry("line", 40),
                Map.entry("rule", RobustKitLintAppService.RULE_BARE_FETCH),
                Map.entry("message", violations.get(1).get("message")));
        assertThat(violations.get(1).get("message")).asString().contains("httpFetch");
    }

    /** 预置档灵魂用例：手写 cookie 会话与裸密码哈希原语命中（会话件自写）；未用＝零命中不判违规。 */
    @Test
    void given_handwritten_session_when_scan_then_preset_listed_without_mandatory() {
        givenProbe(true);
        givenGrep("createClient", "", 1);
        givenGrep("pbkdf2", """
                ./src/app/login/actions.ts:8:const jar = await cookies();
                ./src/lib/auth.ts:3:import { scryptSync } from 'node:crypto';
                """, 0);
        Map<String, Object> payload = appService.lintClosingPayload(WORKSPACE);
        assertThat(payload.get("status")).isEqualTo("violations");
        assertThat(payload).doesNotContainKey("mandatory");
        List<Map<String, Object>> violations = tierViolations(payload, "preset");
        assertThat(violations).extracting(violation -> violation.get("rule"))
                .containsExactly(RobustKitLintAppService.RULE_HANDWRITTEN_SESSION,
                        RobustKitLintAppService.RULE_HANDWRITTEN_SESSION);
        assertThat(violations.get(0)).containsEntry("file", "src/app/login/actions.ts")
                .containsEntry("line", 8);
    }

    /** 经基座件的合法用法不误报：件目录路径（robust 实现）解析侧剔除——双保险。 */
    @Test
    void given_kit_files_in_output_when_parse_then_dropped_no_false_positive() {
        givenProbe(true);
        givenGrep("createClient", """
                ./src/lib/robust/data-access.ts:30:const pool = new Pool(...);
                ./src/lib/robust/session.ts:15:const jar = await cookies();
                ./src/components/robust/ErrorBoundary.tsx:9:await fetch('/health');
                """, 0);
        givenGrep("pbkdf2", "", 1);
        assertThat(appService.lintClosingPayload(WORKSPACE))
                .containsExactly(Map.entry("status", "passed"));
    }

    /** 清单截 20、total 如实（同设计规范 lint 截断先例——卡不无限长）。 */
    @Test
    void given_more_than_cap_when_scan_then_listed_capped_total_honest() {
        givenProbe(true);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            lines.append("./src/lib/db").append(i).append(".ts:1:await fetch('x')");
            if (i < 24) {
                lines.append('\n');
            }
        }
        givenGrep("createClient", lines.toString(), 0);
        givenGrep("pbkdf2", "", 1);
        Map<String, Object> payload = appService.lintClosingPayload(WORKSPACE);
        @SuppressWarnings("unchecked")
        Map<String, Object> mandatory = (Map<String, Object>) payload.get("mandatory");
        assertThat(mandatory.get("total")).isEqualTo(25);
        assertThat((List<?>) mandatory.get("violations")).hasSize(20);
    }

    /** 畸形行跳过不猜（grep 输出形是容器侧契约）；扫描异常退出＝unavailable 不假装达标。 */
    @Test
    void given_scan_faults_when_scan_then_unavailable_and_malformed_skipped() {
        givenProbe(true);
        givenGrep("createClient", "./not-a-path\n./src/app/x.ts:not-a-number:fetch('x')\n"
                + "./src/app/y.ts:5:await fetch('y')", 0);
        givenGrep("pbkdf2", "", 1);
        assertThat(tierViolations(appService.lintClosingPayload(WORKSPACE), "mandatory"))
                .hasSize(1);

        givenGrep("createClient", "", 2);
        assertThat(appService.lintClosingPayload(WORKSPACE))
                .containsExactly(Map.entry("status", "unavailable"));

        when(workspaceLifecycleAppService.exec(anyString(),
                argThat((WorkspaceExecCommand cmd) -> cmd != null
                        && cmd.command().contains("grep"))))
                .thenThrow(new RuntimeException("容器通道异常"));
        assertThat(appService.lintClosingPayload(WORKSPACE))
                .containsExactly(Map.entry("status", "unavailable"));
    }

    // ---------- 工具 ----------

    /** 健壮件在场探针桩（true＝新基座工作区、false＝存量工作区）。 */
    private void givenProbe(boolean kitPresent) {
        when(workspaceLifecycleAppService.exec(anyString(), argThat((WorkspaceExecCommand cmd) ->
                cmd != null && cmd.command().contains("data-access"))))
                .thenReturn(new ExecResultResponse("", "", kitPresent ? 0 : 1));
    }

    /** grep 桩：pattern 片段匹配分流（强制档/预置档各自 grep 的 stdout 与退出码）。 */
    private void givenGrep(String patternFragment, String stdout, int exitCode) {
        when(workspaceLifecycleAppService.exec(anyString(), argThat((WorkspaceExecCommand cmd) ->
                cmd != null && cmd.command().contains(patternFragment))))
                .thenReturn(new ExecResultResponse(stdout, "", exitCode));
    }

    /** 断言用：payload 某档违规清单（档缺席＝空）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tierViolations(Map<String, Object> payload,
            String tier) {
        Object section = payload.get(tier);
        if (!(section instanceof Map<?, ?> map)) {
            return List.of();
        }
        return (List<Map<String, Object>>) map.get("violations");
    }
}
