package com.aieducenter.aiplatform.business.project.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

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
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignItem;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMaterials;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignItemRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计资产包容器活体（#297 验收「冻结件内容集」的真运行面；docker daemon 不在
 * 整类跳过，对偶规范活体口径）：真 dev 容器里走全链——定稿稿＋规范正本在容器，
 * 下单冻结（stage → seal）产出真 tar，成员集经容器侧 {@code tar tzf} 回读独立
 * 见证：选定稿双形态＋DESIGN.md 规范＋帧衍生入包、落选稿/规范侧车不入、暂存件
 * 已落名不在。系统＋设计单活证统一部件容器（整树＋选件共存、exports 自排除）。
 * 「用户确认下单后拿到包」的活体走查归用户（绿测≠能跑）。
 */
@IntegrationTest
class DesignPackageAppServiceLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 600;

    /** 界面类定稿稿（写稿协议形态——token 集中 :root，规范正本由此直提）。 */
    private static final String HERO_DRAFT = """
            <!DOCTYPE html>
            <html lang="zh">
            <head><meta charset="utf-8"><style>
            :root { --primary: #166534; --radius: 0.75rem; }
            body { color: var(--primary); }
            </style></head>
            <body><main>首页主视觉</main></body>
            </html>
            """;

    @Autowired
    private DesignPackageAppService appService;

    @Autowired
    private DesignSpecAppService designSpecAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DesignItemRepository designItems;

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
            jdbcTemplate.update("DELETE FROM prj_design_items WHERE project_id = ?", projectId);
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
    void given_design_project_when_staged_and_sealed_then_tar_members_are_exact() {
        // 灵魂用例：冻结件内容集＝选定稿＋规范＋衍生、落选稿不入——tar 真包成员
        // 由容器侧 tar tzf 回读见证（不经打包通道的独立事实）
        givenDesignProject(ProjectEndpointType.DESIGN);
        writeFile("design/hero.html", HERO_DRAFT);
        writeFile("design/logo.png", "png-bytes-of-logo");
        writeFile("design/logo.html", "<html><body>logo 源稿</body></html>");
        writeFile("design/logo.spec.json", "{\"palette\":[\"#166534\"]}");
        writeFile("design/hero-v3.html", "<html><body>落选的第三稿</body></html>");
        finalizeItem(1, "/design/hero.html");
        finalizeItem(2, "/design/logo.png");
        designSpecAppService.refreshAtFinalize(project(), "/design/hero.html", "live-run-1");

        DesignPackageAppService.StagedPackage staged = appService.stageForOrder(projectId);
        Long orderId = 991901L;
        appService.sealForOrder(staged, orderId);

        List<String> members = tarMembers(orderId);
        assertThat(members).as("选定稿自包含形态（界面类稿＋平面类双形态）＋规范＋帧衍生入包")
                .contains("./DESIGN.md", "./design/hero.html", "./design/logo.html",
                        "./design/logo.png", "./exports/hero-1280x800.png");
        assertThat(members).as("落选稿与规范侧车结构性不入")
                .noneMatch(member -> member.contains("hero-v3") || member.contains(".spec.json"));
        assertThat(members).as("冻结件自身不进包（exports 自排除）")
                .noneMatch(member -> member.contains("design-package"));
        // 暂存件已落名不在（mv 原子改名）；冻结件字节经取件通道原样回读（gzip 魔数）
        assertThat(testFileAbsent(".design-package-" + projectId + ".staging.tar.gz")).isTrue();
        byte[] bytes = appService.frozenPackage(projectId, orderId);
        assertThat(bytes.length).isPositive();
        assertThat(bytes[0] & 0xFF).isEqualTo(0x1f);
        assertThat(bytes[1] & 0xFF).isEqualTo(0x8b);
        // 帧衍生真渲（chromium 位图化）——PNG 魔数回读独立见证
        ExecResultResponse derivative = workspaceLifecycleAppService.exec(workspaceId,
                new WorkspaceExecCommand("head -c 8 /workspace/exports/hero-1280x800.png | od -An -tx1"));
        assertThat(derivative.stdout().trim()).startsWith("89 50 4e 47");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_system_design_project_when_staged_then_unified_container_members() {
        // 系统＋设计单活证：统一部件容器——系统源码整树（自写的应用文件入包）与
        // 设计部件（design/＋DESIGN.md＋衍生）同包共存、目录即类型边界
        givenDesignProject(ProjectEndpointType.SYSTEM_DESIGN);
        writeFile("src/app/page.tsx", "export default function Page() { return null; }");
        writeFile("package.json", "{\"name\":\"live-unified\"}");
        writeFile("design/hero.html", HERO_DRAFT);
        finalizeItem(1, "/design/hero.html");
        designSpecAppService.refreshAtFinalize(project(), "/design/hero.html", "live-run-2");

        DesignPackageAppService.StagedPackage staged = appService.stageForOrder(projectId);
        Long orderId = 991902L;
        appService.sealForOrder(staged, orderId);

        List<String> members = tarMembers(orderId);
        assertThat(members).as("系统部件与设计部件同包共存")
                .contains("./src/app/page.tsx", "./package.json",
                        "./design/hero.html", "./DESIGN.md");
        assertThat(members).as("冻结件自身不进包").noneMatch(member -> member.contains("design-package"));
    }

    // ---------- 工具 ----------

    private void givenDesignProject(ProjectEndpointType endpointType) {
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        Project project = projectRepository.save(Project.create("资产包活体", null,
                Long.parseLong(workspaceId), 3897654321098765405L));
        project.switchEndpoint(endpointType, null);
        project = projectRepository.save(project);
        projectId = project.getId();
    }

    private void finalizeItem(int ord, String anchoredPath) {
        DesignItem item = DesignItem.pending(projectId, ord, "活体设计物 " + ord,
                LocalDateTime.now());
        item.finalize("live-run", anchoredPath);
        designItems.save(item);
    }

    private Project project() {
        return projectRepository.findById(projectId).orElseThrow();
    }

    private void writeFile(String relativePath, String content) {
        ExecResultResponse result = workspaceLifecycleAppService.execWithStdin(workspaceId,
                new WorkspaceExecCommand(ProjectMaterials.uploadCommand(relativePath)),
                content.getBytes(StandardCharsets.UTF_8));
        assertThat(result.exitCode()).as("fixture 写入应成功：%s", result.stderr()).isZero();
    }

    /** 容器侧 tar tzf 回读成员清单（独立见证通道——不经打包/选件代码）。 */
    private List<String> tarMembers(Long orderId) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(workspaceId,
                new WorkspaceExecCommand(
                        "tar tzf /workspace/exports/design-package-" + orderId + ".tar.gz"));
        assertThat(result.exitCode()).as("tar tzf 应成功：%s", result.stderr()).isZero();
        return result.stdout().lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private boolean testFileAbsent(String exportsRelativeName) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(workspaceId,
                new WorkspaceExecCommand(
                        "test -f /workspace/exports/" + exportsRelativeName + " && echo present || echo absent"));
        return "absent".equals(result.stdout().trim());
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info").start();
            return process.waitFor() == 0;
        }
        catch (Exception e) {
            return false;
        }
    }
}
