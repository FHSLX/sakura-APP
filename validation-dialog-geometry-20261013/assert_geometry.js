'use strict';
/*
 * 对话几何的独立断言 CLI（不依赖浏览器，只吃 DOM JSON）。
 *
 * 输入：fixture 页面 POST/落盘的 geometry-results.json（{cases:[...]}），
 *       或任何一个形如 [{case,caseInput,measured,requests,error}] 的数组。
 * 输出：逐项 PASS/FAIL，失败 exit 1。
 *
 * 覆盖：卡片左右不裁切（含正负偏移）、翻页按钮在卡片内、默认居中、
 * 缩放只应用一次、缩放线性、默认紧邻立绘、长文本/大字号不溢出、
 * 隐藏再显示无收缩循环、原生窗口 clamp 范围。
 *
 * 也可作为模块使用：require('./assert_geometry.js').assertCases(cases)
 * 返回失败项数组（供 assert_geometry_selftest.js 用合成 DOM JSON 验证 CLI 本身）。
 *
 * 用法: node validation-dialog-geometry-20261013/assert_geometry.js [results.json]
 */
'use strict';

const fs = require('fs');
const path = require('path');

function assertCases(cases, log) {
  const output = typeof log === 'function' ? log : () => { };
  const failures = [];
  function check(name, condition, detail) {
    output(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
      + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
    if (!condition) failures.push(name);
  }
  function within(value, min, max) {
    return value >= min && value <= max;
  }

  const byName = {};
  cases.forEach((item) => { byName[item.case] = item; });
  check('用例数量 >= 10', cases.length >= 10, cases.length);

  cases.forEach((item) => {
    const m = item.measured || {};
    check(`${item.case}: 无驱动错误`, !item.error, item.error);
    check(`${item.case}: 拿到卡片几何`, !!(m.card && m.card.w > 0), m.card);
    check(`${item.case}: 拿到立绘几何`, !!(m.wrap && m.stage), { wrap: m.wrap, stage: m.stage });
  });

  cases.forEach((item) => {
    const m = item.measured || {};
    if (!m.card || !m.viewportW) return;
    check(`${item.case}: 卡片左右不被裁切`,
      m.card.l >= -0.5 && m.card.r <= m.viewportW + 0.5,
      { left: m.card.l, right: m.card.r, vw: m.viewportW });
    check(`${item.case}: 卡片具有可读高度`, m.card.h >= 48, m.card);
    if(m.composer) {
      check(`${item.case}: 卡片不压住输入栏`, m.card.b <= m.composer.t + .5, {card:m.card,composer:m.composer});
      check(`${item.case}: 输入栏完整在窗口内`, m.composer.b <= m.viewportH + .5, m.composer);
    }
  });

  cases.forEach((item) => {
    const m = item.measured || {};
    if (!m.card || !m.viewportH) return;
    check(`${item.case}: 卡片上下不被裁切`,
      m.card.t >= -0.5 && m.card.b <= m.viewportH + 0.5,
      { top: m.card.t, bottom: m.card.b, vh: m.viewportH });
  });

  cases.forEach((item) => {
    const m = item.measured || {};
    const input = item.caseInput || {};
    if (!m.card || !m.composer || input.hidden) return;
    check(`${item.case}: 输入栏不被卡片遮挡`,
      m.card.b <= m.composer.t + 1 && m.composer.b <= m.viewportH + 0.5,
      { cardBottom: m.card.b, composerTop: m.composer.t, composerBottom: m.composer.b });
  });

  cases.forEach((item) => {
    const m = item.measured || {};
    if (!m.card || !m.prev || !m.next) return;
    check(`${item.case}: 上下翻页按钮在卡片内`,
      m.prev.l >= m.card.l - 0.5 && m.next.r <= m.card.r + 0.5,
      { prev: m.prev, next: m.next, card: m.card });
  });

  ['393-tall-scale1', '320-tall-scale1', '393-wide-scale1', '393-square-scale1'].forEach((name) => {
    const m = (byName[name] || {}).measured || {};
    if (!m.card) return;
    const center = m.card.l + m.card.w / 2;
    check(`${name}: 默认偏移卡片居中`,
      Math.abs(center - m.viewportW / 2) <= 1.5, { center, vw: m.viewportW });
  });

  cases.forEach((item) => {
    const m = item.measured || {};
    if (!m.wrap || !m.stage) return;
    check(`${item.case}: 缩放只应用一次（wrap 高度==stage 高度）`,
      Math.abs(m.wrap.h - m.stage.h) <= 1.5,
      { wrapH: m.wrap.h, stageH: m.stage.h });
  });

  const baseH = (byName['393-tall-scale1'] || {}).measured;
  const smallH = (byName['393-tall-scale0.5'] || {}).measured;
  const bigH = (byName['393-tall-scale1.5'] || {}).measured;
  if (baseH && smallH && bigH) {
    check('scale=0.5 可视高度约为 1x 的一半',
      within(smallH.wrap.h / baseH.wrap.h, 0.45, 0.55),
      { ratio: smallH.wrap.h / baseH.wrap.h });
    check('scale=1.5 高度单调增加并受屏幕预算限制',
      within(bigH.wrap.h / baseH.wrap.h, 0.99, 1.58),
      { ratio: bigH.wrap.h / baseH.wrap.h });
  } else {
    check('scale 对比用例齐全', false, Object.keys(byName));
  }

  cases.forEach((item) => {
    const m = item.measured || {};
    const input = item.caseInput || {};
    if (!m.card || !m.stage || input.dx || input.dy) return;
    check(`${item.case}: 卡片默认紧邻立绘`,
      Math.abs(m.card.t - m.stage.b) <= 1.5,
      { cardTop: m.card.t, stageBottom: m.stage.b });
  });

  ['393-long-text', '393-font-150'].forEach((name) => {
    const m = (byName[name] || {}).measured || {};
    check(`${name}: 文字不横向溢出`, m.textOverflow !== null && m.textOverflow <= 0.5, m.textOverflow);
  });

  const hidden = (byName['393-hidden-reopen'] || {}).measured || {};
  check('隐藏时卡片高度收为 0', hidden.hiddenCardH !== undefined && hidden.hiddenCardH <= 0.5,
    hidden.hiddenCardH);
  check('重开后卡片可见', hidden.reopenedCardH > 8, hidden.reopenedCardH);
  if (baseH) {
    check('重开后 stage 高度与常显一致（无收缩循环）',
      hidden.reopenedStageH !== undefined
        && Math.abs(hidden.reopenedStageH - baseH.stage.h) <= 2,
      { reopened: hidden.reopenedStageH, normal: baseH.stage.h });
  }

  cases.forEach((item) => {
    const requests = item.requests || [];
    const input = item.caseInput || {};
    if (!requests.length || !input.devW) return;
    const last = requests[requests.length - 1];
    check(`${item.case}: 原生窗口 clamp 在 [160, 设备宽]`,
      last.cssW >= 160 && last.cssW <= (input.devW + 1)
      && last.cssH >= 160 && last.cssH <= (input.devH + 1), last);
  });

  return failures;
}

function loadCases(file) {
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (Array.isArray(raw)) return raw;
  if (raw && Array.isArray(raw.cases)) return raw.cases;
  throw new Error('unexpected results shape: expected array or {cases:[...]}');
}

function main() {
  const inputPath = process.argv[2] || path.join(__dirname, 'geometry-results.json');
  if (!fs.existsSync(inputPath)) {
    console.error(`missing results file: ${inputPath}`);
    console.error('run the fixture in a browser first (node validation-dialog-geometry-20261013/fixture_server.js)');
    process.exit(2);
  }
  const cases = loadCases(inputPath);
  const failures = assertCases(cases, (line) => console.log(line));
  console.log();
  if (failures.length) {
    console.error(`${failures.length} 项失败：${failures.join(', ')}`);
    process.exit(1);
  }
  console.log(`PASS (browser DOM JSON: ${inputPath})`);
}

module.exports = { assertCases, loadCases };

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error('assert_geometry error:', error);
    process.exit(2);
  }
}
