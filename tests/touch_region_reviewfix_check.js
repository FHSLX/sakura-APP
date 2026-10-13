// 真机穿透修正（TS-01 / TS-02）的前端行为测试。
//
// 与旧版（RV-03/RV-05）的区别：**复杂轮廓不再整窗回退**。本测试用假 DOM /
// 假 canvas / 假原生桥验证**真实行为**：
//   1) 复杂头发 + 多孤立段 + 内部孔洞 + 底部肢体：有界保守简化提交**完整覆盖**
//      不透明人物的区域，主要透明位置被释放，底部肢体不被截断；
//   2) 逐像素棋盘（病态最复杂图）也不会整窗回退，矩形数有硬上限；
//   3) 纯透明图仍安全回退到整窗可交互（不应用空 Region）；
//   4) 超大图明确失败并回报（不伪造轮廓）；
//   5) queued 与 native applied ACK 区分：拿到 ACK 前不写 lastSubmitted、不标记 active；
//   6) 原生报错后 desired 仍为 true，内容恢复时重新开启 native 并重试；
//   7) 页面会话在启用时登记、注销时上报；
//   8) mapRunsToCssEx 溢出显式上报（不会把半截 band 拉到底）。
//
// 用法: node tests/touch_region_reviewfix_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml'))
  ? _root
  : path.join(_root, 'sakura_remote');

const source = fs.readFileSync(
  path.join(PLUGIN_ROOT, 'static', 'overlay_touch_region.js'), 'utf8');

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/* ---------- 假环境 ---------- */

let alphaFn = () => 0;

/** 两条竖带，中间是内部透明洞（简单轮廓）。 */
function bandAlpha(x) {
  return ((x >= 10 && x < 20) || (x >= 80 && x < 90)) ? 255 : 0;
}

/**
 * 复杂但真实的立绘轮廓：
 *   - 顶部 13 条 3px 宽的发丝（多孤立段），
 *   - 底部 100px 宽的实体躯干/肢体（绝不能被截断），
 *   - 躯干内部一个 20×40 的透明孔洞（应被释放）。
 */
function complexPortraitAlpha(x, y) {
  if (y >= 140 && y < 290 && x >= 50 && x < 150) {
    if (x >= 90 && x < 110 && y >= 190 && y < 230) return 0;   // 孔洞
    return 255;
  }
  if (y >= 5 && y < 140 && (x % 16) < 3) return 255;           // 发丝
  return 0;
}

function makeImg(rect, natural) {
  const box = rect || { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 };
  const nat = natural || { width: 100, height: 100 };
  return {
    naturalWidth: nat.width,
    naturalHeight: nat.height,
    src: 'http://pc/asset/portrait?key=A',
    currentSrc: 'http://pc/asset/portrait?key=A',
    getBoundingClientRect() { return box; },
  };
}

function makeEnv(options = {}) {
  const store = new Map(Object.entries(options.storage || {}));
  const nat = options.natural || { width: 100, height: 100 };
  const img = makeImg(options.rect, nat);
  const wrapBox = { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 };
  const wrap = { getBoundingClientRect() { return wrapBox; } };
  const controls = options.controls || [];
  const body = {
    classes: new Set(options.bodyClasses || []),
    classList: {
      add(name) { body.classes.add(name); },
      remove(name) { body.classes.delete(name); },
      contains(name) { return body.classes.has(name); },
    },
  };
  const canvasFactory = () => {
    const canvas = { width: 0, height: 0 };
    canvas.getContext = () => ({
      clearRect() {},
      imageSmoothingEnabled: true,
      drawImage() {},
      getImageData() {
        const w = canvas.width;
        const h = canvas.height;
        const data = new Uint8ClampedArray(w * h * 4);
        for (let py = 0; py < h; py += 1) {
          for (let px = 0; px < w; px += 1) {
            const nx = Math.floor((px * nat.width) / w);
            const ny = Math.floor((py * nat.height) / h);
            data[(py * w + px) * 4 + 3] = alphaFn(nx, ny);
          }
        }
        return { data };
      },
    });
    return canvas;
  };
  const doc = {
    body,
    hidden: false,
    defaultView: {
      getComputedStyle() {
        return { display: 'block', visibility: 'visible', opacity: '1', pointerEvents: 'auto' };
      },
    },
    createElement(tag) { return tag === 'canvas' ? canvasFactory() : {}; },
    getElementById(id) {
      if (id === 'portrait') return img;
      if (id === 'portraitWrap') return wrap;
      return null;
    },
    querySelectorAll(selector) {
      return controls.filter((item) => item.selector === selector);
    },
    addEventListener() {},
  };
  const win = {
    innerWidth: 393,
    innerHeight: 700,
    devicePixelRatio: 2.75,
    document: doc,
    localStorage: {
      getItem(key) { return store.has(key) ? store.get(key) : null; },
      setItem(key, value) { store.set(key, String(value)); },
    },
    setTimeout,
    setInterval() { return 0; },
    clearInterval() {},
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    addEventListener() {},
    SakuraNative: null,
  };
  return { win, doc, img, body, controls };
}

