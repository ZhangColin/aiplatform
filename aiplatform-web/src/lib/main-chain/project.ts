import type { components } from "@/lib/api/schema";

import type { StageView } from "./stages";

/** 详情响应（swagger ProjectDetailResponse 原始形状，字段全可缺）。 */
export type ProjectDetailResponse = components["schemas"]["ProjectDetailResponse"];

export type GateView = components["schemas"]["GateView"];

/**
 * 主链消费口径的项目详情（issue #19）：核心字段收紧为必有，`stages` / `gate`
 * 归一（缺省 = 空序列 / 无门）。展示一律走 `*Name` 字段，枚举 code 不进消费
 * 逻辑（spec 0002 §6 枚举纪律）；终态判定走 `stages[].terminal`，不比 status。
 */
export type ProjectDetail = {
  id: string;
  name: string;
  /** 当前段稳定键（stages[].name 的命中源）。 */
  stage: string;
  stageLabel: string;
  /** 工作区 id（终端 exec 寻址面，issue #42）；未创建工作区时缺省。 */
  workspaceId?: string;
  /** Integer code（1=开发中 2=已交付 3=已归档）；只透传，不进逻辑。 */
  status?: number;
  statusName?: string;
  stageTaskCount?: number;
  archived?: boolean;
  createdAt?: string;
  /** 主链定义序列（呈现唯一源，spec 0002 §6）。 */
  stages: StageView[];
  /** 当前段确认门 `{actor, ready}`；无门段 / 已收口 = null。 */
  gate: GateView | null;
};

/** 信封解包后的详情 → 消费口径（缺省字段防御归一）。 */
export function normalizeProjectDetail(raw: ProjectDetailResponse): ProjectDetail {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "",
    stage: raw.stage ?? "",
    stageLabel: raw.stageLabel ?? "",
    workspaceId: raw.workspaceId,
    status: raw.status,
    statusName: raw.statusName,
    stageTaskCount: raw.stageTaskCount,
    archived: raw.archived,
    createdAt: raw.createdAt,
    stages: raw.stages ?? [],
    gate: raw.gate ?? null,
  };
}

/**
 * 门就绪判定（挂卡收口，issue #58）：`gate` 存在且 `ready` 显式 true 才可拍板；
 * 契约字段可空，缺失语义 = 未就绪。所有挂卡点（双端对话流底 / 待处理队列 /
 * 阶段面板）与计数、「等你」徽章统一走本谓词——门卡出现即处于可操作态，
 * 锁定态整支删除（不再有「挂出但不能拍」的门卡）。
 */
export function isGateReady(gate: GateView | null | undefined): gate is GateView {
  return gate != null && gate.ready === true;
}
