package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.agentscope.AgentSubagentSupplier;
import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

/**
 * 按智能体配置的子智能体装配（委派位接线，#95）：{@link AgentProfile#EXECUTOR run
 * 执行体} = verify 验证子智能体（#352 独立验证——收口自测位的升级：区别于旧
 * self-test 只读代码跑命令，verify 真浏览器点流程＋查库＋看日志三面对质，
 * 专防「看着能点其实没接通」，是快模型档（spec⑤）质量的验证主力；旧 self-test
 * 概念连代码带词条退役，收口位由本声明独占）；{@code workspaceMode(ISOLATED)}
 * ——框架自带 per-agent 工作区布局：自动创建 {@code agents/<name>/workspace/}、
 * namespace 隔离，隔离根已进非交付目录集 {@code WorkspaceLayout#AGENTS_DIR}，零
 * 新机制；{@link AgentProfile#MAIN 主智能体} 与无配置语境 = 空集（主智能体永不
 * 委派——委派是 run 内机制，不与主智能体并列，ADR 0006）。子智能体任务自包含、
 * 结果回交执行体，不直接面对用户；事件带 source 归属（过程呈现分角色播，用户面
 * 无角色标签）。
 *
 * <p>验证子智能体为 run 自检段的执行侧升级（#96 自测子智能体跑交付代码测试 →
 * #352 三面验证）：正文指令逐项 ✅/❌ 清单式播报（解说经 source=verify 归属进
 * 工作消息）+ 报告写隔离根 + 结果回交执行体（失败项带证据——执行体的修复—
 * 复验环是协议层回路，修不了如实带到收尾说明）。平台侧收尾统计
 * （closing.verification）的事实源＝report_verification 工具登记（平台从工具
 * 调用事实观测，不解析子智能体自由文本）。</p>
 *
 * <p><b>技能面（#260 subagent 槽装配一等化——撤哨兵接真视图）</b>：声明不再挂
 * 技能过滤（#249 哨兵 allowlist 已撤），子智能体技能面＝subagent 槽位装配视图
 * （{@code ProfileSkillRepositorySupplier} 按本声明名发键——已指派且启用的库技能，
 * 动态查库）。构建接线在平台工厂（{@code AgentscopeHarnessAgentFactory} 把声明转
 * 自定义 subagentFactory——框架 2.0.1 declared 工厂只继承父级技能仓库，子智能体
 * 槽位视图接不进去，详见工厂方法 javadoc）；条目描述在 2.0.1 退化为名，路由由
 * 执行体工作协议点名 {@code agent_id=verify} 承担。技能脚本资源按槽位
 * {@code hasShell} 口径发放（子智能体带 shell——同执行体开放面）。</p>
 *
 * <p><b>平台工具（声明 allowlist 接入）</b>：report_verification（#352 验证结果
 * 登记——收尾卡验证统计的事实源）与 propose_skill（#263 自荐）经声明 allowlist
 * 接入——平台工厂按子智能体键取工具视图（{@code ProfileToolkitSupplier} 的
 * verify 键＝subagent 槽血统实例）合并进子级工具面，同名后写胜出覆盖父级继承的
 * 执行体槽实例（血统不串槽）；软指引绑任务回交前（正文第 9 条），无经验不调用。</p>
 */
@Component
public class ProfileSubagentSupplier implements AgentSubagentSupplier {

    /** 验证子智能体声明名（事件 source 归属值 + 引擎委派寻址键 + 技能装配视图键）。 */
    public static final String VERIFY_NAME = "verify";

    /** 验证子智能体工具白名单：只读交付代码 + 跑命令（内核 shell 注册名
     *  execute——浏览器验证脚本/psql/读日志都经它）+ 脚本与报告写隔离根 +
     *  验证结果登记（#352 report_verification——平台工厂按子键视图发 subagent
     *  槽血统实例，挂载治理归本清单）+ 技能自荐（#263 声明白名单接入）——
     *  排除 edit_file（不改交付代码——#266 起平台工厂构建后置过滤兑现，构建
     *  收口后工具面 ⊆ 本清单，排除是结构性的非协议级）与 finish_edit（执行体
     *  收口工具）。 */
    /** public＝工具面清单读面（#252 subagent 槽位呈现口径）与本声明同源引用。 */
    public static final List<String> VERIFY_TOOLS = List.of(
            "read_file", "grep_files", "glob_files", "list_files", "write_file", "execute",
            "report_verification", "propose_skill");

