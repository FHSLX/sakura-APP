'use strict';
// 矩形预算对照：真实立绘 PNG，在**真实手机显示框**（393×700 CSS）下逐预算测量。
//
// 与 tests/outer_contour_measure.js 同一口径：覆盖统计按提交矩形的真实几何逐像素
// 判定（不做格点近似），因此不会出现「真实 100% 覆盖被误报成 4%」的假失败。
const { makeEnv, loadModule, analyze } = require('./touch_region_real_png_check');
const { decodePngAlpha } = require('./png_alpha');
const fs = require('fs');

/** 与真机一致的竖屏视口（CSS 像素）。 */
const DISPLAY = { width: 393, height: 700 };
const results = [];
for (const fixture of ['Sakura/portraits/A020.png', 'Sakura/portraits/E050.png',
  'Sakura/portraits/A081.png', 'nikaidou_shinku/portrait/default/01_neutral.png',
  'kuro_sakuramoyu/portrait/default/01_neutral.png']) {  const file = 'F:/sakura/characters/' + fixture;
  if (!fs.existsSync(file)) continue;
  const image = decodePngAlpha(file);
  for (const budget of [1200, 256, 192, 128, 96]) {
    const env = makeEnv(image, DISPLAY);
    const api = loadModule(env);
    const start = performance.now();
    const scan = api._scanAlpha(env.img, budget);
    const rects = api._mapRunsToCss(env.img, scan);
    const elapsed = performance.now() - start;
    const coverage = analyze(image, scan, rects, DISPLAY);
    if (coverage.sourceOpaque !== coverage.sourceOpaqueCovered) throw Error('Foreground lost');
    if (rects.length > budget) throw Error('Budget exceeded');
    const interiorRelease = coverage.interiorPixels
      ? coverage.interiorReleased / coverage.interiorPixels : 0;
    const exteriorRelease = coverage.exteriorPixels
      ? coverage.exteriorReleased / coverage.exteriorPixels : 1;
    results.push({
      fixture,
      requestedBudget: budget,
      effectiveBudget: Math.min(budget, api._limits.maxRects),
      rects: rects.length,
      level: scan.level,
      filledGaps: scan.filledGaps || 0,
      elapsedMs: +elapsed.toFixed(2),
      opaqueCoverage: 1,
      exteriorRelease: +exteriorRelease.toFixed(4),
      interiorRelease: +interiorRelease.toFixed(4),
      displayToleranceCssPx: +api._outlineTolerance(env.img, scan).toFixed(2),
    });
  }
}
console.log(JSON.stringify(results, null, 2));
if (!results.length) console.log('SKIP: installed visual fixtures absent');
else console.log('PASS: all budgets retain all opaque source pixels');
