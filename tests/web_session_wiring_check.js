// INT-01 真实接线测试：在同一个假原生桥上跑**真实的** page_session.js +
// overlay_touch_region.js + app.js 引导，验证页面会话唯一。
//
// 这不是源码关键词搜索，也不是只测纯策略：三个真实文件被依次求值，
// app.js 的 boot() 真实执行，穿透模块真实 install()，假桥按原生
// PageSessionRegistry 的语义（每个宿主只有一个当前会话）记账。
//
// 覆盖复审补充 INT-01 的验收：
//   1) 同一文档里两个模块登记的是**同一个**代际编号，且只登记一次；
//   2) 开启穿透后发起截图：请求携带的会话 == 原生当前会话（不会被拒）；
//   3) 关闭/再次开启穿透期间截图仍然有效，代际不刷新；
//   4) 同 URL 刷新产生新代际；旧页迟到注销清不掉新页；旧截图被拒绝；
//   5) 提交给原生的区域载荷带的是同一个会话编号。
//
// 用法: node tests/web_session_wiring_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml'))
  ? _root
  : path.join(_root, 'sakura_remote');
const STATIC = path.join(PLUGIN_ROOT, 'static');

const pageSessionSource = fs.readFileSync(path.join(STATIC, 'page_session.js'), 'utf8');
const regionSource = fs.readFileSync(path.join(STATIC, 'overlay_touch_region.js'), 'utf8');
const appSource = fs.readFileSync(path.join(STATIC, 'app.js'), 'utf8');

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/* ---------- 假原生桥（按真实 PageSessionRegistry 语义记账） ---------- */

function makeBridge() {
  return {
    // 单个宿主 pageId 的「当前会话」，与 PageSessionRegistry 一致
    currentSession: '',
    registerCalls: [],
    invalidateCalls: [],
    screenshotRequests: [],
    screenshotRejections: [],
    regionPayloads: [],
    regionEnabled: [],
    appliedRevision: 0,

    registerPageSession(sessionId) {
      this.registerCalls.push(sessionId);
      // PageSessionRegistry.register：同编号幂等，不同编号覆盖旧会话
      this.currentSession = sessionId;
    },
    invalidatePageSession(sessionId) {
      this.invalidateCalls.push(sessionId);
      // PageSessionRegistry.invalidate(pageId, sessionId)：只有匹配才清
      if (!sessionId || sessionId === this.currentSession) {
        this.currentSession = '';
      }
    },
    requestScreenshotWithSession(sessionId) {
      this.screenshotRequests.push(sessionId);
      // 原生 OverlayService.requestScreenshot 的复核：会话不匹配直接拒绝
      if (sessionId !== this.currentSession) {
        this.screenshotRejections.push(sessionId);
        return 'rejected-stale-session';
      }
      return 'accepted';
    },
    requestScreenshot() {
      this.screenshotRequests.push('<no-session>');
      return 'accepted-legacy';
    },
    rememberUrl() {},
    isTouchRegionSupported() { return true; },
    setPassThroughEnabled(value) {
      this.regionEnabled.push(!!value);
      if (!value) this.appliedRevision = -1;
      return true;
    },
    setTouchRegion(json) {
      const payload = JSON.parse(json);
      this.regionPayloads.push(payload);
      this.appliedRevision = payload.revision;
      return 'applied';
    },
    touchRegionStatus() {
      return JSON.stringify({
        supported: true, enabled: true, revision: this.appliedRevision,
        rects: 0, fallbackFull: false, error: '',
      });
    },
  };
}

/* ---------- 假 DOM ---------- */

function makeClassList() {
  const set = new Set();
  return {
    _set: set,
    add(...names) { names.forEach((n) => set.add(n)); },
    remove(...names) { names.forEach((n) => set.delete(n)); },
    contains(name) { return set.has(name); },
    toggle(name, force) {
      const want = force === undefined ? !set.has(name) : !!force;
      if (want) set.add(name); else set.delete(name);
      return want;
    },
  };
}

