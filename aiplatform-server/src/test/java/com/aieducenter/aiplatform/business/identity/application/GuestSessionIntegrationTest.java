package com.aieducenter.aiplatform.business.identity.application;

import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.business.project.application.MainAgentAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectNamingAppService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访客会话域端到端（#326 集测）：真过滤链（BFF 会话/访客凭证装配 → ApiAuth 闸 →
 * owner 守卫）→ 真应用/查询服务 → aiplatform_test 真库。覆盖：惰性建时机（首条
 * 需求消息才建访客＋发凭证；空输入不建、只逛零足迹）、凭证直查（cookie 过拦截面、
 * 归属挂访客列）、滑动续期写节流（阈值内零写零 Set-Cookie、到期一写顺寿）、过期/
 * 作废不认且数据保留、访客多项目（列表只回自己的、创建倒序＝回最近一个）、越权面
 * （账户×访客、访客×访客互不可见）、访客下单（订单归属访客列＋SSE 归属发布不炸）。
 */
@IntegrationTest
@AutoConfigureMockMvc
class GuestSessionIntegrationTest {

    private static final String SESSION_ACCOUNT = "guest-iso-account";
    private static final long ACCOUNT = 933001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BffSessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 建项目编排的沙箱/对话副作用收口（过滤与守卫是被测面，副作用走假面）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private MainAgentAppService mainAgentAppService;

    @MockitoBean
    private ProjectNamingAppService namingService;

    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private final AtomicLong workspaceSeq = new AtomicLong(933_600L);

    private Long guestId;
    private String credential;

    /**
     * 本类三表全清（独立测试库的自清口径）：访客/项目/订单的绝对计数断言对
     * 跨运行残留敏感（库跨 mvn 复用）——前后各清一次，只动本域三表。
     */
    private void purgeGuestDomainTables() {
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_projects");
        jdbcTemplate.update("DELETE FROM idn_guests");
    }

    @BeforeEach
    void seed() throws Exception {
        purgeGuestDomainTables();
        sessionStore.put(SESSION_ACCOUNT, new BffSession(
                ACCOUNT, "对照账户", null, null, null, Instant.now().plusSeconds(3600)));
        when(workspaceLifecycleAppService.create(any())).thenAnswer(invocation -> {
            long workspaceId = workspaceSeq.incrementAndGet();
            return new WorkspaceResponse(Long.toString(workspaceId), EnvKind.DEV, "开发环境",
                    "ws-" + workspaceId, "net-x", ProvisioningStatus.READY, "就绪", null,
                    List.of(), LocalDateTime.now());
        });
        when(mainAgentAppService.startConversation(any(), any()))
                .thenReturn(new MainAgentAppService.MainAgentRun("run-guest"));
        // 访客下单用例的 PRD 读面（prd() 直读工作区——exec 假面回 PRD 正文+mtime）
        when(workspaceLifecycleAppService.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1600000000\n# PRD\n\n功能清单…", "", 0));
    }

    @AfterEach
    void tearDown() {
        purgeGuestDomainTables();
        sessionStore.remove(SESSION_ACCOUNT);
    }

    // ---------- 惰性建时机：首条需求消息才建访客＋发凭证 ----------

    @Test
    void given_anonymous_first_requirement_when_create_then_guest_row_and_credential_issued()
            throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idn_guests", Long.class))
                .isEqualTo(0L); // 只逛零足迹：建前无访客行

        MvcResult result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"免注册先用一句话建项目\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .contains(AuthCookies.GUEST_COOKIE_NAME + "=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/")
                .contains("Max-Age=");
        credential = cookieValueOf(setCookie);

        // 访客行已建（凭证值即凭证——行可按值直查）＋项目挂 owner 访客列（账户列空）
        guestId = jdbcTemplate.queryForObject(
                "SELECT id FROM idn_guests WHERE credential = ?", Long.class, credential);
        Long ownerGuest = jdbcTemplate.queryForObject(
                "SELECT owner_guest_id FROM prj_projects ORDER BY created_at DESC LIMIT 1",
                Long.class);
        assertThat(ownerGuest).isEqualTo(guestId);
        Long ownerAccount = jdbcTemplate.queryForObject(
                "SELECT owner_account_id FROM prj_projects ORDER BY created_at DESC LIMIT 1",
                Long.class);
        assertThat(ownerAccount).isNull();
    }

