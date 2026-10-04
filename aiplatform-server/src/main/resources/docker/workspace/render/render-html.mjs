#!/usr/bin/env node
// HTML→PNG 位图出口渲染器（#284，ADR-0026/0027）：chromium 保真渲染——「LLM 产
// 任意 HTML 设计稿→PNG 保真」的唯一解，供出稿即渲 / 下载位图化 / 导出衍生触发。
// 与画布呈现分家：设计稿 tab 呈现走用户浏览器 iframe live 渲染（零 chromium），
// 本渲染器只管出口。
//
// 用法：node render-html.mjs <html 绝对路径> <png 落点> <宽 px> <高 px>
// 退出码：0 = 渲染成（PNG 落盘）、2 = 渲染失败（含看门狗超时）、3 = 用法错误。
// 源文件存在性守卫在调用方 shell 壳（退出码 1），本渲染器不做重复守卫。
//
// 细节口径：
// - file:// 装载（非 setContent）：相对资源（物料图等）按 HTML 所在目录解析；
// - networkidle + document.fonts.ready：本地子资源与字体就绪后才截屏；
// - 画幅＝viewport 截屏（固定画幅帧语义，#278——界面类稿是帧不是网站）；
// - 落点父目录按需自建（写入方自带 mkdir 兜底，与布局骨架不冲突）；
// - 浏览器二进制经 RENDER_CHROMIUM 显式指路（镜像内 /opt/chrome 的 legacy 路
//   径 zip，与 playwright 钉版严格配对——不走注册表下载）；环境变量缺省时回落
//   playwright 自管安装（开发机形态）。
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { pathToFileURL } from 'node:url';

import { chromium } from 'playwright';

const [source, out, widthText, heightText] = process.argv.slice(2);
const width = Number(widthText);
const height = Number(heightText);
if (!source || !out
    || !Number.isInteger(width) || width < 1
    || !Number.isInteger(height) || height < 1) {
  console.error('用法: render-html.mjs <html> <png-out> <width> <height>');
  process.exit(3);
}

// 看门狗：单动作超时（goto/截屏各 30s）之外的整体兜底——挂死的渲染不留常驻
// chromium，调用线程（docker exec）不被无限占住。
const watchdog = setTimeout(() => {
  console.error('渲染看门狗超时（120s）');
  process.exit(2);
}, 120_000);
watchdog.unref();

let browser;
let exitCode = 0;
try {
  mkdirSync(dirname(out), { recursive: true });
  browser = await chromium.launch(
      process.env.RENDER_CHROMIUM ? { executablePath: process.env.RENDER_CHROMIUM } : {});
  const page = await browser.newPage({ viewport: { width, height } });
  await page.goto(pathToFileURL(source).href, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: out });
} catch (error) {
  console.error('HTML 渲染失败: ' + (error && error.message ? error.message : error));
  exitCode = 2;
} finally {
  if (browser) {
    await browser.close().catch(() => {});
  }
}
process.exit(exitCode);
