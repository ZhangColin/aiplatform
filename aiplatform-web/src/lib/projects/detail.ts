import type { components } from "@/lib/api/schema";

/** 详情响应（swagger ProjectDetailResponse 原始形状，字段全可缺）。 */
export type ProjectDetailResponse = components["schemas"]["ProjectDetailResponse"];

/** 消费口径的项目详情（缺省字段防御归一）：壳态只取骨架所需字段，随切片增补。 */
export type ProjectDetail = {
  id: string;
  name: string;
  statusName?: string;
  archived?: boolean;
  createdAt?: string;
};

/** 信封解包后的详情 → 消费口径（缺省字段防御归一）。 */
export function normalizeProjectDetail(raw: ProjectDetailResponse): ProjectDetail {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "",
    statusName: raw.statusName,
    archived: raw.archived,
    createdAt: raw.createdAt,
  };
}
