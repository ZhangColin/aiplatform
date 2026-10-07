package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.DesignSpecs;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 设计规范提炼线（#295，ADR-0028——伴随产出＋确定性提取、不经模型判读）的
 * 应用层：「提炼无所得不刷新／覆盖＝整行覆写／物化与转正不反噬出图与定稿」
 * 三口径的断言面（出现什么/不出现什么是一等断言）；确定性（同稿同产出）与
 * 提取规则归 {@link com.aieducenter.aiplatform.business.project.domain.model.DesignSpecsTest}，
 * 容器保真（token 与稿内 :root 一致、参数与物化草稿一致）归
 * {@code DesignSpecAppServiceLiveTest}。
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
                        .contains("cat > \"$p\""))
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

    // ---------- 工具 ----------

    /** 容器读桩的工作区文件表（相对路径 → 正文；不在表内＝读取 exit 1）。 */
    private final Map<String, String> workspaceFiles = new LinkedHashMap<>();

    private Project persistedProject() {
        return projectRepository.save(Project.create("规范提炼项目", null, 971901L, OWNER));
    }

    /** 正本行读口（jsonb 文本形态空白不定，读侧归一后断言）。 */
    private List<Map<String, Object>> specRows(Long projectId) {
        return jdbcTemplate.queryForList(
                "SELECT source_draft_path, source_run_id, tokens, palette, style"
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
