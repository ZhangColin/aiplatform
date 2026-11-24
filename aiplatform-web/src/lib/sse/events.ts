/**
 * SSE 事件名册的手写镜像（判别联合 + 信封解析）。
 *
 * 正本 = aiplatform-server `docs/spec/SSE事件清单.md`（SSE 不进 swagger，这是唯一可行的
 * 类型来源，ADR 0003）。字段形状镜像正本字段表；**每个 SSE 对接 issue 到达时对照正本
 * 更新本文件**，勿凭记忆改。payload 为线上数据，收窄函数只按 type 字符串判别、字段
 * 直接信任转型（同源本地后端）；缺字段的容错呈现归消费端。
 */

/**
 * 单端点单流统一信封：`data = {type, payload, ts}`（正本「信封」节）。
 *
 * 注（ADR-0018，#208 通道按订阅者隔离）：payload 另携 `ownerAccountId` 归属路由
 * 键（两族共有）——服务端据此按「事件归属 == 订阅者」定向投递；**路由键非内容，
 * 前端不消费**（隔离修后只见自己的 accountId），故不在下方各 payload 类型中建模。
 */
export type SseEnvelope = {
  type: string;
  payload: Record<string, unknown>;
  /** ISO-8601；run 级已运行时钟（#225）以 run-start / 收口事件的 ts 为锚，其余消费不依赖，解析时不校验。 */
  ts: string;
};

export function parseSseEnvelope(raw: string): SseEnvelope | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;
  const { type, payload, ts } = parsed as Record<string, unknown>;
  if (typeof type !== "string" || typeof payload !== "object" || payload === null) {
    return null;
  }
  return { type, payload: payload as Record<string, unknown>, ts: typeof ts === "string" ? ts : "" };
}

// ── 平台通知族（单端点单流上的广播族，封闭集合）──────────────────────────────
// 字段表镜像正本「平台通知族」；正本更新时同步改这里。

/** 订单事件载荷（order-status-changed / order-repriced 同形——同字段同量级，不含金额）。 */
type OrderPayload = {
  projectId: string;
  orderId: string;
  status: number;
  statusName: string;
};

export type NotificationEvent =
  | {
      type: "workspace-created";
      payload: {
        projectId: string;
        projectName: string;
        container: string;
        projectType: string;
      };
    }
  | { type: "preview-ready"; payload: { projectId: string; url: string } }
  | { type: "workspace-destroyed"; payload: { projectId: string } }
  | {
      /** 工作区文档产物写出/修订落定；v1 唯一写入方 = 主智能体的 savePrd。 */
      type: "document-updated";
      payload: { projectId: string; documentType: string };
    }
  | {
      /** 异步取名落库成功顶替占位名后发射；失败保占位不发。 */
      type: "project-renamed";
      payload: { projectId: string; projectName: string };
    }
  | {
      /**
       * 订单状态变化（#30）：下单（status=1）/首次报价（2）/取消（5）/支付完成
       * （3）/归档（4）各发一次；改价不换状态、不发本事件（改价信号 =
       * order-repriced）；消费 = 失效重查 + toast 路标（他页弹、组件内跳转；
       * 首次报价在项目页在场不弹——失效水合报价卡入流即感知，#206 分流）。
       */
      type: "order-status-changed";
      payload: OrderPayload;
    }
  | {
      /**
       * 订单已改价（#204 改价入流，推翻「改价不换状态不发」静默）：改价落定后
       * 发射（先落「报价已更新」对话卡后发——信号触发重查时卡已在库）。载荷与
       * order-status-changed 同字段同量级（status 恒 2=已报价），不含金额与备注；
       * 消费 = 失效重查（「报价已更新」卡入流、历史报价卡视镜显新价）+ toast
       * 路标（#206 分流：项目页在场不弹，他页「报价已更新」+ 组件内跳转）。
       */
      type: "order-repriced";
      payload: OrderPayload;
    };

const NOTIFICATION_TYPES: ReadonlySet<string> = new Set([
  "workspace-created",
  "preview-ready",
  "workspace-destroyed",
  "document-updated",
  "project-renamed",
  "order-status-changed",
  "order-repriced",
] satisfies Array<NotificationEvent["type"]>);

/** 通知族为封闭集合：名册外 type → null（消费端按 miss 忽略）。 */
export function asNotificationEvent(envelope: SseEnvelope): NotificationEvent | null {
  return NOTIFICATION_TYPES.has(envelope.type)
    ? (envelope as unknown as NotificationEvent)
    : null;
}

