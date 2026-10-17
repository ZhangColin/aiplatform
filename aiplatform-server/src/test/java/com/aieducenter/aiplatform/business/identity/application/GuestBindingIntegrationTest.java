package com.aieducenter.aiplatform.business.identity.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.aieducenter.aiplatform.business.identity.application.GuestBindingAppService.BindingOutcome;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.domain.model.IdTokenClaims;
import com.aieducenter.aiplatform.business.identity.domain.model.OauthTransaction;
import com.aieducenter.aiplatform.business.identity.domain.port.VerificationCodeSender;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountBindingRepository;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.OidcClient;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.TokenResponse;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.business.project.application.MainAgentAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectNamingAppService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 绑定平移与签约门端到端（#328，spec #320 段②）：真过滤链（会话/访客维度装配）→
 * 真应用服务 → 真库。覆盖：登录即自动平移（M01 验证码路＋OIDC 路两路）——访客名下
 * 未绑定项目过户给账户、访客行作废、清访客凭证 cookie，单一事务；幂等（重复调用
 * no-op，归属不再变）；过期访客拒绝绑定（不平移）；账户会话优先于访客凭证（登录
 * 态新输入挂账户）；签约门拦截（访客下单被拦 ORD_017/5017）与放行（绑定后账户会话
 * 过闸下单成功）。
 *
 * <p>依赖收口（真链路无沙箱副作用）：建项目编排的工作区/对话/取名走假面；发码
 * 服务商走假面（收件即验码观察点）；OIDC 远程换 token/验签走假面。</p>
 */
@IntegrationTest(properties = {
        "sso.issuer=http://identity.localhost:10001",
        "sso.client-id=test-client",
        "sso.client-secret=test-secret",
        "sso.redirect-uri=http://localhost:3333/auth/callback",
        "sso.app-base-url=http://localhost:3333",
        "sso.cookie-secure=false",
        "app.verification-code.code-ttl-seconds=300",
        "app.verification-code.send-cooldown-seconds=3600"})
@AutoConfigureMockMvc
class GuestBindingIntegrationTest {

    private static final String APP_BASE = "http://localhost:3333";
    private static final String SESSION_ACCOUNT = "binding-iso-account";
    private static final long ACCOUNT = 934001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BffSessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GuestBindingAppService bindingAppService;

    @Autowired
    private AuthAppService authAppService;

    @Autowired
    private AccountAppService accountAppService;

    @Autowired
    private AccountBindingRepository bindingRepository;

    @Autowired
    private AccountRepository accountRepository;

    /** 建项目编排的沙箱/对话副作用收口（归属与平移是被测面，副作用走假面）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private MainAgentAppService mainAgentAppService;

    @MockitoBean
    private ProjectNamingAppService namingService;

    @MockitoBean
    private EnvironmentBackend environmentBackend;

    /** M01 发码通道（收件即验码观察点；真服务商＝上线参数）。 */
    @MockitoBean
    private VerificationCodeSender sender;

    /** OIDC 远程调用（换 token / 验签）走假面——平移是被测面，远程面非本票范围。 */
    @MockitoBean
    private OidcClient oidcClient;

    private final AtomicLong workspaceSeq = new AtomicLong(934_600L);

    /** 每次运行唯一（OIDC sub＋收码邮箱），规避历史数据撞唯一索引/冷却窗。 */
    private final String sub = "sub-" + UUID.randomUUID();
    private final String email = "binding-" + UUID.randomUUID() + "@example.com";

    private Long guestId;
    private String credential;

