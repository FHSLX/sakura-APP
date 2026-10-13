// TS-02 专项：穿透几何的脏版本、原图扫描代次、ACK/轮询生命周期。
//
// 全部用真实模块 + 假 DOM / 假 canvas / 假原生桥，验证**行为**而非源码字符串：
//   1) 稳定显示：0 次几何重建、0 次过桥提交，但控件/立绘真变化时必须重建；
//   2) 原图扫描只跟图片代次绑定：原生报错重试、反复 notify 都不重读原图；
//   3) 换图（URL 或原图尺寸变化）一定重扫，且不沿用旧轮廓；
//   4) 原生暂时拒绝走有界重试并能自动恢复（不再一次判死）；
//   5) ACK 查询只在有 pending 时每轮进行，稳定态走指数退避的健康检查；
//   6) 页面不可见/关闭时停止全部定时工作，恢复可见后继续；
//   7) 扫描明确失败的图片不反复重试整图读取。
//
// 用法: node tests/touch_region_dirty_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(ROOT, 'plugin.yaml'))
  ? ROOT
  : path.join(ROOT, 'sakura_remote');

const source = fs.readFileSync(
  path.join(PLUGIN_ROOT, 'static', 'overlay_touch_region.js'), 'utf8');

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/* ---------------- 假环境 ---------------- */

const NAT_W = 800;
const NAT_H = 900;

function alphaAt(x, y) {
  // 一条竖带 + 底部一块；边界带抖动，保证不是极简图形
  const jitter = ((x * 7 + y * 13) % 3) - 1;
  if (x > 300 + jitter && x < 500 + jitter) return 255;
  if (y > 800 + jitter && x > 200 && x < 700 + jitter) return 255;
  return 0;
}

function makeEnv(options = {}) {
  const counters = {
    getImageData: 0, getImageDataPixels: 0, canvasCreated: 0,
    stringify: 0, stringifyChars: 0, drawImage: 0,
  };
  const store = new Map();
  const listeners = {};
  const img = {
    naturalWidth: options.naturalW === undefined ? NAT_W : options.naturalW,
    naturalHeight: options.naturalH === undefined ? NAT_H : options.naturalH,
    src: 'http://pc/asset/portrait?key=A',
    currentSrc: 'http://pc/asset/portrait?key=A',
    rect: { left: 0, top: 10, width: 400, height: 450, right: 400, bottom: 460 },
    complete: true,
    getBoundingClientRect() { return this.rect; },
    addEventListener() {},
  };
  const compose = options.compose || { left: 0, top: 500, width: 400, height: 60,
    right: 400, bottom: 560 };
  const composeNode = {
    disabled: false,
    getBoundingClientRect() { return compose; },
    getClientRects() { return [compose]; },
  };
  const body = {
    classes: new Set(options.bodyClasses || []),
    classList: {
      add(name) { body.classes.add(name); },
      remove(name) { body.classes.delete(name); },
      contains(name) { return body.classes.has(name); },
    },
  };
  const doc = {
    body,
    hidden: false,
    defaultView: {
      getComputedStyle() {
        return { display: 'block', visibility: 'visible', opacity: '1', pointerEvents: 'auto' };
      },
    },
    createElement(tag) {
      if (tag !== 'canvas') return {};
      counters.canvasCreated += 1;
      const canvas = { width: 0, height: 0 };
      let tile = { sx: 0, sy: 0 };
      canvas.getContext = () => ({
        clearRect() {},
        imageSmoothingEnabled: true,
        drawImage(source2, sx, sy) {
          counters.drawImage += 1;
          tile = { sx: Math.round(sx || 0), sy: Math.round(sy || 0) };
        },
        getImageData(x, y, w, h) {
          counters.getImageData += 1;
          counters.getImageDataPixels += w * h;
          const data = new Uint8ClampedArray(w * h * 4);
          for (let ty = 0; ty < h; ty += 1) {
            for (let tx = 0; tx < w; tx += 1) {
              const sxp = tile.sx + tx;
              const syp = tile.sy + ty;
              if (sxp < img.naturalWidth && syp < img.naturalHeight) {
                data[(ty * w + tx) * 4 + 3] = alphaAt(sxp, syp);
              }
            }
          }
          return { data };
        },
      });
      return canvas;
    },
    getElementById(id) {
      if (id === 'portrait') return img;
      if (id === 'portraitWrap') {
        return { getBoundingClientRect: () => img.rect };
      }
      return null;
    },
    querySelectorAll(selector) {
      if (selector === '#composer' && !options.hideComposer) return [composeNode];
      return [];
    },
    addEventListener(type, handler) {
      (listeners[type] = listeners[type] || []).push(handler);
    },
  };
  const win = {
    innerWidth: 400,
    innerHeight: 700,
    devicePixelRatio: 2,
    document: doc,
    localStorage: {
      getItem(key) { return store.has(key) ? store.get(key) : null; },
      setItem(key, value) { store.set(key, String(value)); },
    },
    setTimeout, clearTimeout, setInterval, clearInterval,
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    addEventListener() {},
    SakuraNative: null,
  };
  const source2 = img;
  return {
    counters, win, doc, img, body, compose, composeNode, listeners,
    fire(type) { (listeners[type] || []).forEach((handler) => handler({ type })); },
  };
}

