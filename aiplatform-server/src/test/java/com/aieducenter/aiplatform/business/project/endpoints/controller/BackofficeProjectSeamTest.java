package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;
import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台项目只读面（#159，/api/backoffice/projects）在 {@code #152} seam 上全绿：
 * MockMvc 穿完整过滤链（签名验签 → 会话豁免 → @RequireSignature 强制闸）→ 真应用
 * 服务 → aiplatform_test 真库。清单/详情两读端点纯数据库路径（订单引用/账号换算
 * 都是库事实，无 docker 依赖）——仅订单夹具的跨 BC 项目读按 {@code #152} 形制
 * mock 收口（同 BackofficeOrderSeamTest.placeOrder）。
 *
 * <p>#159 验收面在本类钉死：三档单选（ACTIVE/ARCHIVED/缺省全，归档项目缺省含）、
 * 创建时间区间（含端点边界）、externalId 换算过滤＋行带 ownerDisplayName（缺档/
 * 无主容缺 null 不炸）、项目 id 精确（查无/非数值＝空清单 200）、详情订单引用三态
 * （有未终结单 / 只有历史单 / 无单，照用户面 activeOrder/latestOrder 先例）、已删
 * 项目不可见（真删无墓碑：清单不含＋详情 404）、分页上界截断与越界、签名负例与
 * 过滤参数绑定负例（框架信封）。</p>
 */
@BackofficeSeamTest
class BackofficeProjectSeamTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OrderAppService orderAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 订单夹具的跨 BC 读面（docker 依赖收口，同 BackofficeOrderSeamTest 形制）。 */
    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    /** 工作区软引用无 FK：夹具自增占位（一项目一 dev 环境的隔离只求不撞号）。 */
    private long workspaceSeq = 920100L;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM idn_accounts");
    }

    // ---------- 三档单选＋归档缺省含 ----------

    @Test
    void given_active_and_archived_projects_when_filter_single_select_then_matching_rows()
            throws Exception {
        Project active = newProject("进行中的项目", null);
        Project archived = archivedProject("已归档的项目", null);

        // 单选 1（进行中）：仅未归档
        signedGet("/api/backoffice/projects?status=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(active.getId().toString())))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 单选 3（已归档）：仅已归档（全状态照读）
        signedGet("/api/backoffice/projects?status=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(archived.getId().toString())))
                .andExpect(jsonPath("$.data.items[0].archived").value(true))
                .andExpect(jsonPath("$.data.items[0].status").value(3))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 缺省（无参）＝全部：归档项目缺省含（售后排查历史项目不受影响）
        signedGet("/api/backoffice/projects")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(
                        active.getId().toString(), archived.getId().toString())))
                .andExpect(jsonPath("$.data.total").value("2"));
    }

    @Test
    void given_projects_created_at_known_times_when_filter_by_range_then_endpoints_inclusive()
            throws Exception {
        Project first = newProject("较早的项目", null);
        Project second = newProject("较晚的项目", null);
        LocalDateTime firstAt = createdAtOf(first.getId());
        LocalDateTime secondAt = createdAtOf(second.getId());
        // 边界断言依赖两项目时点可分（两笔完整事务隔开，PG 微秒精度下必然成立）
        assertThat(secondAt).isAfter(firstAt);

        // createdFrom＝较早项目时点（下界含端点）：两项目都在
        signedGet("/api/backoffice/projects?createdFrom=" + firstAt)
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.total").value("2"));

        // createdTo＝较早项目时点（上界含端点）：仅较早项目
        signedGet("/api/backoffice/projects?createdTo=" + firstAt)
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(first.getId().toString())))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 闭区间 [首项目时点, 次项目时点]：两端点都含
        signedGet("/api/backoffice/projects?createdFrom=" + firstAt
                        + "&createdTo=" + secondAt)
                .andExpect(jsonPath("$.data.items", hasSize(2)));

        // 下界抬到次项目时点：仅次项目（首项目严格早于下界被排除）
        signedGet("/api/backoffice/projects?createdFrom=" + secondAt)
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(second.getId().toString())))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    @Test
    void given_projects_owned_when_filter_by_external_id_then_rows_with_owner_name()
            throws Exception {
        // 真账号 + 有主项目 + 一个无主项目（测试/无会话上下文可空）
        Account owner = accountRepository.save(
                Account.register("sub-159-a", "运营查档·王五"));
        Project owned = newProject("有主的项目", owner.getId());
        Project anonymous = newProject("无主的项目", null);

        // externalId 命中：只有该账号的项目，行带显示名（运营不用二次查档）
        signedGet("/api/backoffice/projects?externalId=sub-159-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(owned.getId().toString())))
                .andExpect(jsonPath("$.data.items[0].ownerDisplayName")
                        .value("运营查档·王五"))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 全量行：有主带名、无主容缺 null（不炸）。排序 id 倒序，但两笔快速
        // save 可能同毫秒——TSID 低位随机不保证单调，下标以 id 值定而非创建序
        // （次序断言本身即 id 倒序的顺带验证）
        int ownedIndex = owned.getId() > anonymous.getId() ? 0 : 1;
        int anonymousIndex = 1 - ownedIndex;
        signedGet("/api/backoffice/projects")
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[" + ownedIndex + "].id")
                        .value(owned.getId().toString()))
                .andExpect(jsonPath("$.data.items[" + ownedIndex + "].ownerDisplayName")
                        .value("运营查档·王五"))
                .andExpect(jsonPath("$.data.items[" + anonymousIndex + "].id")
                        .value(anonymous.getId().toString()))
                .andExpect(jsonPath("$.data.items[" + anonymousIndex + "].ownerDisplayName")
                        .value(nullValue()));

        // 详情同样带归属账号显示名（有主带名 / 无主 null）
        signedGet("/api/backoffice/projects/" + owned.getId())
                .andExpect(jsonPath("$.data.ownerDisplayName").value("运营查档·王五"));
        signedGet("/api/backoffice/projects/" + anonymous.getId())
                .andExpect(jsonPath("$.data.ownerDisplayName").value(nullValue()));

        // externalId 未命中（用户在我方无建档）＝无项目可检：如实空清单，非错误
        signedGet("/api/backoffice/projects?externalId=sub-never-registered")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));

        // 账号已删：externalId 换算必落空 → 过滤面同「未建档」语义＝空清单；
        // 项目本身仍是交付记录（不带账号维度看），取名容缺 ownerDisplayName 落
        // null 不炸（有主项目悬空引用＋无主项目，两行皆 null）
        accountRepository.deleteById(owner.getId());
        signedGet("/api/backoffice/projects?externalId=sub-159-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));
        signedGet("/api/backoffice/projects")
                .andExpect(jsonPath("$.data.items[*].ownerDisplayName",
                        containsInAnyOrder(nullValue(), nullValue())));
    }

    @Test
    void given_projects_when_filter_by_project_id_then_exact_hit_and_misses_are_empty()
            throws Exception {
        Project project = newProject("报障定位的项目", null);
        newProject("另一个项目", null);

        // 项目 id 精确命中（用户报障贴链接场景）：单行
        signedGet("/api/backoffice/projects?projectId=" + project.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(project.getId().toString())))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 查无此号：空清单
        signedGet("/api/backoffice/projects?projectId=123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));

        // 非数值项目 id（过滤值非寻址语义）：不可能命中任何 TSID → 空清单，不 500
        signedGet("/api/backoffice/projects?projectId=not-a-tsid")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));
    }

    // ---------- 详情订单引用三态（照用户面 activeOrder/latestOrder 先例） ----------

    @Test
    void given_project_orders_in_three_states_when_signed_detail_then_order_refs_match()
            throws Exception {
        Project withActive = newProject("挂着未终结单的项目", null);
        OrderResponse active = placeOrder(withActive.getId());

        Project withHistory = newProject("只有历史单的项目", null);
        OrderResponse historical = placeOrder(withHistory.getId());
        orderAppService.cancel(Long.parseLong(historical.id()));

        Project withoutOrders = newProject("从未下单的项目", null);

        // 有未终结单：activeOrder 非空（1=待报价），latestOrder 同指该单
        signedGet("/api/backoffice/projects/" + withActive.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(withActive.getId().toString()))
                .andExpect(jsonPath("$.data.name").value("挂着未终结单的项目"))
                .andExpect(jsonPath("$.data.archived").value(false))
                .andExpect(jsonPath("$.data.generatedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.activeOrder.id").value(active.id()))
                .andExpect(jsonPath("$.data.activeOrder.status").value(1))
                .andExpect(jsonPath("$.data.latestOrder.id").value(active.id()));

        // 只有历史单：activeOrder 转 null，latestOrder 承接最近一张（任意状态）
        signedGet("/api/backoffice/projects/" + withHistory.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeOrder").value(nullValue()))
                .andExpect(jsonPath("$.data.latestOrder.id").value(historical.id()))
                .andExpect(jsonPath("$.data.latestOrder.status").value(5));

        // 无单：两引用皆空
        signedGet("/api/backoffice/projects/" + withoutOrders.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeOrder").value(nullValue()))
                .andExpect(jsonPath("$.data.latestOrder").value(nullValue()));
    }

    // ---------- 已删项目不可见（真删无墓碑） ----------

    @Test
    void given_deleted_project_when_list_and_detail_then_invisible() throws Exception {
        Project project = newProject("将删除的项目", null);
        projectRepository.deleteById(project.getId());

        // 清单不含已删项目
        signedGet("/api/backoffice/projects")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));

        // 详情 404（无墓碑）；未知名与非数值标识同口径 PRJ_001
        signedGet("/api/backoffice/projects/" + project.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        signedGet("/api/backoffice/projects/999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        signedGet("/api/backoffice/projects/not-a-tsid")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    // ---------- 分页边界 ----------

    @Test
    void given_101_projects_when_page_bounds_then_size_capped_and_far_page_empty()
            throws Exception {
        // 101 项目 > size 上界 100：上界截断可证（items 恰 100、total 仍 101）
        for (int i = 0; i < 101; i++) {
            newProject("分页项目" + i, null);
        }

        signedGet("/api/backoffice/projects?size=500&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(100)))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.total").value("101"));

        // page 越界：空页不炸、total 原样回（分页元数据完整）
        signedGet("/api/backoffice/projects?size=20&page=99")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(99))
                .andExpect(jsonPath("$.data.total").value("101"));
    }

    // ---------- 签名与参数绑定负例 ----------

    @Test
    void given_no_signature_headers_when_get_projects_then_401_signature_required()
            throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(get("/api/backoffice/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    @Test
    void given_invalid_filter_params_when_list_then_framework_envelope() throws Exception {
        newProject("绑定负例的项目", null);
        // 非法状态 code → 400 带合法取值表（框架统一信封）
        signedGet("/api/backoffice/projects?status=99")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status 取值 99 非法，合法取值：1=进行中, 3=已归档"));
        signedGet("/api/backoffice/projects?status=active")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status 取值 active 非法，合法取值：1=进行中, 3=已归档"));
        // 时间类型不匹配 → 404（框架口径，不暴露转换细节）
        signedGet("/api/backoffice/projects?createdFrom=not-a-time")
                .andExpect(status().isNotFound());
        // 非数值分页 → 400 带字段级明细
        for (String query : new String[] {"size=abc", "page=0x"}) {
            signedGet("/api/backoffice/projects?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Parameter validation failed"));
        }
    }

    // -------- 夹具 --------

    /** 签名 GET（无体）：pathWithQuery 原样参与签名计算（与验签 Filter 同构）。 */
    private ResultActions signedGet(String pathWithQuery) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null));
    }

    /** 建项目（真库写入，workspaceId 占位自增、归属账号可空）。 */
    private Project newProject(String name, Long ownerAccountId) {
        return projectRepository.save(
                Project.create(name, ProjectType.WEBSITE, workspaceSeq++, ownerAccountId));
    }

    /** 已归档项目（真聚合 archive() 路径落 archived_at）。 */
    private Project archivedProject(String name, Long ownerAccountId) {
        Project project = newProject(name, ownerAccountId);
        Project loaded = projectRepository.findById(project.getId()).orElseThrow();
        loaded.archive();
        return projectRepository.save(loaded);
    }

    /** 经真应用服务下单（真库写入、快照冻结），返回带 TSID 的订单回执。 */
    private OrderResponse placeOrder(long projectId) {
        when(projectQueryAppService.detail(projectId)).thenReturn(new ProjectDetailResponse(
                Long.toString(projectId), "seam 测试项目", ProjectType.WEBSITE, "官网", "9200",
                ProjectStatus.IN_PROGRESS, ProjectStatus.IN_PROGRESS.getName(), false,
                LocalDateTime.of(2026, 9, 13, 9, 0), null, null, null, null, null, null, null));
        when(projectQueryAppService.prd(projectId)).thenReturn(new PrdResponse(
                Long.toString(projectId), "# PRD\n\n需求背景：后台项目 seam。",
                Instant.parse("2026-09-13T01:00:00Z")));
        try {
            // 下单绑定缺省账号会话——owner 路由键非空（发布口强制校验）
            return RequestContext.runFor(
                    new RequestContext(null, null, null, null, /* userId */ 900001L,
                            null, null, null),
                    () -> orderAppService.place(projectId));
        } catch (Exception e) {
            throw new RuntimeException("下单夹具绑定缺省账号失败", e);
        }
    }

    /** 库内创建时点（边界断言以库值为准，不依赖应用时钟）。 */
    private LocalDateTime createdAtOf(Long projectId) {
        Timestamp createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM prj_projects WHERE id = ?",
                Timestamp.class, projectId);
        assertThat(createdAt).isNotNull();
        return createdAt.toLocalDateTime();
    }
}
