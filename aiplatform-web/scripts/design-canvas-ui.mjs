// 设计稿画布冒烟·浏览器腿（#294 验收：点选路由/渐进时序/下载门）：真实 Chromium
// 走「进设计项目 → 画布点选稿卡 → 作用域 chip＋发散档呈现 → 发送路由 → 点开预览
// → 下载动作吃门」。这是唯一能抓住 pointer 手势合成（拖排/点选分流）与 iframe
// 取件穿透类故障的 seam（curl 抓不到浏览器层）。
//
// 前提（活体走查时）：
//   1) dev 前后端在跑（scripts/dev.sh；后端 8888 前端 3333）
//   2) 已有一个设计项目且画布上有稿（走完设计过程或已有收尾卡）——PROJECT_ID
//      指定该项目，缺省取列表第一个项目
//   3) cookie jar：aiplatform-server/scripts/login.sh /tmp/aiplatform-design-ui-jar
//
// 判据三级：
//   FAIL      画布不出稿卡 / 点选不出作用域 chip —— 交互层断裂
//   PARTIAL   预览或下载面未按预期（如 402 信封未呈现门语义）
//   PASS      点选路由＋chip＋预览＋下载门四环节全过（未付费 402 门语义如实
//             呈现，或已支付 200 落盘——两者都算门行为正确）
//
// 用法（aiplatform-web/ 下）：node scripts/design-canvas-ui.mjs
// 环境变量：PROJECT_ID（目标设计项目）、WATCH_MS（稿卡等待上限，默认 60s）
import { readFileSync } from "node:fs";
import { chromium } from "@playwright/test";

const JAR = process.env.JAR ?? "/tmp/aiplatform-design-ui-jar";
const BASE = process.env.BASE ?? "http://localhost:3333";
const WATCH_MS = Number(process.env.WATCH_MS ?? 60000);
const PROJECT_ID = process.env.PROJECT_ID ?? null;

const jar = readFileSync(JAR, "utf-8");
const session = jar.match(/aiplatform_session\t(\S+)/)?.[1];
if (!session) { console.error("no aplatform_session in jar（先跑 login.sh）"); process.exit(2); }

const browser = await chromium.launch();
const ctx = await browser.newContext();
await ctx.addCookies([{ name: "aiplatform_session", value: session, domain: "localhost", path: "/" }]);
const page = await ctx.newPage();

const problems = [];
page.on("console", (m) => m.type() === "error" && problems.push(m.text().slice(0, 200)));
page.on("requestfailed", (r) => problems.push(`REQFAIL ${r.url().slice(0, 120)} ${r.failure()?.errorText}`));

// ---------- 定位项目 ----------
let projectId = PROJECT_ID;
if (!projectId) {
  await page.goto(`${BASE}/projects`, { waitUntil: "networkidle" });
  await page.waitForTimeout(1500);
  const href = await page.locator('a[href^="/projects/"]').first().getAttribute("href").catch(() => null);
  if (!href) { console.error("FAIL ✗ 项目列表无项目可进（先建一个设计项目）"); await browser.close(); process.exit(1); }
  projectId = href.split("/").pop();
}
console.log(`目标项目：${projectId}`);

// ① 稿卡等待（画布是产物面——稿在才可测）
await page.goto(`${BASE}/projects/${projectId}`, { waitUntil: "networkidle" });
const canvasUrl = await (async () => {
  // 设计稿 tab（范式注册表——自动挂载或手动加挂都在 tab 簇上）
  for (let waited = 0; waited < WATCH_MS; waited += 3000) {
    const tab = page.getByRole("tab", { name: /设计稿/ }).or(page.getByRole("button", { name: /设计稿/ }));
    if (await tab.count() > 0) return "tab";
    await page.waitForTimeout(3000);
  }
  return null;
})();
if (!canvasUrl) {
  console.error(`FAIL ✗ ${WATCH_MS / 1000}s 内无「设计稿」tab（设计过程未启动或 tab 未挂载）`);
  await browser.close(); process.exit(1);
}
await page.getByRole("tab", { name: /设计稿/ }).or(page.getByRole("button", { name: /设计稿/ })).first().click();

