package com.semgravio.aiplatform.business.project.domain.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;

/**
 * 平台侧智能体工具面正本（#252，ADR-0021 工具面可观测＋窄幅开关）：按职能槽位
 * （{@link AgentProfile} 稳定键）列平台资产工具——名字即模型可见注册名（与
 * {@code ProfileToolkitSupplier} 装配注册同源，一致性由装配面测试钉死：装配
 * 注册集与枚举该槽位集合必须一致，改一头不改另一头测试即红）。<b>#604 拆
 * run 尝试体旧机制</b>：executor 槽（finish_edit/update_plan/自荐两件/构建轨
 * 出图 gi 件）与 subagent 槽（report_verification）随旧执行协议与子智能体
 * 委派一并退役；<b>#610 设计线整条退役</b>：designer 槽与 generate_image 件
 * 一并拆除——现存槽位＝main（主智能体）。
 *
 * <p><b>骨架 / 增强</b>（ADR-0021：编排权不下放配置）：骨架＝编排链路工具
 * （问答、PRD 落库）＋项目事实只读件——结构性锁死不开放关停（接口层拒绝，
 * PRJ_036）；增强＝联网搜索 / 网页抓取（#252）两件——窄幅可开关（存
 * {@code prj_agent_configs} 开关列，关即退出装配面）。harness 内建编码工具
 * （read_file / execute 等）不属本枚举——呈现口径经 {@code HarnessBuiltinTools}
 * 注册自省，同样不可开关。</p>
 */
public enum AgentTool implements BaseEnum<AgentTool> {

    // ---------- main 槽位（主智能体：需求梳理＋答询＋迭代受理，只读面） ----------

    ASK_USER(1, "ask_user", "main", AgentToolKind.SKELETON,
            "每轮一问的澄清工具（问答挂起的唯一起源——编排链路）"),
    SAVE_PRD(2, "savePrd", "main", AgentToolKind.SKELETON,
            "PRD 产出/修订落盘＋业务登记（需求侧判定的观测面——编排链路）"),
    LIST_WORKSPACE_FILES(4, "list_workspace_files", "main", AgentToolKind.SKELETON,
            "工作区文件清单（答询查证只读件）"),
    READ_WORKSPACE_FILE(5, "read_workspace_file", "main", AgentToolKind.SKELETON,
            "读工作区文件内容（答询查证只读件）"),
    QUERY_PROJECT_FACTS(6, "query_project_facts", "main", AgentToolKind.SKELETON,
            "查项目事实含系统访问地址（答询查证只读件）"),
    FETCH_URL(7, "fetch_url", "main", AgentToolKind.ENHANCEMENT,
            "读用户贴的 http/https 地址内容（外部资料，取数口安全底线兜底——增强可关）"),
    WEB_SEARCH(8, "web_search", "main", AgentToolKind.ENHANCEMENT,
            "自主搜索调研补缺口（增强可关——成本与滥用可控）"),
    PROPOSE_SKILL_MAIN(12, "propose_skill", "main", AgentToolKind.SKELETON,
            "技能草稿自荐（需求侧梳理经验沉淀待审——过程知识沉淀链，#263 三槽位齐开）"),
    PROPOSE_SEDIMENT_MAIN(15, "propose_sediment", "main", AgentToolKind.SKELETON,
            "沉淀物草稿自荐（需求案例/功能模块/业务规则沉淀待审——数据飞轮采集层，#460）");

    private final Integer code;
    /** 模型可见注册名（与装配注册、REST 按名寻址共用）。 */
    private final String name;
    /** 所属职能槽位（AgentProfile 稳定键；清单呈现按名回解本枚举）。 */
    private final String slot;
    private final AgentToolKind kind;
    private final String description;

    AgentTool(Integer code, String name, String slot, AgentToolKind kind, String description) {
        this.code = code;
        this.name = name;
        this.slot = slot;
        this.kind = kind;
        this.description = description;
    }

    /** 框架约定码（BaseEnum 自动转换面；不落库不经 REST——寻址用注册名字符串）。 */
    @Override
    public Integer getCode() {
        return code;
    }

    /** 展示名（BaseEnum 约定面；REST 呈现用注册名，本值即注册名）。 */
    @Override
    public String getName() {
        return name;
    }

    public String toolName() {
        return name;
    }

    public String slot() {
        return slot;
    }

    public AgentToolKind kind() {
        return kind;
    }

    public String description() {
        return description;
    }

    /**
     * 按注册名解析（REST 按名寻址腿；空/未知返回空）。注意：工具开关窄幅写口
     * 须用 {@link #enhancementByName}——骨架件不可开关，开关写口只认增强件。
     */
    public static Optional<AgentTool> byName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(tool -> tool.toolName().equals(toolName.trim()))
                .findFirst();
    }

    /**
     * 按注册名解析<strong>可开关的增强件</strong>（窄幅写口专用）：只返回增强类别
     * 件；无增强件返回声明序首件（交调用方按骨架锁死拒绝——与 byName 同效）。
     */
    public static Optional<AgentTool> enhancementByName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        List<AgentTool> matches = Arrays.stream(values())
                .filter(tool -> tool.toolName().equals(toolName.trim()))
                .toList();
        return matches.stream()
                .filter(tool -> tool.kind() == AgentToolKind.ENHANCEMENT)
                .findFirst()
                .or(() -> matches.stream().findFirst());
    }

    /** 该槽位的平台工具清单（枚举序即呈现序）。 */
    public static List<AgentTool> ofSlot(String slot) {
        return Arrays.stream(values()).filter(tool -> tool.slot().equals(slot)).toList();
    }
}
