#!/usr/bin/env node
// 预览哨兵（#422，spec #416「预览哨兵」＝#408 grilling 裁决）：发布时一次性外部真浏览器
// 冒烟。Playwright 从发布机外部位点走「平台主站＋代表性预览链路」，断言页面与关键子资源
// 真实加载（复用验证件的浏览器能力与断言口径——真浏览器进真实入口，不是 curl 探活）。
//
// 查新账不查旧账：只验「发布后的当下链路健康」；只盯平台自身变更引入的确定性断供
// （发布回归/网关/证书类——形态＝页面 5xx/提示页、关键子资源 4xx/网络层失败）。
// 失败＝非零退出（发布流水线红）＋醒目横幅；回滚人工决，本脚本不自动回滚。
//
// 用法（一般经 deploy/preview-sentinel.sh 包装跑；独立跑）：
//   node preview-sentinel.mjs <主站URL> <预览URL> [选项]
//     --resolve 域=IP[,域=IP...]   chromium host-resolver-rules（本地排练把域名指到 127.0.0.1）
//     --preview-window 变量=值     预览页脚本执行证明（哨兵沙箱 chunk 设置的 window 变量）
//     --budget-ms 毫秒             整体看门狗（缺省 300000）
// 退出码：0＝绿；1＝断言红（发布流水线红）；2＝环境/用法错（先修环境再判链路）。
//
// 本文件双形态：直接执行＝main()；被 import＝只暴露纯函数（单测缝，playwright 延迟
// import——无 node_modules 也能跑纯逻辑测试）。

// ---------------------------------------------------------------- 纯逻辑（缝 A）

/** 关键子资源类型：这些类型的请求「必须真实加载成功」；image/media/websocket/ping 等
 * 不在关键面（应用缺陷归 #406 终验、HMR ws 属常态）。API 面（fetch/xhr）单独口径：
 * 401/403＝匿名冒烟的鉴权健康形态（release.sh 既有 curl 冒烟即断言 /api/projects 401），
 * 404/5xx 才是断供。关键面只看同源请求（平台自身链路）——域外（应用跳转的 OIDC
 * localhost、第三方 CDN）不归发布回归盯。 */
const CRITICAL_RESOURCE_TYPES = new Set([
  'document', 'script', 'stylesheet', 'font', 'xhr', 'fetch',
]);
const API_TYPES = new Set(['xhr', 'fetch']);

/**
 * 归类一次页面加载收到的请求记录（记录形如
 * {@code {url, resourceType, status, failed}}——status 为 HTTP 状态码或 null，
 * failed 为 false 或失败原因串），产出断言判决用的两件事实：
 * 关键子资源失败清单（网络层＝kind 'network'，HTTP 计败＝kind 'http'）与
 * 成功加载的同源 script 数（页面真有 JS 在服——「壳没挂」的守门事实）。
 * origin＝页面源（如 {@code https://semgravio.com}），域外请求不入关键面。
 */
export function classifyResources(records, origin) {
  const failures = [];
  let scriptsLoaded = 0;
  for (const rec of records) {
    if (!CRITICAL_RESOURCE_TYPES.has(rec.resourceType)) continue;
    let sameOrigin = true;
    if (origin) {
      try { sameOrigin = new URL(rec.url).origin === origin; } catch { sameOrigin = false; }
    }
    if (!sameOrigin) continue;
    const isApi = API_TYPES.has(rec.resourceType);
    if (rec.failed) {
      // API 面的 ERR_ABORTED＝应用侧取消（Next RSC 预取/AbortController 常态）非断供；
      // 静态资产面的任何网络层错都计败（锚案例类断供的证据面）
      if (!(isApi && String(rec.failed).includes('ERR_ABORTED'))) {
        failures.push({ kind: 'network', url: rec.url, detail: String(rec.failed) });
      }
    } else if (typeof rec.status === 'number' && rec.status >= 400) {
      // API 面：401/403 是匿名鉴权形态；其余 4xx（端点丢失等）与 5xx 计败
      if (!(isApi && (rec.status === 401 || rec.status === 403))) {
        failures.push({ kind: 'http', url: rec.url, status: rec.status });
      }
    } else if (rec.resourceType === 'script') {
      scriptsLoaded += 1;
    }
  }
  return { failures, scriptsLoaded };
}

