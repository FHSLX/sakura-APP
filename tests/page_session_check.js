// page_session.js（INT-01 的页面代际唯一来源）行为测试。
//
// 覆盖：
//   1) 每个文档一个非空代际编号；格式稳定；
//   2) register() 幂等：同一代际只过一次桥，不刷新原生会话；
//   3) register() 在没有桥时返回 false 且不抛异常；
//   4) invalidate() 只注销当前代际；
//   5) renew() 之后是新代际，且需要重新登记；
//   6) renew 模拟「同一 URL 刷新」的会话失效语义。
//
// 用法: node tests/page_session_check.js
'use strict';

const fs = require('fs');
const path = require('path');

const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(_root, 'plugin.yaml'))
  ? _root
  : path.join(_root, 'sakura_remote');
const source = fs.readFileSync(
  path.join(PLUGIN_ROOT, 'static', 'page_session.js'), 'utf8');

const failures = [];
function check(name, condition, detail) {
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${name}`
    + (condition ? '' : ` -> ${JSON.stringify(detail)}`));
  if (!condition) failures.push(name);
}

function load(bridge, options) {
  const opts = options || {};
  const listeners = new Map();
  const win = {
    SakuraNative: bridge === undefined ? undefined : bridge,
    document: {
      addEventListener(type, fn) {
        if (!listeners.has(type)) listeners.set(type, []);
        listeners.get(type).push(fn);
      },
      dispatch(type) { (listeners.get(type) || []).slice().forEach((fn) => fn({ type })); },
    },
  };
  const factory = new Function('window', source + '\n;return window.SakuraPageSession;');
  return { api: factory(win), win, listeners };
}

function main() {
  const calls = { register: [], invalidate: [] };
  const bridge = {
    registerPageSession(id) { calls.register.push(id); },
    invalidatePageSession(id) { calls.invalidate.push(id); },
  };

  const page = load(bridge);
  const id = page.api.sessionId;
  check('代际编号非空且格式稳定', typeof id === 'string' && /^ps-[0-9a-z]+-[0-9a-z]+$/.test(id), id);
  check('generationId 与 sessionId 一致', page.api.generationId === id, page.api.generationId);
  check('默认未登记', page.api.registered === false, page.api.registered);

  check('首次登记成功', page.api.register() === true);
  check('只过一次桥', calls.register.length === 1 && calls.register[0] === id, calls.register);
  check('登记后 registered=true', page.api.registered === true, page.api.registered);

  // 幂等：穿透开关反复启停会重复调用 register，但原生会话不能被刷新
  page.api.register();
  page.api.register();
  check('重复登记幂等（仍只过一次桥）', calls.register.length === 1, calls.register);
  check('重复登记不换会话', page.api.sessionId === id, page.api.sessionId);

  // 旧页迟到注销：只注销自己那一代
  check('注销成功', page.api.invalidate() === true);
  check('注销上报的是自己的代际', calls.invalidate.length === 1 && calls.invalidate[0] === id,
    calls.invalidate);
  check('注销后 registered=false', page.api.registered === false, page.api.registered);
  page.api.register();
  check('注销后可重新登记同一代际（重入 boot）', calls.register.length === 2, calls.register);

  // 同 URL 刷新语义：新代际
  const renewed = page.api.renew();
  check('renew 产生新代际', renewed !== id, { id, renewed });
  check('renew 后需要重新登记', page.api.registered === false, page.api.registered);
  page.api.register();
  check('新代际登记的是新编号', calls.register[calls.register.length - 1] === renewed,
    calls.register);

  // pagehide 统一注销（捕获阶段）
  check('pagehide 监听已注册', page.listeners.has('pagehide'), Array.from(page.listeners.keys()));
  page.win.document.dispatch('pagehide');
  check('pagehide 注销新代际', calls.invalidate[calls.invalidate.length - 1] === renewed,
    calls.invalidate);

  // 无桥环境不能抛异常
  const bridgeLess = load(undefined);
  check('无桥时 register 返回 false', bridgeLess.api.register() === false);
  check('无桥时 invalidate 返回 false', bridgeLess.api.invalidate() === false);
  check('无桥时仍有代际编号', typeof bridgeLess.api.sessionId === 'string'
    && bridgeLess.api.sessionId.length > 3, bridgeLess.api.sessionId);

  // 两个文档（模拟两次加载）代际不同
  const other = load(bridge);
  check('两个文档的代际不同', other.api.sessionId !== bridgeLess.api.sessionId,
    { a: other.api.sessionId, b: bridgeLess.api.sessionId });

  console.log();
  if (failures.length) {
    console.error(`${failures.length} 项失败：${failures.join(', ')}`);
    process.exit(1);
  }
  console.log('PASS');
}

main();
