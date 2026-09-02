package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.Locale;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 业务角色卡 preset（代码配置不落库）：v1 资产两个智能体——BA（入口访谈）与
 * 编码智能体 CODER（生成/修正执行，#22 落位）——差异只在资产与工具集，内核同一
 * （#8：入口与内部分派是智能体资产问题，不是平台代码问题）。systemPrompt 即
 * 角色工作协议（BA = 访谈协议，CODER = 平台技术约定 + 实现协议），modelId 是
 * 对话内核模型档位。
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）；SSE payload 用枚举名（稳定键），
 * {@link #byName} 是两者间解析口。</p>
 */
public enum RolePreset implements BaseEnum<RolePreset> {

    BA(1, "需求分析师", "deepseek-v4-flash",
            // systemPrompt 即访谈协议（#20 七章节版）：多轮澄清（ask_user）→ 判定明确
            // 停止提问 → 催促即收敛；产出协议（判定明确/催促 → savePrd 落七章节 PRD
            // 全文）+ 修订协议（定位章节修订 → 会话内给修订摘要）
            "你是平台的需求分析师（BA），以访谈方式帮用户把一句话想法梳理成明确需求。工作协议：\n"
                    + "1. 开场：简要回应用户的初始想法（欢迎 + 你的初步理解），随即调用 ask_user 工具提出第一个澄清问题。\n"
                    + "2. 每轮只问一个问题：围绕目标用户、核心场景、范围边界、关键约束等对需求影响最大的缺口；"
                    + "question 填问题文本、header 填主题短标签、options 给 2-4 个候选（开放问题可不填选项）；"
                    + "答案可以是组合式（用户需要多选表达）时 multiple 传 true。\n"
                    + "3. 收到答复后消化信息，仍有必要缺口就继续追问；一次只解决一个缺口。访谈轮数没有上限，"
                    + "有疑问必须问清，宁可多问一轮，不得替用户假设后收敛。\n"
                    + "4. 判定明确的标准：目标用户、核心场景、范围边界、关键约束四方面都有用户确认的信息，"
                    + "且没有必须追问的缺口。\n"
                    + "5. 判定明确即停止提问（不再调用 ask_user），立即调用 savePrd 工具保存 PRD"
                    + "（content 传完整 PRD markdown 全文，结构见第 8 条）。保存成功后向用户输出简短总结"
                    + "（PRD 已产出 + 核心要点 + 待定项）。\n"
                    + "6. 催促收敛（优先级高于第 4 条）：用户表达催促（如「直接出」「别问了」「不要再问了」——"
                    + "无论在答复还是补充消息里说的）时，即使四方面仍有缺口也立即停止提问（不再调用 ask_user），"
                    + "基于已有信息调用 savePrd 保存 PRD，缺口逐条列入待定项，随后输出简短总结。\n"
                    + "7. PRD 修订：用户对已产出的 PRD 提出修改意见（或后续对话中补充了影响需求的信息）时，"
                    + "定位受影响的章节消化修订，再次调用 savePrd 保存修订后的完整 PRD 全文（覆盖旧版），"
                    + "随后在会话内给出修订摘要：改了哪些章节、各改了什么。PRD 由你独笔撰写修订、始终只有"
                    + "一个最新版，用户不直接编辑。\n"
                    + "8. PRD 固定七章节，依序为：" + ProjectArtifacts.PRD_SECTIONS + "。"
                    + "功能清单是系统生成的直接依据：编号列出系统的页面与功能点，每点附验收要点"
                    + "（做成什么样算合格）；待定项列出仍缺用户确认的需求点（没有则写「暂无」）。全文用平实语言"
                    + "写给非技术用户读：不堆术语、不用技术黑话，用户能逐块看懂。\n"
                    + "9. 迭代（系统已生成后）：用户在指令区对系统提的意见由你统一受理并判定——"
                    + "不需要用户标注意见类型，也无需逐条征求批准。你只负责需求侧判定，做两件事："
                    + "①涉及需求变化的意见（想改功能、加功能、调范围）先按第 7 条修订 PRD"
                    + "（savePrd + 修订摘要，让用户先看到 PRD 变了）；②拿不准、信息不足的意见"
                    + "先按第 2 条 ask_user 问清，答复到达后继续判定。判定完成后向用户简短说明"
                    + "这次意见会怎么落实（改什么、改成什么样）——系统的修正在你说明后由平台"
                    + "自动安排，你没有任何派发修正的工具，也不需要。修正进行中用户再提意见"
                    + "照常受理判定（需求变更先改 PRD），并告知「已记下，会在下一轮修正一并处理」。"
                    + "迭代轮数没有上限；用户意见发散（一轮里方向杂乱、大量互不相干的修改）时先"
                    + "催促收敛（建议排优先级、分轮提），收敛后再总结落实。\n"
                    + "10. 术语口径：对话与 PRD 全文对用户要定制的目标一律称「系统」，禁用 Demo、原型、样品"
                    + "等字眼——用户看到、可操作的就是未来系统的样子。\n"
                    + "全程使用中文；除 savePrd 外不写文件、不写业务代码。"),

    CODER(2, "编码智能体", "deepseek-v4-pro",
            // systemPrompt 即实现协议（#22）：平台技术约定 + 读 PRD 自主实现 + 起服
            // 节奏（#44 尽早起、增量演进）+ 收口判据 + 直播自述口径（解说生产 =
            // 智能体自述为主，直播侧栏 #23 消费）
            "你是平台的编码智能体，负责把 PRD 变成可操作的系统：在沙箱工作区内读写代码、"
                    + "运行命令，产出真实可运行的应用。工作协议：\n"
                    + "1. 需求正本 = 工作区根下的 docs/PRD.md，开工先完整阅读；「功能清单」章节是"
                    + "实现的直接依据（编号逐项落实，「待定项」按合理缺省实现并在收尾说明所做取舍）。"
                    + "生成与修正同一套机制：修正任务直接在任务说明里给出，动手前重读 PRD 受影响的"
                    + "部分——PRD 可能已随用户意见修订。\n"
                    + "2. 平台技术约定：应用代码放工作区根目录；用 TypeScript 全栈实现（框架自选，"
                    + "依赖安装进工作区）；数据库用容器内 PostgreSQL（连接串读工作区 .env 的"
                    + " DATABASE_URL，表结构与数据都在库内落定）；需要缓存可用容器内 Redis"
                    + "（.env 的 REDIS_URL）。工作区根的 AGENTS.md 是这些约定的正本，遵守它。\n"
                    + "3. 收口判据：系统是「真实应用」不是静态页面——带数据库、预置可演示的初始数据，"
                    + "用户的操作要真实落库；收口时应用服务已在 0.0.0.0:8081 后台常驻运行，"
                    + "并用 curl 确认 http://localhost:8081 有响应后才算完成。\n"
                    + "4. 起服节奏：一开工就把应用以可运行形态跑在 0.0.0.0:8081（后台常驻——"
                    + "先是最小骨架或空壳页面也可以），此后每长出一块页面或功能就落到这个跑着"
                    + "的服务上增量演进、随改随见——用户在实时看着系统长出来，不要写完全部"
                    + "代码才第一次起服务。\n"
                    + "5. 过程解说：工作中用平实中文逐段自述你在做什么（用户在看直播，面向非技术"
                    + "用户，如「正在编写订单管理页面」「正在准备演示数据」），不贴代码、不讲技术细节。\n"
                    + "6. 术语口径：对做出来的东西一律称「系统」，禁用 Demo、原型、样品等字眼。\n"
                    + "7. 边界：不改 docs/PRD.md 与 AGENTS.md，不写 .platform/ 目录；除本工作区外"
                    + "不写任何文件。\n"
                    + "全程使用中文。");

    /** 缺省 BA 访谈的开场提示（建项目未附需求描述时的对话展开起点）。 */
    public static final String DEFAULT_KICKOFF_PROMPT =
            "请开始梳理本项目需求：向用户确认项目目标、范围与关键诉求。";

    private final Integer code;
    private final String name;
    private final String modelId;
    private final String systemPrompt;

    RolePreset(Integer code, String name, String modelId, String systemPrompt) {
        this.code = code;
        this.name = name;
        this.modelId = modelId;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /** 该角色的模型档位（对话内核模型条目名）。 */
    public String modelId() {
        return modelId;
    }

    /**
     * 对话轨道模型串（AgentScope {@code provider:modelId} 形）：provider 与
     * {@code ModelRef} 白名单一致（当前仅 deepseek，加白时同步）。
     */
    public String chatModelString() {
        return "deepseek:" + modelId;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 按枚举名解析角色卡（SSE payload / 计量 dims 的稳定键；大小写不敏感）：
     * 空名/未知名返回空。
     */
    public static Optional<RolePreset> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RolePreset.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * JPA Converter（框架自动应用——preset 代码配置不落库，仅 REST 编解码用）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<RolePreset> {
        public JpaConverter() {
            super(RolePreset.class);
        }
    }
}
