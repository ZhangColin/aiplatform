// 样式探针（部署文档 §9 坑点 9 的定位工具）：无头浏览器量「消息气泡/输入框」的计算样式，
// 可仿真深色偏好，用于排查「线上与本地样式不一致」类问题。
//
// 用法（在带 chromium 的 dev 镜像里跑，本地 dev 服务器须经 socat 以 localhost Host 访问——
// next dev 对非 localhost Host 的静态 chunk 请求回 403，见部署文档 §9 坑点 10）：
//   # 目标与 cookie 按需改 targets；guest cookie 用 POST /api/projects 现造
//   docker run --rm -v /tmp:/work --add-host host.docker.internal:host-gateway \
//     aiplatform/dev:0.13 sh -c '
//       apt-get update -qq >/dev/null && apt-get install -y -qq socat >/dev/null
//       socat TCP-LISTEN:9333,fork,reuseaddr TCP:host.docker.internal:3333 &
//       sleep 1; cp /work/style-probe.mjs /opt/render/ && cd /opt/render && node style-probe.mjs'
//
// 判读：输入框容器（rounded-2xl bg-background…）应为浅色（如 rgb(248,250,252)）；
// 出现 lab(2.x…)≈黑即 .dark 令牌生效（主题问题）。气泡底色应为
// oklch(0.93 0.09 276.97) 形态的淡紫。
import { chromium } from 'playwright';

const targets = [
  // { label: 'dev', url: 'http://localhost:9333/projects/<pid>', cookieUrl: 'http://localhost:9333', gk: '<aiplatform_guest 值>' },
  { label: 'prod', url: 'http://host.docker.internal:3334/projects/<pid>', cookieUrl: 'http://host.docker.internal', gk: '<aiplatform_guest 值>' },
];

const browser = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM, headless: true });
for (const t of targets) {
  if (t.url.includes('<pid>')) continue;
  const ctx = await browser.newContext({ viewport: { width: 1680, height: 1000 }, colorScheme: 'dark' });
  await ctx.addCookies([{ name: 'aiplatform_guest', value: t.gk, url: t.cookieUrl }]);
  const page = await ctx.newPage();
  try {
    await page.goto(t.url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForSelector('textarea', { timeout: 90000 });
    await page.waitForTimeout(1200);
    const info = await page.evaluate(() => {
      const ta = document.querySelector('textarea');
      const bubble = document.querySelector('[data-slot="bubble-content"]');
      const wrap = ta?.closest('[class*="bg-background"]');
      return {
        colorScheme: getComputedStyle(document.documentElement).colorScheme,
        inputWrapBg: wrap ? getComputedStyle(wrap).backgroundColor : null,
        bubbleBg: bubble ? getComputedStyle(bubble).backgroundColor : null,
      };
    });
    console.log('=== ' + t.label + ' ===');
    console.log(JSON.stringify(info));
    await page.screenshot({ path: `/work/probe-${t.label}.png` });
  } catch (e) {
    console.log('=== ' + t.label + ' ERROR: ' + e.message.slice(0, 120));
  }
  await ctx.close();
}
await browser.close();
