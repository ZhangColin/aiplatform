package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.Instant;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.BinaryExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下载支付门端到端（#287 通用单文件下载＋源码包补门，@IntegrationTest 隔离库＋
 * EnvironmentBackend 假面）：真过滤链 → 真应用/查询/订单服务 → 真库。门判定
 * 真值表正本——未付费（无单/待报价/已报价/已取消）单文件与源码包皆拦（402
 * ORD_015 如实告知门语义、docker 零触达），曾支付（已支付＝归档前中间态）/
 * 已归档即开放；契约面：attachment 带走语义＋真实 content-type＋原始字节（含
 * NUL）＋容器命令正本（守卫 + cat、无大小上限子句）。点看不受门（files/raw
 * 未付费照旧 200）与后台镜像端点不受门（BackofficeOrderAppService 走
 * sourcePackage 内核）的边界归各自缝钉死。
 */
@IntegrationTest
@AutoConfigureMockMvc
class ProjectFileDownloadServeTest {

    /** PNG 魔数＋载荷（含 NUL——下载字节保真的钉子）。 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01, 0x02};

    /** 源码包假面字节（gzip 魔数打头，形不对齐即可——内核归 packSource 先例）。 */
    private static final byte[] TARBALL_BYTES = {0x1f, (byte) 0x8b, 0x08, 0x00, 't', 'a', 'r'};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BffSessionStore sessionStore;

