// 悬浮窗穿透性能的可复现测量（同一输入、真实模块、假 DOM + 假 canvas + 假原生桥）。
//
// 目的：不用「只查源码字符串」证明卡顿止损，而是对**固定输入**统计：
//   - 原图 alpha 扫描次数 / 读到的源像素数 / 每轮扫描耗时（getImageData）；
//   - 轮询周期里真正重建几何（buildPayload）与序列化（JSON.stringify）的次数与字节数；
//   - 过桥提交（setTouchRegion）次数；
//   - 稳定显示 3 秒内的「每秒重建次数」。
//
// 同一份脚本既能跑**当前源码**，也能跑 `--module <path>` 指定的历史版本（基线），
// 因此前后对比是同一输入、同一度量口径。
//
// 用法:
//   node tests/touch_region_perf_check.js                        # 跑当前模块
//   node tests/touch_region_perf_check.js --module backup/.../overlay_touch_region.js
//   node tests/touch_region_perf_check.js --json out.json        # 落盘（供报告引用）
//
// 退出码 0 = 观测成功（性能阈值由 --budget 决定，见下）。
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(ROOT, 'plugin.yaml'))
  ? ROOT
  : path.join(ROOT, 'sakura_remote');

function argValue(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) return fallback;
  return process.argv[index + 1];
}

const MODULE_PATH = path.resolve(argValue('--module',
  path.join(PLUGIN_ROOT, 'static', 'overlay_touch_region.js')));
const JSON_OUT = argValue('--json', '');
const STEADY_MS = Number(argValue('--steady-ms', '3000'));
const ROUNDS = Number(argValue('--rounds', '1'));
// 稳定显示期允许的最大「每秒几何重建次数」与「每秒过桥次数」。
const BUDGET_BUILDS_PER_SEC = Number(argValue('--budget-builds-per-sec', '0.5'));
const BUDGET_SUBMITS_PER_SEC = Number(argValue('--budget-submits-per-sec', '0.5'));
/** 只测量、不判定：用于跑历史基线（基线本来就该是不达标的）。 */
const REPORT_ONLY = process.argv.includes('--report-only');

const source = fs.readFileSync(MODULE_PATH, 'utf8');

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const now = () => Number(process.hrtime.bigint() / 1000n) / 1000;   // 毫秒

/* ------------------------------------------------------------------ *
 * 假环境：1024×2048 立绘，扫描需读 2 块 1024×1024 瓦片（≈2M 源像素）
 * ------------------------------------------------------------------ */

const IMG_W = 1316;
const IMG_H = 1376;
/** 真机上的显示尺寸（屏幕宽 − 边距）。 */
const VIEW_W = 1080;
const VIEW_H = 1130;

function makeImgState() {
  return {
    naturalWidth: IMG_W,
    naturalHeight: IMG_H,
    src: 'http://pc/asset/portrait?key=A020',
    currentSrc: 'http://pc/asset/portrait?key=A020',
    rect: { left: 0, top: 20, width: VIEW_W, height: VIEW_H,
      right: VIEW_W, bottom: 20 + VIEW_H },
    getBoundingClientRect() { return this.rect; },
  };
}

/*
 * 假立绘的 alpha：一个「宽裙 + 长发 + 披肩 + 底部肢体」的确定性轮廓。
 *
 * 复杂度按真实立绘的量级取（不是极简矩形）：alpha 边界带 1 像素抖动，
 * 因此扫描后每个 band 的行段都不完全相同，矩形数在**几百**这个量级，
 * 与真机上报的载荷规模同阶。全部由坐标算出，任何机器上结果一致。
 */
const EDGE_JITTER = (x, y) => ((x * 31 + y * 17) % 3) - 1;

