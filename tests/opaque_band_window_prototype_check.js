// 阶段 B 原型评估：用**公开 WindowManager 的有界数量不透明窗口**替代 alpha 安全阈值。
//
// 背景（为什么需要这个评估）
// ---------------------------------------------------------------
// Android 12+ 的跨应用遮挡判定按**窗口框**（frameContainsPoint）取样，输入 Region
// 里的孔洞不会缩小遮挡窗口框。因此「一个矩形浮窗 + 局部输入 Region」必然要在
// 「整窗不透明」和「透明处能点下层」之间二选一：要么接受窗口 alpha 被降到系统
// 上限（用户看到的「变淡」），要么放弃一部分穿透。
//
// 阶段 B 的候选是把人物像素切成一串**真实裁切范围**的不透明窗口：透明空隙落在
// 所有可见窗口框之外，于是既不遮挡（不需要降 alpha），也不吃触摸。窗口框本身
// 必须完全落在「不透明像素」集合内，否则那块窗口会画出不该有的像素。
//
// 因此这条路线能不能成立，只取决于一个纯几何问题：
//   **把立绘的不透明区域无损分解成少量矩形，到底需要多少个窗口？**
// 本脚本在**本机已安装的真实立绘 PNG**（只读像素 alpha，不导出、不进代码/提交/
// 发布包）和一组**确定性合成镂空字形**上回答这个问题，并给出窗口预算不足时的
// 漏覆盖/误覆盖代价与耗时。
//
// 算法
// ---------------------------------------------------------------
//   A. exactCover：把 mask 精确分解为「框内全是 mask」的矩形（漏覆盖 = 0、
//      误覆盖 = 0）。相邻的 valid/cutoff 矩形再反复做竖直/水平合并，得到窗口数。
//   B. boundedCover：在「最多 N 个窗口」的预算下贪心挑最大面积矩形，给出
//      漏覆盖（人物被切掉）比例。
//   C. bandOutline：现有生产实现（overlay_touch_region.js）的保守轮廓，作为
//      「误覆盖容差」的对照基线。
//
// 用法:
//   node tests/opaque_band_window_prototype_check.js
//   node tests/opaque_band_window_prototype_check.js --json out.json --budgets 16,24,48
//   node tests/opaque_band_window_prototype_check.js --limit-ms 20000
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

function argList(name, fallback) {
  const raw = argValue(name, '');
  if (!raw) return fallback;
  return raw.split(',').filter(Boolean).map(Number);
}

const BUDGETS = argList('--budgets', [16, 24, 48]);
const JSON_OUT = argValue('--json', '');
const LIMIT_MS = Number(argValue('--limit-ms', '20000'));
const MAX_RECTS = Number(argValue('--max-rects', '200000'));
const ALPHA_THRESHOLD = Number(argValue('--alpha-threshold', '16'));

const { decodePngAlpha } = require('./png_alpha.js');

/* ------------------------------------------------------------------ *
 * mask 工具
 * ------------------------------------------------------------------ */

function maskFromAlpha(alpha, width, height, threshold) {
  const mask = new Uint8Array(width * height);
  let opaque = 0;
  for (let i = 0; i < mask.length; i += 1) {
    if (alpha[i] >= threshold) { mask[i] = 1; opaque += 1; }
  }
  return { mask, width, height, opaque };
}

/** 现有生产实现的逐行扫描 + 相邻相同行段合并（block=1，设备像素级）。 */
function bandOutlineRectCount(mask, width, height) {
  const rowRuns = [];
  let segments = 0;
  for (let y = 0; y < height; y += 1) {
    const runs = [];
    let x = 0;
    const base = y * width;
    while (x < width) {
      while (x < width && !mask[base + x]) x += 1;
      if (x >= width) break;
      const start = x;
      while (x < width && mask[base + x]) x += 1;
      runs.push([start, x]);
      segments += 1;
    }
    rowRuns.push(runs);
  }
  const same = (a, b) => {
    if (a === b) return true;
    if (!a || !b || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i += 1) if (a[i][0] !== b[i][0] || a[i][1] !== b[i][1]) return false;
    return true;
  };
  let rects = 0;
  let bandStart = -1;
  let bandRuns = null;
  for (let y = 0; y < height; y += 1) {
    if (bandStart < 0) { bandStart = y; bandRuns = rowRuns[y]; continue; }
    if (same(bandRuns, rowRuns[y])) continue;
    if (bandRuns) rects += bandRuns.length;
    bandStart = y;
    bandRuns = rowRuns[y];
  }
  if (bandRuns) rects += bandRuns.length;
  return { rects, segments };
}

