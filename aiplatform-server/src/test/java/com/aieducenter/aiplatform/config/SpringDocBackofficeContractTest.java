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
 *   <li>技能域两 schema（#247）：技能柄 id 为 opaque 串两形制（builtin:&lt;技能名&gt;／
 *       TSID 十进制串），type=string＋形制自描述；来源 code 带取值对照。</li>
 *   <li>技能域安装命令与留痕面（#248）：SkillInstallCommand.repoUrl 快照语义＋
 *       excludeDirs 路径段排除语义自描述；清单行 operatorId 留痕口径（装者/最近
 *       动作者、type=string）。</li>
 *   <li>技能域槽位指派面（#249）：SkillSlotAssignCommand.skillIds 整包替换语义
 *       （清单即终态）；BackofficeSlotAssignmentResponse.slot 三把槽位键自描述。</li>
 *   <li>技能域更新面（#250）：清单行 updateAvailable 标记语义（有新版/未检查/
 *       永不自动跟新）；SkillUpdateCommand.sourcePackage 取值口径（清单行原值）；
 *       更新回执 from/to 版本与留痕读面自描述。</li>
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

    @Test
    void given_skill_schemas_when_read_group_then_id_is_opaque_string_and_source_coded() throws Exception {
        // #247：技能柄是 opaque 串两形制（builtin:<技能名>／TSID 十进制串）——
        // 消费方不得做数值假设，type=string 钉死＋柄形制自描述（#242 口径）
        JsonNode skills = fetchGroup("skills");
        for (String schema : List.of("BackofficeSkillSummaryResponse", "BackofficeSkillDetailResponse")) {
            JsonNode id = property(skills, schema, "id");
            assertThat(id.path("type").asText(null))
                    .as("%s.id 应渲染 type=string（两形制 opaque 柄）", schema)
                    .isEqualTo("string");
            assertThat(id.path("description").asText(""))
                    .as("%s.id 应自描述两形制（builtin: 前缀与 TSID 串）", schema)
                    .contains("builtin:")
                    .contains("TSID");
            assertThat(exampleText(id))
                    .as("%s.id 应附示例", schema)
                    .isNotBlank();
        }
        // 来源 code 对照自描述（1=内置 2=安装）
        JsonNode source = property(skills, "BackofficeSkillSummaryResponse", "source");
        assertThat(source.path("type").asText(null))
                .as("source 应渲染 type=integer（BaseEnum 房规）")
                .isEqualTo("integer");
        assertThat(source.path("description").asText(""))
                .as("source 应带取值对照")
                .contains("1=内置")
                .contains("2=安装");
    }

    @Test
    void given_install_command_schema_when_read_group_then_repo_url_and_excludes_are_self_described() throws Exception {
        // #248：安装命令两字段自描述——repoUrl 是 clone 契约（快照语义、同源身份），
        // excludeDirs 是排除语义（路径段命中即不入库）；消费方（admin）靠此拼装
        JsonNode skills = fetchGroup("skills");
        JsonNode repoUrl = property(skills, "SkillInstallCommand", "repoUrl");
        assertThat(repoUrl.path("type").asText(null))
                .as("repoUrl 应渲染 type=string")
                .isEqualTo("string");
        assertThat(repoUrl.path("description").asText(""))
                .as("repoUrl 应自描述快照安装语义")
                .contains("快照")
                .contains("HEAD commit");
        JsonNode excludeDirs = property(skills, "SkillInstallCommand", "excludeDirs");
        assertThat(excludeDirs.path("type").asText(null))
                .as("excludeDirs 应渲染 type=array")
                .isEqualTo("array");
        assertThat(excludeDirs.path("description").asText(""))
                .as("excludeDirs 应自描述路径段排除语义")
                .contains("命中即不入库");
        // 操作者留痕面（#248）：清单行两列自描述（null 语义写明——内置与未管理过）
        JsonNode operatorId = property(skills, "BackofficeSkillSummaryResponse", "operatorId");
        assertThat(operatorId.path("type").asText(null))
                .as("operatorId 应渲染 type=string（admin 侧 TSID，外域标识）")
                .isEqualTo("string");
        assertThat(operatorId.path("description").asText(""))
                .as("operatorId 应自描述留痕口径")
                .contains("装者")
                .contains("最近动作者");
    }

    @Test
    void given_slot_assignment_schemas_when_read_group_then_replacement_semantics_self_described() throws Exception {
        // #249：槽位指派读写面——命令是整包替换语义（清单即终态）、柄是 TSID 串
        //（type=string 防数值假设，与清单行 id 口径同源）、读面 slot 键自描述三槽
        JsonNode skills = fetchGroup("skills");
        JsonNode skillIds = property(skills, "SkillSlotAssignCommand", "skillIds");
        assertThat(skillIds.path("type").asText(null))
                .as("skillIds 应渲染 type=array")
                .isEqualTo("array");
        assertThat(skillIds.path("description").asText(""))
                .as("skillIds 应自描述整包替换语义")
                .contains("整包替换")
                .contains("终态");
        JsonNode slot = property(skills, "BackofficeSlotAssignmentResponse", "slot");
        assertThat(slot.path("type").asText(null))
                .as("slot 应渲染 type=string（槽位稳定键）")
                .isEqualTo("string");
        assertThat(slot.path("description").asText(""))
                .as("slot 应自描述三把槽位键")
                .contains("main")
                .contains("executor")
                .contains("subagent");
    }

    @Test
    void given_update_schemas_when_read_group_then_update_semantics_self_described() throws Exception {
        // #250：清单行 updateAvailable 标记三态语义（true 有新版/false 最新/null 未检查）
        // ＋「永不自动跟新」的平台承诺写进自描述——消费方不得把标记当自动同步信号
        JsonNode skills = fetchGroup("skills");
        JsonNode mark = property(skills, "BackofficeSkillSummaryResponse", "updateAvailable");
        assertThat(mark.path("type").asText(null))
                .as("updateAvailable 应渲染 type=boolean")
                .isEqualTo("boolean");
        assertThat(mark.path("description").asText(""))
                .as("updateAvailable 应自描述有新版语义")
                .contains("有新版")
                .contains("显式")
                .contains("null＝未检查过");
        // 命令口径：sourcePackage 是清单行原值回传（admin 不自拼原始 URL——同源身份单源）
        JsonNode sourcePackage = property(skills, "SkillUpdateCommand", "sourcePackage");
        assertThat(sourcePackage.path("type").asText(null))
                .as("sourcePackage 应渲染 type=string")
                .isEqualTo("string");
        assertThat(sourcePackage.path("description").asText(""))
                .as("sourcePackage 应自描述取值口径与显式语义")
                .contains("sourcePackage 原值")
                .contains("永不自动跟新");
        // 更新回执：from/to 版本对（快照锚翻新确认面）＋移除确认名单位
        for (String field : List.of("fromVersion", "toVersion")) {
            JsonNode version = property(skills, "BackofficeSkillUpdateResponse", field);
            assertThat(version.path("type").asText(null))
                    .as("BackofficeSkillUpdateResponse.%s 应渲染 type=string", field)
                    .isEqualTo("string");
            assertThat(version.path("description").asText(""))
                    .as("BackofficeSkillUpdateResponse.%s 应有版本语义说明", field)
                    .isNotBlank();
        }
        assertThat(property(skills, "BackofficeSkillUpdateResponse", "removedSkillNames")
                .path("type").asText(null))
                .as("removedSkillNames 应渲染 type=array")
                .isEqualTo("array");
        // 留痕读面：操作者三件（id/名/时刻）＋版本对——历史版本可查的自描述面
        for (String field : List.of("fromVersion", "toVersion", "operatorId", "operatedAt")) {
            assertThat(property(skills, "BackofficeSkillUpdateTraceResponse", field).isMissingNode())
                    .as("BackofficeSkillUpdateTraceResponse.%s 缺字段（契约漂移）", field)
                    .isFalse();
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
