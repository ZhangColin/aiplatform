// 预览哨兵纯断言逻辑单测（#422 缝 A：node:test 内建 runner，零新基建）。
// 只测纯函数（归类/识别/构建/解析）；浏览器实跑属缝 B（live 车道：本地排练＋生产真跑，
// 留痕票内评论），不在此测。
//
// 判据独立来源：归类口径来自 #408/#416 spec（「断言页面与关键子资源真实加载」＋锚案例
// chunk/字体 403 形态）与 rehearsal.sh ⑤（自恢复提示页标记）；不反推实现。
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  classifyResources,
  isRecoveryPage,
  buildLaunchCandidates,
  parseArgs,
} from './preview-sentinel.mjs';

// ---------------------------------------------------------------- 归类
// 归类口径带「页面源」上下文：关键面只看同源请求（平台自身链路），域外请求（应用跳转
// 的第三方/OIDC localhost 等）不归发布回归盯。API 面（fetch/xhr）401/403＝匿名冒烟的
// 鉴权健康形态（release.sh 既有 curl 冒烟即断言 /api/projects 401）；404/5xx＝断供。
test('关键子资源：同源 script/css/font/document 零失败＋image 404 不算失败', () => {
  const r = classifyResources([
    { url: 'https://s.com/', resourceType: 'document', status: 200, failed: false },
    { url: 'https://s.com/_next/static/chunk.js', resourceType: 'script', status: 200, failed: false },
    { url: 'https://s.com/_next/static/app.css', resourceType: 'stylesheet', status: 200, failed: false },
    { url: 'https://s.com/logo.png', resourceType: 'image', status: 404, failed: false },
    { url: 'https://s.com/api/health', resourceType: 'fetch', status: 200, failed: false },
  ], 'https://s.com');
  assert.deepEqual(r.failures, []);
  assert.equal(r.scriptsLoaded, 1);
});

test('同源 API 401 是健康形态（匿名冒烟鉴权面）；404/500 记失败', () => {
  const r = classifyResources([
    { url: 'https://s.com/api/projects?page=1', resourceType: 'fetch', status: 401, failed: false },
    { url: 'https://s.com/api/projects', resourceType: 'xhr', status: 403, failed: false },
  ], 'https://s.com');
  assert.deepEqual(r.failures, []);
  const bad = classifyResources([
    { url: 'https://s.com/api/projects', resourceType: 'fetch', status: 404, failed: false },
    { url: 'https://s.com/api/x', resourceType: 'xhr', status: 500, failed: false },
  ], 'https://s.com');
  assert.equal(bad.failures.length, 2);
  assert.ok(bad.failures.every((f) => f.kind === 'http'));
});

test('域外请求不盯（OIDC localhost 跳转拒连、第三方失败都不算发布断供）', () => {
  const r = classifyResources([
    { url: 'https://s.com/', resourceType: 'document', status: 200, failed: false },
    { url: 'http://identity.localhost:10001/authorize?x=1', resourceType: 'fetch', status: null, failed: 'net::ERR_CONNECTION_REFUSED' },
    { url: 'https://cdn.example.com/lib.js', resourceType: 'script', status: 500, failed: false },
  ], 'https://s.com');
  assert.deepEqual(r.failures, []);
  assert.equal(r.scriptsLoaded, 0); // 域外 script 不计入「壳有 JS」事实
});

test('API 面的 ERR_ABORTED 容忍（RSC 预取/AbortController 应用侧取消是常态）；静态资产网络错仍严格', () => {
  const tolerated = classifyResources([
    { url: 'https://s.com/?_rsc=p37cr', resourceType: 'fetch', status: null, failed: 'net::ERR_ABORTED' },
  ], 'https://s.com');
  assert.deepEqual(tolerated.failures, []);
  const strict = classifyResources([
    { url: 'https://s.com/_next/static/chunk.js', resourceType: 'script', status: null, failed: 'net::ERR_ABORTED' },
  ], 'https://s.com');
  assert.equal(strict.failures.length, 1);
  assert.equal(strict.failures[0].kind, 'network');
});

test('锚案例形态：同源 script/字体 403 记失败（Origin 闸门/证书类断供的主证据面）', () => {
  const r = classifyResources([
    { url: 'https://p.com/', resourceType: 'document', status: 200, failed: false },
    { url: 'https://p.com/_next/static/chunk.js', resourceType: 'script', status: 403, failed: false },
    { url: 'https://p.com/__nextjs_font/geist.woff2', resourceType: 'font', status: 403, failed: false },
  ], 'https://p.com');
  assert.equal(r.failures.length, 2);
  assert.ok(r.failures.every((f) => f.kind === 'http' && f.status === 403));
  assert.equal(r.scriptsLoaded, 0);
});