/* ------------------------------------------------------------------ *
 * A. 无损分解 + 窗口数下界
 * ------------------------------------------------------------------ */

/**
 * **与算法无关的下界**：任何无损分解里，某一行的每个极大不透明行段都必须
 * 至少落在一个矩形窗口内（矩形窗口在某一行的截面是单个区间），所以窗口数
 * ≥ max(每行的极大行段数)。这条下界不依赖分解算法，无法靠更聪明的切法绕过。
 */
function windowLowerBound(mask, width, height) {
  const perRow = new Int32Array(height);
  let maxRuns = 0;
  let maxRow = 0;
  for (let y = 0; y < height; y += 1) {
    const base = y * width;
    let runs = 0;
    let x = 0;
    while (x < width) {
      while (x < width && !mask[base + x]) x += 1;
      if (x >= width) break;
      runs += 1;
      while (x < width && mask[base + x]) x += 1;
    }
    perRow[y] = runs;
    if (runs > maxRuns) { maxRuns = runs; maxRow = y; }
  }
  return { maxRuns, maxRow, perRow };
}

/**
 * 无损竖直带分解（与生产实现同一族）：相邻行里 x 区间完全相同的行段并成一条
 * 更高的矩形；再做一轮水平合并（y 区间相同且 x 首尾相接）。
 *
 * 每个矩形都严格取自同一段连续不透明像素，因此**误覆盖必然为 0**；
 * 全部不透明像素都被某个矩形覆盖，因此**漏覆盖必然为 0**。两者都会在下面
 * 用独立的前缀和/标记数组复核，不靠算法自觉。
 */
function bandDecompose(mask, width, height) {
  const started = Date.now();
  const rects = [];
  let open = new Map();     // "x0:x1" -> { y0, y1 }
  for (let y = 0; y < height; y += 1) {
    const base = y * width;
    const runs = [];
    let x = 0;
    while (x < width) {
      while (x < width && !mask[base + x]) x += 1;
      if (x >= width) break;
      const x0 = x;
      while (x < width && mask[base + x]) x += 1;
      runs.push(x0 + ':' + x);
    }
    const seen = new Set(runs);
    // 结束不再延续的带
    const toClose = [];
    open.forEach((span, key) => {
      if (!seen.has(key)) { toClose.push(key); }
    });
    for (const key of toClose) {
      const span = open.get(key);
      span.y1 = y;
      const sep = key.indexOf(':');
      rects.push([Number(key.slice(0, sep)), span.y0, Number(key.slice(sep + 1)), span.y1]);
      open.delete(key);
    }
    // 开启新的带
    for (const key of runs) {
      if (!open.has(key)) open.set(key, { y0: y, y1: y + 1 });
      else open.get(key).y1 = y + 1;
    }
  }
  open.forEach((span, key) => {
    const sep = key.indexOf(':');
    rects.push([Number(key.slice(0, sep)), span.y0, Number(key.slice(sep + 1)), span.y1]);
  });
  const decomposeMs = Date.now() - started;

  // 水平合并：y 区间相同且 x 首尾相接
  const mergeStarted = Date.now();
  const byRow = new Map();
  for (let i = 0; i < rects.length; i += 1) {
    const key = rects[i][1] + ':' + rects[i][3];
    const list = byRow.get(key);
    if (list) list.push(i); else byRow.set(key, [i]);
  }
  const dead = new Uint8Array(rects.length);
  byRow.forEach((list) => {
    list.sort((a, b) => rects[a][0] - rects[b][0]);
    for (let k = 0; k + 1 < list.length; k += 1) {
      const a = rects[list[k]];
      const b = rects[list[k + 1]];
      if (dead[list[k]] || dead[list[k + 1]]) continue;
      if (a[2] !== b[0]) continue;
      a[2] = b[2];
      dead[list[k + 1]] = 1;
    }
  });
  const mergedRects = rects.filter((_, i) => !dead[i]);
  return { rects: mergedRects, bandsBeforeMerge: rects.length, decomposeMs, mergeMs: Date.now() - mergeStarted };
}

