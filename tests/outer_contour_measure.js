// 外轮廓穿透的**独立测量工具**（真实 PNG 像素级，主机侧）。
//
// 目的：在同一输入、同一度量口径下，对「当前工作区模块」与「历史基线模块」
// 给出可复现的数字，用于判断「矩形预算」与「聚合级别」到底值不值得，
// 而不是靠猜。所有覆盖统计都直接读原图 alpha，不看降采样网格。
//
// 度量：
//   scanMs              扫一次整张原图（分块 drawImage + getImageData）的主机耗时
//   scanPixels          扫一次读到的源像素数
//   chooseLevelMs       选简化级别（含全部 rowsAtLevel）耗时
//   rects               提交矩形数
//   segments            行段数
//   level/block         简化级别与聚合块
//   payloadBytes        JSON 载荷字节（按真实载荷结构序列化）
//   opaqueCoverage     alpha≥16 源像素落在提交矩形内的比例（必须 = 1）
//   exteriorRelease    每行首个/末个不透明像素**之外**的透明像素释放比例
//   interiorRelease    行内（外轮廓内部）透明像素释放比例（外轮廓填充后应为 0）
//   areaRatio          提交矩形总面积 / 原图面积
//   toleranceCssPx     当前显示尺寸下如实上报的边缘容差
//
// 用法:
//   node tests/outer_contour_measure.js                      # 当前模块 + 真实 PNG
//   node tests/outer_contour_measure.js --module <path>      # 指定模块（基线对比）
//   node tests/outer_contour_measure.js --json out.json
//   node tests/outer_contour_measure.js --budgets 256,512,1536
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(ROOT, 'plugin.yaml'))
  ? ROOT
  : path.join(ROOT, 'sakura_remote');
const { decodePngAlpha } = require('./png_alpha.js');

function argValue(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) return fallback;
  return process.argv[index + 1];
}

const MODULE_PATH = path.resolve(argValue(
  '--module', path.join(PLUGIN_ROOT, 'static', 'overlay_touch_region.js')));
const JSON_OUT = argValue('--json', '');
const BUDGETS = String(argValue('--budgets', '')).trim()
  ? String(argValue('--budgets')).split(',').map((value) => Number(value.trim()))
  : [];
const THRESHOLD = 16;
const REAL_ROOT = argValue('--real-root', 'F:/sakura/characters');
const CANDIDATES = [
  'Sakura/portraits/A020.png',
  'Sakura/portraits/A081.png',
  'Sakura/portraits/E050.png',
  'nikaidou_shinku/portrait/default/01_neutral.png',
  'kuro_sakuramoyu/portrait/default/01_neutral.png',
];
/** 显示尺寸（CSS 像素）：小屏手机竖屏 / 大屏。 */
const DISPLAYS = [
  { label: '393x700', width: 393, height: 700 },
  { label: '412x915', width: 412, height: 915 },
];

const source = fs.readFileSync(MODULE_PATH, 'utf8');

const now = () => Number(process.hrtime.bigint() / 1000n) / 1000;