/** 自恢复提示页标记（rehearsal ⑤ 口径）：网关对「沙箱不在服」的回形——正文 5xx ＋
 * recovery 脚本标记。main 流程据它区分「上游尚未就绪的可重试环境态」与「断言红」。 */
const RECOVERY_MARKER = 'data-aiplatform="recovery"';

/** 该 HTML 是否平台自恢复提示页（沙箱不在服/唤醒中——环境态，非网关断供判定红）。 */
export function isRecoveryPage(html) {
  return typeof html === 'string' && html.includes(RECOVERY_MARKER);
}

/** 缺省启动链：bundled chromium（playwright install 装过则在）→ 系统 msedge → 系统
 * chrome——发布机（Windows）通常无 playwright 浏览器缓存但必带 Edge/Chrome，零下载可用。 */
const DEFAULT_CHANNELS = ['msedge', 'chrome'];

/**
 * 由环境变量构建浏览器启动尝试链（有序、去重）。显式覆盖：
 * SENTINEL_EXECUTABLE＝只试该二进制（操作者钉死）；SENTINEL_CHANNEL＝该 channel
 * 置首，其余缺省链兜底。
 */
export function buildLaunchCandidates(env) {
  if (env.SENTINEL_EXECUTABLE) {
    return [{ label: env.SENTINEL_EXECUTABLE, options: { executablePath: env.SENTINEL_EXECUTABLE } }];
  }
  const labels = env.SENTINEL_CHANNEL
    ? [env.SENTINEL_CHANNEL, 'chromium', ...DEFAULT_CHANNELS.filter((c) => c !== env.SENTINEL_CHANNEL)]
    : ['chromium', ...DEFAULT_CHANNELS];
  return labels.map((label) => (label === 'chromium'
    ? { label, options: {} }
    : { label, options: { channel: label } }));
}

/** 整体看门狗缺省：5 分钟（两条腿各含 goto/沉降窗，跨境带宽下留足但必有界）。 */
const DEFAULT_BUDGET_MS = 300_000;

/** 解析命令行：位置参数＝主站 URL、预览 URL；选项见文件头用法。 */
export function parseArgs(argv) {
  const positional = [];
  let resolveRules = null;
  let previewWindow = null;
  let budgetMs = DEFAULT_BUDGET_MS;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--resolve') {
      const pairs = argv[i += 1].split(',');
      resolveRules = pairs.map((p) => `MAP ${p.replace('=', ' ')}`).join(', ');
    } else if (arg === '--preview-window') {
      const [name, ...rest] = argv[i += 1].split('=');
      previewWindow = { name, value: rest.join('=') };
    } else if (arg === '--budget-ms') {
      budgetMs = Number(argv[i += 1]);
    } else {
      positional.push(arg);
    }
  }
  if (positional.length < 2) {
    throw new Error('用法：node preview-sentinel.mjs <主站URL> <预览URL> [--resolve 域=IP,...] '
      + '[--preview-window 变量=值] [--budget-ms 毫秒]');
  }
  return { mainUrl: positional[0], previewUrl: positional[1], resolveRules, previewWindow, budgetMs };
}

// ---------------------------------------------------------------- main（缝 B：live 实跑验证）

