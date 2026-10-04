#!/usr/bin/env node
// SVG→PNG 位图出口旁路渲染器（#284，ADR-0026/0027）：vector 原生物件（logo 源
// 文件等）的 PNG 衍生特价路径——resvg 纯栅格化、零浏览器（不启 chromium），
// 原生尺寸渲染（多分辨率衍生 2x/4x 是备案项，不预埋）。系统字体经 fontconfig
// 解析（镜像含 CJK 字体），<text> 可渲。
//
// 用法：node render-svg.mjs <svg 绝对路径> <png 落点>
// 退出码：0 = 渲染成（PNG 落盘）、2 = 渲染失败、3 = 用法错误。
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

import { Resvg } from '@resvg/resvg-js';

const [source, out] = process.argv.slice(2);
if (!source || !out) {
  console.error('用法: render-svg.mjs <svg> <png-out>');
  process.exit(3);
}

try {
  mkdirSync(dirname(out), { recursive: true });
  const resvg = new Resvg(readFileSync(source), { font: { systemFonts: true } });
  writeFileSync(out, resvg.render().asPng());
} catch (error) {
  console.error('SVG 渲染失败: ' + (error && error.message ? error.message : error));
  process.exit(2);
}
