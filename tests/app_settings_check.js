// 真机设置/穿透交互（TS-02 / TS-03 / TS-05 / TS-06）的前端行为测试。
//
// 在假 DOM + 假原生桥上真实求值 static/page_session.js + overlay_touch_region.js +
// app.js，boot() 真实执行，验证：
//   1) 上下浮动默认关闭（无偏好），已保存偏好被保留；
//   2) App 主界面（非悬浮窗）穿透开关可用，写原生持久偏好，且**不提交区域**；
//   3) 保存按钮保存**尚未 blur 的数字框**当前值，并回读校验；
//   4) 本机存储失败时如实反馈，不假装成功；
//   5) 原生回读不一致时报告「桌面立绘偏好保存失败」；
//   6) 原生推送 SakuraDisplayPreferences.apply 会应用且不形成回写回环。
//
// 用法: node tests/app_settings_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml')) ? _root : path.join(_root, 'sakura_remote');
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

/*
 * app.js 里有 `target instanceof Element`（人物点击、点空白收起）。
 * Node 里没有 Element，所以定义一个最小类并让假元素继承它，
 * 否则真实事件顺序的测试会在事件处理器里抛 ReferenceError。
 */
const FakeElement = class FakeElement {};
if (typeof globalThis.Element === 'undefined') {
  globalThis.Element = FakeElement;
}
const ElementClass = globalThis.Element;

/* ---------- 假原生桥 ---------- */

function makeBridge(options = {}) {
  return {
    supported: options.supported !== false,
    currentSession: '',
    registerCalls: [],
    invalidateCalls: [],
    regionPayloads: [],
    regionEnabled: [],
    appliedRevision: 0,
    setTouchRegionCalls: 0,
    prefWrites: [],
    setPrefCalls: 0,
    prefs: Object.assign({ has: false, value: true }, options.prefs || {}),
    floatPrefs: Object.assign({ has: false, value: false }, options.floatPrefs || {}),
    mismatchFloat: false,

    registerPageSession(id) { this.registerCalls.push(id); this.currentSession = id; },
    invalidatePageSession(id) {
      this.invalidateCalls.push(id);
      if (!id || id === this.currentSession) this.currentSession = '';
    },
    requestScreenshotWithSession(id) {
      return id === this.currentSession ? 'accepted' : 'rejected-stale-session';
    },
    requestScreenshot() { return 'accepted-legacy'; },
    requestScreenPermission() {},
    rememberUrl() {},
    isTouchRegionSupported() { return this.supported; },
    // 悬浮窗手势/尺寸链路需要这些桥方法（installOverlayGestures 会先探测 moveBy）
    moveBy() {},
    moveTo() {},
    setDragging() {},
    setWindowSize() {},
    setBubbleMode() {},
    setKeyboardOpen() {},
    overlayLayout() {
      return JSON.stringify({
        x: 0, y: 0, w: 393, h: 700, screenW: 393, screenH: 700, density: 2.75,
      });
    },
    setPassThroughEnabled(value) {
      this.regionEnabled.push(!!value);
      if (!value) this.appliedRevision = -1;
    },
    setTouchRegion(json) {
      this.setTouchRegionCalls += 1;
      const payload = JSON.parse(json);
      this.regionPayloads.push(payload);
      this.appliedRevision = payload.revision;
      return 'applied';
    },
    touchRegionStatus() {
      return JSON.stringify({
        supported: this.supported, enabled: true, revision: this.appliedRevision,
        rects: 0, fallbackFull: false, error: '', alpha: 0.78, alphaReduced: true,
        alphaSafe: 0.8, level: 1, tolerance: 7.3,
      });
    },
    displayPreferences() {
      const floatValue = this.mismatchFloat ? !this.floatPrefs.value : this.floatPrefs.value;
      return JSON.stringify({
        float: { has: this.floatPrefs.has, value: floatValue },
        passThrough: { has: this.prefs.has, value: this.mismatchPass ? !this.prefs.value : this.prefs.value, supported: this.supported },
      });
    },
    setDisplayPreferences(json) {
      this.setPrefCalls += 1;
      const prefs = JSON.parse(json);
      this.prefWrites.push(prefs);
      if (typeof prefs.passThrough === 'boolean') {
        this.prefs = { has: true, value: prefs.passThrough };
      }
      if (typeof prefs.float === 'boolean') {
        this.floatPrefs = { has: true, value: prefs.float };
      }
      return JSON.stringify({ ok: true,
        float: { has: this.floatPrefs.has, value: this.floatPrefs.value },
        passThrough: { has: this.prefs.has, value: this.prefs.value } });
    },
  };
}