function makeControl(selector, rect, options = {}) {
  const el = {
    selector,
    disabled: !!options.disabled,
    getBoundingClientRect() { return rect; },
    getClientRects() { return options.multi || [rect]; },
  };
  return el;
}

/**
 * 忠实模拟「按瓦片 1:1 读取原图」的假 canvas：
 * drawImage 记住源矩形，getImageData 返回该瓦片的源像素（不做任何缩放采样）。
 * 用它验证生产扫描器真的 OR 了源图像素，而不是先最近邻降采样。
 */
function makeTiledEnv(image, rect) {
  const naturalRect = rect || {
    left: 0, top: 0, width: image.width, height: image.height,
    right: image.width, bottom: image.height,
  };
  const img = {
    naturalWidth: image.width,
    naturalHeight: image.height,
    src: 'http://pc/asset/portrait?key=real',
    currentSrc: 'http://pc/asset/portrait?key=real',
    getBoundingClientRect() { return naturalRect; },
  };
  const store = new Map();
  const canvasFactory = () => {
    const canvas = { width: 0, height: 0 };
    let tile = { sx: 0, sy: 0 };
    canvas.getContext = () => ({
      clearRect() {},
      imageSmoothingEnabled: true,
      drawImage(source, sx, sy, sw, sh, dx, dy, dw, dh) {
        tile = { sx: Math.round(sx || 0), sy: Math.round(sy || 0) };
      },
      getImageData(x, y, w, h) {
        const data = new Uint8ClampedArray(w * h * 4);
        for (let ty = 0; ty < h; ty += 1) {
          for (let tx = 0; tx < w; tx += 1) {
            const sxp = tile.sx + tx;
            const syp = tile.sy + ty;
            if (sxp >= 0 && sxp < image.width && syp >= 0 && syp < image.height) {
              data[(ty * w + tx) * 4 + 3] = image.alpha[syp * image.width + sxp];
            }
          }
        }
        return { data };
      },
    });
    return canvas;
  };
  const body = {
    classes: new Set(),
    classList: {
      add() {}, remove() {}, contains() { return false; },
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
    createElement(tag) { return tag === 'canvas' ? canvasFactory() : {}; },
    getElementById(id) { return id === 'portrait' ? img : null; },
    querySelectorAll() { return []; },
    addEventListener() {},
  };
  const win = {
    innerWidth: 393,
    innerHeight: 700,
    devicePixelRatio: 2.75,
    document: doc,
    localStorage: {
      getItem(key) { return store.has(key) ? store.get(key) : null; },
      setItem(key, value) { store.set(key, String(value)); },
    },
    setTimeout,
    setInterval() { return 0; },
    clearInterval() {},
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    addEventListener() {},
    SakuraNative: null,
  };
  return { win, doc, img };
}

function sourceAlphaImage(width, height, opaqueFn) {
  const alpha = new Uint8Array(width * height);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      alpha[y * width + x] = opaqueFn(x, y) ? 255 : 0;
    }
  }
  return { width, height, alpha };
}

function loadModule(env) {
  const factory = new Function(
    'window', 'document', 'localStorage', 'setTimeout', 'setInterval', 'clearInterval',
    'requestAnimationFrame', 'console',
    source + '\n;return window.SakuraTouchRegion;'
  );
  return factory(
    env.win, env.doc, env.win.localStorage, env.win.setTimeout,
    env.win.setInterval, env.win.clearInterval, env.win.requestAnimationFrame, console
  );
}