function alphaAt(x, y) {
  const dx = x - IMG_W / 2;
  const jitter = EDGE_JITTER(x, y);
  // 头发：上宽下窄，中间留出脸的空隙
  const head = y < 760 && Math.abs(dx) < 260 + jitter * 3
    && !(y > 150 && y < 700 && Math.abs(dx + 40) < 120 + jitter);
  // 裙摆：逐行加宽的圆/椭圆
  const skirtTop = 980;
  const skirtBottom = 1920;
  const t = Math.max(0, Math.min(1, (y - skirtTop) / (skirtBottom - skirtTop)));
  const skirt = y >= skirtTop && y < skirtBottom
    && Math.abs(dx) < (140 + 260 * t) + jitter * 2;
  // 披肩：跨两侧的窄带
  const shawl = y >= 700 && y < 1000
    && (Math.abs(dx) > 120 + jitter) && Math.abs(dx) < 330 + jitter * 2;
  // 底部肢体
  const feet = y >= 1920 && x > 300 + jitter && x < 760 + jitter;
  return (head || skirt || shawl || feet) ? 255 : 0;
}

function makeCounters() {
  return {
    getImageData: 0,
    getImageDataPixels: 0,
    getImageDataMs: 0,
    drawImage: 0,
    canvasCreated: 0,
    stringify: 0,
    stringifyChars: 0,
    controlQueries: 0,
    rectQueries: 0,
  };
}

function makeEnv(counters, options = {}) {
  const store = new Map();
  const img = makeImgState();
  const wrapRect = { left: 0, top: 0, width: VIEW_W, height: VIEW_H,
    right: VIEW_W, bottom: VIEW_H };
  const docListeners = {};
  const body = {
    classes: new Set(),
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
      let tileX = 0;
      let tileY = 0;
      canvas.getContext = () => ({
        clearRect() {},
        imageSmoothingEnabled: true,
        drawImage(source, sx, sy) {
          counters.drawImage += 1;
          tileX = Math.round(sx || 0);
          tileY = Math.round(sy || 0);
        },
        getImageData(x, y, w, h) {
          const started = now();
          counters.getImageData += 1;
          counters.getImageDataPixels += w * h;
          const data = new Uint8ClampedArray(w * h * 4);
          for (let ty = 0; ty < h; ty += 1) {
            const sy = tileY + ty;
            for (let tx = 0; tx < w; tx += 1) {
              const sx = tileX + tx;
              data[(ty * w + tx) * 4 + 3] = alphaAt(sx, sy);
            }
          }
          counters.getImageDataMs += now() - started;
          return { data };
        },
      });
      return canvas;
    },
    getElementById(id) {
      if (id === 'portrait') return img;
      if (id === 'portraitWrap') {
        return { getBoundingClientRect() { counters.rectQueries += 1; return wrapRect; } };
      }
      return null;
    },
    querySelectorAll(selector) {
      counters.controlQueries += 1;
      if (selector === '#composer') {
        return [{
          disabled: false,
          getBoundingClientRect() {
            counters.rectQueries += 1;
            return { left: 0, top: VIEW_H, width: VIEW_W, height: 120,
              right: VIEW_W, bottom: VIEW_H + 120 };
          },
          getClientRects() { return [this.getBoundingClientRect()]; },
        }];
      }
      return [];
    },
    addEventListener(type, handler) {
      (docListeners[type] = docListeners[type] || []).push(handler);
    },
  };
  const fireDoc = (type) => {
    (docListeners[type] || []).forEach((handler) => handler({ type }));
  };
  const win = {
    innerWidth: 1080,
    innerHeight: 2280,
    devicePixelRatio: 2.75,
    document: doc,
    localStorage: {
      getItem(key) { return store.has(key) ? store.get(key) : null; },
      setItem(key, value) { store.set(key, String(value)); },
    },
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    addEventListener() {},
    SakuraNative: null,
  };
  return { win, doc, img, body, wrapRect, fireDoc };
}

/**
 * 用**影子 JSON** 载入模块：模块内所有 JSON.stringify 都过计数器，
 * 从而能如实统计「每轮是否重建了几何 + 序列化了多少字节」。
 */