/** 假 style：既能当属性对象用，也支持 CSS 自定义属性 API。 */
function makeStyle() {
  const props = new Map();
  const target = {
    setProperty(name, value) { props.set(String(name), String(value)); },
    getPropertyValue(name) { return props.has(String(name)) ? props.get(String(name)) : ''; },
    removeProperty(name) {
      const value = props.get(String(name)) || '';
      props.delete(String(name));
      return value;
    },
  };
  return new Proxy(target, {
    get(t, key) {
      if (key in t) return t[key];
      return props.has(String(key)) ? props.get(String(key)) : '';
    },
    set(t, key, value) {
      if (key in t) { t[key] = value; return true; }
      props.set(String(key), String(value));
      return true;
    },
  });
}

/**
 * 给一个 canvas 元素挂上真实的 2d 上下文：getImageData 按 alphaFn 生成像素，
 * 与 tests/touch_region_geometry_check.js 的做法一致。
 */
function attachAlphaContext(canvas, alphaFn) {
  const getImageData = () => {
    const w = canvas.width | 0;
    const h = canvas.height | 0;
    const data = new Uint8ClampedArray(Math.max(0, w * h * 4));
    for (let py = 0; py < h; py += 1) {
      for (let px = 0; px < w; px += 1) {
        data[(py * w + px) * 4 + 3] = alphaFn(px);
      }
    }
    return { data };
  };
  canvas.getContext = (type) => (type === '2d'
    ? { clearRect() {}, drawImage() {}, getImageData }
    : null);
  return canvas;
}

function makeAlphaCanvas(alphaFn) {
  return attachAlphaContext({ width: 0, height: 0, tagName: 'CANVAS' }, alphaFn);
}

function makeElement(id, doc, options) {
  const opts = options || {};
  const listeners = new Map();
  const classes = makeClassList();
  const style = makeStyle();
  const rect = opts.rect
    || { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 };
  const el = {
    id,
    tagName: opts.tagName || 'DIV',
    nodeType: 1,
    children: [],
    dataset: {},
    classList: classes,
    style: makeStyle(),
    value: opts.value === undefined ? '' : opts.value,
    textContent: '',
    innerHTML: '',
    innerText: '',
    checked: false,
    disabled: false,
    hidden: false,
    complete: true,
    naturalWidth: opts.naturalWidth || 0,
    naturalHeight: opts.naturalHeight || 0,
    src: opts.src || '',
    currentSrc: opts.src || '',
    width: 0,
    height: 0,
    offsetWidth: 100,
    offsetHeight: 100,
    clientWidth: 100,
    clientHeight: 100,
    scrollTop: 0,
    scrollHeight: 100,
    scrollLeft: 0,
    parentNode: null,
    getBoundingClientRect() { return rect; },
    getClientRects() { return [rect]; },
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const list = listeners.get(type) || [];
      const index = list.indexOf(fn);
      if (index >= 0) list.splice(index, 1);
    },
    dispatch(type, event) {
      const payload = event || {
        type, preventDefault() {}, stopPropagation() {}, target: el,
      };
      (listeners.get(type) || []).slice().forEach((fn) => fn(payload));
    },
    setAttribute() {},
    removeAttribute() {},
    getAttribute() { return null; },
    hasAttribute() { return false; },
    appendChild(child) { el.children.push(child); return child; },
    removeChild(child) {
      const index = el.children.indexOf(child);
      if (index >= 0) el.children.splice(index, 1);
      return child;
    },
    insertBefore(child) { el.children.push(child); return child; },
    replaceChildren() {},
    contains() { return false; },
    closest() { return null; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    focus() {},
    blur() {},
    click() { el.dispatch('click'); },
    pause() {},
    play() { return Promise.resolve(); },
    load() {},
    remove() {},
    setPointerCapture() {},
    releasePointerCapture() {},
  };
  if (opts.canvas) {
    attachAlphaContext(el, opts.alphaFn || (() => 0));
  }
  return el;
}

