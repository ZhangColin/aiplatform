// @vitest-environment happy-dom
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * 试用体验提示契约测试缝（#539 访谈④）：happy-dom 装载**真实注入脚本**
 * （服务端资源单源 aiplatform-server/src/main/resources/docker/workspace/
 * trial-notice.js，经 ADR-0014 同缝网关注入预览页——测试直读文件、不复制），
 * 桩 fetch 供给平台判定端点（/.aiplatform/trial-notice，经网关反代）响应，
 * 断言外部可观测行为：提示横幅的显/熄、SPA 路由切换的复探、轮询兜底、失败静默。
 * 判定单源＝billing 两轨读点（ADR-0047），脚本只消费 {trial} 不做第二份判定。
 * MPA「每页重跑」是注入口的自然属性（每文档注入一次、随页加载执行），同文档内
 * 不可观测、由 live 网关冒烟断言注入恰一次兜底。
 *
 * 时钟全用例 fake（shouldAdvanceTime：真实时钟照走、脚本 setInterval 受
 * advanceTimersByTime 驱动）——装载前开启，脚本的轮询定时器才挂在假钟上。
 *
 * 同步纪律：脚本的 fetch 是「调用即记、落地异步」——inFlight 去重位在链路
 * 收尾才释放，只等「fetch 被调用」就推进会撞上在途位（复探被吞的假红实锤）。
 * 故全缝以 **settledFetches（链路落地计数）** 为同步点，不用调用计数做判据。
 */

/** 服务端注入脚本单源（同 annotation.contract.test.ts 口径：文件缺失＝契约缝断裂）。 */
const SCRIPT_PATH = resolve(
  process.cwd(),
  "../aiplatform-server/src/main/resources/docker/workspace/trial-notice.js",
);

/** 判定端点（网关注入头 X-Preview-Workspace，响应统一信封 ApiResponse）。 */
const ENDPOINT = "/.aiplatform/trial-notice";

/** fetch 调用/落地计数（调用即记；落地＝响应体交付脚本 apply 链之后）。 */
let fetchCalls = 0;
let settledFetches = 0;

/** fetch 调用 URL 清单（端点断言面）。 */
const fetchedUrls: string[] = [];

/** 用例级响应编排：默认试用态出提示，各用例自行覆盖。 */
let nextResponse: unknown = { code: 0, data: { trial: true } };
let nextError: Error | null = null;

vi.stubGlobal(
  "fetch",
  vi.fn((url: unknown) => {
    fetchCalls++;
    fetchedUrls.push(String(url));
    const settled = () => {
      settledFetches++;
    };
    if (nextError) {
      const p = Promise.reject(nextError);
      p.then(undefined, settled); // 拒绝也落地（脚本的 catch 静默分支）
      return p;
    }
    // 最小 fetch 形态（ok/json）——被测对象是脚本对响应的消费，不是 fetch 本身；
    // 不用真 Response：happy-dom 的 Response.json 与假钟不合（实锤：链挂在
    // res.json 上不落地、inFlight 去重位永久占死、后续复探全被吞）。
    const p = Promise.resolve({
      ok: true,
      json: () =>
        Promise.resolve(nextResponse).then((body) => {
          settled();
          return body;
        }),
    });
    p.then(settled); // 响应交付也算一次落地（脚本链晚本回调一个微任务，flush 补齐）
    return p;
  }),
);

// 假钟须先于脚本装载开启（脚本 setInterval 才注册在假钟上）；真实时钟照走，
// vi.waitFor/微任务链照常落地。
vi.useFakeTimers({ shouldAdvanceTime: true });

// 装载真实脚本（IIFE 即发起首次探测并挂 SPA 路由 hook；唯一注入口＝网关
// sub_filter，无同文档双执行向量，同 annotation.js 不设防重入 guard）。
new Function(readFileSync(SCRIPT_PATH, "utf8"))();

/** 提示横幅（在场断言的观测面）。 */
function banner(): HTMLElement | null {
  return document.querySelector('[data-aiplatform="trial-notice"]');
}