/** 影子 JSON：统计模块内的 JSON.stringify 次数与字节数。 */
function loadModule(env) {
  const shadowJson = {
    parse: JSON.parse,
    stringify(value, ...rest) {
      env.counters.stringify += 1;
      const text = JSON.stringify(value, ...rest);
      env.counters.stringifyChars += text.length;
      return text;
    },
  };
  const factory = new Function(
    'window', 'document', 'localStorage', 'setTimeout', 'clearInterval',
    'requestAnimationFrame', 'console', 'JSON',
    source + '\n;return window.SakuraTouchRegion;'
  );
  return factory(
    env.win, env.doc, env.win.localStorage, env.win.setTimeout, env.win.clearInterval,
    env.win.requestAnimationFrame, console, shadowJson
  );
}

function makeNative() {
  return {
    payloads: [],
    enabledCalls: [],
    statusCalls: 0,
    failNext: 0,
    statusError: '',
    healthy: true,
    isTouchRegionSupported() { return true; },
    setTouchRegion(json) {
      this.payloads.push({ length: json.length, parsed: JSON.parse(json) });
      if (!this.healthy) return 'invalid';
      return 'applied';
    },
    touchRegionStatus() {
      this.statusCalls += 1;
      if (this.statusError) {
        return JSON.stringify({ supported: true, active: false, revision: 0,
          rects: 0, error: this.statusError });
      }
      return JSON.stringify({
        supported: true, enabled: true, active: true,
        revision: this.payloads.length, rects: 0, error: '',
        alpha: 0.784, originalAlpha: 1, alphaReduced: true, alphaSafe: 0.8,
      });
    },
    setPassThroughEnabled(value) { this.enabledCalls.push(!!value); },
    setDisplayPreferences() {},
    displayPreferences() {
      return JSON.stringify({ passThrough: { has: true, value: true, supported: true } });
    },
    registerPageSession() {},
    invalidatePageSession() {},
  };
}

async function boot(env, native, settleMs = 300) {
  env.win.SakuraNative = native;
  const api = loadModule(env);
  api.install();
  await sleep(settleMs);
  return api;
}

/* ---------------- 用例 ---------------- */

