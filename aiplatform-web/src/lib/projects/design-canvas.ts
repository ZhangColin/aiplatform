import type { DesignItemFact } from "@/lib/projects/detail";
import { relativeFormOf, type WorkspaceFile } from "@/lib/projects/files";
import type { ClosingDraft } from "@/lib/store/chat";

/**
 * 全系统画布的纯逻辑单点（#293 设计稿范式；#294 渐进长出）：对话流收尾卡的稿
 * 清单（closing.drafts——live 经 run-finish 入流、回访经对话史水合，同一事件→状态
 * seam，SSE 零扩展）× 文件树存在性（悬卡删除＝真删工作区文件，删稿即消卡）
 * × 轨道件清单（分组锚与定稿事实）→ 画布模型（件 × 代 × 稿）。
 *
 * 代际派生＝历史事实不重排：每轮收口（closing 一条）为本件贡献≥1 张新稿
 * （路径未见）即开新代——同稿重复播报（定稿收尾卡复述选定稿）不开新代；
 * 整代稿件被删（存在性滤空）该代不呈现、序号不回收（代际可辨以史为锚）。
 *
 * <b>在途稿提升（#294 渐进长出）</b>：设计轨 live（work 座席 designer 未定格）
 * 时，文件树里 design/ 锚定而未进任何收尾卡轮的文件＝本场正在长的稿——提升为
 * 目标件的下一代替补（incoming 标记：占位观感与定稿口的有无判据），随写随显
 * （写文件动作收口即失效文件树，稿一张张到达不等齐）。收口后收尾卡轮接管锚定
 * （incoming 标记退场）；非 live 时零提升——树上的游离稿（渲失败的 HTML 源、
 * 下载位图化产物）不冒充代际事实。
 */

/** 画布稿卡事实：path 工作区锚定形（取件走 raw 路由）。 */
export type CanvasDraft = {
  path: string;
  media: "html" | "image";
  item: string;
  /** 代序（1-based，按到达轮派生——历史事实不重排）。 */
  gen: number;
  /** 在途稿（#294）：live 会话正在写的稿——占位观感、无定稿口、不参与删除。 */
  incoming?: boolean;
};

/** 画布件分组：代左→右（gen 升序），稿在代内按路径序稳定。 */
export type CanvasItem = {
  /** 分组键＝设计物标题（收尾卡 drafts.item）。 */
  item: string;
  /** 现行清单序（标题精确对照匹配才携带；清单演进措辞漂移的旧件 null 殿后）。 */
  ord: number | null;
  /** 件状态（清单匹配才携带——画布定稿徽记与删除口的判据）。 */
  status: DesignItemFact["status"] | null;
  /** 定稿稿路径（已定稿件携带——该卡不可删、定稿徽记）。 */
  finalizedPath?: string;
  gens: { gen: number; drafts: CanvasDraft[] }[];
  /** 本件正在被 live 设计会话出稿（#294 占位卡的呈现判据）。 */
  live?: boolean;
};

/**
 * live 设计会话的在途事实（#294）：itemTitle＝run-start 切片标题（当前设计物）；
 * null＝无 live 会话（零提升）。
 */
export type LiveDesignRun = { itemTitle: string | null } | null;

/** 文件树条目是否 design/ 锚定的稿面（树路径相对形；两形归一后判定）。 */
function isDesignDraftPath(path: string): boolean {
  return path.toLowerCase().startsWith("design/");
}

/** 稿形态（按扩展名派生——与收尾卡 mediaOf 同口径的呈现面镜像）。 */
function mediaOfPath(path: string): "html" | "image" {
  return path.toLowerCase().endsWith(".html") ? "html" : "image";
}

/**
 * 派生画布模型。rounds＝对话序的收尾卡稿清单轮（每条 closing 一轮，空稿轮如实
 * 不开代）；files＝文件树（undefined = 树未达——不呈现任何稿，树是存在性正本）；
 * items＝现行轨道件清单（null = 清单过期/未落——稿按标题自组、无状态面）；
 * live＝live 设计会话事实（null = 不在途——零提升）。
 */
