// R2-3 专项：发送失败 / 结果未知时的草稿恢复与历史刷新策略。
//
// 直接执行 app.js 里真实的 restoreSendDraft / handleSendFailure：
//   1) 输入框为空时恢复草稿，并派发 input 事件（让高度自适应等逻辑跟随）；
//   2) 用户已重新输入时不覆盖；
//   3) 请求已发出但响应丢失 → 标「结果未知」并先刷新历史，不自动重发；
//   4) 服务端明确 busy / 上传就失败 → 只报错，不刷新历史、不谎称未知；
//   5) 没有可恢复文字（例如截图路径）时不报错。
//
// 用法: node tests/send_draft_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml'))
  ? _root
  : path.join(_root, 'sakura_remote');

const source = fs.readFileSync(path.join(PLUGIN_ROOT, 'static', 'app.js'), 'utf8');

const start = source.indexOf('function restoreSendDraft');
const end = source.indexOf('async function send(text)');
if (start < 0 || end < 0 || end <= start) {
  console.error('无法从 app.js 提取发送失败处理逻辑');
  process.exit(1);
}
const snippet = source.slice(start, end);

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

function makeEnv() {
  const notes = [];
  const statuses = [];
  const historyRefreshes = [];
  let inputEvents = 0;
  const text = {
    value: '',
    dispatchEvent() { inputEvents += 1; return true; },
  };
  const env = {
    notes,
    statuses,
    historyRefreshes,
    text,
    inputEvents: () => inputEvents,
  };
  const factory = new Function(
    'el', 'systemNote', 'setStatus', 'loadHistory', 'setTimeout', 'Event',
    snippet + '\nreturn { restoreSendDraft, handleSendFailure };'
  );
  env.api = factory(
    { text },
    (message) => notes.push(message),
    (value) => statuses.push(value),
    (silent) => historyRefreshes.push(silent),
    (fn) => { /* 立即执行定时回调，便于断言状态提示 */ fn(); return 0; },
    class FakeEvent { constructor(type) { this.type = type; } }
  );
  return env;
}

/* ---------- 1) 空输入框时恢复草稿 ---------- */
{
  const env = makeEnv();
  const ok = env.api.restoreSendDraft('你好呀');
  check('空输入框时恢复草稿', ok === true && env.text.value === '你好呀',
    { ok, value: env.text.value });
  check('恢复草稿时派发 input 事件', env.inputEvents() === 1, env.inputEvents());
}

/* ---------- 2) 不覆盖用户重新输入的内容 ---------- */
{
  const env = makeEnv();
  env.text.value = '我正在重新打';
  const ok = env.api.restoreSendDraft('旧的失败内容');
  check('已有新草稿时不覆盖', ok === false && env.text.value === '我正在重新打',
    { ok, value: env.text.value });
  check('不覆盖时不派发 input 事件', env.inputEvents() === 0, env.inputEvents());
}

/* ---------- 3) 结果未知：刷新历史 + 恢复草稿 + 不自动重发 ---------- */
{
  const env = makeEnv();
  env.api.handleSendFailure(new Error('连不上电脑端（Failed to fetch）'),
    { text: '在吗？', chatDispatched: true });
  check('结果未知时明确提示', env.notes.length === 1
    && env.notes[0].indexOf('结果未知') >= 0, env.notes);
  check('结果未知时先刷新历史',
    env.historyRefreshes.length === 1 && env.historyRefreshes[0] === true,
    env.historyRefreshes);
  check('结果未知时恢复可编辑草稿', env.text.value === '在吗？', env.text.value);
  check('恢复后给出重试提示',
    env.statuses.some((s) => s.indexOf('重试') >= 0), env.statuses);
  check('没有自动重复请求（只报一次）', env.notes.length === 1, env.notes.length);
}

/* ---------- 4) 服务端明确 busy：只报错，不刷新历史 ---------- */
{
  const env = makeEnv();
  const busy = new Error('角色正忙');
  busy.busy = true;
  env.api.handleSendFailure(busy, { text: '第二条', chatDispatched: true });
  check('busy 时按普通失败提示', env.notes.length === 1
    && env.notes[0] === '角色正忙', env.notes);
  check('busy 时不刷新历史', env.historyRefreshes.length === 0, env.historyRefreshes);
  check('busy 时同样恢复草稿', env.text.value === '第二条', env.text.value);
}

/* ---------- 5) 请求还没发出（上传失败）：不谎称结果未知 ---------- */
{
  const env = makeEnv();
  env.api.handleSendFailure(new Error('图片格式无法识别'), { text: '带图', chatDispatched: false });
  check('未发出时不标结果未知', env.notes.length === 1
    && env.notes[0].indexOf('结果未知') < 0, env.notes);
  check('未发出时不刷新历史', env.historyRefreshes.length === 0, env.historyRefreshes);
  check('未发出时恢复草稿', env.text.value === '带图', env.text.value);
}

/* ---------- 6) 没有可恢复文字时不报错 ---------- */
{
  const env = makeEnv();
  env.api.handleSendFailure(new Error('x'), { text: '', chatDispatched: true });
  check('空文字不写入输入框', env.text.value === '', env.text.value);
  check('空文字时清空状态栏', env.statuses.includes(''), env.statuses);
}

console.log();
if (failures.length) {
  console.error(`${failures.length} 项失败：${failures.join(', ')}`);
  process.exit(1);
}
console.log('PASS');