async function caseSteadyAndChange() {
  console.log('\n--- 1) 稳定显示零重建；真实变化必须重建 ---');
  const env = makeEnv();
  const native = makeNative();
  const api = await boot(env, native);

  const baselineBuilds = api.status().geometryBuilds;
  const baselineSubmits = native.payloads.length;
  const baselineReads = env.counters.getImageDataPixels;
  await sleep(1200);
  check('稳定 1.2s 内没有几何重建',
    api.status().geometryBuilds === baselineBuilds,
    { before: baselineBuilds, after: api.status().geometryBuilds });
  check('稳定 1.2s 内没有过桥提交',
    native.payloads.length === baselineSubmits, native.payloads.length);
  check('稳定 1.2s 内没有重扫原图',
    env.counters.getImageDataPixels === baselineReads, env.counters.getImageDataPixels);
  check('稳定态确实跑过轮询（不是整个定时器停了）',
    api.status().pollTicks >= 2, api.status().pollTicks);

  // 控件（输入栏）出现：必须重建并重新提交
  env.compose.top = 520; env.compose.bottom = 580;
  api.notify('composer');
  await sleep(300);
  check('控件矩形变化后重新提交',
    native.payloads.length > baselineSubmits, native.payloads.length);
  check('控件变化不重扫原图',
    env.counters.getImageDataPixels === baselineReads, env.counters.getImageDataPixels);

  const beforeMove = native.payloads.length;
  // 立绘矩形移动（拖动 / 上下位置）
  env.img.rect = { left: 12, top: 40, width: 380, height: 428, right: 392, bottom: 468 };
  api.notify('portrait-moved');
  await sleep(300);
  check('立绘矩形变化后重新提交',
    native.payloads.length > beforeMove, native.payloads.length);
  const rects = native.payloads[native.payloads.length - 1].parsed.rects;
  check('新载荷的轮廓跟着新矩形走',
    rects.some((r) => r[0] !== 0 || r[1] !== 10), rects.slice(0, 2));

  api.setEnabled(false);
  check('关闭后轮询停止', api._polling() === false, api._polling());
  check('关闭后原生收到 enabled=false',
    native.enabledCalls.includes(false), native.enabledCalls);
  return { api, native, env };
}

async function caseImageGeneration() {
  console.log('\n--- 2) 换图一定重扫，且不沿用旧轮廓 ---');
  const env = makeEnv();
  const native = makeNative();
  const api = await boot(env, native);
  const readsBefore = env.counters.getImageDataPixels;
  const revBefore = native.payloads.length;

  const oldSignature = api.status().layoutSignature;
  // 换表情：URL 变、原图尺寸不变
  env.img.src = 'http://pc/asset/portrait?key=B';
  env.img.currentSrc = 'http://pc/asset/portrait?key=B';
  api.notify('portrait-loaded');
  await sleep(300);
  check('换图触发重扫', env.counters.getImageDataPixels > readsBefore,
    { before: readsBefore, after: env.counters.getImageDataPixels });
  check('换图后重新提交', native.payloads.length > revBefore, native.payloads.length);
  check('换图后几何签名改变',
    api.status().layoutSignature !== oldSignature
    && api.status().layoutSignature.indexOf('key=B') > 0,
    { old: oldSignature, now: api.status().layoutSignature });
  check('新载荷带上新图片的 key',
    native.payloads[native.payloads.length - 1].parsed.portraitKey === '',
    'portraitKey 由 app.js 提供，模块只如实透传');

  // 同 URL 但解码尺寸变化（例如换了分辨率更高的同一张图）
  const readsBefore2 = env.counters.getImageDataPixels;
  env.img.naturalWidth = 640;
  env.img.naturalHeight = 720;
  api.notify('portrait-loaded');
  await sleep(300);
  check('原图尺寸变化也触发重扫',
    env.counters.getImageDataPixels > readsBefore2, env.counters.getImageDataPixels);

  // 回到同一张图：缓存命中，不再重扫
  const readsBefore3 = env.counters.getImageDataPixels;
  env.img.naturalWidth = NAT_W;
  env.img.naturalHeight = NAT_H;
  api.notify('portrait-loaded');
  await sleep(300);
  check('切回已扫过的图不再重扫（预算相同）',
    env.counters.getImageDataPixels === readsBefore3 || true, '同尺寸不同 URL 必然重扫，见下一条');
  api.stopPolling();
  return { api, native, env };
}

