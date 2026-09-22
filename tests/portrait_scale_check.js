// 验证「角色大小」缩放逻辑：applyPortraitScale 必须写到 #portraitWrap 的
// --pet-scale 上，并同步滑块、文案、localStorage 与原生桥接。
//
// 为什么单独测：手机上 WebView 息屏后会停止渲染，getComputedStyle 返回陈旧值，
// 靠真机测量不可靠。这里用桩 DOM 把逻辑钉死。
//
// 用法: node tests/portrait_scale_check.js
'use strict';

const fs = require('fs');
const path = require('path');
// Registry 投稿要求 plugin.yaml 在仓库根目录，早期在 sakura_remote/ 子目录。
// 两种布局都认，避免改了布局测试就跑不起来。
const fs0 = require('fs');
const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs0.existsSync(path.join(_root, 'plugin.yaml'))
  ? _root
  : path.join(_root, 'sakura_remote');


const source = fs.readFileSync(path.join(PLUGIN_ROOT, 'static', 'app.js'), 'utf8');

// 只截取缩放相关的三个函数。
// 注意 nativeBridge 夹在 applyPortraitScale 和 loadPortraitScale 之间，
// 所以要把 nativeBridge 也一起包含进来。
const start = source.indexOf('const PORTRAIT_SCALE_KEY');
const end = source.indexOf('function attachPinchZoom()');
if (start < 0 || end < 0) {
  console.error('无法从 app.js 提取缩放逻辑');
  process.exit(1);
}
const snippet = source.slice(start, end);

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}` + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

function makeEl(id) {
  const props = {};
  return {
    id: id,
    value: '',
    textContent: '',
    style: {
      setProperty(name, value) { props[name] = String(value); },
      getPropertyValue(name) { return props[name] || ''; },
      _props: props,
    },
    classList: { toggle() {}, add() {}, remove() {} },
  };
}

const wrap = makeEl('portraitWrap');
const portrait = makeEl('portrait');
const scaleRange = makeEl('scaleRange');
const scaleValue = makeEl('scaleValue');
const scaleValueInline = makeEl('scaleValueInline');

const store = new Map();
const nativeCalls = [];

const el = {
  portrait: portrait,
  portraitWrap: wrap,
  scaleRange: scaleRange,
  scaleValue: scaleValue,
  scaleValueInline: scaleValueInline,
};

const state = { portraitScale: 1.0 };

const document = {
  getElementById(id) {
    return id === 'portraitWrap' ? wrap : null;
  },
};

const localStorage = {
  getItem(key) { return store.has(key) ? store.get(key) : null; },
  setItem(key, value) { store.set(key, String(value)); },
};

const window = {
  SakuraNative: {
    setScale(value) { nativeCalls.push(value); },
    getScale() { return 1.25; },
  },
};

const fn = new Function(
  'el', 'state', 'document', 'localStorage', 'window', 'updateSaveState',
  snippet + '\nreturn { applyPortraitScale, clampPortraitScale, loadPortraitScale, PORTRAIT_SCALE_MIN, PORTRAIT_SCALE_MAX };'
);
// updateSaveState 是配置页底部保存栏的提示函数（改动后提示「已自动保存」）。
// 缩放逻辑会在持久化后调用它，桩里给个空实现即可 —— 这里不测保存栏。
const api = fn(el, state, document, localStorage, window, () => {});

// 1) 正常缩放写进 CSS 变量
api.applyPortraitScale(1.3);
check('写入 --pet-scale', wrap.style._props['--pet-scale'] === '1.300', wrap.style._props);
check('同步 state', state.portraitScale === 1.3, state.portraitScale);
check('同步滑块', scaleRange.value === '130', scaleRange.value);
check('同步文案', scaleValue.textContent === '130%', scaleValue.textContent);
check('持久化到 localStorage', localStorage.getItem('sakura.remote.portraitScale') === '1.3',
  localStorage.getItem('sakura.remote.portraitScale'));
check('同步给原生悬浮窗', nativeCalls[nativeCalls.length - 1] === 1.3, nativeCalls);

// 2) 上下限收敛
api.applyPortraitScale(9);
check('超过上限被收敛', state.portraitScale === api.PORTRAIT_SCALE_MAX, state.portraitScale);
api.applyPortraitScale(0.01);
check('低于下限被收敛', state.portraitScale === api.PORTRAIT_SCALE_MIN, state.portraitScale);

// 3) 非法输入回落默认值
api.applyPortraitScale('abc');
check('非法输入回落 1.0', state.portraitScale === 1.0, state.portraitScale);
api.applyPortraitScale(NaN);
check('NaN 回落 1.0', state.portraitScale === 1.0, state.portraitScale);

// 4) 读回已存的值（persist:false 不应再次写盘）
store.set('sakura.remote.portraitScale', '0.7');
const callsBefore = nativeCalls.length;
api.loadPortraitScale();
check('从 localStorage 读回缩放', state.portraitScale === 0.7, state.portraitScale);
check('loadPortraitScale 不回写 localStorage',
  localStorage.getItem('sakura.remote.portraitScale') === '0.7',
  localStorage.getItem('sakura.remote.portraitScale'));

// 5) 没有存过时，问原生要（桌面立绘那边可能改过）
store.clear();
api.loadPortraitScale();
check('无本地值时读原生缩放', state.portraitScale === 1.25, state.portraitScale);

// 6) CSS 必须让缩放落在外层容器，且动画只在内层 img 上
const css = fs.readFileSync(path.join(PLUGIN_ROOT, 'static', 'app.css'), 'utf8');
function ruleFor(selector) {
  const match = css.match(new RegExp(selector.replace(/[.#]/g, '\\$&') + '\\s*\\{([^}]*)\\}'));
  return match ? match[1] : '';
}
const wrapRule = ruleFor('#portraitWrap');
const portraitRule = ruleFor('#portrait');
check('#portraitWrap 使用 scale(var(--pet-scale))',
  /scale\(var\(--pet-scale/.test(wrapRule), wrapRule.slice(0, 120));
check('#portraitWrap 上了动画的 transform', /transform/.test(wrapRule) && !/animation/.test(wrapRule),
  { hasTransform: /transform/.test(wrapRule), hasAnimation: /animation/.test(wrapRule) });
check('#portrait 只做动画，不写 scale()',
  /animation/.test(portraitRule) && !/scale\(/.test(portraitRule),
  { hasAnimation: /animation/.test(portraitRule), hasScale: /scale\(/.test(portraitRule) });

console.log();
if (failures.length) {
  console.error(`${failures.length} 项失败：${failures.join(', ')}`);
  process.exit(1);
}
console.log('PASS');
