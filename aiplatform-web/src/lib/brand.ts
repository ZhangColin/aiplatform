/**
 * 站点品牌常量（#342 品牌落位）：站点名/对外简述/品牌素材路径的单一来源。
 * 正本＝交接包《语义引力网站设计资料包_v1.0》「品牌信息」章（中文品牌
 * 「语义引力」、英文品牌 SEMANTIC GRAVITY、对外简述建议原文）；metadata
 * title/description 与品牌件从这里取值，不落第二份字面量。
 */

/** 站点名（metadata title 与品牌头共用）。 */
export const SITE_NAME = "语义引力";

/** 对外简述（资料包「对外简述建议」原文；meta description 用，不带句号收尾）。 */
export const SITE_DESCRIPTION =
  "把需求聊清楚，形成 PRD，先体验 Demo，再推进正式交付";

/**
 * 品牌徽标两版（交接包 assets 落库 public/brand）：
 * - dark＝深色圆底（#0F172A 圆底叠白色标识）——浅色场合（导航位缺省）；
 * - white＝白色透明（原件位图）——深色场合留存，白底看不清不代表图片空白。
 */
export const BRAND_LOGO = {
  dark: "/brand/logo-dark-round.svg",
  white: "/brand/logo-white-transparent.png",
} as const;