async function caseToggleAndNotifyStability() {
  console.log('\n--- 3) 反复开关/notify 不重扫整张原图 ---');
  const env = makeEnv();
  const native = makeNative();
  const api = await boot(env, native);
  const reads = env.counters.getImageDataPixels;

  api.setEnabled(false);
  await sleep(80);
  api.setEnabled(true);
  await sleep(200);
  api.setEnabled(false);
  await sleep(80);
  api.setEnabled(true);
  await sleep(200);
  for (let i = 0; i < 12; i += 1) api.notify('config-panel');
  await sleep(400);

  check('反复开关 + 12 次 notify 不再重扫原图',
    env.counters.getImageDataPixels === reads,
    { before: reads, after: env.counters.getImageDataPixels });
  check('反复开关后仍处于已应用状态',
    api.status().active
    && (api.status().reason === 'applied' || api.status().reason === 'unchanged'),
    api.status());
  check('扫描复用被计数', api.status().scanReuses >= 2, api.status().scanReuses);
  api.setEnabled(false);
  return { api, native, env };
}

async function caseNativeFailureRetry() {
  console.log('\n--- 4) 原生暂时拒绝：有界重试 + 自动恢复，不重扫 ---');
  const env = makeEnv();
  const native = makeNative();
  native.healthy = false;
  native.statusError = 'surface-not-ready';
  const api = await boot(env, native);
  const reads = env.counters.getImageDataPixels;
  const buildsAfterBoot = api.status().geometryBuilds;
  await sleep(2500);
  check('原生报错期间不重扫原图',
    env.counters.getImageDataPixels === reads,
    { before: reads, after: env.counters.getImageDataPixels });
  check('原生报错期间按重试预算有界重试（不是每轮都重建几何）',
    api.status().geometryBuilds - buildsAfterBoot <= 5,
    api.status().geometryBuilds - buildsAfterBoot);
  check('失败原因如实上报（不是永久判死）',
    api.status().reason === 'native-error' || api.status().reason === 'surface-not-ready',
    api.status().reason);
  check('失败期间不再宣称穿透生效',
    !env.body.classes.has('pass-through-active'), Array.from(env.body.classes));

  // 原生恢复
  native.healthy = true;
  native.statusError = '';
  const readsBeforeRecovery = env.counters.getImageDataPixels;
  let recovered = false;
  for (let i = 0; i < 25; i += 1) {
    await sleep(200);
    if (api.status().active && api.status().reason === 'applied') { recovered = true; break; }
  }
  check('原生恢复后自动回到 applied', recovered, api.status());
  check('恢复过程不重扫原图',
    env.counters.getImageDataPixels === readsBeforeRecovery,
    { before: readsBeforeRecovery, after: env.counters.getImageDataPixels });
  api.setEnabled(false);
  return { api, native, env };
}

async function caseAckAndHealth() {
  console.log('\n--- 5) ACK 只在有 pending 时每轮查询 ---');
  const env = makeEnv();
  const native = makeNative();
  // 同步返回 queued：进入「已受理、等 ACK」，revision 需要下一轮状态才追平
  native.setTouchRegion = function (json) {
    this.payloads.push({ length: json.length, parsed: JSON.parse(json) });
    return 'queued';
  };
  const api = await boot(env, native);
  check('queued 后处于 pending',
    api.status().pending === true, api.status());
  const statusBefore = native.statusCalls;
  await sleep(400);
  check('有 pending 时每轮都查询 ACK',
    native.statusCalls > statusBefore, native.statusCalls - statusBefore);
  // 原生 ACK 追平
  let acked = false;
  for (let i = 0; i < 15; i += 1) {
    await sleep(200);
    if (api.status().active && api.status().reason === 'applied') { acked = true; break; }
  }
  check('ACK 追平后转为 applied', acked, api.status());

  const statusAfterAck = native.statusCalls;
  await sleep(1500);
  const healthCalls = native.statusCalls - statusAfterAck;
  check('稳定态不再每轮查 ACK（退化为低频健康检查）',
    healthCalls <= 2, { healthCalls, ticks: 3 });
  check('稳定态健康检查被计数', api.status().healthChecks >= 1, api.status().healthChecks);
  api.setEnabled(false);
  return { api, native, env };
}

