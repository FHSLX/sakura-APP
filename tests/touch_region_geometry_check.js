// R2-1 / TS-03 专项：精确局部穿透的**外轮廓**前端几何与安全降级。
// 用假 DOM / 假 canvas / 假原生桥验证**真实行为**，不是搜索源码字符串：
//   1) alpha 行段按「外轮廓」语义：每行取最左→最右不透明格子的一条带，
//      行内空隙（发缝/内部洞）被填平，但**左右两端仍严格贴合真实外轮廓**；
//   2) object-fit/transform 后的坐标映射正确（含缩放与位移）；
//   3) 可见可交互控件矩形被并入；
//   4) 相同几何不重复过桥（revision 单调、去重）；
//   5) API 不支持 / 轮廓为空时**不调用** setTouchableRegion，退回整窗可交互。
//
// 本轮用户授权：内部空隙不穿透是可接受的取舍；因此旧断言「内部透明洞没有被填满」
// 被**替换**为更强的外轮廓断言（左右边界精确 + 内部确实被填 + 外部确实释放），
// 而不是删除检查。安全、坐标、全像素覆盖与恢复类断言一条都没有减少。
//
// 用法: node tests/touch_region_geometry_check.js
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
function near(a, b, tolerance) {
  return Math.abs(a - b) <= (tolerance === undefined ? 0.51 : tolerance);
}
function closeRect(rect, expected, tolerance) {
  return rect && rect.length === 4
    && near(rect[0], expected[0], tolerance) && near(rect[1], expected[1], tolerance)
    && near(rect[2], expected[2], tolerance) && near(rect[3], expected[3], tolerance);
}

/* ---------- 假环境 ---------- */

/** 图案：100x100，只有两条竖带不透明，中间是内部透明洞。 */
function bandAlpha(x) {
  return ((x >= 10 && x < 20) || (x >= 80 && x < 90)) ? 255 : 0;
}
let alphaFn = bandAlpha;

function makeImg(rect) {
  const box = rect || { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 };
  return {
    naturalWidth: 100,
    naturalHeight: 100,
    src: 'http://pc/asset/portrait?key=A',
    currentSrc: 'http://pc/asset/portrait?key=A',
    getBoundingClientRect() { return box; },
  };
}

function makeCanvas(img) {
  const canvas = { width: 0, height: 0 };
  canvas.getContext = () => ({
    clearRect() {},
    drawImage() {},
    getImageData() {
      const w = canvas.width;
      const h = canvas.height;
      const data = new Uint8ClampedArray(w * h * 4);
      for (let py = 0; py < h; py += 1) {
        for (let px = 0; px < w; px += 1) {
          // 假 canvas 尺寸就是原图尺寸（100×100），因此像素坐标即源坐标。
          // 必须把 y 也传给图案函数：只传 x 会让「按行」图案（如只有顶部不透明）
          // 静默变成全透明，测试会以错误的原因失败。
          const nx = Math.floor((px * img.naturalWidth) / w);
          const ny = Math.floor((py * img.naturalHeight) / h);
          data[(py * w + px) * 4 + 3] = alphaFn(nx, ny);
        }
      }
      return { data };
    },
  });
  return canvas;
}

function makeElement(rect, options = {}) {
  return {
    disabled: !!options.disabled,
    getBoundingClientRect() { return rect; },
    getClientRects() { return options.multi ? options.multi : [rect]; },
  };
}

