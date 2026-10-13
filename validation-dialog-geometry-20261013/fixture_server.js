'use strict';
/*
 * 对话几何 fixture 的 localhost 服务器（不启动任何浏览器）。
 *
 * 提供：
 *   /                    合成 fixture 外层页（可选 ?all=1 / ?case=<name>）
 *   /app                 真实 web_ui.render_page 生成的页面 + 注入 driver
 *   /app.css /app.js /page_session.js /overlay_touch_region.js   真实静态资源
 *   POST /results        接收浏览器回传的 DOM JSON，落盘 geometry-results.json
 *   GET  /results        读回已落盘的 JSON
 *
 * Codex 用 cua_repl 打开 /?all=1（或 ?case=<name>），页面测完后会把
 * window.__GEO_RESULT 汇总 POST 到 /results；随后用
 *   node validation-dialog-geometry-20261013/assert_geometry.js
 * 独立断言。
 *
 * 用法: node validation-dialog-geometry-20261013/fixture_server.js [port]
 */
'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');

const DIR = __dirname;
const ROOT = path.join(DIR, '..');
const PLUGIN_ROOT = fs.existsSync(path.join(ROOT, 'plugin.yaml')) ? ROOT : path.join(ROOT, 'sakura_remote');
const STATIC = path.join(PLUGIN_ROOT, 'static');
const RESULTS = path.join(DIR, 'geometry-results.json');
const PORT = Number(process.argv[2] || 8781);

function appPage() {
  const renderedPath = path.join(DIR, 'app-rendered.html');
  if (!fs.existsSync(renderedPath)) {
    throw new Error('missing app-rendered.html; run tests/dialog_geometry_check.ps1 first');
  }
  let html = fs.readFileSync(renderedPath, 'utf8');
  // driver-before 必须在 page_session/app.js 之前
  html = html.replace('<script src="/page_session.js',
    '<script src="/driver-before.js"></script>\n<script src="/page_session.js');
  // driver 在 app.js 之后、</body> 之前
  html = html.replace('</body>', '<script src="/driver.js"></script>\n</body>');
  return html;
}

function readText(file) {
  return fs.readFileSync(file);
}

function buildFiles() {
  return new Map([
    ['/app', { type: 'text/html; charset=utf-8', body: appPage() }],
    ['/', { type: 'text/html; charset=utf-8', body: readText(path.join(DIR, 'fixture.html')) }],
    ['/focus.html', { type: 'text/html; charset=utf-8', body: readText(path.join(DIR, 'codex-focus.html')) }],
    ['/index.html', { type: 'text/html; charset=utf-8', body: readText(path.join(DIR, 'fixture.html')) }],
    ['/fixture.js', { type: 'application/javascript; charset=utf-8', body: readText(path.join(DIR, 'fixture.js')) }],
    ['/driver.js', { type: 'application/javascript; charset=utf-8', body: readText(path.join(DIR, 'driver.js')) }],
    ['/driver-before.js', { type: 'application/javascript; charset=utf-8', body: readText(path.join(DIR, 'driver-before.js')) }],
    ['/app.css', { type: 'text/css; charset=utf-8', body: readText(path.join(STATIC, 'app.css')) }],
    ['/app.js', { type: 'application/javascript; charset=utf-8', body: readText(path.join(STATIC, 'app.js')) }],
    ['/page_session.js', { type: 'application/javascript; charset=utf-8', body: readText(path.join(STATIC, 'page_session.js')) }],
    ['/overlay_touch_region.js', { type: 'application/javascript; charset=utf-8', body: readText(path.join(STATIC, 'overlay_touch_region.js')) }],
  ]);
}

function send(res, status, type, body) {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
}

const files = buildFiles();

// 合成接口数据（绝不包含真实角色/聊天/令牌），让 app 走正常 boot 路径
const SYNTHETIC_STATE = JSON.stringify({
  characterId: 'fixture-role',
  displayName: '合成角色',
  tones: [],
  portraits: [],
  defaultPortraitKey: '',
  portraitOverrides: {},
  settings: {},
  theme: {},
});
const SYNTHETIC_HISTORY = JSON.stringify({
  history: [{ role: 'assistant', content: '合成的上一句', raw_content: '' }],
});

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  if (url.pathname === '/api/state') {
    send(res, 200, 'application/json', JSON.stringify({characterId:'synthetic',displayName:'合成角色',portraits:[{key:'test',url:'/synthetic.svg'}],defaultPortraitKey:'test',settings:{tts_enabled:false,autoplay:false}}));
    return;
  }
  if (url.pathname === '/api/history') {
    send(res, 200, 'application/json', JSON.stringify({history:[{role:'assistant',content:'嗯。'}]}));
    return;
  }
  if (url.pathname === '/synthetic.svg') {
    send(res, 200, 'image/svg+xml', '<svg xmlns="http://www.w3.org/2000/svg" width="700" height="1400"><rect width="700" height="1400" fill="#8298b4"/></svg>');
    return;
  }
  if (url.pathname.startsWith('/api/')) {
    if (url.pathname === '/api/state') {
      send(res, 200, 'application/json; charset=utf-8', SYNTHETIC_STATE);
    } else if (url.pathname === '/api/history') {
      send(res, 200, 'application/json; charset=utf-8', SYNTHETIC_HISTORY);
    } else {
      send(res, 200, 'application/json; charset=utf-8', '{}');
    }
    return;
  }
  if (url.pathname === '/results') {
    if (req.method === 'POST') {
      const chunks = [];
      req.on('data', (chunk) => chunks.push(chunk));
      req.on('end', () => {
        try {
          const payload = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          fs.writeFileSync(RESULTS, JSON.stringify(payload, null, 2), 'utf8');
          send(res, 200, 'application/json; charset=utf-8',
            JSON.stringify({ ok: true, path: 'geometry-results.json', cases: (payload.cases || []).length }));
        } catch (error) {
          send(res, 400, 'application/json; charset=utf-8',
            JSON.stringify({ ok: false, error: String(error) }));
        }
      });
      return;
    }
    if (fs.existsSync(RESULTS)) {
      send(res, 200, 'application/json; charset=utf-8', fs.readFileSync(RESULTS));
    } else {
      send(res, 404, 'application/json; charset=utf-8', JSON.stringify({ ok: false, error: 'no results yet' }));
    }
    return;
  }
  const file = files.get(url.pathname);
  if (!file) {
    send(res, 404, 'text/plain; charset=utf-8', 'not found');
    return;
  }
  send(res, 200, file.type, file.body);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('dialog geometry fixture server');
  console.log(`  all cases : http://127.0.0.1:${PORT}/?all=1`);
  console.log(`  one case  : http://127.0.0.1:${PORT}/?case=393-tall-scale1`);
  console.log(`  results   : http://127.0.0.1:${PORT}/results`);
  console.log(`  assert    : node validation-dialog-geometry-20261013/assert_geometry.js`);
  console.log('  (Codex: open the URL with cua_repl; the page POSTs DOM JSON to /results)');
});