    /** docker 依赖收口（单文件下载＝容器内字节直读；源码包＝整卷打包，同假面）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private static final String SESSION_ID = "file-download-test-session";

    private Long projectId;
    private long workspaceSeq = 930200L;

    /** 全上下文过滤链要求真会话（BffSessionContextFilter 绑定 + ApiAuth 拦截）。 */
    private ResultActions performAsUser(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.cookie(new Cookie("aiplatform_session", SESSION_ID)));
    }

    @BeforeEach
    void seed() {
        sessionStore.put(SESSION_ID, new BffSession(
                1L, "下载门测试", null, null, null, Instant.now().plusSeconds(3600)));
        Workspace ready = Workspace.registerPending(
                WorkspaceId.of(Long.toString(workspaceSeq)), EnvKind.DEV);
        ready.complete(WorkspaceProvision.of(WorkspaceHandle.dev(
                WorkspaceId.of(Long.toString(workspaceSeq)),
                "ws-" + workspaceSeq, "previewnet")));
        workspaceRepository.save(ready);
        projectId = projectRepository.save(
                Project.create("下载门测试", null, workspaceSeq, 1L)).getId();
    }

    @AfterEach
    void cleanup() {
        sessionStore.remove(SESSION_ID);
        jdbcTemplate.update("DELETE FROM ord_orders WHERE project_id = ?", projectId);
        projectRepository.findById(projectId).ifPresent(projectRepository::delete);
        workspaceRepository.findById(workspaceSeq).ifPresent(workspaceRepository::delete);
    }

    private String downloadUrl(String path) {
        return "/api/projects/" + projectId + "/files/download?path=" + path;
    }

    private String sourcePackageUrl() {
        return "/api/projects/" + projectId + "/source-package";
    }

    // ---------- 订单状态种子（领域转移链，不裸改库——状态机即事实） ----------

    /** 已报价（待支付）：place → quote。 */
    private void seedQuotedOrder() {
        Order order = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.SYSTEM);
        order.quote(10000L, null, null);
        orderRepository.save(order);
    }

    /** 已取消（未支付回迭代）：place → cancel。 */
    private void seedCancelledOrder() {
        Order order = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.SYSTEM);
        order.cancel();
        orderRepository.save(order);
    }

    /** 已支付（真实中间态，归档前即放行）：place → quote → pay。 */
    private void seedPaidOrder() {
        Order order = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.SYSTEM);
        order.quote(10000L, null, null);
        order.pay("PAY-TEST-1");
        orderRepository.save(order);
    }

    /** 已归档（终态）：place → quote → pay → archive。 */
    private void seedArchivedOrder() {
        Order order = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.SYSTEM);
        order.quote(10000L, null, null);
        order.pay("PAY-TEST-1");
        order.archive();
        orderRepository.save(order);
    }

    // ---------- 门真值表：单文件下载 ----------

    @Test
    void given_no_order_when_download_then_ord_015_without_workspace_touch() throws Exception {
        performAsUser(get(downloadUrl("materials/ref.png")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(5015))
                .andExpect(jsonPath("$.message").value(
                        "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付"));
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    @Test
    void given_unpaid_statuses_when_download_faces_then_ord_015() throws Exception {
        // 未支付三态同拦（两下载面同门）：待报价（place 即是）/已报价/已取消
        // （取消即回迭代，从未支付）
        orderRepository.save(Order.place(projectId, 1L, "# PRD", OrderDeliverableType.SYSTEM));
        assertThatGateClosedForBothDownloadFaces();
        cleanupOrders();

        seedQuotedOrder();
        assertThatGateClosedForBothDownloadFaces();
        cleanupOrders();

        seedCancelledOrder();
        assertThatGateClosedForBothDownloadFaces();
    }

    private void cleanupOrders() {
        jdbcTemplate.update("DELETE FROM ord_orders WHERE project_id = ?", projectId);
    }

    private void assertThatGateClosedForBothDownloadFaces() throws Exception {
        performAsUser(get(downloadUrl("materials/ref.png")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(5015));
        performAsUser(get(sourcePackageUrl()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(5015));
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
        verify(environmentBackend, never()).packSource(any(WorkspaceHandle.class));
    }

    @Test
    void given_paid_when_download_then_attachment_bytes_with_canonical_command() throws Exception {
        seedPaidOrder();
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(PNG_BYTES, "", 0));

        byte[] body = performAsUser(get(downloadUrl("materials/ref.png")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"ref.png\""))
                .andReturn().getResponse().getContentAsByteArray();

        // 原始字节原样（含 NUL——带走语义，非文本通道、非 JSON 信封）
        assertThat(body).containsExactly(PNG_BYTES);
        // 容器命令＝ProjectFiles.downloadCommand 正本：存在守卫 + cat、无大小上限子句
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(environmentBackend).execBinary(any(WorkspaceHandle.class), command.capture());
        assertThat(command.getValue())
                .isEqualTo(ProjectFiles.downloadCommand("materials/ref.png"))
                .doesNotContain("stat -c");
    }

    @Test
    void given_archived_when_download_then_served() throws Exception {
        seedArchivedOrder();
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult("src".getBytes(), "", 0));

        performAsUser(get(downloadUrl("src/app.ts")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"app.ts\""));
    }

    // ---------- 门真值表：源码包端点补门（无门→有门，#287 行为变更） ----------

    @Test
    void given_unpaid_when_source_package_then_ord_015_without_workspace_touch() throws Exception {
        seedQuotedOrder();

        performAsUser(get(sourcePackageUrl()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(5015))
                .andExpect(jsonPath("$.message").value(
                        "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付"));
        verify(environmentBackend, never()).packSource(any(WorkspaceHandle.class));
    }

    @Test
    void given_paid_when_source_package_then_tarball_served() throws Exception {
        seedPaidOrder();
        when(environmentBackend.packSource(any(WorkspaceHandle.class)))
                .thenReturn(TARBALL_BYTES);

        byte[] body = performAsUser(get(sourcePackageUrl()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/gzip"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).containsExactly(TARBALL_BYTES);
    }

    // ---------- 门只盖下载面：点看照旧自由（体验免费） ----------

    @Test
    void given_unpaid_when_raw_view_then_still_served() throws Exception {
        // 同一未付费项目：下载被门拦，点看（raw inline）照旧 200——门语义边界钉子
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(PNG_BYTES, "", 0));
        performAsUser(get("/api/projects/" + projectId + "/files/raw")
                        .param("path", "materials/ref.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "inline; filename=\"ref.png\""));
    }

    // ---------- 放行后的常规守卫（门开了路径/文件守卫照常） ----------

    @Test
    void given_paid_and_non_viewable_path_when_download_then_prj_020() throws Exception {
        seedPaidOrder();
        for (String path : new String[] {".env", "../escape.png", "data/x.png"}) {
            performAsUser(get(downloadUrl(path)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(4020));
        }
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    @Test
    void given_paid_and_missing_file_when_download_then_prj_021() throws Exception {
        seedPaidOrder();
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(new byte[0], "", 1));
        performAsUser(get(downloadUrl("materials/gone.png")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4021));
    }

    @Test
    void given_unknown_project_when_download_then_prj_001() throws Exception {
        mockMvc.perform(get("/api/projects/999999999/files/download?path=materials/ref.png")
                        .cookie(new Cookie("aiplatform_session", SESSION_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    // ---------- 越权：既有隔离口径（会话闸，单账号 v1） ----------

    @Test
    void given_no_session_when_download_then_401() throws Exception {
        mockMvc.perform(get(downloadUrl("materials/ref.png")))
                .andExpect(status().isUnauthorized());
    }
}
