/*
 * SmartDock Analytics SDK v1
 * 零依赖、可异步加载：访客 ID、30 分钟会话、SPA 路由、批量上报、失败重试。
 *
 * 用法：
 *   <script src="/sdk/smartdock-analytics.js"></script>
 *   <script>
 *     SmartdockAnalytics.init({ siteKey: 'sd_demo_site', endpoint: '/analytics/collect' });
 *   </script>
 *
 * 可选：
 *   SmartdockAnalytics.identify('user-1001', { plan: 'pro' });
 *   SmartdockAnalytics.track('signup', { plan: 'pro' });
 *   SmartdockAnalytics.flush();
 */
(function (window, document) {
  'use strict';

  if (window.SmartdockAnalytics && window.SmartdockAnalytics.__installed) return;

  var STORAGE_VISITOR = 'sd_analytics_vid';
  var STORAGE_SESSION = 'sd_analytics_session';
  var SESSION_GAP_MS = 30 * 60 * 1000;
  var MAX_QUEUE = 200;
  var BATCH_SIZE = 10;
  var FLUSH_INTERVAL_MS = 3000;

  var config = {
    siteKey: '',
    endpoint: '/analytics/collect',
    autoPageView: true,
    sessionGapMs: SESSION_GAP_MS,
    debug: false
  };
  var installed = false;
  var started = false;
  var queue = [];
  var timer = null;
  var memoryVisitor = null;
  var memorySession = null;
  var userId = null;
  var userTraits = null;

  // ---------------------------------------------------------------- 基础工具
  function log() {
    if (!config.debug || !window.console) return;
    var args = Array.prototype.slice.call(arguments);
    args.unshift('[SmartDock]');
    window.console.log.apply(window.console, args);
  }

  function uuid() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      try {
        return window.crypto.randomUUID();
      } catch (e) {
        /* 老浏览器 crypto 可能不可用 */
      }
    }
    var d = Date.now().toString(16);
    var r = Math.random().toString(16).slice(2, 10);
    return d + '-' + r;
  }

  function readStore(storage, key) {
    try {
      return window[storage].getItem(key);
    } catch (e) {
      return null;
    }
  }

  function writeStore(storage, key, value) {
    try {
      window[storage].setItem(key, value);
    } catch (e) {
      /* 隐私模式下写入会失败，降到内存 */
    }
  }

  function parseJson(raw) {
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch (e) {
      return null;
    }
  }

  function currentUrl() {
    return window.location.pathname + window.location.search + window.location.hash;
  }

  function referrer() {
    return document.referrer || '';
  }

  // ---------------------------------------------------------------- 身份 / 会话
  function visitorId() {
    if (memoryVisitor) return memoryVisitor;
    var stored = readStore('localStorage', STORAGE_VISITOR);
    if (!stored) {
      stored = 'v-' + uuid();
      writeStore('localStorage', STORAGE_VISITOR, stored);
    }
    memoryVisitor = stored;
    return stored;
  }

  function sessionId() {
    var now = Date.now();
    var cached = memorySession || parseJson(readStore('sessionStorage', STORAGE_SESSION));
    if (cached && cached.id && now - Number(cached.lastAt || 0) < config.sessionGapMs) {
      cached.lastAt = now;
      memorySession = cached;
      writeStore('sessionStorage', STORAGE_SESSION, JSON.stringify(cached));
      return cached.id;
    }
    var created = { id: 's-' + uuid(), lastAt: now };
    memorySession = created;
    writeStore('sessionStorage', STORAGE_SESSION, JSON.stringify(created));
    return created.id;
  }

  function touchSession() {
    if (!memorySession) return;
    memorySession.lastAt = Date.now();
    writeStore('sessionStorage', STORAGE_SESSION, JSON.stringify(memorySession));
  }

  // ---------------------------------------------------------------- 环境上下文
  function deviceType() {
    var ua = navigator.userAgent || '';
    if (/iPad|Tablet|PlayBook|Silk/i.test(ua)) return 'tablet';
    if (/Mobile|Android|iPhone|iPod|Windows Phone/i.test(ua)) return 'mobile';
    return 'desktop';
  }

  function osName() {
    var ua = navigator.userAgent || '';
    if (/Windows NT/i.test(ua)) return 'Windows';
    if (/Android/i.test(ua)) return 'Android';
    if (/iPhone|iPad|iPod/i.test(ua)) return 'iOS';
    if (/Mac OS X/i.test(ua)) return 'macOS';
    if (/Linux/i.test(ua)) return 'Linux';
    return 'Unknown';
  }

  function browserName() {
    var ua = navigator.userAgent || '';
    if (/Edg\//i.test(ua)) return 'Edge';
    if (/OPR\/|Opera/i.test(ua)) return 'Opera';
    if (/Firefox\//i.test(ua)) return 'Firefox';
    if (/Chrome\//i.test(ua)) return 'Chrome';
    if (/Safari\//i.test(ua)) return 'Safari';
    return 'Unknown';
  }

  function utmFromUrl() {
    var params = new URLSearchParams(window.location.search || '');
    var keys = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_content', 'utm_term'];
    var utm = {};
    var found = false;
    for (var i = 0; i < keys.length; i++) {
      var value = params.get(keys[i]);
      if (value) {
        utm[keys[i].replace('utm_', '')] = value;
        found = true;
      }
    }
    return found ? utm : null;
  }

  function context(extra) {
    var ctx = {
      pageUrl: currentUrl(),
      title: document.title || '',
      referrer: referrer(),
      deviceType: deviceType(),
      os: osName(),
      browser: browserName(),
      language: navigator.language || '',
      screen: window.screen ? window.screen.width + 'x' + window.screen.height : ''
    };
    var utm = utmFromUrl();
    if (utm) {
      ctx.utm = utm;
      if (utm.source) ctx.sourceChannel = utm.source;
      if (utm.campaign) ctx.campaign = utm.campaign;
    }
    if (extra) {
      for (var key in extra) {
        if (Object.prototype.hasOwnProperty.call(extra, key) && extra[key] !== undefined) {
          ctx[key] = extra[key];
        }
      }
    }
    return ctx;
  }

  // ---------------------------------------------------------------- 队列 / 上报
  function enqueue(eventName, props, ctx) {
    if (!config.siteKey) {
      log('missing siteKey, drop event', eventName);
      return;
    }
    var event = {
      eventName: eventName,
      eventUid: 'sdk-' + uuid(),
      anonymousId: visitorId(),
      sessionId: sessionId(),
      occurredTime: new Date().toISOString(),
      props: props || {},
      context: ctx || context()
    };
    if (userId) event.userId = userId;
    if (userTraits) event.subjectName = userTraits.name || userTraits.nickname || undefined;
    queue.push(event);
    if (queue.length > MAX_QUEUE) queue = queue.slice(queue.length - MAX_QUEUE);
    log('enqueue', eventName, event);
    if (queue.length >= BATCH_SIZE) {
      flush();
    } else {
      scheduleFlush();
    }
  }

  function scheduleFlush() {
    if (timer) return;
    timer = window.setTimeout(function () {
      timer = null;
      flush();
    }, FLUSH_INTERVAL_MS);
  }

  function send(payload, useBeacon) {
    var body = JSON.stringify(payload);
    if (useBeacon && navigator.sendBeacon) {
      try {
        var blob = new Blob([body], { type: 'application/json' });
        return navigator.sendBeacon(config.endpoint, blob);
      } catch (e) {
        /* 继续走 fetch */
      }
    }
    if (window.fetch) {
      try {
        window
          .fetch(config.endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body,
            credentials: 'omit',
            keepalive: true
          })
          .catch(function (err) {
            log('send failed', err);
          });
        return true;
      } catch (e) {
        log('fetch throw', e);
      }
    }
    return false;
  }

  function flush(useBeacon) {
    if (timer) {
      window.clearTimeout(timer);
      timer = null;
    }
    if (!queue.length) return;
    var batch = queue.slice(0, 50);
    queue = queue.slice(batch.length);
    var payload = { siteKey: config.siteKey, events: batch };
    log('flush', batch.length);
    send(payload, useBeacon);
  }

  // ---------------------------------------------------------------- 页面浏览
  function pageView(extra) {
    touchSession();
    var ctx = context(extra);
    var props = { pageUrl: ctx.pageUrl, title: ctx.title };
    if (ctx.referrer) props.referrer = ctx.referrer;
    enqueue('page_view', props, ctx);
  }

  function wrapHistory(method) {
    var original = window.history[method];
    if (typeof original !== 'function') return;
    window.history[method] = function () {
      var result = original.apply(this, arguments);
      if (config.autoPageView) {
        window.setTimeout(function () {
          pageView({ navigation: method });
        }, 0);
      }
      return result;
    };
  }

  // ---------------------------------------------------------------- 公开 API
  var api = {
    __installed: true,

    init: function (options) {
      if (started) {
        log('already started');
        return api;
      }
      options = options || {};
      config.siteKey = options.siteKey || config.siteKey;
      config.endpoint = options.endpoint || config.endpoint;
      config.autoPageView = options.autoPageView !== false;
      config.debug = options.debug === true;
      if (options.sessionGapMinutes) {
        config.sessionGapMs = Math.max(1, Number(options.sessionGapMinutes)) * 60 * 1000;
      }
      if (!config.siteKey) {
        if (window.console) window.console.warn('[SmartDock] init 缺少 siteKey，SDK 不启动');
        return api;
      }
      started = true;
      visitorId();
      sessionId();

      if (config.autoPageView) {
        pageView({ navigation: 'load' });
        wrapHistory('pushState');
        wrapHistory('replaceState');
        window.addEventListener('popstate', function () {
          if (config.autoPageView) pageView({ navigation: 'popstate' });
        });
      }
      window.addEventListener('pagehide', function () {
        flush(true);
      });
      document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') flush(true);
      });
      log('started', config);
      return api;
    },

    track: function (eventName, props) {
      if (!started) {
        log('track before init, dropped', eventName);
        return api;
      }
      if (!eventName) return api;
      enqueue(String(eventName), props || {}, context());
      return api;
    },

    page: function (props) {
      if (!started) return api;
      pageView(props || {});
      return api;
    },

    identify: function (id, traits) {
      userId = id ? String(id) : null;
      userTraits = traits || null;
      if (started && userId) {
        enqueue('identify', { userId: userId }, context());
      }
      return api;
    },

    reset: function () {
      queue = [];
      userId = null;
      userTraits = null;
      memoryVisitor = null;
      memorySession = null;
      try {
        window.localStorage.removeItem(STORAGE_VISITOR);
        window.sessionStorage.removeItem(STORAGE_SESSION);
      } catch (e) {
        /* ignore */
      }
      return api;
    },

    flush: function () {
      flush(false);
      return api;
    }
  };

  // 自动初始化：<script src=".../smartdock-analytics.js" data-site-key="xxx">
  var script = document.currentScript;
  if (script) {
    var autoKey = script.getAttribute('data-site-key');
    if (autoKey) {
      var src = script.src || '';
      var sdkIndex = src.indexOf('/sdk/');
      var origin = sdkIndex > -1 ? src.slice(0, sdkIndex) : window.location.origin;
      api.init({
        siteKey: autoKey,
        endpoint: script.getAttribute('data-endpoint') || origin + '/analytics/collect',
        debug: script.getAttribute('data-debug') === 'true'
      });
    }
  }

  window.SmartdockAnalytics = api;
})(window, document);
