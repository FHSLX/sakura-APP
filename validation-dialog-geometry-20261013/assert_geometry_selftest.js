'use strict';
/*
 * assert_geometry.js 的离线自测：用合成 DOM JSON 证明 CLI 能分辨
 * 「几何正确」与「双重缩放 / 卡片被裁切」。
 *
 * 不含浏览器：只喂数据给独立断言 CLI。
 * 用法: node validation-dialog-geometry-20261013/assert_geometry_selftest.js
 */
'use strict';

const { assertCases } = require('./assert_geometry.js');

let failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

function makeCase(name, opts) {
  const o = Object.assign({
    devW: 393, devH: 852, scale: 1, dx: 0, dy: 0, text: 'short',
    cardW: 178, stageH: 300, viewportW: 393, viewportH: 800,
  }, opts || {});
  const caseInput = { name, devW: o.devW, devH: o.devH, dx: o.dx, dy: o.dy, scale: o.scale, hidden: !!o.hidden };
  const measured = {
    viewportW: o.viewportW, viewportH: o.viewportH, screenW: o.devW, screenH: o.devH,
    stage: { l: 0, t: 300 - o.stageH, r: o.viewportW, b: 300, w: o.viewportW, h: o.stageH },
    wrap: { w: o.cardW, h: o.stageH, t: 300 - o.stageH, b: 300 },
    img: { w: o.cardW, h: o.stageH },
    card: {
      l: o.cardLeft !== undefined ? o.cardLeft : (o.viewportW - o.cardW) / 2,
      t: o.cardTop !== undefined ? o.cardTop : 300,
      w: o.cardW, h: 80,
    },
    prev: { l: 0, r: 20, t: 0, b: 20 },
    next: { l: 0, r: 20, t: 0, b: 20 },
    textOverflow: o.textOverflow === undefined ? 0 : o.textOverflow,
    hiddenCardH: o.hiddenCardH, reopenedCardH: o.reopenedCardH,
    reopenedStageH: o.reopenedStageH,
  };
  measured.card.r = measured.card.l + measured.card.w;
  measured.card.b = measured.card.t + measured.card.h;
  // 输入栏紧接卡片下方且仍在视口内（不遮挡、不裁切）
  measured.composer = {
    l: 8, t: measured.card.b + 1, r: o.viewportW - 8, b: o.viewportH - 4,
  };
  measured.prev = { l: measured.card.l + 2, r: measured.card.l + 22, t: measured.card.t + 2, b: measured.card.t + 22 };
  measured.next = { l: measured.card.r - 22, r: measured.card.r - 2, t: measured.card.t + 2, b: measured.card.t + 22 };
  return {
    case: name, caseInput, measured, error: '',
    requests: [{ wantW: o.cardW + 12, wantH: o.stageH + 100, cssW: o.devW, cssH: o.viewportH }],
  };
}

function passingCases() {
  return [
    makeCase('393-tall-scale1', {}),
    makeCase('393-tall-scale1.5', { scale: 1.5, stageH: 450 }),
    makeCase('393-tall-scale0.5', { scale: 0.5, stageH: 150 }),
    makeCase('393-wide-scale1', { cardW: 300 }),
    makeCase('393-square-scale1', { cardW: 300 }),
    makeCase('320-tall-scale1', { devW: 320, viewportW: 320, cardW: 178 }),
    makeCase('393-offset-negative', { dx: -60, cardLeft: (393 - 178) / 2 - 60 }),
    makeCase('393-offset-positive', { dx: 60, cardLeft: (393 - 178) / 2 + 60 }),
    makeCase('393-long-text', { text: 'long', cardW: 380 }),
    makeCase('393-font-150', { text: 'long', cardW: 380 }),
    makeCase('393-hidden-reopen', { hiddenCardH: 0, reopenedCardH: 44, reopenedStageH: 300 }),
  ];
}

function main() {
  const good = passingCases();
  const goodFailures = assertCases(good, () => { });
  check('CLI 接受几何正确的合成结果', goodFailures.length === 0, goodFailures);

  const doubleScaled = passingCases();
  const target = doubleScaled.find((c) => c.case === '393-tall-scale1.5');
  target.measured.wrap.h = target.measured.stage.h * 1.5;   // 双重缩放
  const scaledFailures = assertCases(doubleScaled, () => { });
  check('CLI 能发现双重缩放',
    scaledFailures.some((n) => n.indexOf('缩放只应用一次') >= 0), scaledFailures);

  const clipped = passingCases();
  const clippedCase = clipped.find((c) => c.case === '393-offset-negative');
  clippedCase.measured.card.l = -50;                        // 左边被裁
  clippedCase.measured.card.r = 128;
  const clippedFailures = assertCases(clipped, () => { });
  check('CLI 能发现卡片被裁切',
    clippedFailures.some((n) => n.indexOf('卡片左右不被裁切') >= 0), clippedFailures);

  console.log();
  if (failures.length) {
    console.error(`${failures.length} 项失败：${failures.join(', ')}`);
    process.exit(1);
  }
  console.log('PASS (assert_geometry CLI distinguishes good vs double-scaled/clipped synthetic DOM JSON)');
}

main();