// ── 智能体事件族（断线补发族，#89：重连补断线窗口，新连接不补发）────────────────
// 平台事件 = 封闭集合（字段扁平，下表为准）；引擎透传 = 开放集合，`data` 为
// 引擎 part 原样。退役五族（role-assigned / run-created / run-retrying /
// fix-unchanged / dispatch-stage / live-*）不进名册——旧事件到达按 miss 忽略。
// 呈现面清场（#608，ADR-0057）：part-text / part-check / part-plan 随发射源退役，
// 不进名册（存量事件按 miss 忽略）；部件只余 part-signal 与 part-action。

/** 智能体事件 payload 的公共关联字段：必带 projectId + runId，sessionId 建立后携带。 */
type AgentPayload = {
  projectId: string;
  runId: string;
  sessionId?: string;
};

/** 收口扩载的文件条目（正本 closing.files 元素，#619 起为工作区真实 diff）：
 *  path = 工作区锚定形（{@code /src/App.jsx}），行数为跑前快照基线的真实 ± 行数；
 *  patch = 单文件只读 diff（迭代模式；首建不携）；patchOmitted = patch 超限省略
 *  （卡上标注「diff 过大」，行数仍保留）。 */
export type SseClosingFile = {
  path: string;
  added: number;
  removed: number;
  patch?: string;
  patchOmitted?: boolean;
};

/** 首建目录汇总条目（#620）：目录、文件数、± 行数。 */
export type SseClosingDirectory = {
  path: string;
  fileCount: number;
  added: number;
  removed: number;
};

/**
 * 收口扩载（#617 正本 closing schema，五字段骨架；#619 变更清单改真实 diff；
 * #620 首建目录汇总）：收尾卡的服务端权威事实——内核自述（末次文本，空则缺省）/
 * 判据逐件（build 与预览）/变更清单（工作区真实 diff：跑前快照基线 + 收口
 * numstat；首建另携 directories 目录汇总、迭代携单文件 patch）/用时。预览入口
 * 取既有预览查询，不入载荷；旧平台句式摘要与「需求文档／系统」判定键已退役
 * （旧对话史由读侧 toWorkClosing 回落呈现）。判据与清单以平台可观测事实为准，
 * 不由模型自报。
 */
export type SseClosing = {
  /** 内核自述（markdown 源；内核末次文本为空时缺省）。 */
  narrative?: string;
  criteria: { buildPassed: boolean; previewPassed: boolean };
  files: SseClosingFile[];
  /** 首建目录汇总（#620；迭代缺省——缺省时前端按文件级清单呈现）。 */
  directories?: SseClosingDirectory[];
  /** 变更清单不可用（#619：快照或 diff 执行失败——生成照常成功，卡上标注）。 */
  filesUnavailable?: boolean;
  durationMs: number;
  /** 成版 commit hash（#91；#605 后新载荷不再携带，存量旧卡沿用）——版本控件锚。 */
  version?: string;
};

