package com.aieducenter.aiplatform.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台 swagger 自描述契约（#242）：照 {@link SpringDocEnumContractTest} 先例起全
 * 上下文拉 {@code /v3/api-docs/{group}} 断 components.schemas——钉住三处此前只能
 * 反向读源码的真实结构（wire 零变化，纯 @Schema 注解的自描述面）：
 *
 * <ul>
 *   <li>成本金额字段（四个承载面）：币种分桶结构说明（键 = ISO 4217 币种码、
 *       值 = 金额）＋示例——不再是 additionalProperties 裸空对象；</li>
 *   <li>对话条目四载荷（question / closing / attachments / quote）：描述＋示例
 *       非空（quote 须注明不含金额——视镜语义，ADR-0017）；</li>
 *   <li>unitPrice 出入参：响应侧 string、开行/改价入参侧 number，类型照实各自
 *       显式呈现（不做类型统一）；</li>
 *   <li>ownerExternalId（订单/项目四读面，#243）：账号档案读口的寻址键，
 *       type=string 钉死。</li>
 * </ul>
 */
@IntegrationTest
@AutoConfigureMockMvc
class SpringDocBackofficeContractTest {

    /** 四个成本承载面：分组|schema 名（CostSummary 为嵌套 record，springdoc 平铺命名）。 */
    private static final List<String[]> COST_SURFACES = List.of(
            new String[]{"metering", "BackofficeCostOverviewResponse"},
            new String[]{"metering", "BackofficeProjectCostResponse"},
            new String[]{"metering", "BackofficeProjectCostDetailResponse"},
            new String[]{"project", "CostSummary"});

    /** 四个 owner 读面（#243）：分组|schema 名——订单/项目各自的清单与详情。 */
    private static final List<String[]> OWNER_SURFACES = List.of(
            new String[]{"order", "BackofficeOrderSummaryResponse"},
            new String[]{"order", "BackofficeOrderDetailResponse"},
            new String[]{"project", "BackofficeProjectSummaryResponse"},
            new String[]{"project", "BackofficeProjectDetailResponse"});

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void given_cost_surfaces_when_read_schemas_then_currency_buckets_are_self_described() throws Exception {
        for (String[] surface : COST_SURFACES) {
            JsonNode cost = property(fetchGroup(surface[0]), surface[1], "cost");
            assertThat(cost.path("description").asText(""))
                    .as("%s.cost 应写明币种分桶结构（键 = ISO 4217 币种码、值 = 金额）", surface[1])
                    .contains("ISO 4217")
                    .contains("币种");
            assertThat(exampleText(cost))
                    .as("%s.cost 应附结构示例", surface[1])
                    .isNotBlank();
        }
    }

    @Test
    void given_conversation_entry_schema_when_read_payloads_then_four_payloads_are_documented() throws Exception {
        JsonNode entry = fetchGroup("project").path("components").path("schemas")
                .path("ConversationEntryResponse");
        assertThat(entry.isMissingNode())
                .as("project 分组应含 ConversationEntryResponse（缺 schema 视为契约漂移）")
                .isFalse();
        for (String field : List.of("question", "closing", "attachments", "quote")) {
            JsonNode payload = entry.path("properties").path(field);
            assertThat(payload.isMissingNode())
                    .as("ConversationEntryResponse.%s 缺字段（契约漂移）", field)
                    .isFalse();
            assertThat(payload.path("description").asText(""))
                    .as("ConversationEntryResponse.%s 应有结构说明", field)
                    .isNotBlank();
            assertThat(exampleText(payload))
                    .as("ConversationEntryResponse.%s 应附示例", field)
                    .isNotBlank();
        }
        // quote 专项：视镜语义不含金额（ADR-0017）——消费方不得误以为对话史里有报价金额可取
        assertThat(entry.path("properties").path("quote").path("description").asText(""))
                .as("quote 载荷说明须注明不含金额")
                .contains("不含金额");
    }

    @Test
    void given_price_entry_schemas_when_read_unit_price_then_types_are_explicit_as_is() throws Exception {
        JsonNode metering = fetchGroup("metering");
        // 响应侧：十进制原串（BigDecimal 直出会落科学计数）
        JsonNode response = property(metering, "UnitPriceEntryResponse", "unitPrice");
        assertThat(response.path("type").asText(null))
                .as("unitPrice 响应侧类型照实 = string")
                .isEqualTo("string");
        // 入参侧（开行/改价）：number，类型不统一、各自如实
        for (String schema : List.of("OpenPriceEntryCommand", "RepricePriceEntryCommand")) {
            JsonNode command = property(metering, schema, "unitPrice");
            assertThat(command.path("type").asText(null))
                    .as("%s.unitPrice 入参侧类型照实 = number", schema)
                    .isEqualTo("number");
        }
        // 三处均须显式说明＋示例（类型不对称是坑，说明是本票要点）
        for (Map.Entry<String, JsonNode> unitPrice : Map.of(
                "UnitPriceEntryResponse", response,
                "OpenPriceEntryCommand", property(metering, "OpenPriceEntryCommand", "unitPrice"),
                "RepricePriceEntryCommand", property(metering, "RepricePriceEntryCommand", "unitPrice")
        ).entrySet()) {
            assertThat(unitPrice.getValue().path("description").asText(""))
                    .as("%s.unitPrice 应有类型说明", unitPrice.getKey())
                    .isNotBlank();
            assertThat(exampleText(unitPrice.getValue()))
                    .as("%s.unitPrice 应附示例", unitPrice.getKey())
                    .isNotBlank();
        }
    }

    @Test
    void given_owner_surfaces_when_read_schemas_then_owner_external_id_is_string() throws Exception {
        // #243：ownerExternalId 是账号档案读口（按 externalId 寻址）的键——OIDC
        // sub 为不透明串，四读面 type=string 钉死（防类型漂移炸消费方）
        for (String[] surface : OWNER_SURFACES) {
            JsonNode ownerExternalId = property(fetchGroup(surface[0]), surface[1], "ownerExternalId");
            assertThat(ownerExternalId.path("type").asText(null))
                    .as("%s.ownerExternalId 应渲染 type=string", surface[1])
                    .isEqualTo("string");
        }
    }

    // ---------- 装载 ----------

    private JsonNode fetchGroup(String group) throws Exception {
        MockHttpServletRequestBuilder request = get("/v3/api-docs/" + group);
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** components.schemas.{schema}.properties.{field}（缺失即契约漂移，直接失败）。 */
    private JsonNode property(JsonNode doc, String schema, String field) {
        JsonNode property = doc.path("components").path("schemas").path(schema)
                .path("properties").path(field);
        assertThat(property.isMissingNode())
                .as("分组应含 %s.%s（缺 schema 视为契约漂移）", schema, field)
                .isFalse();
        return property;
    }

    /** 示例文本（值节点取文本、结构节点取 JSON 串；缺示例 = 空串）。 */
    private static String exampleText(JsonNode property) {
        JsonNode example = property.path("example");
        if (example.isMissingNode() || example.isNull()) {
            return "";
        }
        return example.isValueNode() ? example.asText() : example.toString();
    }
}
