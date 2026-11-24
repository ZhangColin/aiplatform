import { create } from "zustand";

import { parseQuestion, type RaisedQuestion } from "@/lib/chat/qa";
import { parseAnnotationAttachment, type AnnotationDraft } from "@/lib/preview/annotation";
import { parseImageAttachment } from "@/lib/projects/materials";
import { asRecord } from "@/lib/utils";

/** 随话发出的图片物料条目（#286 回显 chip 用：名 + 工作区路径引用）。 */
export type ChatMaterial = { name: string; path: string };

/**
 * 试用拦截卡事实（#533 计费②门②）：SSE 事件与 GET 读面同形的卡面——被拦输入
 * 引用＋源池显式素材（源未定随卡首次选源）。确认扣减后项目翻转付费，按 lane
 * 恢复受理原输入（不重输）。
 */
export type InterceptCardFact = {
  /** 留存 id（卡锚）。 */
  interceptId: string;
  /** 被拦车道：OPINION=意见 / FALLBACK=兜底。 */
  lane: string;
  /** 被拦输入原文（卡上引用呈现；用户气泡由恢复受理的 run-start/guide-reply 落——
   *  卡不重建气泡，避免与乐观气泡/恢复轮双写）。 */
  prompt: string;
  /** 扣减源是否已选定（false＝随卡首次选源）。 */
  sourceSelected: boolean;
  /** 已选定扣减源码位（1=账户余额 2=项目余额；未定缺省）。 */
  deductionSource?: number;
  /** 源池余额快照（分；池未建缺省）。 */
  sourcePoolBalance?: number;
  /** 拦截卡事件锚。 */
  runId?: string;
};

/**
 * 余额提醒卡事实（#537 计费③段间硬门）：SSE 事件与 GET 读面同形的卡面——被拦
 * 输入引用＋当前负额显式素材。充值到账服务端自动放行（留存认领恢复／run 场
 * intent 排空重派），卡随读面熄灭（无确认动作——区别于拦截卡的「确认扣减」）。
 */
export type BalanceReminderCardFact = {
  /** 留存 id（卡锚；run 场拦截无留存缺省）。 */
  holdId?: string;
  /** 被拦车道：OPINION=意见 / INQUIRY=咨询。 */
  lane?: string;
  /** 被拦输入原文（卡上引用呈现；用户气泡由恢复受理的 run-start 落——卡不
   *  重建气泡，避免双写）。 */
  prompt?: string;
  /** 源池余额快照（分；显负额素材——卡显当前负额）。 */
  sourcePoolBalance?: number;
  /** 余额提醒卡事件锚。 */
  runId?: string;
};

/**
 * 对话面 store（issue #19 需求环①，SSE 相关 store——桥为唯一事件写入方，
 * ADR 0003 状态三分法）：按项目累积对话面（用户发言 / 智能体回复增量 / 问答卡 /
 * 收尾卡 / 平台轻引导 / 受理动作卡），对话史跨 run 常驻。<b>#89 起对话史落库</b>：闭史以 REST 水合为准（hydrate 按库序
 * 应用），live 事件只承载在途增量（重放缓冲降级断线补发——新连接不重放，刷新
 * 重建归水合；作答文本随落库不再「即逝」）。
 *
 * <p><b>对话面 run 判定（#86 单会话收敛后）</b>：run-start 携智能体配置键
 * （agent=main）即登记为对话面 run——界面上只有一个「它」，无角色分支；对话
 * 事件的归属判定一律 runId 锚定（chatRunIds），不看会话前缀（后端 ba-/assist-
 * 派生会话已并入单会话 main-{projectId}，前端无从也无需判定）。编码 run 的
 * 解说不进对话（过程长在工作消息）；编码 run 真收口的<b>收尾卡</b>归对话流
 * （#89：闭史的常驻位——live 到达经 appendClosing、回访经水合，同一 runId
 * 退位去重）。</p>
 *
 * <p><b>无角色标签</b>（#86 终态口径，用户故事「界面上只有一个它」+ ADR 0006）：
 * 智能体话语不带任何署名标签；平台轻引导（guide-reply）自带 label（「平台」，
 * 平台自己说话、非智能体角色）是唯一带标签的对话消息。</p>
 *
 * <p><b>水合合并（#89）</b>：hydrate 增量应用新 run 的库条目——该 run 的 live
 * 片段原位退位（闭史接管，位置不跳）、开放轮（openRunId——进行中/挂起问答的
 * 轮）条目跳过不应用（live 尾巴权威，轮收口后下一次水合接管）。重放幂等沿用：
 * text / 问答 / 失败 / 收尾卡事件按 SSE 事件 id 只收一次。</p>
 */

/** guide-reply 事件缺 label 时的呈现兜底（正本在后端 GUIDE_LABEL）。 */
export const DEFAULT_GUIDE_LABEL = "平台";

/** 收尾卡判据逐件结果（#617）：build 与预览两项（服务端权威值）。 */
export type WorkClosingCriteria = {
  buildPassed: boolean;
  previewPassed: boolean;
};

/**
 * 收尾卡权威事实（#617 五字段骨架；#619 变更清单改真实 diff，#89 起归对话流）：
 * 用时（durationMs）/判据逐件（criteria）/变更清单（files，跑前快照基线的真实
 * ± 行数；迭代携单文件只读 patch、首建只出行数）/内核自述（narrative，内核末次
 * 文本）/预览入口（取既有预览查询，不入载荷）。live 经 run-finish.closing 到达、
 * 回访经对话史水合——同载荷同形；旧载荷（summary/判定键/活动量行数）由读侧
 * 回落呈现（见 toWorkClosing）。
 */