const card = page.locator("[data-draft-card]").first();
try {
  await card.waitFor({ state: "visible", timeout: WATCH_MS });
} catch {
  console.error(`FAIL ✗ 画布无稿卡（data-draft-card 未出现——收尾卡稿清单/文件树/清单三源断裂？）`);
  await page.screenshot({ path: "/tmp/design-canvas-empty.png", fullPage: true });
  await browser.close(); process.exit(1);
}
console.log("① 画布稿卡呈现 OK");

// ② 点选作用域（抬起合成——不拖动即点选）
await card.click();
const scopeChip = page.locator("[data-design-scope]");
try {
  await scopeChip.waitFor({ state: "visible", timeout: 5000 });
  const chipText = (await scopeChip.innerText()).trim();
  console.log(`② 点选作用域 OK：${chipText.split("\n")[0]}`);
} catch {
  console.error("FAIL ✗ 点稿卡后对话区无作用域 chip（就「…」改）——点选路由断裂");
  await page.screenshot({ path: "/tmp/design-canvas-pick-fail.png", fullPage: true });
  await browser.close(); process.exit(1);
}
// 发散度三档在场
for (const label of ["微调", "探索", "大胆"]) {
  if (!(await page.locator("[data-design-scope]").getByText(label, { exact: true }).count())) {
    console.error(`FAIL ✗ 发散度 chip 缺「${label}」`);
    await browser.close(); process.exit(1);
  }
}
console.log("   发散度三档 chip 在场 OK（微调/探索/大胆）");

// 渐进时序（条件判据——live 会话在场才测）：占位卡与在途稿
const placeholder = await page.locator("[data-draft-placeholder]").count();
const incoming = await page.getByText("正在写…").count();
console.log(`   渐进面：占位卡 ${placeholder} 张、在途稿标记 ${incoming} 处（live 会话${placeholder + incoming > 0 ? "" : "不在途——本环节跳过，活体改稿时补验"}）`);

// ③ 点开预览＋下载门
await page.locator("[data-preview-open]").first().click();
const modal = page.locator("[data-design-preview]");
try {
  await modal.waitFor({ state: "visible", timeout: 5000 });
  console.log("③ 点开预览 OK（放大面挂载）");
} catch {
  console.error("FAIL ✗ 预览弹窗未挂载（data-design-preview）");
  await page.screenshot({ path: "/tmp/design-canvas-preview-fail.png", fullPage: true });
  await browser.close(); process.exit(1);
}

const downloadResponses = [];
page.on("response", (res) => {
  if (res.url().includes("/design-drafts/png") || res.url().includes("/files/download")) {
    downloadResponses.push({ url: res.url().slice(0, 100), status: res.status() });
  }
});
const toast = page.locator("[data-sonner-toast]").last();
await page.locator("[data-download-png]").first().click();
await page.waitForTimeout(4000);
const pngHit = downloadResponses.find((r) => r.url().includes("/design-drafts/png"));
const toastText = (await toast.innerText().catch(() => "")) || "";
if (pngHit?.status === 402 || /还未支付/.test(toastText)) {
  console.log("④ 下载门 OK：未付费被拦（402 ORD_015 信封 message 如实呈现）");
  console.log(`   ${toastText.slice(0, 80)}`);
} else if (pngHit?.status === 200) {
  console.log("④ 下载门 OK：已支付可得（PNG 位图化 200 落盘）");
} else {
  console.log(`PARTIAL △ 下载动作未按预期（命中 ${JSON.stringify(downloadResponses)}、toast「${toastText.slice(0, 60)}」）`);
}

if (problems.length > 0) {
  console.log(`PARTIAL △ 控制台/网络有异常（${problems.length} 条，首条：${problems[0]}）`);
} else {
  console.log("PASS ✓ 点选路由＋chip＋预览＋下载门四环节全过（真浏览器层）");
}
await browser.close();
