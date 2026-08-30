import type { StageProgress, StageStatus } from "./stages";

/**
 * 用户侧旅程映射（共享层资产，spec 0002 §5）：主链只有一个领域模型——详情
 * `stages[]` 按序 1:1 映射六步（第 i 段 ↔ 第 i 步；`terminal` 段 = 「交付」终态），
 * 不硬编码段键名。映射表归通用层（旅程呈现组件消费）。
 *
 * 文案红线（票面）：用户侧禁词——阶段 / 状态机 / 智能体 / 期 / OPC / HITL / Demo
 * （对用户说「原型」）；称谓：顾问（需求梳理）、团队（开发）、质检团队（测试）。
 */

/**
 * 旅程六步（spec 0002 §5 定稿文案）。`gate` = 该步对应段的用户侧门名（三扇门：
 * 确认 PRD / 确认原型 / 验收，第一扇门 2026-08-25 更名，PRD 用词放开）——只挂
 * 用户拍板的门；开发侧的「开发完成」门在用户视角折叠为「质检中 → 验收」之间，
 * 不单列（对应段即使带 gateActor 也不映射）。
 */
export const JOURNEY_STEPS = [
  { key: "chat", label: "聊需求", hint: "顾问陪你把想做的事聊清楚", gate: "确认 PRD" },
  { key: "prototype", label: "看原型", hint: "先看可点击的原型，确认长相", gate: "确认原型" },
  { key: "build", label: "制作中", hint: "团队按确认的需求制作系统" },
  { key: "qa", label: "质检中", hint: "质检团队逐项检查系统" },
  { key: "accept", label: "验收", hint: "亲手体验，你来拍板", gate: "验收" },
  { key: "deliver", label: "交付", hint: "拿到源码包和使用说明" },
] as const;

export type JourneyStep = {
  key: string;
  label: string;
  hint: string;
  status: StageStatus;
  terminal: boolean;
  /** 该步对应段有门时的用户侧门名（仅用户拍板的门；无门 / 开发侧门为 undefined）。 */
  gateLabel?: string;
};

/**
 * 段推导 → 旅程步骤。六步预设按位对齐；序列长于六步的超出段回退段 label
 * （后端增段防御），短于六步按实际段数映射，不越界。
 */
export function mapStagesToJourney(progress: StageProgress): JourneyStep[] {
  return progress.steps.map(({ index, stage, status }) => {
    const preset = JOURNEY_STEPS[index];
    return {
      key: preset?.key ?? stage.name ?? `stage-${index}`,
      label: preset?.label ?? stage.label ?? stage.name ?? `第 ${index + 1} 步`,
      hint: preset?.hint ?? "",
      status,
      terminal: stage.terminal === true,
      // 六步外的超出段无预设门名（后端增段防御），不臆造
      gateLabel:
        stage.gateActor && preset !== undefined && "gate" in preset ? preset.gate : undefined,
    };
  });
}
