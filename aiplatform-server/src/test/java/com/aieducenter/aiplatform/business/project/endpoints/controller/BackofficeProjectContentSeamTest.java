package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

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
import com.aieducenter.aiplatform.business.project.domain.aggregate.ConversationEntry;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ConversationEntryRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台项目读面深读端点（#162，/api/backoffice/projects/{id}/conversation|prd|
 * versions[/{ref}]）在 {@code #152} seam 上全绿：MockMvc 穿完整过滤链（签名验签 →
 * 会话豁免 → @RequireSignature 强制闸）→ 真应用服务 → aiplatform_test 真库。三组
 * 端点口径照用户面、同源委托既有应用服务——对话史（全量、写入序＝对话序）、PRD
 * （工作区直读、未产出 404 PRJ_015）、版本列表＋详情（正本＝容器内 git log，详情
 * 锚定收尾卡＋rollbackFrom）。
 *
 * <p>docker 依赖收口：PRD/版本的正本在工作区容器内（docker exec 直读），按
 * {@code ProjectVersionAppServiceTest} 形制 {@code @MockitoBean} 收口在
 * WorkspaceLifecycleAppService（外部设施边界），对话史纯库路径零 mock。#162 验收面
 * 在本类钉死：六类条目同序全量、归档项目三面照读、已删/未知项目 404 不触工作区、
 * PRD 已产出全文返回／未产出 PRJ_015／环境故障 WSP_002、版本列表新→旧（回滚版本
 * runId 空 rollbackFrom 锚定）、版本详情收尾卡载荷完整、非 hash ref 不触工作区、
 * 签名负例 401。</p>
 */
@BackofficeSeamTest
class BackofficeProjectContentSeamTest {

    private static final String HASH_1 = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
    private static final String HASH_2 = "c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2";
    private static final String HASH_3 = "e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3";
    private static final String FS = "\u001f";
    private static final String RS = "\u001e";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ConversationEntryRepository entries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** docker 依赖收口（PRD/版本正本＝容器内直读，同 ProjectVersionAppServiceTest 形制）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    /** 工作区软引用无 FK：夹具自增占位（一项目一 dev 环境的隔离只求不撞号）。 */
    private long workspaceSeq = 920200L;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 对话史（与用户面同源同序） ----------

    @Test
    void given_mixed_entries_when_signed_conversation_then_all_kinds_in_write_order()
            throws Exception {
        Long id = newProject("对话史项目").getId();
        entries.save(ConversationEntry.userUtterance(id, "run-1", "做一个官网"));
        entries.save(ConversationEntry.agentReply(id, "run-1", "好的，我先梳理需求"));
        entries.save(ConversationEntry.question(id, "run-1",
                Map.of("engineRef", "reply-1", "data", Map.of("toolCalls", List.of()))));
        entries.save(ConversationEntry.answer(id, "run-1", "选 A：三页官网"));
        entries.save(ConversationEntry.closing(id, "run-2", Map.of(
                "summary", "首次生成了系统", "prdChanged", false,
                "systemChanged", true, "files", List.of(), "durationMs", 183420L)));
        entries.save(ConversationEntry.guide(id, "run-3", "确认下单请点「立即下单」"));

        signedGet("/api/backoffice/projects/" + id + "/conversation")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(6))
                // 写入序＝对话序（id 升序），六类全量呈现——与用户面同源同序
                .andExpect(jsonPath("$.data[*].kind", contains(1, 2, 3, 4, 5, 6)))
                .andExpect(jsonPath("$.data[0].text").value("做一个官网"))
                // 问答卡＝question-raised 载荷原样（answered=false 即挂起待答）
                .andExpect(jsonPath("$.data[2].question.engineRef").value("reply-1"))
                .andExpect(jsonPath("$.data[2].answered").value(false))
                // 收尾卡＝run-finish 扩载同载荷（版本详情锚定的权威事实）
                .andExpect(jsonPath("$.data[4].closing.summary").value("首次生成了系统"))
                .andExpect(jsonPath("$.data[4].closing.systemChanged").value(true))
                .andExpect(jsonPath("$.data[4].closing.durationMs").value(183420))
                .andExpect(jsonPath("$.data[5].text").value("确认下单请点「立即下单」"));
    }

    @Test
    void given_archived_project_when_signed_content_reads_then_all_three_readable()
            throws Exception {
        Long id = archivedProject("已归档的项目").getId();
        entries.save(ConversationEntry.userUtterance(id, "run-1", "归档前的最后对话"));
        stubExec((command, out) -> {
            if (command.contains("PRD.md")) {
                out.stdout = "1756100000\n# 归档项目的 PRD\n";
            } else {
                out.stdout = logLine(HASH_1, 1700000100L, "首次生成了系统", "111");
            }
        });

        // 归档项目全状态照读（工作区保留）——三组端点同口径
        signedGet("/api/backoffice/projects/" + id + "/conversation")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].text").value("归档前的最后对话"));
        signedGet("/api/backoffice/projects/" + id + "/prd")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("# 归档项目的 PRD\n"));
        signedGet("/api/backoffice/projects/" + id + "/versions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].commitHash").value(HASH_1));
    }

    @Test
    void given_unknown_or_deleted_project_when_signed_content_reads_then_prj_001()
            throws Exception {
        Long id = newProject("将删除的项目").getId();
        entries.save(ConversationEntry.userUtterance(id, "run-1", "随项目删除消失"));
        projectRepository.deleteById(id);

        // 已删项目真删无墓碑：四端点一律 404 PRJ_001
        for (String suffix : new String[] {"/conversation", "/prd", "/versions",
                "/versions/" + HASH_1}) {
            signedGet("/api/backoffice/projects/" + id + suffix)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("项目不存在"));
        }
        // 未知/非数值标识同口径（Tsid 严格式收口）
        signedGet("/api/backoffice/projects/999999999/conversation")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        signedGet("/api/backoffice/projects/not-a-tsid/prd")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        // 项目不存在在 exec 之前即拒：docker 边界零触达
        verify(workspaceLifecycleAppService, never()).exec(anyString(), any());
    }

    // ---------- PRD（未产出 404 照搬用户面） ----------

    @Test
    void given_prd_in_workspace_when_signed_prd_then_full_content_returned() throws Exception {
        Long id = newProject("PRD 项目").getId();
        stubExec((command, out) ->
                out.stdout = "1756100000\n# 官网 PRD\n\n目标：三页官网。\n");

        signedGet("/api/backoffice/projects/" + id + "/prd")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value(id.toString()))
                .andExpect(jsonPath("$.data.content").value("# 官网 PRD\n\n目标：三页官网。\n"))
                // Instant → ISO-8601；updatedAt = 工作区文件 mtime（秒精度）
                .andExpect(jsonPath("$.data.updatedAt")
                        .value(Instant.ofEpochSecond(1756100000).toString()));
    }

    @Test
    void given_no_prd_file_when_signed_prd_then_prj_015() throws Exception {
        Long id = newProject("未产出 PRD 的项目").getId();
        stubExec((command, out) -> out.exitCode = 1);

        // 未产出（工作区无 docs/PRD.md）＝404 PRJ_015，区别于项目不存在的 PRJ_001
        signedGet("/api/backoffice/projects/" + id + "/prd")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("PRD 尚未产出"));
    }

    @Test
    void given_dead_container_when_signed_prd_then_wsp_002() throws Exception {
        Long id = newProject("容器故障的项目").getId();
        stubExec((command, out) -> out.exitCode = 125);

        // docker exec 自身失败 ≠ 未产出：环境故障如实上抛（WSP_002），不伪装 200/404
        signedGet("/api/backoffice/projects/" + id + "/prd")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("环境后端操作失败"));
    }

    // ---------- 版本列表（新→旧） ----------

    @Test
    void given_run_and_rollback_versions_when_signed_versions_then_newest_first()
            throws Exception {
        Long id = newProject("版本项目").getId();
        stubExec((command, out) -> out.stdout =
                rollbackLogLine(HASH_3, 1700000300L, "回滚到「首次生成了系统」", HASH_1)
                        + logLine(HASH_2, 1700000200L, "更新了系统", "222")
                        + logLine(HASH_1, 1700000100L, "首次生成了系统", "111"));

        signedGet("/api/backoffice/projects/" + id + "/versions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(3))
                // git log 原生序＝新→旧（回滚成版最近、首次成版最旧）
                .andExpect(jsonPath("$.data[*].commitHash", contains(HASH_3, HASH_2, HASH_1)))
                // 回滚版本：runId 空、rollbackFrom 锚定源版本
                .andExpect(jsonPath("$.data[0].runId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].rollbackFrom").value(HASH_1))
                // run 版本：runId 锚定收尾卡、rollbackFrom 空
                .andExpect(jsonPath("$.data[1].runId").value("222"))
                .andExpect(jsonPath("$.data[1].rollbackFrom").value(nullValue()))
                .andExpect(jsonPath("$.data[2].subject").value("首次生成了系统"))
                .andExpect(jsonPath("$.data[2].committedAt").value(localAt(1700000100L)));
    }

    @Test
    void given_no_versions_when_signed_versions_then_empty_list_not_error() throws Exception {
        Long id = newProject("尚无收口的项目").getId();
        stubExec((command, out) -> out.exitCode = 3);

        // 零版本（无 HEAD，尚无收口成版）＝空列表 200，非错误
        signedGet("/api/backoffice/projects/" + id + "/versions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- 版本详情（收尾卡＋rollbackFrom） ----------

    @Test
    void given_version_with_closing_card_when_signed_detail_then_full_payload()
            throws Exception {
        Long id = newProject("收尾卡版本项目").getId();
        stubExec((command, out) -> out.stdout = logLine(HASH_1, 1700000100L, "首次生成了系统", "111"));
        entries.save(ConversationEntry.closing(id, "111", Map.of(
                "summary", "首次生成了系统", "systemChanged", true,
                "files", List.of("src/app.js"), "durationMs", 183420L)));

        signedGet("/api/backoffice/projects/" + id + "/versions/" + HASH_1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.commitHash").value(HASH_1))
                .andExpect(jsonPath("$.data.subject").value("首次生成了系统"))
                .andExpect(jsonPath("$.data.runId").value("111"))
                .andExpect(jsonPath("$.data.rollbackFrom").value(nullValue()))
                .andExpect(jsonPath("$.data.committedAt").value(localAt(1700000100L)))
                // 收尾卡载荷完整（Run-Id 联接对话史 closing 条目——#88 同载荷复用）
                .andExpect(jsonPath("$.data.closing.summary").value("首次生成了系统"))
                .andExpect(jsonPath("$.data.closing.systemChanged").value(true))
                .andExpect(jsonPath("$.data.closing.files[0]").value("src/app.js"))
                .andExpect(jsonPath("$.data.closing.durationMs").value(183420));
    }

    @Test
    void given_rollback_version_when_signed_detail_then_run_id_empty_and_rollback_from()
            throws Exception {
        Long id = newProject("回滚版本项目").getId();
        stubExec((command, out) -> out.stdout =
                rollbackLogLine(HASH_2, 1700000200L, "回滚到「首次生成了系统」", HASH_1));

        signedGet("/api/backoffice/projects/" + id + "/versions/" + HASH_2)
                .andExpect(status().isOk())
                // 回滚版本无 run / 收尾卡：runId 与 closing 均空、rollbackFrom 锚定源版本
                .andExpect(jsonPath("$.data.runId").value(nullValue()))
                .andExpect(jsonPath("$.data.rollbackFrom").value(HASH_1))
                .andExpect(jsonPath("$.data.closing").value(nullValue()));
    }

    @Test
    void given_unknown_or_malformed_ref_when_signed_detail_then_prj_028() throws Exception {
        Long id = newProject("版本守卫项目").getId();
        stubExec((command, out) -> out.exitCode = 3);

        // hex 形态但查无此版（exit 3）＝404 PRJ_028
        signedGet("/api/backoffice/projects/" + id + "/versions/" + "beef".repeat(10))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("版本不存在"));

        // 非 hash 形态＝同 404 且不触工作区（shell 注入防线——用户可控入参不进 shell）
        signedGet("/api/backoffice/projects/" + id + "/versions/zzzz-not-a-hash")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("版本不存在"));
        // 全程仅未知 hex 触达一次 exec；非 hash 形态在命令构造前即拒
        verify(workspaceLifecycleAppService, times(1)).exec(anyString(), any());
    }

    // ---------- 签名负例 ----------

    @Test
    void given_no_signature_headers_when_get_conversation_then_401_signature_required()
            throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(get("/api/backoffice/projects/1/conversation"))
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

    /** 建项目（真库写入，workspaceId 占位自增、归属账号可空）。 */
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

    /** git log 单行（字段 \u001f 分隔、记录 \u001e 收尾——与 WorkspaceVersions 格式同源）。 */
    private static String logLine(String hash, long at, String subject, String runId) {
        return hash + FS + at + FS + subject + FS + "Run-Id: " + runId + "\n" + RS;
    }

    /** git log 单行的回滚变体（Rollback-From trailer，runId 空）。 */
    private static String rollbackLogLine(String hash, long at, String subject,
            String rollbackFrom) {
        return hash + FS + at + FS + subject + FS + "Rollback-From: " + rollbackFrom + "\n" + RS;
    }

    /** git epoch 秒 → ISO-8601 期望值（JVM 时区口径、恒含秒——Jackson 序列化同构，
     *  {@code LocalDateTime.toString()} 整分时省 ":00" 不可用）。 */
    private static String localAt(long epochSeconds) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault())
                .format(ISO_LOCAL_DATE_TIME);
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