/**
 * 建立一个「文档」，并按 web_ui.py 的真实顺序求值三个脚本。
 *
 * @param bridge 共享的假原生桥（跨页面重载时传同一个，模拟同一个原生进程）
 */
function loadPage(bridge, options) {
  const opts = options || {};
  const alphaFn = opts.alphaFn || (() => 0);
  const store = new Map(Object.entries(opts.storage || {}));
  const body = makeElement('body', null, { tagName: 'BODY' });
  body.parentNode = null;
  const documentElement = makeElement('html', null, { tagName: 'HTML' });
  const registry = new Map();
  const documentListeners = new Map();

  const doc = {
    body,
    documentElement,
    hidden: false,
    readyState: 'complete',
    defaultView: null,
    getElementById(id) {
      if (!registry.has(id)) {
        if (id === 'portrait') {
          registry.set(id, makeElement(id, doc, {
            tagName: 'IMG',
            naturalWidth: opts.portraitWidth === undefined ? 100 : opts.portraitWidth,
            naturalHeight: opts.portraitHeight === undefined ? 100 : opts.portraitHeight,
            src: 'http://pc/asset/portrait?key=A',
          }));
        } else {
          registry.set(id, makeElement(id, doc, { value: '100' }));
        }
      }
      return registry.get(id);
    },
    createElement(tag) {
      if (tag === 'canvas') {
        return makeAlphaCanvas(alphaFn);
      }
      return makeElement(tag, doc, { alphaFn });
    },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    addEventListener(type, fn) {
      if (!documentListeners.has(type)) documentListeners.set(type, []);
      documentListeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const list = documentListeners.get(type) || [];
      const index = list.indexOf(fn);
      if (index >= 0) list.splice(index, 1);
    },
    dispatch(type, event) {
      (documentListeners.get(type) || []).slice()
        .forEach((fn) => fn(event || { type }));
    },
    execCommand() { return false; },
    createTextNode() { return { nodeType: 3 }; },
  };

  const win = {
    innerWidth: 393,
    innerHeight: 700,
    devicePixelRatio: 2.75,
    document: doc,
    location: { href: 'http://pc:8770/?mode=overlay', search: '?mode=overlay', host: 'pc:8770' },
    navigator: { clipboard: null, userAgent: 'node-test' },
    localStorage: {
      getItem(key) { return store.has(key) ? store.get(key) : null; },
      setItem(key, value) { store.set(key, String(value)); },
      removeItem(key) { store.delete(key); },
    },
    sessionStorage: {
      getItem() { return null; },
      setItem() {},
      removeItem() {},
    },
    SakuraNative: bridge,
    setTimeout,
    clearTimeout,
    setInterval() { return 0; },
    clearInterval() {},
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    cancelAnimationFrame() {},
    getComputedStyle() {
      return {
        display: 'block', visibility: 'visible', opacity: '1',
        pointerEvents: 'auto', transform: 'none', objectFit: 'contain',
        objectPosition: '50% 50%', position: 'static', overflow: 'visible',
        width: '100px', height: '100px', getPropertyValue() { return ''; },
      };
    },
    addEventListener() {},
    removeEventListener() {},
    dispatch() {},
    matchMedia() { return { matches: false, addEventListener() {}, addListener() {} }; },
  };
  doc.defaultView = win;
  win.window = win;

  const factory = new Function(
    'window', 'document', 'localStorage', 'sessionStorage', 'navigator', 'location',
    'fetch', 'Image', 'Audio', 'console', 'URLSearchParams', 'Date',
    'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval',
    'requestAnimationFrame', 'cancelAnimationFrame',
    (opts.disablePageSession ? '/* page_session.js disabled */' : pageSessionSource)
      + '\n;' + regionSource
      + '\n;' + appSource
      + '\n;return { app: { PAGE_SESSION_ID: PAGE_SESSION_ID, boot: boot,'
      + ' requestScreenshot: requestScreenshot, registerPageSession: registerPageSession,'
      + ' invalidatePageSession: invalidatePageSession },'
      + ' region: window.SakuraTouchRegion, pageSession: window.SakuraPageSession,'
      + ' ScreenshotButton: null };'
  );

  const fetchStub = () => Promise.reject(new Error('offline-test'));
  const ImageStub = function ImageStub() { return makeElement('img', doc); };
  const AudioStub = function AudioStub() {
    return { preload: '', src: '', addEventListener() {}, pause() {}, play() { return Promise.resolve(); }, load() {} };
  };

  const handles = factory(
    win, doc, win.localStorage, win.sessionStorage, win.navigator, win.location,
    fetchStub, ImageStub, AudioStub, console, URLSearchParams, Date,
    setTimeout, clearTimeout, () => 0, () => {},
    (fn) => setTimeout(fn, 0), () => {}
  );

  return {
    win, doc, handles, bridge,
    screenshot() { return handles.app.requestScreenshot(); },
    pagehide() { doc.dispatch('pagehide', { type: 'pagehide' }); },
  };
}

