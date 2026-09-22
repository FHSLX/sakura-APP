// 用最小 DOM 桩验证气泡渲染：桌宠式双行字幕。
// 用法: node tests/bubble_render_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, '..', 'sakura_remote', 'static', 'app.js'), 'utf8');

const start = source.indexOf('function addBubble');
const end = source.indexOf('function trimBubbles');
if (start < 0 || end < 0) {
  console.error('无法从 app.js 中提取 addBubble');
  process.exit(1);
}

function makeNode(tag) {
  const node = {
    tagName: tag,
    children: [],
    className: '',
    textContent: '',
    // addBubble 会把内容存进 dataset（悬浮窗的历史导航靠它，因为气泡行是隐藏的）
    dataset: {},
    classList: {
      values: new Set(),
      add(name) { this.values.add(name); },
      remove(name) { this.values.delete(name); },
      toggle(name, on) { if (on) this.values.add(name); else this.values.delete(name); },
      contains(name) { return this.values.has(name); },
    },
    appendChild(child) { this.children.push(child); return child; },
    querySelector(selector) {
      const wanted = selector.replace(/^\./, '');
      const walk = (item) => {
        for (const child of item.children) {
          if (child.className && child.className.split(' ').includes(wanted)) return child;
          const found = walk(child);
          if (found) return found;
        }
        return null;
      };
      return walk(this);
    },
  };
  return node;
}

const bubblesRoot = makeNode('section');
const el = { bubbles: bubblesRoot };
let scrollCalls = 0;

const snippet = source.slice(start, end);
const fn = new Function(
  'el',
  'scrollBubbles',
  'trimBubbles',
  'document',
  snippet + '\nreturn addBubble;'
);

const addBubble = fn(
  el,
  () => { scrollCalls += 1; },
  () => {},
  { createElement: makeNode }
);

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}` + (condition ? '' : ` -> ${detail}`));
  if (!condition) failures.push(name);
}

// 1) 日文原文 + 中文字幕 -> 双行
const dual = addBubble('assistant', 'いるよ。', { secondary: '在的哦。' });
const original = dual.bubble.querySelector('.textOriginal');
const translation = dual.bubble.querySelector('.textTranslation');
check('双行：生成 .textOriginal', !!original && original.textContent === 'いるよ。', original && original.textContent);
check('双行：生成 .textTranslation', !!translation && translation.textContent === '在的哦。', translation && translation.textContent);

// 2) 中文是主体：译文要排在原文前面，且 CSS 里字号更大
const rawCss = fs.readFileSync(
  path.join(__dirname, '..', 'sakura_remote', 'static', 'app.css'), 'utf8');
const css = rawCss.replace(/\/\*[\s\S]*?\*\//g, '');

// 注意匹配的必须是「规则体的开头」，避免误命中
// body.hide-original .textOriginal 这类后代选择器。
function ruleBody(selector) {
  const marker = '\n' + selector + ' {';
  const start = css.indexOf(marker);
  if (start < 0) return '';
  const open = start + marker.length - 1;
  const close = css.indexOf('}', open);
  return close < 0 ? '' : css.slice(open + 1, close);
}

const originalBody = ruleBody('.textOriginal');
const translationBody = ruleBody('.textTranslation');
const originalOrder = Number((originalBody.match(/order:\s*(\d+)/) || [])[1] || 0);
const translationOrder = Number((translationBody.match(/order:\s*(\d+)/) || [])[1] || 0);
const originalSize = Number((originalBody.match(/font-size:\s*([\d.]+)px/) || [])[1] || 0);
const translationSize = Number((translationBody.match(/font-size:\s*([\d.]+)px/) || [])[1] || 0);

check('拿到 .textOriginal 规则', originalSize > 0 && originalOrder > 0, originalBody.slice(0, 80));
check('拿到 .textTranslation 规则', translationSize > 0 && translationOrder > 0, translationBody.slice(0, 80));
check('中文排在日文前面（order 更大）', translationOrder > originalOrder, { originalOrder, translationOrder });
check('中文字号大于日文字号', translationSize > originalSize, { originalSize, translationSize });

// 3) 「隐藏日文原文」开关要有对应样式，否则勾了没反应
check('hide-original 时隐藏日文',
  /body\.hide-original\s+\.textOriginal\s*\{\s*display:\s*none/.test(css),
  'body.hide-original .textOriginal 缺少 display:none');

// 4) 输入栏的 grid 列数必须够放所有控件。
// 曾经因为只写了 3 列而加了第 4 个控件（截屏），「发送」被挤到第二行。
//
// 不能简单数 <label|input|button|textarea> 标签数：「图」「截屏」现在收进了
// #mediaMenu 的下拉面板（绝对定位、不占网格列），#image 也带 class="hidden"。
// 所以只数**真正占网格列**的直接子元素。
const formBody = ruleBody('#form');
const columnCount = (formBody.match(/grid-template-columns:([^;]+);/) || [])[1] || '';
const declaredColumns = columnCount.trim().split(/\s+/).filter(Boolean).length;
const html = fs.readFileSync(
  path.join(__dirname, '..', 'sakura_remote', 'web_ui.py'), 'utf8');
const formMatch = html.match(/<form id="form">([\s\S]*?)<\/form>/);
const formInner = formMatch ? formMatch[1] : '';
const layoutInner = formInner
  .replace(/<div id="mediaPanel"[\s\S]*?<\/div>/, '')
  .replace(/<input[^>]*class="hidden"[^>]*>/g, '');
const formControls = (layoutInner.match(/<(label|input|button|textarea)\b/g) || []).length;
check('拿到 #form 的 grid 列定义', declaredColumns > 0, formBody.slice(0, 80));
check('#form 的 grid 列数不少于控件数',
  declaredColumns >= formControls,
  { declaredColumns, formControls, columnCount: columnCount.trim() });

// 2) 只有一行 -> 不生成副行
const single = addBubble('assistant', '在的哦。', { secondary: '在的哦。' });
check('原文等于译文时不重复显示', !single.bubble.querySelector('.textTranslation') && single.bubble.textContent === '在的哦。', single.bubble.textContent);

// 3) 没有 secondary -> 单行
const plain = addBubble('assistant', '只有中文', {});
check('无 secondary 时单行显示', !plain.bubble.querySelector('.textOriginal') && plain.bubble.textContent === '只有中文', plain.bubble.textContent);

// 4) 用户气泡不受影响
const user = addBubble('user', '在吗？');
check('用户气泡保持单行', user.row.className.includes('user') && user.bubble.textContent === '在吗？', user.bubble.textContent);

// 5) typing 气泡仍然只有三个点
const typing = addBubble('assistant', '', { typing: true });
check('typing 气泡渲染三个点', typing.bubble.children.length === 3 && typing.bubble.classList.contains('typing'), typing.bubble.children.length);

check('每次添加都会滚动到底部', scrollCalls === 5, scrollCalls);

console.log();
if (failures.length) {
  console.error(`${failures.length} 项失败：${failures.join(', ')}`);
  process.exit(1);
}
console.log('PASS');
