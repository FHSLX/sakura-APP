// 人物轻点坐标 + 输入焦点释放 + 拍照入口的自由行为测试。
//
// 在假 DOM / 假原生桥 / 假计时器上真实求值 page_session.js +
// overlay_touch_region.js + app.js，只驱动生产函数：
//   1) 轻点采样必须用 client 坐标（浮窗被移动后 screen 与 client 不再相等）；
//      拖动仍然用 screen 坐标；拖动/长按/多指都不算轻点；
//      同一次轻点只触发一次 onPortraitTap。
//   2) 点非编辑控件时释放输入焦点（focusout → 原生恢复不可聚焦）。
//   3) 「拍照」与「图库」两个 input 共用同一套「本地预览，点发送才上传」流程。
//
// 只使用合成内容，不读取真实聊天/照片。
// 用法: node tests/portrait_tap_camera_check.js
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

// 上传图片要走 FileReader → data URL；Node 没有，给一个合成实现（不读真实文件）
if (typeof globalThis.FileReader === 'undefined') {
  globalThis.FileReader = class {
    readAsDataURL(file) {
      const type = (file && file.type) || 'image/png';
      this.result = 'data:' + type + ';base64,'
        + Buffer.from('synthetic-image').toString('base64');
      if (typeof this.onload === 'function') this.onload();
    }
  };
}

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

