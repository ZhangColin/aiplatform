import type { components } from "@/lib/api/schema";

/**
 * 一句话建项目（issue #39 起，#51 收为纯一句话，spec 0002 §3.1 / #48）：
 * CreateProjectCommand 载荷构造的纯逻辑层。创建只有一句话——项目名由后端
 * LLM 取（aiplatform-server#39 已落地，name 从契约移除）、模板单链默认、引擎
 * 后台统一定，载荷不含 name / type / engine。
 */

export type CreateProjectCommand = components["schemas"]["CreateProjectCommand"];

/** 建项目响应（POST /api/projects → {project, runId}）。 */
export type ProjectCreatedResponse = components["schemas"]["ProjectCreatedResponse"];

export type CreateProjectInput = {
  requirement: string;
};

/** 载荷构造：requirement trim 后必有；项目名/模板/引擎一概后端定。 */
export function buildCreateProjectCommand(input: CreateProjectInput): CreateProjectCommand {
  return { requirement: input.requirement.trim() };
}
