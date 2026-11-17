package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.model.DesignSpecs;
import com.semgravio.aiplatform.business.project.domain.model.StyleDirections;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 设计规范正本的应用面（#295 提炼线＋#296 遵守面，ADR-0028）：提炼侧「无所得
 * 不刷新／覆盖＝整行覆写／物化与转正不反噬出图与定稿」＋遵守侧「派发物化四件
 * 落位／无规范项目零写入（一等断言）／收口扫描三态不抛」的断言面（出现什么/
 * 不出现什么是一等断言）；确定性（同稿同产出）与改写规则归
 * {@link com.semgravio.aiplatform.business.project.domain.model.DesignSpecsTest}，
 * 容器保真（token 与稿内 :root 一致、参数与物化草稿一致、:root 写入/刷值/lint
 * 回执）归 {@code DesignSpecAppServiceLiveTest}。
 */
@IntegrationTest
class DesignSpecAppServiceTest {

    private static final long OWNER = 3897654321098765403L;

    /** 界面类稿（写稿协议：token 集中唯一 :root）。 */
    private static final String INTERFACE_DRAFT = """
            <!DOCTYPE html>
            <style>
            :root { --primary: #166534; --radius: 0.75rem; }
            body { color: var(--primary); }
            </style>
            """;

    @Autowired
    private DesignSpecAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_design_specs");
        jdbcTemplate.update("DELETE FROM prj_projects");
        // 风格层 kill-switch 归位（缺省生效态——测试间共享行，绝不把关停态留给下游）
        jdbcTemplate.update("UPDATE prj_style_direction_switch SET enabled = TRUE WHERE id = 1");
    }

    // ---------- 界面类：定稿确定性直提 ----------

    @Test
    void given_interface_draft_when_finalized_refresh_then_tokens_canonical_row() {
        // 灵魂用例（#295 验收②④）：界面类定稿 → 平台从稿内 :root 确定性直提
        // （零模型调用）→ 项目正本建行（token 面、源锚＝定稿稿＋定稿 runId）
        Project project = persistedProject();
        givenFileContent("/design/home-1.html", INTERFACE_DRAFT);

        appService.refreshAtFinalize(project, "/design/home-1.html", "run-final-1");

        assertThat(specRows(project.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("source_draft_path")).isEqualTo("/design/home-1.html");
            assertThat(row.get("source_run_id")).isEqualTo("run-final-1");
            assertThat(tokensFace(row)).isEqualTo(DesignSpecs.rootTokensOf(INTERFACE_DRAFT));
            assertThat(row.get("palette")).isNull();
            assertThat(row.get("style")).isNull();
        });
    }

    @Test
    void given_no_root_or_unreadable_draft_when_refresh_then_no_row_and_old_kept() {
        // 提炼无所得不刷新：协议偏离稿（无 :root）不建行；已有正本不被空稿冲掉
        //（品牌一致性——上一定稿的规范保持）
        Project project = persistedProject();
        givenFileContent("/design/plain-1.html", "<p>无样式稿</p>");
        appService.refreshAtFinalize(project, "/design/plain-1.html", "run-plain");
        assertThat(specRows(project.getId())).isEmpty();

        givenFileContent("/design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project, "/design/home-1.html", "run-final-1");
        givenFileContent("/design/plain-2.html", "<p>又一张无样式稿</p>");
        appService.refreshAtFinalize(project, "/design/plain-2.html", "run-plain-2");
        assertThat(specRows(project.getId())).singleElement()
                .satisfies(row -> assertThat(row.get("source_run_id")).isEqualTo("run-final-1"));

        // 读取面不可达（容器不在稿/超限/环境故障）同律如实无所得
        when(workspaceLifecycleAppService.exec(anyString(),
                any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "", 1));
        assertThatCode(() -> appService.refreshAtFinalize(
                project, "/design/home-1.html", "run-quiet")).doesNotThrowAnyException();
        assertThat(specRows(project.getId())).singleElement()
                .satisfies(row -> assertThat(row.get("source_run_id")).isEqualTo("run-final-1"));
    }

    // ---------- 平面类：出图参数随稿物化、定稿转正 ----------

    @Test
    void given_params_when_generate_landed_then_sidecar_materialized_per_draft() {
        // 灵魂用例（#295 验收③前半）：出图参数在场 → 逐张落盘稿各物化一份
        // 同词干侧车（参数即设计意图正身）；参数缺席＝无设计意图，不物化空壳
        appService.materializePrintParams("971900", List.of(
                "design/384926573-logo-a.png", "design/384926574-logo-b.png"),
                List.of("#166534", "#faf9f6"), "扁平暖调");
        ArgumentCaptor<WorkspaceExecCommand> commands =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, times(2))
                .execWithStdin(anyString(), commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(WorkspaceExecCommand::command)
                .allSatisfy(command -> assertThat(command)
                        .contains(".spec.json")
                        .contains("cat > '/workspace/design/"))
                .anySatisfy(command -> assertThat(command)
                        .contains("384926573-logo-a.spec.json"))
                .anySatisfy(command -> assertThat(command)
                        .contains("384926574-logo-b.spec.json"));

        clearInvocations(workspaceLifecycleAppService);
        appService.materializePrintParams("971900",
                List.of("design/384926575-logo-c.png"), null, null);
        verify(workspaceLifecycleAppService, never())
                .execWithStdin(anyString(), any(WorkspaceExecCommand.class), any());
    }

    @Test
    void given_materialize_write_failure_when_generate_then_quiet_no_throw() {
        // 物化失败不反噬出图（规范草稿是伴随产出，不是出图的成败判据）
        when(workspaceLifecycleAppService.execWithStdin(anyString(),
                any(WorkspaceExecCommand.class), any()))
                .thenThrow(new RuntimeException("容器通道异常"));
        assertThatCode(() -> appService.materializePrintParams("971900",
                List.of("design/384926573-logo-a.png"),
                List.of("#166534"), null)).doesNotThrowAnyException();
    }

    @Test
    void given_image_draft_with_sidecar_when_finalized_then_params_promoted() {
        // 灵魂用例（#295 验收③后半）：平面类定稿 → 侧车参数转正进项目正本
        //（参数面：色板＋风格）——「logo 品牌色由此进系统主题」的取件面
        Project project = persistedProject();
        givenFileContent("/design/384926573-logo-a.spec.json",
                "{\"palette\":[\"#166534\",\"#faf9f6\"],\"style\":\"扁平暖调\"}");

        appService.refreshAtFinalize(project, "/design/384926573-logo-a.png", "run-final-2");

        assertThat(specRows(project.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("source_draft_path")).isEqualTo("/design/384926573-logo-a.png");
            assertThat(paletteFace(row)).isEqualTo(List.of("#166534", "#faf9f6"));
            assertThat(row.get("style")).isEqualTo("扁平暖调");
            assertThat(row.get("tokens")).isNull();
        });
    }

    @Test
    void given_style_only_sidecar_when_promoted_then_palette_null_style_present() {
        // 仅风格侧车（色板缺席）：palette 落 NULL（列语义「token 形为 NULL」不与
        // 空数组混淆）、风格如实转正
        Project project = persistedProject();
        givenFileContent("/design/384926573-logo-a.spec.json",
                "{\"style\":\"极简几何\"}");

        appService.refreshAtFinalize(project, "/design/384926573-logo-a.png", "run-style-only");

        assertThat(specRows(project.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("palette")).isNull();
            assertThat(row.get("style")).isEqualTo("极简几何");
            assertThat(row.get("tokens")).isNull();
        });
    }

    @Test
    void given_print_png_without_sidecar_when_finalized_then_html_source_tokens_fallback() {
        // 代码出图路（#292）：稿面是渲成 PNG（正身）、:root 在同名 HTML 源里——
        // 侧车缺席（图片模型路才物化侧车）回落 HTML 源直提
        Project project = persistedProject();
        givenFileContent("/design/poster-1.html", """
                <!-- print: 800x1200 -->
                <style>:root { --primary: #b91c1c; }</style>
                """);

        appService.refreshAtFinalize(project, "/design/poster-1.png", "run-final-3");

        assertThat(specRows(project.getId())).singleElement().satisfies(row -> {
            assertThat(tokensFace(row)).isEqualTo(Map.of("--primary", "#b91c1c"));
            assertThat(row.get("palette")).isNull();
        });
    }

    @Test
    void given_malformed_sidecar_when_promote_then_no_row() {
        // 侧车畸形（执行体改写/半截写）＝如实无所得，不猜不炸
        Project project = persistedProject();
        givenFileContent("/design/384926573-logo-a.spec.json", "{\"palette\": 未闭合");

        appService.refreshAtFinalize(project, "/design/384926573-logo-a.png", "run-bad");

        assertThat(specRows(project.getId())).isEmpty();
    }

    // ---------- 项目单一正本：新定稿覆盖旧规范、多设计物共用 ----------

    @Test
    void given_existing_canonical_when_new_finalize_then_overwritten_single_row() {
        // 灵魂用例（#295 验收④）：新定稿覆盖旧规范（整行覆写、两面互斥——
        // token 形让位参数形）、项目恒一行（多设计物共用同一正本）
        Project project = persistedProject();
        givenFileContent("/design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project, "/design/home-1.html", "run-final-1");
        givenFileContent("/design/384926573-logo-a.spec.json",
                "{\"palette\":[\"#166534\"],\"style\":\"扁平暖调\"}");

        appService.refreshAtFinalize(project, "/design/384926573-logo-a.png", "run-final-2");

        assertThat(specRows(project.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("source_draft_path")).isEqualTo("/design/384926573-logo-a.png");
            assertThat(row.get("source_run_id")).isEqualTo("run-final-2");
            assertThat(row.get("tokens")).isNull();
            assertThat(paletteFace(row)).isEqualTo(List.of("#166534"));
        });
    }

    // ---------- 遵守面（#296）：派发物化（三件套①）＋收口扫描（三件套③） ----------

    /** 基座 globals.css（真实形态节选——物化的改写对象）。 */
    private static final String BASELINE_CSS = """
            @import "tailwindcss";
            @import "shadcn/tailwind.css";
            @theme inline {
              --color-primary: var(--primary);
            }
            :root {
              --primary: oklch(0.205 0 0);
              --radius: 0.625rem;
              --chart-1: #123456;
            }
            """;

    @Test
    void given_existing_generated_project_without_spec_when_dispatch_then_zero_writes_and_false() {
        // 一等断言（#296 验收⑤后半＋#543 存量不迁移）：已生成项目（存量）无规范
        // 派发零物化零写入——基座行为零改变（新项目首个构建的缺省套物化归
        // 风格分支断言面）
        Project project = persistedProject();
        project.markGenerated();
        projectRepository.save(project);
        assertThat(appService.materializeAtDispatch(project)).isFalse();
        verify(workspaceLifecycleAppService, never()).exec(anyString(), any());
        verify(workspaceLifecycleAppService, never())
                .execWithStdin(anyString(), any(), any());
    }

    @Test
    void given_tokens_spec_when_dispatch_materialize_then_css_rules_lintconfig_written() {
        // 灵魂用例（#296 验收①）：带规范项目起跑物化——:root 刷值＋平台收窄块＋
        // DESIGN.md 规则文件＋lint 配置四件落位（平台确定性动作、非模型）
        Project project = persistedProject();
        givenFileContent("/design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project, "/design/home-1.html", "run-final-1");
        givenFileContent("/src/app/globals.css", BASELINE_CSS);
        givenPlatformWriteSucceeds();

        assertThat(appService.materializeAtDispatch(project)).isTrue();

        ArgumentCaptor<WorkspaceExecCommand> commands =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        // 读 globals.css 一次（提炼取件通道）＋写入三件（globals.css / DESIGN.md / .oxlintrc.json）
        verify(workspaceLifecycleAppService, times(3))
                .execWithStdin(anyString(), commands.capture(), payloads.capture());
        Map<String, String> writes = new LinkedHashMap<>();
        for (int i = 0; i < commands.getAllValues().size(); i++) {
            writes.put(commands.getAllValues().get(i).command(),
                    new String(payloads.getAllValues().get(i), StandardCharsets.UTF_8));
        }
        String cssWrite = writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains("src/app/globals.css"))
                .findFirst().orElseThrow().getValue();
        assertThat(cssWrite)
                .contains("--primary: #166534;")      // :root 值刷换（换肤）
                .contains("--radius: 0.75rem;")       // spec 内 token 同刷（定稿为准）
                .contains("--chart-1: #123456;")      // 执行体自加 token 原样保留
                .contains("--color-*: initial;")      // 调色板收窄（硬约束）
                .doesNotContain("--primary: oklch(0.205 0 0)");
        String designMd = writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains("DESIGN.md"))
                .findFirst().orElseThrow().getValue();
        assertThat(designMd)
                .contains("# 设计规范")
                .contains("/design/home-1.html")
                .contains("| `--primary` | `#166534` |");
        String oxlintrc = writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains(".oxlintrc.json"))
                .findFirst().orElseThrow().getValue();
        assertThat(oxlintrc)
                .contains("shadcn/no-raw-colors")
                .contains("shadcn/no-arbitrary-values")
                .contains("shadcn/no-inline-styles");
    }

    @Test
    void given_params_spec_when_dispatch_materialize_then_brand_palette_into_root() {
        // 参数面（平面类定稿）：色板物化为 --brand-N 进 :root（logo 品牌色由此进
        // 系统主题）＋收窄块品牌色暴露＋规则文件出品牌色板与风格
        Project project = persistedProject();
        givenFileContent("/design/384926573-logo-a.spec.json",
                "{\"palette\":[\"#166534\",\"#faf9f6\"],\"style\":\"扁平暖调\"}");
        appService.refreshAtFinalize(project, "/design/384926573-logo-a.png", "run-final-2");
        givenFileContent("/src/app/globals.css", BASELINE_CSS);
        givenPlatformWriteSucceeds();

        assertThat(appService.materializeAtDispatch(project)).isTrue();

        ArgumentCaptor<WorkspaceExecCommand> commands =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(workspaceLifecycleAppService, times(3))
                .execWithStdin(anyString(), commands.capture(), payloads.capture());
        Map<String, String> writes = new LinkedHashMap<>();
        for (int i = 0; i < commands.getAllValues().size(); i++) {
            writes.put(commands.getAllValues().get(i).command(),
                    new String(payloads.getAllValues().get(i), StandardCharsets.UTF_8));
        }
        String cssWrite = writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains("src/app/globals.css"))
                .findFirst().orElseThrow().getValue();
        assertThat(cssWrite)
                .contains("--brand-1: #166534;")
                .contains("--brand-2: #faf9f6;")
                .contains("--color-brand-1: var(--brand-1);")
                .contains("--color-*: initial;");
        String designMd = writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains("DESIGN.md"))
                .findFirst().orElseThrow().getValue();
        assertThat(designMd)
                .contains("## 品牌色板")
                .contains("## 风格")
                .contains("扁平暖调");
    }

    @Test
    void given_globals_css_absent_when_dispatch_materialize_then_css_face_skipped() {
        // globals.css 不在（执行体重构了样式文件）＝CSS 面跳过留日志，规则文件与
        // lint 配置照常落位（保守方向不破链）
        Project project = persistedProject();
        givenFileContent("/design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project, "/design/home-1.html", "run-final-1");
        givenPlatformWriteSucceeds();

        assertThat(appService.materializeAtDispatch(project)).isTrue();

        ArgumentCaptor<WorkspaceExecCommand> commands =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, times(2))
                .execWithStdin(anyString(), commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(WorkspaceExecCommand::command)
                .noneSatisfy(command -> assertThat(command).contains("globals.css"));
    }

    @Test
    void given_materialize_write_failure_when_dispatch_then_environment_fault() {
        // 物化失败＝环境故障如实上抛（run 不起跑——遵守链不静默缺席，对偶
        // AGENTS.md 资产就位口径）
        Project project = persistedProject();
        givenFileContent("/design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project, "/design/home-1.html", "run-final-1");
        when(workspaceLifecycleAppService.execWithStdin(anyString(),
                any(WorkspaceExecCommand.class), any()))
                .thenReturn(new ExecResultResponse("", "写失败", 1));
        assertThatThrownBy(() -> appService.materializeAtDispatch(project))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void given_no_spec_when_closing_scan_then_null_without_exec() {
        // 一等断言（#296 验收⑤）：无规范项目收口不扫描——lint 只作用带规范项目
        Project project = persistedProject();
        assertThat(appService.lintClosingPayload(project)).isNull();
        verify(workspaceLifecycleAppService, never()).exec(anyString(), any());
    }

    @Test
    void given_spec_when_scan_clean_then_passed_payload() {
        Project project = persistedProjectWithSpec();
        givenOxlint(new ExecResultResponse("{}", "", 0));
        assertThat(appService.lintClosingPayload(project))
                .containsExactly(Map.entry("status", "passed"));
    }

    @Test
    void given_spec_when_scan_violations_then_parsed_and_capped() {
        // 灵魂用例（#296 验收④/⑥）：违规诊断解析为收尾卡条目（file/line/rule/
        // message——code 取括号内规则名）；清单截 20、total 如实
        Project project = persistedProjectWithSpec();
        StringBuilder diagnostics = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (!diagnostics.isEmpty()) {
                diagnostics.append(',');
            }
            diagnostics.append("""
                    {"message": "\\"bg-red-500\\" uses the raw Tailwind palette.",
                    "code": "shadcn(no-raw-colors)","severity": "error",
                    "filename": "src/app/page.tsx",
                    "labels": [{"span": {"line": %d}}]}""".formatted(i + 3));
        }
        givenOxlint(new ExecResultResponse(
                "{ \"diagnostics\": [" + diagnostics + "] }", "", 1));

        Map<String, Object> payload = appService.lintClosingPayload(project);

        assertThat(payload.get("status")).isEqualTo("violations");
        assertThat(payload.get("total")).isEqualTo(21);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) payload.get("violations");
        assertThat(violations).hasSize(20);
        assertThat(violations.get(0)).containsOnly(
                Map.entry("file", "src/app/page.tsx"),
                Map.entry("line", 3),
                Map.entry("rule", "no-raw-colors"),
                Map.entry("message", "\"bg-red-500\" uses the raw Tailwind palette."));
    }

    @Test
    void given_spec_when_scan_unavailable_then_reported_not_faked() {
        // 扫描不可执行（退出码非 0/1、诊断不可解析、通道异常）＝unavailable 如实
        // 呈现，不假装达标；全程不抛（收口链路不被扫描故障反噬）
        Project project = persistedProjectWithSpec();
        givenOxlint(new ExecResultResponse("", "oxlint: config error", 2));
        assertThat(appService.lintClosingPayload(project))
                .containsExactly(Map.entry("status", "unavailable"));

        givenOxlint(new ExecResultResponse("退出码 1 但这不是 JSON", "", 1));
        assertThat(appService.lintClosingPayload(project))
                .containsExactly(Map.entry("status", "unavailable"));

        when(workspaceLifecycleAppService.exec(anyString(),
                argThat(command -> command != null && command.command().contains("oxlint"))))
                .thenThrow(new RuntimeException("容器通道异常"));
        assertThatCode(() -> assertThat(appService.lintClosingPayload(project))
                .containsExactly(Map.entry("status", "unavailable")))
                .doesNotThrowAnyException();
    }

    // ---------- 风格方向来源（#543，ADR-0052 切片①） ----------

    /** 含风格方向字段的系统形 PRD（关键约束节——物化通道的读面对象）。 */
    private static final String PRD_WITH_DIRECTION = """
            # PRD
            ## 关键约束
            - 风格方向：清新教育（微调：圆角再大一点）
            - 数据存储采用平台内建数据库
            """;

    @Test
    void given_style_field_in_prd_when_first_dispatch_then_style_direction_materialized() {
        // 灵魂用例（#543 验收③）：新项目首个构建派发——平台读 PRD 字段→库值映射
        // 确定性物化四件（:root 刷库值＋收窄块／风格规则文件〔人读规则句＝库件〕／
        // lint 配置），正本行带 direction_key 标记（生成式来源可识别、可重物化）
        Project project = persistedProject();
        givenFileContent("/docs/PRD.md", PRD_WITH_DIRECTION);
        givenFileContent("/src/app/globals.css", BASELINE_CSS);
        givenPlatformWriteSucceeds();

        assertThat(appService.materializeAtDispatch(project)).isTrue();

        // 正本行：token 面＝库值集、方向标记、源锚＝PRD 正本
        assertThat(specRows(project.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("source_draft_path")).isEqualTo("docs/PRD.md");
            assertThat(row.get("source_run_id")).isEqualTo("style-materialization");
            assertThat(row.get("direction_key")).isEqualTo("fresh-education");
            assertThat(row.get("style")).isEqualTo("清新教育");
            assertThat(tokensFace(row))
                    .isEqualTo(StyleDirections.byName("清新教育").tokens());
        });
        // 写面：css 刷库值、DESIGN.md 出方向规则句、lint 配置在场
        Map<String, String> writes = writtenFiles();
        assertThat(writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains("src/app/globals.css"))
                .findFirst().orElseThrow().getValue())
                .contains("--primary: oklch(0.52 0.11 180);")
                .contains("--radius: 0.875rem;")
                .contains("--color-*: initial;");
        String designMd = writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains("DESIGN.md"))
                .findFirst().orElseThrow().getValue();
        assertThat(designMd)
                .contains("# 设计规范")
                .contains("风格方向：清新教育")
                .contains("## 风格方向规则（清新教育）")
                .contains("圆角偏大")          // 人读规则句＝库双件直陈
                .contains("遵守条目");
        assertThat(writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains(".oxlintrc.json"))
                .count()).isEqualTo(1);
    }

    @Test
    void given_missing_or_unknown_field_when_first_dispatch_then_default_direction_fallback() {
        // 兜底断言（#543 验收③后半）：字段缺失/库无此名/PRD 不可读＝落缺省套——
        // 生成不被风格层卡死
        Project noField = persistedProject();
        givenFileContent("/docs/PRD.md", "# PRD\n## 关键约束\n- 无风格行\n");
        givenFileContent("/src/app/globals.css", BASELINE_CSS);
        givenPlatformWriteSucceeds();
        assertThat(appService.materializeAtDispatch(noField)).isTrue();
        assertThat(specRows(noField.getId())).singleElement()
                .satisfies(row -> assertThat(row.get("direction_key"))
                        .isEqualTo(StyleDirections.DEFAULT_KEY));

        Project unknown = persistedProject();
        givenFileContent("/docs/PRD.md", "## 关键约束\n- 风格方向：赛博朋克\n");
        assertThat(appService.materializeAtDispatch(unknown)).isTrue();
        assertThat(specRows(unknown.getId())).singleElement()
                .satisfies(row -> assertThat(row.get("direction_key"))
                        .isEqualTo(StyleDirections.DEFAULT_KEY));
    }

    @Test
    void given_switch_off_when_dispatch_then_back_to_no_spec_status_quo() {
        // 读面测试（#543 验收⑤——沿收口开关测试先例）：kill-switch 关＝回无规范
        // 现状——物化零动作零写入、正本不建行、收口不扫；风格来源行在场同律
        jdbcTemplate.update("UPDATE prj_style_direction_switch SET enabled = FALSE WHERE id = 1");
        Project project = persistedProject();
        givenFileContent("/docs/PRD.md", PRD_WITH_DIRECTION);
        givenPlatformWriteSucceeds();

        assertThat(appService.materializeAtDispatch(project)).isFalse();
        assertThat(specRows(project.getId())).isEmpty();
        verify(workspaceLifecycleAppService, never()).exec(anyString(), any());
        verify(workspaceLifecycleAppService, never())
                .execWithStdin(anyString(), any(), any());
        assertThat(appService.lintClosingPayload(project)).isNull();

        // 已物化的风格来源行：关＝收口不扫（无规范现状）
        jdbcTemplate.update("UPDATE prj_style_direction_switch SET enabled = TRUE WHERE id = 1");
        givenFileContent("/src/app/globals.css", BASELINE_CSS);
        assertThat(appService.materializeAtDispatch(project)).isTrue();
        jdbcTemplate.update("UPDATE prj_style_direction_switch SET enabled = FALSE WHERE id = 1");
        verify(workspaceLifecycleAppService, never()).exec(anyString(),
                argThat(command -> command != null && command.command().contains("oxlint")));
        assertThat(appService.lintClosingPayload(project)).isNull();
    }

    @Test
    void given_style_source_row_when_prd_direction_changed_then_rematerialized_on_dispatch() {
        // 重物化（改风格＝对话→PRD 修订→派发对齐，零新通道）：风格来源行每次
        // 派发按 PRD 字段重刷——方向换了即换肤，正本行随刷
        Project project = persistedProject();
        givenFileContent("/docs/PRD.md", PRD_WITH_DIRECTION);
        givenFileContent("/src/app/globals.css", BASELINE_CSS);
        givenPlatformWriteSucceeds();
        assertThat(appService.materializeAtDispatch(project)).isTrue();

        clearInvocations(workspaceLifecycleAppService);
        workspaceFiles.put("docs/PRD.md", "## 关键约束\n- 风格方向：沉稳金融\n");
        assertThat(appService.materializeAtDispatch(project)).isTrue();

        assertThat(specRows(project.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("direction_key")).isEqualTo("calm-finance");
            assertThat(row.get("style")).isEqualTo("沉稳金融");
        });
        Map<String, String> writes = writtenFiles();
        assertThat(writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains("src/app/globals.css"))
                .findFirst().orElseThrow().getValue())
                .contains("--primary: oklch(0.42 0.10 275);")
                .doesNotContain("oklch(0.52 0.11 180)");
        assertThat(writes.entrySet().stream()
                .filter(entry -> entry.getKey().contains("DESIGN.md"))
                .findFirst().orElseThrow().getValue())
                .contains("## 风格方向规则（沉稳金融）");
    }

    @Test
    void given_existing_generated_project_without_spec_when_update_dispatch_then_no_style_migration() {
        // 存量不迁移：无正本的已生成项目（风格层落地前的存量）更新 run 起手——
        // 不补风格层（零动作零写入）
        Project project = persistedProject();
        project.markGenerated();
        projectRepository.save(project);
        givenFileContent("/docs/PRD.md", PRD_WITH_DIRECTION);

        assertThat(appService.materializeAtDispatch(project)).isFalse();
        assertThat(specRows(project.getId())).isEmpty();
        verify(workspaceLifecycleAppService, never())
                .execWithStdin(anyString(), any(), any());
    }

    @Test
    void given_design_line_spec_when_dispatch_then_direction_key_absent_and_generic_rules() {
        // 设计线来源行不受影响（#295 语义不动）：direction_key 恒 NULL、规则文件
        // 走定稿叙述——风格层零误伤
        Project project = persistedProject();
        givenFileContent("/design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project, "/design/home-1.html", "run-final-1");
        givenFileContent("/src/app/globals.css", BASELINE_CSS);
        givenPlatformWriteSucceeds();

        assertThat(appService.materializeAtDispatch(project)).isTrue();

        assertThat(specRows(project.getId())).singleElement()
                .satisfies(row -> assertThat(row.get("direction_key")).isNull());
        assertThat(writtenFiles().entrySet().stream()
                .filter(entry -> entry.getKey().contains("DESIGN.md"))
                .findFirst().orElseThrow().getValue())
                .contains("视觉正源（定稿设计稿")
                .doesNotContain("风格方向规则");
    }

    // ---------- 工具 ----------

    /** 捕获平台落盘写（命令 → 正文）——风格分支断言面。 */
    private Map<String, String> writtenFiles() {
        ArgumentCaptor<WorkspaceExecCommand> commands =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(workspaceLifecycleAppService, atLeastOnce())
                .execWithStdin(anyString(), commands.capture(), payloads.capture());
        Map<String, String> writes = new LinkedHashMap<>();
        for (int i = 0; i < commands.getAllValues().size(); i++) {
            writes.put(commands.getAllValues().get(i).command(),
                    new String(payloads.getAllValues().get(i), StandardCharsets.UTF_8));
        }
        return writes;
    }

    /** 容器读桩的工作区文件表（相对路径 → 正文；不在表内＝读取 exit 1）。 */
    private final Map<String, String> workspaceFiles = new LinkedHashMap<>();

    private Project persistedProject() {
        return projectRepository.save(Project.create("规范提炼项目", null, null, 971901L, OWNER, null));
    }

    /** 带规范正本的项目（界面类直提建行——遵守面测试的既有事实底座）。 */
    private Project persistedProjectWithSpec() {
        Project project = persistedProject();
        givenFileContent("/design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project, "/design/home-1.html", "run-final-1");
        return project;
    }

    /** 平台落盘写桩（execWithStdin 缺省成功——物化路径不打桩时 Mockito 默认 null）。 */
    private void givenPlatformWriteSucceeds() {
        when(workspaceLifecycleAppService.execWithStdin(anyString(),
                any(WorkspaceExecCommand.class), any()))
                .thenReturn(new ExecResultResponse("123", "", 0));
    }

    /**
     * oxlint 收口扫描桩（后注册的最后匹配优先——命令含 oxlint 的 exec 分岔到本桩，
     * 其余命令仍走 {@link #givenFileContent} 的文件表桩）。
     */
    private void givenOxlint(ExecResultResponse result) {
        when(workspaceLifecycleAppService.exec(anyString(),
                argThat(command -> command != null && command.command().contains("oxlint"))))
                .thenReturn(result);
    }

    /** 正本行读口（jsonb 文本形态空白不定，读侧归一后断言）。 */
    private List<Map<String, Object>> specRows(Long projectId) {
        return jdbcTemplate.queryForList(
                "SELECT source_draft_path, source_run_id, tokens, palette, style, direction_key"
                        + " FROM prj_design_specs WHERE project_id = ?",
                projectId);
    }

    /** jsonb 列 → Map/List（jsonb 不保序，反序列化后按集合等值断言）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, String> tokensFace(Map<String, Object> row) {
        try {
            return JSON.readValue(String.valueOf(row.get("tokens")),
                    new TypeReference<Map<String, String>>() {
                    });
        }
        catch (Exception e) {
            throw new IllegalStateException("tokens 面应为可解析 jsonb 对象", e);
        }
    }

    private static List<String> paletteFace(Map<String, Object> row) {
        try {
            return JSON.readValue(String.valueOf(row.get("palette")),
                    new TypeReference<List<String>>() {
                    });
        }
        catch (Exception e) {
            throw new IllegalStateException("palette 面应为可解析 jsonb 数组", e);
        }
    }

    /** 按路径分岔的容器读桩（同一测试可多次登记——单桩按表分岔，contentCommand
     * 命中回「大小行＋正文」，未命中/不在＝exit 1 如实无所得）。 */
    private void givenFileContent(String anchoredPath, String content) {
        workspaceFiles.put(anchoredPath.substring(1), content);
        when(workspaceLifecycleAppService.exec(anyString(),
                any(WorkspaceExecCommand.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.<WorkspaceExecCommand>getArgument(1).command();
                    for (Map.Entry<String, String> file : workspaceFiles.entrySet()) {
                        if (command.contains(file.getKey())) {
                            return new ExecResultResponse(
                                    file.getValue().getBytes(StandardCharsets.UTF_8).length
                                            + "\n" + file.getValue(),
                                    "", 0);
                        }
                    }
                    return new ExecResultResponse("", "", 1);
                });
    }
}
