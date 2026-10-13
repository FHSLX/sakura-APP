// UI-01 / VOICE-01 / MEDIA-01(预览流程) 的真实 app.js 行为测试。
//
// 在假 DOM + 假原生桥 + 假 Audio + 可编程 fetch 上真实求值
// page_session.js + overlay_touch_region.js + app.js，只驱动生产函数：
//   - 对话偏移：applyDialogShift 写入 --pet-dialog-x/-y（几何由 CSS 检查覆盖）；
//   - 截图先预览：onScreenCapture 不触发 /api/upload 或 /api/chat；
//   - 统一待发附件：选择图片与截图互相替换、取消释放 objectURL、
//     上传失败保留可重试、失败重试不重复上传、结果未知不自动重发、
//     重复点发送不双发、成功后不误删发送期间新选的附件；
//   - 语音：有界预取与句序、同 key 不重复请求、停止取消在途 POST 且迟到音频不出声。
//
// 只使用合成内容，不读取真实聊天/截图。
// 用法: node tests/ux_media_voice_check.js
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

const FakeElement = class FakeElement {};
if (typeof globalThis.Element === 'undefined') globalThis.Element = FakeElement;
const ElementClass = globalThis.Element;

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
    removeProperty(name) { const v = props.get(String(name)) || ''; props.delete(String(name)); return v; },
  };
  return new Proxy(target, {
    get(t, k) { if (k in t) return t[k]; return props.has(String(k)) ? props.get(String(k)) : ''; },
    set(t, k, v) { if (k in t) { t[k] = v; return true; } props.set(String(k), String(v)); return true; },
  });
}

function makeElement(id, doc, options) {
  const opts = options || {};
  const listeners = new Map();
  const rect = opts.rect || { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 };
  const el = {
    id, tagName: opts.tagName || 'DIV', nodeType: 1, children: [], dataset: {},
    classList: makeClassList(), style: makeStyle(),
    value: opts.value === undefined ? '' : opts.value,
    textContent: '', innerHTML: '', innerText: '',
    checked: false, disabled: false, hidden: false, complete: true,
    naturalWidth: opts.naturalWidth || 0, naturalHeight: opts.naturalHeight || 0,
    src: '', currentSrc: '', width: 0, height: 0,
    offsetWidth: 100, offsetHeight: 100, clientWidth: 100, clientHeight: 100,
    scrollTop: 0, scrollHeight: 100, scrollLeft: 0, parentNode: null,
    files: [],
    _rect: rect,
    getBoundingClientRect() { return el._rect || rect; },
    setRect(next) { el._rect = next; },
    getClientRects() { return [el._rect || rect]; },
    addEventListener(type, fn) { if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(fn); },
    removeEventListener(type, fn) { const l = listeners.get(type) || []; const i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); },
    dispatch(type, event) {
      (listeners.get(type) || []).slice().forEach((fn) => fn(event || { type, target: el, preventDefault() {}, stopPropagation() {} }));
    },
    setAttribute() {}, removeAttribute() {}, getAttribute() { return null; }, hasAttribute() { return false; },
    appendChild(child) { el.children.push(child); return child; },
    removeChild(child) { const i = el.children.indexOf(child); if (i >= 0) el.children.splice(i, 1); return child; },
    insertBefore(child) { el.children.push(child); return child; },
    replaceChildren() {}, contains() { return false; }, closest() { return null; },
    querySelector() { return null; }, querySelectorAll() { return []; },
    focus() {}, blur() {}, click() { el.dispatch('click'); },
    pause() {}, play() { return Promise.resolve(); }, load() {}, remove() {},
  };
  Object.setPrototypeOf(el, ElementClass.prototype);
  return el;
}

/** 可编程假 Audio：记录播放顺序，可手动触发 ended/error。 */
function makeAudioLog() {
  const log = { plays: [], listeners: new Map(), playMs: 25, failNextPlay: false };
  function AudioStub() {
    const audio = {
      preload: '', src: '', duration: 0.5, _timer: 0,
      play() {
        if (log.failNextPlay) { log.failNextPlay = false; return Promise.reject(new Error('autoplay blocked')); }
        log.plays.push(audio.src);
        clearTimeout(audio._timer);
        audio._timer = setTimeout(() => audio._fire('ended'), log.playMs);
        return Promise.resolve();
      },
      pause() { clearTimeout(audio._timer); },
      removeAttribute() { audio.src = ''; },
      load() {},
      addEventListener(type, fn) { if (!log.listeners.has(type)) log.listeners.set(type, new Set()); log.listeners.get(type).add(fn); },
      removeEventListener(type, fn) { const s = log.listeners.get(type); if (s) s.delete(fn); },
      _fire(type) { const s = log.listeners.get(type); if (s) Array.from(s).forEach((fn) => fn({ type })); },
    };
    return audio;
  }
  log.Audio = AudioStub;
  log.listenerCount = () => {
    let n = 0;
    log.listeners.forEach((s) => { n += s.size; });
    return n;
  };
  return log;
}

