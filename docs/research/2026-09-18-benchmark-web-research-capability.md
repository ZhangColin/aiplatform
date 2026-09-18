# 六平台智能体「外部信息获取能力」联网取证（2026-09-18）

> 动因：用户实测贴 GitHub 地址给主智能体做官网，智能体无法读取（#212 的直接起因）。既有两份对标调研（2026-09-03）未覆盖此维度，本报告为补位取证，全部结论带来源 URL，供 #212 及后续 ADR 引用。

## 1. Lovable

- **能力形态**：主 agent 有实时 web search 工具。Agent Mode 发布博客："Search the web in real-time to fetch documentation, content, images, or even screenshots needed to complete a task"（https://lovable.dev/blog/agent-mode-beta）。
- **贴 URL**：docs 的 Build with URL 支持 `html=PAGE_URL` 传 live web page 复刻布局（https://docs.lovable.dev/integrations/build-with-url）。聊天内直接贴 URL 的抓取细节未覆盖。
- **research 形态**：**只读并行研究 subagents**（六家中唯一产品化为独立子智能体）。"Lovable can start temporary, read-only subagents to research, inspect, or review focused parts of the work"；"can run several in parallel"；"They cannot edit, create, delete files"；两类 Generic / Explore（https://docs.lovable.dev/features/subagents）。所有文件改动仍由主 agent 做。
- **安全边界**：云端开发机 50+ 服务出站白名单（⚠️ 页面现 404，仅存缓存证据：https://docs.lovable.dev/more-information/lovable-cloud-egress-allowlist）；connector 通道有网关静态出口 IP、IP allowlisting、domain restrictions（https://docs.lovable.dev/integrations/security）。
- **默认/可配**：Agent Mode beta 需项目设置 opt-in，usage-based 计费；subagents 无开关、平台自主决定（https://docs.lovable.dev/features/plan-mode）。

## 2. Bolt.new

- **能力形态**：官方文档化的 web research 仅在 **Plan Mode**："can pull in real-time, up-to-date information from trusted web sources"，结果在回复顶部展示来源（https://support.bolt.new/best-practices/plan-mode）。Build 模式是否联网未覆盖。
- **research 形态**：模型直调搜索工具，非独立子智能体。2026-09 新增第三 agent Bolt Forge（开源模型，research preview）。
- **安全边界**：代码跑在**用户浏览器里的 WebContainer**，无服务端 egress 概念（https://bolt.new/platform/security）。

## 3. v0.dev（Vercel）

- **能力形态**：双通道——实时 web search（内联带可点击来源）＋ **Browser use**（"visit external URLs to capture visual references or inspect a page's layout before recreating it"）（https://v0.dev/docs/agentic-features）。
- **research 形态**：agent 直调工具＋Marketplace/MCP；权限三档 Ask/Auto/Full。
- **安全边界（六家最完整）**：**Sandbox Network Policy 默认 `allow-all`，Team owner 可配**（https://v0.dev/docs/sandbox）；底层 Vercel Sandbox 防火墙——egress 限制、SNI 域名策略＋CIDR 规则、策略在宿主侧 microVM 之外执行沙箱内改不掉（https://vercel.com/docs/sandbox/concepts/firewall）；2026-08 起下放到 Hobby 免费档。
- **默认/可配**：能力默认开；出网默认 allow-all、可收紧。

## 4. Replit

- **能力形态**：2025-07 官宣 Web Search for Agent（https://replit.com/blog/web-search）。文档三动作：Search / **Content fetching**（"Retrieve detailed information from specific websites and URLs"）/ Source citations（https://docs.replit.com/features/agent/web-search）。
- **research 形态**：Agent 本体内置工具（模型直调）；底层供数是 **Firecrawl**（官方案例含 Replit Staff AI Engineer 引语：https://www.firecrawl.dev/blog/how-replit-uses-firecrawl-to-power-ai-agents）。
- **默认/可配**："built into Agent — there's nothing to toggle on"（文档原文），无档位门控记载。

## 5. 扣子编程（coze.cn）

- **能力形态**：对话区「生成配置」控制是否可调联网搜索等外部工具（https://docs.coze.cn/guides_vibe_coding_environment）；被开发应用侧预置「网页内容读取」技能＋「联网搜索」集成（https://docs.coze.cn/vibe-coding-plugin）。用户贴 URL 让 agent 抓：未覆盖（仅文档化上传文件）。
- **research 形态**：工具开关制——用户开、模型自主调。
- **安全边界**：云端沙箱项目级隔离；出网策略未覆盖。

## 6. Kimi 网页版（kimi.com）

- **能力形态**：Agentic RL 搜索——"端到端自主强化学习架构，让 AI 自主决定何时搜索、调用何种工具"（https://www.kimi.com/help/features/search）。**贴 URL 即抓**："在对话中直接粘贴网址，Kimi 将自动抓取并分析该页面内容"（限制：密码保护/反爬可能失败、动态页只拿初始 HTML）；Kimi Code/Work 另有 WebBridge 浏览器操作能力。
- **research 形态**：RL 训进模型，非外挂子智能体；信源体系 100+ 验证信源＋风险站点过滤。
- **默认/可配**：网页版有联网开关（K3 趋向自主判断取消开关）；**API 侧默认关**（需声明 `$web_search` 内置工具）。

## 六平台对照

| 维度 | Lovable | Bolt.new | v0 | Replit | 扣子编程 | Kimi |
|---|---|---|---|---|---|---|
| web search | ✅ 主 agent | ⚠️ 仅 Plan Mode | ✅ | ✅（Firecrawl 供数） | ✅ 工具开关 | ✅ RL 内生 |
| 贴 URL 抓取 | ✅ Build with URL | 未覆盖 | ✅ browser use | ✅ Content fetching | 未覆盖 | ✅ 粘贴即抓 |
| 谁来抓 | 主 agent＋只读 subagents | 模型直调 | agent 直调（沙箱内） | Agent 本体 | AI 调工具 | 模型原生 |
| research 形态 | **只读子智能体（唯一）** | 无独立 | 无独立 | 无独立 | 无 | RL 内生 |
| egress 管控 | 云端白名单（⚠️404） | 无服务端概念 | **默认 allow-all＋可配** | 未覆盖 | 未覆盖 | 未覆盖 |
| 抓取消毒/防注入 | 未覆盖 | 未覆盖 | 未覆盖 | 未覆盖 | 未覆盖 | 未覆盖（仅信源过滤） |

## 对本平台的映射（#212 决策依据）

1. **基线能力坐实**：六家全有 web search，四家明确支持贴 URL——外部资料能力不是增强。
2. **research 形态选型**：模型直调工具是 4/6 多数派；仅 Lovable 用子智能体（上下文隔离＋并行）。本平台选直挂只读工具，委派保持 run 内机制——与多数派一致且不破自身职责正典。
3. **出网默认态**：v0 的 allow-all＋可配是业界可接受默认；六家无一公开注入消毒——四条安全底线（GET-only/封内网/大小上限/不可信标注）是自主裁决。
4. **实现线索**：Replit 抓取外包 Firecrawl（JS 渲染/反爬无底洞）；本平台纯 HTTP 自建，JS 渲染备案。