    /**
     * 本类全清（独立测试库自清口径）：访客/项目/订单/账号域表——绝对计数断言对
     * 跨运行残留敏感。账号清理只动本类两根（收码邮箱＋OIDC sub），不搅他类台账。
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
                .thenReturn(new MainAgentAppService.MainAgentRun("run-binding"));
        when(workspaceLifecycleAppService.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1600000000\n# PRD\n\n功能清单…", "", 0));
    }

    @AfterEach
    void tearDown() {
        purgeGuestDomainTables();
        accountAppService.accountByIdentifier(sub).ifPresent(account -> {
            bindingRepository.deleteAll(bindingRepository.findByAccountIdIn(List.of(account.getId())));
            accountRepository.delete(account);
        });
        accountAppService.accountByIdentifier(email).ifPresent(account -> {
            bindingRepository.deleteAll(bindingRepository.findByAccountIdIn(List.of(account.getId())));
            accountRepository.delete(account);
        });
        sessionStore.remove(SESSION_ACCOUNT);
    }

    // ---------- 绑定平移核心：单一事务（过户＋作废＋清 cookie） ----------

    @Test
    void given_guest_with_two_projects_when_bind_on_login_then_transferred_and_guest_revoked()
            throws Exception {
        Long first = createAnonymousProject("访客项目一");
        Long second = createGuestProject("访客项目二"); // 回访带凭证 → 挂同一访客

        BindingOutcome outcome = bindingAppService.bindOnLogin(credential, ACCOUNT);

        // 单一事务结果：两项目过户给账户（owner 双列互斥切换）＋访客行作废＋清 cookie 指令
        assertThat(outcome.migratedProjects()).isEqualTo(2);
        assertThat(outcome.guestClearCookie()).isNotNull();
        assertThat(outcome.guestClearCookie().getMaxAge().isZero()).isTrue();
        for (Long projectId : List.of(first, second)) {
            assertThat(ownerAccountOf(projectId)).isEqualTo(ACCOUNT);
            assertThat(ownerGuestOf(projectId)).isNull();
        }
        assertThat(revokedAtOf(guestId)).isNotNull();
    }

    @Test
    void given_already_bound_when_bind_again_then_no_op_zero_migrate() throws Exception {
        Long projectId = createAnonymousProject("访客项目");
        bindingAppService.bindOnLogin(credential, ACCOUNT);

        // 幂等：已作废凭证再绑 → no-op（不重复平移、归属不变）
        BindingOutcome again = bindingAppService.bindOnLogin(credential, ACCOUNT);

        assertThat(again.migratedProjects()).isZero();
        assertThat(ownerAccountOf(projectId)).isEqualTo(ACCOUNT);
        assertThat(ownerGuestOf(projectId)).isNull();
    }

    @Test
    void given_concurrent_binds_then_only_one_claims_no_double_move() throws Exception {
        Long projectId = createAnonymousProject("并发绑定项目");
        Long otherAccount = 934002L;

        // 两登录并发同携一凭证（原子占位 CAS）：恰一方拿到平移权——迁移数之和＝项目
        // 数（不双移），归属落赢家、访客作废恰一次
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<BindingOutcome> toFirst = () -> {
            start.await();
            return bindingAppService.bindOnLogin(credential, ACCOUNT);
        };
        Callable<BindingOutcome> toSecond = () -> {
            start.await();
            return bindingAppService.bindOnLogin(credential, otherAccount);
        };
        Future<BindingOutcome> first = pool.submit(toFirst);
        Future<BindingOutcome> second = pool.submit(toSecond);
        start.countDown();
        BindingOutcome firstOutcome = first.get(10, TimeUnit.SECONDS);
        BindingOutcome secondOutcome = second.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(firstOutcome.migratedProjects() + secondOutcome.migratedProjects())
                .isEqualTo(1);
        assertThat(ownerAccountOf(projectId)).isIn(ACCOUNT, otherAccount);
        assertThat(ownerGuestOf(projectId)).isNull();
        assertThat(revokedAtOf(guestId)).isNotNull();
    }

    @Test
    void given_expired_guest_when_bind_then_rejected_and_not_transferred() throws Exception {
        Long projectId = createAnonymousProject("过期访客项目");
        jdbcTemplate.update("UPDATE idn_guests SET last_active_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(31), guestId); // 逾保留期（30d）

        BindingOutcome outcome = bindingAppService.bindOnLogin(credential, ACCOUNT);

        // 过期访客拒绝绑定：不平移、不作废——项目仍挂访客、访客行未作废
        assertThat(outcome.migratedProjects()).isZero();
        assertThat(ownerGuestOf(projectId)).isEqualTo(guestId);
        assertThat(ownerAccountOf(projectId)).isNull();
        assertThat(revokedAtOf(guestId)).isNull();
    }

    @Test
    void given_no_credential_when_bind_then_no_op_no_cookie() {
        // 无凭证（无访客维度）＝幂等 no-op：零平移、不发清 cookie 指令
        BindingOutcome outcome = bindingAppService.bindOnLogin(null, ACCOUNT);

        assertThat(outcome.migratedProjects()).isZero();
        assertThat(outcome.guestClearCookie()).isNull();
    }

    // ---------- 登录即自动平移（M01 验证码路，真过滤链 → 端点 → 应用） ----------

    @Test
    void given_guest_credential_when_code_login_then_projects_migrated_and_guest_cookie_cleared()
            throws Exception {
        Long projectId = createAnonymousProject("M01 平移项目");

        // 发码 → 从假发送器取回六位码（真链路的观察点）
        mockMvc.perform(post("/auth/code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        String code = capturedCode();

        // 验码（携访客凭证）：登录即自动平移 → 过户给新账户、作废访客行、清访客 cookie
        MvcResult verifyResult = mockMvc.perform(post("/auth/verify")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String setCookie = verifyResult.getResponse().getHeaders("Set-Cookie").toString();
        assertThat(setCookie).contains(AuthCookies.SESSION_COOKIE_NAME + "=");
        assertThat(setCookie)
                .contains(AuthCookies.GUEST_COOKIE_NAME + "=;")
                .contains("Max-Age=0");

        Long accountId = Long.parseLong(objectMapper.readTree(
                verifyResult.getResponse().getContentAsString()).path("data").path("accountId").asText());
        assertThat(ownerAccountOf(projectId)).isEqualTo(accountId);
        assertThat(ownerGuestOf(projectId)).isNull();
        assertThat(revokedAtOf(guestId)).isNotNull();
    }

    // ---------- 登录即自动平移（OIDC 回调路） ----------

    @Test
    void given_guest_credential_when_oidc_callback_then_projects_migrated_and_guest_cookie_cleared()
            throws Exception {
        Long projectId = createAnonymousProject("OIDC 平移项目");

        OauthTransaction txn = OauthTransaction.issue("/projects");
        when(oidcClient.exchangeCode("code-1")).thenReturn(
                new TokenResponse("at-value", "Bearer", 3600L, "rt-value", "idt-value"));
        when(oidcClient.verifyIdToken("idt-value", txn.nonce()))
                .thenReturn(new IdTokenClaims(sub, "张三", null, null));

        AuthAppService.LoginCompletion completion = authAppService.completeLogin(
                "code-1", txn.state(), txn.cookieValue(), credential);

        assertThat(completion.sessionCookie()).isNotNull();
        assertThat(completion.guestClearCookie()).isNotNull();
        assertThat(completion.guestClearCookie().getMaxAge().isZero()).isTrue();

        Account account = accountAppService.accountByIdentifier(sub).orElseThrow();
        assertThat(ownerAccountOf(projectId)).isEqualTo(account.getId());
        assertThat(ownerGuestOf(projectId)).isNull();
        assertThat(revokedAtOf(guestId)).isNotNull();
    }

    // ---------- 账户会话优先于访客凭证：登录态新输入挂账户 ----------

    @Test
    void given_account_session_and_guest_cookie_when_create_then_attaches_to_account()
            throws Exception {
        // 先有一段访客历史：访客项目在册
        Long guestProject = createAnonymousProject("访客历史项目");

        // 账户会话在场（同携访客凭证）：新输入直接挂账户，访客凭证不消费
        MvcResult accountCreate = mockMvc.perform(post("/api/projects")
                        .cookie(new Cookie(AuthCookies.SESSION_COOKIE_NAME, SESSION_ACCOUNT))
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"登录态新项目\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Long newProject = Long.parseLong(objectMapper.readTree(
                accountCreate.getResponse().getContentAsString())
                .path("data").path("project").path("id").asText());

        assertThat(ownerAccountOf(newProject)).isEqualTo(ACCOUNT);
        assertThat(ownerGuestOf(newProject)).isNull();
        // 访客凭证未被消费：历史项目仍挂访客、访客行未作废
        assertThat(ownerGuestOf(guestProject)).isEqualTo(guestId);
        assertThat(revokedAtOf(guestId)).isNull();
    }

    // ---------- 签约门：拦截（访客）与放行（绑定后账户） ----------

    @Test
    void given_bound_guest_project_when_account_place_order_then_released() throws Exception {
        Long projectId = createAnonymousProject("绑定后下单项目");
        jdbcTemplate.update("UPDATE prj_projects SET prd_produced_at = NOW() WHERE id = ?", projectId);

        // 登录即绑定（未走端点，直调应用层）：项目过户给 ACCOUNT、访客行作废
        bindingAppService.bindOnLogin(credential, ACCOUNT);

        // 绑定后账户会话过闸：下单成功（对比 GuestSessionIntegrationTest 的访客拦截）
        mockMvc.perform(post("/api/projects/" + projectId + "/orders")
                        .cookie(new Cookie(AuthCookies.SESSION_COOKIE_NAME, SESSION_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ord_orders", Long.class))
                .isEqualTo(1L);
    }

    // -------- 测试工具 --------

    /** 匿名首条需求消息建访客＋项目（捕获凭证/访客 id/项目 id 供后续请求与断言）。 */
    private Long createAnonymousProject(String requirement) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"" + requirement + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        credential = cookieValueOf(result.getResponse().getHeader("Set-Cookie"));
        guestId = jdbcTemplate.queryForObject(
                "SELECT id FROM idn_guests WHERE credential = ?", Long.class, credential);
        return projectIdOf(result);
    }

    /** 回访访客（带凭证）建项目：挂既有访客，不另建行。 */
    private Long createGuestProject(String requirement) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .cookie(new Cookie(AuthCookies.GUEST_COOKIE_NAME, credential))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"" + requirement + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return projectIdOf(result);
    }

    private Long projectIdOf(MvcResult result) throws Exception {
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("project").path("id").asText());
    }

    private Long ownerAccountOf(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT owner_account_id FROM prj_projects WHERE id = ?", Long.class, projectId);
    }

    private Long ownerGuestOf(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT owner_guest_id FROM prj_projects WHERE id = ?", Long.class, projectId);
    }

    private LocalDateTime revokedAtOf(Long guestId) {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM idn_guests WHERE id = ?", LocalDateTime.class, guestId);
    }

    private String capturedCode() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(AccountBindingProvider.EMAIL), anyString(), code.capture());
        assertThat(code.getValue()).matches("\\d{6}");
        return code.getValue();
    }

    private static String cookieValueOf(String setCookieHeader) {
        return setCookieHeader.split(";")[0].split("=", 2)[1];
    }
}