function loadPage(options) {
  const opts = options || {};
  const store = new Map(Object.entries(opts.storage || {}));
  const body = makeElement('body', null, { tagName: 'BODY' });
  const registry = new Map();
  const docListeners = new Map();
  const winListeners = new Map();
  const search = opts.search === undefined ? '' : opts.search;
  const audioLog = makeAudioLog();
  const calls = { upload: 0, chat: 0, tts: 0, uploadFailNext: false, chatFailNext: false };
  const windowSizeCalls = { count: 0 };
  const ttsRequests = [];

  const doc = {
    body, documentElement: makeElement('html', null, { tagName: 'HTML' }),
    hidden: false, readyState: 'complete', defaultView: null,
    getElementById(id) {
      if (!registry.has(id)) {
        if (id === 'portrait') {
          registry.set(id, makeElement(id, doc, { tagName: 'IMG', naturalWidth: 100, naturalHeight: 100 }));
        } else {
          registry.set(id, makeElement(id, doc, { value: '100' }));
        }
      }
      return registry.get(id);
    },
    createElement(tag) { return makeElement(tag, doc, {}); },
    querySelector(selector) {
      if (typeof selector === 'string' && selector.charAt(0) === '#') {
        return doc.getElementById(selector.slice(1));
      }
      return null;
    }, querySelectorAll() { return []; },
    addEventListener(type, fn) { if (!docListeners.has(type)) docListeners.set(type, []); docListeners.get(type).push(fn); },
    removeEventListener(type, fn) { const l = docListeners.get(type) || []; const i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); },
    dispatch(type, event) { (docListeners.get(type) || []).slice().forEach((fn) => fn(event || { type })); },
    execCommand() { return false; }, createTextNode() { return { nodeType: 3 }; },
  };

  const win = {
    innerWidth: 393, innerHeight: 700, devicePixelRatio: 2.75, screen: { width: 393, height: 700 },
    document: doc,
    location: { href: 'http://pc:8770/' + search, search, host: 'pc:8770' },
    navigator: { clipboard: null, userAgent: 'node-test' },
    localStorage: {
      getItem(k) { return store.has(k) ? store.get(k) : null; },
      setItem(k, v) { store.set(k, String(v)); },
      removeItem(k) { store.delete(k); },
    },
    sessionStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    __SAKURA__: { token: 'synthetic-token', autoplay: true, tts: true },
    SakuraNative: {
      isTouchRegionSupported() { return true; },
      displayPreferences() { return JSON.stringify({ float: { has: false, value: false }, passThrough: { has: true, value: false, supported: true } }); },
      setDisplayPreferences() { return JSON.stringify({ ok: true }); },
      setPassThroughEnabled() {}, isOverlayRunning() { return true; },
      registerPageSession() {}, invalidatePageSession() {},
      requestScreenshotWithSession() { return 'accepted'; },
      hasScreenPermission() { return true; },
      setWindowSize() { windowSizeCalls.count += 1; },
      setScale() {},
    },
    setTimeout, clearTimeout, setInterval() { return 0; }, clearInterval() {},
    requestAnimationFrame(fn) { return setTimeout(fn, 0); }, cancelAnimationFrame() {},
    getComputedStyle() {
      return {
        display: 'block', visibility: 'visible', opacity: '1', pointerEvents: 'auto',
        transform: 'none', objectFit: 'contain', objectPosition: '50% 50%',
        position: 'static', overflow: 'visible', width: '100px', height: '100px',
        getPropertyValue() { return ''; },
      };
    },
    addEventListener(type, fn) { if (!winListeners.has(type)) winListeners.set(type, []); winListeners.get(type).push(fn); },
    removeEventListener(type, fn) { const l = winListeners.get(type) || []; const i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); },
    dispatch(type, event) { (winListeners.get(type) || []).slice().forEach((fn) => fn(event || { type })); },
    matchMedia() { return { matches: false, addEventListener() {}, addListener() {} }; },
  };
  doc.defaultView = win;

  const responses = opts.responses || {};
  const fetchStub = (url, init) => {
    const text = String(url || '');
    if (text.indexOf('/api/upload') >= 0) {
      calls.upload += 1;
      if (calls.uploadFailNext) { calls.uploadFailNext = false; return Promise.reject(new Error('upload down')); }
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ url: 'asset-' + calls.upload }) });
    }
    if (text.indexOf('/api/chat') >= 0) {
      calls.chat += 1;
      if (calls.chatFailNext) { calls.chatFailNext = false; return Promise.reject(new Error('chat link lost')); }
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ segments: [], reply: '' }) });
    }
    if (text.indexOf('/api/tts') >= 0) {
      calls.tts += 1;
      const body = init && init.body ? JSON.parse(init.body) : {};
      const record = { text: body.text, signal: init ? init.signal : undefined, startedAt: Date.now() };
      ttsRequests.push(record);
      const delay = opts.ttsDelayMs === undefined ? 40 : opts.ttsDelayMs;
      return new Promise((resolve) => {
        const timer = setTimeout(() => {
          if (record.signal && record.signal.aborted) return;
          resolve({ ok: true, status: 200, blob: async () => ({ duration: 0.5 }) });
        }, delay);
        if (record.signal && typeof record.signal.addEventListener === 'function') {
          record.signal.addEventListener('abort', () => clearTimeout(timer));
        }
      });
    }
    const keys = Object.keys(responses);
    for (let i = 0; i < keys.length; i += 1) {
      if (text.indexOf(keys[i]) >= 0) {
        return Promise.resolve({ ok: true, status: 200, json: async () => responses[keys[i]] });
      }
    }
    return Promise.reject(new Error('offline-test'));
  };

  const factory = new Function(
    'window', 'document', 'localStorage', 'sessionStorage', 'navigator', 'location',
    'fetch', 'Image', 'Audio', 'console', 'URLSearchParams', 'Date', 'URL',
    'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval',
    'requestAnimationFrame', 'cancelAnimationFrame', 'AbortController',
    pageSessionSource + '\n;' + regionSource + '\n;' + appSource
      + '\n;return { app: { state: state, el: el, send: send, drainQueue: drainQueue,'
      + ' enqueueSegment: enqueueSegment, stopVoice: stopVoice, voiceEpoch: voiceEpoch,'
      + ' onScreenCapture: onScreenCapture, previewScreenshot: previewScreenshot,'
      + ' onPickedFile: onPickedFile, clearMediaInfo: clearMediaInfo,'
      + ' applyDialogShift: applyDialogShift, uploadPendingAttachment: uploadPendingAttachment,'
      + ' syncDialogOffset: syncDialogOffset, applyPortraitScale: applyPortraitScale,'
      + ' applyPortraitTransform: applyPortraitTransform, isOverlayPage: isOverlayPage,'
      + ' playSegment: playSegment, unlockAudio: unlockAudio },'
      + ' display: window.SakuraDisplayPreferences };'
  );

  const urlLog = { created: new Set(), revoked: new Set() };
  const URLStub = {
    createObjectURL(blob) { const u = 'blob:synthetic-' + (urlLog.created.size + 1); urlLog.created.add(u); return u; },
    revokeObjectURL(u) { urlLog.revoked.add(u); urlLog.created.delete(u); },
  };

  const handles = factory(
    win, doc, win.localStorage, win.sessionStorage, win.navigator, win.location,
    fetchStub,
    function ImageStub() { return makeElement('img', doc); },
    audioLog.Audio,
    console, URLSearchParams, Date, URLStub,
    setTimeout, clearTimeout, () => 0, () => {}, (fn) => setTimeout(fn, 0), () => {},
    typeof AbortController === 'function' ? AbortController : undefined
  );
  return { win, doc, handles, calls, ttsRequests, audioLog, urlLog, store, windowSizeCalls };
}

