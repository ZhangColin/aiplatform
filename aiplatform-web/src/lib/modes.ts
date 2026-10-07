/**
 * 平台模式位（#72 定稿 / #76 落地；#299 收编两档 live，ADR-0029）：发送框类型
 * 下拉与侧栏「能做这些」同源概念。两档：做系统（默认）｜做设计——复合终点不设
 * 入口档（由中途切换或定稿后转系统开发达成）；「做页面」「写文档」占位档已删
 * （占位是结构不是能力——「上线新模式只改此一处」机制保留）。
 */

/** 入口两档标识（做系统＝默认系统主线、做设计＝设计主线；分流态 store 的值域）。 */
export const ENTRY_MODE = {
  system: "做系统",
  design: "做设计",
} as const;

export type EntryMode = (typeof ENTRY_MODE)[keyof typeof ENTRY_MODE];

/** 模式位注册表：档面声明单点（新档上线只改此表；live=false 渲染「敬请期待」）。 */
export const PLATFORM_MODES: readonly { label: EntryMode; live: boolean }[] = [
  { label: ENTRY_MODE.system, live: true },
  { label: ENTRY_MODE.design, live: true },
];