/** 人物像素掩码：20..80 之间算不透明，其余透明。 */
function makeCanvasStub(el) {
  let px = -1;
  let py = -1;
  el.width = 0;
  el.height = 0;
  el.getContext = () => ({
    clearRect() {},
    drawImage(img, sx, sy) { px = sx; py = sy; },
    getImageData() {
      const opaque = px >= 20 && px < 80 && py >= 20 && py < 80;
      return { data: [0, 0, 0, opaque ? 255 : 0] };
    },
  });
  return el;
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
    isContentEditable: false,
    _rect: rect,
    getBoundingClientRect() { return el._rect || rect; },
    setRect(next) { el._rect = next; },
    getClientRects() { return [el._rect || rect]; },
    addEventListener(type, fn) { if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(fn); },
    removeEventListener(type, fn) { const l = listeners.get(type) || []; const i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); },
    dispatch(type, event) {
      (listeners.get(type) || []).slice().forEach((fn) => fn(event || {
        type, target: el, preventDefault() {}, stopPropagation() {},
      }));
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

/** 可控计时器：只在本测试需要时手动触发（例如 5 秒长按）。 */
function makeScheduler() {
  let seq = 0;
  const timers = new Map();
  return {
    setTimeout(fn, ms) { const id = ++seq; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
    pending(ms) { return Array.from(timers.values()).filter((t) => t.ms === ms).length; },
    fire(ms) {
      const hits = Array.from(timers.entries()).filter(([, t]) => t.ms === ms);
      hits.forEach(([id, t]) => {
        timers.delete(id);
        try { t.fn(); } catch (error) { /* 生产回调可能依赖真实 DOM，测试只关心状态位 */ }
      });
      return hits.length;
    },
  };
}

function loadPage(options) {
  const opts = options || {};
  const search = opts.search === undefined ? '' : opts.search;
  const scheduler = makeScheduler();
  const body = makeElement('body', null, { tagName: 'BODY' });
  const registry = new Map();
  const docListeners = new Map();
  const winListeners = new Map();
  const calls = { upload: 0, chat: 0, uploadFailNext: false, chatFailNext: false };
  const nativeLog = { keyboard: [], moveTo: [], moveBy: [], dragging: [] };

  const doc = {
    body, documentElement: makeElement('html', null, { tagName: 'HTML' }),
    hidden: false, readyState: 'complete', defaultView: null, activeElement: null,
    getElementById(id) {
      if (!registry.has(id)) {
        if (id === 'portrait') {
          registry.set(id, makeElement(id, doc, {
            tagName: 'IMG', naturalWidth: 100, naturalHeight: 100,
            rect: { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 },
          }));
        } else {
          registry.set(id, makeElement(id, doc, { value: '100' }));
        }
      }
      return registry.get(id);
    },
    createElement(tag) {
      const made = makeElement(tag, doc, {});
      if (String(tag).toLowerCase() === 'canvas') return makeCanvasStub(made);
      return made;
    },
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
    localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    sessionStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    __SAKURA__: { token: 'synthetic-token', autoplay: true, tts: true },
    SakuraNative: {
      isTouchRegionSupported() { return true; },
      displayPreferences() {
        return JSON.stringify({
          float: { has: false, value: false },
          passThrough: { has: true, value: false, supported: true },
        });
      },
      setDisplayPreferences() { return JSON.stringify({ ok: true }); },
      setPassThroughEnabled() {}, isOverlayRunning() { return true; },
      registerPageSession() {}, invalidatePageSession() {},
      requestScreenshotWithSession() { return 'accepted'; },
      hasScreenPermission() { return true; },
      setWindowSize() {}, setScale() {},
      setKeyboardOpen(open) { nativeLog.keyboard.push(!!open); },
      setDragging(value) { nativeLog.dragging.push(!!value); },
      moveTo(x, y) { nativeLog.moveTo.push([x, y]); },
      moveBy(x, y) { nativeLog.moveBy.push([x, y]); },
      overlayLayout() { return JSON.stringify({ x: 550, y: 1100, w: 800, h: 1080 }); },
    },
    setTimeout: scheduler.setTimeout, clearTimeout: scheduler.clearTimeout,
    setInterval() { return 0; }, clearInterval() {},
    requestAnimationFrame(fn) { return scheduler.setTimeout(fn, 16); }, cancelAnimationFrame() {},
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

  const fetchStub = (url) => {
    const text = String(url || '');
    if (text.indexOf('/api/upload') >= 0) {
      calls.upload += 1;
      if (calls.uploadFailNext) { calls.uploadFailNext = false; return Promise.reject(new Error('upload down')); }
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ url: 'asset-' + calls.upload }) });
    }
    if (text.indexOf('/api/chat') >= 0) {
      calls.chat += 1;
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ segments: [], reply: '' }) });
    }
    if (text.indexOf('/api/history') >= 0) {
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ history: [] }) });
    }
    if (text.indexOf('/api/state') >= 0) {
      return Promise.resolve({
        ok: true, status: 200,
        json: async () => ({ characterId: 'synthetic', displayName: '合成', tones: [], portraits: [] }),
      });
    }
    return Promise.reject(new Error('offline-test'));
  };

  const factory = new Function(
    'window', 'document', 'localStorage', 'sessionStorage', 'navigator', 'location',
    'fetch', 'Image', 'Audio', 'console', 'URLSearchParams', 'Date', 'URL',
    'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval',
    'requestAnimationFrame', 'cancelAnimationFrame', 'AbortController',
    pageSessionSource + '\n;' + regionSource + '\n;' + appSource
      + '\n;return { state: state, el: el,'
      + ' installOverlayGestures: installOverlayGestures, installClickThrough: installClickThrough,'
      + ' portraitAlphaAt: portraitAlphaAt, releaseOverlayInputFocus: releaseOverlayInputFocus,'
      + ' isEditableElement: isEditableElement, onPickedFile: onPickedFile,'
      + ' pendingAttachmentOf: pendingAttachmentOf, clearMediaInputs: clearMediaInputs,'
      + ' clearMediaInfo: clearMediaInfo, isOverlayPage: isOverlayPage, send: send };'
  );

  const URLStub = {
    createObjectURL() { return 'blob:synthetic'; },
    revokeObjectURL() {},
  };

  const handles = factory(
    win, doc, win.localStorage, win.sessionStorage, win.navigator, win.location,
    fetchStub,
    function ImageStub() { return makeElement('img', doc); },
    function AudioStub() {
      return {
        preload: '', src: '', play() { return Promise.resolve(); }, pause() {},
        addEventListener() {}, removeEventListener() {}, removeAttribute() {}, load() {},
      };
    },
    console, URLSearchParams, Date, URLStub,
    scheduler.setTimeout, scheduler.clearTimeout, () => 0, () => {},
    (fn) => scheduler.setTimeout(fn, 16), () => {},
    typeof AbortController === 'function' ? AbortController : undefined
  );
  return { win, doc, app: handles, calls, nativeLog, scheduler };
}

function touch(clientX, clientY, screenX, screenY) {
  return { clientX, clientY, screenX, screenY };
}

