// 用户旅程冒烟·浏览器腿：真实 Chromium 走「建项目 → 进对话 → 等 BA 首问 →
// 打字作答 → 等下一问」，断言 SSE 驱动的内容真实出现在指令区、且访谈循环能续上。
// 这是唯一能抓住「传输层穿透」类故障的 seam（例：Next compress 把代理 SSE 扣在
// gzip 缓冲里，EventSource onopen 后一个事件都收不到——curl 不带 Accept-Encoding
// 抓不到，只有浏览器能抓到）。
//
// 判据三级：
//   FAIL      指令区只有静态空态文案 → SSE 事件未穿透（症状「进入对话界面没反应」）
//   PARTIAL   出现错误提示（本轮回复中断…）→ SSE 穿透正常，后端报错（如缺模型 key）
//   PASS      首问出现 + 打字作答 + 下一问出现 → 用户视角访谈循环完整可用
//
// 用法（aiplatform-web/ 下，前提 dev 3333 + 后端 8888 在跑）：
//   node scripts/user-journey-ui.mjs
// 环境变量：WATCH_MS 单问等待（默认 180s，DeepSeek reasoning 轮实测 1~2 分钟）；
// cookie jar 由 aiplatform-server/scripts/login.sh /tmp/aiplatform-user-journey-jar 产出。
import { readFileSync } from "node:fs";
import { chromium } from "@playwright/test";

const JAR = process.env.JAR ?? "/tmp/aiplatform-user-journey-jar";
const BASE = process.env.BASE ?? "http://localhost:3333";
const WATCH_MS = Number(process.env.WATCH_MS ?? 180000);
const STATIC_HINT = "把想法告诉需求分析师"; // 静态空态文案，出现它 ≠ SSE 活了

const jar = readFileSync(JAR, "utf8");
const session = jar.match(/aiplatform_session\t(\S+)/)?.[1];
if (!session) { console.error("no aiplatform_session in jar（先跑 login.sh）"); process.exit(2); }

const browser = await chromium.launch();
const ctx = await browser.newContext();
await ctx.addCookies([{ name: "aiplatform_session", value: session, domain: "localhost", path: "/" }]);
const page = await ctx.newPage();

const problems = [];
page.on("console", (m) => m.type() === "error" && problems.push(m.text().slice(0, 200)));
page.on("requestfailed", (r) => problems.push(`REQFAIL ${r.url().slice(0, 120)} ${r.failure()?.errorText}`));

await page.goto(`${BASE}/`, { waitUntil: "networkidle" });
await page.fill("textarea", "浏览器冒烟：给一家手工咖啡烘焙店做介绍官网，展示豆子产地和冲煮方法");
await page.click('button[aria-label="开始"]');
try {
  await page.waitForURL(/\/projects\/[^/]+/, { timeout: 15000 });
} catch {
  console.error(`FAIL ✗ 未跳转项目页（当前 ${page.url()}）`);
  await browser.close(); process.exit(1);
}
const projectId = page.url().match(/\/projects\/([^/?#]+)/)?.[1];
console.log(`项目页 OK（projectId=${projectId}）`);

const commandArea = page.locator("main").first();
const classify = (text) => {
  if (/本轮回复中断|回复失败/.test(text)) return "error";
  const body = text.split(STATIC_HINT).pop() ?? text;
  if (/[？?]/.test(body)) return "question";
  return "static";
};

/** 轮询指令区直到分类变化（或超时返回当前分类）。 */
async function awaitKind(unknownOk, ...targets) {
  for (let waited = 0; waited < WATCH_MS; waited += 5000) {
    await page.waitForTimeout(5000);
    const text = (await commandArea.innerText().catch(() => "")).trim();
    const kind = classify(text);
    if (targets.includes(kind)) return { kind, text };
  }
  return { kind: "timeout", text: (await commandArea.innerText().catch(() => "")).trim() };
}

// 1) 等 BA 首问（用户感知：问答卡出现）
const first = await awaitKind(null, "question", "error");
if (first.kind === "error") {
  console.log(`PARTIAL △ SSE 穿透正常；后端报错：${first.text.match(/本轮回复中断[^\n]{0,120}/)?.[0] ?? ""}`);
  await ctx.request.delete(`${BASE}/api/projects/${projectId}`).catch(() => {});
  await browser.close(); process.exit(0);
}
if (first.kind !== "question") {
  console.error(`FAIL ✗ ${WATCH_MS / 1000}s 内指令区无问答卡（kind=${first.kind}）——对应症状「进入对话界面没反应」`);
  await page.screenshot({ path: "/tmp/ui-journey-static.png", fullPage: true });
  await ctx.request.delete(`${BASE}/api/projects/${projectId}`).catch(() => {});
  await browser.close(); process.exit(1);
}
console.log("PASS ✓ BA 首问出现（问答卡）");
await page.screenshot({ path: "/tmp/ui-journey-q1.png", fullPage: true });
console.log("== 首问内容:", (first.text.split(STATIC_HINT).pop() ?? "").trim().slice(0, 200).replace(/\n/g, " | "));

// 2) 用户作答：直接打字发送（挂起中输入即作答——与真实 UI 行为一致）
const input = page.locator("textarea, input[type=text]").last();
await input.fill("以线上预约体验为主：展示豆子产地与冲煮方法，访客可以留言，请继续");
await input.press("Enter");
console.log("已打字作答，等下一问…");

// 3) 等下一问（作答后 BA 续跑——需先离开 question 分类再回到 question）
let next = { kind: "question", text: first.text }; // 起点即 question，等待“新的 question 文本”
for (let waited = 0; waited < WATCH_MS; waited += 5000) {
  await page.waitForTimeout(5000);
  const text = (await commandArea.innerText().catch(() => "")).trim();
  const kind = classify(text);
  if (kind === "error") { next = { kind, text }; break; }
  if (kind === "question" && text.length > first.text.length + 30) { next = { kind, text }; break; }
}

await page.screenshot({ path: "/tmp/ui-journey-q2.png", fullPage: true });
const tail = (next.text.split(STATIC_HINT).pop() ?? "").trim();
if (next.kind === "error") {
  console.error(`FAIL ✗ 作答后访谈中断：${tail.match(/本轮回复中断[^\n]{0,120}/)?.[0] ?? tail.slice(0, 150)}`);
  await ctx.request.delete(`${BASE}/api/projects/${projectId}`).catch(() => {});
  await browser.close(); process.exit(1);
}
if (next.kind === "question") {
  console.log("PASS ✓ 作答续跑 OK（下一问出现）");
  console.log("== 下一问内容:", tail.slice(-200).replace(/\n/g, " | "));
} else {
  console.error(`FAIL ✗ 作答后 ${WATCH_MS / 1000}s 无下一问（用户视角「发消息半天没反应」）`);
}

console.log("== 指令区最终内容:\n", tail.slice(0, 600));
if (problems.length) console.log("== console/网络异常:", problems.slice(0, 5));
await ctx.request.delete(`${BASE}/api/projects/${projectId}`).catch(() => {});
console.log("（现场已清理：DELETE /api/projects/" + projectId + "）");
await browser.close();

if (next.kind === "question") { console.log("PASS ✓ 浏览器旅程全绿（首问 → 作答 → 下一问）"); process.exit(0); }
process.exit(1);
