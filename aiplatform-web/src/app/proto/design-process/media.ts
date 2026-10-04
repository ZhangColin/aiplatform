/**
 * ============================================================================
 * 原 型 —— 设计过程体验（#278）：假媒体资产（一次性，走查完即归档，勿当生产代码）
 * ============================================================================
 * 毛巾图案＝程序化 SVG（两代六稿＋吊牌两稿）；咖啡首屏＝分阶段 srcDoc
 * （界面类候选「随编码逐步显现」的渐进源）。图不接真管道——#276 已定
 * 稿卡取件走平台文件服务路由，原型里本地假图代演。
 * ============================================================================
 */

/* ---------- 毛巾主图案（程序化 SVG） ---------- */

type TowelPattern = "stripes" | "dots" | "geo" | "blocks" | "squiggle";

function towelSVG(pattern: TowelPattern, palette: string[], ground: string): string {
  const W = 300;
  const H = 420;
  let art = "";
  if (pattern === "stripes") {
    art = [70, 110, 150, 190, 230, 270, 310]
      .map((y, i) => `<rect x="40" y="${y}" width="220" height="${i % 2 ? 14 : 26}" rx="7" fill="${palette[i % palette.length]}"/>`)
      .join("");
  } else if (pattern === "dots") {
    const dots: string[] = [];
    for (let r = 0; r < 5; r++)
      for (let c = 0; c < 4; c++)
        dots.push(
          `<circle cx="${72 + c * 52 + (r % 2) * 14}" cy="${92 + r * 56}" r="${10 + ((r + c) % 3) * 4}" fill="${palette[(r + c) % palette.length]}"/>`,
        );
    art = dots.join("");
  } else if (pattern === "geo") {
    const tris: string[] = [];
    for (let r = 0; r < 3; r++)
      for (let c = 0; c < 4; c++)
        tris.push(
          `<path d="M${64 + c * 58} ${120 + r * 88} l24 -44 l24 44 z" fill="${palette[(r * 2 + c) % palette.length]}" opacity="${0.75 + ((r + c) % 2) * 0.25}"/>`,
        );
    art = tris.join("");
  } else if (pattern === "blocks") {
    art = [
      `<rect x="52" y="86" width="88" height="88" rx="18" fill="${palette[0]}"/>`,
      `<rect x="160" y="86" width="88" height="88" rx="44" fill="${palette[1]}"/>`,
      `<rect x="52" y="194" width="88" height="88" rx="44" fill="${palette[1]}"/>`,
      `<rect x="160" y="194" width="88" height="88" rx="18" fill="${palette[0]}"/>`,
      `<rect x="106" y="300" width="88" height="26" rx="13" fill="${palette[2] ?? palette[0]}"/>`,
    ].join("");
  } else {
    const path: string[] = [];
    for (let i = 0; i < 4; i++) {
      const y = 110 + i * 62;
      path.push(
        `<path d="M52 ${y} q28 -34 56 0 t56 0 t56 0" stroke="${palette[i % palette.length]}" stroke-width="9" fill="none" stroke-linecap="round"/>`,
      );
    }
    art = path.join("");
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}">
  <rect width="${W}" height="${H}" rx="18" fill="${ground}"/>
  <rect x="26" y="22" width="248" height="376" rx="12" fill="none" stroke="rgba(0,0,0,.08)" stroke-width="2"/>
  ${art}
  <rect x="26" y="30" width="248" height="10" fill="rgba(255,255,255,.55)"/>
  <rect x="26" y="380" width="248" height="10" fill="rgba(255,255,255,.55)"/>
</svg>`;
}

/** 吊牌：竖卡＋图案回声＋品牌字。 */
function tagSVG(pattern: TowelPattern, palette: string[], ground: string): string {
  const W = 220;
  const H = 320;
  const echo =
    pattern === "dots"
      ? `<circle cx="70" cy="120" r="26" fill="${palette[0]}"/><circle cx="120" cy="120" r="18" fill="${palette[1] ?? palette[0]}"/><circle cx="158" cy="120" r="12" fill="${palette[2] ?? palette[0]}"/>`
      : `<rect x="56" y="96" width="108" height="48" rx="10" fill="${palette[0]}"/><rect x="56" y="152" width="72" height="14" rx="7" fill="${palette[1] ?? palette[0]}"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}">
  <rect width="${W}" height="${H}" rx="14" fill="${ground}" stroke="rgba(0,0,0,.1)"/>
  <circle cx="110" cy="34" r="7" fill="none" stroke="rgba(0,0,0,.25)" stroke-width="3"/>
  ${echo}
  <text x="110" y="228" text-anchor="middle" font-family="sans-serif" font-size="21" font-weight="700" fill="#3f3f46">COTTON&nbsp;CO.</text>
  <text x="110" y="252" text-anchor="middle" font-family="sans-serif" font-size="11" letter-spacing="4" fill="#a1a1aa">FACE TOWEL</text>
  <rect x="70" y="272" width="80" height="3" rx="1.5" fill="${palette[0]}"/>