export type PlatformAgentEvent =
  | {
      /** 运行开始：一场 run 恰一次（#84 静默重试——编码 run 重试不新发，用户面
       * run 身份 = 首试 runId 全程不变）。 */
      type: "run-start";
      payload: AgentPayload & {
        prompt: string;
        model: string;
        engine?: string;
        /**
         * 智能体配置键（引擎信息归一：业务侧 AgentProfile 稳定键——main 主智能体
         * 对话轮 / executor 编码 run；无配置语境的一次性调用不携带）——呈现形态的
         * 登记锚：executor 起工作消息、main 进对话面（#86 单会话收敛后对话只有主
         * 智能体一座）。字面量联合即后端正本键值的镜像（AgentProfile.key()）。
         */
        agent?: "main" | "executor";
        /**
         * 工作消息头部标题扩载（#118）：`title` 为用户语言标题（生成 = 「系统初始化」、
         * 更新 run = 「系统更新」）；切片序号/进度随切片机器退役（#602/#608）；
         * 主智能体对话轮 / 一次性调用不携带（前端回落「正在做」）。
         */
        slice?: { title: string };
      };
    }
  | { type: "error"; payload: AgentPayload & { message: string } }
  | {
      /** 运行结束：到达即真收口（#84——编码 run 在收口判据落定后才发，一场至多
       * 一次、中场无假收口）；挂起轮不发（软终点）。编码 run 真收口携带 `closing`
       * （#617 收口扩载——收尾卡五字段的服务端权威事实，schema 见正本）；主智能体
       * 对话轮（咨询/纯追问）不携带——无收尾卡。 */
      type: "run-finish";
      payload: AgentPayload & { sessionId: string; finish: string; closing?: SseClosing };
    }
  | {
      /**
       * 智能体挂起提问（ask_user，唯一挂起源）：答复续跑归业务编排（问答作答通道，
       * 需求环）。`engineRef` = 引擎侧请求 id（续跑批复的锚）；`data` = 引擎载荷
       * 原样（含前端问答卡投影 `data.questions`），问答卡切片消费。
       */
      type: "question-raised";
      payload: AgentPayload & {
        summary: string;
        engineRef?: string;
        data?: unknown;
      };
    }
  | {
      /**
       * 编码 run 重试超限·终态收口（#56）：轨道层在真终态落定点发射（更新轨道与
       * 终态账同事实点——排队合并续派的中途超限不是终态，不发）；`runId` = 该场
       * run 的用户面标识（首试 runId——#84 静默重试：重试不换新锚、不新发
       * run-start，中间尝试的内部 runId 不出用户面）。恢复出口（继续生成 /
       * 继续更新）只认本事件——run 失败为唯一失败终态，重试全程静默（中间错误
       * 不出用户面）。
       */
      type: "run-failed";
      payload: AgentPayload & {
        /** 用户面失败原因（#355 机制检查·终态零原因补齐）：桥映射的一句人话
         * （末次错误现场定型/截断）；缺省不携带——前端失败档原因行消费。 */
        message?: string;
        /** 用户停止收口标记（#359 生成取消）：true = 本终态由用户主动停止收口
         * （失败账口径）——失败档文案分岔「已停止」（不称中断/失败）；缺省＝
         * 重试超限的真失败。 */
        stopped?: boolean;
      };
    }
  | {
      /**
       * 平台侧定型文案直达对话面（带「平台」标签气泡）。两类使用：兜底轻引导
       * （#47 入口三分类——非意见非咨询输入的零产物路径，本事件即该次派发的
       * 全部）；在途意见回执（#360——首个构建 run 在途时意见轮收口的排队事实
       * 告知，runId 锚该意见轮）。`prompt` 为锚定的用户输入（事件到达重建对话
       * 面；回访经对话史水合——#89）——可缺省（在途回执不携带：用户气泡已由
       * 意见轮自落，不重复重建）；`label` 为呈现标签（「平台」）；`text` 为
       * 定型文案。
       */
      type: "guide-reply";
      payload: AgentPayload & { prompt?: string; label: string; text: string };
    }
  | {
      /**
       * 受理开始（#87 受理动作卡）：受理轮（迭代期意见轮）开场的受理事实——意见
       * 已接住、主智能体正在受理（追问或改 PRD）。对话区受理动作卡的呈现源；受理
       * 落定不出新事件（该轮 run-finish / error 推导）。场景矩阵：咨询轮与纯追问轮
       * （访谈期意见轮）服务端不发。
       */
      type: "acceptance-start";
      payload: AgentPayload;
    }
  | {
      /**
       * 试用拦截卡（#533 计费②门②）：试用项目（迭代期）意见/兜底输入被结构闸
       * 拦下——平台侧卡片（显源池/随卡首次选源/确认扣减翻转/充值引导），非智能体
       * run（本事件即该次派发的全部）；`prompt` 供重建用户气泡（同 guide-reply
       * 语义）；卡可回读面＝GET /projects/{id}/intercept（同形）。被拦输入原文随卡
       * 留存（bil_intercepts 待决行）——确认后按 lane 恢复受理（不重输）。
       */
      type: "intercept-card";
      payload: AgentPayload & {
        /** 留存 id（卡锚）。 */
        interceptId: string;
        /** 被拦车道：OPINION=意见 / FALLBACK=兜底。 */
        lane: string;
        /** 被拦输入原文（卡上引用呈现）。 */
        prompt: string;
        /** 扣减源是否已选定（false＝随卡首次选源「本项目余额／我的账户」）。 */
        sourceSelected: boolean;
        /** 已选定扣减源码位（1=账户余额 2=项目余额；未定为缺省不携带）。 */
        deductionSource?: number;
        /** 源池余额快照（分；池未建不携带——免登录直确认/充值引导的判定素材）。 */
        sourcePoolBalance?: number;
      };
    }
  | {
      /**
       * 余额提醒卡（#537 计费③段间硬门，ADR-0047「段间硬门段中自由」）：付费
       * 项目段边界源池≤0 被拦的平台侧卡——显当前负额＋充值入口（到账自动放行
       * 不手动重试）；被拦输入留存 bil_balance_holds（`holdId` 可空＝run 场拦截
       * 〔生成/修正〕——intent 进程内暂存＋充值回正排空重派，无留存）。
       * 段内绝不检查绝不中断——本卡只在段边界出现。卡可回读面＝GET
       * /projects/{id}/balance-reminder（同形）。
       */
      type: "balance-reminder";
      payload: AgentPayload & {
        /** 留存 id（卡锚；run 场拦截无留存缺省不携带）。 */
        holdId?: string;
        /** 被拦车道：OPINION=意见 / INQUIRY=咨询。 */
        lane?: string;
        /** 被拦输入原文（卡上引用呈现；run 场拦截缺省不携带）。 */
        prompt?: string;
        /** 源池余额快照（分；显负额素材）。 */
        sourcePoolBalance?: number;
      };
    }
  | {
      /**
       * 脱轨信号部件（#240 机器语法吞段的人话留痕）：叙事段守卫识别到机器语法段
       * （模型以原生工具参数语法直接发射、引擎未识别为工具调用——什么都没跑）时
       * 发射，每次吞段一句（天然低频）。载荷只有发生事实：不携带原文（原文出口 =
       * 后端 trace 日志）。呈现 = 活性行脱轨变体（定型文案），静态面无痕、不伪造
       * 动作；`signal` 为封闭词表，v1 唯一值 derailed。
       */
      type: "part-signal";
      payload: AgentPayload & { engine: string; source?: string; signal: "derailed" };
    }
  | {
      /**
       * 工具动作部件（动作卡）：开始/进行中/完成/失败全生命周期——动作一开始即出
       * 事件，同一动作以 `toolCallId` 锚定跨状态更新。`state` ∈ started（参数在途，
       * label 通用对象）/ running（参数落定，label 具体对象）/ completed / failed
       * （动作层状态；run 层唯一失败终态仍是 run-failed）；`label` 无时态（时态由
       * state 表达；execute 的 label = 命令原文首行〔剥壳·截断，#228 直播行动态化〕
       * ——当前动作行逐字渲染成 live tail）。播报工具封闭表：write_file / edit_file
       * / execute（内核 shell 注册名——非 durationBreakdown 的 command 归组键，两者
       * 字面勿混）。
       */
      type: "part-action";
      payload: AgentPayload & {
        engine: string;
        source?: string;
        toolCallId: string;
        toolName: string;
        state: "started" | "running" | "completed" | "failed";
        label: string;
        /**
         * 失败留痕（#229）：仅 failed 携带——错误/stderr 首行截断（与 label 各管
         * 各的截断额度）；失败红行渲染 label＋error，排障不进容器即可初判原因。
         */
        error?: string;
        /**
         * 写文件目标路径（#618 预览跟随）：仅 write_file／edit_file 落定/终态携带
         * ——path 参数原形（相对/锚定两形自选）；命令类与读类不携带，参数在途
         * （started）无路径则缺省。前端纯函数（lib/preview/follow）映射应用路由。
         */
        path?: string;
      };
    };

