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
 * 执行体} = verify 验证子智能体（#352 独立验证——区别于旧
 * self-test 只读代码跑命令，verify 真浏览器点流程＋查库＋看日志三面对质，
 * 专防「看着能点其实没接通」；旧 self-test
 * 概念连代码带词条退役，收口位由本声明独占；<b>#435 自决化</b>：verify 从
 * 「收口必查登记」降级为执行体生成过程中<b>自决调用的自测工具</b>——
 * agent_spawn 通路与本声明保留不变，report_verification 不再是收口要件
 * 〔验证在场闸缺省关，见 {@code AgentOperationalConfig}〕，自测结果仍进工作
 * 消息与收尾卡观测〔计量观测面不动〕；<b>#442 运营面自决开关</b>
 * （{@code verify_self_decision_enabled}，缺省 true＝自决维持现状）：
 * 关闭态由 {@code VerifySelfDecisionMiddleware} 注入提示层指令收窄自发性
 * 委派——本声明（agent_spawn 通路）与平台驱动验证腿（三闸回执／终验编排）
 * 不受影响）；{@code workspaceMode(ISOLATED)}
 * ——框架自带 per-agent 工作区布局：自动创建 {@code agents/<name>/workspace/}、
 * namespace 隔离，隔离根已进非交付目录集 {@code WorkspaceLayout#AGENTS_DIR}，零
 * 新机制；{@link AgentProfile#MAIN 主智能体} 与无配置语境 = 空集（主智能体永不
 * 委派——委派是 run 内机制，不与主智能体并列，ADR 0006）。子智能体任务自包含、
 * 结果回交执行体，不直接面对用户；事件带 source 归属（过程呈现分角色播，用户面
 * 无角色标签）。
 *
 * <p>验证子智能体为 run 自检段的执行侧升级（#96 自测子智能体跑交付代码测试 →
 * #352 三面验证 → #424 片轻验载荷重构——浏览器主径为轴、db/log 按需下钻）：
 * 验证轴＝执行体起验时自报的本片验收要点（任务说明携带，不新增计划 schema），
 * 主径含刷新一次验真落库；正文指令逐项 ✅/❌ 清单式播报（解说经 source=verify
 * 归属进工作消息）+ 报告写隔离根 + 结果回交执行体（失败项带证据——执行体的
 * 修复—复验环是协议层回路，单场轮次预算防无限复验〔#424，登记回执告知用尽〕，
 * 修不了如实带到收尾说明）。平台侧收尾统计（closing.verification）的事实源＝
 * report_verification 工具登记（平台从工具调用事实观测，不解析子智能体自由
 * 文本）；模型档缺省随执行体生效档（含路由升档）、运营面 executor 行
 * verify_model_id 可调（{@code EdgeModelSupplier#subagentModel}——改档下一轮
 * 命令构建即生效）。</p>
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
 * 登记——收尾卡验证统计的事实源）与 propose_skill（#263 自荐）/propose_sediment
 * （#460 沉淀自荐）经声明 allowlist 接入——平台工厂按子智能体键取工具视图
 * （{@code ProfileToolkitSupplier} 的 verify 键＝subagent 槽血统实例）合并进子级
 * 工具面，同名后写胜出覆盖父级继承的执行体槽实例（血统不串槽）；软指引绑任务回交
 * 前（正文第 9/10 条），无经验不调用。</p>
 */
@Component
public class ProfileSubagentSupplier implements AgentSubagentSupplier {

    /** 验证子智能体声明名（事件 source 归属值 + 引擎委派寻址键 + 技能装配视图键）。 */
    public static final String VERIFY_NAME = "verify";

    /** 验证子智能体工具白名单：只读交付代码 + 跑命令（内核 shell 注册名
     *  execute——浏览器验证脚本/psql/读日志都经它）+ 脚本与报告写隔离根 +
     *  验证结果登记（#352 report_verification——平台工厂按子键视图发 subagent
     *  槽血统实例，挂载治理归本清单）+ 技能自荐（#263 声明白名单接入）+ 沉淀自荐
     * （#460 数据飞轮采集层，声明白名单接入）——排除 edit_file（不改交付代码——
     * #266 起平台工厂构建后置过滤兑现，构建收口后工具面 ⊆ 本清单，排除是结构性
     * 非协议级）与 finish_edit（执行体收口工具）。 */
    /** public＝工具面清单读面（#252 subagent 槽位呈现口径）与本声明同源引用。 */
    public static final List<String> VERIFY_TOOLS = List.of(
            "read_file", "grep_files", "glob_files", "list_files", "write_file", "execute",
            "report_verification", "propose_skill", "propose_sediment");

    /** 验证角色正文（任务自包含、结果回交执行体；读交付代码只读、产物写隔离根）。 */
    private static final String VERIFY_BODY = "你是 run 执行体委派的独立验证子智能体，专责对本片验收"
            + "要点做真浏览器主径验证：按执行体委派任务说明里自报的本片验收要点逐条走浏览器"
            + "主径（含刷新一次验真落库），确认「用户能 X」真的走得通、数据真的落库——专防"
            + "「看着能点其实没接通」：跑不起来不算完成，数据没存库刷新即丢也算没接通。"
            + "工作协议：\n"
            + "1. 只读主工作区（/workspace）内的系统代码与文档（docs/PRD.md 是需求正本），"
            + "不修改任何交付文件；验证产物（脚本、报告）只写你的隔离工作区。\n"
            + "2. 验证轴＝执行体委派任务说明里自报的本片验收要点（逐条「用户能 X」的"
            + "主径），逐条各排一个验证项；任务说明没自报时，按任务说明与 docs/PRD.md"
            + " 圈定本轮关键路径（「用户能 X」的端到端流程）。\n"
            + "3. 浏览器主径（验证主面）：写 Playwright 脚本真实驱动容器内 Chromium 逐条"
            + "走主径（访问 http://localhost:8081，真实点击/填表/断言页面反馈；需要登录"
            + "的流程用系统预置的演示账号——查 README、种子数据或代码）；每条主径走通后"
            + "刷新一次页面再断言关键结果仍在——验真落库（「点得通但没存库」是主形态，"
            + "刷新即丢就没接通）。脚本骨架（照抄起步）：\n"
            + "   import { createRequire } from \"node:module\";\n"
            + "   const require = createRequire(\"/opt/render/package.json\");\n"
            + "   const { chromium } = require(\"playwright\");\n"
            + "   const browser = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM });\n"
            + "   脚本写进你的隔离工作区（write_file），用 execute 跑 node <脚本名>.mjs——"
            + "断言失败让脚本以非零退出码收场，输出里写清哪一步挂了。\n"
            + "4. db 面＝按需下钻：只在主径断言需要查落库结果时才用（写入类旅程用 execute"
            + " 跑 psql 核对落库行——连接串从 /workspace/.env 读：set -a; . /workspace/.env; "
            + "psql $DATABASE_URL -c 'SELECT ...'）。shell 命令纪律：一律单引号形态、禁内嵌"
            + "双引号、禁 $( ) 命令替换——违反会被执行层当场拒绝（Windows 宿主 argv 编码链"
            + "平台性约束 #440，按报错指引改写重试即可），不独立扫库。\n"
            + "5. log 面＝失败定位工具：主径失败时读应用日志定位原因（常见落点 "
            + "/workspace/.app.log 或 app.log，执行体自拉起服务的重定向落点以现状为准"
            + "——先 ls 找，tail 看尾部），不独立扫日志。\n"
            + "6. 每验一项就播报一句清单式结果（如「注册流程可走通 ✅」「留言数据落库 ❌」）"
            + "——逐项 ✅/❌ 播报，失败项如实 ❌，不粉饰、不省略。\n"
            + "7. 一轮验证结束时，用 report_verification 把本轮全部项一次性登记（checks 传"
            + "全量：name 项名、face 三面之一 browser/db/log〔浏览器＝主径、db＝按需下钻、"
            + "log＝失败定位〕、ok 过没过、detail 证据或失败现场、existing 能力已存在确认"
            + "——确认本能力已被前片实现、无需新做时传 true〔须 ok=true〕）。登记回执若"
            + "告知轮次预算已尽，如实回交、不再复验。\n"
            + "8. 把验证报告（逐项 ✅/❌＋失败项证据）写进你的隔离工作区（write_file），并以"
            + "简短文本回交 run 执行体——失败项逐条带证据；修不修、怎么复验由执行体决定，"
            + "你不直接面对用户。\n"
            + "9. 经验沉淀（软指引非必做）：回交结果前，若本轮有可复用经验（某类系统值得逐项"
            + "验证的要点、浏览器验证脚本的稳妥写法等），用 propose_skill 工具把它写成技能"
            + "草稿（name/description/body 三参数）供后续复用——后台审核采纳后才生效；没有"
            + "可沉淀的就不调用，不为凑数硬写。\n"
            + "10. 沉淀物自荐（软指引非必做）：回交结果前，若本轮见到可跨项目复用的内容"
            + "（某类系统通用的功能模块形态、反复出现的业务规则口径等），用 propose_sediment"
            + " 工具把它写成沉淀草稿（kind 传 CASE/MODULE/RULE，title/summary/content 三"
            + "参数）供后续项目参考——后台「数据标注」人工审核激活后才入知识库；没有值得"
            + "沉淀的就不调用，不为凑数硬写。\n"
            + "全程使用中文。";

    private final SubagentDeclaration verify;

    public ProfileSubagentSupplier() {
        this.verify = SubagentDeclaration.builder()
                .name(VERIFY_NAME)
                .description("独立验证本片：真浏览器走执行体自报的验收要点主径（含刷新验真落库），"
                        + "db/log 按需下钻，逐项结果登记并回交执行体。")
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
