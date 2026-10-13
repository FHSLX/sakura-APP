// R2-3 专项：立绘异步竞态（latest-wins）。
//
// 用桩 Image / 桩 DOM 执行 app.js 里真实的 resolvePortraitKey + setPortrait，
// 验证：
//   1) 慢 A 后快 B：B 已显示时，A 迟到的 onload 不得把画面覆盖回旧图；
//   2) 加载失败保留旧图，且**不提前提交** currentPortraitKey；
//   3) 同一个 key 失败后允许重试，重试成功才提交 key；
//   4) 陈旧请求的 onerror 不影响当前状态。
//
// 用法: node tests/portrait_race_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml'))
  ? _root
  : path.join(_root, 'sakura_remote');

const source = fs.readFileSync(path.join(PLUGIN_ROOT, 'static', 'app.js'), 'utf8');

const start = source.indexOf('const TONE_STOPWORDS');
const end = source.indexOf('function applyTheme');
if (start < 0 || end < 0) {
  console.error('无法从 app.js 提取立绘加载逻辑');
  process.exit(1);
}
const snippet = source.slice(start, end);

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

/* ---------- 桩环境 ---------- */

function makeClassList() {
  const set = new Set();
  return {
    add(name) { set.add(name); },
    remove(name) { set.delete(name); },
    contains(name) { return set.has(name); },
    values() { return Array.from(set); },
  };
}

const createdImages = [];
class FakeImage {
  constructor() {
    this.onload = null;
    this.onerror = null;
    this._src = '';
    createdImages.push(this);
  }
  set src(value) { this._src = value; }
  get src() { return this._src; }
}

const state = {
  portraitByKey: new Map([['A', 'url-A'], ['B', 'url-B']]),
  portraitAlias: new Map([['A', 'url-A'], ['B', 'url-B']]),
  portraitOverrides: new Map([['toneA', 'A'], ['toneB', 'B']]),
  defaultPortraitKey: 'A',
  currentPortraitKey: '',
  portraitReady: false,
  portraitFailed: '',
};

const portraitEl = { src: '', classList: makeClassList() };
const el = { portrait: portraitEl };
const settingsWrites = [];
const touchNotices = [];
let relayouts = 0;

const windowStub = { __sakuraPortraitKey: '' };

const factory = new Function(
  'state', 'el', 'window', 'Image', 'requestAnimationFrame',
  'notifyTouchRegion', 'relayoutOverlay', 'Map',
  snippet + '\nreturn { setPortrait, resolvePortraitKey, getGeneration: () => portraitGeneration };'
);

const api = factory(
  state, el, windowStub, FakeImage,
  (fn) => fn(),
  (reason) => touchNotices.push(reason),
  () => { relayouts += 1; },
  Map
);

/* ---------- 1) 慢 A / 快 B ---------- */
api.setPortrait('toneA');           // 第 1 次请求：A
const imageA = createdImages[0];
api.setPortrait('toneB');           // 第 2 次请求：B（更新）
const imageB = createdImages[1];
check('两次请求各创建一个 Image', createdImages.length === 2, createdImages.length);
check('两图地址不同', imageA.src === 'url-A' && imageB.src === 'url-B',
  { a: imageA.src, b: imageB.src });

imageB.onload();                    // 快图先到
check('快图 onload 提交 src', el.portrait.src === 'url-B', el.portrait.src);
check('快图 onload 提交 key', state.currentPortraitKey === 'B', state.currentPortraitKey);
check('快图 onload 标记 ready', state.portraitReady === true, state.portraitReady);

imageA.onload();                    // 慢图迟到
check('陈旧 onload 不覆盖已显示的新图', el.portrait.src === 'url-B', el.portrait.src);
check('陈旧 onload 不覆盖 currentPortraitKey',
  state.currentPortraitKey === 'B', state.currentPortraitKey);

/* ---------- 2) 同 key 已成功时不重复加载 ---------- */
const before = createdImages.length;
api.setPortrait('toneB');
check('同一张已显示时不重复请求', createdImages.length === before, createdImages.length);

/* ---------- 3) 失败保留旧图、允许同 key 重试 ---------- */
state.portraitReady = false;        // 模拟需要重新加载同一 key
api.setPortrait('toneB');
const retryImage = createdImages[createdImages.length - 1];
check('同 key 可重新发起加载', createdImages.length === before + 1, createdImages.length);
retryImage.onerror();
check('失败时保留旧图可见', el.portrait.src === 'url-B', el.portrait.src);
check('失败时不提前提交 currentPortraitKey',
  state.currentPortraitKey === 'B', state.currentPortraitKey);
check('失败后 ready=false', state.portraitReady === false, state.portraitReady);
check('失败后记录 portraitFailed', state.portraitFailed === 'B', state.portraitFailed);
check('失败通知了穿透模块', touchNotices.includes('portrait-failed'), touchNotices);

/* 重试成功 */
api.setPortrait('toneB');
const retryOk = createdImages[createdImages.length - 1];
retryOk.onload();
check('重试成功后提交 key', state.currentPortraitKey === 'B' && state.portraitReady === true,
  { key: state.currentPortraitKey, ready: state.portraitReady });
check('成功后清掉 portraitFailed', state.portraitFailed === '', state.portraitFailed);
check('成功后通知穿透模块重算轮廓',
  touchNotices.includes('portrait-loaded'), touchNotices);
check('成功后触发一次窗口重排', relayouts > 0, relayouts);

/* ---------- 4) 陈旧 onerror 不污染状态 ---------- */
const staleImage = retryImage;      // 之前已经 error 过的陈旧请求
state.portraitReady = true;
const touchBefore = touchNotices.length;
staleImage.onerror();
check('陈旧 onerror 不改动 ready', state.portraitReady === true, state.portraitReady);
check('陈旧 onerror 不新增通知', touchNotices.length === touchBefore, touchNotices.length);

/* ---------- 5) __sakuraPortraitKey 供穿透载荷使用 ---------- */
check('成功加载后写入 window.__sakuraPortraitKey',
  windowStub.__sakuraPortraitKey === 'B', windowStub.__sakuraPortraitKey);

console.log();
if (failures.length) {
  console.error(`${failures.length} 项失败：${failures.join(', ')}`);
  process.exit(1);
}
console.log('PASS');