const PLATFORM_AGENT_TYPES: ReadonlySet<string> = new Set([
  "run-start",
  "error",
  "run-finish",
  "question-raised",
  "run-failed",
  "guide-reply",
  "acceptance-start",
  "intercept-card",
  "balance-reminder",
  "part-signal",
  "part-action",
] satisfies Array<PlatformAgentEvent["type"]>);

const PASSTHROUGH_AGENT_TYPES: ReadonlySet<string> = new Set([
  "text",
  "reasoning",
  "patch",
  "tool",
  "step-start",
  "step-finish",
]);

/** 引擎透传（开放集合）：已知名型 text / reasoning / patch / tool / step-start / step-finish，data = part 原样。 */
export type PassthroughAgentEvent = {
  /** 名册列已知名型，开放集合不限于它们。 */
  type: string;
  /** `source`（#95 委派位）：子智能体转发进父流的事件带子智能体名，执行体缺省。 */
  payload: AgentPayload & { source?: string; data: unknown };
};

/**
 * 智能体事件族收窄（两个函数而非一个判别联合）：透传 `type: string` 若并入联合会与
 * 平台事件字面量重叠、破坏 switch 收窄，故平台 / 透传各自收窄。透传是开放
 * 集合——带 `data` 的未知 type 同样收窄为透传，不返回 null。
 */
export function asPlatformAgentEvent(envelope: SseEnvelope): PlatformAgentEvent | null {
  return PLATFORM_AGENT_TYPES.has(envelope.type)
    ? (envelope as unknown as PlatformAgentEvent)
    : null;
}

export function asPassthroughAgentEvent(envelope: SseEnvelope): PassthroughAgentEvent | null {
  if (PLATFORM_AGENT_TYPES.has(envelope.type)) return null;
  // 已知名型缺 data 也照收（字段表初版，防漂移丢事件）；未知 type 以携带 data 为准
  return PASSTHROUGH_AGENT_TYPES.has(envelope.type) || "data" in envelope.payload
    ? (envelope as unknown as PassthroughAgentEvent)
    : null;
}
