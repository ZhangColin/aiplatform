package com.aieducenter.aiplatform.base.skills.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.skills.application.dto.command.SkillSlotAssignCommand;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillPrecheckResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillPrecheckResponse.PrecheckHint;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.error.SkillMessage;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.port.SlotProtocolSource;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

import com.cartisan.core.exception.ApplicationException;

/**
 * 指派双头预检用例（#255，#254 spec／#245 grill 收口决议）：指派确认前对某槽位
 * 候选集做方法论重叠判定——对照<b>双头</b>（该槽位生效工作协议〔智能体运营配置
 * 生效值——经 {@link SlotProtocolSource} 反转读 business 解析单点，库值优先缺省
 * 回落〕＋该槽位已指派启用技能＋候选集内其他技能），返回<b>非阻断提示</b>
 * （#254：提示是输入不是门，判定权留后台管理员）。
 *
 * <p><b>判定引擎＝classify 先例形态的一次性会话</b>（不抽公共「一次性判定」组件
 * ——三个调用点形状各异，防投机性复杂度）：空 sink（无 SSE 事件）、短超时 15s、
 * 专用会话前缀 {@code skillcheck-}（每次预检独立会话，不承上下文）、专用 flash
 * 档模型配置键（{@code app.skills.precheck-model}，代码保证缺省——不吃
 * agentscope 缺省模型的部署配法）、专用计量标记 agentKind=skillcheck（平台级
 * 归属桶 subject=skills——预检无项目语境）。系统提示词要求结构化输出，容错解析，
 * 不可解析按失败降级。
 *
 * <p><b>失败/超时降级＝如实标注「未执行」</b>：与「无重叠」在回执上必须可区分
 * （防降级伪装成安全），且不拦后续指派；预检全程只读（不落库、不留痕、不改
 * 指派），指派端点本身语义不动。subagent 槽无运营配置正本——对照＝已指派＋
 * 候选集（无协议面），机制同一不特判。
 */
@Service
public class BackofficeSkillPrecheckAppService {

    private static final Logger logger = LoggerFactory.getLogger(BackofficeSkillPrecheckAppService.class);

    /** 预检一次性会话标识前缀（每次预检独立会话——判定不承上下文）。 */
    static final String SESSION_PREFIX = "skillcheck-";

    /**
     * 预检超时（REST 路径上的硬界，对齐 classify 15s 量级）：结构化小判定秒级
     * 可成；超时即降级「未执行」，不让预检请求无限等待。
     */
    private static final Duration PRECHECK_TIMEOUT = Duration.ofSeconds(15);

    /** 预检计量用途标记（一次性辅助面，非登记配置——读侧落「—」桶，classify 同款）。 */
    static final String AGENT_KIND = "skillcheck";

    /** 预检计量归属（平台级不透明桶——预检无项目语境，底座不解释）。 */
    private static final String METERING_SUBJECT = "skills";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 判定协议：只输出一个 JSON 对象（结构化重叠提示），不解释不用代码块。 */
    private static final String PRECHECK_SYSTEM_PROMPT =
            "你是平台技能指派预检器：对照给定材料，判定候选指派技能与该槽位其他方法论"
                    + "载体之间的方法论重叠，产出结构化提示。判定口径：\n"
                    + "1. 方法论重叠＝多份材料对同一类工作教了重复或冲突的做法（如两个技能"
                    + "各自定义一套测试先行流程、技能与工作协议各自规定一套计划管理方式）；"
                    + "分工互补（一个管需求梳理、一个管代码审查）不算重叠。\n"
                    + "2. 对照面：该槽位生效工作协议、该槽位已指派的启用技能、候选集内的"
                    + "其他技能——候选技能与任一面重叠都算。\n"
                    + "3. 每处重叠一条提示：skills 填涉及的候选技能名；counterpart 填对照侧"
                    + "——与工作协议重叠填「工作协议」，与技能重叠填该技能名；overlap 用"
                    + "一句中文说明重叠在哪；resolution 给消解方向（如：二选一保留其一、"
                    + "收窄技能适用范围、可不指派）。\n"
                    + "4. 无重叠输出 {\"overlaps\":[]}。只输出一个 JSON 对象"
                    + "（{\"overlaps\":[{\"skills\":[\"技能名\"],\"counterpart\":\"…\","
                    + "\"overlap\":\"…\",\"resolution\":\"…\"}]}），不加解释、不用 markdown 代码块。";

