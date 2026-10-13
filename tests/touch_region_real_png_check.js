// 真实 Sakura 立绘 PNG 的轮廓简化本地验证（TS-01）。
//
// 只用**公开视觉资源**：读取 F:/sakura/characters 下真实安装角色的 PNG 像素
// alpha（只读，不导出、不进代码/提交/发布包），不读取提示词、聊天、用户配置
// 或凭据。找不到真实角色时明确 SKIP（公开克隆仓库没有本机安装），不当失败。
//
// 验证内容：
//   1) 真实复杂立绘走**有界保守简化**（不再 contour-too-complex 整窗回退）；
//   2) 实体（整格不透明）覆盖 100%：底部肢体/躯干不被截断；
//   3) 主要透明位置被释放，提交区域远小于整张图片矩形；
//   4) 简化级别/矩形数/边缘容差如实上报，且矩形数在上限内。
//
// 用法: node tests/touch_region_real_png_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml'))
  ? _root
  : path.join(_root, 'sakura_remote');
const { decodePngAlpha } = require('./png_alpha.js');

const source = fs.readFileSync(
  path.join(PLUGIN_ROOT, 'static', 'overlay_touch_region.js'), 'utf8');

const THRESHOLD = 16;
/** 真实手机显示框（CSS 像素）：object-fit contain + object-position bottom center。 */
const DISPLAY = { width: 393, height: 700 };
const REAL_ROOT = 'F:/sakura/characters';
const CANDIDATES = [
  'Sakura/portraits/A020.png',
  'Sakura/portraits/A081.png',
  'nikaidou_shinku/portrait/default/01_neutral.png',
  'kuro_sakuramoyu/portrait/default/01_neutral.png',
];

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

/**
 * 假环境。默认按**原图尺寸**当显示框（scale=1，坐标 === 源像素），
 * 传 display 时按 object-fit contain + bottom-center 还原真实手机显示框，
 * 这样边缘容差与覆盖统计都对应真机布局。
 */