test('网络层失败（net::ERR/连接拒）记失败；websocket/ping 不在关键面（HMR 常态）', () => {
  const r = classifyResources([
    { url: 'https://p.com/', resourceType: 'document', status: 200, failed: false },
    { url: 'https://p.com/_next/static/chunk.js', resourceType: 'script', status: null, failed: 'net::ERR_CONNECTION_REFUSED' },
    { url: 'https://p.com/_next/webpack-hmr', resourceType: 'websocket', status: null, failed: 'net::ERR_FAILED' },
    { url: 'https://p.com/ping', resourceType: 'other', status: null, failed: 'net::ERR_ABORTED' },
  ], 'https://p.com');
  assert.equal(r.failures.length, 1);
  assert.equal(r.failures[0].kind, 'network');
  assert.equal(r.failures[0].url, 'https://p.com/_next/static/chunk.js');
});

test('document 5xx（网关断供/提示页形态）记失败', () => {
  const r = classifyResources([
    { url: 'https://p.com/', resourceType: 'document', status: 502, failed: false },
  ], 'https://p.com');
  assert.equal(r.failures.length, 1);
  assert.ok(r.failures.every((f) => f.kind === 'http'));
});

// ---------------------------------------------------------------- 提示页识别
test('自恢复提示页：含 data-aiplatform="recovery" 标记即真（网关对沙箱不在服的回形）', () => {
  const html = '<html><body>系统暂不可用，可能正在启动或休眠中'
    + '<script data-aiplatform="recovery" src="/.aiplatform/recovery.js"></script></body></html>';
  assert.equal(isRecoveryPage(html), true);
});

test('普通页与圈注页都不是提示页（annotation 是正常注入，别误判）', () => {
  const normal = '<html><body><script data-aiplatform="annotation" src="/.aiplatform/annotation.js"></script></body></html>';
  assert.equal(isRecoveryPage('<html><body>hello</body></html>'), false);
  assert.equal(isRecoveryPage(normal), false);
});

// ---------------------------------------------------------------- 启动链
test('缺省启动链：bundled chromium → msedge → chrome（发布机零浏览器下载的现实序）', () => {
  const chain = buildLaunchCandidates({});
  assert.deepEqual(chain.map((c) => c.label), ['chromium', 'msedge', 'chrome']);
  assert.equal('channel' in chain[0].options, false); // bundled 形态不带 channel
  assert.equal(chain[1].options.channel, 'msedge');
  assert.equal(chain[2].options.channel, 'chrome');
});

test('SENTINEL_EXECUTABLE 显式指路＝只试这一条（操作者钉死，不再兜底）', () => {
  const chain = buildLaunchCandidates({ SENTINEL_EXECUTABLE: '/opt/chrome/chrome' });
  assert.equal(chain.length, 1);
  assert.equal(chain[0].options.executablePath, '/opt/chrome/chrome');
});

test('SENTINEL_CHANNEL 优先且后续链去重（msedge 不重复出现）', () => {
  const chain = buildLaunchCandidates({ SENTINEL_CHANNEL: 'msedge' });
  assert.deepEqual(chain.map((c) => c.label), ['msedge', 'chromium', 'chrome']);
  const chain2 = buildLaunchCandidates({ SENTINEL_CHANNEL: 'chrome' });
  assert.deepEqual(chain2.map((c) => c.label), ['chrome', 'chromium', 'msedge']);
});

// ---------------------------------------------------------------- 参数解析
test('两个位置参数（主站/预览 URL）成立，其余缺省', () => {
  const cfg = parseArgs(['http://semgravio.com', 'http://4242.preview.semgravio.com']);
  assert.equal(cfg.mainUrl, 'http://semgravio.com');
  assert.equal(cfg.previewUrl, 'http://4242.preview.semgravio.com');
  assert.equal(cfg.resolveRules, null);
  assert.equal(cfg.previewWindow, null);
  assert.equal(cfg.budgetMs, 300000);
});

test('--resolve 转 chromium host-resolver-rules 形（本地排练把域名指到回环）', () => {
  const cfg = parseArgs([
    'http://semgravio.com', 'http://4242.preview.semgravio.com',
    '--resolve', 'semgravio.com=127.0.0.1,4242.preview.semgravio.com=127.0.0.1',
  ]);
  assert.equal(cfg.resolveRules,
    'MAP semgravio.com 127.0.0.1, MAP 4242.preview.semgravio.com 127.0.0.1');
});

test('--preview-window 与 --budget-ms 解析；缺位置参数抛用法错', () => {
  const cfg = parseArgs([
    'http://a.com', 'http://b.com',
    '--preview-window', '__sentinelChunk=loaded-4242424',
    '--budget-ms', '60000',
  ]);
  assert.deepEqual(cfg.previewWindow, { name: '__sentinelChunk', value: 'loaded-4242424' });
  assert.equal(cfg.budgetMs, 60000);
  assert.throws(() => parseArgs(['http://a.com']), /用法/);
});