export function buildDesignCanvas(
  rounds: (ClosingDraft[] | undefined)[],
  files: WorkspaceFile[] | undefined,
  items: DesignItemFact[] | null | undefined,
  live: LiveDesignRun = null,
): CanvasItem[] {
  if (!files) return [];
  // 两形归一后比较：树逐路径相对形、稿清单携锚定形（前导 /）——精确匹配会
  // 永不相等（归一正本＝relativeFormOf，与 raw 取件同点）
  const existing = new Set(files.map((file) => relativeFormOf(file.path)));
  const itemFact = new Map((items ?? []).map((fact) => [fact.title, fact]));

  // 件 → 代账本（到达序）；同稿重复播报不开新代（seenPaths）
  const seenPaths = new Set<string>();
  const ledger = new Map<string, Map<number, CanvasDraft[]>>();
  for (const drafts of rounds) {
    const roundNew = new Map<string, CanvasDraft[]>();
    for (const draft of drafts ?? []) {
      if (!draft.path || seenPaths.has(draft.path)) continue;
      seenPaths.add(draft.path);
      const list = roundNew.get(draft.item) ?? [];
      list.push({ path: draft.path, media: draft.media, item: draft.item, gen: 0 });
      roundNew.set(draft.item, list);
    }
    for (const [item, draftsOfRound] of roundNew) {
      const gens = ledger.get(item) ?? new Map<number, CanvasDraft[]>();
      const gen = gens.size + 1;
      gens.set(gen, draftsOfRound.map((draft) => ({ ...draft, gen })));
      ledger.set(item, gens);
    }
  }

  // 在途稿提升（#294）：live 会话的树上未见稿＝本场正在长的稿——挂目标件账本
  // 尾代（incoming）。目标件按切片标题对照清单（清单漂移的旧标题自组同律）。
  const liveItem = live?.itemTitle ?? null;
  if (liveItem) {
    const incoming = [...existing]
      .filter((path) => isDesignDraftPath(path) && !seenPaths.has("/" + path))
      .sort()
      .map((path) => ({
        path: "/" + path,
        media: mediaOfPath(path),
        item: liveItem,
        gen: 0,
        incoming: true,
      }));
    if (incoming.length > 0) {
      const gens = ledger.get(liveItem) ?? new Map<number, CanvasDraft[]>();
      gens.set(gens.size + 1, incoming.map((draft) => ({ ...draft, gen: gens.size + 1 })));
      ledger.set(liveItem, gens);
    }
  }

  // 存在性滤稿（悬卡删除即消卡）；整代滤空不呈现、序号不回收；现行清单件按
  // ord 在前、清单演进措辞漂移的旧件按到达序殿后（Map 插入序）
  /** 件分组拼装：代升序、代内稿路径稳定序、存在性滤空整代不呈现。 */
  const canvasItemOf = (item: string, fact: DesignItemFact | null): CanvasItem => {
    const gens = [...(ledger.get(item)?.entries() ?? [])]
      .sort(([a], [b]) => a - b)
      .map(([gen, drafts]) => ({
        gen,
        drafts: drafts
          .filter((draft) => existing.has(relativeFormOf(draft.path)))
          .sort((a, b) => (a.path < b.path ? -1 : 1)),
      }))
      .filter((gen) => gen.drafts.length > 0);
    return {
      item,
      ord: fact?.ord ?? null,
      status: fact?.status ?? null,
      finalizedPath: fact?.finalizedPath,
      gens,
      live: item === liveItem,
    };
  };
  const canvas: CanvasItem[] = [];
  for (const fact of items ?? []) {
    canvas.push(canvasItemOf(fact.title, fact));
  }
  for (const item of ledger.keys()) {
    if (itemFact.has(item)) continue; // 现行清单件已入列
    canvas.push(canvasItemOf(item, null));
  }
  return canvas;
}

/**
 * 稿卡名（呈现）：路径基名去 TSID 数字前缀——图片稿落盘名 `{tsid}-{词干}.png`
 * （#288 转存防撞），词干才是稿名（generate_image 的 name / 执行体命名纪律）；
 * 无前缀（HTML 稿执行体自命名）原样。
 */
export function draftDisplayName(path: string): string {
  const name = path.split("/").pop() ?? path;
  return name.replace(/^\d+-/, "");
}