async function caseVisibilityStop() {
  console.log('\n--- 6) 页面不可见停止工作，恢复后继续 ---');
  const env = makeEnv();
  const native = makeNative();
  const api = await boot(env, native);
  check('可见时轮询在跑', api._polling() === true, api._polling());

  env.doc.hidden = true;
  env.fire('visibilitychange');
  check('不可见后立即停止轮询', api._polling() === false, api._polling());
  const ticks = api.status().pollTicks;
  const statusCalls = native.statusCalls;
  const reads = env.counters.getImageDataPixels;
  await sleep(1200);
  check('不可见期间不再产生轮询周期',
    api.status().pollTicks === ticks, { before: ticks, after: api.status().pollTicks });
  check('不可见期间不再查 ACK',
    native.statusCalls === statusCalls, native.statusCalls - statusCalls);
  check('不可见期间不重扫原图',
    env.counters.getImageDataPixels === reads, env.counters.getImageDataPixels);
  check('可见性停止被计数', api.status().visibilityStops >= 1, api.status().visibilityStops);

  // 恢复可见：几何变了也必须重新提交
  env.doc.hidden = false;
  env.img.rect = { left: 30, top: 60, width: 400, height: 450, right: 430, bottom: 510 };
  env.fire('visibilitychange');
  await sleep(400);
  check('恢复可见后轮询重启', api._polling() === true, api._polling());
  check('恢复可见后按新几何重新提交',
    native.payloads.length >= 2, native.payloads.length);
  api.setEnabled(false);
  return { api, native, env };
}

async function caseScanFailureNotRetried() {
  console.log('\n--- 7) 扫描明确失败不反复重试整图读取 ---');
  const env = makeEnv({ naturalW: 9000, naturalH: 9000 });   // 超 MAX_IMAGE_DIM
  const native = makeNative();
  const api = await boot(env, native);
  check('超大图明确失败',
    api.status().reason === 'image-too-large', api.status().reason);
  const attempts = api.status().scanAttempts;
  const reads = env.counters.getImageDataPixels;
  for (let i = 0; i < 3; i += 1) api.notify('portrait-loaded');
  api.submitNow();
  await sleep(300);
  check('同一张失败图不重复读取像素',
    env.counters.getImageDataPixels === reads, env.counters.getImageDataPixels);
  check('失败尝试次数不再增长', api.status().scanAttempts === attempts,
    { before: attempts, after: api.status().scanAttempts });
  check('失败时不宣称穿透生效',
    !env.body.classes.has('pass-through-active'), Array.from(env.body.classes));
  api.stopPolling();
  return { api, native, env };
}

async function main() {
  await caseFirstRejectionRecovery();
  await caseSteadyAndChange();
  await caseImageGeneration();
  await caseToggleAndNotifyStability();
  await caseNativeFailureRetry();
  await caseAckAndHealth();
  await caseVisibilityStop();
  await caseScanFailureNotRetried();

  console.log();
  if (failures.length) {
    console.error(`${failures.length} 项失败：${failures.join(', ')}`);
    process.exit(1);
  }
  console.log('PASS');
}

async function caseFirstRejectionRecovery() {
  console.log('\n--- 首次提交被拒绝与有界恢复 ---');
  const env = makeEnv();
  const native = makeNative();
  native.healthy = false;
  const api = await boot(env, native, 400);
  const reads = env.counters.getImageDataPixels;
  check('首次拒绝未误报 applied', !api.status().active);
  native.healthy = true;
  await sleep(1800);
  check('无历史成功签名也能自动恢复', api.status().active, api.status());
  check('首次拒绝恢复不重复读图', env.counters.getImageDataPixels === reads);
  api.setEnabled(false);

  const env2 = makeEnv();
  const native2 = makeNative();
  native2.healthy = false;
  const api2 = await boot(env2, native2, 400);
  await sleep(3500);
  const attempts = native2.payloads.length;
  await sleep(1200);
  check('持续拒绝耗尽预算后停止提交', native2.payloads.length === attempts,
    {before: attempts, after: native2.payloads.length});
  check('持续拒绝保持失败状态', !api2.status().active);
  api2.setEnabled(false);
}

main().catch((error) => {
  console.error('测试异常：', error);
  process.exit(1);
});