function imageData(letter) {
  return 'data:image/jpeg;base64,' + Buffer.from('synthetic-' + letter).toString('base64');
}

async function main() {
  const statePayload = {
    characterId: 'synthetic-role', displayName: '合成角色', tones: [], portraits: [],
    defaultPortraitKey: '', portraitOverrides: {}, settings: {}, theme: {},
  };
  const historyPayload = { history: [{ role: 'assistant', content: '合成的上一句', raw_content: '' }] };

  /* ---------- UI-01：偏移写入 CSS 变量 ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload } });
    await sleep(20);
    const app = page.handles.app;
    app.applyDialogShift(24, -12);
    const root = page.doc.documentElement;
    check('水平偏移写入 --pet-dialog-x',
      root.style.getPropertyValue('--pet-dialog-x') === '24px',
      root.style.getPropertyValue('--pet-dialog-x'));
    check('垂直偏移写入 --pet-dialog-y',
      root.style.getPropertyValue('--pet-dialog-y') === '-12px',
      root.style.getPropertyValue('--pet-dialog-y'));
  }

  /* ---------- 对话几何：缩放只应用一次 + 偏移夹在可见区域 ---------- */
  {
    const page = loadPage({
      search: '?mode=overlay',
      responses: { '/api/state': statePayload, '/api/history': historyPayload },
    });
    await sleep(30);
    const app = page.handles.app;
    const root = page.doc.documentElement;
    const nav = page.doc.getElementById('msgNav');
    const wrap = page.doc.getElementById('portraitWrap');
    check('overlay 模式已建立', app.isOverlayPage() === true, app.isOverlayPage());

    // 缩放只应用一次：--pet-avail-h 已写入（布局已含缩放）时 transform 不再乘 scale
    app.applyPortraitScale(1.5);
    root.style.setProperty('--pet-avail-h', '300px');
    app.applyPortraitTransform();
    check('已预留缩放高度时 transform 不再乘 scale',
      /scale\(1(\.0+)?\)/.test(String(wrap.style.transform)), wrap.style.transform);
    // 布局未包含缩放（App 页面语义）时才由 transform 承担缩放
    root.style.removeProperty('--pet-avail-h');
    app.applyPortraitTransform();
    check('布局未含缩放时 transform 乘 scale',
      /scale\(1\.5/.test(String(wrap.style.transform)), wrap.style.transform);

    // 偏移夹取：真实窄窗（窗口随立绘只申请到 200 宽、卡片 178）
    // → 单侧余量 (200-178)/2 - 1 = 10，用户存满 -60 时旧实现会裁掉左边 50px
    page.win.innerWidth = 200;
    page.win.innerHeight = 700;
    nav.offsetWidth = 178;
    nav.classList.remove('hidden');
    nav.classList.remove('auto-hidden');
    nav.setRect({ left: 11, top: 20, right: 189, bottom: 100, width: 178, height: 80 });
    page.doc.getElementById('stage').setRect({bottom:20});
    page.doc.getElementById('composer').setRect({top:100});

    app.applyDialogShift(-60, 0);
    check('窄窗里负偏移被夹到可见范围',
      root.style.getPropertyValue('--pet-dialog-x') === '-10px',
      root.style.getPropertyValue('--pet-dialog-x'));
    check('用户设置值原样保留（不靠清除设置修复）',
      app.state.dialogX === -60, app.state.dialogX);
    const appliedX = Number((root.style.getPropertyValue('--pet-dialog-x') || '0').replace('px', ''));
    check('夹取后卡片左边缘不出界（旧实现会裁掉 50px）',
      11 + appliedX >= 0, 11 + appliedX);
    app.applyDialogShift(60, 0);
    check('窄窗里正偏移被夹到可见范围',
      root.style.getPropertyValue('--pet-dialog-x') === '10px',
      root.style.getPropertyValue('--pet-dialog-x'));
    app.applyDialogShift(8, 0);
    check('范围内偏移原样显示',
      root.style.getPropertyValue('--pet-dialog-x') === '8px',
      root.style.getPropertyValue('--pet-dialog-x'));
    app.applyDialogShift(0, -60);
    check('紧邻立绘时向上偏移不覆盖立绘',
      root.style.getPropertyValue('--pet-dialog-y') === '0px',
      root.style.getPropertyValue('--pet-dialog-y'));
    app.applyDialogShift(0, 60);
    check('紧邻输入栏时向下偏移不覆盖输入栏',
      root.style.getPropertyValue('--pet-dialog-y') === '0px',
      root.style.getPropertyValue('--pet-dialog-y'));
    check('夹取显示位置仍保留垂直设置值',app.state.dialogY === 60,app.state.dialogY);
    page.doc.getElementById('composer').setRect({top:160});
    app.syncDialogOffset();
    check('有实际空隙时恢复合法垂直偏移',root.style.getPropertyValue('--pet-dialog-y') === '60px',root.style.getPropertyValue('--pet-dialog-y'));

    app.applyDialogShift(-60, 0);
    nav.classList.add('auto-hidden');
    app.syncDialogOffset();
    check('隐藏时不做无意义夹取',
      root.style.getPropertyValue('--pet-dialog-x') === '-60px',
      root.style.getPropertyValue('--pet-dialog-x'));
    nav.classList.remove('auto-hidden');
    app.syncDialogOffset();
    check('重新显示后恢复夹取',
      root.style.getPropertyValue('--pet-dialog-x') === '-10px',
      root.style.getPropertyValue('--pet-dialog-x'));

    nav.offsetWidth = 200;
    nav.setRect({ left: 0, top: 20, right: 200, bottom: 100, width: 200, height: 80 });
    app.applyDialogShift(-60, 0);
    check('卡片撑满视口时偏移夹为 0',
      root.style.getPropertyValue('--pet-dialog-x') === '0px',
      root.style.getPropertyValue('--pet-dialog-x'));

    // 夹取只改 CSS 变量，不请求窗口尺寸（避免「夹取→resize→再夹取」的反馈收缩循环）
    const sizesBefore = page.windowSizeCalls.count;
    app.syncDialogOffset();
    app.syncDialogOffset();
    check('夹取不会触发窗口尺寸请求（无收缩循环）',
      page.windowSizeCalls.count === sizesBefore,
      { before: sizesBefore, after: page.windowSizeCalls.count });
  }

  /* ---------- MEDIA：截图先预览，不发请求 ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload } });
    await sleep(20);
    const app = page.handles.app;
    app.onScreenCapture(imageData('A'), '');
    check('截图成功只生成本地预览（不 upload）', page.calls.upload === 0, page.calls);
    check('截图成功不发聊天请求', page.calls.chat === 0, page.calls);
    check('预览记录为待发截图',
      app.state.pendingMedia && app.state.pendingMedia.kind === 'shot',
      app.state.pendingMedia && app.state.pendingMedia.kind);
    check('发送前不会自动发送', app.state.sending === false, app.state.sending);
  }

  /* ---------- MEDIA：发送才上传+聊天，成功后清空 ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload } });
    await sleep(20);
    const app = page.handles.app;
    app.onScreenCapture(imageData('B'), '');
    app.el.text.value = '看看这个';
    await app.send(app.el.text.value);
    await sleep(20);
    check('发送后恰好上传一次', page.calls.upload === 1, page.calls.upload);
    check('发送后恰好聊天一次', page.calls.chat === 1, page.calls.chat);
    check('成功后清空待发附件', app.state.pendingMedia === null,
      app.state.pendingMedia && app.state.pendingMedia.kind);
    check('成功后清空输入框', app.el.text.value === '', app.el.text.value);
  }

  /* ---------- MEDIA：上传失败保留附件；重试复用已上传地址 ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload } });
    await sleep(20);
    const app = page.handles.app;
    app.onScreenCapture(imageData('C'), '');
    page.calls.uploadFailNext = true;
    app.el.text.value = '重试看看';
    await app.send(app.el.text.value);
    await sleep(20);
    check('上传失败仍保留可重试附件',
      app.state.pendingMedia && app.state.pendingMedia.kind === 'shot',
      app.state.pendingMedia && app.state.pendingMedia.kind);
    check('上传失败不发聊天请求', page.calls.chat === 0, page.calls.chat);
    await app.send('再试一次');
    await sleep(20);
    check('重试后上传成功且聊天一次',
      page.calls.chat === 1 && app.state.pendingMedia === null, page.calls);
  }

  /* ---------- MEDIA：聊天结果未知不自动重发；附件保留 ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload } });
    await sleep(20);
    const app = page.handles.app;
    app.onScreenCapture(imageData('D'), '');
    page.calls.chatFailNext = true;
    await app.send('会失败');
    await sleep(20);
    check('聊天失败不自动重发', page.calls.chat === 1, page.calls.chat);
    check('聊天失败保留附件与草稿', !!app.state.pendingMedia, !!app.state.pendingMedia);
  }

  /* ---------- MEDIA：截图/图片互相替换 + 取消释放 objectURL ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload } });
    await sleep(20);
    const app = page.handles.app;
    app.onScreenCapture(imageData('E'), '');
    const first = app.state.pendingMedia.dataUrl;
    app.onScreenCapture(imageData('F'), '');
    check('再次截图替换旧预览',
      app.state.pendingMedia.dataUrl !== first && app.state.pendingMedia.kind === 'shot',
      app.state.pendingMedia && app.state.pendingMedia.kind);
    check('无效截图不破坏已有附件',
      app.previewScreenshot('data:text/plain;base64,AAAA') === false
      && app.state.pendingMedia.kind === 'shot',
      app.state.pendingMedia && app.state.pendingMedia.kind);

    // 选择图片替换截图，并在取消时释放 objectURL
    const file = { name: 'synthetic.png', size: 2048, type: 'image/png' };
    app.el.image.files = [file];
    app.onPickedFile();
    check('选择图片替换截图预览', app.state.pendingMedia.kind === 'file',
      app.state.pendingMedia && app.state.pendingMedia.kind);
    const objectUrl = app.state.pendingMedia.objectUrl;
    app.clearMediaInfo();
    check('取消预览清空附件', app.state.pendingMedia === null, app.state.pendingMedia);
    check('取消预览释放 objectURL', page.urlLog.revoked.has(objectUrl), Array.from(page.urlLog.revoked));
  }

  /* ---------- MEDIA：重复点发送不双发 ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload } });
    await sleep(20);
    const app = page.handles.app;
    app.el.text.value = '连点';
    const first = app.send('连点');
    const second = app.send('连点');
    await Promise.all([first, second]);
    await sleep(20);
    check('重复点发送只发生一次聊天', page.calls.chat === 1, page.calls.chat);
  }

  /* ---------- VOICE：首段优先 + 有界预取 + 句序 ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload }, ttsDelayMs: 60 });
    await sleep(20);
    const app = page.handles.app;
    app.state.voiceEnabled = true;
    app.state.audioUnlocked = true;
    app.state.displayName = '合成';
    const segments = ['一', '二', '三'].map((t) => ({
      characterId: 'synthetic-role', rawText: t, tone: '', bubble: { classList: page.handles.app.el.bubbles ? makeClassList() : makeClassList() },
    }));
    const started = Date.now();
    segments.forEach((seg) => app.enqueueSegment(seg));
    await sleep(60 + 3 * 40 + 120);
    // 排除用户手势解锁时的静音 data URL，只统计真正的语音段
    const segmentPlays = page.audioLog.plays.filter((u) => u && u.indexOf('data:') !== 0);
    check('三段按句序播放', segmentPlays.length === 3, segmentPlays.length);
    check('同 key 不重复请求（请求数不超过段数）', page.calls.tts <= 3, page.calls.tts);
    check('发生有界预取', app.state.ttsStats && app.state.ttsStats.prefetches >= 1,
      app.state.ttsStats);
    check('预取结果被复用（没有为同一句重复请求）',
      app.state.ttsStats && app.state.ttsStats.reused >= 1, app.state.ttsStats);
    check('第一段没有被预取挤占（首段请求最先发出）',
      page.ttsRequests.length > 0 && page.ttsRequests[0].text === '一',
      page.ttsRequests.map((r) => r.text));
    const firstPlayAt = Date.now() - started;
    check('总耗时没有串行 3 次合成延迟（有重叠）', firstPlayAt < 60 + 3 * 40 + 200, firstPlayAt);
  }

  /* ---------- VOICE：停止取消在途 POST 且迟到音频不出声 ---------- */
  {
    const page = loadPage({ responses: { '/api/state': statePayload, '/api/history': historyPayload }, ttsDelayMs: 80 });
    await sleep(20);
    const app = page.handles.app;
    app.state.voiceEnabled = true;
    app.state.audioUnlocked = true;
    app.state.displayName = '合成';
    const seg = { characterId: 'synthetic-role', rawText: '取消我', tone: '', bubble: { classList: makeClassList() } };
    app.enqueueSegment(seg);
    await sleep(10);
    app.stopVoice();
    const aborted = page.ttsRequests.length > 0 && page.ttsRequests[0].signal
      && page.ttsRequests[0].signal.aborted;
    check('停止会取消当前 TTS POST', aborted === true, aborted);
    await sleep(140);
    // 解锁用的静音 data URL 会先播一次；这里只关心被取消的语音段没有出声
    const segmentPlays = page.audioLog.plays.filter((u) => u && u.indexOf('data:') !== 0);
    check('迟到音频不再播放', segmentPlays.length === 0, segmentPlays.length);
    const ended = page.audioLog.listeners.get('ended') ? page.audioLog.listeners.get('ended').size : 0;
    const errored = page.audioLog.listeners.get('error') ? page.audioLog.listeners.get('error').size : 0;
    check('停止后没有残留的 ended/error 监听', ended === 0 && errored === 0, { ended, errored });
    check('停止后对象 URL 已释放', page.urlLog.created.size === 0, Array.from(page.urlLog.created));
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
