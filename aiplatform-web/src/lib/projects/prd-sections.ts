/**
 * PRD 锚点分节提取（#346，spec #320 ⑥ 需求分析页右栏）：节界＝`## ` 二级
 * 章头（PRD 正本结构＝prd-writing 技能的固定七章节）；### 子节不独立成锚、
 * 代码围栏内的「## 」不误切。锚 id 从 1 起编（prd-sec-N——锚点导航 pill 的
 * 序号与渲染侧锚位同源）。
 */
export type PrdSection = {
  /** 锚 id（prd-sec-N，1 起编——导航 pill 序号同源）。 */
  id: string;
  /** 章名（## 后原文 trim）。 */
  title: string;
  /** 节体 markdown（章头下一行起至下一条 ## 或文末；含 ### 子节原文）。 */
  body: string;
};

export function splitPrdSections(markdown: string): {
  /** 首章之前的内容（文档大标题/导言等；无则为空串）。 */
  preamble: string;
  sections: PrdSection[];
} {
  const lines = markdown.split("\n");
  const preambleLines: string[] = [];
  const sections: { title: string; bodyLines: string[] }[] = [];
  let inFence = false;
  let current: { title: string; bodyLines: string[] } | null = null;

  for (const line of lines) {
    // 代码围栏开合（``` 或 ~~~ 起止）——围栏内内容不参与章头判定
    if (/^\s*(```|~~~)/.test(line)) inFence = !inFence;
    const heading = !inFence && /^##\s+/.test(line) ? line.replace(/^##\s+/, "").trim() : null;
    if (heading !== null) {
      current = { title: heading, bodyLines: [] };
      sections.push(current);
    } else if (current) {
      current.bodyLines.push(line);
    } else {
      preambleLines.push(line);
    }
  }

  return {
    preamble: preambleLines.join("\n").trim(),
    sections: sections.map((s, i) => ({
      id: `prd-sec-${i + 1}`,
      title: s.title,
      body: s.bodyLines.join("\n").trim(),
    })),
  };
}
