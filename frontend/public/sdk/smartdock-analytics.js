/*
 * SmartDock Analytics SDK v2（批7 采集质量版）
 * 零依赖、可异步加载：访客 ID、30 分钟会话、SPA 路由、page_leave 停留时长、
 * 批量上报、离线队列 + 指数退避补传、sendBeacon 兜底、同意管理。
 *
 * 用法：
 *   <script src="/sdk/smartdock-analytics.js" data-site-key="sd_demo_site"></script>
 *   或手动：
 *   <script src="/sdk/smartdock-analytics.js"></script>
 *   <script>
 *     SmartdockAnalytics.init({ siteKey: 'sd_demo_site', endpoint: '/analytics/collect' });
 *   </script>
 *
 * 可选：
 *   SmartdockAnalytics.identify('user-1001', { plan: 'pro' });
 *   SmartdockAnalytics.track('signup', { plan: 'pro' });
 *   SmartdockAnalytics.setConsent(false);   // 访客撤回同意：停止采集并清空本地队列
 *   SmartdockAnalytics.init({ siteKey: 'x', consent: false });  // 或接入时就默认不采
 *   SmartdockAnalytics.flush();
 *
 * 网络失败：事件进 localStorage 离线队列（上限 500 条），1s→2s→4s…封顶 60s 带抖动重试；
 * 服务端确定性拒绝（accepted=0 且 dropped 有值，如机器人/跨域拦截）不重试。
 */