/** 复核分解结果：误覆盖必须为 0、漏覆盖必须为 0。 */
function verifyCover(rects, mask, width, height) {
  const covered = new Uint8Array(width * height);
  let stray = 0;
  let area = 0;
  for (const [x0, y0, x1, y1] of rects) {
    area += (x1 - x0) * (y1 - y0);
    for (let y = y0; y < y1; y += 1) {
      const base = y * width;
      for (let x = x0; x < x1; x += 1) {
        if (!mask[base + x]) stray += 1;
        covered[base + x] = 1;
      }
    }
  }
  let opaque = 0;
  let missing = 0;
  for (let i = 0; i < mask.length; i += 1) {
    if (!mask[i]) continue;
    opaque += 1;
    if (!covered[i]) missing += 1;
  }
  return { stray, missing, opaque, area };
}

/* ------------------------------------------------------------------ *
 * B. 预算受限：最多 N 个窗口时能覆盖多少不透明像素
 * ------------------------------------------------------------------ */

function boundedCover(mask, width, height, budget, limitMs) {
  const started = Date.now();
  const claimed = new Uint8Array(width * height);
  const heights = new Int32Array(width);
  const rects = [];
  let covered = 0;
  let rounds = 0;
  let opaque = 0;
  for (let i = 0; i < mask.length; i += 1) if (mask[i]) opaque += 1;

  while (rects.length < budget && Date.now() - started < limitMs) {
    rounds += 1;
    // 每轮重算柱状高度并取面积最大的「框内全是未覆盖 mask」矩形
    for (let x = 0; x < width; x += 1) heights[x] = 0;
    let best = null;
    for (let y = 0; y < height; y += 1) {
      const base = y * width;
      for (let x = 0; x < width; x += 1) {
        heights[x] = mask[base + x] && !claimed[base + x] ? heights[x] + 1 : 0;
      }
      const stack = [];
      for (let x = 0; x <= width; x += 1) {
        const h = x === width ? 0 : heights[x];
        let start = x;
        while (stack.length && stack[stack.length - 1].h >= h) {
          const top = stack.pop();
          start = top.x;
          const area = top.h * (x - top.x);
          if (top.h > 0 && (!best || area > best[4])) {
            best = [start, y - top.h + 1, x, y + 1, area];
          }
        }
        stack.push({ x: start, h });
      }
    }
    if (!best) break;
    for (let y = best[1]; y < best[3]; y += 1) {
      const base = y * width;
      for (let x = best[0]; x < best[2]; x += 1) {
        if (!claimed[base + x]) { claimed[base + x] = 1; covered += 1; }
      }
    }
    rects.push(best);
  }
  let stray = 0;
  for (const [x0, y0, x1, y1] of rects) {
    for (let y = y0; y < y1; y += 1) {
      const base = y * width;
      for (let x = x0; x < x1; x += 1) if (!mask[base + x]) stray += 1;
    }
  }
  return {
    windows: rects.length,
    rounds,
    coveredOpaque: covered,
    opaque,
    coveredRatio: Number((covered / Math.max(1, opaque)).toFixed(4)),
    missingOpaque: opaque - covered,
    missingRatio: Number(((opaque - covered) / Math.max(1, opaque)).toFixed(4)),
    strayPixels: stray,
    elapsedMs: Date.now() - started,
  };
}

/* ------------------------------------------------------------------ *
 * 确定性合成镂空字形（最坏情况压力测试，不依赖外部字体文件）
 * ------------------------------------------------------------------ */