/* ---------- 主流程 ---------- */

async function main() {
  const bridge = makeBridge();

  /* 1) 同文档：两个模块共用同一代际，且只登记一次 */
  const page1 = loadPage(bridge, { alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0) });
  await sleep(5);

  const appSession = page1.handles.app.PAGE_SESSION_ID;
  const regionSession = page1.handles.region && page1.handles.region._sessionId
    ? page1.handles.region._sessionId()
    : '';
  check('app.js 用页面级共享代际', typeof appSession === 'string' && appSession.startsWith('ps-'),
    appSession);
  check('轮廓模块用同一个代际（不再自行随机）', regionSession === appSession,
    { appSession, regionSession });
  check('原生只被登记一次', bridge.registerCalls.length === 1, bridge.registerCalls);
  check('原生当前会话 == 页面代际', bridge.currentSession === appSession,
    { current: bridge.currentSession, appSession });

  /* 2) 开启穿透后发起截图：请求会话与原生当前会话一致 */
  await sleep(160);   // 等一次去抖提交
  if (process.env.SAKURA_WIRING_DEBUG) {
    console.log('[debug] region status =', JSON.stringify(page1.handles.region.status()));
    const portrait = page1.doc.getElementById('portrait');
    const scan = page1.handles.region._scanAlpha(portrait);
    console.log('[debug] scan =', JSON.stringify({
      width: scan && scan.width, height: scan && scan.height,
      segments: scan && scan.segments, complete: scan && scan.complete,
      failed: scan && scan.failed, rows: scan && scan.rows.length,
    }));
    console.log('[debug] native enabled calls =', JSON.stringify(bridge.regionEnabled));
  }
  check('穿透已提交区域载荷', bridge.regionPayloads.length >= 1, bridge.regionPayloads.length);
  check('区域载荷带同一会话',
    bridge.regionPayloads.length > 0
      && bridge.regionPayloads[bridge.regionPayloads.length - 1].pageSessionId === appSession,
    bridge.regionPayloads.map((p) => p.pageSessionId));

  page1.screenshot();
  check('截图请求带页面代际', bridge.screenshotRequests[0] === appSession,
    bridge.screenshotRequests);
  check('截图未被原生拒绝', bridge.screenshotRejections.length === 0,
    bridge.screenshotRejections);
  check('穿透开启后登记次数仍为 1', bridge.registerCalls.length === 1, bridge.registerCalls);

  /* 3) 关闭 / 再次开启穿透：代际不刷新，截图仍然有效 */
  page1.handles.region.setEnabled(false);
  await sleep(5);
  page1.handles.region.setEnabled(true);
  await sleep(160);
  check('关开穿透后登记次数仍为 1（不刷新代际）', bridge.registerCalls.length === 1,
    bridge.registerCalls);
  check('关开穿透后会话不变', bridge.currentSession === appSession,
    { current: bridge.currentSession, appSession });
  page1.screenshot();
  const afterToggle = bridge.screenshotRequests[bridge.screenshotRequests.length - 1];
  check('关开穿透后截图仍带同一会话', afterToggle === appSession, bridge.screenshotRequests);
  check('关开穿透后截图未被拒绝', bridge.screenshotRejections.length === 0,
    bridge.screenshotRejections);

  /* 4) 同 URL 刷新：新页面产生新代际，旧页迟到注销清不掉新页 */
  page1.pagehide();
  check('pagehide 注销的是旧代际', bridge.invalidateCalls[0] === appSession,
    bridge.invalidateCalls);
  check('旧代际注销后原生无会话', bridge.currentSession === '', bridge.currentSession);
  // 旧页的截图请求（旧会话）此时必须被原生拒绝
  const staleVerdict = bridge.requestScreenshotWithSession(appSession);
  check('旧代际截图请求被原生拒绝', staleVerdict === 'rejected-stale-session', staleVerdict);

  const page2 = loadPage(bridge, { alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0) });
  await sleep(5);
  const appSession2 = page2.handles.app.PAGE_SESSION_ID;
  check('同 URL 刷新产生新代际', appSession2 !== appSession, { appSession, appSession2 });
  check('新页只登记一次', bridge.registerCalls.length === 2, bridge.registerCalls);
  check('原生当前会话是新代际', bridge.currentSession === appSession2,
    { current: bridge.currentSession, appSession2 });
  check('新页轮廓模块会话一致',
    page2.handles.region._sessionId() === appSession2,
    { region: page2.handles.region._sessionId(), appSession2 });

  // 旧页的迟到注销（pagehide 再次触发）不得清掉新页
  page1.pagehide();
  check('旧页迟到注销清不掉新页', bridge.currentSession === appSession2,
    { current: bridge.currentSession, appSession2 });
  check('旧页注销带的是自己的旧代际',
    bridge.invalidateCalls[bridge.invalidateCalls.length - 1] === appSession,
    bridge.invalidateCalls);

  // 新页截图有效
  const beforeCount = bridge.screenshotRejections.length;
  page2.screenshot();
  check('新页截图带新代际',
    bridge.screenshotRequests[bridge.screenshotRequests.length - 1] === appSession2,
    bridge.screenshotRequests);
  check('新页截图未被拒绝', bridge.screenshotRejections.length === beforeCount,
    bridge.screenshotRejections);

  /* 5) 兜底：page_session.js 缺席时仍不崩、仍能登记 */
  const bridgeFallback = makeBridge();
  const fallbackPage = loadPage(bridgeFallback, { disablePageSession: true });
  await sleep(5);
  if (process.env.SAKURA_WIRING_DEBUG) {
    console.log('[debug] shared key =', fallbackPage.win.__sakuraSharedPageSessionId,
      '| region =', fallbackPage.handles.region._sessionId(),
      '| app =', fallbackPage.handles.app.PAGE_SESSION_ID,
      '| calls =', JSON.stringify(bridgeFallback.registerCalls));
  }
  check('无共享模块时 app.js 仍登记（兜底）', bridgeFallback.registerCalls.length >= 1,
    bridgeFallback.registerCalls);
  check('无共享模块时登记的就是截图用的代际',
    bridgeFallback.currentSession === fallbackPage.handles.app.PAGE_SESSION_ID,
    { current: bridgeFallback.currentSession, app: fallbackPage.handles.app.PAGE_SESSION_ID });

  console.log();
  if (failures.length) {
    console.error(`${failures.length} 项失败：${failures.join(', ')}`);
    process.exit(1);
  }
  console.log('PASS');
}

main().catch((error) => {
  console.error('测试异常：', error);
  process.exit(1);
});