export type WorkClosingFile = {
  path: string;
  added: number;
  removed: number;
  /** 单文件只读 diff 文本（#619 迭代）；首建与超限省略时缺省。 */
  patch?: string;
  /** patch 超限省略（#619）——卡上标注「diff 过大」，行数仍保留。 */
  patchOmitted?: boolean;
};

export type WorkClosing = {
  /** 内核自述（markdown 源；空则卡片不出该块）。 */
  narrative?: string;
  criteria: WorkClosingCriteria;
  files: WorkClosingFile[];
  /** 变更清单不可用（#619：快照或 diff 执行失败——生成照常成功，卡上标注）。 */
  filesUnavailable?: boolean;
  durationMs: number;
  /** 成版 commit hash（#91 收口自动成版回填；#605 后新载荷不再携带，存量旧卡沿用）。 */
  version?: string;
};

/** 对话史条目（#89 水合载荷——GET /projects/{id}/conversation 读面消费口径）。 */
export type HydratedEntry = {
  /** 库写入序（对话序正本）。string——后端全局 Long→String 序列化（防精度丢失）。 */
  id: string;
  kind: "user" | "agent" | "question" | "answer" | "closing" | "guide" | "quote";
  runId?: string | null;
  text?: string | null;
  question?: Record<string, unknown> | null;
  /** 「第 N 问」序号（#538/#549，kind=question；读口按对话史重算——权威于载荷
   *  内嵌的 data.questionOrdinal，存量行自动有号；非问答卡缺省）。 */
  questionOrdinal?: number | null;
  closing?: Record<string, unknown> | null;
  /** 圈注附件（#97 随用户发言落库，JSON 数组；消息回显重建圈注 chip 用）。 */
  attachments?: Record<string, unknown>[] | null;
  /** 报价卡载荷（#203：事件 + 订单引用，不含金额——视镜语义，ADR-0017）。 */
  quote?: Record<string, unknown> | null;
  answered: boolean;
};

export type ChatMessage =
  | {
      /** 用户发言；annotations = 随发言发送的圈注条目（#97 回显 chip）、materials =
       * 图片物料条目（#286 回显 chip——路径引用，取件走 raw 直出）。 */
      kind: "user";
      id: string;
      text: string;
      runId?: string;
      annotations?: AnnotationDraft[];
      materials?: ChatMaterial[];
    }
  | {
      /** 智能体话语与平台轻引导；runId 锚增量合并（同 run 才拼接）。label 仅
       *  平台轻引导携带（「平台」——智能体话语无标签）。 */
      kind: "agent";
      id: string;
      text: string;
      label?: string;
      runId?: string;
    }
  | {
      /**
       * 收尾卡（#88 定格收口，#89 起归对话流常驻）：编码 run 真收口的凝聚物——
       * live 经 run-finish.closing 到达（id = 事件 id，重放去重），回访经对话史
       * 水合（id = 库条目 id，退位合并的锚之一）。「查看当时/回滚到此」版本控件
       * 归版本层（#91）。
       */
      kind: "closing";
      id: string;
      runId?: string;
      closing: WorkClosing;
    }
  | {
      /**
       * 受理动作卡（#87）：受理轮（迭代期意见轮）开场的受理锚——意见已接住、
       * 正在受理（追问或改 PRD 的过程呈现位，衔接更新 run 工作消息）。id = 受理
       * 事件 id（重放去重）；落定（settled）由该轮收口事件推导（run-finish /
       * error），挂起追问不落定（同一受理轮仍在途）。
       */
      kind: "acceptance";
      id: string;
      runId: string;
      settled: boolean;
    }
  | { kind: "error"; id: string; text: string; runId?: string }
  | {
      /**
       * 报价卡（#203 报价感知，ADR-0017 视镜语义）：平台对用户的钱事发言——
       * 载荷仅事件 + 订单引用，金额/备注/状态渲染时取订单当前态（useOrder
       * 详情查询，待报价/已报价态轮询现成），卡不冻结金额；改价追加新卡不改
       * 旧卡（#204 改价入流）。不属任何对话轮（无 runId）——live 到达不经 SSE
       * 载荷，由 order-status-changed / order-repriced 失效对话史重查后水合入流。
       */
      kind: "quote";
      id: string;
      orderId: string;
      /** 事件类型：quoted=报价已出 / repriced=报价已更新（#204）。 */
      event: string;
    }
  | {
      /**
       * 试用拦截卡（#533 计费②门②）：试用项目（迭代期）意见/兜底输入被结构闸
       * 拦下的平台侧卡——live 经 intercept-card 事件到达、回访/重载经 GET 读面
       * （同形，桥外由 hook 同步入流）。一项目一卡：新拦取代旧卡（服务端顶替旧
       * 待决）。settled＝确认扣减落定（卡转只读回执）；关闭＝本地面移除（卡可回：
       * 读面仍在，重载重建）。卡不重建用户气泡——恢复受理时由该轮的
       * run-start / guide-reply 落（原文卡内引用）。
       */
      kind: "intercept";
      id: string;
      card: InterceptCardFact;
      settled: boolean;
    }
  | {
      /**
       * 余额提醒卡（#537 计费③段间硬门）：付费项目段边界源池≤0 被拦的平台侧卡
       * ——live 经 balance-reminder 事件到达、回访/重载经 GET 读面（同形，桥外
       * 由 hook 同步入流）。一项目一卡：新拦取代旧卡（服务端顶替旧待决）；充值
       * 到账自动放行——卡无确认动作，随读面熄灭（读面 null → 本面移除）；中途
       * 关闭＝本地面移除（有留存的卡可回：重载重建；run 场卡一次性呈现）。卡不
       * 重建用户气泡——恢复受理时由该轮 run-start 落（原文卡内引用）。
       */
      kind: "balance-reminder";
      id: string;
      card: BalanceReminderCardFact;
    }
  | (RaisedQuestion & { kind: "question"; answered: boolean });