function makeNative(options = {}) {
  return {
    supported: options.supported !== false,
    code: options.code || 'applied',
    error: options.error || '',
    appliedRevision: options.appliedRevision === undefined ? 0 : options.appliedRevision,
    payloads: [],
    enabledCalls: [],
    registerCalls: [],
    invalidateCalls: [],
    prefs: options.prefs || { has: false, value: true },
    prefWrites: [],
    isTouchRegionSupported() { return this.supported; },
    setTouchRegion(json) {
      const payload = JSON.parse(json);
      this.payloads.push(payload);
      if (this.code === 'applied') {
        this.appliedRevision = payload.revision;
      }
      return this.code;
    },
    setPassThroughEnabled(value) {
      this.enabledCalls.push(!!value);
      if (!value) this.appliedRevision = -1;
    },
    touchRegionStatus() {
      return JSON.stringify({
        supported: this.supported,
        enabled: true,
        revision: this.appliedRevision,
        rects: 0,
        fallbackFull: false,
        error: this.error,
      });
    },
    registerPageSession(sessionId) { this.registerCalls.push(sessionId); },
    invalidatePageSession(sessionId) { this.invalidateCalls.push(sessionId); },
    displayPreferences() {
      return JSON.stringify({ passThrough: { has: this.prefs.has, value: this.prefs.value } });
    },
    setDisplayPreferences(json) {
      const prefs = JSON.parse(json);
      this.prefWrites.push(prefs);
      if (typeof prefs.passThrough === 'boolean') {
        this.prefs = { has: true, value: prefs.passThrough };
      }
      return JSON.stringify({ ok: true });
    },
  };
}

/* ---------- 覆盖检查工具 ---------- */

/**
 * 独立参考：把**自然像素**按 scan 的**原网格 + block** 做 max-pooling，
 * 得到每个聚合格子的不透明性。与模块内部实现无关，用来核对覆盖。
 */
function referenceOpaqueCells(natural, scan) {
  const baseW = scan.baseWidth || scan.width;
  const baseH = scan.baseHeight || scan.height;
  const block = scan.block || 1;
  const cells = [];
  for (let gy = 0; gy < scan.height; gy += 1) {
    const by0 = gy * block;
    const by1 = Math.min((gy + 1) * block, baseH);
    const y0 = Math.floor((by0 * natural.height) / baseH);
    const y1 = Math.max(y0 + 1, Math.floor((by1 * natural.height) / baseH));
    for (let gx = 0; gx < scan.width; gx += 1) {
      const bx0 = gx * block;
      const bx1 = Math.min((gx + 1) * block, baseW);
      const x0 = Math.floor((bx0 * natural.width) / baseW);
      const x1 = Math.max(x0 + 1, Math.floor((bx1 * natural.width) / baseW));
      let opaque = false;
      for (let y = y0; y < y1 && !opaque; y += 1) {
        for (let x = x0; x < x1; x += 1) {
          if (alphaFn(x, y) >= 16) { opaque = true; break; }
        }
      }
      if (opaque) cells.push([gx, gy]);
    }
  }
  return cells;
}

function cellCenter(natural, scan, gx, gy) {
  const baseW = scan.baseWidth || scan.width;
  const baseH = scan.baseHeight || scan.height;
  const block = scan.block || 1;
  const bx = Math.min((gx + 0.5) * block, baseW);
  const by = Math.min((gy + 0.5) * block, baseH);
  return [(bx * natural.width) / baseW, (by * natural.height) / baseH];
}

function rectCoversPoint(rects, x, y) {
  return rects.some((r) => r[0] <= x && r[2] >= x && r[1] <= y && r[3] >= y);
}

