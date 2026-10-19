// 移动视口抽验·浏览器腿（#350 验收锚）：真实 Chromium 以 1440/1280/390/360 四档
// 视口抽验上线版关键页（首页/项目列表/需求分析/Demo 体验/签约付款）——
//   ①全页横向零溢出（破点④复核：旧首页出血卡区已随 #345 退役，新首页/列表复核）
//   ②窄屏导航抽屉有打开入口且可开（破点①消解）
//   ③阶段页移动单区域切换件在场、可切（对话/文档/预览，13.2）
//   ④预览手机档框宽不破视口（破点②消解：min(390px,100%)）
//   ⑤Demo 手机说明位（预览真在跑才出——沙箱不在跑记 SKIP 不记 FAIL）
//
// 判据：FAIL 硬失败（溢出/入口缺失/切换失灵）；SKIP 环境不满足（无项目/无在跑
// 预览）；其余 PASS。退出码＝FAIL 数非零即 1。
//
// 用法（aiplatform-web/ 下，前提 dev 3333 + 后端 8888 在跑）：
//   node scripts/mobile-viewport-ui.mjs
// 环境变量：BASE（默认 http://localhost:3333）；JAR（会话 cookie jar，由
// aiplatform-server/scripts/login.sh 产出——缺 jar 只跑匿名面：首页四档）；
// PROJECT_ID（缺省取项目列表首个）。
import { existsSync, readFileSync } from "node:fs";
import { chromium } from "@playwright/test";

const JAR = process.env.JAR ?? "/tmp/aiplatform-user-journey-jar";
const BASE = process.env.BASE ?? "http://localhost:3333";
const VIEWPORTS = [
  { name: "1440 桌面", width: 1440, height: 900 },
  { name: "1280 桌面", width: 1280, height: 800 },
  { name: "390 手机", width: 390, height: 844 },
  { name: "360 手机", width: 360, height: 800 },
];

const results = [];
const record = (level, item, note = "") => {
  results.push({ level, item, note });
  console.log(`${level} ${item}${note ? `（${note}）` : ""}`);
};

/** 横向溢出检查：文档宽不超视口（1px 容差）。 */
async function checkNoOverflow(page, label) {
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }));
  if (scrollWidth > innerWidth + 1) {
    record("FAIL", `${label} 横向溢出`, `scrollWidth=${scrollWidth} > ${innerWidth}`);
  } else {
    record("PASS", `${label} 无横向溢出`);
  }
}

let session;
if (existsSync(JAR)) {
  session = readFileSync(JAR, "utf8").match(/aiplatform_session\t(\S+)/)?.[1];
}
if (!session) console.log("（无会话 jar——只跑匿名面：首页四档）");

const browser = await chromium.launch();

// ---------- 匿名面：首页四档 ----------
for (const vp of VIEWPORTS) {
  const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
  const page = await ctx.newPage();
  await page.goto(`${BASE}/`, { waitUntil: "networkidle" });
  await checkNoOverflow(page, `首页 ${vp.name}`);
  await ctx.close();
}

