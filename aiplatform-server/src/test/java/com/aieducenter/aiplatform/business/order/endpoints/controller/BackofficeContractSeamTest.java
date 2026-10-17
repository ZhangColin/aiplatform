package com.aieducenter.aiplatform.business.order.endpoints.controller;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台合同承接端点在 #152 seam 上全绿（#336）：真过滤链（五头 HMAC 签名闸/
 * 会话豁免/强制拦截器）＋真应用服务＋aiplatform_test 真库——范围录入 → 报价
 * → 重建创建（用户确认范围共用口）→ 推进 → 签署标记 → 完成全链经真签名 HTTP
 * 驱动，留痕行落库以 JdbcTemplate 直查为证；无签名请求被闸拒（新控制器挂上
 * 签名面的活体证明）。订单夹具直接落库（合同域纯 BC 内闭环，不触项目面）。
 */
@BackofficeSeamTest
class BackofficeContractSeamTest {

    /** 每次落单换新项目号（同项目至多一张未终结订单）。 */
    private static final AtomicLong PROJECT_SEQ = new AtomicLong(900300L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 合同与留痕行经 FK 级联随订单清（同价目行口径）
        jdbcTemplate.update("DELETE FROM ord_orders");
    }

    @Test
    void given_signed_chain_when_scope_quote_create_sign_complete_then_trail_in_db() throws Exception {
        Order order = orderRepository.save(Order.place(PROJECT_SEQ.incrementAndGet(), 1L, null,
                "# PRD v3\n\n已确认版本全文", OrderDeliverableType.SYSTEM, 3, 2));
        String orderPath = "/api/backoffice/orders/" + order.getId();

        // 范围录入＋报价（签约准备面：范围文本工作副本＋价目行）。签名体＝body
        // 原文，透传头由签名件附加（HMAC 覆盖 body——null 与空串不同签）
        String scopeBody = "{\"scopeDeliverables\":\"交付清单：管理后台＋小程序\","
                + "\"scopeAcceptance\":\"验收方式：按 PRD v3 验收\","
                + "\"scopeTimeline\":\"工期口径：4 周\"}";
        mockMvc.perform(BackofficeSignatures.signed(
                post(orderPath + "/scope").contentType(MediaType.APPLICATION_JSON).content(scopeBody), orderPath + "/scope", scopeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeDeliverables").value("交付清单：管理后台＋小程序"));
        String quoteBody = "{\"amount\":128000,\"note\":\"首版报价\"}";
        mockMvc.perform(BackofficeSignatures.signed(
                post(orderPath + "/quote").contentType(MediaType.APPLICATION_JSON).content(quoteBody), orderPath + "/quote", quoteBody))
                .andExpect(status().isOk());

        // 重建创建口（与用户「确认范围并准备合同」共用正本）：合同起步准备中
        String createPath = "/api/backoffice/contracts/orders/" + order.getId();
        String contractId = new ObjectMapper()
                .readTree(mockMvc.perform(BackofficeSignatures.signed(
                                post(createPath).contentType(MediaType.APPLICATION_JSON), createPath, ""))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value(1))
                        .andExpect(jsonPath("$.data.contractNo").isNotEmpty())
                        .andReturn().getResponse().getContentAsString())
                .at("/data/id").asText();

        // 推进 → 签署 → 双方落签 → 完成（每次动作经真签名 HTTP；合同动作以
        // 操作者为审计正身——带 X-User-Id/X-User-Name 透传头，缺头被 ORD_035 拒）
        String base = "/api/backoffice/contracts/" + contractId;
        for (String[] action : new String[][] {
                {"advance", "{\"basis\":\"内容核对完毕，送用户签署\"}"},
                {"advance", "{\"basis\":\"用户已核对，开始签署\"}"},
                {"sign", "{\"party\":1,\"basis\":\"甲方盖章\"}"},
                {"sign", "{\"party\":2,\"basis\":\"乙方盖章\"}"},
                {"advance", "{\"basis\":\"双方落签，合同完成\"}"}}) {
            mockMvc.perform(BackofficeSignatures.signed(
                    post(base + "/" + action[0]).contentType(MediaType.APPLICATION_JSON).content(action[1]),
                    base + "/" + action[0], action[1])
                    .header("X-User-Id", "700100").header("X-User-Name", "运营·小刘"))
                    .andExpect(status().isOk());
        }

        // 详情面：六态到头＋留痕时间线五行（append-only，新 → 旧）
        mockMvc.perform(BackofficeSignatures.signed(get(base), base, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contract.status").value(4))
                .andExpect(jsonPath("$.data.contract.partyASigned").value(true))
                .andExpect(jsonPath("$.data.contract.partyBSigned").value(true))
                .andExpect(jsonPath("$.data.transitions.length()").value(5))
                .andExpect(jsonPath("$.data.transitions[0].action").value(1))
                .andExpect(jsonPath("$.data.transitions[0].basis").value("双方落签，合同完成"));

        // 留痕行落库直查（库内事实：五行 append-only、签署标记 from=to）
        Integer trailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_contract_transitions WHERE contract_id = ?",
                Integer.class, Long.parseLong(contractId));
        assertThat(trailCount).isEqualTo(5);
        Integer signMarkRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ord_contract_transitions"
                        + " WHERE contract_id = ? AND action = 4 AND from_status = to_status",
                Integer.class, Long.parseLong(contractId));
        assertThat(signMarkRows).isEqualTo(2);
    }

    @Test
    void given_unsigned_request_when_post_advance_then_rejected_by_signature_gate() throws Exception {
        // 无签名请求被五头 HMAC 闸拒：新控制器挂上签名面的活体证明
        mockMvc.perform(post("/api/backoffice/contracts/910/advance")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"无签名\"}"))
                .andExpect(status().isUnauthorized());
    }
}
