package com.aieducenter.aiplatform.business.project.endpoints.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台项目文件区只读端点（#163，/api/backoffice/projects/{id}/files 与
 * /files/content?path=）在 {@code #152} seam 上全绿：MockMvc 穿完整过滤链（签名
 * 验签 → 会话豁免 → @RequireSignature 强制闸）→ 真应用服务 → aiplatform_test
 * 真库。同源委托用户面应用服务（ProjectQueryAppService#files/fileContent）——
 * 路径判定与上限守卫由构造不漂移：拒机密/逃逸/非交付物 PRJ_020（判定层拒绝、
 * 工作区不被触达）、不存在 PRJ_021、超 1MiB PRJ_022（容器侧拦截不读取）、
 * 含 NUL 非文本 PRJ_023、环境故障 WSP_002。
 *
 * <p>#163 的验收核心：文件区挂项目不挂订单——<strong>未下单项目可浏览</strong>
 * （源码包只挂订单的排障缺口在此补上，夹具零订单＋库内断言钉死）；归档项目
 * 照读（工作区保留）；已删/未知项目 404 PRJ_001 不触工作区。docker 依赖照
 * {@code BackofficeProjectContentSeamTest} 形制 {@code @MockitoBean} 收口在
 * WorkspaceLifecycleAppService（外部设施边界）。</p>
 */
@BackofficeSeamTest
class BackofficeProjectFilesSeamTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** docker 依赖收口（文件树/内容正本＝容器内直读，同 #162 形制）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    /** 工作区软引用无 FK：夹具自增占位（一项目一 dev 环境的隔离只求不撞号）。 */
    private long workspaceSeq = 920400L;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 文件树（交付文件视图） ----------

    @Test
    void given_workspace_files_when_signed_files_then_viewable_tree_sorted_by_path()
            throws Exception {
        Long id = newProject("文件树项目").getId();
        // find 是目录序非字典序：夹具故意乱序，钉「按路径稳定排序」
        stubExec((command, out) -> out.stdout =
                "1024\tzz-app.js\n512\tREADME.md\n2048\tsrc/index.html\n");

        signedGet("/api/backoffice/projects/" + id + "/files")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value(id.toString()))
                .andExpect(jsonPath("$.data.files[*].path",
                        contains("README.md", "src/index.html", "zz-app.js")))
                .andExpect(jsonPath("$.data.files[0].size").value(512))
                .andExpect(jsonPath("$.data.files[2].size").value(1024));
    }

    @Test
    void given_empty_workspace_when_signed_files_then_empty_list_not_error() throws Exception {
        Long id = newProject("空工作区项目").getId();
        stubExec((command, out) -> out.stdout = "");

        // 空输出 = 空工作区：空列表 200，非错误
        signedGet("/api/backoffice/projects/" + id + "/files")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(0));
    }

    // ---------- 未下单项目（#163 排障缺口：文件区挂项目不挂订单） ----------

    @Test
    void given_project_without_any_order_when_signed_file_area_then_both_readable()
            throws Exception {
        Long id = newProject("从未下单的项目").getId();
        // 钉死排障缺口语境：该库内零订单，浏览仍全通（源码包只挂订单、文件区不设此门槛）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_orders WHERE project_id = ?", Integer.class, id))
                .isZero();
        stubExec((command, out) -> {
            if (command.startsWith("find ")) {
                out.stdout = "256\tsrc/app.js\n";
            } else {
                out.stdout = "256\nexport default app;\n";
            }
        });

        signedGet("/api/backoffice/projects/" + id + "/files")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].path").value("src/app.js"));
        signedGet("/api/backoffice/projects/" + id + "/files/content?path=src/app.js")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("src/app.js"))
                .andExpect(jsonPath("$.data.content").value("export default app;\n"));
    }

    // ---------- 守卫同源（口径照用户面先例） ----------

    @Test
    void given_secret_or_escaping_or_non_deliverable_path_when_signed_content_then_prj_020()
            throws Exception {
        Long id = newProject("守卫项目").getId();

        // 机密（根级 .env）/逃逸（.. 与绝对路径）/非交付物（data/、node_modules/、
        // external/ 外部仓库资料）——判定层一律 400 PRJ_020，错误口径照用户面先例。
        // 入参取裸字符路径（/ . - 均合法）：MockMvc 按 URI 模板再编码，百分号编码
        // 会破坏签名基串
        for (String path : new String[] {".env", "../escape.txt", "src/../../etc/passwd",
                "/abs/path.js", "data/dump.json", "node_modules/vue/index.js",
                "external/some-repo/README.md"}) {
            signedGet("/api/backoffice/projects/" + id + "/files/content?path=" + path)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("该文件不在可浏览范围"));
        }
        // 判定层拒绝：工作区不被触达（docker 边界零 exec）
        verify(workspaceLifecycleAppService, never()).exec(anyString(), any());
    }

    @Test
    void given_missing_file_when_signed_content_then_prj_021() throws Exception {
        Long id = newProject("缺文件项目").getId();
        stubExec((command, out) -> out.exitCode = 1);

        signedGet("/api/backoffice/projects/" + id + "/files/content?path=src/gone.js")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("文件不存在"));
    }

    @Test
    void given_file_over_1mib_when_signed_content_then_prj_022() throws Exception {
        Long id = newProject("超限项目").getId();
        stubExec((command, out) -> out.exitCode = 2);

        // 超 1MiB＝容器侧 cat 前拦截（exit 2），巨文件不进内存即拒
        signedGet("/api/backoffice/projects/" + id + "/files/content?path=big-bundle.js")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件太大，暂不支持在线查看"));
    }

    @Test
    void given_nul_in_content_when_signed_content_then_prj_023() throws Exception {
        Long id = newProject("二进制项目").getId();
        stubExec((command, out) -> out.stdout = "8\nab\0cd\n");

        // 正文含 NUL＝非文本可靠信号（无扩展名面），400 PRJ_023
        signedGet("/api/backoffice/projects/" + id + "/files/content?path=assets/logo.bin")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该文件不是文本文件，暂不支持在线查看"));
    }

    // ---------- 生命周期口径 ----------

    @Test
    void given_archived_project_when_signed_file_area_then_both_readable() throws Exception {
        Long id = archivedProject("已归档的项目").getId();
        stubExec((command, out) -> {
            if (command.startsWith("find ")) {
                out.stdout = "512\tdocs/PRD.md\n";
            } else {
                out.stdout = "512\n# 归档项目的 PRD\n";
            }
        });

        // 归档项目照读（工作区保留）——两端口同口径
        signedGet("/api/backoffice/projects/" + id + "/files")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].path").value("docs/PRD.md"));
        signedGet("/api/backoffice/projects/" + id + "/files/content?path=docs/PRD.md")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("# 归档项目的 PRD\n"));
    }

    @Test
    void given_unknown_or_deleted_project_when_signed_file_area_then_prj_001() throws Exception {
        Long id = newProject("将删除的项目").getId();
        projectRepository.deleteById(id);

        // 已删项目真删无墓碑：两端口一律 404 PRJ_001
        for (String suffix : new String[] {"/files", "/files/content?path=src/app.js"}) {
            signedGet("/api/backoffice/projects/" + id + suffix)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("项目不存在"));
        }
        // 未知/非数值标识同口径（Tsid 严格式收口）
        signedGet("/api/backoffice/projects/999999999/files")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        signedGet("/api/backoffice/projects/not-a-tsid/files/content?path=src/app.js")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        // 项目不存在在 exec 之前即拒：docker 边界零触达
        verify(workspaceLifecycleAppService, never()).exec(anyString(), any());
    }

    @Test
    void given_dead_container_when_signed_file_area_then_wsp_002() throws Exception {
        Long id = newProject("容器故障的项目").getId();
        stubExec((command, out) -> out.exitCode = 125);

        // docker exec 自身失败：环境故障如实上抛（WSP_002），不伪装 200/404——两端点同口径
        signedGet("/api/backoffice/projects/" + id + "/files")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("环境后端操作失败"));
        signedGet("/api/backoffice/projects/" + id + "/files/content?path=src/app.js")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("环境后端操作失败"));
    }

    // ---------- 签名负例 ----------

    @Test
    void given_no_signature_headers_when_get_files_then_401_signature_required()
            throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(get("/api/backoffice/projects/1/files"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // -------- 夹具 --------

    /** 签名 GET（无体）：pathWithQuery 原样参与签名计算（与验签 Filter 同构）。 */
    private ResultActions signedGet(String pathWithQuery) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null));
    }

    /** 建项目（真库写入，workspaceId 占位自增、归属账号可空、零订单）。 */
    private Project newProject(String name) {
        return projectRepository.save(
                Project.create(name, ProjectType.WEBSITE, workspaceSeq++, null));
    }

    /** 已归档项目（真聚合 archive() 路径落 archived_at）。 */
    private Project archivedProject(String name) {
        Project project = newProject(name);
        Project loaded = projectRepository.findById(project.getId()).orElseThrow();
        loaded.archive();
        return projectRepository.save(loaded);
    }

    /** exec 通道脚本化：按命令内容写结果（缺省 exit 0 空输出）。 */
    private void stubExec(ExecScript script) {
        doAnswer(invocation -> {
            WorkspaceExecCommand command = invocation.getArgument(1);
            ExecOut out = new ExecOut();
            script.apply(command.command(), out);
            return new ExecResultResponse(out.stdout, "", out.exitCode);
        }).when(workspaceLifecycleAppService).exec(anyString(), any());
    }

    private interface ExecScript {
        void apply(String command, ExecOut out);
    }

    private static final class ExecOut {
        String stdout = "";
        int exitCode = 0;
    }
}