/* ---------- 假 DOM（与 web_session_wiring_check 同源） ---------- */

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

function makeStyle() {
  const props = new Map();
  const target = {
    setProperty(name, value) { props.set(String(name), String(value)); },
    getPropertyValue(name) { return props.has(String(name)) ? props.get(String(name)) : ''; },
    removeProperty(name) { const value = props.get(String(name)) || ''; props.delete(String(name)); return value; },
  };
  return new Proxy(target, {
    get(t, key) { if (key in t) return t[key]; return props.has(String(key)) ? props.get(String(key)) : ''; },
    set(t, key, value) { if (key in t) { t[key] = value; return true; } props.set(String(key), String(value)); return true; },
  });
}

function attachAlphaContext(canvas, alphaFn) {
  const getImageData = () => {
    const w = canvas.width | 0;
    const h = canvas.height | 0;
    const data = new Uint8ClampedArray(Math.max(0, w * h * 4));
    for (let py = 0; py < h; py += 1) {
      for (let px = 0; px < w; px += 1) data[(py * w + px) * 4 + 3] = alphaFn(px);
    }
    return { data };
  };
  canvas.getContext = (type) => (type === '2d'
    ? { clearRect() {}, drawImage() {}, getImageData, imageSmoothingEnabled: true }
    : null);
  return canvas;
}

function makeElement(id, doc, options) {
  const opts = options || {};
  const listeners = new Map();
  const rect = opts.rect || { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 };
  const el = {
    id,
    tagName: opts.tagName || 'DIV',
    nodeType: 1,
    children: [],
    dataset: {},
    classList: makeClassList(),
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
    addEventListener(type, fn) { if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(fn); },
    removeEventListener(type, fn) { const list = listeners.get(type) || []; const i = list.indexOf(fn); if (i >= 0) list.splice(i, 1); },
    dispatch(type, event) {
      const payload = event || { type, preventDefault() {}, stopPropagation() {}, target: el };
      (listeners.get(type) || []).slice().forEach((fn) => fn(payload));
    },
    setAttribute() {}, removeAttribute() {}, getAttribute() { return null; }, hasAttribute() { return false; },
    appendChild(child) { el.children.push(child); child.parentElement = el; return child; },
    removeChild(child) { const i = el.children.indexOf(child); if (i >= 0) el.children.splice(i, 1); return child; },
    insertBefore(child) { el.children.push(child); return child; },
    replaceChildren() {}, contains() { return false; }, closest() { return null; },
    querySelector(selector) { return el.querySelectorAll(selector)[0] || null; },
    querySelectorAll(selector) {
      if (selector === '.bubbleRow') return el.children.filter(child => /^bubbleRow(?: |$)/.test(child.className || ''));
      if (selector === '.bubble') return el.children.filter(child => child.className === 'bubble');
      return [];
    },
    focus() {}, blur() {}, click() { el.dispatch('click'); },
    pause() {}, play() { return Promise.resolve(); }, load() {}, remove() {},
    setPointerCapture() {}, releasePointerCapture() {},
  };
  Object.setPrototypeOf(el, ElementClass.prototype);
  let html = '';
  Object.defineProperty(el, 'innerHTML', {
    get() { return html; },
    set(value) { html = value; if (value === '') el.children = []; },
  });
  if (opts.canvas) attachAlphaContext(el, opts.alphaFn || (() => 0));
  return el;
}

