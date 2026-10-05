/**
 * 功能清单条目解析（#285 设置 tab 切换控件的作用域勾选源）：PRD 清单章系统形
 * （「功能清单」章节）的编号条目 → 勾选清单。PRD 是主智能体独笔演进的 markdown
 * 正本，条目无稳定标识——解析取条目首行全文作为建议性锚（与后端 design_scope_pages
 * 同口径：标签是锚不是标识，措辞漂移由主智能体重产时按语义消化）。
 */

/** 功能清单章标题行（##/### 等任意层级，容忍前后空白）。 */
const CHAPTER_HEADING = /^#{1,6}\s*功能清单\s*$/;

/** 章内同层或更高级标题（章节边界）。 */
const ANY_HEADING = /^#{1,6}\s+/;

/** 编号条目行（1. / 1、等，取全行文本）。 */
const NUMBERED_ITEM = /^\s*(\d{1,3})[.、．]\s*(\S.*)$/;

/** 条目标签长度上限（与后端命令层 @Size(200) 对齐——超长截断保请求可发）。 */
const ITEM_MAX_LENGTH = 200;

/**
 * 解析 PRD markdown 的功能清单条目：无 PRD / 无该章 / 无编号条目 → 空数组
 * （调用侧按空清单隐藏勾选态、只留全部页面）。
 */
export function parseFeatureListItems(prdMarkdown: string | null | undefined): string[] {
  if (!prdMarkdown) return [];
  const lines = prdMarkdown.split("\n");
  const items: string[] = [];
  let inChapter = false;
  for (const line of lines) {
    if (CHAPTER_HEADING.test(line.trim())) {
      inChapter = true;
      continue;
    }
    if (inChapter && ANY_HEADING.test(line)) break; // 下一章开始
    if (!inChapter) continue;
    const match = NUMBERED_ITEM.exec(line);
    if (match) {
      const text = match[2].trim();
      if (text) items.push(text.slice(0, ITEM_MAX_LENGTH));
    }
  }
  return items;
}
