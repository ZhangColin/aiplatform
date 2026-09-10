/**
 * 平台预览标注脚本（#97 圈注 B 档）：由 serve.js 注入用户系统页面，在预览 iframe
 * 内跑——非常驻：不进标注态时对页面零干扰（无监听、无遮罩）。父窗（平台前端）经
 * postMessage 呼出/退出标注态并指定工具，本脚本据此启用点选 / 圈选 / 评论三能力，
 * 把结构化 DOM 锚回传给父窗（跨源 postMessage，不走截图识图）。
 *
 * 协议（双侧契约，与 aiplatform-web src/lib/preview/annotation.ts 同源）：
 *   父 → 子：{ __aiplatform__: true, type: "annotate", mode: "enter"|"exit",
 *             tool: "select"|"circle"|"comment" }
 *   子 → 父：{ __aiplatform__: true, type: "anchor", payload: {
 *             kind: "select"|"circle"|"comment",
 *             anchor: { selector, text } | { region: {x,y,width,height},
 *                      selector?, text? },
 *             note: "" } }
 *          | { __aiplatform__: true, type: "exit" }（Esc 退出，#134：顶层信封，
 *             不包进锚 payload——父窗解析器查顶层）
 * 回传目标 = 呼出消息的 event.origin（父窗源），不做通配广播。
 */
