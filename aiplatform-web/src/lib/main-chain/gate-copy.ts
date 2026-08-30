/**
 * 需求端三扇门文案（共享层纯映射，issue #43 / spec 0002 §5）：门名（旅程映射
 * gateLabel）→ 用户侧卡片文案。禁词红线（不出现「门 / 阶段 / 智能体」等）由本
 * 映射集中守住，组件不另写文案；非用户拍板门（无门名）返回 null 兜底。
 *
 * 门名来自 journey.ts 的 `JOURNEY_STEPS[].gate`（确认 PRD / 确认原型 / 验收，
 * 2026-08-25 修订），是主链唯一领域模型在用户侧的关注点呈现；此处不硬编码段
 * 键名，只做门名 → 文案。`USER_GATES` 键型从 `JOURNEY_STEPS` 的 gate 字段推导
 * ——journey.ts 改门名时本表漏键 / 多键即 typecheck 红，防两处文案漂移。
 */

import { JOURNEY_STEPS } from "./journey";

export type UserGateCopy = {
  /** 门名（卡片副标题「需要你拍板 · 确认 PRD」）。 */
  label: string;
  /** 等待语（卡片主标题）。 */
  waiting: string;
  /** 通过按钮文案（验收门 = 「验收通过」，issue #43 专门验收动作口径）。 */
  approve: string;
  /** 驳回按钮文案。 */
  reject: string;
};

/** 用户侧门名（JOURNEY_STEPS 中带 gate 的步；六步内仅三扇门）。 */
type UserGateName = Extract<(typeof JOURNEY_STEPS)[number], { gate: string }>["gate"];

const USER_GATES: Record<UserGateName, UserGateCopy> = {
  "确认 PRD": {
    label: "确认 PRD",
    waiting: "PRD 已整理好，等你确认",
    approve: "确认无误，开始做原型",
    reject: "有补充 / 不对的地方",
  },
  确认原型: {
    label: "确认原型",
    waiting: "原型做好了，等你打开看看",
    approve: "满意，按这个做",
    reject: "要改一改",
  },
  验收: {
    label: "验收",
    waiting: "系统已可以体验，等你验收",
    approve: "验收通过",
    reject: "驳回反馈",
  },
};

/** 门名 → 用户侧文案；无门名 / 未知名（后端增门防御）返回 null，呈现层兜底。 */
export function userGateCopy(gateLabel: string | undefined): UserGateCopy | null {
  if (!gateLabel) return null;
  // 运行时未知名兜底（`in` 守卫）；`as UserGateName` 为编译期收窄，键集已由 Record 键型锁死
  return gateLabel in USER_GATES ? USER_GATES[gateLabel as UserGateName] : null;
}
