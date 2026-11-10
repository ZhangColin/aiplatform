package com.semgravio.aiplatform.business.project.domain.decisionhint;

import java.util.ArrayList;
import java.util.List;

import com.cartisan.core.domain.BaseEnum;

/**
 * 决策时微指令库条目（#437，首批四条——并行工具调用／反 mock 数据／失败止损／
 * 上下文经济性）：{@code key} 是运营配置面（prj_agent_configs.decision_hint_disabled）
 * 寻址用的稳定串；{@code priority} 数字小者先注入（同轮多信号竞争单注入位）；
 * {@code instructionTemplate} 是注入模型的微指令正文（{@code {0}} 占位由理由参数
 * 填充）。库正文在代码正本，运营面只调开关与参数（阈值/上限/禁用集）——正文
 * 不做库内覆盖（微指令措辞迭代走发版，防运营误配乱指令面）。
 *
 * <p>实现 {@link BaseEnum}（架构闸：domain 包枚举统一挂 BaseEnum 自动整型转换）：
 * {@code getCode} 复用优先级序（稳定序即编码，1-4 唯一），{@code getName} 取
 * {@code key}——本枚举不落库列（库内寻址只用 key 串），编码面仅为架构合规。</p>
 */
public enum DecisionHintKind implements BaseEnum<DecisionHintKind> {

    /** 失败止损：同类错误连续 N 次（阈值运营可调，缺省 3）→ 换策略，禁原样重试。 */
    FAILURE_STOP_LOSS(1, "failure_stop_loss",
            "【平台提示】同一操作已连续失败 {0} 次。停止原样重试：先分析失败输出的根因，"
                    + "然后换一个不同的方法（拆小步骤、改命令形态、先查环境与依赖），"
                    + "新策略执行前不要重复同一命令。"),

    /** 反 mock 数据：近期产出出现占位/mock 痕迹 → 演示数据必须真实语义、禁占位。 */
    ANTI_MOCK(2, "anti_mock",
            "【平台提示】近期产出中出现占位/mock 痕迹（{0}）。系统必须真实可用：演示数据"
                    + "要有真实语义（真实词表、真实名称等），界面不得留 lorem/placeholder/"
                    + "TODO 占位——立刻把占位内容替换成真实内容。"),

    /**
     * 上下文经济性：单次超大结果或本段累计成功结果超阈（#443 补累计判据，同条目
     * 双判据）→ 窄读定位、只取必要行段、大文件不反复整读、结论落小笔记。
     */
    CONTEXT_ECONOMY(3, "context_economy",
            "【平台提示】{0}——上下文灌入过大。从现在起改用窄读：先 grep/列目录定位，"
                    + "再只读必要行段；长输出只取关键片段，别把整文件反复读进上下文；"
                    + "已读过的大文件要回查时按关键词定位到行再窄读局部；构建/测试输出"
                    + "只看报错尾段；阶段性结论一句话记进工作笔记或文件头注释，别靠"
                    + "重读大文件恢复记忆。"),

    /** 并行工具调用：连续多步一次只发一个调用 → 无依赖调用同轮并行发起。 */
    PARALLEL_TOOLS(4, "parallel_tools",
            "【平台提示】近 {0} 步每次只发起一个工具调用。无依赖的多个调用（如同时读多个"
                    + "文件、同时建多个互不依赖的文件）应在同一次响应里并行发起，减少往返轮数。");

    private final int priority;
    private final String key;
    private final String instructionTemplate;

    DecisionHintKind(int priority, String key, String instructionTemplate) {
        this.priority = priority;
        this.key = key;
        this.instructionTemplate = instructionTemplate;
    }

    /** 注入优先级（数字小者先注入——单注入位竞争时的排序依据）。 */
    public int priority() {
        return priority;
    }

    /** 运营配置面禁用集寻址键（prj_agent_configs.decision_hint_disabled 逗号串成员）。 */
    public String key() {
        return key;
    }

    /** 注入微指令正文（{@code {0}} 由理由参数填充）。 */
    public String instructionTemplate() {
        return instructionTemplate;
    }

    @Override
    public Integer getCode() {
        return priority;
    }

    @Override
    public String getName() {
        return key;
    }

    /**
     * 按键解析库条目（配置面禁用集解码）：null/未知键返回 null（宽容腿——坏配置
     * 值不炸装配面，等价于该 token 不生效，与工具规格串解码同口径）。
     */
    public static DecisionHintKind byKey(String key) {
        if (key == null) {
            return null;
        }
        for (DecisionHintKind kind : values()) {
            if (kind.key.equals(key.trim())) {
                return kind;
            }
        }
        return null;
    }

    /**
     * 存储形逗号串 → 库条目列表（读侧解码单源）：null/空＝空表；未知 token 静默
     * 丢弃（写口 PRJ_083 已拒，读面宽容不炸装配面）；序随存储串、去重保首。
     * 配置读面（{@code AgentConfigAppService}）与后台响应面
     * （{@code BackofficeAgentConfigResponse}）共用，禁再散弹解码。
     */
    public static List<DecisionHintKind> decode(String keys) {
        if (keys == null || keys.isBlank()) {
            return List.of();
        }
        List<DecisionHintKind> decoded = new ArrayList<>();
        for (String token : keys.split(",")) {
            DecisionHintKind kind = byKey(token);
            if (kind != null && !decoded.contains(kind)) {
                decoded.add(kind);
            }
        }
        return decoded;
    }
}