function makeEnv(image, display) {
  const img = {
    naturalWidth: image.width,
    naturalHeight: image.height,
    src: 'http://pc/asset/portrait?key=A',
    currentSrc: 'http://pc/asset/portrait?key=A',
    getBoundingClientRect() {
      // object-fit: contain + object-position: bottom center 之后的显示框
      const scale = Math.min(display.width / image.width, display.height / image.height);
      const shownW = image.width * scale;
      const shownH = image.height * scale;
      const left = (display.width - shownW) / 2;
      return { left, top: display.height - shownH, width: shownW, height: shownH,
        right: left + shownW, bottom: display.height };
    },
  };
  const canvasFactory = () => {
    const canvas = { width: 0, height: 0 };
    let tile = { sx: 0, sy: 0 };
    canvas.getContext = () => ({
      clearRect() {},
      imageSmoothingEnabled: true,
      drawImage(source, sx, sy) { tile = { sx: Math.round(sx || 0), sy: Math.round(sy || 0) }; },
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
  const body = { classList: { add() {}, remove() {}, contains() { return false; } } };
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
    innerWidth: display.width,
    innerHeight: display.height,
    devicePixelRatio: 2.75,
    document: doc,
    localStorage: { getItem() { return null; }, setItem() {} },
    setTimeout, setInterval() { return 0; }, clearInterval() {},
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    addEventListener() {}, SakuraNative: null,
  };
  return { win, doc, img };
}

function loadModule(env) {
  const factory = new Function('window', 'document', 'localStorage', 'setTimeout',
    'setInterval', 'clearInterval', 'requestAnimationFrame', 'console',
    source + '\n;return window.SakuraTouchRegion;');
  return factory(env.win, env.doc, env.win.localStorage, env.win.setTimeout,
    env.win.setInterval, env.win.clearInterval, env.win.requestAnimationFrame, console);
}

/**
 * 源像素级覆盖统计。**按提交矩形的真实几何**逐像素判定，不做格点近似：
 *
 *   源像素 (x,y) → object-fit contain + bottom-center 的 CSS 点 (cssX,cssY)
 *   → 是否落在任一提交矩形内（含极小的闭合容差）。
 *
 * 这与原生 buildRegion 的并集语义一致（原生把矩形按 scaleX/scaleY 放大后并集，
 * 不透明像素的显示点只要在某个矩形内就算可交互）。
 *
 * 为什么不用「聚合网格覆盖位图」：把矩形撤回网格时会因为 floor/ceil 的边界
 * 归属产生 1 格误差，实测会把真实 100% 的覆盖误报成 4%。这里改成精确判定，
 * 误差只来自 object-fit 的浮点映射本身，与真机一致。
 *
 * 每像素用一个「按 y 排序的矩形区间 + 二分」定位候选行，避免 rects×pixels 的
 * 二次复杂度（150 × 1.8M 会直接把测量跑成分钟级）。
 */
function analyze(image, scan, rects, display) {
  const scale = Math.min(display.width / image.width, display.height / image.height);
  const shownW = image.width * scale;
  const shownH = image.height * scale;
  const offsetX = (display.width - shownW) / 2;
  const offsetY = display.height - shownH;
  // 按 top 排序，构造二分定位所需的有序数组
  const sorted = rects.slice().sort((a, b) => a[1] - b[1]);
  const tops = sorted.map((r) => r[1]);
  const bottoms = sorted.map((r) => r[3]);
  /** 返回覆盖 cssY 这一行的矩形索引（用二分找到第一个 bottom > cssY 的行）。 */
  function firstCandidate(cssY) {
    let low = 0;
    let high = bottoms.length;
    while (low < high) {
      const mid = (low + high) >> 1;
      if (bottoms[mid] <= cssY) low = mid + 1;
      else high = mid;
    }
    return low;
  }
  function covered(cssX, cssY) {
    const limit = Math.min(sorted.length, firstCandidate(cssY) + 4);
    for (let index = firstCandidate(cssY); index < limit; index += 1) {
      const r = sorted[index];
      if (r[1] > cssY) break;
      if (cssX >= r[0] - 0.001 && cssX <= r[2] + 0.001
          && cssY >= r[1] - 0.001 && cssY <= r[3] + 0.001) {
        return true;
      }
    }
    return false;
  }

  let opaque = 0;
  let opaqueCovered = 0;
  let exteriorPixels = 0;
  let exteriorReleased = 0;
  let interiorPixels = 0;
  let interiorReleased = 0;
  let bottomRow = -1;
  let bottomCovered = false;
  for (let y = 0; y < image.height; y += 1) {
    let first = image.width;
    let last = -1;
    for (let x = 0; x < image.width; x += 1) {
      if (image.alpha[y * image.width + x] >= THRESHOLD) {
        if (x < first) first = x;
        last = x;
      }
    }
    const cssY = offsetY + (y + 0.5) * scale;
    for (let x = 0; x < image.width; x += 1) {
      const value = image.alpha[y * image.width + x];
      const isCovered = covered(offsetX + (x + 0.5) * scale, cssY);
      if (value >= THRESHOLD) {
        opaque += 1;
        if (isCovered) opaqueCovered += 1;
        bottomRow = y;
        bottomCovered = isCovered;
      } else if (last < 0 || x < first || x > last) {
        exteriorPixels += 1;
        if (!isCovered) exteriorReleased += 1;
      } else {
        interiorPixels += 1;
        if (!isCovered) interiorReleased += 1;
      }
    }
  }
  const area = rects.reduce(
    (sum, r) => sum + Math.max(0, r[2] - r[0]) * Math.max(0, r[3] - r[1]), 0);
  return {
    opaque,
    opaqueCoverage: opaque ? opaqueCovered / opaque : 0,
    exteriorPixels,
    exteriorRelease: exteriorPixels ? exteriorReleased / exteriorPixels : 1,
    interiorPixels,
    interiorRelease: interiorPixels ? interiorReleased / interiorPixels : 0,
    areaRatio: area / (image.width * image.height),
    bottomRow,
    bottomCovered,
    display,
  };
}

/** 模拟真实载荷结构，量出 JSON 字节数（与 buildPayload 同字段）。 */
function payloadBytes(rects, display, scan, tolerance) {
  const payload = {
    v: 1,
    revision: 2,
    pageSessionId: 'ps-measure-0001',
    cssW: display.width,
    cssH: display.height,
    dpr: 2.75,
    portraitKey: 'A020',
    mode: 'overlay',
    approximated: !!scan.approximated,
    level: scan.level || 0,
    tolerance: Math.round(tolerance * 100) / 100,
    segments: scan.segments || 0,
    rects: rects.map((r) => r.map((value) => Math.round(value * 100) / 100)),
    layoutSignature: 'o|' + 'x'.repeat(64),
  };
  return JSON.stringify(payload).length;
}

function measureBudget(api, image, env, budget) {
  const scanStart = now();
  const scan = api._scanAlpha(env.img, budget);
  const scanMs = now() - scanStart;
  if (!scan || scan.failed) {
    return { budget, failed: scan ? scan.failed : 'null' };
  }
  const selectStart = now();
  const grid = api._scanAlphaGrid(env.img);
  const picked = api._chooseLevel(grid, budget);
  const chooseLevelMs = now() - selectStart;
  const rects = api._mapRunsToCss(env.img, scan);
  const tolerance = api._outlineTolerance(env.img, scan);
  const stats = analyze(image, scan, rects, env.display);
  return {
    budget,
    level: scan.level,
    block: scan.block,
    gridW: scan.baseWidth,
    gridH: scan.baseHeight,
    rows: scan.height,
    segments: scan.segments,
    rects: rects.length,
    scanMs: Number(scanMs.toFixed(2)),
    chooseLevelMs: Number(chooseLevelMs.toFixed(2)),
    payloadBytes: payloadBytes(rects, env.display, scan, tolerance),
    toleranceCssPx: Number(tolerance.toFixed(3)),
    opaqueCoverage: Number(stats.opaqueCoverage.toFixed(6)),
    exteriorRelease: Number(stats.exteriorRelease.toFixed(4)),
    interiorRelease: Number(stats.interiorRelease.toFixed(4)),
    areaRatio: Number(stats.areaRatio.toFixed(4)),
    bottomCovered: stats.bottomCovered,
  };
}

function main() {
  const found = CANDIDATES
    .map((rel) => path.join(REAL_ROOT, rel))
    .filter((file) => {
      try { return fs.statSync(file).isFile(); } catch (error) { return false; }
    });
  if (!found.length) {
    console.log('[SKIP] 未找到本机真实立绘资源，无法测量（只读，不导出）。');
    return 0;
  }
  const report = {
    module: path.relative(ROOT, MODULE_PATH).replace(/\\/g, '/'),
    moduleSha256: require('crypto').createHash('sha256')
      .update(source, 'utf8').digest('hex').toUpperCase(),
    threshold: THRESHOLD,
    moduleLimits: null,
    budgets: BUDGETS,
    results: [],
  };
  console.log(`模块: ${report.module}`);
  console.log(`SHA256: ${report.moduleSha256}`);
  console.log();

  found.forEach((file) => {
    const image = decodePngAlpha(file);
    DISPLAYS.forEach((display) => {
      const env = makeEnv(image, display);
      env.display = display;
      const api = loadModule(env);
      report.moduleLimits = report.moduleLimits || api._limits;
      const limit = api._limits.maxRects;
      const budgets = BUDGETS.length ? BUDGETS : [limit];
      budgets.forEach((budget) => {
        const result = measureBudget(api, image, env, budget);
        const row = {
          fixture: path.relative(REAL_ROOT, file).replace(/\\/g, '/'),
          image: `${image.width}x${image.height}`,
          display: display.label,
          ...result,
        };
        report.results.push(row);
        console.log(JSON.stringify(row));
      });
    });
  });

  console.log();
  console.log('budget   meanRects  maxRects  meanTolCss  minExteriorRelease  payloadBytes  scanMs  chooseLevelMs');
  const budgets = Array.from(new Set(report.results.map((row) => row.budget))).sort((a, b) => a - b);
  budgets.forEach((budget) => {
    const rows = report.results.filter((row) => row.budget === budget && !row.failed);
    if (!rows.length) return;
    const mean = (key) => rows.reduce((sum, row) => sum + row[key], 0) / rows.length;
    console.log([
      String(budget).padEnd(7),
      mean('rects').toFixed(1).padEnd(10),
      Math.max(...rows.map((row) => row.rects)).toString().padEnd(9),
      mean('toleranceCssPx').toFixed(2).padEnd(11),
      Math.min(...rows.map((row) => row.exteriorRelease)).toFixed(4).padEnd(19),
      Math.round(mean('payloadBytes')).toString().padEnd(13),
      mean('scanMs').toFixed(2).padEnd(7),
      mean('chooseLevelMs').toFixed(2),
    ].join(' '));
  });

  if (JSON_OUT) {
    const target = path.resolve(JSON_OUT);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, JSON.stringify(report, null, 2), 'utf8');
    console.log();
    console.log(`已写出: ${path.relative(ROOT, target)}`);
  }
  return 0;
}

module.exports = { makeEnv, loadModule, analyze, measureBudget };
if (require.main === module) process.exit(main());