    private final BackofficeSkillAppService skillAppService;
    private final SkillStore skillStore;
    private final SlotProtocolSource protocolSource;
    private final AgentscopeAgentClient agentClient;
    private final SkillsProperties properties;

    public BackofficeSkillPrecheckAppService(BackofficeSkillAppService skillAppService,
            SkillStore skillStore, SlotProtocolSource protocolSource,
            AgentscopeAgentClient agentClient, SkillsProperties properties) {
        this.skillAppService = skillAppService;
        this.skillStore = skillStore;
        this.protocolSource = protocolSource;
        this.agentClient = agentClient;
        this.properties = properties;
    }

    /**
     * 指派双头预检（只读，非阻断）：槽位解析（SKL_010）→ 候选集解析（与指派 PUT
     * 同源单点——内置柄 SKL_011、未寻址/畸形 TSID 同 404 SKL_001、保序去重）→
     * 组装对照材料（生效工作协议＋已指派启用技能＋候选集）→ 一次性判定调用 →
     * 容错解析。判定失败/超时/不可解析一律降级「未执行」回执（hints 恒空、
     * executed=false），不抛错不拦指派。不裹 {@code @Transactional}：三次材料
     * 读各自成读、无原子性需要，判定调用秒级到 15s——不为其占事务连接。
     *
     * @throws ApplicationException SKL_010 槽位不存在；SKL_001 候选柄未寻址（含
     *                              先卸载后预检的不变窗口）；SKL_011 内置柄不可指派
     */
    public BackofficeSkillPrecheckResponse precheck(String slotKey, SkillSlotAssignCommand command) {
        SkillSlot slot = BackofficeSkillAppService.requireSlot(slotKey);
        List<Long> candidateIds = skillAppService.resolveAssignmentTargets(command);
        List<SkillRecord> candidates = candidateIds.stream().map(this::requireSkill).toList();
        List<SkillRecord> assigned = skillStore.findEnabledAssigned(slot);
        String protocol = protocolSource.effectiveProtocolOf(slot);

        List<PrecheckHint> hints = judge(slot, protocol, assigned, candidates);
        return hints != null
                ? new BackofficeSkillPrecheckResponse(true, hints)
                : BackofficeSkillPrecheckResponse.notExecuted();
    }

    // ---------- 内部 ----------

    /**
     * 一次性判定调用（classify 先例形态）：skillcheck-{runId} 独立会话、专用
     * flash 配置键、空 sink、短超时、计量 agentKind=skillcheck、不触任何工作区。
     * 失败/超时/输出不可解析返回 null（调用方降级「未执行」）。
     */
    private List<PrecheckHint> judge(SkillSlot slot, String protocol,
            List<SkillRecord> assigned, List<SkillRecord> candidates) {
        String runId = EventsAppService.newRunId();
        String sessionId = SESSION_PREFIX + runId;
        AgentCommand command = new AgentCommand(
                runId,
                assemblePrompt(slot, protocol, assigned, candidates),
                PRECHECK_SYSTEM_PROMPT,
                properties.getPrecheckModel(), // 专用配置键（缺省 flash 档）
                sessionId,
                null, // 一次性会话，无 (userId, sessionId) 槽位复用语义
                new UsageContext(METERING_SUBJECT,
                        Map.of(UsageEvent.DIM_KEY_AGENT_KIND, AGENT_KIND)),
                null, // 预检不读写任何工作区
                Map.of(),
                PRECHECK_TIMEOUT,
                null, // 一次性判定无配置语境：空工具面、无规格
                /* workspaceReadOnly= */ false,
                /* workspaceNoShell= */ false,
                /* heading= */ null,
                /* toolSpec= */ null);
        try {
            AgentReply reply = agentClient.converse(command, event -> {
            });
            List<PrecheckHint> parsed = parse(reply.text());
            if (parsed != null) {
                return parsed;
            }
            logger.warn("[skill-precheck] 槽位 {} 预检输出不可解析（降级未执行）：{}",
                    slot.key(), reply.text());
        }
        catch (RuntimeException e) {
            logger.warn("[skill-precheck] 槽位 {} 预检调用失败（降级未执行）：{}",
                    slot.key(), e.toString());
        }
        return null;
    }

