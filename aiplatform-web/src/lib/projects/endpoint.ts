/**
 * 项目终点类型（#285，ADR-0024「项目不分型、终点是属性」）：REST 传 Integer code
 * （后端 ProjectEndpointType，BaseEnum 契约）。下单前可变（设置 tab 控件＝项目内
 * 唯一变更位）、下单即冻结；PRD 清单章形态跟终点类型走（设计＝设计物清单、系统/
 * 系统＋设计＝功能清单）。
 */
export const ENDPOINT_TYPE = {
  design: 1,
  system: 2,
  systemDesign: 3,
} as const;

export type EndpointTypeCode = (typeof ENDPOINT_TYPE)[keyof typeof ENDPOINT_TYPE];

/** 控件三档文案（做设计＝设计主线、系统＋设计＝先设计后系统）。 */
export const ENDPOINT_OPTIONS: { code: EndpointTypeCode; label: string; hint: string }[] = [
  {
    code: ENDPOINT_TYPE.system,
    label: "做系统",
    hint: "交付可操作的完整系统",
  },
  {
    code: ENDPOINT_TYPE.design,
    label: "做设计",
    hint: "交付设计资产包（logo、海报、页面样式）",
  },
  {
    code: ENDPOINT_TYPE.systemDesign,
    label: "系统＋设计",
    hint: "先做设计，系统从设计稿长出",
  },
];

/** 设计范围作用域 code（后端 DesignScopeType）。 */
export const DESIGN_SCOPE = {
  allPages: 1,
  selectedPages: 2,
} as const;
