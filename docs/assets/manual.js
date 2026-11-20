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

  /* ---------- 宽图横向滚动提示 ---------- */
  function markScrollable() {
    document.querySelectorAll(".diagram-scroll").forEach(function (box) {
      var need = box.scrollWidth > box.clientWidth + 2;
      box.classList.toggle("is-scrollable", need);
    });
  }
  markScrollable();
  window.addEventListener("resize", markScrollable);
  window.addEventListener("load", markScrollable);

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
})();
