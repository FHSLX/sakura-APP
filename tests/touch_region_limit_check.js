// 轮廓简化预算的一致性检查（TS-01 / TS-04）：
//   前端（overlay_touch_region.js）与原生（OverlayTouchRegionController.java）
//   的矩形数 / 载荷长度 / 视口上限必须一致，且前端更保守；最粗聚合级别必须
//   保证矩形数有**硬上限**（否则又回到「整窗回退」）。
//
// 纯静态核对（读源码常量），不需要设备或网络。
// 用法: node tests/touch_region_limit_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml')) ? _root : path.join(_root, 'sakura_remote');
const jsPath = path.join(PLUGIN_ROOT, 'static', 'overlay_touch_region.js');
const javaPath = path.join(_root, 'phone_app', 'android', 'app', 'src', 'main',
  'java', 'com', 'sakura', 'remote', 'OverlayTouchRegionController.java');

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

function jsConst(source, name) {
  const match = source.match(new RegExp('var\\s+' + name + '\\s*=\\s*([^;]+);'));
  if (!match) return null;
  // eslint-disable-next-line no-new-func
  try { return new Function('return (' + match[1] + ');')(); } catch (error) { return null; }
}

function javaInt(source, name) {
  const match = source.match(new RegExp('static\\s+final\\s+int\\s+' + name + '\\s*=\\s*([0-9]+)'));
  return match ? Number(match[1]) : null;
}

function javaFloat(source, name) {
  const match = source.match(new RegExp('static\\s+final\\s+float\\s+' + name + '\\s*=\\s*([0-9.]+)f'));
  return match ? Number(match[1]) : null;
}

const js = fs.readFileSync(jsPath, 'utf8');
const java = fs.readFileSync(javaPath, 'utf8');

const jsMaxRects = jsConst(js, 'MAX_RECTS');
const jsMaxPayload = jsConst(js, 'MAX_PAYLOAD_BYTES');
const jsMaxSegments = jsConst(js, 'MAX_SEGMENTS');
const jsMaxLevel = jsConst(js, 'MAX_LEVEL');
const jsScanW = jsConst(js, 'MAX_SCAN_W');
const jsScanH = jsConst(js, 'MAX_SCAN_H');
const jsTilePixels = jsConst(js, 'MAX_TILE_PIXELS');
const jsSourcePixels = jsConst(js, 'MAX_SCAN_SOURCE_PIXELS');

const nativeMaxRects = javaInt(java, 'MAX_RECTS');
const nativePayloadChars = javaInt(java, 'MAX_PAYLOAD_CHARS');
const nativeMaxLevel = javaInt(java, 'MAX_LEVEL');
const nativeMaxViewport = javaFloat(java, 'MAX_VIEWPORT');

check('前端常量可解析', [jsMaxRects, jsMaxPayload, jsMaxSegments, jsMaxLevel, jsScanW, jsScanH]
  .every((value) => typeof value === 'number' && value > 0),
  { jsMaxRects, jsMaxPayload, jsMaxSegments, jsMaxLevel, jsScanW, jsScanH });
check('瓦片/源图预算常量可解析', [jsTilePixels, jsSourcePixels]
  .every((value) => typeof value === 'number' && value > 0),
  { jsTilePixels, jsSourcePixels });
check('每块 canvas/getImageData 像素有上限且不超过源图总预算',
  jsTilePixels <= 2 * 1024 * 1024 && jsTilePixels <= jsSourcePixels,
  { jsTilePixels, jsSourcePixels });
check('原生常量可解析', [nativeMaxRects, nativePayloadChars, nativeMaxLevel, nativeMaxViewport]
  .every((value) => typeof value === 'number' && value > 0),
  { nativeMaxRects, nativePayloadChars, nativeMaxLevel, nativeMaxViewport });

check('前端矩形上限 ≤ 原生矩形上限（前端更保守）',
  jsMaxRects <= nativeMaxRects, { jsMaxRects, nativeMaxRects });
check('前端载荷上限 ≤ 原生载荷上限（前端更保守）',
  jsMaxPayload <= nativePayloadChars, { jsMaxPayload, nativePayloadChars });
check('原生保留视口上限（与前端 cssW/H 校验一致）',
  nativeMaxViewport === 8192, nativeMaxViewport);

// 硬上限证明：最粗级别下网格格子数 ≤ 前端矩形上限。
const coarsestW = Math.ceil(jsScanW / (1 << jsMaxLevel));
const coarsestH = Math.ceil(jsScanH / (1 << jsMaxLevel));
check('最粗聚合级别下矩形数仍有硬上限（不会整窗回退）',
  coarsestW * coarsestH <= jsMaxRects,
  { coarsestW, coarsestH, cells: coarsestW * coarsestH, jsMaxRects });
check('行段预算不小于矩形上限（否则级别选择会过度退化）',
  jsMaxSegments >= jsMaxRects, { jsMaxSegments, jsMaxRects });

// 旧失败路径必须消失：扫描阶段不能再因段数超限而返回 contour-too-complex。
check('扫描阶段不再硬失败为 contour-too-complex',
  !/segments\s*>=\s*MAX_SEGMENTS[\s\S]{0,200}failed\s*:\s*'contour-too-complex'/.test(js)
  && !/capped\s*=\s*true/.test(js),
  'legacy truncation/fallback still present');
check('仍保留真正的安全失败（超大图/扫描失败）',
  js.includes("failSafe('alpha-scan-failed')") && js.includes("'image-too-large'"),
  'safety fallbacks missing');

// 源像素足迹 OR：必须按 [x,x+1) 与网格取交（start floor / endExclusive ceil），
// 不能只落一个整数锚点。
check('扫描按瓦片 1:1 读取原图（不是缩放后最近邻）',
  js.includes('drawImage(img, sx0, sy0, tileCols, tileRows, 0, 0, tileCols, tileRows)'),
  'tiled 1:1 drawImage missing');
check('源像素足迹 OR 到所有相交格子',
  js.includes('Math.ceil((sourceX + 1) * gridW / naturalW)')
  && js.includes('Math.ceil((sourceY + 1) * gridH / naturalH)'),
  'source footprint OR missing');
check('坐标映射保留原网格格宽与 block 末尾边界',
  js.includes('baseWidth') && js.includes('endCellX') && js.includes('endCellY'),
  'base-grid mapping missing');

console.log();
if (failures.length) {
  console.error(`${failures.length} 项失败：${failures.join(', ')}`);
  process.exit(1);
}
console.log('PASS');