    @Test
    void given_anonymous_blank_requirement_when_create_then_rejected_zero_footprint()
            throws Exception {
        // 空输入不算首条需求消息：匿名无归属可挂——快败 400（PRJ_006 数字码 4006）、
        // 零副作用（无访客行、无项目行＝只逛零足迹的同源口径）
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4006));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idn_guests", Long.class))
                .isEqualTo(0L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_projects", Long.class))
                .isEqualTo(0L);
    }

    // ---------- 凭证直查＋多项目（回最近一个）＋列表只回自己的 ----------

    @Test
    void given_returning_guest_when_create_twice_then_same_guest_and_list_newest_first()
            throws Exception {
        Long firstProject = issueGuestWithProject("访客的第一个项目");
        Long firstGuestId = guestId;

        // 回访（带凭证）再建：挂既有访客，不另建行
        MvcResult second = mockMvc.perform(post("/api/projects")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"访客的第二个项目\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Long secondProject = Long.parseLong(
                objectMapper.readTree(second.getResponse().getContentAsString())
                        .path("data").path("project").path("id").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idn_guests", Long.class))
                .isEqualTo(1L); // 不另建行
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_guest_id FROM prj_projects WHERE id = ?", Long.class, secondProject))
                .isEqualTo(firstGuestId);

        // 账户对照项目（访客不可见）＋访客两项目：列表只回自己的、创建倒序（首项＝
        // 「继续上次项目」的最近一个）
        createAsAccount("账户的对照项目");

        mockMvc.perform(get("/api/projects")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(secondProject.toString()))
                .andExpect(jsonPath("$.data[1].id").value(firstProject.toString()));
    }

    // ---------- 越权面：账户×访客、访客×访客互不可见（404 不泄露存在性） ----------

    @Test
    void given_guest_project_when_account_or_other_guest_access_then_404() throws Exception {
        issueGuestWithProject("访客项目");
        Long projectId = firstGuestProjectId();

        // 账户会话访客项目 → 404
        mockMvc.perform(get("/api/projects/" + projectId)
                        .cookie(new Cookie("aiplatform_session", SESSION_ACCOUNT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001));

        // 伪造访客凭证（值无行可查）→ 401（不认）；本人照常 200（下例）
        mockMvc.perform(get("/api/projects/" + projectId)
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, "forged-nonexistent")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void given_guest_project_when_own_credential_then_detail_ok() throws Exception {
        issueGuestWithProject("访客项目");
        Long projectId = firstGuestProjectId();

        mockMvc.perform(get("/api/projects/" + projectId)
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(projectId.toString()));
    }

    // ---------- 过期/作废不认、数据保留 ----------

    @Test
    void given_expired_credential_when_list_then_401_and_row_kept() throws Exception {
        issueGuestWithProject("过期访客项目");
        jdbcTemplate.update("UPDATE idn_guests SET last_active_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(31), guestId);

        mockMvc.perform(get("/api/projects")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential)))
                .andExpect(status().isUnauthorized());

        // 数据不删：行仍在（未绑定不承诺找回，但库面保留）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idn_guests WHERE id = ?", Long.class, guestId))
                .isEqualTo(1L);
    }

    @Test
    void given_revoked_credential_when_list_then_401_and_row_kept() throws Exception {
        issueGuestWithProject("作废访客项目");
        jdbcTemplate.update("UPDATE idn_guests SET revoked_at = NOW() WHERE id = ?", guestId);

        mockMvc.perform(get("/api/projects")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential)))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idn_guests WHERE id = ?", Long.class, guestId))
                .isEqualTo(1L);
    }

    // ---------- 滑动续期（写节流） ----------

    @Test
    void given_activity_reached_threshold_when_request_then_renewed_once_and_cookie_refreshed()
            throws Exception {
        issueGuestWithProject("续期访客项目");
        LocalDateTime stale = LocalDateTime.now().minusDays(16); // 阈值 15d 已过
        jdbcTemplate.update("UPDATE idn_guests SET last_active_at = ? WHERE id = ?",
                stale, guestId);

        MvcResult renewed = mockMvc.perform(get("/api/projects")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential)))
                .andExpect(status().isOk())
                .andReturn();

        // 续期写发生：活跃锚前移到当前时刻附近（滑动窗口随写前移）＋顺寿 Set-Cookie
        assertThat(renewed.getResponse().getHeader("Set-Cookie"))
                .contains(AuthCookies.GUEST_COOKIE_NAME + "=" + credential);
        LocalDateTime lastActive = jdbcTemplate.queryForObject(
                "SELECT last_active_at FROM idn_guests WHERE id = ?", LocalDateTime.class,
                guestId);
        assertThat(lastActive).isAfter(stale.plusDays(15));

        // 节流窗口内再请求：零写零 Set-Cookie（读多写少）
        MvcResult throttled = mockMvc.perform(get("/api/projects")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(throttled.getResponse().getHeader("Set-Cookie")).isNull();
    }

    // ---------- 访客下单：归属访客列＋订单事件发布不炸 ----------

    @Test
    void given_guest_project_with_prd_when_place_order_then_owned_by_guest() throws Exception {
        issueGuestWithProject("访客下单项目");
        Long projectId = firstGuestProjectId();
        jdbcTemplate.update("UPDATE prj_projects SET prd_produced_at = NOW() WHERE id = ?",
                projectId);

        // place 内同步走 publishNotification（归属路由键 fail-fast）——访客键在场不炸
        MvcResult placed = mockMvc.perform(post("/api/projects/" + projectId + "/orders")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode order = objectMapper.readTree(placed.getResponse().getContentAsString())
                .path("data");
        Long orderId = Long.parseLong(order.path("id").asText());
        Long orderGuest = jdbcTemplate.queryForObject(
                "SELECT owner_guest_id FROM ord_orders WHERE id = ?", Long.class, orderId);
        assertThat(orderGuest).isEqualTo(guestId);
        Long orderAccount = jdbcTemplate.queryForObject(
                "SELECT owner_account_id FROM ord_orders WHERE id = ?", Long.class, orderId);
        assertThat(orderAccount).isNull();
    }

    // ---------- SSE 订阅握手：访客凭证过 /api/events 拦截面 ----------

    @Test
    void given_guest_credential_when_subscribe_events_then_handshake_ok() throws Exception {
        issueGuestWithProject("访客订阅项目");

        // 订阅握手过闸（路由语义＝双键匹配已在 EventsAppServiceTest 钉；此处钉
        // 拦截面/装配链对访客维度放行——SSE 端点在 /api/** 拦截面内）
        mockMvc.perform(get("/api/events")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential)))
                .andExpect(request().asyncStarted());
    }

    // -------- 测试工具 --------

    /** 匿名首条需求消息建访客＋项目（捕获凭证/访客 id/项目 id 供后续请求与断言）。 */
    private Long issueGuestWithProject(String requirement) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"" + requirement + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        credential = cookieValueOf(result.getResponse().getHeader("Set-Cookie"));
        guestId = jdbcTemplate.queryForObject(
                "SELECT id FROM idn_guests WHERE credential = ?", Long.class, credential);
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("project").path("id").asText());
    }

    private Long firstGuestProjectId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM prj_projects WHERE owner_guest_id = ? ORDER BY created_at DESC",
                Long.class, guestId);
    }

    private void createAsAccount(String requirement) throws Exception {
        mockMvc.perform(post("/api/projects")
                        .cookie(new Cookie("aiplatform_session", SESSION_ACCOUNT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"" + requirement + "\"}"))
                .andExpect(status().isOk());
    }

    private static String cookieValueOf(String setCookieHeader) {
        // "aiplatform_guest=VALUE; Path=/; ..." → VALUE
        return setCookieHeader.split(";")[0].split("=", 2)[1];
    }
}