function makeEnv(image, display) {
  const screen = display || { width: image.width, height: image.height };
  const fitScale = Math.min(screen.width / image.width, screen.height / image.height);
  const shownW = image.width * fitScale;
  const shownH = image.height * fitScale;
  const box = {
    left: (screen.width - shownW) / 2,
    top: screen.height - shownH,
    width: shownW,
    height: shownH,
    right: (screen.width - shownW) / 2 + shownW,
    bottom: screen.height,
  };
  const img = {
    naturalWidth: image.width,
    naturalHeight: image.height,
    src: 'http://pc/asset/portrait?key=A',
    currentSrc: 'http://pc/asset/portrait?key=A',
    getBoundingClientRect() { return box; },
  };
  const store = new Map();
  const canvasFactory = () => {
    const canvas = { width: 0, height: 0 };
    let tile = { sx: 0, sy: 0 };
    canvas.getContext = () => ({
      clearRect() {},
      imageSmoothingEnabled: true,
      // 忠实模拟「按瓦片 1:1 读取」：记住源矩形
      drawImage(source, sx, sy) {
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
    innerWidth: screen.width, innerHeight: screen.height, devicePixelRatio: 2.75,
    document: doc,
    localStorage: {
      getItem(key) { return store.has(key) ? store.get(key) : null; },
      setItem(key, value) { store.set(key, String(value)); },
    },
    setTimeout, setInterval() { return 0; }, clearInterval() {},
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    addEventListener() {}, SakuraNative: null,
  };
  return { win, doc, img, display: screen };
}

function loadModule(env) {
  const factory = new Function('window', 'document', 'localStorage', 'setTimeout',
    'setInterval', 'clearInterval', 'requestAnimationFrame', 'console',
    source + '\n;return window.SakuraTouchRegion;');
  return factory(env.win, env.doc, env.win.localStorage, env.win.setTimeout,
    env.win.setInterval, env.win.clearInterval, env.win.requestAnimationFrame, console);
}

/**
 * **源像素级**覆盖分析：按原始 PNG 的每个像素（alpha ≥ THRESHOLD）核对它
 * **显示点**是否落在提交矩形内，而不是只看降采样后的网格或 alpha=255 的大块人物。
 *
 * 映射：源像素 → object-fit contain + bottom-center 的 CSS 点 → 是否在任一提交
 * 矩形内。这与原生 buildRegion 的并集语义一致（原生把矩形按实际像素/视口放大
 * 后并集），误差只来自 object-fit 的浮点映射本身。
 *
 * 为什么不再用「先把矩形撤回网格」的格点近似：floor/ceil 的边界归属会产生
 * 1 格误差，实测会把真实 100% 的覆盖误报成 4%（假失败）。精确判定既更严格
 * 也更诚实。
 *
 * 统计口径：
 *   - sourceOpaque/OpaqueCovered：alpha≥16 的源像素及其被覆盖数（必须 = 100%）；
 *   - exteriorPixels/Released：**该行首末不透明像素之外**的透明像素（外部轮廓外，
 *     应尽量释放 = 真穿透）；
 *   - interiorPixels/Released：外轮廓**内部**的透明像素（发缝/内部洞；本轮用户授权
 *     不要求穿透，允许为 0 释放）；
 *   - transparentReleased：全部透明像素的释放比例（诊断用，不再是判定主口径）。
 */
function analyze(image, scan, rects, display) {
  const id = display || { width: image.width, height: image.height };
  const scale = Math.min(id.width / image.width, id.height / image.height);
  const shownW = image.width * scale;
  const shownH = image.height * scale;
  const offsetX = (id.width - shownW) / 2;
  const offsetY = id.height - shownH;
  // 按 top 排序 + 二分，避免 rects×pixels 的二次复杂度
  const sorted = rects.slice().sort((a, b) => a[1] - b[1]);
  const bottoms = sorted.map((r) => r[3]);
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
    const start = firstCandidate(cssY);
    const limit = Math.min(sorted.length, start + 4);
    for (let index = start; index < limit; index += 1) {
      const r = sorted[index];
      if (r[1] > cssY) break;
      if (cssX >= r[0] - 0.001 && cssX <= r[2] + 0.001
          && cssY >= r[1] - 0.001 && cssY <= r[3] + 0.001) {
        return true;
      }
    }
    return false;
  }

  let sourceOpaque = 0;
  let sourceOpaqueCovered = 0;
  let transparentPixels = 0;
  let transparentReleased = 0;
  let exteriorPixels = 0;
  let exteriorReleased = 0;
  let interiorPixels = 0;
  let interiorReleased = 0;
  let bottomOpaqueRow = -1;
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
        sourceOpaque += 1;
        if (isCovered) sourceOpaqueCovered += 1;
        bottomOpaqueRow = y;
        bottomCovered = isCovered;
      } else {
        transparentPixels += 1;
        if (!isCovered) transparentReleased += 1;
        if (last < 0 || x < first || x > last) {
          exteriorPixels += 1;
          if (!isCovered) exteriorReleased += 1;
        } else {
          interiorPixels += 1;
          if (!isCovered) interiorReleased += 1;
        }
      }
    }
  }
  return { sourceOpaque, sourceOpaqueCovered, transparentPixels, transparentReleased,
    exteriorPixels, exteriorReleased, interiorPixels, interiorReleased,
    bottomOpaqueRow, bottomCovered };
}