if (session) {
  // 取抽验项目：PROJECT_ID 或列表首个
  let projectId = process.env.PROJECT_ID;
  const ctx390 = await browser.newContext({ viewport: { width: 390, height: 844 } });
  await ctx390.addCookies([
    { name: "aiplatform_session", value: session, domain: "localhost", path: "/" },
  ]);
  const page = await ctx390.newPage();
  if (!projectId) {
    await page.goto(`${BASE}/projects`, { waitUntil: "domcontentloaded" }); // 同源上下文（fetch 前提）
    const list = await page.evaluate(async () => {
      const res = await fetch("/api/projects", { credentials: "include" });
      if (!res.ok) return [];
      const body = await res.json();
      return Array.isArray(body) ? body : (body?.data ?? []); // 错误信封外裸数组两形兼容
    });
    projectId = list[0]?.id != null ? String(list[0].id) : undefined;
  }

  if (!projectId) {
    record("SKIP", "项目页族抽验", "无可用项目（PROJECT_ID 可指定）");
  } else {
    // ---------- 项目列表 390 ----------
    await page.goto(`${BASE}/projects`, { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(1500);
    await checkNoOverflow(page, "项目列表 390");

    // ---------- 需求分析页 390：抽屉入口 + 单区域切换 ----------
    await page.goto(`${BASE}/projects/${projectId}`, { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(1500);
    await checkNoOverflow(page, "需求分析页 390");

    const navTrigger = page.getByRole("button", { name: "打开导航" });
    if (await navTrigger.isVisible().catch(() => false)) {
      record("PASS", "导航抽屉有打开入口（破点①）");
      await navTrigger.click();
      const drawer = page.getByRole("dialog", { name: "项目导航" });
      if (await drawer.isVisible().catch(() => false)) {
        record("PASS", "导航抽屉可开");
      } else {
        record("FAIL", "导航抽屉点击后未开");
      }
      await page.keyboard.press("Escape");
    } else {
      record("FAIL", "导航抽屉打开入口缺失（破点①未消）");
    }

    // 单区域切换（有 PRD 才有切换件——闲聊期对话占全宽为正确形态）
    const chatArea = page.locator('[data-area="chat"]');
    if ((await chatArea.count()) > 0) {
      await page.getByRole("button", { name: "文档", exact: true }).click();
      const docVisible = await page.locator('[data-area="doc"]').isVisible();
      const chatHidden = !(await chatArea.isVisible());
      if (docVisible && chatHidden) {
        record("PASS", "需求分析页单区域切换（对话→文档）");
      } else {
        record("FAIL", "需求分析页单区域切换失灵");
      }
    } else {
      record("SKIP", "需求分析页单区域切换", "项目尚无 PRD（闲聊期）");
    }

    // ---------- Demo 体验页 390 ----------
    await page.goto(`${BASE}/projects/${projectId}/demo`, { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(1500);
    await checkNoOverflow(page, "Demo 体验页 390");
    const previewArea = page.locator('[data-area="preview"]');
    if ((await previewArea.count()) > 0) {
      await page.getByRole("button", { name: "对话", exact: true }).click();
      if (await page.locator('[data-area="chat"]').isVisible()) {
        record("PASS", "Demo 页单区域切换（预览→对话）");
      } else {
        record("FAIL", "Demo 页单区域切换失灵");
      }
      await page.getByRole("button", { name: "预览", exact: true }).click();
    } else {
      record("FAIL", "Demo 页移动切换件缺失");
    }

    // 预览手机档框宽（破点②复核）——需预览真在跑
    const notice = page.getByRole("note", { name: "手机体验说明" });
    if (await notice.isVisible().catch(() => false)) {
      record("PASS", "Demo 手机说明位在场（破点 R8）");
      await page.getByRole("button", { name: "手机预览" }).click();
      const ok = await page.evaluate(() => {
        const frame = document.querySelector('[aria-label="Demo 沙箱窗口"] iframe');
        return frame ? frame.getBoundingClientRect().width <= window.innerWidth : null;
      });
      if (ok === null) {
        record("SKIP", "预览手机档框宽", "iframe 不在场");
      } else if (ok) {
        record("PASS", "预览手机档框宽不破视口（破点②）");
      } else {
        record("FAIL", "预览手机档框被裁（破点②未消）");
      }
    } else {
      record("SKIP", "Demo 手机说明位/手机档框宽", "预览未在跑（无沙箱或生成中）");
    }

    // ---------- 签约付款页 390 ----------
    await page.goto(`${BASE}/projects/${projectId}/contract`, { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(1500);
    await checkNoOverflow(page, "签约付款页 390");
  }

  // ---------- 360 复核（需求分析页 + 首页列表族） ----------
  const ctx360 = await browser.newContext({ viewport: { width: 360, height: 800 } });
  await ctx360.addCookies([
    { name: "aiplatform_session", value: session, domain: "localhost", path: "/" },
  ]);
  const page360 = await ctx360.newPage();
  await page360.goto(`${BASE}/projects`, { waitUntil: "domcontentloaded" });
  await page360.waitForTimeout(1500);
  await checkNoOverflow(page360, "项目列表 360");
  if (projectId) {
    await page360.goto(`${BASE}/projects/${projectId}`, { waitUntil: "domcontentloaded" });
  await page360.waitForTimeout(1500);
    await checkNoOverflow(page360, "需求分析页 360");
    await page360.goto(`${BASE}/projects/${projectId}/demo`, { waitUntil: "domcontentloaded" });
  await page360.waitForTimeout(1500);
    await checkNoOverflow(page360, "Demo 体验页 360");
    await page360.goto(`${BASE}/projects/${projectId}/contract`, { waitUntil: "domcontentloaded" });
  await page360.waitForTimeout(1500);
    await checkNoOverflow(page360, "签约付款页 360");
  }
  await ctx360.close();

  // ---------- 1280 桌面复核 ----------
  const ctx1280 = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  await ctx1280.addCookies([
    { name: "aiplatform_session", value: session, domain: "localhost", path: "/" },
  ]);
  const page1280 = await ctx1280.newPage();
  if (projectId) {
    await page1280.goto(`${BASE}/projects/${projectId}`, { waitUntil: "domcontentloaded" });
  await page1280.waitForTimeout(1500);
    await checkNoOverflow(page1280, "需求分析页 1280");
    await page1280.goto(`${BASE}/projects/${projectId}/demo`, { waitUntil: "domcontentloaded" });
  await page1280.waitForTimeout(1500);
    await checkNoOverflow(page1280, "Demo 体验页 1280");
  }
  await ctx1280.close();
  await ctx390.close();
}

await browser.close();

const fails = results.filter((r) => r.level === "FAIL").length;
const skips = results.filter((r) => r.level === "SKIP").length;
console.log(`\n抽验收口：${results.length - fails - skips} PASS / ${fails} FAIL / ${skips} SKIP`);
process.exit(fails > 0 ? 1 : 0);