function makeEnv(options = {}) {
  const store = new Map();
  const win = {
    innerWidth: 393,
    innerHeight: 700,
    devicePixelRatio: 2.75,
    document: null,
    localStorage: {
      getItem(key) { return store.has(key) ? store.get(key) : null; },
      setItem(key, value) { store.set(key, String(value)); },
    },
    setTimeout,
    setInterval() { return 0; },      // 不启动轮询，避免测试互相干扰
    clearInterval() {},
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    addEventListener() {},
    SakuraNative: null,
  };
  const img = makeImg(options.rect);
  const wrap = makeElement(options.wrapRect || { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 });
  const controls = options.controls || [];
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
    createElement(tag) { return tag === 'canvas' ? makeCanvas(img) : {}; },
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
  win.document = doc;
  return { win, doc, img, body, controls };
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

function makeNative(supported) {
  return {
    supported,
    payloads: [],
    enabledCalls: [],
    isTouchRegionSupported() { return this.supported; },
    setTouchRegion(json) {
      this.payloads.push(JSON.parse(json));
      return 'applied';
    },
    setPassThroughEnabled(value) { this.enabledCalls.push(!!value); },
  };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
  /* ---------- 1) 外轮廓语义：行内空隙填平，左右外边界不动 ---------- */
  {
    const env = makeEnv();
    const api = loadModule(env);
    const scan = api._scanAlpha(env.img);
    check('扫描尺寸按降采样上限取', scan && scan.width === 100 && scan.height === 100,
      scan && { w: scan.width, h: scan.height });
    // 100×100 网格，level 0：每行两条不透明段 → 外轮廓合并为一条带
    check('每行合并为一条外轮廓带', scan.rows[0].length === 1, scan.rows[0]);
    check('外轮廓带如实记录被填平的内部空隙数', scan.filledGaps > 0, scan.filledGaps);
    const rects = api._mapRunsToCss(env.img, scan);
    check('两条竖带合并为一个外轮廓矩形', rects.length === 1, rects);
    // 左边界 = 最左不透明像素（10），右边界 = 最右不透明像素末（90）
    check('外轮廓左右边界精确贴合真实轮廓',
      closeRect(rects[0], [10, 0, 90, 100]), rects[0]);

    // 源像素级：左右边界之外的透明像素必须全部释放（外部穿透）
    const inside = (x) => rects.some((r) => x >= r[0] - 0.001 && x <= r[2] + 0.001);
    let outsideTransparent = 0;
    let outsideReleased = 0;
    for (let x = 0; x < 100; x += 1) {
      if (x >= 10 && x < 90) continue;      // 外轮廓内部
      for (let y = 0; y < 100; y += 1) {
        outsideTransparent += 1;
        if (!inside(x + 0.5)) outsideReleased += 1;
      }
    }
    check('外轮廓以外的透明列全部释放',
      outsideTransparent > 0 && outsideReleased === outsideTransparent,
      { outsideTransparent, outsideReleased });

    // 内部空隙被填平：中间列落在矩形内（用户授权：内部不穿透）
    check('内部空隙被填平（发缝不再穿透）',
      rects.some((r) => r[0] <= 50 && r[2] >= 50), rects);
    // 但不是整图包围盒：左右仍留出透明列
    check('没有退化成整图包围盒',
      rects[0][0] > 0 && rects[0][2] < 100, rects[0]);
  }

  /* ---------- 2) object-fit + transform 坐标映射 ---------- */
  {
    // 祖先 scale(0.5)：包围盒缩到 50x50，并平移到 (50,100)
    const env = makeEnv({
      rect: { left: 50, top: 100, width: 50, height: 50, right: 100, bottom: 150 },
    });
    const api = loadModule(env);
    const scan = api._scanAlpha(env.img);
    const rects = api._mapRunsToCss(env.img, scan);
    check('缩放后坐标按同一公式映射',
      rects.length === 1 && closeRect(rects[0], [55, 100, 95, 150]), rects);
  }

  /* ---------- 3) 提交载荷：控件并入 + 去重 ---------- */
  {
    const control = makeElement(
      { left: 0, top: 200, width: 100, height: 50, right: 100, bottom: 250 });
    control.selector = '#composer';
    const env = makeEnv({ controls: [control] });
    const native = makeNative(true);
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();

    const ok = api.setEnabled(true);
    check('支持时启用成功', ok === true, ok);
    await sleep(250);
    check('提交了一次区域载荷', native.payloads.length === 1, native.payloads.length);
    const payload = native.payloads[0];
    check('载荷带 CSS 视口尺寸', payload.cssW === 393 && payload.cssH === 700,
      { cssW: payload.cssW, cssH: payload.cssH });
    check('载荷带页面会话 id', typeof payload.pageSessionId === 'string'
      && payload.pageSessionId.length > 4, payload.pageSessionId);
    check('载荷带单调 revision', payload.revision === 1, payload.revision);
    check('控件矩形被并入', payload.rects.some((r) => closeRect(r, [0, 200, 100, 250])),
      payload.rects);
    check('外轮廓矩形仍在载荷里', payload.rects.some((r) => closeRect(r, [10, 0, 90, 100])),
      payload.rects);
    check('载荷如实标注外轮廓填充', payload.interiorFill === 1 && payload.filledGaps > 0,
      { interiorFill: payload.interiorFill, filledGaps: payload.filledGaps });

    const before = native.payloads.length;
    api.submitNow();
    check('几何未变化时不重复过桥', native.payloads.length === before, native.payloads.length);
    check('启用时给原生发了 enabled=true', native.enabledCalls.includes(true),
      native.enabledCalls);
    const status = api.status();
    check('状态如实上报填充计数与矩形数',
      status.interiorFill === true && status.filledGaps > 0 && status.rects > 0,
      { interiorFill: status.interiorFill, filledGaps: status.filledGaps, rects: status.rects });
  }

  /* ---------- 4) API 不支持：明确降级，不碰 setTouchableRegion ---------- */
  {
    const env = makeEnv();
    const native = makeNative(false);
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    const ok = api.setEnabled(true);
    await sleep(120);
    check('不支持时返回 false', ok === false, ok);
    check('不支持时不调用 setTouchRegion', native.payloads.length === 0, native.payloads.length);
    check('不支持时不加 pass-through-active 类',
      !env.body.classes.has('pass-through-active'), Array.from(env.body.classes));
    check('状态给出 api-unsupported', api.status().reason === 'api-unsupported',
      api.status().reason);
  }

  /* ---------- 5) 轮廓为空：安全回退到整窗可交互 ---------- */
  {
    const env = makeEnv();
    const native = makeNative(true);
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    // 全透明立绘
    alphaFn = () => 0;
    api.setEnabled(true);
    await sleep(250);
    check('空轮廓时不应用空 Region', native.payloads.length === 0, native.payloads.length);
    check('空轮廓时移除 pass-through-active',
      !env.body.classes.has('pass-through-active'), Array.from(env.body.classes));
    check('空轮廓时通知原生关闭穿透', native.enabledCalls.includes(false),
      native.enabledCalls);
    // 顶部空白必须释放：全透明图不产生任何矩形，窗口保持整窗可交互（安全底线）
    check('全透明图不产生任何轮廓矩形', api.status().rects === 0, api.status().rects);
    alphaFn = bandAlpha;
  }

  /* ---------- 6) 只有顶部一小块不透明：顶部保留、下方释放 ---------- */
  {
    // 只有 y<20 有内容：非空轮廓 + 下方大面积释放
    alphaFn = (x, y) => (y < 20 ? 255 : 0);
    const env = makeEnv();
    const native = makeNative(true);
    env.win.SakuraNative = native;
    const api = loadModule(env);
    api._reset();
    api.setEnabled(true);
    await sleep(250);
    alphaFn = bandAlpha;
    check('顶部内容图提交了区域', native.payloads.length >= 1, native.payloads.length);
    if (native.payloads.length) {
      const rects = native.payloads[0].rects;
      const coveredArea = rects.reduce(
        (sum, r) => sum + Math.max(0, r[2] - r[0]) * Math.max(0, r[3] - r[1]), 0);
      check('顶部空白之外的下方区域被释放（面积远小于整图）',
        coveredArea < 100 * 100 * 0.5, { coveredArea, rects });
      check('顶部内容仍在提交区域内',
        rects.some((r) => r[1] <= 20 && r[3] >= 20), rects);
    }
  }

  /* ---------- 7) 小球模式：圆形近似区域 ---------- */
  {
    const env = makeEnv({
      wrapRect: { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 },
      bodyClasses: ['bubble-mode'],
    });
    const api = loadModule(env);
    api._reset();
    const rects = api._bubbleCircleRects();
    check('小球区域是多段近似', rects.length >= 8, rects.length);
    // 圆的中段本来就有接近直径的一行；四角是否被覆盖要看**首尾**两条带的宽度
    const firstWidth = rects[0][2] - rects[0][0];
    const lastWidth = rects[rects.length - 1][2] - rects[rects.length - 1][0];
    check('小球四角不被整块矩形覆盖',
      firstWidth < 60 && lastWidth < 60, { firstWidth, lastWidth });
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