function glyphMask(size) {
  const strokes = [];
  const bar = (x, y, w, h) => strokes.push([x, y, x + w, y + h]);
  bar(0.08, 0.10, 0.30, 0.045);
  bar(0.08, 0.28, 0.30, 0.045);
  bar(0.08, 0.46, 0.30, 0.045);
  bar(0.19, 0.10, 0.045, 0.42);
  bar(0.46, 0.12, 0.44, 0.045);
  bar(0.46, 0.28, 0.44, 0.045);
  bar(0.46, 0.44, 0.44, 0.045);
  bar(0.46, 0.60, 0.44, 0.045);
  bar(0.46, 0.76, 0.44, 0.045);
  bar(0.50, 0.12, 0.045, 0.42);
  bar(0.82, 0.12, 0.045, 0.42);
  bar(0.60, 0.62, 0.045, 0.30);
  bar(0.72, 0.62, 0.045, 0.30);
  bar(0.60, 0.88, 0.165, 0.045);
  for (let i = 0; i < 26; i += 1) {
    const t = i / 25;
    bar(0.30 + t * 0.16, 0.52 + t * 0.44, 0.035, 0.035);
  }
  const mask = new Uint8Array(size * size);
  for (const [x0, y0, x1, y1] of strokes) {
    const px0 = Math.round(x0 * size);
    const px1 = Math.round(x1 * size);
    const py0 = Math.round(y0 * size);
    const py1 = Math.round(y1 * size);
    for (let y = py0; y < py1 && y < size; y += 1) {
      for (let x = px0; x < px1 && x < size; x += 1) {
        if (x >= 0 && y >= 0) mask[y * size + x] = 1;
      }
    }
  }
  let opaque = 0;
  for (let i = 0; i < mask.length; i += 1) if (mask[i]) opaque += 1;
  return { mask, width: size, height: size, opaque };
}

/* ------------------------------------------------------------------ *
 * 主流程
 * ------------------------------------------------------------------ */

const REAL_ROOT = 'F:/sakura/characters';
const REAL_CANDIDATES = [
  'Sakura/portraits/A020.png',
  'Sakura/portraits/A081.png',
  'Sakura/portraits/E050.png',
  'nikaidou_shinku/portrait/default/01_neutral.png',
  'kuro_sakuramoyu/portrait/default/01_neutral.png',
  'nero_claudius/portrait/default/01_neutral.png',
];

/**
 * 单图评估：无损分解窗口数、与算法无关的窗口数下界、预算受限时的人物覆盖率。
 */
function analyse(name, info, real) {
  const { mask, width, height, opaque } = info;
  const band = bandOutlineRectCount(mask, width, height);
  const lower = windowLowerBound(mask, width, height);
  const decompose = bandDecompose(mask, width, height);
  const verify = verifyCover(decompose.rects, mask, width, height);
  const bounded = {};
  for (const budget of BUDGETS) {
    bounded[budget] = boundedCover(mask, width, height, budget, LIMIT_MS);
  }
  return {
    name, real, width, height,
    opaquePixels: opaque,
    opaqueRatio: Number((opaque / (width * height)).toFixed(4)),
    lowerBoundWindows: lower.maxRuns,
    lowerBoundRow: lower.maxRow,
    losslessWindows: decompose.rects.length,
    losslessBandsBeforeMerge: decompose.bandsBeforeMerge,
    losslessStrayPixels: verify.stray,
    losslessMissingPixels: verify.missing,
    windowAreaVsOpaque: Number((verify.area / Math.max(1, opaque)).toFixed(3)),
    decomposeMs: decompose.decomposeMs,
    mergeMs: decompose.mergeMs,
    bandOutlineRects: band.rects,
    bandSegments: band.segments,
    bounded,
  };
}

