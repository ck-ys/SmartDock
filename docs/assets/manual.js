/* SmartDock 开发手册 — 交互
   无依赖，file:// 可直接打开。 */
(function () {
  "use strict";

  var body = document.body;
  var manual = body.getAttribute("data-manual") || "manual";
  var storeKey = "sd-manual:" + manual + ":checks";
  var storage = null;
  try {
    storage = window.localStorage;
    storage.setItem("sd-manual:probe", "1");
    storage.removeItem("sd-manual:probe");
  } catch (e) {
    storage = null;
  }

  /* ---------- 侧栏开关 ---------- */
  var toggle = document.querySelector("[data-nav-toggle]");
  if (toggle) {
    toggle.addEventListener("click", function () {
      body.classList.toggle("nav-open");
      toggle.setAttribute("aria-expanded", body.classList.contains("nav-open") ? "true" : "false");
    });
    document.addEventListener("click", function (e) {
      if (!body.classList.contains("nav-open")) return;
      if (e.target.closest(".sidebar") || e.target.closest("[data-nav-toggle]")) return;
      body.classList.remove("nav-open");
      toggle.setAttribute("aria-expanded", "false");
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && body.classList.contains("nav-open")) {
        body.classList.remove("nav-open");
        toggle.setAttribute("aria-expanded", "false");
      }
    });
  }

  /* 点击侧栏链接后，手机端自动收起 */
  document.querySelectorAll(".sidebar a[href^='#']").forEach(function (a) {
    a.addEventListener("click", function () {
      if (window.innerWidth <= 900) {
        body.classList.remove("nav-open");
        if (toggle) toggle.setAttribute("aria-expanded", "false");
      }
    });
  });

  /* ---------- 侧栏搜索 ---------- */
  var search = document.querySelector("[data-search]");
  var hint = document.querySelector("[data-search-hint]");
  if (search) {
    var chapters = Array.prototype.slice.call(document.querySelectorAll(".nav-list > li"));
    search.addEventListener("input", function () {
      var q = search.value.trim().toLowerCase();
      var shown = 0;
      chapters.forEach(function (li) {
        var text = li.textContent.toLowerCase();
        var hit = !q || text.indexOf(q) >= 0;
        li.classList.toggle("hidden", !hit);
        if (hit) shown++;
      });
      if (hint) {
        hint.classList.toggle("show", !!q);
        hint.textContent = q ? "匹配 " + shown + " 章" + (shown ? "" : "，换个词试试") : "";
      }
    });
  }

  /* ---------- 标题锚点 ---------- */
  document.querySelectorAll("h2[id], h3[id]").forEach(function (h) {
    var a = document.createElement("a");
    a.className = "anchor";
    a.href = "#" + h.id;
    a.setAttribute("aria-label", "本节链接");
    a.textContent = "#";
    h.appendChild(a);
  });

  /* ---------- 滚动高亮 + 进度 ---------- */
  var heads = Array.prototype.slice.call(document.querySelectorAll("h2[id], h3[id]"));
  var navLinks = Array.prototype.slice.call(document.querySelectorAll("[data-target]"));
  var progress = document.querySelector("[data-progress]");
  var toTop = document.querySelector("[data-to-top]");
  var ticking = false;

  function activeId() {
    var y = window.scrollY + 96;
    var id = heads.length ? heads[0].id : "";
    for (var i = 0; i < heads.length; i++) {
      if (heads[i].offsetTop <= y) id = heads[i].id;
      else break;
    }
    return id;
  }

  function isActive(target, id) {
    if (!target) return false;
    if (target === id) return true;
    if (target.indexOf("h-") === 0) {
      var ch = target.slice(2);
      return id === target || id.indexOf("s-" + ch + "-") === 0;
    }
    return false;
  }

  function sync() {
    ticking = false;
    var id = activeId();
    navLinks.forEach(function (a) {
      a.classList.toggle("active", isActive(a.getAttribute("data-target"), id));
    });
    if (progress) {
      var max = document.documentElement.scrollHeight - window.innerHeight;
      var p = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
      progress.style.width = (p * 100).toFixed(2) + "%";
    }
    if (toTop) toTop.classList.toggle("show", window.scrollY > 600);
  }

  function onScroll() {
    if (!ticking) {
      ticking = true;
      window.requestAnimationFrame(sync);
    }
  }
  window.addEventListener("scroll", onScroll, { passive: true });
  window.addEventListener("resize", onScroll);
  sync();

  if (toTop) {
    toTop.addEventListener("click", function () {
      window.scrollTo({ top: 0, behavior: "smooth" });
    });
  }

  /* ---------- 清单持久化 ---------- */
  var checks = {};
  if (storage) {
    try {
      checks = JSON.parse(storage.getItem(storeKey) || "{}") || {};
    } catch (e) {
      checks = {};
    }
  }
  function saveChecks() {
    if (!storage) return;
    try {
      storage.setItem(storeKey, JSON.stringify(checks));
    } catch (e) {
      /* 存不下就算了，不打断阅读 */
    }
  }
  document.querySelectorAll("input[data-key]").forEach(function (input) {
    var key = input.getAttribute("data-key");
    if (Object.prototype.hasOwnProperty.call(checks, key)) input.checked = !!checks[key];
    input.addEventListener("change", function () {
      checks[key] = input.checked;
      saveChecks();
    });
  });
  document.querySelectorAll("[data-clear-checks]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      document.querySelectorAll("input[data-key]").forEach(function (i) {
        i.checked = false;
        checks[i.getAttribute("data-key")] = false;
      });
      saveChecks();
    });
  });

  /* ---------- 代码复制 ---------- */
  document.querySelectorAll(".code").forEach(function (box) {
    var pre = box.querySelector("pre");
    if (!pre) return;
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "copy";
    btn.textContent = "复制";
    btn.addEventListener("click", function () {
      var text = pre.innerText;
      function done(ok) {
        btn.textContent = ok ? "已复制" : "复制失败";
        setTimeout(function () {
          btn.textContent = "复制";
        }, 1400);
      }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(
          function () {
            done(true);
          },
          function () {
            fallback();
          }
        );
      } else {
        fallback();
      }
      function fallback() {
        var ta = document.createElement("textarea");
        ta.value = text;
        ta.style.position = "fixed";
        ta.style.opacity = "0";
        document.body.appendChild(ta);
        ta.select();
        var ok = false;
        try {
          ok = document.execCommand("copy");
        } catch (e) {
          ok = false;
        }
        document.body.removeChild(ta);
        done(ok);
      }
    });
    box.appendChild(btn);
  });

  /* ---------- 图放大（内联 SVG） ---------- */
  var zoom = document.createElement("div");
  zoom.className = "zoom";
  zoom.setAttribute("role", "dialog");
  zoom.setAttribute("aria-modal", "true");
  zoom.setAttribute("aria-label", "放大查看流程图");

  var zbar = document.createElement("div");
  zbar.className = "zoom-bar";
  var zcap = document.createElement("p");
  zcap.className = "zoom-cap";
  var zstage = document.createElement("div");
  zstage.className = "zoom-stage";
  var zclose = document.createElement("button");
  zclose.type = "button";
  zclose.className = "zoom-close";
  zclose.setAttribute("aria-label", "关闭");
  zclose.textContent = "×";

  var zlabel = document.createElement("span");
  zlabel.className = "znum";
  var zbtn = {};
  [50, 75, 100, 150, 200].forEach(function (pct) {
    var b = document.createElement("button");
    b.type = "button";
    b.textContent = pct + "%";
    b.addEventListener("click", function () {
      setScale(pct / 100);
    });
    zbtn[pct] = b;
    zbar.appendChild(b);
  });
  zbar.appendChild(zlabel);

  zoom.appendChild(zstage);
  zoom.appendChild(zbar);
  zoom.appendChild(zclose);
  zoom.appendChild(zcap);
  document.body.appendChild(zoom);

  var zc = { svg: null, w: 0, h: 0, scale: 1 };

  function setScale(scale) {
    if (!zc.svg) return;
    zc.scale = scale;
    zc.svg.style.width = Math.round(zc.w * scale) + "px";
    zc.svg.style.height = Math.round(zc.h * scale) + "px";
    zlabel.textContent = Math.round(scale * 100) + "%";
    Object.keys(zbtn).forEach(function (k) {
      zbtn[k].classList.toggle("on", Number(k) === Math.round(scale * 100));
    });
  }

  function openZoom(canvas) {
    var src = canvas.querySelector("svg.flow-svg") || canvas.querySelector("svg");
    if (!src) return;
    zc.w = parseFloat(canvas.getAttribute("data-w")) || 900;
    zc.h = parseFloat(canvas.getAttribute("data-h")) || 600;
    var clone = src.cloneNode(true);
    clone.removeAttribute("style");
    clone.setAttribute("width", zc.w);
    clone.setAttribute("height", zc.h);
    zstage.innerHTML = "";
    zstage.appendChild(clone);
    zc.svg = clone;
    setScale(1);
    zstage.scrollLeft = Math.max(0, (zc.w - zstage.clientWidth) / 2);
    zcap.textContent =
      (canvas.getAttribute("data-name") || "流程图") +
      " · 原始尺寸 " + Math.round(zc.w) + " × " + Math.round(zc.h) + " px · 按 Esc 关闭";
    zoom.classList.add("open");
    document.body.classList.add("lb-open");
  }

  function closeZoom() {
    zoom.classList.remove("open");
    document.body.classList.remove("lb-open");
    zstage.innerHTML = "";
    zc.svg = null;
  }

  document.querySelectorAll(".diagram-canvas").forEach(function (canvas) {
    canvas.addEventListener("click", function () {
      openZoom(canvas);
    });
    canvas.addEventListener("keydown", function (e) {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        openZoom(canvas);
      }
    });
  });

  zstage.addEventListener("click", function (e) {
    if (e.target === zstage) closeZoom();
  });
  zclose.addEventListener("click", closeZoom);
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape" && zoom.classList.contains("open")) closeZoom();
  });

  /* ---------- 打印 ---------- */
  document.querySelectorAll("[data-print]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      window.print();
    });
  });

  /* ---------- 流程步骤聚焦（悬停预览 / 点击钉住 / 键盘上下步） ---------- */
  var flowsAll = Array.prototype.slice.call(document.querySelectorAll(".dgm-flow"));

  function stepOf(e) {
    var t = e.target;
    return t && t.closest ? t.closest(".fl-step") : null;
  }
  function flowClear(flow) {
    /* 淡化作用域是图卡（一张图卡可能被 fork 行分成多个 .dgm-flow，编号却连续） */
    var fig = flow.closest(".diagram-figure");
    if (fig) {
      fig.classList.remove("flow-focus");
      Array.prototype.forEach.call(fig.querySelectorAll(".fl-step.step-active"), function (s) {
        s.classList.remove("step-active");
      });
    } else {
      flow.classList.remove("flow-focus");
      Array.prototype.forEach.call(flow.querySelectorAll(".fl-step.step-active"), function (s) {
        s.classList.remove("step-active");
      });
    }
  }
  /* 播完后手动点亮某一步时，把「N/N · 播完了」的计数复位成中性「N 步」，
     否则计数与「只亮 1 步」的画面自相矛盾 */
  function demoUnwind(flow) {
    var fig = flow.closest(".diagram-figure");
    if (fig && fig.__done && fig.__demo) {
      fig.__done = false;
      fig.__demo.count.textContent = fig.__demo.stepsN + " 步";
      fig.__demo.play.textContent = "▶ 播放";
      fig.__demo.reset.style.display = "none";
    }
  }
  function flowSetActive(flow, step) {
    flowClear(flow);
    demoUnwind(flow);
    var fig = flow.closest(".diagram-figure");
    (fig || flow).classList.add("flow-focus");
    step.classList.add("step-active");
  }

  flowsAll.forEach(function (flow) {
    var steps = flow.querySelectorAll(".fl-step");
    /* 守卫按「图卡」算而不是按单条流程：一张图卡被 fork 行切成多个 .dgm-flow 时，
       开头/结尾常常只剩 1 步的碎流程；按流程守卫会让那几步既不聚焦也不淡化 */
    var figOwner = flow.closest(".diagram-figure");
    var figSteps = figOwner ? figOwner.querySelectorAll(".fl-step") : steps;
    if (figSteps.length < 2) return;
    Array.prototype.forEach.call(steps, function (s) {
      s.setAttribute("tabindex", "0");
    });

    /* 悬停预览：指针进哪步亮哪步 */
    flow.addEventListener("pointerover", function (e) {
      var step = stepOf(e);
      if (!step || flow.__playing) return;
      if (flow.querySelector(".fl-step.step-active") === step) return;
      flowSetActive(flow, step);
    });
    /* 指针离开：有钉住则回到钉住的那步，否则全亮 */
    flow.addEventListener("pointerleave", function () {
      if (flow.__playing) return;
      if (flow.__pinned) flowSetActive(flow, flow.__pinned);
      else flowClear(flow);
    });
    /* 点击钉住：mousedown 已先触发 focusin 完成钉住，这里只「设钉」不做二次取消
       （click 再 toggle 会自拆）；解除 = Esc / 点图外 / 焦点离开整条流程 */
    flow.addEventListener("click", function (e) {
      var step = stepOf(e);
      if (!step || flow.__playing) return;
      var sel = "";
      try {
        sel = String(window.getSelection ? window.getSelection() : "");
      } catch (err) {
        sel = "";
      }
      if (sel.length > 0) return; /* 选中文字时点击不钉 */
      flow.__pinned = step;
      flowSetActive(flow, step);
    });
    /* 键盘：Tab 进入步骤，上下方向键换步，Esc 解除 */
    flow.addEventListener("focusin", function (e) {
      var step = stepOf(e);
      if (step && !flow.__playing) {
        flow.__pinned = step;
        flowSetActive(flow, step);
      }
    });
    flow.addEventListener("focusout", function (e) {
      if (!flow.contains(e.relatedTarget) && !flow.__playing) {
        flow.__pinned = null;
        flowClear(flow);
      }
    });
    flow.addEventListener("keydown", function (e) {
      /* 上下步按图卡取步骤：跨 .dgm-flow 的连续编号才能一路走到底 */
      var arr = Array.prototype.slice.call(
        (flow.closest(".diagram-figure") || flow).querySelectorAll(".fl-step"),
      );
      var i = arr.indexOf(document.activeElement);
      if (i < 0) return;
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        var n = arr[i + (e.key === "ArrowDown" ? 1 : -1)];
        if (n) n.focus();
      } else if (e.key === "Escape") {
        flow.__pinned = null;
        flowClear(flow);
        if (document.activeElement) document.activeElement.blur();
      }
    });
  });

  /* 点到图外：清除所有钉住 */
  document.addEventListener("click", function (e) {
    flowsAll.forEach(function (flow) {
      if (flow.__pinned && !flow.__playing && !flow.contains(e.target)) {
        flow.__pinned = null;
        flowClear(flow);
      }
    });
  });
  /* 全局 Esc：清除所有钉住（与放大层的 Esc 各管各的，互不冲突） */
  document.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") return;
    flowsAll.forEach(function (flow) {
      flow.__pinned = null;
      if (!flow.__playing) flowClear(flow);
    });
  });

  /* ---------- 逐步演示：按「图卡」为单位（一卡可能含多个子流程），≥3 步才出工具条 ---------- */
  Array.prototype.slice.call(document.querySelectorAll(".diagram-figure")).forEach(function (fig) {
    var flowsInFig = Array.prototype.slice.call(fig.querySelectorAll(".dgm-flow"));
    if (!flowsInFig.length) return;
    var steps = Array.prototype.slice.call(fig.querySelectorAll(".fl-step"));
    if (steps.length < 3 || fig.querySelector(".dgm-demo")) return;
    fig.__playing = false;
    fig.__done = false;

    var bar = document.createElement("div");
    bar.className = "dgm-demo";
    bar.setAttribute("role", "group");
    bar.setAttribute("aria-label", "逐步演示");
    var play = document.createElement("button");
    play.type = "button";
    play.className = "demo-play";
    play.textContent = "▶ 播放";
    var reset = document.createElement("button");
    reset.type = "button";
    reset.className = "demo-reset";
    reset.textContent = "↺ 重置";
    reset.style.display = "none";
    var count = document.createElement("span");
    count.className = "demo-count";
    count.setAttribute("aria-live", "polite");
    count.textContent = steps.length + " 步";
    bar.appendChild(play);
    bar.appendChild(reset);
    bar.appendChild(count);
    fig.insertBefore(bar, fig.firstChild);
    fig.__demo = { count: count, play: play, reset: reset, stepsN: steps.length };

    var idx = -1;
    var timer = null;
    function stopTimer() {
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
    }
    function figClear() {
      Array.prototype.forEach.call(fig.querySelectorAll(".fl-step.step-active"), function (s) {
        s.classList.remove("step-active");
      });
      fig.classList.remove("flow-focus");
      flowsInFig.forEach(function (f) {
        f.classList.remove("flow-focus");
      });
    }
    function show(i) {
      idx = i;
      Array.prototype.forEach.call(steps, function (s, k) {
        s.classList.toggle("step-active", k <= i);
      });
      fig.classList.add("flow-focus");
      flowsInFig.forEach(function (f) {
        f.classList.add("flow-focus");
      });
      count.textContent = (i + 1) + " / " + steps.length;
      steps[i].scrollIntoView({ block: "nearest" });
    }
    function tick() {
      if (idx + 1 >= steps.length) {
        pause(true);
        return;
      }
      show(idx + 1);
      timer = setTimeout(tick, 1400);
    }
    function startPlay() {
      stopTimer();
      fig.__playing = true;
      play.textContent = "⏸ 暂停";
      reset.style.display = "";
      flowsInFig.forEach(function (f) {
        f.__playing = true;
        f.__pinned = null;
      });
      /* 从头播：全新一轮或上一轮已播完（idx 停在末尾）都先清场，否则重播秒停 */
      if (idx + 1 >= steps.length) {
        idx = -1;
        figClear();
        count.textContent = "0 / " + steps.length;
      }
      timer = setTimeout(tick, 200);
    }
    function pause(done) {
      stopTimer();
      fig.__playing = false;
      fig.__done = !!done;
      flowsInFig.forEach(function (f) {
        f.__playing = false;
      });
      play.textContent = done ? "▶ 重播" : "▶ 继续";
      if (done) count.textContent = steps.length + " / " + steps.length + " · 播完了";
    }
    play.addEventListener("click", function () {
      if (fig.__playing || timer) pause(false);
      else startPlay();
    });
    reset.addEventListener("click", function () {
      stopTimer();
      fig.__playing = false;
      fig.__done = false;
      flowsInFig.forEach(function (f) {
        f.__playing = false;
        f.__pinned = null;
      });
      idx = -1;
      figClear();
      play.textContent = "▶ 播放";
      reset.style.display = "none";
      count.textContent = steps.length + " 步";
    });
    /* 播放中点某一步：跳到那步继续 */
    fig.addEventListener("click", function (e) {
      if (!fig.__playing) return;
      var step = stepOf(e);
      if (!step) return;
      var i = steps.indexOf(step);
      if (i >= 0) {
        stopTimer();
        show(i);
        timer = setTimeout(tick, 1400);
      }
    });
    /* 页面切到后台自动暂停 */
    document.addEventListener("visibilitychange", function () {
      if (document.hidden && fig.__playing) pause(false);
    });
  });
})();