export type ProjectChat = {
  messages: ChatMessage[];
  /** run-start(agent=main) 登记的对话面 run（用户气泡与对话事件归属的判定锚）。 */
  chatRunIds: string[];
  /** 已折算成对话事件的 run（run-start 重放 / 回声去重锚）。 */
  ingestedRunIds: string[];
  /** 已收事件的 SSE 事件 id（重放去重锚，有界）。 */
  seenEventIds: string[];
  /** 对话轮进行中（run-start / 作答续跑起，问答挂起或收口落）。 */
  turnActive: boolean;
  /** 进行中的对话轮 run（水合合并的开放尾巴锚——挂起问答/流式中；收口即清）。 */
  openRunId?: string;
};

export type ChatState = {
  chats: Record<string, ProjectChat>;
  // ---- SSE 侧（bridge 唯一写入方） ----
  /** run-start(agent=main) 登记对话面 run。 */
  noteChatRun: (projectId: string, runId: string) => void;
  ingestRunStart: (projectId: string, runId: string, prompt?: string) => void;
  appendAgentDelta: (
    projectId: string,
    runId: string | undefined,
    delta: unknown,
    eventId: string,
  ) => void;
  raiseQuestion: (projectId: string, runId: string | undefined, question: RaisedQuestion) => void;
  finishTurn: (projectId: string, runId: string | undefined) => void;
  noteTurnError: (projectId: string, runId: string, message: string, eventId: string) => void;
  /** 收尾卡落对话流（#88 定格收口，#89 归对话流常驻；SSE 事件 id 只收一次）。 */
  appendClosing: (
    projectId: string,
    runId: string,
    closing: WorkClosing,
    eventId: string,
  ) => void;
  /** 受理动作卡落卡（#87；SSE 事件 id 只收一次——先于 run-start 到达，不设 run 登记）。 */
  noteAcceptance: (projectId: string, runId: string, eventId: string) => void;
  /** 受理卡落定（#87：该受理轮收口——run-finish / error；幂等，异 runId 无操作）。 */
  settleAcceptance: (projectId: string, runId: string) => void;
  /** 平台轻引导落对话面（#47 兜底分支；prompt 重建用户气泡，SSE 事件 id 只收一次）。 */
  noteGuideReply: (
    projectId: string,
    runId: string,
    prompt: string | undefined,
    label: string | undefined,
    text: string,
    eventId: string,
  ) => void;
  /** 拦截卡落卡（#533）：SSE 事件（eventId 在场，只收一次）与 GET 读面重建
   *  （messageId 自持）同口——一项目一卡，新卡取代旧卡（待决被顶替）。 */
  noteIntercept: (projectId: string, card: InterceptCardFact, messageId: string,
    eventId?: string) => void;
  /** 拦截卡确认落定（#533：确认扣减成功——卡转只读回执，操作区退场）。 */
  settleIntercept: (projectId: string, interceptId: string) => void;
  /** 拦截卡中途关闭（#533：本地面移除；卡可回——GET 读面仍在、重载重建）。 */
  dismissIntercept: (projectId: string, interceptId: string) => void;
  /** 余额提醒卡落卡（#537）：SSE 事件（eventId 在场，只收一次）与 GET 读面重建
   *  （messageId 自持）同口——一项目一卡，新卡取代旧卡（待决被顶替；run 场卡
   *  无留存——新事件取代旧卡）。 */
  noteBalanceReminder: (projectId: string, card: BalanceReminderCardFact, messageId: string,
    eventId?: string) => void;
  /** 余额提醒卡熄灭（#537：读面 null＝充值回正已放行/试用态——本面移除；或中途
   *  关闭。一项目一卡，幂等）。 */
  dismissBalanceReminder: (projectId: string) => void;
  /** 对话史水合（#89）：库条目增量应用（新 run 原位退位 live 片段，开放轮跳过；
   *  无 run 归属条目——报价卡 #203——恒应用，按库条目 id 原位退位不双卡）。 */
  hydrate: (projectId: string, entries: HydratedEntry[]) => void;
  // ---- 发送侧（hooks） ----
  /** 乐观落用户气泡（返回消息 id；失败经 {@link removeMessage} 撤回）。annotations
   *  = 随发言发送的圈注条目（#97 回显 chip）、materials = 图片物料条目（#286
   *  回显 chip）。 */
  appendUserMessage: (
    projectId: string,
    text: string,
    annotations?: AnnotationDraft[],
    materials?: ChatMaterial[],
  ) => string;
  /** 作答落定：用户气泡 + 问题卡转已答 + 轮进行中。 */
  submitAnswer: (projectId: string, text: string, runId: string) => string;
  /** 发言起轮（智能体将回复；run-start 回声会被去重）。 */
  startTurn: (projectId: string) => void;
  /** 发送失败收轮（无会话锚的落轮口，区别于 SSE 侧 finishTurn 的 runId 判定）。 */
  endTurn: (projectId: string) => void;
  removeMessage: (projectId: string, messageId: string) => void;
  /** 发送失败撤尾卡（#87）：撤回乐观气泡时尾随的未落定受理卡一并撤——受理事件
   *  先于提交失败发出时（REST 500），该轮无 run-finish/error 可落定，随气泡同撤。 */
  removeTrailingAcceptance: (projectId: string) => void;
  /** 作答发送失败：撤回用户气泡 + 问题卡重开。 */
  reopenQuestion: (projectId: string) => void;
  markRunIngested: (projectId: string, runId: string) => void;
};