const GOTO_TIMEOUT_MS = 90_000;   // 单页导航预算（跨境带宽＋真内容，宽但有界）
const SETTLE_CAP_MS = 15_000;     // 子资源沉降窗上限（networkidle 竞速封顶）
const PREVIEW_ATTEMPTS = 3;       // 预览腿对「提示页」形态的重试（沙箱刚起 DNS 未及解析）

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 单腿断言：真浏览器进真实入口，页面与关键子资源真实加载。返回逐项检查
 * （ok/msg/evidence）——外层只看 every(ok)。 */
async function runLeg(browser, { label, url, retryRecovery, windowExpect, annotationExpect }) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const records = [];
  page.on('response', (r) => records.push({
    url: r.url(), resourceType: r.request().resourceType(), status: r.status(), failed: false,
  }));
  page.on('requestfailed', (r) => records.push({
    url: r.url(), resourceType: r.resourceType(), status: null,
    failed: (r.failure() && r.failure().errorText) || 'failed',
  }));

  let response = null;
  let html = '';
  let lastError = null;
  const attempts = retryRecovery ? PREVIEW_ATTEMPTS : 1;
  for (let i = 0; i < attempts; i += 1) {
    try {
      response = null;
      html = '';
      lastError = null;
      response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: GOTO_TIMEOUT_MS });
      try { await page.waitForLoadState('networkidle', { timeout: SETTLE_CAP_MS }); } catch { /* 封顶即收 */ }
      html = await page.content();
    } catch (e) {
      lastError = e;
      // 预览腿不单发定生死（#396 口径）：沙箱刚起时网关 resolver 未及重解析/端口未及监听
      // 的瞬态网错，重试而非据此定红；重试耗尽仍失败＝真失败
      if (i < attempts - 1) {
        await sleep(5000);
        continue;
      }
      break;
    }
    // 提示页＝上游暂不可达的环境态（沙箱不在服/resolver 未及解析——网关把 502 转成自恢复
    // 提示页），不据此定红——重试；重试耗尽仍是提示页＝上游真不可达
    if (!(isRecoveryPage(html) && i < attempts - 1)) break;
    await sleep(5000);
  }

  const checks = [];
  const check = (ok, msg, evidence) => checks.push({ ok, msg, evidence });
  if (lastError) {
    check(false, `页面导航失败：${String(lastError.message || lastError).split('\n')[0]}`);
  } else {
    const status = response ? response.status() : 0;
    check(status >= 200 && status < 400, `主文档 ${status}（期望 2xx/3xx）`);
    try {
      const dom = await page.evaluate(() => ({
        title: document.title || '',
        bodyText: (document.body && document.body.innerText || '').trim(),
        bodyChildren: document.body ? document.body.children.length : 0,
        annotations: document.querySelectorAll('script[data-aiplatform="annotation"]').length,
      }));
      check(dom.title.length > 0 || dom.bodyChildren > 0, 'DOM 实渲染（标题/内容非空）',
        `title="${dom.title.slice(0, 60)}" bodyChildren=${dom.bodyChildren}`);
      if (isRecoveryPage(html)) {
        check(false, '页面为平台自恢复提示页（沙箱不在服——上游未起或网关解析断）');
      }
      if (annotationExpect) {
        check(dom.annotations === 1, `圈注注入恰一次（实际 ${dom.annotations}）`);
      }
      if (windowExpect) {
        const val = await page.evaluate((n) => window[n], windowExpect.name);
        check(val === windowExpect.value,
          `脚本执行证明 window.${windowExpect.name}=${windowExpect.value}（实际 ${JSON.stringify(val)}）`);
      }
    } catch (e) {
      check(false, `页面内断言失败：${String(e.message || e).split('\n')[0]}`);
    }
  }
  const { failures, scriptsLoaded } = classifyResources(records, new URL(url).origin);
  check(failures.length === 0, `关键子资源零失败（script 成功 ${scriptsLoaded} 件）`,
    failures.slice(0, 5).map((f) => `${f.kind === 'http' ? `HTTP ${f.status}` : f.detail} ${f.url}`).join('；'));
  check(scriptsLoaded >= 1, '至少一件 script 真实加载（壳未空转）');

  await context.close().catch(() => {});
  return { label, url, checks };
}

