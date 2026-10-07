// 入口两档冒烟·浏览器腿（#299，ADR-0029）：真实 Chromium 走「默认系统主线 →
// 点「先做设计」就地切换 → 设计态提交 → 载荷带终点初值 → 项目页 → 回首页看
// 最近项目卡弱标识」。交互测试已覆盖组件契约，本腿抓的是真实 DOM/路由/网络
// 链路上的穿透类故障（切换件不可点、载荷丢字段、回显不刷新）。
//
// 判据三级：
//   FAIL    切换不生效 / 载荷缺 endpointType / 未跳项目页
//   PARTIAL 链路通了但弱标识不出（列表字段未随新后端透出）
//   PASS    切换 + 载荷 + 回显全绿
//
// 用法（aiplatform-web/ 下，前提 dev 3333 + 后端 8888 在跑）：
//   node scripts/entry-split-ui.mjs
// cookie jar 由 aiplatform-server/scripts/login.sh /tmp/aiplatform-entry-split-jar 产出。
// 副作用：真实建一个设计项目（工作区容器照置备——与 user-journey 冒烟同口径）。
import { readFileSync } from "node:fs";
import { chromium } from "@playwright/test";

const JAR = process.env.JAR ?? "/tmp/aiplatform-entry-split-jar";
const BASE = process.env.BASE ?? "http://localhost:3333";

const jar = readFileSync(JAR, "utf-8");
const session = jar.match(/aiplatform_session\t(\S+)/)?.[1];
if (!session) { console.error("no aiplatform_session in jar（先跑 login.sh）"); process.exit(2); }

const browser = await chromium.launch();
const ctx = await browser.newContext();
await ctx.addCookies([{ name: "aiplatform_session", value: session, domain: "localhost", path: "/" }]);
const page = await ctx.newPage();

const problems = [];
page.on("console", (m) => m.type() === "error" && problems.push(m.text().slice(0, 200)));
page.on("requestfailed", (r) => problems.push(`REQFAIL ${r.url().slice(0, 120)} ${r.failure()?.errorText}`));

let verdict = "PASS";
const fail = (msg) => { verdict = "FAIL"; console.error(`FAIL ✗ ${msg}`); };
const partial = (msg) => { if (verdict === "PASS") verdict = "PARTIAL"; console.error(`PARTIAL △ ${msg}`); };

await page.goto(`${BASE}/`, { waitUntil: "networkidle" });

// ① 默认系统主线零变化：系统态主标 + 系统 placeholder 在场
if (!(await page.getByText("想做什么，直接说").isVisible())) fail("默认态主标缺失（系统主线零变化被破）");
if (!(await page.getByPlaceholder("一句话说说你想做什么…").isVisible())) fail("默认态 placeholder 缺失");

// ② 「先做设计」可见可点、就地切换（不跳页）：主标/placeholder/示例 chips 随切换
await page.getByRole("button", { name: "先做设计" }).click();
await page.waitForTimeout(300);
if (page.url() !== `${BASE}/`) fail("切换发生跳页（应就地切换）");
if (!(await page.getByText("想要什么样子，直接说").isVisible())) fail("设计态主标未切换");
if (!(await page.getByPlaceholder(/一句话说说你想要的设计/).isVisible())) fail("设计态 placeholder 未切换");
if (!(await page.getByRole("button", { name: "给我的咖啡店设计一个 logo" }).isVisible()))
  fail("设计态示例 chips 未切换");

// ③ 可切回：再点回系统态
await page.getByRole("button", { name: "先做系统" }).click();
await page.waitForTimeout(300);
if (!(await page.getByText("想做什么，直接说").isVisible())) fail("切回系统态失败");

// ④ 设计态提交带终点初值：拦 POST /api/projects 载荷
let createBody = null;
page.on("request", (r) => {
  if (r.method() === "POST" && new URL(r.url()).pathname === "/api/projects") {
    createBody = r.postData();
  }
});
await page.getByRole("button", { name: "先做设计" }).click();
await page.getByPlaceholder(/一句话说说你想要的设计/).fill("浏览器腿冒烟：给手工咖啡烘焙店设计一个 logo");
await page.getByRole("button", { name: "发送" }).click();

try {
  await page.waitForURL(/\/projects\/[^/]+/, { timeout: 20000 });
} catch {
  fail(`未跳转项目页（当前 ${page.url()}）`);
  await browser.close(); process.exit(1);
}
console.log(`项目页 OK（${page.url()}）`);

if (!createBody) fail("POST /api/projects 载荷未被捕获");
else {
  const body = JSON.parse(createBody);
  if (body.endpointType !== 1) fail(`载荷缺终点初值（endpointType=${body.endpointType}，应=1）`);
  else console.log("载荷终点初值 OK（endpointType=1）");
}

// ⑤ 回显：回首页看最近项目卡弱标识（文字级「设计」）
await page.goto(`${BASE}/`, { waitUntil: "networkidle" });
await page.waitForTimeout(1000);
const mark = await page.locator('[data-endpoint-type="1"]').first().isVisible().catch(() => false);
if (mark) console.log("项目卡弱标识 OK（设计）");
else partial("最近项目卡未见「设计」弱标识（列表字段未透出？）");

console.log(`\n判据：${verdict}${problems.length ? `\nconsole 异常 ${problems.length} 条（首条：${problems[0]}）` : ""}`);
await browser.close();
process.exit(verdict === "PASS" ? 0 : 1);
