/**
 * 平台预览标注脚本（#97 圈注 B 档 → #137 标注态交互层重构）：由预览网关注入
 * 用户系统页面（ADR-0014 单源，#138 serve.js 旧注入路径已删——sub_filter_once
 * 单次替换，无同文档双执行向量，故不设防重入 guard），在预览 iframe 内跑——
 * 非常驻：不进标注态时对页面零干扰（无监听、无遮罩）。
 * 父窗（平台前端）经 postMessage 呼出/退出标注
 * 态并指定工具，本脚本进入**标注态 = 页面冻结**：遮罩拦下全部指针事件，点击
 * 只用于拾取、不触发页面自身操作（禁用控件同样可指认：选择≠操作）；悬停出
 * outline 高亮 + 元素徽章；光标统一 crosshair。结构化 DOM 锚回传父窗（跨源
 * postMessage，不走截图识图）。
 *
 * 协议（双侧契约，与 aiplatform-web src/lib/preview/annotation.ts 同源）：
 *   父 → 子：{ __aiplatform__: true, type: "annotate", mode: "enter"|"exit",
 *             tool: "select"|"circle" }（#135 起评论键置灰，工具只余两键；
 *             其余工具值不进标注态）
 *   子 → 父：{ __aiplatform__: true, type: "anchor", payload: {
 *             kind: "select"|"circle",
 *             anchor: { selector, text } | { region: {x,y,width,height},
 *                      selector?, text? },
 *             note: "" } }（kind=comment 仅历史兼容，本脚本不再产生）
 *          | { __aiplatform__: true, type: "exit" }（Esc 退出，#134：顶层信封，
 *             不包进锚 payload——父窗解析器查顶层）
 * 回传目标 = 呼出消息的 event.origin（父窗源），不做通配广播。
 *
 * 拾取在 pointerdown 层完成（#137）：遮罩 pointer-events:auto 拦下命中，
 * preventDefault + stopPropagation——页面自身的点击/按压/focus 一律不穿透
 * （遮罩挂在 documentElement 下、应用根容器之外，框架根委托监听够不着；
 * document 捕获段监听用户层无法拦下，是已知边界）；浏览器不对禁用控件派发
 * click（旧 click 拾取路径的死角），pointerdown + elementFromPoint 命中测试
 * 不受此限。hover 反馈：遮罩全拦后页面元素收不到 mouseover/mouseout，遮罩
 * mousemove + 命中测试即等价自洽的悬停信号——目标元素画 outline（画在元素
 * 自身、随滚动自然跟随）+ 鼠标旁徽章（data-slot 组件名 → 标签名）。
 */