    /**
     * 判定材料组装（对照面全量入 prompt——审核面所见即判定面）：槽位职能＋生效
     * 工作协议（无协议面如实标注「无内建工作协议」，subagent 形）＋该槽位已指派
     * 启用技能（description＋正文）＋候选集（description＋正文）。
     */
    private static String assemblePrompt(SkillSlot slot, String protocol,
            List<SkillRecord> assigned, List<SkillRecord> candidates) {
        StringBuilder prompt = new StringBuilder("技能指派预检材料：\n");
        prompt.append("【槽位】").append(slot.key()).append("（").append(slot.role())
                .append("）\n");
        prompt.append("【该槽位生效工作协议】\n")
                .append(protocol != null ? protocol : "（该槽位无内建工作协议）").append("\n");
        prompt.append("【该槽位当前已指派的启用技能】\n");
        appendSkills(prompt, assigned);
        prompt.append("【候选指派集（本次拟指派）】\n");
        appendSkills(prompt, candidates);
        return prompt.toString();
    }

    /** 技能材料段：每件「技能名：description」＋正文全文；空集如实标「（无）」。 */
    private static void appendSkills(StringBuilder prompt, List<SkillRecord> skills) {
        if (skills.isEmpty()) {
            prompt.append("（无）\n");
            return;
        }
        for (SkillRecord skill : skills) {
            prompt.append("- ").append(skill.name()).append("：")
                    .append(skill.description()).append("\n正文：\n")
                    .append(skill.content()).append("\n");
        }
    }

    /**
     * 判定输出解析（容错）：裸 JSON 之外的包裹文本按首尾花括号截取（模型违规加
     * 解释/代码块围栏时仍可解）；{@code overlaps} 数组逐条映射提示。结构不合
     * （无 overlaps、非数组、条目非对象、skills 非字符串数组）视为不可解析返回
     * null（调用方降级「未执行」——classify 同款取舍，宁降级不编造）。
     */
    static List<PrecheckHint> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode overlaps = JSON.readTree(raw.substring(start, end + 1)).get("overlaps");
            if (overlaps == null || !overlaps.isArray()) {
                return null;
            }
            List<PrecheckHint> hints = new ArrayList<>();
            for (JsonNode item : overlaps) {
                if (item == null || !item.isObject()) {
                    return null;
                }
                JsonNode skillsNode = item.get("skills");
                if (skillsNode == null || !skillsNode.isArray()) {
                    return null;
                }
                List<String> skills = new ArrayList<>();
                for (JsonNode name : skillsNode) {
                    if (name == null || !name.isTextual()) {
                        return null;
                    }
                    skills.add(name.asText());
                }
                hints.add(new PrecheckHint(skills,
                        textOrNull(item, "counterpart"),
                        textOrNull(item, "overlap"),
                        textOrNull(item, "resolution")));
            }
            return hints;
        }
        catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 文本字段容错读：缺/ null 节点返回 null，值节点取文本。 */
    private static String textOrNull(JsonNode item, String field) {
        JsonNode node = item.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private SkillRecord requireSkill(long id) {
        SkillRecord record = skillStore.find(id);
        if (record == null) {
            throw new ApplicationException(SkillMessage.SKILL_NOT_FOUND);
        }
        return record;
    }
}
