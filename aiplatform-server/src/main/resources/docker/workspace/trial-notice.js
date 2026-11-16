/**
 * 平台试用体验提示脚本（#539 访谈④）：由预览网关注入用户系统页面（ADR-0014 同缝，
 * gateway-inject.conf sub_filter 单次替换——与 annotation.js 并列注入，无同文档
 * 双执行向量，故不设防重入 guard），在预览页内跑——非常驻轻提示：判定试用态时
 * 顶缘浮一条「只是体验」横幅（pointer-events:none，不挡页面任何操作），付费翻转
 * 即撤。判定单源＝平台后端两轨读点（billing ADR-0047），本脚本只消费 {trial}
 * 布尔、不做第二份判定——付费后熄灭＝平台数据侧一处收口，用户工作区零字节。
 *
 * 探测通道：同源 GET /.aiplatform/trial-notice（网关反代平台后端、注入
 * X-Preview-Workspace 头——零 CORS；响应统一信封 ApiResponse，no-store）。
 *
 * 复探时机（「页面切换时」语义的两条腿）：
 *   MPA——每次导航产生新文档、脚本随页重生，装载即探，天然每页一探；
 *   SPA ——客户端路由不产生新文档，这里 hook history.pushState/replaceState
 *          并监听 popstate/hashchange，每切换复探；
 *   兜底——轮询（POLL_MS）兜「无导航的付费翻转」：熄灭最迟下一个轮询周期。
 * 探测失败静默：首探失败＝不出提示，后续失败保持最后已知态——提示面永不
 * 阻断预览页主流程。
 */
(function () {
  var ENDPOINT = "/.aiplatform/trial-notice";
  var POLL_MS = 60000;
  var banner = null;
  var inFlight = false;

  /** 横幅出生（内联样式、不依赖页面任何 CSS；pointer-events:none 不挡操作）。 */
  function showBanner() {
    if (!banner) {
      banner = document.createElement("div");
      banner.setAttribute("data-aiplatform", "trial-notice");
      banner.textContent = "试用体验 · 这只是演示，不影响正式使用";
      banner.style.cssText = [
        "position:fixed",
        "top:12px",
        "left:50%",
        "transform:translateX(-50%)",
        "z-index:2147483647",
        "background:rgba(17,24,39,.88)",
        "color:#fff",
        "font:13px/1.6 system-ui,-apple-system,sans-serif",
        "padding:6px 14px",
        "border-radius:999px",
        "pointer-events:none",
        "box-shadow:0 2px 8px rgba(0,0,0,.25)",
      ].join(";");
    }
    if (!banner.isConnected) {
      // 脚本在 <head> 内执行时 body 可能未生——documentElement 兜底；
      // DOM 就绪前 documentElement 即挂点，横幅同样置顶呈现
      (document.body || document.documentElement).appendChild(banner);
    }
  }

  /** 熄灭＝撤下 DOM（内部引用同清——下次试用态重新出生）。 */
  function hideBanner() {
    if (banner) {
      banner.remove();
      banner = null;
    }
  }

  function apply(trial) {
    if (trial) {
      showBanner();
    } else {
      hideBanner();
    }
  }

  /**
   * 复探：在途去重（路由连打不叠请求）；响应统一信封（data.trial），裸 JSON
   * 容错（data 缺省按顶层读）；任何失败静默——首探失败＝不提示，后续失败
   * 保持最后已知态（apply 只在成功路径调用）。
   */
  function refresh() {
    if (inFlight) {
      return;
    }
    inFlight = true;
    fetch(ENDPOINT, { method: "GET", cache: "no-store" })
      .then(function (res) {
        return res.ok ? res.json() : null;
      })
      .then(function (body) {
        if (!body || typeof body !== "object") {
          return;
        }
        var data = body.data !== undefined ? body.data : body;
        if (data && typeof data.trial === "boolean") {
          apply(data.trial);
        }
      })
      .catch(function () { /* 静默：提示面不阻断预览页 */ })
      .finally(function () {
        inFlight = false;
      });
  }

  /** SPA 路由 hook（#539 票面机制）：包装 pushState/replaceState＋popstate/hashchange。 */
  function hookHistory() {
    try {
      var wrap = function (type) {
        var raw = history[type];
        history[type] = function () {
          var ret = raw.apply(this, arguments);
          refresh();
          return ret;
        };
      };
      wrap("pushState");
      wrap("replaceState");
      window.addEventListener("popstate", refresh);
      window.addEventListener("hashchange", refresh);
    } catch (e) {
      // hook 失败（非常规环境）不阻断提示面——轮询兜底仍在
    }
  }

  hookHistory();
  refresh(); // MPA 每页装载即探；SPA 首探
  setInterval(refresh, POLL_MS); // 无导航翻转的熄灭兜底
})();