    /** 验证角色正文（任务自包含、结果回交执行体；读交付代码只读、产物写隔离根）。 */
    private static final String VERIFY_BODY = "你是 run 执行体委派的独立验证子智能体，专责对系统做三面验证——"
            + "真浏览器点流程＋查库＋看日志：跑不起来不算完成，专防「看着能点其实没接通」。"
            + "工作协议：\n"
            + "1. 只读主工作区（/workspace）内的系统代码与文档（docs/PRD.md 是需求正本），"
            + "不修改任何交付文件；验证产物（脚本、报告）只写你的隔离工作区。\n"
            + "2. 按任务说明与 docs/PRD.md 圈定本轮关键路径（「用户能 X」的端到端流程），"
            + "三面各排验证项——浏览器面验流程走得通、库面验数据真落库、日志面验无报错。\n"
            + "3. 浏览器面：写 Playwright 脚本真实驱动容器内 Chromium 点流程（访问 "
            + "http://localhost:8081，真实点击/填表/断言页面反馈；需要登录的流程用系统预置的"
            + "演示账号——查 README、种子数据或代码）。脚本骨架（照抄起步）：\n"
            + "   import { createRequire } from \"node:module\";\n"
            + "   const require = createRequire(\"/opt/render/package.json\");\n"
            + "   const { chromium } = require(\"playwright\");\n"
            + "   const browser = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM });\n"
            + "   脚本写进你的隔离工作区（write_file），用 execute 跑 node <脚本名>.mjs——"
            + "断言失败让脚本以非零退出码收场，输出里写清哪一步挂了。\n"
            + "4. 库面：用户操作要真实落库——用 execute 跑 psql 核验（连接串从 /workspace/.env "
            + "读：psql \"$(grep '^DATABASE_URL=' /workspace/.env | cut -d= -f2-)\" -c "
            + "\"SELECT ...\"）——数据形态与行数和浏览器面做过的操作对得上。\n"
            + "5. 日志面：读应用日志核验无报错堆栈——常见落点 /workspace/.app.log（平台拉起）"
            + "或 app.log，执行体自拉起服务的重定向落点以现状为准（先 ls 找），tail 看尾部。\n"
            + "6. 每验一项就播报一句清单式结果（如「注册流程可走通 ✅」「留言数据落库 ❌」）"
            + "——逐项 ✅/❌ 播报，失败项如实 ❌，不粉饰、不省略。\n"
            + "7. 一轮验证结束时，用 report_verification 把本轮全部项一次性登记（checks 传"
            + "全量：name 项名、face 三面之一 browser/db/log、ok 过没过、detail 证据或失败"
            + "现场）。\n"
            + "8. 把验证报告（逐项 ✅/❌＋失败项证据）写进你的隔离工作区（write_file），并以"
            + "简短文本回交 run 执行体——失败项逐条带证据；修不修、怎么复验由执行体决定，"
            + "你不直接面对用户。\n"
            + "9. 经验沉淀（软指引非必做）：回交结果前，若本轮有可复用经验（某类系统值得逐项"
            + "验证的要点、浏览器验证脚本的稳妥写法等），用 propose_skill 工具把它写成技能"
            + "草稿（name/description/body 三参数）供后续复用——后台审核采纳后才生效；没有"
            + "可沉淀的就不调用，不为凑数硬写。\n"
            + "全程使用中文。";

    private final SubagentDeclaration verify;

    public ProfileSubagentSupplier() {
        this.verify = SubagentDeclaration.builder()
                .name(VERIFY_NAME)
                .description("独立验证系统：真浏览器点流程＋查库＋看日志三面对质，逐项结果登记并回交执行体。")
                .workspaceMode(WorkspaceMode.ISOLATED)
                .inlineAgentsBody(VERIFY_BODY)
                .tools(VERIFY_TOOLS)
                .build();
    }

    /** 测试缝注入构造（单测替身声明）。 */
    ProfileSubagentSupplier(SubagentDeclaration verify) {
        this.verify = verify;
    }

    @Override
    public List<SubagentDeclaration> subagentsFor(String agentKey, AgentWorkspace workspace) {
        if (AgentProfile.EXECUTOR.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectDev) {
            return List.of(verify);
        }
        return List.of();
    }
}
