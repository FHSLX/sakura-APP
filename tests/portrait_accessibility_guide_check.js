'use strict';
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const source = fs.readFileSync('sakura_remote/static/app.js', 'utf8');
const start = source.indexOf('function renderPortraitAccessibilityGuide()');
const end = source.indexOf('function openConfig(open)', start);
assert(start >= 0 && end > start);
function element() {
  const classes = new Set(), listeners = {};
  return {classes, listeners, textContent: '', classList: {
    toggle(k, yes) { yes ? classes.add(k) : classes.delete(k); },
    add(k) { classes.add(k); }
  }, addEventListener(k, fn) { listeners[k] = fn; }};
}
const storage = new Map();
function page({connected = false, native = true} = {}) {
  const nodes = Object.fromEntries(['portraitAccessibilityGuide', 'portraitAccessibilitySettings',
    'portraitAccessibilityGuideText', 'portraitAccessibilityGuideDismiss'].map(k => [k, element()]));
  const visibility = {}, opened = [], calls = [];
  const bridge = {portraitAccessibilityConnected: () => connected,
    requestPortraitAccessibilitySettings: () => calls.push('settings')};
  const context = {window: {SakuraNative: native ? bridge : null},
    document: {hidden: false, getElementById: k => nodes[k],
      addEventListener: (k, fn) => visibility[k] = fn},
    localStorage: {getItem: k => storage.get(k), setItem: (k, v) => storage.set(k, v)},
    passThroughSupported: () => true, openConfig: x => opened.push(x), setStatus() {}};
  vm.createContext(context); vm.runInContext(source.slice(start, end), context);
  context.installPortraitAccessibilityGuide();
  return {context, nodes, opened, calls, visibility, setConnected: x => connected = x};
}
const first = page();
assert.deepStrictEqual(first.opened, [true]);
assert(first.nodes.portraitAccessibilityGuideText.textContent.includes('安装 App 不会自动开启'));
first.nodes.portraitAccessibilitySettings.listeners.click();
assert.deepStrictEqual(first.calls, ['settings']);
assert.strictEqual(storage.size, 0, 'opening settings must not acknowledge the reminder');
first.nodes.portraitAccessibilityGuideDismiss.listeners.click();
assert.deepStrictEqual(first.opened, [true, false]);
const again = page();
assert.deepStrictEqual(again.opened, [], 'acknowledged reminder must not reopen settings');
assert(again.nodes.portraitAccessibilityGuideDismiss.classes.has('hidden'));
again.setConnected(true); again.visibility.visibilitychange();
assert(again.nodes.portraitAccessibilityGuideText.textContent.includes('已连接'));
assert(again.nodes.portraitAccessibilitySettings.classes.has('hidden'));
assert.deepStrictEqual(page({native: false}).opened, [], 'ordinary browsers get no Android prompt');
console.log('PASS: first use, explicit acknowledgement, persistence, settings handoff, connected status, browser fallback');