</svg>`;
}

/** 上传参考图（物料回显用，一张小 moodboard）。 */
export const REFERENCE_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 160">
  <rect width="240" height="160" rx="10" fill="#fafaf9"/>
  <rect x="12" y="12" width="100" height="66" rx="8" fill="#fed7aa"/>
  <rect x="128" y="12" width="100" height="66" rx="8" fill="#bbf7d0"/>
  <rect x="12" y="86" width="100" height="62" rx="8" fill="#bfdbfe"/>
  <rect x="128" y="86" width="100" height="62" rx="8" fill="#fecdd3"/>
  <text x="120" y="150" text-anchor="middle" font-family="sans-serif" font-size="9" fill="#a1a1aa">喜欢的配色与感觉.jpg</text>
</svg>`;

export function svgUrl(svg: string): string {
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

/** 毛巾项目两代候选＋吊牌候选（id → 图）。 */
export const TOWEL_DRAFTS: Record<string, string> = {
  // 第一代：方向探索（沉稳系）
  "tw-1-1": towelSVG("stripes", ["#3b82f6", "#93c5fd"], "#f8fafc"),
  "tw-1-2": towelSVG("dots", ["#f97316", "#fdba74"], "#fffbeb"),
  "tw-1-3": towelSVG("geo", ["#64748b", "#a7f3d0", "#34d399"], "#f8fafc"),
  // 第二代：改稿「再活泼一点、色彩跳一些」
  "tw-2-1": towelSVG("dots", ["#f43f5e", "#fbbf24", "#38bdf8"], "#fff7ed"),
  "tw-2-2": towelSVG("blocks", ["#8b5cf6", "#f472b6"], "#faf5ff"),
  "tw-2-3": towelSVG("squiggle", ["#10b981", "#f59e0b", "#ef4444", "#3b82f6"], "#f0fdf4"),
  // 吊牌（第二件设计物）＋改稿第二代
  "tag-1-1": tagSVG("dots", ["#f97316", "#fdba74"], "#fffbeb"),
  "tag-1-2": tagSVG("blocks", ["#8b5cf6", "#f472b6"], "#faf5ff"),
  "tag-2-1": tagSVG("geo", ["#f43f5e", "#fbbf24", "#38bdf8"], "#fff7ed"),
  "tag-2-2": tagSVG("squiggle", ["#10b981", "#f59e0b", "#ef4444"], "#f0fdf4"),
};

/** 稿名（正本在 media——engine 组改稿叙事也要用）。 */
const DRAFT_NAMES: Record<string, string> = {
  "tw-1-1": "蓝白条纹", "tw-1-2": "暖橙波点", "tw-1-3": "灰绿几何",
  "tw-2-1": "波点跳色", "tw-2-2": "几何撞色", "tw-2-3": "涂鸦线条",
  "tag-1-1": "波点呼应", "tag-1-2": "色块呼应",
  "tag-2-1": "三角呼应", "tag-2-2": "线条呼应",
};

export function draftName(mediaId: string): string {
  return DRAFT_NAMES[mediaId] ?? COFFEE_DESIGNS[mediaId]?.name ?? mediaId;
}

/* ---------- 咖啡店首屏（界面类候选：分阶段 srcDoc） ---------- */

export type CoffeeDesign = {
  id: string;
  name: string;
  accent: string;
  soft: string;
  layout: "center" | "split" | "bold";
  /** 页面形：home＝首屏 landing；menu＝菜单页。 */
  kind: "home" | "menu";
};

export const COFFEE_DESIGNS: Record<string, CoffeeDesign> = {
  "cf-1-1": { id: "cf-1-1", name: "居中沉稳", accent: "#7c5a3a", soft: "#f5efe6", layout: "center", kind: "home" },
  "cf-1-2": { id: "cf-1-2", name: "左文右图", accent: "#2f7d5d", soft: "#eef6f0", layout: "split", kind: "home" },
  "cf-1-3": { id: "cf-1-3", name: "大字报", accent: "#b45309", soft: "#fdf6ec", layout: "bold", kind: "home" },
  "cf-2-1": { id: "cf-2-1", name: "暖阳拼贴", accent: "#c2571a", soft: "#fdf1e7", layout: "split", kind: "home" },
  "cf-2-2": { id: "cf-2-2", name: "夜航深色", accent: "#8b5cf6", soft: "#1e1b2e", layout: "center", kind: "home" },
  "cf-m1": { id: "cf-m1", name: "菜单·卡片网格", accent: "#2f7d5d", soft: "#eef6f0", layout: "center", kind: "menu" },
  "cf-m2": { id: "cf-m2", name: "菜单·简洁列表", accent: "#7c5a3a", soft: "#f5efe6", layout: "center", kind: "menu" },
  "cf-m3": { id: "cf-m3", name: "菜单·大图橱窗", accent: "#c2571a", soft: "#fdf1e7", layout: "center", kind: "menu" },
  "cf-m4": { id: "cf-m4", name: "菜单·双栏分类", accent: "#b45309", soft: "#fdf6ec", layout: "center", kind: "menu" },
};

/**
 * 界面类候选的阶段化 srcDoc——「随编码逐步显现」的渐进源：
 * 1 骨架 → 2 内容灰阶 → 3 上色 → 4 成品。构建 run 的系统 tab 复用同一
 * 函数（定稿哪张就长成哪张——按稿对齐的视觉正身）。kind 分页面形：
 * home＝首屏 landing、menu＝菜单页。
 */
export function coffeeHTML(designId: string, stage: number): string {
  const d = COFFEE_DESIGNS[designId] ?? COFFEE_DESIGNS["cf-1-2"];
  return `<!doctype html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
  <style>html,body{margin:0;background:#fff}a{text-decoration:none}</style></head>
  <body style="min-height:100vh">${coffeeContent(d, stage)}</body></html>`;
}

/** PNG 导出用正文片段（foreignObject 内嵌 XHTML——与 iframe 所见同源同帧）。 */
export function coffeeBody(designId: string, stage: number): string {
  const d = COFFEE_DESIGNS[designId] ?? COFFEE_DESIGNS["cf-1-2"];
  return coffeeContent(d, stage);
}

function coffeeContent(d: CoffeeDesign, stage: number): string {
  const dark = d.soft === "#1e1b2e";
  const ink = dark ? "#ece9f7" : "#2f2a24";
  const sub = dark ? "#a8a2c4" : "#8c8478";
  const sk = (w: string, h: number, radius = 6) =>
    `<div style="height:${h}px;width:${w};border-radius:${radius}px;background:${dark ? "#2c2842" : "#eceae5"}"></div>`;
  const button = (label: string) =>
    stage >= 3
      ? `<div style="display:inline-block;margin-top:14px;padding:9px 22px;border-radius:999px;background:${d.accent};color:#fff;font-size:13px;font-weight:600;font-family:sans-serif">${label}</div>`
      : `<div style="margin-top:14px">${sk("120px", 34, 17)}</div>`;
  const menuCard = (emoji: string, name: string, price: string) =>
    stage >= 4
      ? `<div style="flex:1;border-radius:12px;overflow:hidden;border:1px solid ${dark ? "#35304e" : "#eee9df"};font-family:sans-serif">
           <div style="height:56px;display:flex;align-items:center;justify-content:center;font-size:26px;background:${d.soft}">${emoji}</div>
           <div style="padding:8px 10px"><div style="font-size:12px;font-weight:600;color:${ink}">${name}</div>
           <div style="font-size:11px;color:${d.accent};font-weight:700;margin-top:2px">¥ ${price}</div></div>
         </div>`
      : `<div style="flex:1">${sk("100%", 88, 12)}</div>`;

  const nav = stage >= 3
    ? `<div style="display:flex;align-items:center;padding:12px 18px;border-bottom:1px solid ${dark ? "#2c2842" : "#eee9df"};font-family:sans-serif">
         <span style="font-weight:800;color:${ink};font-size:15px">巷角咖啡</span>
         <span style="margin-left:auto;display:flex;gap:14px;font-size:11px;color:${sub}">
           <span style="color:${d.accent};font-weight:600">首页</span><span style="${d.kind === "menu" ? `color:${d.accent};font-weight:600` : ""}">菜单</span><span>关于</span><span>联系</span>
         </span>
       </div>`
    : `<div style="display:flex;align-items:center;padding:12px 18px;border-bottom:1px solid ${dark ? "#2c2842" : "#eee9df"}">
         ${sk("96px", 16)}<span style="margin-left:auto">${sk("220px", 10)}</span>
       </div>`;

  const footer = stage >= 4
    ? `<div style="padding:12px 18px;border-top:1px solid ${dark ? "#2c2842" : "#eee9df"};font-size:10px;color:${sub};display:flex;font-family:sans-serif"><span>© 巷角咖啡</span><span style="margin-left:auto">南京路 12 号 · 8:00–20:00</span></div>`
    : `<div style="padding:12px 18px;border-top:1px solid ${dark ? "#2c2842" : "#eee9df"}">${sk("60%", 9)}</div>`;

  /* 菜单页：卡片网格（m1）/ 简洁列表（m2）两种方向 */
  if (d.kind === "menu") {
    const items: [string, string, string][] = [
      ["☕", "手冲单品", "28"], ["🥐", "黄油可颂", "18"], ["🍰", "巴斯克蛋糕", "26"],
      ["🧋", "黑糖奶茶", "22"], ["🍋", "冰柠美式", "20"], ["🍪", "曲奇礼盒", "38"],
    ];
    const body = d.id === "cf-m3"
      ? `<div style="display:grid;grid-template-columns:repeat(2,1fr);gap:12px;padding:4px 0 12px">${items.slice(0, 4).map(([e, n, p]) =>
          stage >= 4
            ? `<div style="border-radius:14px;overflow:hidden;border:1px solid #eee9df;font-family:sans-serif">
                 <div style="height:96px;display:flex;align-items:center;justify-content:center;font-size:44px;background:linear-gradient(135deg,#fff,${d.soft})">${e}</div>
                 <div style="display:flex;align-items:center;padding:10px 12px"><span style="font-size:14px;font-weight:700;color:${ink}">${n}</span><span style="margin-left:auto;font-size:13px;font-weight:700;color:${d.accent}">¥ ${p}</span></div>
               </div>`
            : `<div>${sk("100%", 150, 14)}</div>`,
        ).join("")}</div>`
      : d.id === "cf-m4"
      ? `<div style="display:grid;grid-template-columns:1fr 1fr;gap:0 24px;padding:4px 0 12px">${["经典咖啡", "茶与小食"].map((cat, ci) =>
          `<div><div style="font-size:12px;font-weight:800;letter-spacing:3px;color:${d.accent};margin:6px 0 2px;font-family:sans-serif">${cat}</div>${
            items.slice(ci * 3, ci * 3 + 3).map(([e, n, p]) =>
              stage >= 3
                ? `<div style="display:flex;align-items:center;gap:8px;padding:9px 0;border-bottom:1px dashed ${dark ? "#2c2842" : "#eee4d4"};font-family:sans-serif"><span style="font-size:16px">${e}</span><span style="font-size:12px;font-weight:600;color:${ink}">${n}</span><span style="margin-left:auto;font-size:12px;font-weight:700;color:${d.accent}">¥ ${p}</span></div>`
                : `<div style="padding:9px 0">${sk("80%", 13)}</div>`,
            ).join("")
          }</div>`,
        ).join("")}</div>`
      : d.id === "cf-m2"
      ? items.map(([e, n, p]) =>
          stage >= 3
            ? `<div style="display:flex;align-items:center;gap:12px;padding:11px 0;border-bottom:1px solid ${dark ? "#2c2842" : "#f0ebe2"};font-family:sans-serif">
                 <span style="font-size:20px">${e}</span><span style="font-size:13px;font-weight:600;color:${ink}">${n}</span>
                 <span style="margin-left:auto;font-size:12px;color:${sub}">……</span>
                 <span style="font-size:13px;font-weight:700;color:${d.accent}">¥ ${p}</span>
               </div>`
            : `<div style="padding:11px 0">${sk("70%", 14)}</div>`,
        ).join("")
      : `<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:10px;padding:4px 0 12px">${items.map(([e, n, p]) => menuCard(e, n, p)).join("")}</div>`;
    return `${nav}
      <div style="padding:20px 18px 6px;font-family:sans-serif">
        ${stage >= 3 ? `<div style="font-size:20px;font-weight:800;color:${ink}">菜单</div><div style="font-size:11px;color:${sub};margin-top:2px">MENU · 自家烘焙，每日新鲜</div>` : `${sk("120px", 22)}${sk("180px", 10)}`}
      </div>
      <div style="padding:0 18px 8px">${body}</div>
      ${footer}`;
  }

  /* 首屏页：三方向布局 */
  let hero = "";
  if (d.layout === "center") {
    hero = `<div style="text-align:center;padding:34px 18px 22px;background:${stage >= 3 ? d.soft : "transparent"}">
      ${stage >= 3 ? `<div style="font-size:12px;letter-spacing:6px;color:${d.accent};font-family:sans-serif">COMMUNITY ROASTERY</div>` : sk("40%", 10)}
      <div style="margin-top:10px">${stage >= 3 ? `<div style="font-size:26px;font-weight:800;color:${ink};font-family:sans-serif">一杯好咖啡，巷口见</div>` : sk("70%", 26)}</div>
      <div style="margin-top:8px">${stage >= 3 ? `<div style="font-size:12px;color:${sub};font-family:sans-serif">自家烘焙 · 街坊价格 · 到店自取</div>` : sk("50%", 11)}</div>
      ${button("看看菜单")}
    </div>`;
  } else if (d.layout === "split") {
    hero = `<div style="display:flex;gap:14px;padding:28px 18px;align-items:center">
      <div style="flex:1.2">
        ${stage >= 3 ? `<div style="font-size:24px;font-weight:800;color:${ink};line-height:1.3;font-family:sans-serif">清晨六点，<br/>第一炉豆子为您而烘</div>` : sk("90%", 24)}
        <div style="margin-top:8px">${stage >= 3 ? `<div style="font-size:12px;color:${sub};font-family:sans-serif">自家烘焙 · 街坊价格 · 到店自取</div>` : sk("60%", 11)}</div>
        ${button("到店逛逛")}
      </div>
      <div style="flex:1">${stage >= 4 ? `<div style="height:120px;border-radius:14px;background:linear-gradient(135deg,${d.soft},${d.accent});display:flex;align-items:center;justify-content:center;font-size:40px">☕</div>` : sk("100%", 120, 14)}</div>
    </div>`;
  } else {
    hero = `<div style="padding:30px 18px 20px;background:${stage >= 3 ? d.soft : "transparent"}">
      ${stage >= 3 ? `<div style="font-size:34px;font-weight:900;line-height:1.15;color:${stage >= 4 ? d.accent : ink};font-family:sans-serif">巷角咖啡<br/>今天也营业</div>` : sk("80%", 60)}
      <div style="margin-top:10px">${stage >= 3 ? `<div style="font-size:12px;color:${sub};font-family:sans-serif">自家烘焙 · 街坊价格 · 到店自取</div>` : sk("55%", 11)}</div>
      ${button("这就来")}
    </div>`;
  }

  const menu = `<div style="padding:6px 18px 20px">
    <div style="margin-bottom:8px">${stage >= 3 ? `<div style="font-size:14px;font-weight:700;color:${ink};font-family:sans-serif">本周推荐</div>` : sk("90px", 15)}</div>
    <div style="display:flex;gap:10px">${menuCard("☕", "手冲单品", "28")}${menuCard("🥐", "黄油可颂", "18")}${menuCard("🍰", "巴斯克", "26")}</div>
  </div>`;

  return `${nav}${hero}${menu}${footer}`;
}

/* ---------- 设计规范（token 对） ----------
 * 规范随定稿刷新（项目级单一正本、最后定稿覆盖）——具体色值跟定稿那张走，
 * 推导归引擎（咖啡类直接取候选 design 的 accent/soft）。
 */
export type SpecTokens = { version: number; pairs: [string, string][] };

export const TOWEL_SPEC: Record<string, [string, string][]> = {
  "tw-1-1": [["主色", "#3b82f6"], ["辅色", "#93c5fd"], ["底色", "#f8fafc"], ["圆角", "18px"]],
  "tw-1-2": [["主色", "#f97316"], ["辅色", "#fdba74"], ["底色", "#fffbeb"], ["圆角", "18px"]],
  "tw-1-3": [["主色", "#34d399"], ["辅色", "#a7f3d0"], ["底色", "#f8fafc"], ["圆角", "18px"]],
  "tw-2-1": [["主色", "#f43f5e"], ["辅色", "#fbbf24"], ["点缀", "#38bdf8"], ["底色", "#fff7ed"], ["圆角", "18px"]],
  "tw-2-2": [["主色", "#8b5cf6"], ["辅色", "#f472b6"], ["底色", "#faf5ff"], ["圆角", "18px"]],
  "tw-2-3": [["主色", "#10b981"], ["辅色", "#f59e0b"], ["点缀", "#ef4444"], ["底色", "#f0fdf4"], ["圆角", "18px"]],
  "tag-1-1": [["主色", "#f97316"], ["辅色", "#fdba74"], ["底色", "#fffbeb"], ["圆角", "14px"]],
  "tag-1-2": [["主色", "#8b5cf6"], ["辅色", "#f472b6"], ["底色", "#faf5ff"], ["圆角", "14px"]],
  "tag-2-1": [["主色", "#f43f5e"], ["辅色", "#fbbf24"], ["点缀", "#38bdf8"], ["底色", "#fff7ed"], ["圆角", "14px"]],
  "tag-2-2": [["主色", "#10b981"], ["辅色", "#f59e0b"], ["点缀", "#ef4444"], ["底色", "#f0fdf4"], ["圆角", "14px"]],
};