(function () {
  if (window.__aiplatformAnnotation) return;
  window.__aiplatformAnnotation = true;

  var parentOrigin = null;
  var activeTool = null; // "select" | "circle" | "comment" | null
  var overlay = null;
  var hint = null;
  var hoverEl = null;
  var dragStart = null;

  // 信封出口（锚/退出共用）：回传目标 = 呼出消息的 event.origin——postMessage 各
  // 路径都在标注态内（activeTool 守卫），而 activeTool 只在 enter() 置位、彼时
  // parentOrigin 必已赋值，故不做 "*" 通配兜底（真到不了这里的广播是契约违约）
  function postEnvelope(envelope) {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage(envelope, parentOrigin);
    }
  }

  function post(payload) {
    postEnvelope({ __aiplatform__: true, type: "anchor", payload: payload });
  }

  // Esc 退出信封：顶层 { type: "exit" }（#134——不包进锚 payload，父窗解析器查顶层）
  function postExit() {
    postEnvelope({ __aiplatform__: true, type: "exit" });
  }

  // 稳定 CSS 选择器：优先 #id，否则沿祖先拼 tag[.class] 路径（截到 body/id 为止）；
  // 目标元素补 :nth-of-type(n) 位置——生成代码里无 id 的重复行（列表/卡片）也能
  // 唯一定位（同文本多见时以选择器为准）
  function cssSelector(el) {
    if (!el || el.nodeType !== 1) return "";
    if (el.id) return "#" + escape(el.id);
    var parts = [];
    var node = el;
    var first = true;
    while (node && node.nodeType === 1 && node !== document.body) {
      var part = node.tagName.toLowerCase();
      if (node.id) {
        parts.unshift("#" + escape(node.id));
        break;
      }
      var cls = node.classList;
      if (cls && cls.length) {
        var names = [];
        for (var i = 0; i < cls.length && i < 2; i++) names.push(escape(cls[i]));
        part += "." + names.join(".");
      }
      if (first) {
        part += nthOfType(node);
      }
      parts.unshift(part);
      first = false;
      node = node.parentElement;
    }
    return parts.join(" > ");
  }

  // 目标元素在同型兄弟中的 1 基序位（唯二无 id 无 class 时也能力图唯一）
  function nthOfType(node) {
    if (!node.parentElement) return "";
    var tag = node.tagName.toLowerCase();
    var idx = 0;
    for (var sib = node; sib; sib = sib.previousElementSibling) {
      if (sib.tagName && sib.tagName.toLowerCase() === tag) idx++;
    }
    return idx > 1 ? ":nth-of-type(" + idx + ")" : "";
  }

  function escape(s) {
    try {
      return CSS.escape(String(s));
    } catch (e) {
      return String(s).replace(/[^a-zA-Z0-9_-]/g, "\\$&");
    }
  }

  function visibleText(el) {
    var t = (el.textContent || "").replace(/\s+/g, " ").trim();
    return t.length > 80 ? t.slice(0, 80) + "…" : t;
  }

  function regionOf(start, end) {
    var sx = window.scrollX, sy = window.scrollY;
    var x = Math.min(start.x, end.x) + sx;
    var y = Math.min(start.y, end.y) + sy;
    return {
      x: Math.round(x),
      y: Math.round(y),
      width: Math.round(Math.abs(end.x - start.x)),
      height: Math.round(Math.abs(end.y - start.y)),
    };
  }

  function targetOf(event) {
    var el = event.target;
    if (!el || el.nodeType !== 1) return null;
    if (el === overlay || el === hint) return null;
    return el;
  }

  function onDocumentClick(event) {
    if (!activeTool) return;
    var el = targetOf(event);
    if (!el) return;
    if (activeTool === "circle") return; // 圈选走 pointer 拖拽，不响应点选
    var kind = activeTool === "comment" ? "comment" : "select";
    event.preventDefault();
    event.stopPropagation();
    post({ kind: kind, anchor: { selector: cssSelector(el), text: visibleText(el) }, note: "" });
  }

  function onPointerDown(event) {
    if (activeTool !== "circle") return;
    dragStart = { x: event.clientX, y: event.clientY };
    event.preventDefault();
    event.stopPropagation();
    if (overlay.setPointerCapture) {
      overlay.setPointerCapture(event.pointerId);
    }
    overlay.addEventListener("pointermove", onPointerMove);
    overlay.addEventListener("pointerup", onPointerUp);
  }

  function onPointerMove(event) {
    if (!dragStart) return;
    var rect = rectFrom(dragStart, { x: event.clientX, y: event.clientY });
    overlay.style.setProperty("--ax", rect.x + "px");
    overlay.style.setProperty("--ay", rect.y + "px");
    overlay.style.setProperty("--aw", rect.w + "px");
    overlay.style.setProperty("--ah", rect.h + "px");
    overlay.classList.add("drawing");
  }

  function onPointerUp(event) {
    overlay.removeEventListener("pointermove", onPointerMove);
    overlay.removeEventListener("pointerup", onPointerUp);
    if (dragStart) {
      var region = regionOf(dragStart, { x: event.clientX, y: event.clientY });
      dragStart = null;
      overlay.classList.remove("drawing");
      if (region.width > 2 || region.height > 2) {
        // 区域中心补 DOM 参照（选择器/文本）——圈选锚不只数字，主智能体可精确读取
        // 「圈住哪个元素」：elementFromPoint 需先让遮罩退出命中测试（防取到遮罩自身）
        var anchor = { region: region };
        var el = elementUnderRegion(region);
        if (el) {
          anchor.selector = cssSelector(el);
          anchor.text = visibleText(el);
        }
        post({ kind: "circle", anchor: anchor, note: "" });
      }
    }
  }

  // 区域中心的顶层元素（视口坐标命中测试；遮罩临时 pointer-events:none 让位）
  function elementUnderRegion(region) {
    var cx = Math.round(region.x - window.scrollX + region.width / 2);
    var cy = Math.round(region.y - window.scrollY + region.height / 2);
    overlay.style.pointerEvents = "none";
    var el = document.elementFromPoint(cx, cy);
    overlay.style.pointerEvents = "auto";
    if (!el || el.nodeType !== 1 || el === overlay || el === hint) return null;
    return el;
  }

  function rectFrom(a, b) {
    return {
      x: Math.min(a.x, b.x),
      y: Math.min(a.y, b.y),
      w: Math.abs(b.x - a.x),
      h: Math.abs(b.y - a.y),
    };
  }

  function enter(tool) {
    activeTool = tool;
    ensureOverlay();
    overlay.classList.add("active");
    overlay.setAttribute("data-tool", tool);
    // 点选/评论：遮罩不拦点击（透传给下层元素，document 捕获取 target）；
    // 圈选：遮罩拦指针（拖拽画框）——同一遮罩按工具分指针语义
    overlay.style.pointerEvents = tool === "circle" ? "auto" : "none";
    showHint(tool);
    document.addEventListener("click", onDocumentClick, true);
    overlay.addEventListener("pointerdown", onPointerDown);
  }

  function exit() {
    activeTool = null;
    if (overlay) {
      overlay.classList.remove("active", "drawing");
      overlay.removeEventListener("pointerdown", onPointerDown);
    }
    document.removeEventListener("click", onDocumentClick, true);
    if (hint) hint.remove();
    hint = null;
    if (hoverEl) hoverEl.style.outline = "";
    hoverEl = null;
  }

  function ensureOverlay() {
    if (overlay) return;
    overlay = document.createElement("div");
    overlay.setAttribute("data-aiplatform-annotation", "overlay");
    overlay.style.cssText =
      "position:fixed;inset:0;z-index:2147483647;" +
      "background:rgba(59,130,246,0.08);" +
      "display:none;cursor:crosshair;";
    // 圈选拖拽框：由 --ax/--ay/--aw/--ah 定位（pointermove 写变量）
    var box = document.createElement("div");
    box.style.cssText =
      "position:absolute;left:var(--ax,0);top:var(--ay,0);width:var(--aw,0);height:var(--ah,0);" +
      "border:2px solid rgb(59,130,246);background:rgba(59,130,246,0.15);";
    overlay.appendChild(box);
    document.documentElement.appendChild(overlay);
    // 通过 CSS 注入的显示态：active 时显示遮罩；圈选工具才显示拖拽框
    var style = document.createElement("style");
    style.textContent =
      "[data-aiplatform-annotation='overlay'].active{display:block}" +
      "[data-aiplatform-annotation='overlay']:not([data-tool='circle']) [data-aiplatform-box]{display:none}";
    document.documentElement.appendChild(style);
    box.setAttribute("data-aiplatform-box", "");
  }

  function showHint(tool) {
    if (hint) hint.remove();
    hint = document.createElement("div");
    var text = tool === "circle"
      ? "拖拽框选要改的区域"
      : tool === "comment"
        ? "点击要评论的位置"
        : "点击要指认的元素";
    hint.textContent = text + "（Esc 退出）";
    hint.style.cssText =
      "position:fixed;left:50%;top:16px;transform:translateX(-50%);z-index:2147483647;" +
      "background:rgb(17,24,39);color:#fff;font-size:13px;line-height:1;" +
      "padding:8px 14px;border-radius:999px;box-shadow:0 4px 16px rgba(0,0,0,0.2);";
    document.documentElement.appendChild(hint);
  }

  window.addEventListener("message", function (event) {
    var data = event.data;
    if (!data || data.__aiplatform__ !== true || data.type !== "annotate") return;
    if (data.mode === "enter") {
      parentOrigin = event.origin;
      enter(data.tool);
    } else if (data.mode === "exit") {
      exit();
    }
  });

  // Esc 退出（键盘在 iframe 内聚焦时可用；父窗侧的退出按钮是主路径）
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && activeTool) {
      postExit();
      exit();
    }
  });
})();
