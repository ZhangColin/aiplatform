package com.aieducenter.aiplatform.business.order.endpoints.controller;

import java.time.Instant;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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
 * 设计资产包下载端到端（#297，@IntegrationTest 隔离库＋EnvironmentBackend 假面，
 * 对偶 {@code ProjectFileDownloadServeTest} 范式）：真过滤链 → 真订单/应用服务 →
 * 真库。支付门真值表正本——未付费（待报价/已报价/已取消）402 ORD_015、docker
 * 零触达；已支付（归档前中间态）/已归档即开放。守卫序＝订单存在 → 交付物类型
 * （系统单 404 ORD_016，先于门——纯判定不触容器）→ 门 → 冻结件读取（缺失 404
 * PRJ_051）。契约面：application/gzip 二进制流＋attachment 带单号文件名＋字节
 * 保真＋容器命令正本（守卫 + cat）。
 */
@IntegrationTest
@AutoConfigureMockMvc
class OrderDesignPackageServeTest {

    /** 冻结件假面字节（gzip 魔数打头——tar 真包成员集归 LiveTest）。 */
    private static final byte[] PACKAGE_BYTES = {
            0x1f, (byte) 0x8b, 0x08, 0x00, 'd', 'e', 's', 'i', 'g', 'n'};

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

    /** docker 依赖收口（冻结件＝容器内字节直读）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private static final String SESSION_ID = "design-package-test-session";

    private Long projectId;
    private long workspaceSeq = 930300L;

    private ResultActions performAsUser(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.cookie(new Cookie("aiplatform_session", SESSION_ID)));
    }

    @BeforeEach
    void seed() {
        sessionStore.put(SESSION_ID, new BffSession(
                1L, "资产包下载测试", null, null, null, Instant.now().plusSeconds(3600)));
        Workspace ready = Workspace.registerPending(
                WorkspaceId.of(Long.toString(workspaceSeq)), EnvKind.DEV);
        ready.complete(WorkspaceProvision.of(WorkspaceHandle.dev(
                WorkspaceId.of(Long.toString(workspaceSeq)),
                "ws-" + workspaceSeq, "previewnet")));
        workspaceRepository.save(ready);
        projectId = projectRepository.save(
                Project.create("资产包下载测试", null, workspaceSeq, 1L)).getId();
    }

    @AfterEach
    void cleanup() {
        sessionStore.remove(SESSION_ID);
        jdbcTemplate.update("DELETE FROM ord_orders WHERE project_id = ?", projectId);
        projectRepository.findById(projectId).ifPresent(projectRepository::delete);
        workspaceRepository.findById(workspaceSeq).ifPresent(workspaceRepository::delete);
    }

    private String designPackageUrl(Long orderId) {
        return "/api/orders/" + orderId + "/design-package";
    }

    private void stubFrozenPackagePresent() {
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(PACKAGE_BYTES, "", 0));
    }

    // ---------- 订单状态种子（领域转移链，不裸改库——状态机即事实） ----------

    /** 待报价设计单（place 即是）。 */
    private Long seedPendingDesignOrder() {
        return orderRepository.save(Order.place(projectId, 1L, "# PRD",
                OrderDeliverableType.DESIGN)).getId();
    }

    /** 已支付设计单（真实中间态，归档前即放行）。 */
    private Long seedPaidDesignOrder() {
        Order order = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.DESIGN);
        order.quote(10000L, null, null);
        order.pay("PAY-TEST-1");
        return orderRepository.save(order).getId();
    }

    /** 已归档设计单（终态）。 */
    private Long seedArchivedDesignOrder() {
        Order order = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.DESIGN);
        order.quote(10000L, null, null);
        order.pay("PAY-TEST-1");
        order.archive();
        return orderRepository.save(order).getId();
    }

    // ---------- 支付门真值表 ----------

    @Test
    void given_unpaid_design_order_when_download_then_ord_015_without_workspace_touch()
            throws Exception {
        // 未付费三态同拦：待报价（place 即是）/已报价/已取消——docker 零触达
        Long orderId = seedPendingDesignOrder();
        assertThatGateClosed(orderId);
        cleanupOrders();

        Order quoted = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.DESIGN);
        quoted.quote(10000L, null, null);
        orderId = orderRepository.save(quoted).getId();
        assertThatGateClosed(orderId);
        cleanupOrders();

        Order cancelled = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.DESIGN);
        cancelled.cancel();
        orderId = orderRepository.save(cancelled).getId();
        assertThatGateClosed(orderId);
    }

    private void cleanupOrders() {
        jdbcTemplate.update("DELETE FROM ord_orders WHERE project_id = ?", projectId);
    }

    private void assertThatGateClosed(Long orderId) throws Exception {
        performAsUser(get(designPackageUrl(orderId)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(5015))
                .andExpect(jsonPath("$.message").value(
                        "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付"));
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    @Test
    void given_paid_design_order_when_download_then_gzip_stream_with_order_scoped_name()
            throws Exception {
        Long orderId = seedPaidDesignOrder();
        stubFrozenPackagePresent();

        performAsUser(get(designPackageUrl(orderId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/gzip"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + orderId + "-design.tar.gz\""))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .isEqualTo(PACKAGE_BYTES));

        // 容器命令正本：冻结件存在守卫 + cat 原始字节（无大小上限子句——带走语义）
        verify(environmentBackend).execBinary(any(WorkspaceHandle.class),
                contains("design-package-" + orderId + ".tar.gz"));
    }

    @Test
    void given_archived_design_order_when_download_then_open() throws Exception {
        Long orderId = seedArchivedDesignOrder();
        stubFrozenPackagePresent();

        performAsUser(get(designPackageUrl(orderId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/gzip"));
    }

    // ---------- 守卫序：类型面与冻结件面 ----------

    @Test
    void given_system_order_when_download_then_ord_016_before_gate() throws Exception {
        // 系统单交付物不含设计资产包：类型判定（纯）先于支付门（未付费也吃 404
        // ORD_016 而非 402——寻址成功而交付物类型不符，如实不假装有包）
        Order system = Order.place(projectId, 1L, "# PRD", OrderDeliverableType.SYSTEM);
        Long orderId = orderRepository.save(system).getId();

        performAsUser(get(designPackageUrl(orderId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5016))
                .andExpect(jsonPath("$.message").value(
                        "该订单的交付物不含设计资产包（系统单交付物是源码包）"));
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    @Test
    void given_paid_design_order_without_frozen_file_when_download_then_prj_051() throws Exception {
        // 冻结件缺失不以空产物顶替（对偶封存包 WSP_016 口径）
        Long orderId = seedPaidDesignOrder();
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(new byte[0], "cat: 无此文件", 1));

        performAsUser(get(designPackageUrl(orderId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4051));
    }

    @Test
    void given_missing_order_when_download_then_ord_001() throws Exception {
        performAsUser(get(designPackageUrl(900999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5001));
    }
}