/** 消息条数软上限（对话史内存有界，库侧全量、前端软切）。 */
const MAX_MESSAGES = 200;
/** run / 事件 id 去重集软上限。 */
const MAX_IDS = 500;

const emptyChat: ProjectChat = {
  messages: [],
  chatRunIds: [],
  ingestedRunIds: [],
  seenEventIds: [],
  turnActive: false,
};

function chatOf(state: ChatState, projectId: string): ProjectChat {
  return state.chats[projectId] ?? emptyChat;
}

function pushCapped(list: string[], id: string): string[] {
  if (list.includes(id)) return list;
  const next = [...list, id];
  return next.length > MAX_IDS ? next.slice(next.length - MAX_IDS) : next;
}

let messageSeq = 0;
function localId(): string {
  messageSeq += 1;
  return `m${messageSeq}`;
}

/** 尾条已呈现同文用户输入（run-start / guide-reply 回声 vs 乐观发送的等价
 *  气泡；#533 拦截卡径——尾条是引用同文的拦截卡时恢复受理轮不再重落气泡：卡
 *  即该次输入的呈现位，乐观气泡已在流上）。 */
function lastPresentsUserText(chat: ProjectChat, text: string): boolean {
  const last = chat.messages[chat.messages.length - 1];
  if (last === undefined) return false;
  if (last.kind === "user") return last.text === text;
  if (last.kind === "intercept") return last.card.prompt === text;
  return false;
}

/** 用户气泡落位（#87）：尾部的同 run 受理卡之前插入——重放重建时受理事件先于
 *  run-start 到达，意见气泡仍落在其受理卡上方（卡承接的是这条意见）。 */
function insertUserMessage(
  chat: ProjectChat,
  runId: string,
  text: string,
): ProjectChat {
  const messages = [...chat.messages];
  let insertAt = messages.length;
  while (insertAt > 0) {
    const prev = messages[insertAt - 1];
    if (prev.kind === "acceptance" && prev.runId === runId) {
      insertAt--;
    } else {
      break;
    }
  }
  messages.splice(insertAt, 0, { kind: "user", id: localId(), text, runId });
  return { ...chat, messages };
}

/** 尾条未锚定的用户气泡补 runId（POST 成功回填——水合退位的合并锚）。 */
function stampTrailingUserRun(chat: ProjectChat, runId: string): ProjectChat {
  const last = chat.messages[chat.messages.length - 1];
  if (last === undefined || last.kind !== "user" || last.runId !== undefined) return chat;
  const messages = chat.messages.slice(0, -1);
  messages.push({ ...last, runId });
  return { ...chat, messages };
}

/**
 * 库条目 → 对话消息（#89 水合转换）：answer 渲染同用户气泡；question 复用
 * question-raised 解析（载荷原样存储——问答卡可重建可作答）；closing 载荷容错
 * 收窄（异常形状视同无卡，不出坏卡）。不可解析条目丢弃（null）。
 */
function hydratedMessage(entry: HydratedEntry): ChatMessage | null {
  const runId = entry.runId ?? undefined;
  switch (entry.kind) {
    case "user":
    case "answer":
      return {
        kind: "user",
        id: `h${entry.id}`,
        text: entry.text ?? "",
        runId,
        annotations: parseHydratedAnnotations(entry.attachments),
        materials: parseHydratedMaterials(entry.attachments),
      };
    case "agent":
      return { kind: "agent", id: `h${entry.id}`, text: entry.text ?? "", runId };
    case "guide":
      return {
        kind: "agent",
        id: `h${entry.id}`,
        text: entry.text ?? "",
        label: DEFAULT_GUIDE_LABEL,
        runId,
      };
    case "closing": {
      const closing = toWorkClosing(entry.closing);
      return closing ? { kind: "closing", id: `h${entry.id}`, runId, closing } : null;
    }
    case "quote": {
      const quote = toQuoteRef(entry.quote);
      return quote ? { kind: "quote", id: `h${entry.id}`, ...quote } : null;
    }
    case "question": {
      const question = parseQuestion(`h${entry.id}`, {
        runId: entry.runId ?? "",
        ...(entry.question as Record<string, unknown> | undefined),
      });
      if (!question) return null;
      // 顶层 questionOrdinal＝读口重算权威（存量行/载荷旧值一律覆盖）；顶层缺省
      // （旧后端/测试桩）回落载荷内嵌值
      const ordinal =
        typeof entry.questionOrdinal === "number" ? entry.questionOrdinal : question.ordinal;
      return {
        ...question,
        kind: "question",
        answered: entry.answered,
        ...(ordinal !== undefined ? { ordinal } : {}),
      };
    }
    default:
      return null;
  }
}

