package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.DesignSpecs;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMaterials;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 规范正本容器活体（#295 验收⑤＋#296 验收⑦；docker daemon 不在整类跳过，对偶出图
 * 冒烟口径）：真 dev 容器里走全链——界面类稿 :root 直提落正本（提取的 token 与稿内
 * :root 一致——cat 回读独立见证）、平面类参数物化侧车＋定稿转正（平面参数与物化
 * 草稿一致）、遵守面派发物化（:root 刷值落位/再物化＝换肤幂等、DESIGN.md／lint
 * 配置落位）与收口扫描（oxlint＋@shadcn/lint 真跑：违规回执带定位与规则、修净即
 * passed、基座骨架页合规）。提炼与物化是确定性平台动作（零供应商依赖），无需出图
 * key；「执行体真按协议写 :root／修违规」的活体走查归用户（绿测≠能跑）。
 */
@IntegrationTest
class DesignSpecAppServiceLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 600;

    /** 界面类稿（写稿协议形态：token 集中唯一 :root、正文引用变量）。 */
    private static final String INTERFACE_DRAFT = """
            <!DOCTYPE html>
            <html lang="zh">
            <head><meta charset="utf-8"><style>
            :root {
              --background: #faf9f6;
              --foreground: #1c1917;
              --primary: #166534;
              --radius: 0.75rem;
              --font-sans: 'PingFang SC', system-ui, sans-serif;
            }
            body { background: var(--background); color: var(--foreground); }
            </style></head>
            <body><main>品牌主视觉</main></body>
            </html>
            """;

    private static final List<String> PALETTE = List.of("#166534", "#faf9f6", "#f59e0b");
    private static final String STYLE = "扁平暖调、圆润、留白充分";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private DesignSpecAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String workspaceId;
    private Long projectId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        if (projectId != null) {
            jdbcTemplate.update("DELETE FROM prj_design_specs WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
        if (workspaceId != null) {
            try {
                workspaceLifecycleAppService.destroy(workspaceId);
            }
            catch (RuntimeException e) {
                // 物理清理尽力而为（记录已随 destroy 删；Docker 残留可重试销毁）
            }
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_interface_draft_in_container_when_finalized_then_tokens_match_root_block() {
        // 灵魂用例（验收⑤前半）：提取的 token 与稿内 :root 一致——正本行由真容器
        // 里的稿直提而来，且与 cat 回读稿文再直提的结果逐位相等（容器事实独立见证）
        givenProjectWithWorkspace();
        writeFile("design/home-1.html", INTERFACE_DRAFT);

        appService.refreshAtFinalize(project(), "/design/home-1.html", "live-run-1");

        Map<String, Object> row = specRow();
        assertThat(row).isNotNull();
        assertThat(row.get("source_draft_path")).isEqualTo("/design/home-1.html");
        assertThat(row.get("source_run_id")).isEqualTo("live-run-1");
        Map<String, String> expected = DesignSpecs.rootTokensOf(INTERFACE_DRAFT);
        assertThat(expected).isNotEmpty(); // fixture 自证：协议形态稿确有 token 可提
        assertThat(tokensFace(row)).as("提取的 token 与稿内 :root 一致").isEqualTo(expected);
        // 独立见证：容器 cat 回读的稿文（不经提炼通道）直提结果与正本一致
        assertThat(DesignSpecs.rootTokensOf(readFile("design/home-1.html")))
                .isEqualTo(tokensFace(row));
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_print_params_when_materialized_and_finalized_then_canonical_matches() {
        // 灵魂用例（验收⑤后半）：平面参数与物化草稿一致——出图参数随稿物化落
        // design/ 侧车（容器事实）、定稿转正进正本（色板＋风格逐位一致）
        givenProjectWithWorkspace();
        writeFile("design/384926573-logo-a.png", "png-placeholder-bytes");

        appService.materializePrintParams(workspaceId, List.of("design/384926573-logo-a.png"),
                PALETTE, STYLE);

        String sidecar = readFile("design/384926573-logo-a.spec.json");
        assertThat(sidecar).as("侧车已随稿物化（容器事实）").contains("#166534").contains(STYLE);

        appService.refreshAtFinalize(project(), "/design/384926573-logo-a.png", "live-run-2");

        Map<String, Object> row = specRow();
        assertThat(row).isNotNull();
        assertThat(row.get("source_draft_path")).isEqualTo("/design/384926573-logo-a.png");
        assertThat(paletteFace(row)).as("平面参数（色板）与物化草稿一致").isEqualTo(PALETTE);
        assertThat(row.get("style")).as("平面参数（风格）与物化草稿一致").isEqualTo(STYLE);
        assertThat(row.get("tokens")).isNull();
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_spec_in_container_when_dispatch_materialize_then_root_written_then_refreshed() {
        // 灵魂用例（#296 验收①②⑦）：派发物化容器事实——:root 刷值落位（cat 回读
        // 独立见证：定稿 token 值进基座）＋平台收窄块＋DESIGN.md／lint 配置；再定稿
        // 再物化＝刷值（换肤）且标记块唯一（幂等不叠加）
        givenProjectWithWorkspace();
        writeFile("design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project(), "/design/home-1.html", "live-run-3");

        assertThat(appService.materializeAtDispatch(project())).isTrue();

        String css = readFile(DesignSpecs.BASELINE_GLOBALS_CSS);
        assertThat(css).as("定稿 token 值刷进基座 :root").contains("--primary: #166534;");
        assertThat(css).as("基座缺省值已被换肤").doesNotContain("--primary: oklch(0.205 0 0)");
        assertThat(css).as("调色板收窄块在位（先于 @theme inline）")
                .contains("--color-*: initial;")
                .contains(DesignSpecs.SPEC_THEME_BEGIN);
        assertThat(css.indexOf(DesignSpecs.SPEC_THEME_END))
                .isLessThan(css.indexOf("@theme inline"));
        assertThat(readFile("DESIGN.md")).as("规则文件落位").contains("# 设计规范").contains("/design/home-1.html");
        assertThat(readFile(".oxlintrc.json")).as("lint 配置落位").contains("shadcn/no-raw-colors");

        // 中途更新＝先确定性刷值：新定稿换主色 → 再物化 → :root 值换掉、收窄块唯一
        writeFile("design/home-2.html", """
                <!DOCTYPE html><html lang="zh"><head><meta charset="utf-8"><style>
                :root { --primary: #b91c1c; --radius: 0.75rem; }
                body { background: var(--primary); }
                </style></head><body></body></html>
                """);
        appService.refreshAtFinalize(project(), "/design/home-2.html", "live-run-4");
        assertThat(appService.materializeAtDispatch(project())).isTrue();

        String refreshed = readFile(DesignSpecs.BASELINE_GLOBALS_CSS);
        assertThat(refreshed).as("新定稿刷值＝全局换肤").contains("--primary: #b91c1c;");
        assertThat(refreshed).doesNotContain("--primary: #166534;");
        assertThat(countOccurrences(refreshed, DesignSpecs.SPEC_THEME_BEGIN))
                .as("标记块幂等唯一").isEqualTo(1);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_violating_page_in_container_when_closing_scan_then_receipt_roundtrip() {
        // 灵魂用例（#296 验收④⑦）：lint 回执容器事实——基座装好的 oxlint＋@shadcn/lint
        // 真跑：违规页 → violations 带文件/行号/规则；修掉 → passed。基座骨架页本身
        // 合规（token 化）＝首个构建不被空违规炸修轮的活证
        givenProjectWithWorkspace();
        writeFile("design/home-1.html", INTERFACE_DRAFT);
        appService.refreshAtFinalize(project(), "/design/home-1.html", "live-run-5");
        assertThat(appService.materializeAtDispatch(project())).isTrue();

        // 基座骨架页（物化后的收窄环境）应干净
        Map<String, Object> baselineScan = appService.lintClosingPayload(project());
        assertThat(baselineScan).as("基座骨架页合规（不空耗修轮）").containsEntry("status", "passed");

        // 执行体写了裸色 → 扫描回执带定位与规则
        writeFile("src/app/violating-page.tsx", """
                export default function Page() {
                  return (
                    <div className="bg-red-500 p-[13px]" style={{ margin: 4 }}>
                      <span className="text-muted-foreground">违规样例</span>
                    </div>
                  );
                }
                """);
        Map<String, Object> payload = appService.lintClosingPayload(project());
        assertThat(payload.get("status")).isEqualTo("violations");
        assertThat((Integer) payload.get("total")).isGreaterThanOrEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) payload.get("violations");
        assertThat(violations).extracting(violation -> violation.get("rule"))
                .contains("no-raw-colors", "no-arbitrary-values", "no-inline-styles");
        assertThat(violations).allSatisfy(violation -> {
            assertThat((String) violation.get("file")).isEqualTo("src/app/violating-page.tsx");
            assertThat((Integer) violation.get("line")).isPositive();
        });

        // 修掉（token 化）→ passed
        writeFile("src/app/violating-page.tsx", """
                export default function Page() {
                  return (
                    <div className="bg-primary p-3">
                      <span className="text-muted-foreground">合规样例</span>
                    </div>
                  );
                }
                """);
        assertThat(appService.lintClosingPayload(project())).containsEntry("status", "passed");
    }

    /** 子串计数（幂等唯一性断言）。 */
    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // ---------- 工具 ----------

    private void givenProjectWithWorkspace() {
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        Project project = projectRepository.save(Project.create("规范提炼活体", null,
                Long.parseLong(workspaceId), 3897654321098765404L));
        projectId = project.getId();
    }

    private Project project() {
        return projectRepository.findById(projectId).orElseThrow();
    }

    /** 经 stdin 写工作区文件（上传通道同款命令——mkdir 兜底＋字节回执）。 */
    private void writeFile(String relativePath, String content) {
        ExecResultResponse result = workspaceLifecycleAppService.execWithStdin(workspaceId,
                new WorkspaceExecCommand(ProjectMaterials.uploadCommand(relativePath)),
                content.getBytes(StandardCharsets.UTF_8));
        assertThat(result.exitCode()).as("fixture 写入应成功：%s", result.stderr()).isZero();
    }

    /** 提炼取件同通道读回（contentCommand：大小行＋正文）。 */
    private String readFile(String relativePath) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(workspaceId,
                new WorkspaceExecCommand(ProjectFiles.contentCommand(relativePath)));
        assertThat(result.exitCode()).as("容器读回应成功：%s", result.stderr()).isZero();
        return result.stdout().substring(result.stdout().indexOf('\n') + 1);
    }

    private Map<String, Object> specRow() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT source_draft_path, source_run_id, tokens, palette, style"
                        + " FROM prj_design_specs WHERE project_id = ?", projectId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

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

    private static boolean dockerAvailable() {
        try {
            return new ProcessBuilder("docker", "info").redirectErrorStream(true)
                    .start().waitFor() == 0;
        }
        catch (Exception e) {
            return false;
        }
    }
}