(function (window, document) {
  'use strict';

  if (window.SmartdockAnalytics && window.SmartdockAnalytics.__installed) return;

  var STORAGE_VISITOR = 'sd_analytics_vid';
  var STORAGE_SESSION = 'sd_analytics_session';
  var STORAGE_QUEUE = 'sd_analytics_queue';
  var STORAGE_CONSENT = 'sd_analytics_consent';
  var SESSION_GAP_MS = 30 * 60 * 1000;
  var MAX_QUEUE = 500;
  var BATCH_SIZE = 10;
  var SEND_BATCH = 50;
  var FLUSH_INTERVAL_MS = 3000;
  var RETRY_BASE_MS = 1000;
  var RETRY_MAX_MS = 60 * 1000;

  var config = {
    siteKey: '',
    endpoint: '/analytics/collect',
    autoPageView: true,
    sessionGapMs: SESSION_GAP_MS,
    debug: false
  };
  var started = false;
  var queue = [];
  var timer = null;
  var retryTimer = null;
  var retryAttempt = 0;
  var sending = false;
  var memoryVisitor = null;
  var memorySession = null;
  var userId = null;
  var userTraits = null;
  var lastTrackedUrl = null;
  var pageOpen = { url: null, at: 0 };
  var consentGranted = true;

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

  function removeStore(storage, key) {
    try {
      window[storage].removeItem(key);
    } catch (e) {
      /* ignore */
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

  // ---------------------------------------------------------------- 同意管理（批7 拍板 Q6-A）
  function loadConsent() {
    var stored = readStore('localStorage', STORAGE_CONSENT);
    consentGranted = stored === null ? true : stored === '1';
    return consentGranted;
  }

  function setConsent(granted) {
    consentGranted = granted === true;
    writeStore('localStorage', STORAGE_CONSENT, consentGranted ? '1' : '0');
    if (!consentGranted) {
      // 撤回即止：停采集、清空内存与本地离线队列，撤回期间的动作不留痕
      queue = [];
      persistQueue();
      if (retryTimer) {
        window.clearTimeout(retryTimer);
        retryTimer = null;
      }
      log('consent revoked, collection stopped and local queue cleared');
    } else {
      log('consent granted, collection resumed');
    }
  }

  // ---------------------------------------------------------------- 离线队列持久化
  function persistQueue() {
    if (!queue.length) {
      removeStore('localStorage', STORAGE_QUEUE);
      return;
    }
    writeStore('localStorage', STORAGE_QUEUE, JSON.stringify(queue.slice(0, MAX_QUEUE)));
  }

  function restoreQueue() {
    var saved = parseJson(readStore('localStorage', STORAGE_QUEUE));
    if (saved && saved.length) {
      queue = saved.slice(0, MAX_QUEUE).concat(queue);
      persistQueue();
      log('restored', saved.length, 'events from offline queue');
      scheduleFlush();
    }
  }

  // ---------------------------------------------------------------- 队列 / 上报
  function buildEvent(eventName, props, ctx) {
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
    return event;
  }

  function enqueue(eventName, props, ctx) {
    if (!config.siteKey) {
      log('missing siteKey, drop event', eventName);
      return;
    }
    if (!consentGranted) {
      log('consent not granted, drop event', eventName);
      return;
    }
    queue.push(buildEvent(eventName, props, ctx));
    if (queue.length > MAX_QUEUE) {
      queue = queue.slice(queue.length - MAX_QUEUE);
    }
    persistQueue();
    log('enqueue', eventName);
    if (queue.length >= BATCH_SIZE) {
      flush(false);
    } else {
      scheduleFlush();
    }
  }

  function scheduleFlush() {
    if (timer) return;
    timer = window.setTimeout(function () {
      timer = null;
      flush(false);
    }, FLUSH_INTERVAL_MS);
  }

  function scheduleRetry() {
    if (retryTimer) return;
    retryAttempt = Math.min(retryAttempt + 1, 6);
    // 1s 起步: 第 1 次重试等 1s，之后 2s/4s/…封顶 60s
    var delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * Math.pow(2, retryAttempt - 1));
    delay = Math.round(delay * (0.8 + Math.random() * 0.4)); // ±20% 抖动，错峰重试
    log('上报失败，' + delay + 'ms 后重试（第 ' + retryAttempt + ' 次）');
    retryTimer = window.setTimeout(function () {
      retryTimer = null;
      flush(false);
    }, delay);
  }

  /**
   * 发送一批。done(outcome)：'ok'=已入账 / 'rejected'=确定性拒绝不重试 / 'failed'=可重试。
   * R 信封语义：HTTP 200 但业务 code 可能非 200——必须看 body.code，不能只看 res.ok。
   */
  function sendBatch(batch, useBeacon, done) {
    var payload = { siteKey: config.siteKey, events: batch };
    var body = JSON.stringify(payload);
    if (useBeacon && navigator.sendBeacon) {
      try {
        var blob = new Blob([body], { type: 'application/json' });
        if (navigator.sendBeacon(config.endpoint, blob)) {
          // 页面即将卸载，beacon 无法读响应：乐观视为成功；万一实际失败由 eventUid 幂等兜底
          done('ok');
          return;
        }
      } catch (e) {
        /* sendBeacon 抛错继续走 fetch */
      }
      /* sendBeacon 返回 false（队列满/限制）→ keepalive fetch 兜底 */
    }
    if (!window.fetch) {
      done('failed');
      return;
    }
    window
      .fetch(config.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body,
        credentials: 'omit',
        keepalive: true
      })
      .then(function (res) {
        if (!res.ok) {
          done('failed');
          return null;
        }
        return res.json().then(
          function (envelope) {
            if (envelope && envelope.code === 200) {
              done('ok');
            } else if (envelope && envelope.data && envelope.data.dropped) {
              // 服务端明确拒收（机器人/跨域等），重试只会循环，丢弃
              log('服务端拒收本批（dropped=' + envelope.data.dropped + '），不重试');
              done('rejected');
            } else {
              done('failed'); // 限流/服务异常等，退避后重试
            }
          },
          function () {
            done('failed');
          }
        );
      })
      .catch(function (err) {
        log('send failed', err);
        done('failed');
      });
  }

  function flush(useBeacon) {
    if (timer) {
      window.clearTimeout(timer);
      timer = null;
    }
    if (!started || !consentGranted || !queue.length) return;
    if (useBeacon) {
      sendOne(true);
      return;
    }
    if (sending) {
      scheduleFlush(); // 已有一批在途，等它回来再继续
      return;
    }
    sendOne(false);
  }

  function sendOne(useBeacon) {
    var batch = queue.splice(0, SEND_BATCH);
    if (!batch.length) return;
    persistQueue();
    log('flush', batch.length, useBeacon ? '(beacon)' : '(fetch)');
    if (useBeacon) {
      sendBatch(batch, true, function () {});
      if (queue.length) sendOne(true); // 页面关闭：分批清空整队，不丢尾部
      return;
    }
    sending = true;
    sendBatch(batch, false, function (outcome) {
      sending = false;
      if (outcome === 'failed') {
        queue = batch.concat(queue); // 放回队首，顺序不乱
        persistQueue();
        scheduleRetry();
      } else {
        retryAttempt = 0;
        if (queue.length) scheduleFlush();
      }
    });
  }

  // ---------------------------------------------------------------- 页面浏览 / page_leave
  function pageView(extra, navigation) {
    touchSession();
    var url = currentUrl();
    if (url === lastTrackedUrl) {
      // replaceState 常用于改 query/埋点位，URL 没变不算一次新 PV
      return;
    }
    var now = Date.now();
    if (pageOpen.url && pageOpen.url !== url) {
      // SPA 路由切换：上一页当场补发 page_leave，停留时长覆盖每一页而不只是最后一页（拍板 Q7-A）
      enqueue('page_leave', { pageUrl: pageOpen.url, durationMs: now - pageOpen.at },
        context({ pageUrl: pageOpen.url }));
    }
    lastTrackedUrl = url;
    pageOpen = { url: url, at: now };
    var ctx = context(extra);
    var props = { pageUrl: ctx.pageUrl, title: ctx.title };
    if (ctx.referrer) props.referrer = ctx.referrer;
    if (navigation) props.navigation = navigation;
    enqueue('page_view', props, ctx);
  }

  function finalLeave() {
    if (!consentGranted || !pageOpen.url) return;
    var leave = buildEvent('page_leave',
      { pageUrl: pageOpen.url, durationMs: Date.now() - pageOpen.at },
      context({ pageUrl: pageOpen.url }));
    queue.push(leave);
  }

  function wrapHistory(method) {
    var original = window.history[method];
    if (typeof original !== 'function') return;
    window.history[method] = function () {
      var result = original.apply(this, arguments);
      if (config.autoPageView) {
        window.setTimeout(function () {
          pageView({ navigation: method }, method);
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
      if (options.sessionGapMinutes !== undefined && options.sessionGapMinutes !== null) {
        var gapMinutes = Number(options.sessionGapMinutes);
        if (!Number.isFinite(gapMinutes) || gapMinutes <= 0) {
          if (window.console) {
            window.console.warn('[SmartDock] sessionGapMinutes 非法（' + options.sessionGapMinutes + '），回退默认 30 分钟');
          }
        } else {
          config.sessionGapMs = Math.max(1, gapMinutes) * 60 * 1000;
        }
      }
      if (!config.siteKey) {
        if (window.console) window.console.warn('[SmartDock] init 缺少 siteKey，SDK 不启动');
        return api;
      }
      loadConsent();
      if (options.consent !== undefined) {
        setConsent(options.consent === true);
      }
      started = true;
      visitorId();
      sessionId();
      restoreQueue();

      if (config.autoPageView) {
        pageView(null, 'load');
        wrapHistory('pushState');
        wrapHistory('replaceState');
        window.addEventListener('popstate', function () {
          if (config.autoPageView) pageView({ navigation: 'popstate' }, 'popstate');
        });
      }
      window.addEventListener('pagehide', function () {
        finalLeave();
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

    /** 同意开关：默认同意；setConsent(false) 撤回后停止采集并清空本地队列 */
    setConsent: function (granted) {
      setConsent(granted);
      return api;
    },

    getConsent: function () {
      return consentGranted;
    },

    reset: function () {
      queue = [];
      persistQueue();
      userId = null;
      userTraits = null;
      memoryVisitor = null;
      memorySession = null;
      lastTrackedUrl = null;
      pageOpen = { url: null, at: 0 };
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
      var autoOptions = {
        siteKey: autoKey,
        endpoint: script.getAttribute('data-endpoint') || origin + '/analytics/collect',
        debug: script.getAttribute('data-debug') === 'true'
      };
      if (script.getAttribute('data-consent') === 'false') autoOptions.consent = false;
      api.init(autoOptions);
    }
  }

  window.SmartdockAnalytics = api;
})(window, document);