async function main() {
  let cfg;
  try {
    cfg = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(e.message);
    process.exit(2);
  }
  const watchdog = setTimeout(() => {
    console.error(`✗ 预览哨兵整体看门狗超时（${cfg.budgetMs}ms）——环境态，先查发布机→服务器链路`);
    process.exit(2);
  }, cfg.budgetMs);
  watchdog.unref();

  const { chromium } = await import('playwright'); // 延迟 import：纯逻辑单测无需 node_modules
  // --no-proxy-server：哨兵必须直连目标（发布机系统代理对目标域的可达性≠链路健康，
  // 代理回 502/劫持都会把断供判定带偏——本地排练已实测撞上）
  const launchArgs = ['--no-proxy-server'];
  if (cfg.resolveRules) launchArgs.push(`--host-resolver-rules=${cfg.resolveRules}`);
  const launchTried = [];
  let browser = null;
  let browserLabel = '';
  for (const cand of buildLaunchCandidates(process.env)) {
    try {
      browser = await chromium.launch({ ...cand.options, headless: true, args: launchArgs });
      browserLabel = cand.label;
      launchTried.push(`${cand.label} ✓`);
      break;
    } catch (e) {
      launchTried.push(`${cand.label} ✗ ${String(e.message || e).split('\n')[0]}`);
    }
  }
  if (!browser) {
    console.error('✗ 浏览器全部启动失败（环境态）：');
    launchTried.forEach((l) => console.error(`  ${l}`));
    console.error('  补救：cd deploy/sentinel && pnpm install && pnpm exec playwright install chromium；'
      + '或 SENTINEL_CHANNEL=msedge / SENTINEL_EXECUTABLE=<路径> 指定系统浏览器');
    process.exit(2);
  }

  console.log('== 预览哨兵（#422 外部真浏览器冒烟）==');
  console.log(`时间：${new Date().toISOString()}`);
  console.log(`浏览器：${browserLabel}`);
  if (cfg.resolveRules) console.log(`域名解析映射：${cfg.resolveRules}`);

  const legs = [
    { label: '主站', url: cfg.mainUrl },
    {
      label: '预览', url: cfg.previewUrl, retryRecovery: true, annotationExpect: true,
      windowExpect: cfg.previewWindow,
    },
  ];
  const results = [];
  for (const leg of legs) {
    const r = await runLeg(browser, leg);
    results.push(r);
    console.log(`\n[${r.label}] ${r.url}`);
    for (const c of r.checks) {
      console.log(`  ${c.ok ? '✓' : '✗'} ${c.msg}${c.ok || !c.evidence ? '' : ` —— ${c.evidence}`}`);
    }
  }
  await browser.close().catch(() => {});

  const failed = results.flatMap((r) => r.checks.filter((c) => !c.ok).map((c) => `[${r.label}] ${c.msg}${c.evidence ? `（${c.evidence}）` : ''}`));
  if (failed.length) {
    console.log('\n结论：红——发布后当下链路有确定性断供（上方 ✗ 项）：');
    failed.forEach((f) => console.log(`  ✗ ${f}`));
    console.log('发布流水线红（退出码 1）。回滚人工决（本哨兵不自动回滚）：对照 deploy/部署文档.md §11；'
      + '修复后单跑 deploy/preview-sentinel.sh 复验。');
    process.exit(1);
  }
  console.log('\n结论：绿——主站＋代表性预览页与关键子资源真实加载（发布后当下链路健康）。');
  process.exit(0);
}

// 直接执行才跑 main()；被 import（单测）时只暴露纯函数
const { pathToFileURL } = await import('node:url');
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => {
    console.error(`✗ 预览哨兵环境态异常：${String(e && e.message || e).split('\n')[0]}`);
    process.exit(2);
  });
}
