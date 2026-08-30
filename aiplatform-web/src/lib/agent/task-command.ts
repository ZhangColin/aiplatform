import type { components } from "@/lib/api/schema";

/**
 * 下任务载荷构造与角色卡词表（issue #40，spec 0001 §4.1）：`POST /projects/{id}/agent/task`
 * 的 ProjectAgentTaskCommand 构造。角色卡 code 1–6（后端固定），**缺省语义 = 不携带
 * role**——后端取当前阶段默认角色（无默认角色的阶段需显式指定，409 PRJ_004）。显式
 * 选角色卡才携带 role。
 */

export type ProjectAgentTaskCommand = components["schemas"]["ProjectAgentTaskCommand"];

/** 角色卡 code 词表（1=BA 2=DEV 3=DELIVERY 4=ARCH 5=TEST 6=DEMO，镜像 swagger 注释）。 */
export const AGENT_ROLES = [
  { code: 1, name: "BA", label: "需求分析师" },
  { code: 2, name: "DEV", label: "开发工程师" },
  { code: 3, name: "DELIVERY", label: "交付工程师" },
  { code: 4, name: "ARCH", label: "架构师" },
  { code: 5, name: "TEST", label: "测试工程师" },
  { code: 6, name: "DEMO", label: "原型开发工程师" },
] as const;

export type AgentRoleCode = (typeof AGENT_ROLES)[number]["code"];

/** code → 角色卡；未知 code 返回 undefined（呈现层兜底）。 */
export function roleByCode(code: number): (typeof AGENT_ROLES)[number] | undefined {
  return AGENT_ROLES.find((r) => r.code === code);
}

/**
 * 下任务载荷构造：prompt 必带；role 缺省（undefined）即省略 → 后端取阶段默认角色。
 * 这是「角色卡缺省语义」的唯一正解——前端不猜阶段默认角色，交给后端按主链定义定。
 */
export function buildTaskCommand(prompt: string, role?: AgentRoleCode): ProjectAgentTaskCommand {
  const command: ProjectAgentTaskCommand = { prompt };
  if (role !== undefined) command.role = role;
  return command;
}
