/**
 * 平台模式位（#72 定稿 / #76 落地）：发送框类型下拉与侧栏「能做这些」
 * 同源概念，v1 仅「做系统」可用，其余为「敬请期待」占位——上线新模式只改
 * 此一处。
 */
export const PLATFORM_MODES = [
  { label: "做系统", live: true },
  { label: "做页面", live: false },
  { label: "写文档", live: false },
] as const;

export type PlatformMode = (typeof PLATFORM_MODES)[number]["label"];
