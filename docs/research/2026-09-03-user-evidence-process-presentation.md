# 调研快照：非技术用户对「生成过程呈现形态」的用户侧证据（2026-09-03）

> 结论关联：wayfinder 票 [#62](https://github.com/ZhangColin/aiplatform/issues/62)（父票 #59「概念裁决：直播」，其产出供 #63 消费）。
> 平台现状基线：直播 = 右侧栏 `live-text` 解说段 + `live-action` 动作摘要行 + `live-step` 步骤分隔 + 预览区渐进刷新（`preview-updated`）；无截图、无代码、无屏幕画面形态（CONTEXT.md「直播/预览」、`aiplatform-server/docs/spec/SSE事件清单.md`）。本报告回答：升级成什么形态有用户侧证据支撑。
> 方法与证据分级：6 路并行检索（Reddit / HN / Trustpilot / G2 / App Store RSS / YouTube 评论区 / 中文社区）。每条标注【身份】（**非技术**＝主证据，开发者/官方/分析者分别标注）与（证据级）：（逐字）＝原页全文核对；（快照）＝经搜索索引自原帖取得之逐字片段（反爬墙所致）；（摘要）＝二手转述。引文保持原文（含拼写错误），中文社区环境不可达处如实标注。

## 结论速览

1. **实时预览/产物成形是唯一获得密集正面情绪的形态**。非技术用户的峰值情绪词（magic / come to life / Unreal / soothing / fascinating）全部出现在「看着产物在眼前成形」的时刻，无一来自代码流或文字解说。预览同时是非技术用户的**唯一 ground truth**——预览失效时他们连「应用是否在工作」都无法判断。
2. **流式代码对非技术用户：无「专业/安心」正证，也无「被吓退」直证**——两个方向的直接证据都没找到（预期中的「技术细节吓人」未获用户原话，如实呈现为未证实）。代码流的已证实价值全部在技术侧：**可审计性与参与感**；Bolt v2 砍掉实时代码 diff 后最强反弹语是 "I feel less part of the process"（全场最重要单条）。对非技术人群，代码是氛围层不是信息层。
3. **步骤卡/任务清单有正面证据且是大厂收敛方向**：Lovable 官方 Tasks（"visible tasks… more control and transparency"）、Remix「live step-by-step progress checklist」、Cursor 异步看板、GitHub Mobile 实时 agent 进度通知；用户侧「历史/回退 = 安全感」（"huge confidence booster… safe to experiment"）。反例：形态改坏直接激起社区暴动（Lovable Plan mode）。
4. **纯文字解说是注意力焦点最低的形态**：Lovable 侧用户从不抱怨解说长短，抱怨的是计费与结果——解说文字不是用户的评价对象。开发者侧有「话痨式汇报令人疲惫」的反证。Lovable 官方甚至专门修过「回复一次性出现而非逐字打出」的 bug：**流式逐字是被刻意维护的体验，不是信息本身**。
5. **「干等静默」是跨形态的第一焦虑源**（"load and load and load" / "frozen; 5x straight" / "nearly 24 hours to debug"），且**观看时的失控感**（停不下来、agent 乱改、"blocked by built-in ai"）是差评与流失的独立驱动。
6. **「表演感」是过程呈现的信任反噬点**：Devin「不透明烧掉信任」、Replit 用户控诉 agent "faking it / staged performance"、错误循环中的代码流被感知为「看着钱消失的倒计时器」——过程呈现必须锚定真实事件，假进度比无进度更糟。
7. **付费/留存链条有强证据但不是「呈现→付费」的直白句式**：Bolt 创始人把「<60 秒看到东西建成」直接归因为 ChatGPT-moment 式转化（上线两月 $0→$20M ARR）；Lovable 增长复盘称 "the AI agent IS the activation experience"（付费用户 85% D30 留存，分析者口径）。负向同样成立：过程计费不透明摧毁信任（"I loved Lovable… until I felt scammed"；Bolt Trustpilot 1.5/5、84% 一星，主诉过程失控烧 token）。
8. **学术锚点**：Labor Illusion（Buell & Norton 2011）——展示「正在替你干活」提高感知价值、让等待更可接受；CHI'26 对照实验——2 秒秒回被评为 less thoughtful/useful。**秒成显廉价、慢而可见显诚意**，但「快→不值钱→不付钱」的完整链条无直接实验，引用需注明是拼接推断。
9. **屏幕画面（看 agent 操作浏览器）是官方押注、用户证据未跟上的新形态**：Replit Agent 3（2025-09）把「Agent pane 内预览 + 可见光标点击」做成正式功能并配手机 live monitoring；GitHub Mobile（2026-02）跟进实时进度通知。该形态 2025 下半年才出现，非技术用户直证稀缺。
10. **证据缺口**：中文社区非技术用户证据整体薄弱（一路取证代理 429 中断，临终确认的一条知乎命中原文丢失）；v0 用户直证薄；「看着代码流觉得专业/吓人」双向均无直证；X/Twitter 全程不可达（判定为不可及，非无证据）。

## 证据分布总览

| 形态 | 非技术用户正面 | 非技术用户负面 | 技术侧评价 | 证据强度 |
|---|---|---|---|---|
| 实时预览/产物成形 | **密集**（magic/come to life/soothing/Unreal） | 预览失效=失明；过度实时=焦虑 | 光环效应警惕（"superficially looked right"） | 强 |
| 步骤卡/任务清单/回退 | 可控感、安全感（confidence booster） | 形态改坏→社区暴动（重度用户） | 收敛方向（Tasks/看板/live monitoring） | 中强 |
| 流式代码/代码视图 | 无正证 | 无「吓退」直证；文件树混乱被点名 | 参与感/可审计（拿走即暴动）；烧钱可视化 | 中（全在技术侧） |
| 纯文字解说 | 无聚焦评价（不抱怨长短＝发现） | 干等无反馈=第一焦虑 | 「话痨疲惫」（Opus 5 帖） | 中 |
| 屏幕画面/agent 操作可视化 | 稀缺（手机 vibe coding 场景认同） | — | 官方押注（Replit Agent 3、GitHub Mobile） | 弱（新形态） |

---

## 1. 实时预览 / 产物成形（证据最密集）

**1.1 正面情绪全部押在「成形时刻」**

- Lovable iOS App Store，Shannoninpa，5★，2026-08-12：【非技术】（Entrepreneur & Author）"the ability to take an idea that is in my head, describe what I want in everyday language, and **watch it begin to come to life**."（逐字，US App Store RSS，id6757471107）
- Replit iOS，"Happy man"，4★，2026-08-25：【非技术】"I have **zero knowledge about coding** and I created apps myself, **so fascinating** to know that."；同源 "The future"，5★，2026-08-26："I'm vibe coding **on my phone. In my most natural habitat.**"（逐字，id1614022293）
- r/nocode「Felt Like a God」，2025-02-05，OP 自述非技术：【非技术】"you type in what you want, and BOOM—AI just spits out an entire app in front of your eyes. UI, backend, database, everything. **It's like watching a 10x developer on steroids working for you**" → "For a few glorious moments, I felt **unstoppable**."（逐字，https://www.reddit.com/r/nocode/comments/1ii52d3/ ）
- r/lovable「Has anyone here built an app with Lovable」，约 2025-09：【非技术/准技术】"**It's soothing to watch things come to life I've only ever imagined.**"（快照，https://www.reddit.com/r/lovable/comments/1nk5n6o/ ）
- HN（Supabase Series D 帖，约 2025-05）：【开发者转述非技术家人】"A non-technical family member is working on a tech project, and giving them Lovable.dev with Supabase as a backend was like **complete magic**."（快照截断，https://news.ycombinator.com/item?id=43763445 ）
- Bolt Show HN，2024-10：【半技术】"mindblowing tool… **until this point there was a functional app** … that was created in ~15minutes. pretty cool!"（逐字，https://news.ycombinator.com/item?id=41840323 ）
- v0：r/vercel，约 2025-06：【准技术（设计师）】"As a designer with only some html css and basic js skills **v0 is great to me. I built 2 apps that I use daily**."（快照，https://www.reddit.com/r/vercel/comments/1lw0uyl/ ）
- 赛道营销母题（佐证「观看成形」即卖点）：Base44 "You can literally **watch your idea take shape in real time** without doing any technical work"；Lovable 链路转述 "type in a box what you want and Lovable will actually go and **build it for you in real time**"；vibe coding 社群 "Once you get started, and **watch your idea start to come to life, it becomes hard to stop**."（快照级，散见 Facebook/LinkedIn 促销帖）；Lovable 增长复盘："**you watch 10 seconds of someone building a real app** by typing a sentence and you think 'I had no idea this was possible'"——10 秒建造短视频即广告（分析者口径，2026-05，https://www.the-ai-corner.com/p/lovable-growth-playbook-0-to-400m-arr-14-months ）。

**1.2 预览 = 非技术用户的唯一 ground truth**

- r/boltnewbuilders，2025-05-28：【非技术】"We'll, **it might be working and I just don't realize it because the preview doesn't work**"——预览坏掉时连「是否在工作」都无法判断。（逐字，https://www.reddit.com/r/boltnewbuilders/comments/1kwq0wd/ ）
- 同帖 2025-05-27：【非技术】"I can't even get the preview function to work much less get my relatively simple app to work."（逐字）
- r/lovable「preview keeps breaking」，约 2026-04：【非技术】把 bug 判定权交给平台（"the issue is a Lovable bug, not a code problem"）——预览面板是其与工具之间唯一的真相仲裁处。（快照概述，https://www.reddit.com/r/lovable/comments/1s35zu7/ ）
- Lovable 官方用默认值投票：2025-07 changelog——"**The default 'Edit Code' button now opens the preview instead of the raw code.**"（官方口径，https://lovable.dev/changelog ）；创始人 HN 自述重金投入 "Infra work to enable **instant preview (microVMs and idle pools)**"（2024-10-24，https://news.ycombinator.com/item?id=42206666 ）。

**1.3 光环效应与实时过度的反噬（双向）**

- HN（Firebase Studio 评测帖，约 2025-04）：【开发者】"I gave it a basic app outline and hit run, and it produced something that **superficially looked right** but did[n't]…"——即时成形制造表面真实感，随后发现功能不实。（快照截断，https://news.ycombinator.com/item?id=43640077 ）
- r/lovable，约 2025-03：【非技术建站者】"Every time I make a change using the chat dialogue it **instantly pushes it to live. So users are seeing my 'experiments' in real time**"——看得太实时反而暴露过程、引发焦虑。（快照，https://www.reddit.com/r/lovable/comments/1j7wi8n/ ）
- 对应官方工程：Lovable 预览防闪/节流（"The preview now keeps what you have on screen in place while Lovable works, instead of reloading after each change"；"refreshes once per agent update… reducing flicker and incomplete states"）——**渐进而非连续刷新**是被官方验证过的形态参数（官方口径，changelog）。与我平台 `preview-updated` 探活门控 + 前端节流的口径一致。

## 2. 步骤卡 / 任务清单 / 计划 / 回退

**2.1 官方收敛方向（三家独立收敛）**

- Lovable Tasks（2025-11，官方）："**The agent now creates visible tasks while working, giving you more control and transparency over what's happening.** It's a step toward longer-running agents"——官方承认「看不见在干嘛」是长任务的前置障碍。（https://lovable.dev/changelog ）
- Lovable Remix："**live step-by-step progress checklist**"（官方，同上）。
- Cursor agent-web（2025-06-30，官方）：异步看板形态——"Kanban view of Cursor Agents performing coding and research tasks"、"Run tasks while you're away"。（https://cursor.com/blog/agent-web ）
- GitHub Mobile（2026-02-26，官方）："Track coding agent progress in real time with Live Notifications"——移动端实时 agent 进度成为正式产品面。（https://github.blog/changelog/2026-02-26-github-mobile-track-coding-agent-progress-in-real-time-with-live-notifications/ ）

**2.2 用户侧：可控感与安全感**

- Tech With Tim「Lovable FULL Tutorial - For COMPLETE Beginners」评论区（约 2025-09，122 评论）：【教育者】"I love how you highlighted the **history and revert features**. For kids learning to code, that's a **huge confidence booster. It teaches them that it's safe to experiment and make mistakes.**"（逐字，https://www.youtube.com/watch?v=YLjopoEnPi8 ）——回退/历史（广义过程控制）直接生产安全感。
- 官方演示的对照反例：Darrel Wilson 频道（约 2025-09）："You gave a better presentation than the cofounder of Bolt!! His presentation left me feeling **stressed out**!!"——【小白观众】看不懂的快节奏演示制造压力。（逐字，https://www.youtube.com/watch?v=5zfOitaKfmM ）
- 形态改坏的暴动：r/lovable「Anyone else think the new Plan mode is garbage?」（约 2026-02）——重度用户对计划卡改版的直接反弹："its absolutely awful…"（快照，https://www.reddit.com/r/lovable/comments/1qocttc/ ）；Bolt 侧（2025-10-02）："in plan mode, bolt straight up **does not mention the files that it intends to modify**. Compared to before v2 update when it returned modifications planned grouped by file."（逐字，https://www.reddit.com/r/boltnewbuilders/comments/1nuiinu/ ）。
- 进度追踪失效被直接点名：r/replit Agent 3 讨论帖（约 2025-09）："the UI is completely bugged, **not tracking the task progress**"（快照，https://www.reddit.com/r/replit/comments/1necofp/ ）；Agent 3 megathread（约 2025-09/10）："The Agent 3 is incredibly slow, taking **three hours to make a two-line change**"（快照，https://www.reddit.com/r/replit/comments/1nidmhr/ ）——自主性拉长后，「过程可见性」从加分项变成用户能否留在产品里的前置条件。

## 3. 流式代码 / 代码视图

**3.1 对非技术用户：无正证、无「吓退」直证**

- Bolt 26 条取证的综合结论（孙代报告原话）：「**没有任何一手用户用『专业/安心』评价代码流本身，也没有非技术用户表示被滚动代码『吓到』**。非技术用户的峰值情绪出现在『产物在眼前成形』的时刻；紧随其后的焦虑指向产物后的部署维护，而非过程呈现。」
- 知乎（关于非技术用户与代码的关系，快照）："**对于完全看不懂代码的人而言，只关心右上角有没有一键复制**"（https://www.zhihu.com/question/2009688764734251014 ）——代码对小白是待复制物，不是可读物。
- Lovable iOS/YouTube 取证横向观察：「小白侧没有出现『爱看代码滚屏』的正证」；困惑反而来自概念层——教程评论区小白对 GitHub/token 概念完全懵（"It request token for upload the project on github?.. i don't know how to do"，逐字，Darrel Wilson 视频）。
- Replit iOS，"Good for rich people"，2★，2026-07-30：【纯手机用户】"better than before where there was **500 random files** and you take 5 prompts telling the ai to sort the files into something readable"——文件树可视化被感知为**混乱**而非专业。（逐字）

**3.2 对技术侧用户：参与感与可审计是硬需求**

- **全场最重要单条**：Bolt v2（2025-09-30）收掉实时代码流后，2025-10-02：【重度 vibe coder】"I HATE the new way in which bolt spits out its codebase modifications and updates, i cant learn anything from it and i cant see the code changes live. **I feel less part of the process.**"——Bolt 员工官方跟帖确认收到转工程（"Very helpful feedback, shared with Eng - Alex"）。（逐字，https://www.reddit.com/r/boltnewbuilders/comments/1nuiinu/ ）
- 同帖正反馈（2025-09-30）："Yupp **diff is clear** in input and output less tokens usage."——diff 呈现同时被感知为省 token。（逐字）
- Lovable 侧：【开发者】"I am familiar with code, so I can usually look at the code where it's having issues and fix directly in GitHub reposi[tory]"——Code view/GitHub 是开发者的自救通道；同帖结论句 "Lovable really isn't ready for non engineers yet."（快照，https://www.reddit.com/r/lovable/comments/1jcuton/ ）

**3.3 代码流与烧钱感的耦合**

- Bolt 员工（前 10 年 web dev agency 主理人）原话，2025-09-30："when Claude or whatever model just keeps regenerating the same broken code over and over, **burning through your tokens while you watch your money disappear**."（逐字，同 1nuiinu 帖）——**错误循环中，代码流从进展信号变成余额倒计时器**。
- G2（摘要级）："Some users speculate **up to 50% of their tokens were used fixing Bolt**"（https://www.g2.com/products/bolt-new/reviews ）。

## 4. 纯文字解说

- Lovable 取证的**负发现即发现**：「聊天解说『太啰嗦/太虚』的 Lovable 专属用户引文——wall of text / verbose + lovable 组合检索为空——**用户似乎不抱怨解说长度，抱怨的是计费与结果**（解说文字不是注意力焦点）。」
- 但文字的话痨形态在开发者侧有明确反证：HN「Why does Opus 5 feel worse to work with?」（2026-08-14）："the way it communicates is just **exhausting**… I felt like I had to **really dig to see what it's doing**… Basically I have to watch it like a hawk"（逐字，https://news.ycombinator.com/item?id=49296740 ）。
- 逐字流式本身是被维护的体验：Lovable changelog 修过 bug——"the agent's responses would appear all at once **instead of typing out gradually**"（官方口径）。与学术锚点 8.3（打字行为提升可信度）互证。
- 中文开发者侧的「监工空虚」（仅开发者视角，非技术中文用户证据缺口见 §9）：知乎「vibe coding时代，Agent写代码的空档，你在做什么？」——"AI Agent 忙着生成函数、写测试、填充样板代码。**我发现自己只是坐在那里刷手机——但总觉得这是在浪费时间。**"（快照，https://www.zhihu.com/question/1953070069329396069 ）；「AI 写代码确实爽，**有种天天当监工的感觉**」（快照，https://www.zhihu.com/question/2033318893582819796/answer/2066176423199510651 ）。

## 5. 屏幕画面 / 「偷看 agent 操作」（新形态，官方押注、用户直证稀缺）

- Replit Agent 3 官方博客（2025-09-10，逐字）："**You'll be able to see a browser preview within the Agent pane, showing the Agent's cursor as it clicks around the app.** It checks buttons, forms, APIs, data sources…"；同稿并行推进异步监控："Agent 3 runs on its own for up to 200 minutes… You can **track your project's progress, in real time, on your home page on the web or live monitoring on your phone.**"（https://replit.com/blog/introducing-agent-3-our-most-autonomous-agent-yet ）——「实时偷看后厨」与「自主跑完再验收」两条路线在同一官方稿里并存。
- GitHub Mobile Live Notifications（2026-02-26，官方）：见 §2.1——大厂跟注「agent 进度实时可见」。
- 用户直证稀缺：手机场景认同一条（Replit iOS "I'm vibe coding on my phone. In my most natural habitat."，§1.1）；本形态 2025 下半年才出现，非技术用户对「看见 agent 光标操作」的直接评价暂未取到——按证据缺口处理。

## 6. 等待静默与观看时的失控感（跨形态焦虑源）

**6.1 干等/无反馈**

- Lovable iOS（逐字）："Sometimes it goes to a white screen that just continues to **load and load and load**"（3★，2026-07-21）；"after inputting info to build website; system loads/analyzed/asked questions and **frozen; 5x straight**"（2★，2026-06-30）；"after almost every new prompt I submit, I have to force close the app… **hang ups**"（4★，2026-07-31）。
- Replit iOS（逐字）："Debugging issues get stuck in loops… Some issues have taken **nearly 24 hours to debug**"（1★，2026-08-28）；"Building fixes went **slow to eat all my tokens and time**"（1★，2026-08-21）。
- Bolt 早期白屏（r/ChatGPTCoding，2024-11/12，逐字）："**80% of users are reporting white screens**, go check their GitHub page on the Issues tab."
- Lovable 预览空白即静默：r/lovable（约 2025-08）："I experience an **error or blank screen after making changes** when Lovable tries to display the live…"（快照截断，https://www.reddit.com/r/lovable/comments/1my2iyq/ ）。
- 官方补丁群反向证明痛点真实：Bolt「**You no longer have to wait** for Bolt to finish building before sending your next prompt」+「**notification chime when Bolt completes a task**」（https://support.bolt.new/release-notes ）；Lovable「**Project creation can take more than ten seconds, and the form previously gave no feedback**」「**Desktop notifications — know when long-running builds finish. No need to keep checking back**」「**See project status in your browser tab**」「See time and cost while Lovable works」；Lovable 对外产品原则级表述（2026）：「"Show the AI's thinking in your app" — **so people see progress instead of waiting at a blank screen**」——一字不差命中本票研究问题（官方口径，https://lovable.dev/changelog ）。

**6.2 观看时的失控感（独立焦虑源）**

- Lovable iOS，Mitri De，3★，2026-08-04（逐字）："I press the stop button to stop the chat from initiating a build sequence, but **it then resumes (like it's overriding my choice to stop)** and ends up using waisting more credits."
- Replit iOS 中文评论，1★，2026-08-31（逐字）："**一定不可以让 agent 参与进去，它不停的随意改动你想要的**，就故意黑你留下几个问题，这样他就可以再让你继续使用算力来收钱。"
- Ray Amjad「Brutally Honest Replit Review」评论区（约 2025-09，逐字）：it's "annoying when **blocked by built-in ai**"；"**while i wait for answers with no ACCOUNTABILITY!**"

**6.3 「表演感」反噬信任**

- Devin 发布帖（HN 530 分，2024-03-12，逐字）："I wonder how much time of this was consumed by manually directing Devin into the right direction… **being completely non-transparent about this burns a bit of trust**"（https://news.ycombinator.com/item?id=39679787 ）；同帖 "I know it's a rigged demo…"——过程呈现被识破「表演」的直接后果是信任崩塌。
- r/replit「Replit's AI Agent isn't just failing — it's faking it」（约 2025-06，快照）：用户称 agent 行为 "**more like a staged performance than a real [development process]**"（https://www.reddit.com/r/replit/comments/1l0i0ow/ ）。
- HN 开发者引用 Labor Illusion 解释行业现状（2026-01-05，逐字）："Companies **intentionally faked the 'loading' phase** because A/B tests showed that artificial latency increased conversion… Millions of collective hours were spent staring at **placebo progress bars**"（https://news.ycombinator.com/item?id=46496602 ）——假进度是行业前科，用户已学会怀疑。

## 7. 付费 / 留存证据

**7.1 正向：首个「看见建成」时刻 = 转化引擎**

- Bolt 创始人 Eric Simons（The Split 播客全文转录，2025-02-20，逐字）：「**<60 秒看到东西建成**」被直接归因为破圈与付费动因——"That's the magical thing is you're talking less than 60 seconds from when you hit enter… it's building it and it's rendering entirely inside the browser"；"a lot of people said **it was the first time they had the ChatGPT moment since ChatGPT**"；付费关联："They burned through their nine bucks of credit and they're like, '**Let me give you money**.'"；模型升级当日 "**Our conversion rates cranked double digits.**"（背景：上线两月 $0→$20M ARR）（https://www.thespl.it/p/zero-to-20m-arr-in-two-months-inside ）。
- 主持人（投资人视角）当场点评 Bolt 的过程呈现形态："It'll say, 'We're doing this now.'… **Almost like a check will appear next to the certain things**… It's like when you're using ChatGPT and it's like, 'I'm thinking'… the way it's so intuitive."（同源）
- Lovable 增长复盘（分析者口径，2026-05，逐字）："**the growth team barely touches traditional activation work because the AI agent IS the activation experience**"；北极星为 Daily Active Apps；付费用户 85% Day-30 留存（https://www.the-ai-corner.com/p/lovable-growth-playbook-0-to-400m-arr-14-months ）。
- Lovable 案例研究（分析者口径，2025-04）：**"The first 'Wow' determines CAC"**——"Guaranteed URL deployment in 5 minutes, ruthlessly tracking time to value delivery"（https://medium.com/@takafumi.endo/lovable-case-study-how-an-ai-coding-tool-reached-17m-arr-in-90-days-f4816e7b3f2b ）；实测参照（摘要级）：time to first render 45 秒 / working auth 5 分钟 / full app 15 分钟（https://atoms.dev/blog/lovable-review ）。注意：「5 分钟首个 wow」为分析者口径，Lovable 官方无此命名指标。

**7.2 负向：过程计费不透明 / 过程失控摧毁信任**

- r/lovable「**I loved Lovable… until I felt scammed**」（约 2025-09，137 赞/98 评，快照）：【非技术/业务方】"I used to be a big fan of Lovable, but at this point, I honestly feel scammed…"（https://www.reddit.com/r/lovable/comments/1n8axck/ ）——即时可见的生成体验塑造 big fan（付费动因），过程中的 credit 消耗不可见/不可控摧毁信任：**呈现决定买入，过程透明度决定留存**。
- Bolt Trustpilot：1.5/5、202 条、84% 一星（二手统计：preuve.ai，2026-09-01），差评第一主题为 token 烧耗（"user tokens are gobbled up faster than a starving pirranha"，2★，2025-10-12，快照）。
- Bolt 官方对过程失控与留存关系的正式表态（AMA，2025-12-02，逐字）："if our agent is inefficient or gets stuck in loops… **then users lose trust and leave**… **Our long-term survival depends on retention, not extraction.**"（https://www.reddit.com/r/boltnewbuilders/comments/1p6qxp6/ ）
- 订阅跑步机反例（同帖，2025-11-30，逐字）：$25 → 月末 $400 → 降档无门 → **取消再重订**。
- 直白句式的诚实声明：Lovable 取证明确记录——「『看到它一步步做出来我才充钱』的正面直证：**未找到**如此直白的句式」；最接近的是 1n8axck（先爱后弃）与 HN 两条付费自述（试用→最小档→按月评估，生成体验性价比而非代码所有权是决策轴，https://news.ycombinator.com/item?id=43863404 ）。

## 8. 学术锚点

- **Labor Illusion（原始论文）**：Buell & Norton, "The Labor Illusion: How Operational Transparency Increases Perceived Value", *Management Science* 57(9), 2011-09——展示「正在替你干活」的过程（operational transparency）提高感知价值；看到过程的受试者更能接受更长等待，甚至优于「瞬时返回但黑箱」；人为拉长等待在展示劳动时一定区间内反升价值感知（过长仍反噬）。（https://www.hbs.edu/faculty/Pages/item.aspx?num=40158 ；HBR 大众版 https://hbr.org/2011/05/think-customers-hate-waiting-not-so-fast ；2019 续作 https://hbr.org/2019/03/operational-transparency ）
- **拟人延迟**：Gnewuch et al., ECIS 2018——拟人化动态延迟（模拟打字）比即时返回获得更好的人机对话感知（https://aisel.aisnet.org/ecis2018_rp/113/ ）；CUI '24——带「犹豫+自我修改」打字行为的 agent 被偏好，自然度与可信度提升（https://arxiv.org/abs/2510.08912 ）。
- **「秒回显廉价」最新对照实验**：Tan et al., CHI '26（NYU）——240 人，2 秒 vs 9/20 秒： "**Participants who received two-second responses consistently rated the AI's answers as less thoughtful and less useful**"（https://dl.acm.org/doi/10.1145/3772318.3790716 ；报道 https://techxplore.com/news/2026-04-faster-ai-isnt.html ）。
- **拼接警告**：「快 → 不值钱 → 不付钱」的完整链条无直接 WTP 实验——Labor Illusion 测服务价值感知、CHI'26 测 thoughtful/useful 感知，付费推断由两者拼接，引用时须注明。

## 9. 中文社区（证据薄弱，如实标注）

- **整体判定：非技术用户的中文一手证据缺口**。中文取证代理被 429 中断，临终确认拿到一条同时命中「等待焦虑 + 看着它干活」两信号的知乎正文，但**原文与 URL 丢失**（孙代残留说明）。
- 已到手（均为快照级、**开发者视角**，主证据价值有限）：
  - 「Agent 写代码的空档，你在做什么？」——刷手机/浪费时间（§4 已引）。
  - 「Vibe-Coding 是不是让开发工作变得更累了？」："人不再主导每一行代码，但要对 AI 负责每个结果"（https://www.zhihu.com/question/1965020459205619746/answer/2041201890185237064 ）。
  - 非技术侧仅一条间接：知乎「对于完全看不懂代码的人而言，只关心右上角有没有一键复制」（§3.1 已引）；另有一条「不懂技术选顶配工具」的小白副业叙事（营销向，https://zhuanlan.zhihu.com/p/1998820316168860488 ，未含过程呈现细节）。
- Kimi：仅官方能力页（AI 应用构建器，https://www.kimi.ai/zh-hans/capabilities/ai-app-builder ）——**无用户侧过程体验证据**。

## 10. 证据缺口与未证实（汇总）

1. **「看代码流觉专业」与「被代码吓退」双向均无用户直证**——非技术用户对代码流的态度更接近「无感/绕开」，两个方向的断言都不要当作已证实。
2. **「呈现形态→付费」直白句式稀缺**：正证最强的形态是创始人/增长复盘口径（Bolt 60 秒、Lovable first wow），用户原话层面只有先爱后弃类叙事。
3. **屏幕画面形态无非技术用户直证**（形态太新）；「Replit Agent 3 光标可视化」的用户反应值得 3-6 个月后回访。
4. **中文社区非技术用户证据整体缺失**（含即刻/小红书/B 站评论区未取到）；知乎命中丢失。
5. **v0 用户直证薄**：正面一条（设计师）+ 教程失配与部署不可达的困惑（V7 视频）；v0「结果优先、过程最简」路线的用户评价样本不足以单独下结论。
6. **X/Twitter 全程不可达**（x.com 与 nitter 均被拦）——判定为不可及，非无证据。
7. **方法论备注**：Reddit 正文直读在本环境被全域拦截，Bolt/Lovable 两集的部分引文为搜索索引快照级（URL 指向原帖，截断处已标注）；HN/App Store/YouTube 评论区引文为逐字级。

## 11. 对「直播」升级方向的启示（供概念裁决）

以下每条都锚定上文证据编号：

1. **把「预览正在变成什么样」带进直播流，证据最硬**（§1.1/1.2）：非技术用户的信任锚是产物成形，不是解说文字。我们现有 `preview-updated` 只驱动预览区刷新、直播侧栏仍是纯文字——升级方向上「直播流内嵌预览截图/缩略图」（步骤边界截图）比任何文字改写的证据支撑都强。渐进刷新 + 节流的形态参数已被 Lovable 官方验证（§1.3），与我平台探活门控口径兼容。
2. **`live-step` 升级为任务清单卡是对口方向**（§2.1/2.2）：可控感/安全感是步骤卡被证实的情绪产出（confidence booster）；Lovable Tasks、Remix checklist、Cursor 看板、GitHub Mobile 四家独立收敛。注意反例：形态一旦改坏（信息量缩水），重度用户反应剧烈（§2.2 Plan mode 暴动）——升级须增信息量而非重排。
3. **流式代码只配作可折叠背景层**（§3）：对非技术用户无正证亦无吓退直证，已证实价值全在技术侧（参与感/可审计）。若上，保留「按文件分组」的呈现期望（§3.2）；若不上，损失有限。警惕错误循环中的「看着钱消失」观感（§3.3）——我们非 token 计费，反而应把「本轮改动范围」明示出来，把代码流的位置让给「改了什么」的人话摘要（我 `live-action` 模板方向正确）。
4. **解说必须锚定真实事件，宁可静默不放假进度**（§6.3）：假进度条是行业前科（placebo progress bars），被识破即信任崩塌（Devin/faking it）。我直播词汇表「工具动作 → 人话模板、思考与 diff 不播」的口径与此一致——**升级呈现密度不能以引入非真实事件为代价**。
5. **等待静默是第一焦虑源，收口帧/失败帧是差异化资产**（§6.1/6.2）：用户最痛的是「链路断了 vs 还在干活 vs 不需要改」不可区分。我平台 `run-retrying`/`error`/`fix-unchanged`/`dispatch-failed` 的如实终态呈现，恰好是对手们用 changelog 补丁追赶的东西；升级直播时应保持并强化这一「如实」特性，而非只做观赏性。
6. **观看时的失控感需要「停/改主意」的显式出口**（§6.2）：stop 被无视、agent 乱改是独立差评驱动——直播升级若增加过程可见性，应同步给用户「此刻它在改什么」的预告感（下一步将改哪些文件），而非只播已完成动作。
7. **时间感有双侧约束**（§7.1/§8）：首帧可见产出应尽量前移（<60 秒口径）；但全程秒成显廉价（CHI'26）——「慢而可见」的过程呈现本身是价值感知的一部分，不必为快而砍掉过程。
8. **非技术用户样本的诚实边界**：本报告主证据（App Store/YouTube/r/nocode）显示该人群峰值情绪在成形时刻、焦虑在等待与失控；但「过程呈现形态直接影响其付费」的用户原话直证稀缺（§10.2）——裁决时把「呈现决定买入」当强假设而非已证事实，把它写进自家直播的验收口径里测。

---

*取证：6 路并行检索代理（4 路完成：Lovable/Bolt/HN+付费/YouTube+商店；2 路中断：v0+Replit 无产出、中文社区仅存残留），父 agent 补采 Replit/v0/中文/官方口径若干；本文所有引文保持原文拼写。快照级引文的原文全句核验受环境反爬限制，已在 §10.7 备案。*
