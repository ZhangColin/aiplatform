package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cartisan.web.request.Pagination;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.business.identity.infrastructure.session.BffSession;
import com.semgravio.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.semgravio.aiplatform.business.project.application.BackofficeProjectAppService;
import com.semgravio.aiplatform.business.project.application.MainAgentAppService;
import com.semgravio.aiplatform.business.project.application.ProjectNamingAppService;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目 owner 隔离端到端（#325 集测：两账户各建项目，列表/详情/操作互不可见；
 * 后台机机面照常全量）：真过滤链（BFF 会话绑定 → ApiAuth 闸 → owner 守卫 →
 * 触碰）→ 真应用/查询服务 → aiplatform_test 真库。建项目走真端点（归属随会话
 * 落列——owner 双列的账户侧写入面）；不泄露存在性＝他人项目与不存在项目的 404
 * 信封逐字节一致；越权操作在守卫层即拒（零副作用）。库层另钉 owner 双列互斥
 * 约束（迁移 V30）与访客列的读写映射（访客域表体与写入方属 #326）。
 */
@IntegrationTest
@AutoConfigureMockMvc
class ProjectOwnerIsolationTest {

    private static final String SESSION_A = "owner-iso-session-a";
    private static final String SESSION_B = "owner-iso-session-b";
    private static final long ACCOUNT_A = 932501L;
    private static final long ACCOUNT_B = 932502L;

    /** 互斥约束直插行用的固定 id（TSID 正空间外，不与真建项目撞号）。 */
    private static final long SQL_SEEDED_PROJECT_ID = 9_325_900L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private BackofficeProjectAppService backofficeProjectAppService;

    @Autowired
    private BffSessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 建项目编排的沙箱/对话副作用收口（守卫与过滤是被测面，副作用走假面）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private MainAgentAppService mainAgentAppService;

    @MockitoBean
    private ProjectNamingAppService namingService;

    /** 触碰拦截器的自愈异步面（docker 依赖收口，形制同 #283/#287 serve 测试）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private final AtomicLong workspaceSeq = new AtomicLong(932_600L);

    private Long projectA;
    private Long projectB;

    @BeforeEach
    void seed() throws Exception {
        sessionStore.put(SESSION_A, new BffSession(
                ACCOUNT_A, "账户甲", null, null, null));
        sessionStore.put(SESSION_B, new BffSession(
                ACCOUNT_B, "账户乙", null, null, null));
        when(workspaceLifecycleAppService.create(any())).thenAnswer(invocation -> {
            long workspaceId = workspaceSeq.incrementAndGet();
            return new WorkspaceResponse(Long.toString(workspaceId), EnvKind.DEV, "开发环境",
                    "ws-" + workspaceId, "net-x", ProvisioningStatus.READY, "就绪", null,
                    List.of(), LocalDateTime.now());
        });
        when(mainAgentAppService.startConversation(any(), any()))
                .thenReturn(new MainAgentAppService.MainAgentRun("run-iso"));

        // 两账户各建项目（真端点：归属随会话落列）
        projectA = createProjectAs(SESSION_A, "甲的官网");
        projectB = createProjectAs(SESSION_B, "乙的商城");
    }

    @AfterEach
    void tearDown() {
        sessionStore.remove(SESSION_A);
        sessionStore.remove(SESSION_B);
        jdbcTemplate.update("DELETE FROM prj_projects WHERE id IN (?, ?)", projectA, projectB);
        jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", SQL_SEEDED_PROJECT_ID);
    }

    private Long createProjectAs(String sessionId, String requirement) throws Exception {
        String body = mockMvc.perform(post("/api/projects")
                        .cookie(new Cookie("aiplatform_session", sessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"" + requirement + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body);
        return Long.parseLong(created.path("data").path("project").path("id").asText());
    }

    private ResultActions as(String sessionId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.cookie(new Cookie("aiplatform_session", sessionId)));
    }

    // ---------- 列表互不可见 ----------

    @Test
    void given_two_accounts_projects_when_list_then_each_sees_only_own() throws Exception {
        as(SESSION_A, get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(projectA.toString()));

        as(SESSION_B, get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(projectB.toString()));
    }

    // ---------- 详情/操作互不可见（404 不泄露存在性） ----------

    @Test
    void given_foreign_project_when_detail_then_identical_envelope_to_missing() throws Exception {
        // 不泄露存在性：他人项目与不存在项目的 404 信封同形（code/message 逐字段一致；
        // requestId 每请求唯一、不属可比面）
        as(SESSION_B, get("/api/projects/" + projectA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value("项目不存在"))
                .andExpect(jsonPath("$.data").value((Object) null));
        as(SESSION_B, get("/api/projects/999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value("项目不存在"))
                .andExpect(jsonPath("$.data").value((Object) null));

        // 本人详情照常
        as(SESSION_A, get("/api/projects/" + projectA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(projectA.toString()));
    }

    @Test
    void given_foreign_project_when_rename_then_404_and_no_side_effect() throws Exception {
        as(SESSION_B, post("/api/projects/" + projectA + "/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"乙的越权改名\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001));

        assertThat(projectRepository.findById(projectA).orElseThrow().getName())
                .isEqualTo(Project.PLACEHOLDER_NAME); // 守卫先于控制器：零副作用（取名 mock 为 no-op，占位名未动）
    }

    // ---------- 后台机机面照常全量（行为红线） ----------

    @Test
    void given_two_accounts_projects_when_backoffice_list_then_both_visible() {
        var page = backofficeProjectAppService.projects(
                null, null, null, null, null, new Pagination(1, 20, null));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items())
                .extracting(item -> item.id())
                .containsExactlyInAnyOrder(projectA.toString(), projectB.toString());
    }

    // ---------- 库层：owner 双列互斥约束（迁移 V30） ----------

    @Test
    void given_both_owner_columns_set_when_insert_then_rejected_by_check() {
        // 互斥约束：账户与访客归属至多一方落值（违规插入库层拒绝）
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO prj_projects (id, name, type, endpoint_type, workspace_id, "
                        + "owner_account_id, owner_guest_id, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 2, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                SQL_SEEDED_PROJECT_ID, "双主违规行", 932_699L, ACCOUNT_A, 932_999L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void given_guest_only_owner_row_when_read_then_column_mapped() {
        // 访客列读写映射（皆可空：仅访客归属合法——访客域表体与写入方属 #326，
        // 本票直插行钉列在聚合上的映射不丢）
        jdbcTemplate.update(
                "INSERT INTO prj_projects (id, name, type, endpoint_type, workspace_id, "
                        + "owner_guest_id, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 2, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                SQL_SEEDED_PROJECT_ID, "访客项目", 932_698L, 932_888L);

        Project project = projectRepository.findById(SQL_SEEDED_PROJECT_ID).orElseThrow();

        assertThat(project.getOwnerGuestId()).isEqualTo(932_888L);
        assertThat(project.getOwnerAccountId()).isNull();
    }

    // ---------- owner 过滤与状态过滤共存 ----------

    @Test
    void given_own_project_archived_when_list_active_then_empty() throws Exception {
        Project archived = projectRepository.findById(projectA).orElseThrow();
        archived.archive();
        projectRepository.save(archived);

        as(SESSION_A, get("/api/projects").param("status", "1")) // ACTIVE
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0)); // 唯一项目已归档
    }
}