let baseline = 0;

/** 微任务 flush：落地计数到点后再放两个微任务，脚本 apply 必已执行。 */
async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

/** 触发一次复探并等它完整落地（响应交付＋apply 执行）。 */
async function refreshAndSettle() {
  const before = settledFetches;
  window.dispatchEvent(new HashChangeEvent("hashchange"));
  await vi.waitFor(() => expect(settledFetches).toBeGreaterThan(before));
  await flush();
}

beforeEach(() => {
  nextResponse = { code: 0, data: { trial: true } };
  nextError = null;
  baseline = fetchCalls;
  banner()?.remove(); // 横幅 DOM 归零（脚本内部引用随 show 重挂——重挂面有用例覆盖）
});

// 资源卫生收口：模块级 stub/假钟全程服务于脚本（脚本只装载一次），逐用例清理
// 会让后续用例打到真 fetch——故在文件尾统一还原（同 connection.test.ts 先例的
// afterAll/afterEach 一族，按本缝装载形态取 afterAll）。
afterAll(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("试用体验提示契约 · 显熄与探测（#539）", () => {
  it("复探测判定端点：试用态出横幅，呈「只是体验」语义文案", async () => {
    await refreshAndSettle();

    expect(fetchedUrls[baseline]).toBe(ENDPOINT);
    expect(banner()).toBeTruthy();
    expect(banner()!.textContent).toContain("试用体验");
    expect(banner()!.textContent).toContain("演示");
    expect(banner()!.style.pointerEvents).toBe("none"); // 不挡页面操作
  });

  it("付费态（trial=false）：不出横幅", async () => {
    nextResponse = { code: 0, data: { trial: false } };

    await refreshAndSettle();

    expect(banner()).toBeNull();
  });

  it("翻转熄灭：先试用出横幅，复探转付费后横幅撤下（同文档不残留）", async () => {
    await refreshAndSettle();
    expect(banner()).toBeTruthy(); // 试用态在场

    nextResponse = { code: 0, data: { trial: false } };
    await refreshAndSettle();

    expect(banner()).toBeNull();
  });

  it("探测失败静默：不出横幅、不炸场（预览页主流程不被提示面打断）", async () => {
    nextError = new Error("backend down");

    await refreshAndSettle();

    expect(banner()).toBeNull();
  });
});

describe("试用体验提示契约 · SPA 路由覆盖（#539：平台脚本 hook history）", () => {
  it("pushState 切换：复探判定端点（每切换一探——「页面切换时」语义）", async () => {
    await refreshAndSettle();
    const before = settledFetches;

    window.history.pushState({}, "", "/spa-next");
    await vi.waitFor(() => expect(settledFetches).toBeGreaterThan(before));
    await flush();

    expect(fetchedUrls[fetchCalls - 1]).toBe(ENDPOINT);
  });

  it("replaceState 切换：同样复探", async () => {
    await refreshAndSettle();
    const before = settledFetches;

    window.history.replaceState({}, "", "/spa-replaced");
    await vi.waitFor(() => expect(settledFetches).toBeGreaterThan(before));
  });

  it("popstate（前进/后退）：同样复探", async () => {
    await refreshAndSettle();
    const before = settledFetches;

    window.dispatchEvent(new PopStateEvent("popstate"));
    await vi.waitFor(() => expect(settledFetches).toBeGreaterThan(before));
  });

  it("轮询兜底：无导航的翻转最迟下一段轮询周期熄灭", async () => {
    await refreshAndSettle();
    expect(banner()).toBeTruthy(); // 试用态在场
    const before = settledFetches;

    nextResponse = { code: 0, data: { trial: false } };
    vi.advanceTimersByTime(60_000); // 推进脚本轮询钟
    await vi.waitFor(() => expect(settledFetches).toBeGreaterThan(before));
    await flush();

    expect(banner()).toBeNull(); // 翻转熄灭落地
  });
});