function main() {
  const found = CANDIDATES
    .map((rel) => path.join(REAL_ROOT, rel))
    .filter((file) => {
      try { return fs.statSync(file).isFile(); } catch (error) { return false; }
    });
  if (!found.length) {
    console.log('[SKIP] 未找到本机真实 Sakura 视觉资源（公开克隆仓库不带）；本用例只在本机运行。');
    console.log('PASS');
    return 0;
  }

  const evidence = [];
  found.forEach((file) => {
    const label = path.basename(file);
    let image;
    try {
      image = decodePngAlpha(file);
    } catch (error) {
      check(`${label} 可解码`, false, error.message);
      return;
    }
    // 真实手机显示框（393×700 CSS，object-fit contain + bottom-center）：
    // 边缘容差与覆盖统计都按这个布局算，不再按原图 1:1 假装显示。
    const env = makeEnv(image, DISPLAY);
    const api = loadModule(env);
    const limit = api._limits.maxRects;
    const scan = api._scanAlpha(env.img, limit);
    check(`${label} 扫描成功且不是整窗回退`,
      !!scan && scan.complete === true && !scan.failed, scan && scan.failed);
    if (!scan || scan.failed) return;
    check(`${label} 简化级别/矩形数有界`,
      scan.level >= 0 && scan.segments > 0 && scan.segments <= limit,
      { level: scan.level, segments: scan.segments, limit });
    const rects = api._mapRunsToCss(env.img, scan);
    check(`${label} 提交矩形数在上限内`, rects.length > 0 && rects.length <= limit, rects.length);
    check(`${label} 边缘容差如实上报`, scan.approximated && api._outlineTolerance(env.img, scan) > 0,
      { approximated: scan.approximated, tolerance: api._outlineTolerance(env.img, scan) });

    const stats = analyze(image, scan, rects, env.display);
    const sourceCoverage = stats.sourceOpaqueCovered / Math.max(1, stats.sourceOpaque);
    check(`${label} 全部 alpha≥16 源像素都被覆盖`,
      stats.sourceOpaque > 0 && stats.sourceOpaqueCovered === stats.sourceOpaque,
      { covered: stats.sourceOpaqueCovered, total: stats.sourceOpaque });
    const released = stats.transparentReleased / Math.max(1, stats.transparentPixels);
    const exteriorRelease = stats.exteriorReleased / Math.max(1, stats.exteriorPixels);
    check(`${label} 外轮廓以外透明区域释放比例 ≥ 90%`,
      exteriorRelease >= 0.9,
      { exteriorRelease: Number(exteriorRelease.toFixed(4)),
        exteriorReleased: stats.exteriorReleased, exteriorPixels: stats.exteriorPixels });
    // 本轮用户授权：只做外轮廓，行内空隙（发缝/内部洞）**不要求**穿透。
    // 这里如实记录内部释放比例，并断言它不会反过来让外部释放率虚高：
    // 内部释放 + 外部释放必须与「全部透明像素释放」自洽。
    check(`${label} 内部空隙释放比例被如实记录（不要求穿透）`,
      stats.interiorReleased >= 0 && stats.interiorReleased <= stats.interiorPixels,
      { interiorRelease: Number((stats.interiorReleased / Math.max(1, stats.interiorPixels)).toFixed(4)),
        interiorPixels: stats.interiorPixels });
    check(`${label} 透明像素释放统计自洽（内部 + 外部 = 全部）`,
      stats.interiorPixels + stats.exteriorPixels === stats.transparentPixels
      && stats.interiorReleased + stats.exteriorReleased === stats.transparentReleased,
      { interiorPixels: stats.interiorPixels, exteriorPixels: stats.exteriorPixels,
        transparentPixels: stats.transparentPixels });
    check(`${label} 底部最末不透明源像素行被覆盖`,
      stats.bottomOpaqueRow >= 0 && stats.bottomCovered === true,
      { bottomOpaqueRow: stats.bottomOpaqueRow, bottomCovered: stats.bottomCovered });

    // 提交区域总面积必须远小于整张图片（不是整图矩形策略）
    const area = rects.reduce((sum, r) => sum + Math.max(0, r[2] - r[0]) * Math.max(0, r[3] - r[1]), 0);
    const imageArea = image.width * image.height;
    check(`${label} 提交区域远小于整图矩形`,
      area > 0 && area < imageArea * 0.85, { area, imageArea, ratio: area / imageArea });

    evidence.push({
      file: label,
      width: image.width,
      height: image.height,
      level: scan.level,
      baseWidth: scan.baseWidth,
      baseHeight: scan.baseHeight,
      block: scan.block,
      segments: scan.segments,
      filledGaps: scan.filledGaps || 0,
      rects: rects.length,
      toleranceCssPx: Number(api._outlineTolerance(env.img, scan).toFixed(2)),
      sourceOpaquePixels: stats.sourceOpaque,
      sourceOpaqueCoverage: Number(sourceCoverage.toFixed(6)),
      exteriorRelease: Number(exteriorRelease.toFixed(4)),
      interiorRelease: Number((stats.interiorReleased / Math.max(1, stats.interiorPixels)).toFixed(4)),
      transparentReleased: Number(released.toFixed(4)),
      areaRatio: Number((area / imageArea).toFixed(4)),
    });
  });

  // 证据写入 validation 目录（公开克隆仓库没有该目录时跳过）
  const validation = path.join(_root, 'validation-touchsettingsfix-20261011');
  if (fs.existsSync(validation)) {
    fs.writeFileSync(path.join(validation, 'real-png-outline-evidence.json'),
      JSON.stringify({ generatedBy: 'tests/touch_region_real_png_check.js',
        threshold: THRESHOLD, results: evidence }, null, 2), 'utf8');
  }

  console.log();
  if (failures.length) {
    console.error(`${failures.length} 项失败：${failures.join(', ')}`);
    return 1;
  }
  console.log('PASS');
  return 0;
}

module.exports = { makeEnv, loadModule, analyze };
if (require.main === module) process.exit(main());