function loadPage(bridge, options) {
  const opts = options || {};
  const alphaFn = opts.alphaFn || (() => 0);
  const store = new Map(Object.entries(opts.storage || {}));
  const body = makeElement('body', null, { tagName: 'BODY' });
  const documentElement = makeElement('html', null, { tagName: 'HTML' });
  const registry = new Map();
  const documentListeners = new Map();
  const search = opts.search === undefined ? '?mode=overlay' : opts.search;

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
        } else if (id === 'scaleNumber' || id === 'fontNumber' || id === 'portraitYNumber'
            || id === 'dialogXNumber' || id === 'dialogYNumber' || id === 'bubbleHideNumber') {
          registry.set(id, makeElement(id, doc, { value: '100' }));
        } else {
          registry.set(id, makeElement(id, doc, { value: '100' }));
        }
      }
      return registry.get(id);
    },
    createElement(tag) {
      if (tag === 'canvas') return attachAlphaContext({ width: 0, height: 0, tagName: 'CANVAS' }, alphaFn);
      return makeElement(tag, doc, { alphaFn });
    },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    addEventListener(type, fn) { if (!documentListeners.has(type)) documentListeners.set(type, []); documentListeners.get(type).push(fn); },
    removeEventListener(type, fn) { const list = documentListeners.get(type) || []; const i = list.indexOf(fn); if (i >= 0) list.splice(i, 1); },
    dispatch(type, event) { (documentListeners.get(type) || []).slice().forEach((fn) => fn(event || { type })); },
    execCommand() { return false; },
    createTextNode() { return { nodeType: 3 }; },
  };

  const windowListeners = new Map();
  const win = {
    innerWidth: 393,
    innerHeight: 700,
    devicePixelRatio: 2.75,
    document: doc,
    screen: { width: 393, height: 851 }, innerWidth: 393, innerHeight: 851,
    location: { href: 'http://pc:8770/' + search, search, host: 'pc:8770' },
    navigator: { clipboard: null, userAgent: 'node-test' },
    localStorage: {
      getItem(key) { return store.has(key) ? store.get(key) : null; },
      setItem(key, value) { store.set(key, String(value)); },
      removeItem(key) { store.delete(key); },
    },
    sessionStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    SakuraNative: bridge,
    setTimeout,
    clearTimeout,
    setInterval() { return 0; },
    clearInterval() {},
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    cancelAnimationFrame() {},
    getComputedStyle() {
      return {
        display: 'block', visibility: 'visible', opacity: '1', pointerEvents: 'auto',
        transform: 'none', objectFit: 'contain', objectPosition: '50% 50%',
        position: 'static', overflow: 'visible', width: '100px', height: '100px',
        getPropertyValue() { return ''; },
      };
    },
    addEventListener(type, fn) {
      if (!windowListeners.has(type)) windowListeners.set(type, []);
      windowListeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const list = windowListeners.get(type) || [];
      const index = list.indexOf(fn);
      if (index >= 0) list.splice(index, 1);
    },
    dispatch(type, event) {
      (windowListeners.get(type) || []).slice().forEach((fn) => fn(event || { type }));
    },
    matchMedia() { return { matches: false, addEventListener() {}, addListener() {} }; },
  };
  doc.defaultView = win;
  win.window = win;

  const factory = new Function(
    'window', 'document', 'localStorage', 'sessionStorage', 'navigator', 'location',
    'fetch', 'Image', 'Audio', 'console', 'URLSearchParams', 'Date',
    'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval',
    'requestAnimationFrame', 'cancelAnimationFrame',
    pageSessionSource + '\n;' + regionSource + '\n;' + appSource
      + '\n;return { app: { PAGE_SESSION_ID: PAGE_SESSION_ID, boot: boot,'
      + ' saveAllDisplaySettings: saveAllDisplaySettings,'
      + ' setPassThroughEnabled: setPassThroughEnabled,'
      + ' applyPortraitFloat: applyPortraitFloat,'
      + ' nativeDisplayPreferences: nativeDisplayPreferences,'
      + ' passThroughStatus: passThroughStatus,'
      + ' passThroughMessage: passThroughMessage,'
      + ' commitVisibleDisplayValues: commitVisibleDisplayValues,'
      + ' toggleBubbleByTap: toggleBubbleByTap, hideBubble: hideBubble,'
      + ' renderHistory: renderHistory, loadHistory: loadHistory,'
      + ' applyMessageNav: applyMessageNav,'
      + ' state: state, el: el },'
      + ' region: window.SakuraTouchRegion, display: window.SakuraDisplayPreferences };'
  );

  // 合成后端：/api/state 与 /api/history 由 opts 提供；未提供的路径仍按离线拒绝。
  const historyState = { calls: 0, items: opts.history || [], lazy: !!opts.lazyHistory };
  const responses = opts.responses || {};
  const fetchStub = (url) => {
    const text = String(url || '');
    if (opts.fetchOverride) {
      const overridden = opts.fetchOverride(text);
      if (overridden) return overridden;
    }
    let payload = null;
    if (text.indexOf('/api/history') >= 0 && (opts.history || opts.lazyHistory)) {
      historyState.calls += 1;
      const empty = opts.lazyHistory && historyState.calls === 1;
      payload = { history: empty ? [] : historyState.items };
    } else {
      const keys = Object.keys(responses);
      for (let i = 0; i < keys.length; i += 1) {
        if (text.indexOf(keys[i]) >= 0) { payload = responses[keys[i]]; break; }
      }
    }
    if (payload === null) return Promise.reject(new Error('offline-test'));
    return Promise.resolve({ ok: true, status: 200, json: async () => payload });
  };

  const handles = factory(
    win, doc, win.localStorage, win.sessionStorage, win.navigator, win.location,
    fetchStub,
    function ImageStub() { return makeElement('img', doc); },
    function AudioStub() {
      return { preload: '', src: '', addEventListener() {}, pause() {},
        play() { return Promise.resolve(); }, load() {} };
    },
    console, URLSearchParams, Date,
    setTimeout, clearTimeout, () => 0, () => {}, (fn) => setTimeout(fn, 0), () => {}
  );

  return { win, doc, handles, bridge, store, historyState };
}