/** 对话史附件数组 → 圈注条目（#97 容错收窄：非圈注/坏形状条目丢弃）。 */
function parseHydratedAnnotations(
  attachments: Record<string, unknown>[] | null | undefined,
): AnnotationDraft[] {
  if (!attachments) return [];
  return attachments.flatMap((raw) => {
    const draft = parseAnnotationAttachment(raw);
    return draft ? [draft] : [];
  });
}

/** 对话史附件数组 → 图片物料条目（#286 容错收窄：非 image 形态/缺路径丢弃）。 */
function parseHydratedMaterials(
  attachments: Record<string, unknown>[] | null | undefined,
): ChatMaterial[] {
  if (!attachments) return [];
  return attachments.flatMap((raw) => {
    const material = parseImageAttachment(raw);
    return material ? [material] : [];
  });
}

/** 末条目即开放轮尾（流式中发言/作答，或挂起未答问答卡）——该 run 的 live 尾巴权威。 */
function isOpenTail(entry: HydratedEntry | undefined): boolean {
  if (!entry?.runId) return false;
  return (
    entry.kind === "user" ||
    entry.kind === "answer" ||
    (entry.kind === "question" && !entry.answered)
  );
}

export const useChatStore = create<ChatState>((set) => ({
  chats: {},

  noteChatRun: (projectId, runId) =>
    updateChat(set, projectId, (chat) =>
      chat.chatRunIds.includes(runId)
        ? chat
        : { ...chat, chatRunIds: pushCapped(chat.chatRunIds, runId) },
    ),

  ingestRunStart: (projectId, runId, prompt) =>
    updateChat(set, projectId, (chat) => {
      if (chat.ingestedRunIds.includes(runId) || !chat.chatRunIds.includes(runId)) return chat;
      const stamped = stampTrailingUserRun(chat, runId);
      const ingested = {
        ...stamped,
        ingestedRunIds: pushCapped(stamped.ingestedRunIds, runId),
        turnActive: true,
        openRunId: runId,
      };
      if (!prompt || lastPresentsUserText(ingested, prompt)) return ingested;
      // 受理事件先于 run-start 到达（#87：服务端守卫后即发）——重放重建时用户
      // 气泡插到本 run 受理卡之前（意见在卡上，卡承接的是这条意见）
      return insertUserMessage(ingested, runId, prompt);
    }),

  appendAgentDelta: (projectId, runId, delta, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 对话事件归属 = runId 锚定（对话面 run 才进对话——编码 run 的解说长在
      // 工作消息）
      if (runId === undefined || !chat.chatRunIds.includes(runId)) return chat;
      if (typeof delta !== "string" || !delta) return chat;
      if (chat.seenEventIds.includes(eventId)) return chat;
      const seen = { ...chat, seenEventIds: pushCapped(chat.seenEventIds, eventId) };
      const last = seen.messages[seen.messages.length - 1];
      // 同 run 的连续增量拼接成一条（runId 锚定——跨 run 交错不互并）
      if (
        last !== undefined &&
        last.kind === "agent" &&
        last.label === undefined &&
        last.runId === runId
      ) {
        const messages = seen.messages.slice(0, -1);
        messages.push({ ...last, text: last.text + delta });
        return { ...seen, messages };
      }
      return appendMessage(seen, { kind: "agent", id: localId(), text: delta, runId });
    }),

  raiseQuestion: (projectId, runId, question) =>
    updateChat(set, projectId, (chat) => {
      if (runId === undefined) return chat;
      // 问答卡只出自主智能体（执行体无 ask_user）——question-raised 即对话面 run 的
      // 充分证据。run-start 可能因连接竞态漏收（SSE 连接晚于 run-start 发射），此时
      // runId 未登记：就地登记不丢卡（水合虽也能补登记，竞态窗口内不兜底）。
      const registered = chat.chatRunIds.includes(runId)
        ? chat
        : { ...chat, chatRunIds: pushCapped(chat.chatRunIds, runId) };
      if (registered.seenEventIds.includes(question.id)) return registered;
      // 水合已建同锚卡（挂起问答卡由库重建）——事件回声不双卡
      const hydrated = registered.messages.some(
        (message) =>
          message.kind === "question" && !message.answered && message.engineRef === question.engineRef,
      );
      if (hydrated) return registered;
      const seen = { ...registered, seenEventIds: pushCapped(registered.seenEventIds, question.id) };
      // 旧未答问题被新问题取代（一轮一问）：转已答不再可交互
      const messages = seen.messages.map((message) =>
        message.kind === "question" && !message.answered
          ? { ...message, answered: true }
          : message,
      );
      return appendMessage(
        { ...seen, messages, turnActive: false, openRunId: runId },
        { ...question, kind: "question", answered: false },
      );
    }),

  finishTurn: (projectId, runId) =>
    updateChat(set, projectId, (chat) =>
      runId !== undefined && chat.chatRunIds.includes(runId)
        ? { ...chat, turnActive: false, openRunId: chat.openRunId === runId ? undefined : chat.openRunId }
        : chat,
    ),

  noteTurnError: (projectId, runId, message, eventId) =>
    updateChat(set, projectId, (chat) => {
      if (!chat.chatRunIds.includes(runId) || chat.seenEventIds.includes(eventId)) return chat;
      return appendMessage(
        {
          ...chat,
          seenEventIds: pushCapped(chat.seenEventIds, eventId),
          turnActive: false,
          openRunId: chat.openRunId === runId ? undefined : chat.openRunId,
        },
        { kind: "error", id: localId(), text: message || "本轮回复失败", runId },
      );
    }),

  appendClosing: (projectId, runId, closing, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 收尾卡（#89 归对话流）：live 到达即常驻（断线补发窗口内重复投递按事件 id
      // 只收一次；水合同 run 块整体接管时原位退位）
      if (chat.seenEventIds.includes(eventId)) return chat;
      if (chat.messages.some((message) => message.kind === "closing" && message.runId === runId)) {
        return chat;
      }
      return appendMessage(
        { ...chat, seenEventIds: pushCapped(chat.seenEventIds, eventId) },
        { kind: "closing", id: eventId, runId, closing },
      );
    }),

  noteGuideReply: (projectId, runId, prompt, label, text, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 平台轻引导（#47 兜底分支）：即时到达即收轮（乐观起轮的对称收口）
      if (chat.seenEventIds.includes(eventId)) return chat;
      const seen = {
        ...chat,
        seenEventIds: pushCapped(chat.seenEventIds, eventId),
        turnActive: false,
      };
      // 重放重建：prompt 落用户气泡（乐观发送已落/拦截卡已引用时尾条同文去重）
      const withUser =
        prompt && !lastPresentsUserText(seen, prompt)
          ? appendMessage(seen, { kind: "user", id: localId(), text: prompt, runId })
          : seen;
      return appendMessage(withUser, {
        kind: "agent",
        id: localId(),
        text,
        label: label || DEFAULT_GUIDE_LABEL,
        runId,
      });
    }),

  noteIntercept: (projectId, card, messageId, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 一项目一卡：同卡幂等（GET 重查/事件回声不双卡），新卡取代旧卡（旧待决
      // 被服务端顶替——本面同步退场，只留最新可确认卡）
      if (chat.messages.some(
          (message) => message.kind === "intercept" && message.card.interceptId === card.interceptId)) {
        return chat;
      }
      if (eventId && chat.seenEventIds.includes(eventId)) return chat;
      const base: ProjectChat = {
        ...chat,
        messages: chat.messages.filter((message) => message.kind !== "intercept"),
      };
      const seen = eventId
        ? { ...base, seenEventIds: pushCapped(base.seenEventIds, eventId) }
        : base;
      // 拦下即收口（乐观起轮的对称收轮，同 guide-reply）
      return appendMessage({ ...seen, turnActive: false }, {
        kind: "intercept",
        id: eventId ?? messageId,
        card,
        settled: false,
      });
    }),

  settleIntercept: (projectId, interceptId) =>
    updateChat(set, projectId, (chat) => {
      const target = chat.messages.find(
        (message): message is Extract<ChatMessage, { kind: "intercept" }> =>
          message.kind === "intercept" && message.card.interceptId === interceptId);
      if (!target || target.settled) return chat; // 无此卡或已落定——幂等
      const messages = chat.messages.map((message) =>
        message === target ? { ...target, settled: true } : message);
      return { ...chat, messages };
    }),

  dismissIntercept: (projectId, interceptId) =>
    updateChat(set, projectId, (chat) => ({
      ...chat,
      messages: chat.messages.filter(
        (message) =>
          !(message.kind === "intercept" && message.card.interceptId === interceptId)),
    })),

  noteBalanceReminder: (projectId, card, messageId, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 一项目一卡：同留存幂等（GET 重查/事件回声不双卡）；run 场卡无留存——事件
      // id 去重后新卡取代旧卡（服务端暂存顶替口径同构）
      if (card.holdId && chat.messages.some(
          (message) => message.kind === "balance-reminder"
            && message.card.holdId === card.holdId)) {
        return chat;
      }
      if (eventId && chat.seenEventIds.includes(eventId)) return chat;
      const base: ProjectChat = {
        ...chat,
        messages: chat.messages.filter((message) => message.kind !== "balance-reminder"),
      };
      const seen = eventId
        ? { ...base, seenEventIds: pushCapped(base.seenEventIds, eventId) }
        : base;
      // 拦下即收口（同拦截卡——受理前的拦下不产生在途轮）
      return appendMessage({ ...seen, turnActive: false }, {
        kind: "balance-reminder",
        id: eventId ?? messageId,
        card,
      });
    }),

  dismissBalanceReminder: (projectId) =>
    updateChat(set, projectId, (chat) => ({
      ...chat,
      messages: chat.messages.filter((message) => message.kind !== "balance-reminder"),
    })),

  hydrate: (projectId, entries) =>
    updateChat(set, projectId, (chat) => {
      if (entries.length === 0) return chat;
      // 开放轮（live 尾巴权威）的 run 条目跳过；无 run 归属的条目（报价卡 #203——
      // 平台对用户的独立发言，不属任何轮）恒应用；其余 run 的库块整体接管
      const applied = entries.filter((entry) =>
        entry.runId ? entry.runId !== chat.openRunId : true,
      );
      const runIds = [...new Set(applied.flatMap((entry) => (entry.runId ? [entry.runId] : [])))];
      // 无 run 条目的退位锚 = 水合消息 id（同库条目原位退位——重水合不双卡、位置不漂）
      const cardIds = new Set(applied.flatMap((entry) => (entry.runId ? [] : [`h${entry.id}`])));
      if (runIds.length === 0 && cardIds.size === 0) return chat;
      // 退位：被接管 run 的 live / 已水合消息与被重应用的无 run 卡原位移除（幂等，
      // 库块重放不双条），run 块与无 run 卡各记最早退位位
      let blockInsertAt = chat.messages.length;
      let cardInsertAt = chat.messages.length;
      const displacedCardIds = new Set<string>();
      const kept: ChatMessage[] = [];
      for (const message of chat.messages) {
        const messageRunId = "runId" in message ? message.runId : undefined;
        if (messageRunId !== undefined && runIds.includes(messageRunId)) {
          if (kept.length < blockInsertAt) blockInsertAt = kept.length;
          continue;
        }
        if (cardIds.has(message.id)) {
          displacedCardIds.add(message.id);
          if (kept.length < cardInsertAt) cardInsertAt = kept.length;
          continue;
        }
        kept.push(message);
      }
      const toMessage = (entry: HydratedEntry): ChatMessage[] => {
        const message = hydratedMessage(entry);
        return message ? [message] : [];
      };
      const openIdx = kept.findIndex(
        (message) => "runId" in message && message.runId === chat.openRunId,
      );
      const messages = [...kept];
      if (openIdx < 0) {
        // 无开放尾巴：整批按写入序（库序 = 对话序）插入最早退位位
        messages.splice(Math.min(blockInsertAt, cardInsertAt), 0, ...applied.flatMap(toMessage));
      } else {
        // 有开放尾巴（live 在途）：run 库块必旧于开放轮、插在尾前（原位退位口径
        // 不变）；已水合过的无 run 卡原位刷新；新到卡必新于在面全部、落末尾——
        // 轮收口后水合按库序归位
        if (blockInsertAt > openIdx) blockInsertAt = openIdx;
        const blockMessages = applied.flatMap((entry) => (entry.runId ? toMessage(entry) : []));
        messages.splice(blockInsertAt, 0, ...blockMessages);
        if (blockMessages.length > 0 && blockInsertAt <= cardInsertAt) {
          cardInsertAt += blockMessages.length;
        }
        for (const entry of applied) {
          if (entry.runId) continue;
          const [message] = toMessage(entry);
          if (!message) continue;
          if (displacedCardIds.has(message.id)) {
            messages.splice(Math.min(cardInsertAt, messages.length), 0, message);
            cardInsertAt += 1;
          } else {
            messages.push(message);
          }
        }
      }
      // 开放轮判定（#89）：末条目为流式中发言/作答或未答问答卡 → 该 run 开放
      // （后续水合跳过其条目，live 流式/挂起卡不被动塌；轮收口事件清锚后接管）
      const last = entries[entries.length - 1];
      const openRunId = isOpenTail(last) ? last.runId ?? undefined : chat.openRunId;
      return {
        ...chat,
        messages: messages.length > MAX_MESSAGES ? messages.slice(messages.length - MAX_MESSAGES) : messages,
        chatRunIds: runIds.reduce((ids, id) => pushCapped(ids, id), chat.chatRunIds),
        ingestedRunIds: runIds.reduce((ids, id) => pushCapped(ids, id), chat.ingestedRunIds),
        openRunId,
      };
    }),

  noteAcceptance: (projectId, runId, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 受理事件先于 run-start 到达（动作卡先出、解说随后）——不设 chatRunIds
      // 登记，SSE 事件 id 即去重锚
      if (chat.seenEventIds.includes(eventId)) return chat;
      const seen = { ...chat, seenEventIds: pushCapped(chat.seenEventIds, eventId) };
      return appendMessage(seen, {
        kind: "acceptance",
        id: eventId,
        runId,
        settled: false,
      });
    }),

  settleAcceptance: (projectId, runId) =>
    updateChat(set, projectId, (chat) => {
      const target = chat.messages.find(
        (message) => message.kind === "acceptance" && message.runId === runId && !message.settled,
      );
      if (!target) return chat; // 无该轮受理卡（咨询/纯追问轮）或已落定——幂等
      const messages = chat.messages.map((message) =>
        message === target ? { ...target, settled: true } : message,
      );
      return { ...chat, messages };
    }),

  appendUserMessage: (projectId, text, annotations = [], materials = []) => {
    const id = localId();
    updateChat(set, projectId, (chat) =>
      appendMessage(chat, { kind: "user", id, text, annotations, materials }),
    );
    return id;
  },

  submitAnswer: (projectId, text, runId) => {
    const id = localId();
    updateChat(set, projectId, (chat) => {
      const messages = chat.messages.map((message) =>
        message.kind === "question" && !message.answered
          ? { ...message, answered: true }
          : message,
      );
      return appendMessage(
        { ...chat, messages, turnActive: true },
        { kind: "user", id, text, runId },
      );
    });
    return id;
  },

  startTurn: (projectId) => updateChat(set, projectId, (chat) => ({ ...chat, turnActive: true })),

  endTurn: (projectId) => updateChat(set, projectId, (chat) => ({ ...chat, turnActive: false })),

  removeMessage: (projectId, messageId) =>
    updateChat(set, projectId, (chat) => ({
      ...chat,
      messages: chat.messages.filter((message) => message.id !== messageId),
    })),

  removeTrailingAcceptance: (projectId) =>
    updateChat(set, projectId, (chat) => {
      const last = chat.messages[chat.messages.length - 1];
      if (last === undefined || last.kind !== "acceptance" || last.settled) return chat;
      return {
        ...chat,
        messages: chat.messages.slice(0, -1),
      };
    }),

  reopenQuestion: (projectId) =>
    updateChat(set, projectId, (chat) => {
      for (let i = chat.messages.length - 1; i >= 0; i--) {
        const message = chat.messages[i];
        if (message.kind === "question") {
          const messages = chat.messages.slice();
          messages[i] = { ...message, answered: false };
          return { ...chat, messages, turnActive: false };
        }
      }
      return { ...chat, turnActive: false };
    }),

  markRunIngested: (projectId, runId) =>
    updateChat(set, projectId, (chat) => {
      const stamped = stampTrailingUserRun(chat, runId);
      return stamped.ingestedRunIds.includes(runId)
        ? stamped
        : { ...stamped, ingestedRunIds: pushCapped(stamped.ingestedRunIds, runId) };
    }),
}));