function loadModule(env, counters) {
  const shadowJson = {
    parse: JSON.parse,
    stringify(value, ...rest) {
      counters.stringify += 1;
      const text = JSON.stringify(value, ...rest);
      counters.stringifyChars += text.length;
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

function makeNative(mode, counters) {
  return {
    mode,
    payloads: [],
    enabledCalls: [],
    statusCalls: 0,
    isTouchRegionSupported() { return this.mode !== 'unsupported'; },
    setTouchRegion(json) {
      this.payloads.push({ length: json.length, parsed: JSON.parse(json) });
      if (this.mode === 'bridge-error') return 'invalid';
      return 'applied';
    },
    // 'missing-ack' 故意不提供 touchRegionStatus：模拟「提交被受理但拿不到 ACK」
    setPassThroughEnabled(value) { this.enabledCalls.push(!!value); },
  };
}

/** 正常原生：ACK 追平 revision，状态查询计入 statusCalls。 */
function attachHealthyStatus(native) {
  native.touchRegionStatus = function () {
    this.statusCalls += 1;
    return JSON.stringify({
      supported: true, enabled: true, active: true,
      revision: this.payloads.length ? this.payloads.length : 0,
      rects: this.payloads.length
        ? this.payloads[this.payloads.length - 1].parsed.rects.length : 0,
      fallbackFull: false, error: '', alpha: 0.784, originalAlpha: 1,
      alphaReduced: true, alphaSafe: 0.8, restoreResult: 'dimmed',
      level: 0, tolerance: 0, prefsSync: 1,
    });
  };
}

/**
 * 原生侧持续报错（例如 surface 未就绪）：旧实现每轮都会清 scanCache 重扫整张原图，
 * 直到重试预算用尽。这里如实统计「重扫次数 / 重扫源像素 / 每秒重扫像素」。
 */
function attachFailingStatus(native) {
  native.touchRegionStatus = function () {
    this.statusCalls += 1;
    return JSON.stringify({ supported: true, enabled: true, active: true,
      revision: 0, rects: 0, fallbackFull: false, error: 'surface-not-ready' });
  };
  native.setTouchRegion = function (json) {
    this.payloads.push({ length: json.length, parsed: JSON.parse(json) });
    return 'invalid';
  };
}

/** 安装页面模块（走真实入口 install()，启动轮询与 visibilitychange 监听）。 */
async function boot(counters, env, native, settleMs = 400) {
  env.win.SakuraNative = native;
  const api = loadModule(env, counters);
  api.install();
  await sleep(settleMs);
  return api;
}

/* ------------------------------------------------------------------ *
 * 场景
 * ------------------------------------------------------------------ */

async function scenarioSteady(nativeMode) {
  const counters = makeCounters();
  const env = makeEnv(counters);
  const native = makeNative(nativeMode, counters);
  if (nativeMode === 'missing-ack') attachFailingStatus(native);
  else attachHealthyStatus(native);
  const api = await boot(counters, env, native);
  check(`${nativeMode}: 首次提交已过桥`, native.payloads.length >= 1, native.payloads.length);
  const afterFirst = { ...counters };
  const payloadsAfterFirst = native.payloads.length;
  const statusAfterFirst = native.statusCalls;
  const t0 = now();
  await sleep(STEADY_MS);      // 稳定显示：几何完全不变
  const elapsedSec = (now() - t0) / 1000;
  const steady = {
    seconds: Number(elapsedSec.toFixed(3)),
    builds: counters.stringify - afterFirst.stringify,
    submits: native.payloads.length - payloadsAfterFirst,
    canvasCreated: counters.canvasCreated - afterFirst.canvasCreated,
    pixelReads: counters.getImageDataPixels - afterFirst.getImageDataPixels,
    statusCalls: native.statusCalls - statusAfterFirst,
    scans: api.status().scans,
    reason: api.status().reason,
    retryBudget: api.status().retryBudget,
  };
  const recovery = await measureRecovery(api, native, counters);
  api.setEnabled(false);
  api.stopPolling();
  return { counters, native, steady, recovery };
}

/**
 * 把原生从故障状态切回正常，观察模块多久恢复并完成一次有效 ACK。
 * 这一步同时验证「恢复过程不重扫原图」。
 */
async function measureRecovery(api, native, counters) {
  const readsBefore = counters.getImageDataPixels;
  const submitsBefore = native.payloads.length;
  attachHealthyStatus(native);
  native.setTouchRegion = function (json) {
    this.payloads.push({ length: json.length, parsed: JSON.parse(json) });
    return 'applied';
  };
  const started = now();
  let restoredMs = -1;
  const observed = [];
  for (let tick = 0; tick < 30; tick += 1) {
    await sleep(200);
    const st = api.status();
    if (tick === 0 || tick === 4 || tick === 12) {
      observed.push({ tick, active: st.active, reason: st.reason,
        pending: st.pending, retry: st.retryBudget, nativeDisabled: st.nativeDisabled });
    }
    if (st.active && st.reason === 'applied') {
      restoredMs = Number((now() - started).toFixed(0));
      break;
    }
  }
  return {
    restoredMs,
    observed,
    rescansDuringRecovery: counters.getImageDataPixels - readsBefore,
    submitsDuringRecovery: native.payloads.length - submitsBefore,
  };
}

async function scenarioHidden() {
  const counters = makeCounters();
  const env = makeEnv(counters);
  const native = makeNative('ok', counters);
  attachHealthyStatus(native);
  const api = await boot(counters, env, native);
  const before = { ...counters };
  const payloadsBefore = native.payloads.length;
  const statusBefore = native.statusCalls;
  env.doc.hidden = true;                       // 切到别的 App / 息屏
  env.fireDoc('visibilitychange');
  await sleep(1600);
  const hidden = {
    builds: counters.stringify - before.stringify,
    submits: native.payloads.length - payloadsBefore,
    pixelReads: counters.getImageDataPixels - before.getImageDataPixels,
    statusCalls: native.statusCalls - statusBefore,
    polling: typeof api._polling === 'function' ? !!api._polling() : null,
  };
  api.stopPolling();
  return { hidden };
}

async function scenarioGeometryChange() {
  const counters = makeCounters();
  const env = makeEnv(counters);
  const native = makeNative('ok', counters);
  attachHealthyStatus(native);
  const api = await boot(counters, env, native);
  const payloadsBefore = native.payloads.length;
  const readsBefore = counters.getImageDataPixels;
  // 拖动/换位置：立绘矩形移动 40px（几何变了，图片没变）
  env.img.rect = { left: 40, top: 60, width: VIEW_W, height: VIEW_H,
    right: 40 + VIEW_W, bottom: 60 + VIEW_H };
  await sleep(400);
  const changed = {
    resubmits: native.payloads.length - payloadsBefore,
    rescans: counters.getImageDataPixels - readsBefore,
  };
  // 同一位置再来一次（没有变化，应不再提交、不再重扫）
  const payloadsBefore2 = native.payloads.length;
  const readsBefore2 = counters.getImageDataPixels;
  await sleep(1400);
  changed.idleSubmits = native.payloads.length - payloadsBefore2;
  changed.idleRescans = counters.getImageDataPixels - readsBefore2;
  api.stopPolling();
  return { changed, native };
}

async function main() {
  console.log(`模块: ${path.relative(ROOT, MODULE_PATH)}`);
  console.log(`稳定窗口: ${STEADY_MS}ms  轮次: ${ROUNDS}`);
  console.log();

  const report = {
    module: path.relative(ROOT, MODULE_PATH).replace(/\\/g, '/'),
    moduleSha256: require('crypto').createHash('sha256')
      .update(source, 'utf8').digest('hex').toUpperCase(),
    steadyMs: STEADY_MS,
    rounds: ROUNDS,
    budget: { buildsPerSec: BUDGET_BUILDS_PER_SEC, submitsPerSec: BUDGET_SUBMITS_PER_SEC },
    runs: [],
  };

  for (let round = 1; round <= ROUNDS; round += 1) {
    const run = { round };

    // 1) 首次扫描成本（原图像素数 + 耗时）
    const first = await scenarioSteady('ok');
    const firstPayload = first.native.payloads.length
      ? first.native.payloads[first.native.payloads.length - 1] : null;
    run.firstScan = {
      canvasCreated: first.counters.canvasCreated,
      getImageDataCalls: first.counters.getImageData,
      sourcePixelsRead: first.counters.getImageDataPixels,
      getImageDataMs: Number(first.counters.getImageDataMs.toFixed(2)),
      payloadBytes: firstPayload ? firstPayload.length : 0,
      rects: firstPayload ? firstPayload.parsed.rects.length : 0,
      segments: firstPayload ? firstPayload.parsed.segments : 0,
      level: firstPayload ? firstPayload.parsed.level : 0,
      statusCalls: first.native.statusCalls,
    };

    // 2) 稳定显示：每秒几何重建 / 过桥 / 重扫
    run.steady = {
      seconds: first.steady.seconds,
      builds: first.steady.builds,
      buildsPerSec: Number((first.steady.builds / first.steady.seconds).toFixed(2)),
      submits: first.steady.submits,
      submitsPerSec: Number((first.steady.submits / first.steady.seconds).toFixed(2)),
      rescanPixelReads: first.steady.pixelReads,
      statusCalls: first.steady.statusCalls,
      statusCallsPerSec: Number((first.steady.statusCalls / first.steady.seconds).toFixed(2)),
    };

    // 3) 原生持续报错：旧实现每轮都清缓存重扫整张原图
    const failing = await scenarioSteady('missing-ack');
    run.nativeFailing = {
      seconds: failing.steady.seconds,
      rescanPixelReads: failing.steady.pixelReads,
      rescanPixelsPerSec: Number((failing.steady.pixelReads / failing.steady.seconds).toFixed(0)),
      canvasCreated: failing.steady.canvasCreated,
      builds: failing.steady.builds,
      submits: failing.steady.submits,
      statusCalls: failing.steady.statusCalls,
      recoveryRestoredMs: failing.recovery.restoredMs,
      recoveryRescans: failing.recovery.rescansDuringRecovery,
      recoveryObserved: failing.recovery.observed,
    };

    // 4) 几何变化一次 → 该有的重提交 + 不重扫；之后的静置不再提交
    const geom = await scenarioGeometryChange();
    run.geometryChange = geom.changed;

    // 5) 页面不可见后是否还有工作
    const hidden = await scenarioHidden();
    run.hidden = hidden.hidden;

    report.runs.push(run);
    console.log(`--- 第 ${round} 轮 ---`);
    console.log(JSON.stringify(run, null, 2));
    console.log();
  }

  // 判定（取最后一轮）
  const last = report.runs[report.runs.length - 1];
  if (!REPORT_ONLY) {
    check('首次扫描只读一次原图（源像素 ≤ 图面积）',
      last.firstScan.sourcePixelsRead <= IMG_W * IMG_H,
      last.firstScan.sourcePixelsRead);
    check('稳定显示不重扫原图',
      last.steady.rescanPixelReads === 0, last.steady.rescanPixelReads);
    check(`稳定显示每秒几何重建 ≤ ${BUDGET_BUILDS_PER_SEC}`,
      last.steady.buildsPerSec <= BUDGET_BUILDS_PER_SEC, last.steady.buildsPerSec);
    check(`稳定显示每秒过桥提交 ≤ ${BUDGET_SUBMITS_PER_SEC}`,
      last.steady.submitsPerSec <= BUDGET_SUBMITS_PER_SEC, last.steady.submitsPerSec);
    check('原生持续报错时不反复重扫整张原图',
      last.nativeFailing.rescanPixelsPerSec === 0, last.nativeFailing.rescanPixelsPerSec);
    check('原生恢复后能自动回到 applied 且不重扫原图',
      last.nativeFailing.recoveryRestoredMs >= 0
      && last.nativeFailing.recoveryRescans === 0, last.nativeFailing);
    check('几何变化后重新提交',
      last.geometryChange.resubmits >= 1, last.geometryChange.resubmits);
    check('几何变化不触发原图重扫',
      last.geometryChange.rescans === 0, last.geometryChange.rescans);
    check('几何稳定后不再重复提交',
      last.geometryChange.idleSubmits === 0, last.geometryChange.idleSubmits);
    check('页面不可见后停止轮询工作',
      last.hidden.submits === 0 && last.hidden.statusCalls === 0, last.hidden);
  } else {
    console.log('[REPORT-ONLY] 跳过阈值判定，仅记录数字（用于基线对比）');
  }

  if (JSON_OUT) {
    const target = path.resolve(JSON_OUT);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, JSON.stringify(report, null, 2), 'utf8');
    console.log(`已写出: ${path.relative(ROOT, target)}`);
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