(function () {
  var parentOrigin = null;
  var activeTool = null; // "select" | "circle" | null
  var overlay = null;
  var dragBox = null; // 圈选拖拽框（遮罩子元素，--ax/--ay/--aw/--ah 定位）
  var badge = null; // 悬停徽章（遮罩子元素，随遮罩显隐；pointer-events:none 不挡命中）
  var hint = null;
  var hoverEl = null; // 当前高亮元素（#137 起真赋值——此前只声明从未赋值的死代码）
  var hoverOutline = ""; // 高亮前元素的行内 outline/offset（撤高亮时还原，不吃页面样式）
  var hoverOutlineOffset = "";
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

  // 命中测试：视口坐标 → 顶层页面元素。遮罩临时退出命中测试（pointer-events
  // 可继承，遮罩子浮件——徽章/拖框随之让位；hint 独立挂载、自设 none），测完
  // 即恢复拦截。body/documentElement 是页面背景——不是可指认对象。
  function hitTest(x, y) {
    overlay.style.pointerEvents = "none";
    var el = document.elementFromPoint(x, y);
    overlay.style.pointerEvents = "auto";
    if (!el || el.nodeType !== 1) return null;
    if (el === document.body || el === document.documentElement) return null;
    return el;
  }

  // 悬停反馈（#137）：目标元素画 outline——画在元素自身，滚动时自然跟随（徽章
  // 固定视口贴鼠标，不需要跟随）；行内旧值记账，撤高亮还原（不吃页面样式）
  function setHover(el, x, y) {
    if (el !== hoverEl) {
      clearHover();
      if (el) {
        hoverEl = el;
        hoverOutline = el.style.outline;
        hoverOutlineOffset = el.style.outlineOffset;
        el.style.outline = "2px solid rgb(59,130,246)";
        el.style.outlineOffset = "-2px";
        badge.textContent = badgeLabel(el);
      }
    }
    if (el) {
      badge.style.display = "block";
      placeBadge(x, y);
    }
  }

  function clearHover() {
    if (hoverEl) {
      hoverEl.style.outline = hoverOutline;
      hoverEl.style.outlineOffset = hoverOutlineOffset;
      hoverEl = null;
      hoverOutline = "";
      hoverOutlineOffset = "";
    }
    if (badge) badge.style.display = "none";
  }

  // 徽章文案优先级（#137）：data-slot 组件名（shadcn 基座的组件痕迹）→ 标签名。
  // 票面第三级「文本预览」天然不可达——命中测试只回元素（nodeType 1），元素必
  // 有 tagName，不设死分支（保留能力不保留结构）
  function badgeLabel(el) {
    var slot = el.getAttribute("data-slot");
    return slot || el.tagName.toLowerCase();
  }

  // 徽章贴鼠标：右/下缘翻转，不出视口
  function placeBadge(x, y) {
    var w = badge.offsetWidth || 0;
    var h = badge.offsetHeight || 0;
    badge.style.left = (x + 14 + w > window.innerWidth ? x - w - 14 : x + 14) + "px";
    badge.style.top = (y + 16 + h > window.innerHeight ? y - h - 16 : y + 16) + "px";
  }

  // 遮罩上的指针按下 = 标注态唯一拾取口（#137）：先冻结——preventDefault 压掉
  // focus 漂移/原生选择，stopPropagation 压掉 document 冒泡监听。遮罩挂在
  // documentElement 下、应用根容器之外：框架根委托监听（React/Vue）够不着；
  // document 捕获段监听（罕见）用户层无法拦下，是本冻结口径的已知边界。
  // 再按工具分流：select = 命中测试拾取元素锚（禁用控件在 elementFromPoint
  // 射程内）；circle = 起拖
  function onOverlayPointerDown(event) {
    event.preventDefault();
    event.stopPropagation();
    clearHover(); // 按下即收悬停场：拾取/拖拽是当下动作
    if (activeTool === "circle") {
      startDrag(event);
    } else {
      pickAt(event.clientX, event.clientY);
    }
  }

  function pickAt(x, y) {
    var el = hitTest(x, y);
    if (!el) return;
    post({ kind: "select", anchor: { selector: cssSelector(el), text: visibleText(el) }, note: "" });
  }

  function startDrag(event) {
    dragStart = { x: event.clientX, y: event.clientY };
    // 起手收掉上一场的完成矩形（拖拽面只在标注态挂载，无工具守卫可言）
    dragBox.style.display = "none";
    // 指针捕获：拖出 iframe 边界（视口外）pointerup 仍送回遮罩收尾，事件不丢
    if (overlay.setPointerCapture) {
      try {
        overlay.setPointerCapture(event.pointerId);
      } catch (e) {
        // 捕获失败（罕见：指针已失效）退化为视口内收尾，拖拽照常
      }
    }
    overlay.addEventListener("pointermove", onPointerMove);
    overlay.addEventListener("pointerup", onPointerUp);
    overlay.addEventListener("pointercancel", onPointerCancel);
  }

  function onPointerMove(event) {
    if (!dragStart) return;
    var rect = rectFrom(dragStart, { x: event.clientX, y: event.clientY });
    overlay.style.setProperty("--ax", rect.x + "px");
    overlay.style.setProperty("--ay", rect.y + "px");
    overlay.style.setProperty("--aw", rect.w + "px");
    overlay.style.setProperty("--ah", rect.h + "px");
    // 首次移动起显示实时矩形（出生隐藏——静止时无 0×0 边框残点）
    dragBox.style.display = "block";
  }

  function onPointerUp(event) {
    var start = dragStart;
    detachDrag();
    if (start) {
      var region = regionOf(start, { x: event.clientX, y: event.clientY });
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
      // 完成矩形保留呈现（对应刚进附件区的 chip）；exit / 下次拖拽收掉
    }
  }

  // 系统取消指针（触屏被滚动接管等）：拖拽作废——不留未完成矩形的残影、不发锚
  function onPointerCancel() {
    detachDrag();
    dragBox.style.display = "none";
  }

  /** 拆拖拽三监听、清起点（onPointerUp 先取起点再收，锚照发；其余调用方 = 弃拖拽）。 */
  function detachDrag() {
    if (!overlay) return;
    overlay.removeEventListener("pointermove", onPointerMove);
    overlay.removeEventListener("pointerup", onPointerUp);
    overlay.removeEventListener("pointercancel", onPointerCancel);
    dragStart = null;
  }

  // 区域中心的顶层元素（视口坐标命中测试；遮罩临时退出命中测试让位）
  function elementUnderRegion(region) {
    var cx = Math.round(region.x - window.scrollX + region.width / 2);
    var cy = Math.round(region.y - window.scrollY + region.height / 2);
    return hitTest(cx, cy);
  }

  function rectFrom(a, b) {
    return {
      x: Math.min(a.x, b.x),
      y: Math.min(a.y, b.y),
      w: Math.abs(b.x - a.x),
      h: Math.abs(b.y - a.y),
    };
  }

  // 悬停反馈驱动面：遮罩 mousemove → 命中测试（拖拽中让位——拖框是当下反馈）。
  // 悬停轨迹不放行到页面 document 监听（mousemove 高频，analytics 类监听常客）
  function onOverlayMouseMove(event) {
    event.stopPropagation();
    if (dragStart) return;
    setHover(hitTest(event.clientX, event.clientY), event.clientX, event.clientY);
  }

  // 鼠标离开视口（遮罩铺满视口，mouseleave 即出窗）：高亮与徽章收场
  function onOverlayMouseLeave() {
    clearHover();
  }

  // 余波压制（#137）：拾取后的原生 click、悬停期的 mouseover/mouseout——事件
  // 落在遮罩上仍会冒泡，压掉不让页面的 document 委托式监听观测到标注态交互
  function suppressBubbling(event) {
    event.preventDefault();
    event.stopPropagation();
  }

  // 遮罩监听表（#137）：进出标注态同表挂/摘（enter 直切重入先摘防重复）。加一类
  // 监听只动这张表——enter/exit 不再各抄一份清单
  var overlayBindings = [
    ["pointerdown", onOverlayPointerDown], // 拾取口（select 拾锚 / circle 起拖）
    ["mousemove", onOverlayMouseMove], // 悬停反馈
    ["mouseleave", onOverlayMouseLeave], // 出窗收场
    ["click", suppressBubbling], // 拾取后的原生 click 余波
    ["mouseover", suppressBubbling], // 悬停期余波（页面委托式监听吃不到）
    ["mouseout", suppressBubbling],
  ];

  function enter(tool) {
    activeTool = tool;
    ensureOverlay();
    // 异工具直切（父窗不发 exit）清场：在途拖拽作废（迟到的 pointer 不发锚）、
    // 上一场的完成矩形与悬停反馈不带到新模式
    detachDrag();
    dragBox.style.display = "none";
    clearHover();
    // 显隐唯一正路 = 内联直切（#136 根因修复）：出生内联 display:none 一路压过
    // 样式表 .active{display:block}（内联恒胜样式表，层叠必败）——遮罩从未显示，
    // 圈选拖拽落在真实页面上触发原生选择，出生即失效。显隐不再借道样式表类名。
    overlay.style.display = "block";
    // 遮罩拦指针 = 标注态页面冻结（#137）：全部工具下 pointer-events:auto——
    // 命中面在遮罩，页面自身交互（点击/按压/focus）一律不触发、光标随遮罩统一
    // crosshair；拾取走命中测试，不依赖（也不允许）事件穿透
    overlay.style.pointerEvents = "auto";
    for (var i = 0; i < overlayBindings.length; i++) {
      overlay.removeEventListener(overlayBindings[i][0], overlayBindings[i][1]);
      overlay.addEventListener(overlayBindings[i][0], overlayBindings[i][1]);
    }
    showHint(tool);
  }

  function exit() {
    activeTool = null;
    if (overlay) {
      detachDrag(); // 拖拽中退出（Esc/父窗 exit）也收干净：迟到的 pointer 不再发锚
      dragBox.style.display = "none";
      overlay.style.display = "none";
      for (var i = 0; i < overlayBindings.length; i++) {
        overlay.removeEventListener(overlayBindings[i][0], overlayBindings[i][1]);
      }
    }
    if (hint) hint.remove();
    hint = null;
    clearHover();
  }

  function ensureOverlay() {
    if (overlay) return;
    overlay = document.createElement("div");
    overlay.setAttribute("data-aiplatform-annotation", "overlay");
    // user-select/touch-action：标注态不吃页面原生文本选择、触屏不被滚动接管；
    // cursor 统一 crosshair（命中面在遮罩，页面自身 cursor 不生效）
    overlay.style.cssText =
      "position:fixed;inset:0;z-index:2147483647;" +
      "background:rgba(59,130,246,0.08);" +
      "display:none;cursor:crosshair;user-select:none;touch-action:none;";
    // 圈选拖拽框：由 --ax/--ay/--aw/--ah 定位（pointermove 写变量）；
    // 出生隐藏，首次移动起显示（静止时无 0×0 边框残点）
    dragBox = document.createElement("div");
    dragBox.setAttribute("data-aiplatform-box", "");
    dragBox.style.cssText =
      "position:absolute;left:var(--ax,0);top:var(--ay,0);width:var(--aw,0);height:var(--ah,0);" +
      "border:2px solid rgb(59,130,246);background:rgba(59,130,246,0.15);display:none;";
    overlay.appendChild(dragBox);
    // 悬停徽章（#137）：遮罩子元素随遮罩显隐；pointer-events:none 不挡命中；
    // 定位/文案由 setHover 驱动
    badge = document.createElement("div");
    badge.setAttribute("data-aiplatform-annotation", "badge");
    badge.style.cssText =
      "position:fixed;left:0;top:0;z-index:2147483647;pointer-events:none;display:none;" +
      "background:rgb(17,24,39);color:#fff;font-size:12px;line-height:1;" +
      "padding:5px 9px;border-radius:6px;box-shadow:0 2px 8px rgba(0,0,0,0.25);" +
      "max-width:280px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;";
    overlay.appendChild(badge);
    document.documentElement.appendChild(overlay);
  }

  function showHint(tool) {
    if (hint) hint.remove();
    hint = document.createElement("div");
    hint.setAttribute("data-aiplatform-annotation", "hint");
    var text = tool === "circle" ? "拖拽框选要改的区域" : "点击要指认的元素";
    hint.textContent = text + "（Esc 退出）";
    // pointer-events:none：提示浮条不挡拾取/拖拽（事件穿到遮罩）
    hint.style.cssText =
      "position:fixed;left:50%;top:16px;transform:translateX(-50%);z-index:2147483647;" +
      "pointer-events:none;" +
      "background:rgb(17,24,39);color:#fff;font-size:13px;line-height:1;" +
      "padding:8px 14px;border-radius:999px;box-shadow:0 4px 16px rgba(0,0,0,0.2);";
    document.documentElement.appendChild(hint);
  }

  window.addEventListener("message", function (event) {
    var data = event.data;
    if (!data || data.__aiplatform__ !== true || data.type !== "annotate") return;
    if (data.mode === "enter") {
      // 只认两键（#135 评论置灰后协议不再送 comment）；异值不进标注态
      if (data.tool !== "select" && data.tool !== "circle") return;
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