async function main() {
  /* ---------- 1) 浮动默认关闭 ---------- */
  {
    const bridge = makeBridge();
    const page = loadPage(bridge, { alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0) });
    await sleep(10);
    check('无偏好时上下浮动默认关闭', page.handles.app.state.portraitFloat === false,
      page.handles.app.state.portraitFloat);
    check('无偏好时 body 带 no-float', page.doc.body.classList.contains('no-float'),
      Array.from(page.doc.body.classList._set));
  }

  /* ---------- 2) 已保存的浮动偏好被保留 ---------- */
  {
    const bridge = makeBridge();
    const page = loadPage(bridge, {
      alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0),
      storage: { 'sakura.remote.portraitFloat': 'true' },
    });
    await sleep(10);
    check('旧浮动偏好不能恢复已移除的动画', page.handles.app.state.portraitFloat === false,
      page.handles.app.state.portraitFloat);
    check('旧偏好下仍保持静止', page.doc.body.classList.contains('no-float'),
      Array.from(page.doc.body.classList._set));
  }

  /* ---------- 3) App 主界面：开关可用、写原生偏好、不提交区域 ---------- */
  {
    const bridge = makeBridge({ supported: true });
    const page = loadPage(bridge, { search: '' });   // 非 overlay 页面 = App 主界面
    await sleep(10);
    const app = page.handles.app;
    check('App 主界面穿透开关不被禁用', app.el.passThroughToggle.disabled === false,
      app.el.passThroughToggle.disabled);
    check('App 主界面开关反映原生默认值', app.el.passThroughToggle.checked === true,
      app.el.passThroughToggle.checked);
    check('App 主界面不提交区域裁剪', bridge.setTouchRegionCalls === 0,
      bridge.setTouchRegionCalls);

    const before = bridge.prefWrites.length;
    const ok = app.setPassThroughEnabled(false);
    check('App 主界面关闭穿透成功', ok === true, ok);
    check('App 主界面写入原生偏好',
      bridge.prefWrites.length === before + 1
      && bridge.prefWrites[bridge.prefWrites.length - 1].passThrough === false,
      bridge.prefWrites);
    check('App 主界面关闭后开关同步', app.el.passThroughToggle.checked === false,
      app.el.passThroughToggle.checked);
    check('App 主界面始终不提交区域', bridge.setTouchRegionCalls === 0,
      bridge.setTouchRegionCalls);
  }

  /* ---------- 4) 保存按钮保存尚未 blur 的数字框 ---------- */
  {
    const bridge = makeBridge();
    const page = loadPage(bridge, {
      alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0),
      search: '',
    });
    await sleep(10);
    const app = page.handles.app;
    // 模拟「输入了数字但没点别处（没有 change/blur）」
    app.el.scaleNumber.value = '123';
    app.el.fontNumber.value = '150';
    // 开关用真实 change 事件（用户点开关时浏览器就是这样触发的）
    app.el.passThroughToggle.disabled = false;
    app.el.passThroughToggle.checked = false;
    app.el.passThroughToggle.dispatch('change');
    app.saveAllDisplaySettings();
    check('未 blur 的字号被保存', page.win.localStorage.getItem('sakura.remote.fontScale') === '150',
      page.win.localStorage.getItem('sakura.remote.fontScale'));
    check('未 blur 的角色大小被保存',
      Number(page.win.localStorage.getItem('sakura.remote.portraitScale')) === 1.23,
      page.win.localStorage.getItem('sakura.remote.portraitScale'));
    check('保存不能恢复旧浮动动画',
      page.win.localStorage.getItem('sakura.remote.portraitFloat') === 'false',
      page.win.localStorage.getItem('sakura.remote.portraitFloat'));
    check('穿透偏好仍同步到原生',
      bridge.prefWrites.some((item) => item.passThrough === false),
      bridge.prefWrites);
    check('保存成功提示区分本机与桌面立绘偏好',
      app.el.saveState.textContent.indexOf('桌面立绘偏好') >= 0, app.el.saveState.textContent);
  }

  /* ---------- 5) 本机存储失败如实反馈 ---------- */
  {
    const bridge = makeBridge();
    const page = loadPage(bridge, {
      alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0),
      search: '',
    });
    await sleep(10);
    const app = page.handles.app;
    const originalSet = page.win.localStorage.setItem;
    page.win.localStorage.setItem = (key, value) => {
      if (key === 'sakura.remote.fontScale') throw new Error('quota');
      originalSet(key, value);
    };
    app.saveAllDisplaySettings();
    check('本机存储失败提示真实原因',
      app.el.saveState.textContent.indexOf('保存失败') >= 0
      && app.el.saveState.textContent.indexOf('本机存储不可用') >= 0,
      app.el.saveState.textContent);
  }

  /* ---------- 6) 原生回读不一致如实反馈 ---------- */
  {
    const bridge = makeBridge();
    const page = loadPage(bridge, {
      alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0),
      search: '',
    });
    await sleep(10);
    const app = page.handles.app;
    app.el.passThroughToggle.checked = false;
    app.el.passThroughToggle.dispatch('change');
    bridge.mismatchPass = true;
    app.saveAllDisplaySettings();
    check('原生回读不一致提示保存失败',
      app.el.saveState.textContent.indexOf('桌面立绘偏好保存失败') >= 0,
      app.el.saveState.textContent);
  }

  /* ---------- 6b) 停留旧状态的页面不得覆盖已保存的关闭偏好 ---------- */
  {
    const bridge = makeBridge({ prefs: { has: true, value: false } });   // 原生已保存「关」
    const page = loadPage(bridge, {
      alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0),
      search: '',
    });
    await sleep(10);
    const app = page.handles.app;
    check('页面初始反映已保存的关闭状态', app.el.passThroughToggle.checked === false,
      app.el.passThroughToggle.checked);
    // 旧页面残留的勾选状态（没有 change 事件 = 用户没改过）
    app.el.passThroughToggle.checked = true;
    const writesBefore = bridge.prefWrites.length;
    app.saveAllDisplaySettings();
    check('旧页面保存不回写穿透（不覆盖关闭偏好）',
      bridge.prefWrites.length === writesBefore
      || !bridge.prefWrites.slice(writesBefore).some((item) => item.passThrough === true),
      bridge.prefWrites);
    check('旧页面保存后开关被原生值纠正', app.el.passThroughToggle.checked === false,
      app.el.passThroughToggle.checked);
  }

  /* ---------- 7) 原生推送 apply：应用且不回写原生 ---------- */
  {
    const bridge = makeBridge();
    const page = loadPage(bridge, { alphaFn: (x) => ((x >= 10 && x < 20) ? 255 : 0) });
    await sleep(10);
    const app = page.handles.app;
    const before = bridge.setPrefCalls;
    const applied = page.handles.display.apply({ float: true, passThrough: false });
    check('SakuraDisplayPreferences.apply 返回已应用', applied === true, applied);
    check('旧原生浮动推送不能恢复动画', app.state.portraitFloat === false, app.state.portraitFloat);
    check('推送的穿透偏好被应用',
      app.passThroughStatus() && app.passThroughStatus().desired === false,
      app.passThroughStatus() && app.passThroughStatus().desired);
    check('推送落地不回写原生（无回环）', bridge.setPrefCalls === before,
      { before, after: bridge.setPrefCalls });
  }

  /* Portrait restore must render once, and a late response must not reopen it. */
  {
    let releaseHistory;
    let defer = false;
    const page = loadPage(makeBridge({ prefs: { has: true, value: false } }), {
      history: [],
      alphaFn: () => 255,
      fetchOverride(url) {
        if (defer && url.includes('/api/history')) {
          return new Promise(resolve => { releaseHistory = resolve; });
        }
        return null;
      },
    });
    await sleep(20);
    const app = page.handles.app;
    app.state.characterId = 'synthetic-role';
    app.renderHistory([]);
    defer = true;
    const opening = app.toggleBubbleByTap();
    await sleep(0);
    releaseHistory({ ok: true, status: 200, json: async () => ({history:[{role:'assistant',content:'Synthetic prior dialogue'}]}) });
    await opening;
    check('无本地历史首次点击恢复并保持显示',
      !app.el.msgNav.classList.contains('auto-hidden') && app.el.msgBody.textContent === 'Synthetic prior dialogue');
    app.hideBubble(true);
    await app.toggleBubbleByTap();
    check('点击其他处后重开保留上次对话', !app.el.msgNav.classList.contains('auto-hidden')
      && app.el.msgBody.textContent === 'Synthetic prior dialogue');
    app.renderHistory([]);
    const cancelled = app.toggleBubbleByTap();
    await sleep(0);
    app.hideBubble(true);
    releaseHistory({ ok: true, status: 200, json: async () => ({history:[{role:'assistant',content:'Late synthetic reply'}]}) });
    await cancelled;
    check('用户取消后迟到历史不恢复内容', app.el.bubbles.children.length === 0);
    app.renderHistory([{role:'assistant',content:'Previous reply'},{role:'assistant',content:''}]);
    check('空待输入占位不覆盖已有回复', app.el.msgBody.textContent === 'Previous reply');
    app.el.msgBody.scrollTop = 25;
    app.applyMessageNav({skipRelayout:true});
    check('相同对话布局刷新保留滚动位置', app.el.msgBody.scrollTop === 25);
    const touch = {clientX:50,clientY:50,screenX:50,screenY:50};
    const tap = () => {
      page.win.dispatch('pointerdown',{target:app.el.portrait,clientX:50,clientY:50});
      app.el.portraitWrap.dispatch('touchstart',{touches:[touch],preventDefault(){}});
      app.el.portraitWrap.dispatch('touchend',{touches:[],preventDefault(){}});
    };
    tap();
    check('实际 pointerdown→touchend 一次点击关闭对话', app.el.msgNav.classList.contains('auto-hidden'));
    tap();
    check('第二次真实点击打开并恢复上次对话', !app.el.msgNav.classList.contains('auto-hidden')
      && app.el.msgBody.textContent === 'Previous reply');
  }

  /* ---------- 10) 穿透提示文案改为「外轮廓」语义（TS-03） ---------- */
  {
    const bridge = makeBridge({ supported: true });
    const page = loadPage(bridge, { search: '?mode=overlay' });
    await sleep(10);
    const app = page.handles.app;
    const applied = app.passThroughMessage({ reason: 'applied' });
    // 精确断言旧承诺：以「已开启：透明处」开头 = 承诺所有透明处都穿透。
    check('已开启提示描述外轮廓（不再宣称透明处一律穿透）',
      applied.includes('外轮廓') && !applied.startsWith('已开启：透明处'), applied);
    const disabled = app.passThroughMessage({ reason: 'disabled' });
    check('未开启提示也描述外轮廓', disabled.includes('外轮廓'), disabled);
    // 旧文案必须彻底消失：它承诺「透明处」一律穿透，与授权的外轮廓语义矛盾。
    const keys = ['applied', 'unchanged', 'disabled'];
    const stale = keys.filter((key) => app.passThroughMessage({ reason: key })
      .startsWith('已开启：透明处'));
    check('旧「透明处一律穿透」文案已全部移除', stale.length === 0, stale);
  }

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