async function main() {
  /* ---------- 1) 复杂头发/孤立段/孔洞/底部肢体：有界保守简化，不整窗回退 ---------- */
  {
    alphaFn = complexPortraitAlpha;
    const natural = { width: 200, height: 300 };
    // 1:1 映射：CSS 坐标 == 自然像素，便于逐格核对覆盖
    const env = makeEnv({
      natural,
      rect: { left: 0, top: 0, width: 200, height: 300, right: 200, bottom: 300 },
      controls: [
        makeControl('#composer',
          { left: 0, top: 305, width: 200, height: 40, right: 200, bottom: 345 }),
      ],
    });
    const native = makeNative({ code: 'applied' });
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    const ok = api.setEnabled(true);
    check('复杂立绘启用请求被接受', ok === true, ok);
    await sleep(300);
    const status = api.status();
    check('复杂立绘不再整窗回退（reason=applied）', status.reason === 'applied', status.reason);
    check('复杂立绘确实提交了轮廓', native.payloads.length === 1, native.payloads.length);
    check('复杂立绘不标记 contour-too-complex',
      status.reason !== 'contour-too-complex', status.reason);
    check('复杂立绘保持 active=true', status.active === true, status);
    check('复杂立绘加回 pass-through-active',
      env.body.classes.has('pass-through-active'), Array.from(env.body.classes));

    const payload = native.payloads[0];
    check('载荷带简化级别', typeof payload.level === 'number' && payload.level >= 1,
      payload.level);
    check('载荷带真实边缘容差', typeof payload.tolerance === 'number' && payload.tolerance > 0.5,
      payload.tolerance);
    check('载荷矩形数在上限内', payload.rects.length <= 1200, payload.rects.length);
    check('控件矩形被并入',
      payload.rects.some((r) => r[0] <= 0 && r[2] >= 200 && r[1] <= 305 && r[3] >= 345),
      payload.rects.length);

    // 用**模块最终实际使用的简化级别**重建参考网格，再按自然像素独立核对覆盖
    const grid = api._scanAlphaGrid(env.img);
    const chosen = api._rowsAtLevel(grid, payload.level);
    const refCells = referenceOpaqueCells(natural, chosen);
    let covered = 0;
    refCells.forEach(([gx, gy]) => {
      const [cx, cy] = cellCenter(natural, chosen, gx, gy);
      if (rectCoversPoint(payload.rects, cx, cy)) covered += 1;
    });
    check('全部不透明格子都被覆盖（不截断人物/肢体）',
      covered === refCells.length, { covered, total: refCells.length });
    check('底部肢体最低一行仍被覆盖',
      rectCoversPoint(payload.rects, 100, 289), 'bottom limb missing');

    // 主要透明位置被释放
    check('顶部空白被释放（y<5）',
      !rectCoversPoint(payload.rects, 130, 2), 'top margin captured');
    check('按用户要求，发缝不再拆成穿透孔洞',
      rectCoversPoint(payload.rects, 8, 60), 'hair gap unexpectedly passes through');
    check('按用户要求，轮廓内部孔洞保留在人物点击区域',
      rectCoversPoint(payload.rects, 100, 210), 'interior hole unexpectedly passes through');
    check('扫描统计含级别与容差',
      status.level >= 1 && status.tolerance > 0.5 && status.segments > 0, status);
  }

  /* ---------- 1b) 源图像素覆盖：细线/孤立点/底部末行（TS-01 复审） ---------- */
  {
    // 720×720：x=2 的单像素竖线、单个孤立点、最底行一个点
    const image = sourceAlphaImage(720, 720, (x, y) => {
      if (x === 2 && y >= 80 && y < 640) return true;
      if (x === 500 && y === 500) return true;
      if (x === 719 && y === 719) return true;
      return false;
    });
    const env = makeTiledEnv(image);
    const api = loadModule(env);
    const grid = api._scanAlphaGrid(env.img);
    check('源图网格宽度按上限取', grid && grid.width === 360 && grid.height === 360,
      grid && { w: grid.width, h: grid.height });
    const scan = api._scanAlpha(env.img, api._limits.maxRects);
    const rects = api._mapRunsToCss(env.img, scan);
    check('单像素竖线被保留（源像素覆盖）',
      rectCoversPoint(rects, 2.5, 300.5), 'thin line lost');
    check('孤立单像素点被保留',
      rectCoversPoint(rects, 500.5, 500.5), 'isolated pixel lost');
    check('底部最末源像素被保留且不越界',
      rectCoversPoint(rects, 719.5, 719.5)
      && rects.every((r) => r[0] >= -0.01 && r[2] <= 720.01 && r[1] >= -0.01 && r[3] <= 720.01),
      'bottom row lost or out of bounds');

    // 源像素逐个核对：所有 alpha>=16 的源像素都必须落在某个矩形内
    let missed = 0;
    for (let y = 0; y < image.height; y += 1) {
      for (let x = 0; x < image.width; x += 1) {
        if (image.alpha[y * image.width + x] < 16) continue;
        if (!rectCoversPoint(rects, x + 0.5, y + 0.5)) missed += 1;
      }
    }
    check('全部不透明源像素都被覆盖（含细线）', missed === 0, missed);
  }

  /* ---------- 1b-2) 非整除网格：源像素足迹 OR 到所有相交格子 ---------- */
  {
    // 721×723（非整除）：三个孤立不透明点，含右下角最后一个像素
    const points = [[2, 350], [360, 360], [720, 722]];
    const image = sourceAlphaImage(721, 723, (x, y) => points.some(([px, py]) => px === x && py === y));
    const env = makeTiledEnv(image);
    const api = loadModule(env);
    const grid = api._scanAlphaGrid(env.img);
    check('非整除源图网格按上限取', grid && grid.width === 360 && grid.height === 361,
      grid && { w: grid.width, h: grid.height });
    const scan = api._scanAlpha(env.img, api._limits.maxRects);
    const mapped = api._mapRunsToCssEx(env.img, scan);
    check('非整除源图不溢出', mapped.overflow === false, mapped.overflow);
    const covered = points.every(([x, y]) => rectCoversPoint(mapped.rects, x + 0.5, y + 0.5));
    check('非整除下全部源不透明点（含边界像素）被覆盖', covered, { points, rects: mapped.rects });
    check('非整除下透明角落被释放',
      !rectCoversPoint(mapped.rects, 700.5, 1.5), 'transparent corner captured');
    // 逐个源像素核对（含像素中心与四边足迹）
    let missed = 0;
    for (let y = 0; y < image.height; y += 1) {
      for (let x = 0; x < image.width; x += 1) {
        if (image.alpha[y * image.width + x] < 16) continue;
        for (const [cx, cy] of [[x + 0.5, y + 0.5], [x + 0.01, y + 0.01],
          [x + 0.99, y + 0.99]]) {
          if (!rectCoversPoint(mapped.rects, cx, cy)) missed += 1;
        }
      }
    }
    check('非整除下源像素中心与四边足迹全部被覆盖', missed === 0, missed);
  }

  /* ---------- 1c) 非整除聚合：坐标保留原网格末尾边界，不漂移 ---------- */
  {
    const env = makeEnv({
      natural: { width: 1000, height: 1000 },
      rect: { left: 0, top: 0, width: 1000, height: 1000, right: 1000, bottom: 1000 },
    });
    const api = loadModule(env);
    // 原网格 361 宽、block=2 → 聚合网格 181；末格起点必须按原格宽算
    const scan = {
      width: 181, height: 181,
      baseWidth: 361, baseHeight: 361, block: 2,
      rows: [[[180, 181]]],
      segments: 1, complete: true, failed: '',
    };
    const ex = api._mapRunsToCssEx(env.img, scan);
    const rect = ex.rects[0];
    const expectedX0 = 180 * 2 * (1000 / 361);
    const expectedX1 = 1000;
    check('非整除聚合格起点按原格宽映射',
      rect && Math.abs(rect[0] - expectedX0) < 0.05, { rect, expectedX0 });
    check('非整除聚合格末尾落在原图边界',
      rect && Math.abs(rect[2] - expectedX1) < 0.05, { rect, expectedX1 });
    const expectedY1 = Math.min(1 * 2, 361) * (1000 / 361);
    check('非整除聚合 band 末尾按原格边界夹取',
      rect && Math.abs(rect[3] - expectedY1) < 0.05, { rect, expectedY1 });
  }


  /* ---------- 2) 病态棋盘：仍不整窗回退，矩形数有硬上限 ---------- */
  {
    alphaFn = (x, y) => (((x + y) % 2) === 0 ? 255 : 0);
    const natural = { width: 120, height: 120 };
    const env = makeEnv({
      natural,
      rect: { left: 0, top: 0, width: 120, height: 120, right: 120, bottom: 120 },
    });
    const native = makeNative({ code: 'applied' });
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(300);
    const status = api.status();
    check('棋盘图不整窗回退', status.reason === 'applied', status.reason);
    check('棋盘图提交了有界矩形', native.payloads.length === 1
      && native.payloads[0].rects.length <= 1200,
      native.payloads.length && native.payloads[0].rects.length);
    check('棋盘图矩形数不超过硬上限',
      native.payloads.length === 0 || native.payloads[0].rects.length <= 1200,
      native.payloads.length ? native.payloads[0].rects.length : 0);
    alphaFn = complexPortraitAlpha;
  }

  /* ---------- 3) 纯透明图：安全回退，不应用空 Region ---------- */
  {
    alphaFn = () => 0;
    const env = makeEnv();
    const native = makeNative({ code: 'applied' });
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(250);
    check('空轮廓不提交载荷', native.payloads.length === 0, native.payloads.length);
    check('空轮廓移除 pass-through-active',
      !env.body.classes.has('pass-through-active'), Array.from(env.body.classes));
    check('空轮廓通知原生关闭穿透', native.enabledCalls.includes(false), native.enabledCalls);
    alphaFn = complexPortraitAlpha;
  }

  /* ---------- 4) 超大图：明确失败，不伪造轮廓 ---------- */
  {
    alphaFn = bandAlpha;
    const env = makeEnv({ natural: { width: 9000, height: 100 } });
    const native = makeNative();
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(200);
    check('超大图原因正确', api.status().reason === 'image-too-large', api.status().reason);
    check('超大图不提交载荷', native.payloads.length === 0, native.payloads.length);
    check('超大图关闭 native 穿透',
      native.enabledCalls.includes(false), native.enabledCalls);
  }

  /* ---------- 5) queued 与 native ACK 区分 ---------- */
  {
    alphaFn = bandAlpha;
    const env = makeEnv();
    const native = makeNative({ code: 'queued', appliedRevision: 0 });
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(260);
    let status = api.status();
    check('queued 后尚未 active', status.active === false, status);
    check('queued 后存在 pending revision', status.pending === true && status.pendingRevision === 1,
      status);
    check('queued 已提交一次', native.payloads.length === 1, native.payloads.length);
    check('queued 未推进 revision（未 ACK）', status.revision === 0, status.revision);

    const before = native.payloads.length;
    api.submitNow();
    check('pending 期间不重复过桥', native.payloads.length === before, native.payloads.length);

    native.appliedRevision = 1;
    api._syncAck();
    status = api.status();
    check('ACK 后 active=true', status.active === true, status);
    check('ACK 后 revision 追平', status.revision === 1, status.revision);
    check('ACK 后加回 pass-through-active',
      env.body.classes.has('pass-through-active'), Array.from(env.body.classes));
    const afterAck = native.payloads.length;
    api.submitNow();
    check('ACK 后相同几何不重复过桥', native.payloads.length === afterAck, native.payloads.length);
  }

  /* ---------- 6) 原生拒绝 → 受控重试恢复 ---------- */
  {
    alphaFn = bandAlpha;
    const env = makeEnv();
    const native = makeNative({ code: 'queued', error: 'no-surface-control' });
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(260);
    check('原生拒绝前已提交', native.payloads.length === 1, native.payloads.length);
    api._syncAck();
    let status = api.status();
    check('原生拒绝后不标记 active', status.active === false, status);
    check('原生拒绝原因归一化', status.reason === 'surface-not-ready', status.reason);
    check('原生拒绝后 retryBudget 可用', status.retryBudget > 0, status.retryBudget);
    check('原生拒绝后 desired 仍为 true', status.desired === true, status);

    native.error = '';
    native.code = 'applied';
    api.notify('retry');
    await sleep(260);
    status = api.status();
    check('恢复后重新提交并 active', status.active === true, status);
    check('恢复后 native 被重新开启',
      native.enabledCalls.lastIndexOf(true) > native.enabledCalls.indexOf(false),
      native.enabledCalls);
  }

  /* ---------- 7) 页面会话登记 / 注销 ---------- */
  {
    alphaFn = bandAlpha;
    const env = makeEnv();
    const native = makeNative({ code: 'applied' });
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(200);
    check('启用时登记了页面会话', native.registerCalls.length === 1,
      native.registerCalls);
    check('登记的会话与载荷一致',
      native.registerCalls[0] === native.payloads[0].pageSessionId, {
        registered: native.registerCalls[0],
        payload: native.payloads[0] && native.payloads[0].pageSessionId,
      });
    api._invalidateSession();
    check('pagehide 注销上报', native.invalidateCalls.length === 1 && native.invalidateCalls[0],
      native.invalidateCalls);
  }

  /* ---------- 8) 穿透偏好走原生通道（不再只依赖 localStorage） ---------- */
  {
    alphaFn = bandAlpha;
    const env = makeEnv();
    const native = makeNative();
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(120);
    check('开启穿透写入原生偏好', native.prefWrites.length >= 1
      && native.prefWrites[0].passThrough === true, native.prefWrites);
    api.setEnabled(false);
    check('关闭穿透写入原生偏好', native.prefWrites.length >= 2
      && native.prefWrites[native.prefWrites.length - 1].passThrough === false,
      native.prefWrites);
  }

  /* ---------- 8b) 原生偏好是唯一事实来源（陈旧 localStorage 不得复活穿透） ---------- */
  {
    alphaFn = bandAlpha;
    // 本机 localStorage 残留旧的 '1'，但原生已解析为「关」（用户之前关过/默认关）
    const env = makeEnv({ storage: { 'sakura.remote.passThrough': '1' } });
    const native = makeNative({ prefs: { has: false, value: false } });
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.install();
    await sleep(200);
    check('原生为关时陈旧 localStorage 不能复活穿透',
      api.status().desired === false, api.status());
    check('原生为关时不提交轮廓', native.payloads.length === 0, native.payloads.length);

    // 反向：localStorage 残留 '0'，但原生解析为「开」，应以原生为准
    const env2 = makeEnv({ storage: { 'sakura.remote.passThrough': '0' } });
    const native2 = makeNative({ prefs: { has: false, value: true } });
    env2.win.SakuraNative = native2;
    const api2 = loadModule(env2);
    api2._reset();
    api2.install();
    await sleep(200);
    check('原生为开时以原生为准（不被陈旧 localStorage 关掉）',
      api2.status().desired === true && native2.payloads.length >= 1,
      { desired: api2.status().desired, payloads: native2.payloads.length });
  }

  /* ---------- 8c) 几何变化不重扫原图（性能） ---------- */
  {
    alphaFn = complexPortraitAlpha;
    const natural = { width: 200, height: 300 };
    const env = makeEnv({
      natural,
      rect: { left: 0, top: 0, width: 200, height: 300, right: 200, bottom: 300 },
    });
    const native = makeNative({ code: 'applied' });
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(320);
    const afterEnable = api.status();
    check('开启穿透只扫描原图一次', afterEnable.scans === 1, afterEnable.scans);
    // 配置面板/卡片显隐 + 窗口几何变化：只需重算坐标，不得重扫原图
    env.img.getBoundingClientRect = () => ({
      left: 12, top: 30, width: 210, height: 300, right: 222, bottom: 330,
    });
    api.notify('config-panel');
    api.notify('card');
    await sleep(160);
    const afterNotify = api.status();
    check('配置面板/卡片/几何变化不重扫原图', afterNotify.scans === 1, afterNotify.scans);
    check('几何变化仍会重新提交', afterNotify.submits >= 2, afterNotify.submits);
    check('像素读取次数有记录且为有界值',
      afterNotify.pixelReads === 200 * 300, afterNotify.pixelReads);
    // 图片换了（key 不同）才允许重扫
    env.img.currentSrc = 'http://pc/asset/portrait?key=B';
    native.code = 'applied';
    api.notify('portrait-changed');
    await sleep(150);
    check('图片变化后才重扫', api.status().scans === 2, api.status().scans);
  }

  /* ---------- 9) mapRunsToCssEx 溢出显式上报 ---------- */
  {
    const env = makeEnv();
    const api = loadModule(env);
    // 100 行、每行 13 段且逐行不同 → 1300 矩形 > MAX_RECTS(1200)
    const rows = [];
    for (let y = 0; y < 100; y += 1) {
      const runs = [];
      for (let k = 0; k < 13; k += 1) {
        const start = (y % 40) + k * 20;
        runs.push([start, start + 5]);
      }
      rows.push(runs);
    }
    const scan = { width: 300, height: 100, rows, approximated: false, segments: 1300,
      complete: true, failed: '' };
    const detailed = api._mapRunsToCssEx(env.img, scan);
    check('矩形超上限时 overflow=true', detailed.overflow === true, detailed.overflow);
    const rects = api._mapRunsToCss(env.img, scan);
    check('_mapRunsToCss 仍返回数组（兼容）', Array.isArray(rects), typeof rects);
    check('溢出时不把半截 band 拉到底', rects.length <= 1200, rects.length);
  }

  console.log();
  if (failures.length) {
    console.error(`${failures.length} 项失败：${failures.join(', ')}`);
    process.exit(1);
  }
  console.log('PASS');
}

alphaFn = complexPortraitAlpha;
main().catch((error) => {
  console.error('测试异常：', error);
  process.exit(1);
});