async function main() {
  /* ---------- 1. 轻点采样用 client 坐标（浮窗偏移后 screen≠client） ---------- */
  {
    const page = loadPage({});
    await sleep(10);
    const { app, doc, win } = page;
    const portrait = doc.getElementById('portrait');
    portrait.setRect({ left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 });
    portrait.naturalWidth = 100;
    portrait.naturalHeight = 100;

    check('人物像素不透明（client 坐标命中）',
      app.portraitAlphaAt(50, 50) === 255, app.portraitAlphaAt(50, 50));
    check('透明处采样为 0',
      app.portraitAlphaAt(5, 5) === 0, app.portraitAlphaAt(5, 5));
    check('窗口偏移后屏幕坐标偏出人物（旧缺陷的根因）',
      app.portraitAlphaAt(550, 560) === 0, app.portraitAlphaAt(550, 560));

    const taps = [];
    win.__petOnPortraitTap = (x, y) => { taps.push([x, y]); };
    app.installOverlayGestures();

    const wrap = doc.getElementById('portraitWrap');
    // 浮窗被拖到 (500,600)：screen 与 client 相差 (500,600)
    wrap.dispatch('touchstart', {
      touches: [touch(30, 40, 530, 560)],
      preventDefault() {},
    });
    wrap.dispatch('touchend', { touches: [], preventDefault() {} });
    check('轻点交付 client 坐标（不是 screen 坐标）',
      taps.length === 1 && taps[0][0] === 30 && taps[0][1] === 40, taps);
    check('同一次轻点只触发一次', taps.length === 1, taps.length);
  }

  /* ---------- 1a2. 轻点后的 alpha 命中判定（人物 vs 透明处） ---------- */
  {
    const page = loadPage({});
    await sleep(10);
    const { app, doc, win } = page;
    // installClickThrough 会把统一的轻点入口换成真实 handlePortraitTap
    app.installClickThrough();
    const handler = win.__petOnPortraitTap;
    check('已安装统一轻点入口', typeof handler === 'function', typeof handler);

    // 让它走 toggleBubbleByTap 的同步「取消恢复」分支，避免异步副作用
    function probe(x, y) {
      app.state.dialogueRestoreSeq = 0;
      app.state.dialogueRestorePending = 1;
      handler(x, y, null);
      return app.state.dialogueRestoreSeq;
    }
    check('透明处轻点不触发对话切换', probe(5, 5) === 0, app.state.dialogueRestoreSeq);
    check('人物处轻点触发一次对话切换', probe(50, 50) === 1, app.state.dialogueRestoreSeq);
  }

  /* ---------- 1b. 拖动仍用 screen 坐标，且拖动后不触发轻点 ---------- */
  {
    const page = loadPage({});
    await sleep(10);
    const { app, doc, win, nativeLog } = page;
    const taps = [];
    win.__petOnPortraitTap = (x, y) => { taps.push([x, y]); };
    app.installOverlayGestures();
    const wrap = doc.getElementById('portraitWrap');

    wrap.dispatch('touchstart', { touches: [touch(30, 40, 500, 600)], preventDefault() {} });
    wrap.dispatch('touchmove', { touches: [touch(60, 80, 560, 680)], preventDefault() {} });
    // dragOriginWin = (550/2.75, 1100/2.75) = (200, 400)
    check('拖动按 screen 位移绝对定位（client 只作参考）',
      nativeLog.moveTo.length === 1
      && nativeLog.moveTo[0][0] === 260 && nativeLog.moveTo[0][1] === 480,
      nativeLog.moveTo);
    check('拖动开始/结束通知原生', nativeLog.dragging.join(',') === 'true', nativeLog.dragging);
    wrap.dispatch('touchend', { touches: [], preventDefault() {} });
    check('拖动结束恢复原生夹取', nativeLog.dragging.join(',') === 'true,false', nativeLog.dragging);
    check('拖动不触发轻点', taps.length === 0, taps);
  }

  /* ---------- 1c. 长按开设置，不算轻点 ---------- */
  {
    const page = loadPage({});
    await sleep(10);
    const { app, doc, win, scheduler } = page;
    const taps = [];
    win.__petOnPortraitTap = (x, y) => { taps.push([x, y]); };
    app.installOverlayGestures();
    const wrap = doc.getElementById('portraitWrap');
    wrap.dispatch('touchstart', { touches: [touch(30, 40, 530, 560)], preventDefault() {} });
    check('按下后登记1500ms长按计时', scheduler.pending(1500) >= 1, scheduler.pending(1500));
    scheduler.fire(1500);
    wrap.dispatch('touchend', { touches: [], preventDefault() {} });
    check('长按抬手不算轻点', taps.length === 0, taps);
  }

  /* ---------- 1d. 多指起手不算轻点 ---------- */
  {
    const page = loadPage({});
    await sleep(10);
    const { app, doc, win } = page;
    const taps = [];
    win.__petOnPortraitTap = (x, y) => { taps.push([x, y]); };
    app.installOverlayGestures();
    const wrap = doc.getElementById('portraitWrap');
    wrap.dispatch('touchstart', {
      touches: [touch(30, 40, 530, 560), touch(80, 90, 580, 610)],
      preventDefault() {},
    });
    wrap.dispatch('touchend', { touches: [], preventDefault() {} });
    check('纯多指手势不触发轻点', taps.length === 0, taps);
  }

  /* ---------- 2. 点非编辑控件释放输入焦点 ---------- */
  {
    const page = loadPage({ search: '?mode=overlay' });
    await sleep(10);
    const { app, doc, win, nativeLog } = page;
    check('已进入 overlay 模式', app.isOverlayPage() === true, app.isOverlayPage());

    const text = doc.getElementById('text');
    text.tagName = 'TEXTAREA';
    const button = doc.getElementById('send');
    button.tagName = 'BUTTON';
    let blurred = 0;
    text.blur = () => { blurred += 1; };
    doc.activeElement = text;

    check('点按钮时释放输入焦点', app.releaseOverlayInputFocus(button) === true, blurred);
    check('释放时确实调用 blur', blurred === 1, blurred);
    check('点输入框自身不释放焦点', app.releaseOverlayInputFocus(text) === false, blurred);
    doc.activeElement = doc.body;
    check('焦点不在编辑控件时不做任何事',
      app.releaseOverlayInputFocus(button) === false, blurred);

    // 文档级 touchstart 监听：真实链路（capture 阶段）
    doc.activeElement = text;
    const before = blurred;
    doc.dispatch('touchstart', { target: button });
    check('touchstart 命中非编辑控件会释放焦点', blurred === before + 1, blurred);

    // 输入框 focus/blur 仍按原样通知原生
    nativeLog.keyboard.length = 0;
    text.dispatch('focus');
    text.dispatch('blur');
    check('输入框聚焦/失焦仍然通知原生键盘状态',
      nativeLog.keyboard.join(',') === 'true,false', nativeLog.keyboard);
    check('编辑控件判定正确',
      app.isEditableElement(text) === true && app.isEditableElement(button) === false,
      [app.isEditableElement(text), app.isEditableElement(button)]);
  }

  /* ---------- 3. 拍照入口复用「本地预览，点发送才上传」 ---------- */
  {
    const page = loadPage({});
    await sleep(10);
    const { app, doc, calls } = page;
    const camera = doc.getElementById('camera');
    const image = doc.getElementById('image');
    camera.tagName = 'INPUT';
    image.tagName = 'INPUT';
    const shot = { name: '', type: 'image/jpeg', size: 4096 };
    camera.files = [shot];

    app.onPickedFile(app.el.camera);
    check('拍照选完只生成本地预览（不 upload）', calls.upload === 0, calls);
    check('拍照选完不发送聊天', calls.chat === 0, calls);
    check('拍照预览进入统一待发附件',
      app.state.pendingMedia && app.state.pendingMedia.kind === 'file'
      && app.state.pendingMedia.file === shot,
      app.state.pendingMedia && app.state.pendingMedia.kind);
    check('待发附件能从拍照 input 找到',
      app.pendingAttachmentOf() && app.pendingAttachmentOf().file === shot,
      app.pendingAttachmentOf() && app.pendingAttachmentOf().name);
    check('相机空文件名有可读描述',
      app.state.pendingMedia.name.indexOf('已选图片') === 0, app.state.pendingMedia.name);

    // 非图片类型被拒绝，且不破坏已有附件
    const bad = { name: 'x.txt', type: 'text/plain', size: 10 };
    camera.files = [bad];
    app.onPickedFile(app.el.camera);
    check('非图片类型被拒绝且不破坏已有附件',
      app.state.pendingMedia && app.state.pendingMedia.file === shot,
      app.state.pendingMedia && app.state.pendingMedia.file === shot);

    // 图库选择替换拍照预览
    const galleryShot = { name: 'gallery.png', type: 'image/png', size: 2048 };
    camera.files = [];
    image.files = [galleryShot];
    app.onPickedFile(app.el.image);
    check('图库选择替换拍照预览',
      app.state.pendingMedia && app.state.pendingMedia.file === galleryShot,
      app.state.pendingMedia && app.state.pendingMedia.name);

    // 点发送才上传 + 聊天
    app.el.text.value = '看看这张';
    await app.send('看看这张');
    await sleep(20);
    check('点发送后恰好上传一次', calls.upload === 1, calls.upload);
    check('点发送后恰好聊天一次', calls.chat === 1, calls.chat);
    check('成功后清空待发附件', app.state.pendingMedia === null, app.state.pendingMedia);
    check('成功后清空两个 input 的值',
      camera.value === '' && image.value === '', [camera.value, image.value]);
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