function main() {
  console.log('阶段 B 原型：不透明矩形带窗口的精确分解成本评估');
  console.log(`预算（窗口数）: ${BUDGETS.join(', ')}   单图时限: ${LIMIT_MS}ms   alpha 阈值: >= ${ALPHA_THRESHOLD}`);
  console.log();

  const cases = [];
  const realAvailable = [];
  for (const candidate of REAL_CANDIDATES) {
    const full = path.join(REAL_ROOT, candidate);
    if (!fs.existsSync(full)) continue;
    let decoded;
    try {
      decoded = decodePngAlpha(full);
    } catch (error) {
      console.log(`[SKIP] ${candidate}: ${error.message}`);
      continue;
    }
    realAvailable.push(candidate);
    cases.push({
      name: `真实立绘 ${candidate}`,
      info: maskFromAlpha(decoded.alpha, decoded.width, decoded.height, ALPHA_THRESHOLD),
      real: true,
    });
  }
  if (!realAvailable.length) {
    console.log('[SKIP] 本机没有已安装的真实立绘（公开克隆仓库不含角色资源）');
  }
  cases.push({ name: '合成镂空字形 256×256', info: glyphMask(256), real: false });
  cases.push({ name: '合成镂空字形 512×512', info: glyphMask(512), real: false });

  const results = [];
  for (const item of cases) {
    console.log(`=== ${item.name} ===`);
    const result = analyse(item.name, item.info, item.real);
    results.push(result);
    console.log(`  ${result.width}×${result.height}，不透明 ${result.opaquePixels} px`
      + `（${(result.opaqueRatio * 100).toFixed(1)}%）`);
    console.log(`  无损分解窗口数 = ${result.losslessWindows}`
      + `（原始 ${result.losslessBandsBeforeMerge} 条竖直带 -> 水平合并；`
      + `分解 ${result.decomposeMs}ms + 合并 ${result.mergeMs}ms）`);
    console.log(`  校验：漏覆盖 ${result.losslessMissingPixels} px，误覆盖 ${result.losslessStrayPixels} px`
      + `（两者都必须为 0 才成立）`);
    console.log(`  与算法无关的窗口数下界 = ${result.lowerBoundWindows}（第 ${result.lowerBoundRow} 行的极大行段数）`);
    console.log(`  窗口总面积 / 不透明面积 = ${result.windowAreaVsOpaque}`);
    console.log(`  现有保守轮廓对照：${result.bandOutlineRects} 个矩形（${result.bandSegments} 个行段）`);
    for (const budget of BUDGETS) {
      const b = result.bounded[budget];
      console.log(`  预算 ${budget} 窗口：覆盖人物 ${(b.coveredRatio * 100).toFixed(2)}%，`
        + `切掉 ${b.missingOpaque} px（${(b.missingRatio * 100).toFixed(2)}%），误覆盖 ${b.strayPixels} px`
        + `，贪心轮数 ${b.rounds}，耗时 ${b.elapsedMs}ms`);
    }
    console.log();
  }

  console.log('--- 结论依据 ---');
  if (results.length) {
    const counts = results.map((r) => r.losslessWindows);
    const bounds = results.map((r) => r.lowerBoundWindows);
    console.log(`无损分解所需窗口数：${Math.min(...counts)} – ${Math.max(...counts)}`
      + `；与算法无关的下界：${Math.min(...bounds)} – ${Math.max(...bounds)}`
      + `（目标上限 ${BUDGETS[0]}–${BUDGETS[BUDGETS.length - 1]}）`);
    const real = results.filter((r) => r.real);
    if (real.length) {
      console.log(`仅真实立绘：无损 ${Math.min(...real.map((r) => r.losslessWindows))} – `
        + `${Math.max(...real.map((r) => r.losslessWindows))} 个窗口；`
        + `下界 ${Math.min(...real.map((r) => r.lowerBoundWindows))} – `
        + `${Math.max(...real.map((r) => r.lowerBoundWindows))}`);
    }
    for (const budget of BUDGETS) {
      const ratios = results.map((r) => r.bounded[budget].coveredRatio);
      if (!ratios.length) continue;
      console.log(`预算 ${budget} 窗口时，各图人物覆盖率 `
        + ratios.map((v) => (v * 100).toFixed(1) + '%').join(' / '));
    }
  }

  if (JSON_OUT) {
    const target = path.resolve(JSON_OUT);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, JSON.stringify({
      budgets: BUDGETS,
      alphaThreshold: ALPHA_THRESHOLD,
      limitMs: LIMIT_MS,
      realPortraitsFound: realAvailable,
      results,
    }, null, 2), 'utf8');
    console.log(`已写出: ${path.relative(ROOT, target)}`);
  }
}

main();
