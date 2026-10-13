// UI-01：对话框偏左下的可验证几何检查。
//
// 根因（真实 CSS 级联）：基础规则 `body.overlay-mode #msgNav { margin: 0 auto }`
// 负责水平居中，后面的位置微调规则又写 `margin-left: var(--pet-dialog-x, 0px)`，
// 默认 0px 覆盖 auto → 卡片被推到左边。修复用 position:relative + left/top。
//
// 这里做两件事（不依赖浏览器，也不需要真机）：
//   1) 静态级联检查：没有任何覆盖规则把水平 auto 居中改成非 auto；
//   2) 行为检查：applyDialogShift 写入的 CSS 变量与偏移语义一致（由
//      tests/ux_media_voice_check.js 在同一套假 DOM 上执行 app.js 完成）。
//
// 用法: node tests/ui_dialog_offset_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml')) ? _root : path.join(_root, 'sakura_remote');
const css = fs.readFileSync(path.join(PLUGIN_ROOT, 'static', 'app.css'), 'utf8');

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

function rulesFor(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const regex = new RegExp(escaped + '\\s*\\{([^}]*)\\}', 'g');
  const blocks = [];
  let match = regex.exec(css);
  while (match) {
    blocks.push(match[1]);
    match = regex.exec(css);
  }
  return blocks;
}

const blocks = rulesFor('body.overlay-mode #msgNav');
check('存在 body.overlay-mode #msgNav 规则', blocks.length >= 2, blocks.length);
const centered = blocks.some((body) => /margin\s*:\s*0\s+auto\s*;/.test(body));
check('基础规则用 margin: 0 auto 水平居中', centered, blocks.slice(0, 4));

const overridesAuto = blocks.filter((body) => /margin-left\s*:/.test(body));
check('没有任何覆盖规则再写 margin-left（会覆盖 auto）',
  overridesAuto.length === 0, overridesAuto);

const offsetRule = blocks.find((body) => body.indexOf('--pet-dialog-x') >= 0);
check('偏移规则存在并读取 --pet-dialog-x', !!offsetRule, blocks.slice(-3));
check('横向偏移用 left（保留 auto 居中）',
  !!offsetRule && /left\s*:\s*var\(--pet-dialog-x/.test(offsetRule), offsetRule);
check('纵向偏移用 top（不再挤动输入栏）',
  !!offsetRule && /top\s*:\s*var\(--pet-dialog-y/.test(offsetRule), offsetRule);
check('偏移规则使用 position: relative',
  !!offsetRule && /position\s*:\s*relative/.test(offsetRule), offsetRule);

console.log();
if (failures.length) {
  console.error(`${failures.length} 项失败：${failures.join(', ')}`);
  process.exit(1);
}
console.log('PASS');