/** 当前待答问题（最后一条未答问答卡；无则 undefined）。 */
export function pendingQuestionOf(
  state: Pick<ChatState, "chats">,
  projectId: string,
): Extract<ChatMessage, { kind: "question" }> | undefined {
  const messages = state.chats[projectId]?.messages ?? [];
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i];
    if (message.kind === "question") return message.answered ? undefined : message;
  }
  return undefined;
}

function appendMessage(chat: ProjectChat, message: ChatMessage): ProjectChat {
  const messages = [...chat.messages, message];
  return {
    ...chat,
    messages: messages.length > MAX_MESSAGES ? messages.slice(messages.length - MAX_MESSAGES) : messages,
  };
}

/**
 * closing 载荷的容错收窄（#617 五字段；#619 变更清单真实 diff；live 事件与水合
 * 同形）：只兜「载荷非对象」的形状异常（视同无收尾卡，不出坏卡）；files 非数组
 * 回落空清单。filesUnavailable 只在明确 true 时呈现降级；patch/patchOmitted
 * 按可选字段收窄。
 *
 * <p>旧载荷回落可读（旧对话史不出坏卡）：#617 前落库的收尾卡没有 criteria /
 * narrative，而是平台句式 summary＋「需求文档／系统」判定键；#619 前的 files
 * 行数是工具参数活动量、无 patch——同样按文件级呈现，只少 diff 展开。旧 summary
 * 作为内核自述的回退文本呈现；判据按收口不变式（closing 在场 ⟺ build＋预览双件
 * 全过）回落双 ✓。新载荷的 narrative 为空/缺省时不出该块。</p>
 */
export function toWorkClosing(raw: unknown): WorkClosing | undefined {
  const record = asRecord(raw);
  if (!record) return undefined;
  const legacySummary = typeof record.summary === "string" ? record.summary : undefined;
  const narrative = typeof record.narrative === "string" ? record.narrative : legacySummary;
  const criteriaRecord = asRecord(record.criteria);
  const criteria: WorkClosingCriteria = criteriaRecord
    ? {
        buildPassed: criteriaRecord.buildPassed === true,
        previewPassed: criteriaRecord.previewPassed === true,
      }
    // 旧载荷：收尾卡在场 ⟺ 收口判据双件全过（#88 口径）
    : { buildPassed: true, previewPassed: true };
  return {
    ...(narrative ? { narrative } : {}),
    criteria,
    files: Array.isArray(record.files)
      ? record.files.flatMap((file) => {
          const entry = asRecord(file);
          if (!entry || typeof entry.path !== "string") return [];
          return [{
            path: entry.path,
            added: typeof entry.added === "number" ? entry.added : 0,
            removed: typeof entry.removed === "number" ? entry.removed : 0,
            ...(typeof entry.patch === "string" && entry.patch.length > 0
              ? { patch: entry.patch }
              : {}),
            ...(entry.patchOmitted === true ? { patchOmitted: true } : {}),
          }];
        })
      : [],
    ...(record.filesUnavailable === true ? { filesUnavailable: true } : {}),
    durationMs: typeof record.durationMs === "number" ? record.durationMs : 0,
    version: typeof record.version === "string" ? record.version : undefined,
  };
}

/**
 * quote 载荷的容错收窄（#203 视镜语义）：载荷 = 事件 + 订单引用；缺订单引用的
 * 条目视同无卡（不出坏卡——卡上一切事实都经订单详情查询现取，无引用即无可视镜）。
 */
export function toQuoteRef(raw: unknown): { orderId: string; event: string } | undefined {
  const record = asRecord(raw);
  if (!record || typeof record.orderId !== "string" || record.orderId === "") return undefined;
  return {
    orderId: record.orderId,
    event: typeof record.event === "string" && record.event !== "" ? record.event : "quoted",
  };
}

type SetFn = (partial: Partial<ChatState>) => void;

function updateChat(set: SetFn, projectId: string, mutate: (chat: ProjectChat) => ProjectChat): void {
  const state = useChatStore.getState();
  const current = chatOf(state, projectId);
  const next = mutate(current);
  if (next === current) return;
  set({ chats: { ...state.chats, [projectId]: next } });
}
