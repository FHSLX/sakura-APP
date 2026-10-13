'use strict';
/*
 * 外层 fixture：按用例矩阵创建 iframe（模拟原生悬浮窗），
 * 顺序加载真实 /app，读取内层 window.__GEO_RESULT。
 *
 * 用法：
 *   ?all=1            跑全部用例并把 JSON 写进 #out（几何自动检查用）
 *   ?case=<name>      只跑一个用例（人工/Codex 复审用）
 */
(function () {
  var BASE = {
    devW: 393, devH: 852, imgW: 700, imgH: 1400, scale: 1, font: 100,
    dx: 0, dy: 0, text: 'short',
  };
  function make(name, overrides) {
    var item = Object.assign({}, BASE, overrides || {});
    item.name = name;
    return item;
  }
  var CASES = [
    make('393-tall-scale1', {}),
    make('393-tall-scale1.5', { scale: 1.5 }),
    make('393-tall-scale0.5', { scale: 0.5 }),
    make('393-wide-scale1', { imgW: 1600, imgH: 900 }),
    make('393-square-scale1', { imgW: 1000, imgH: 1000 }),
    make('320-tall-scale1', { devW: 320, devH: 700, imgW: 700, imgH: 1400 }),
    make('393-offset-negative', { dx: -160, dy: -40 }),
    make('393-offset-positive', { dx: 160, dy: 40 }),
    make('393-long-text', { text: 'long' }),
    make('393-font-150', { font: 150, text: 'long' }),
    make('393-hidden-reopen', { hidden: true })
    ,make('320-small-negative-delayed', {devW:320,devH:700,scale:.5,dx:-160,dy:60,startW:307,startH:396,resizeDelay:40})
    ,make('393-existing-window-delayed', {scale:.8,dx:-60,dy:-40,startW:307,startH:396,resizeDelay:40})
  ];

  var frames = document.getElementById('frames');
  var out = document.getElementById('out');
  var stateEl = document.getElementById('state');
  var select = document.getElementById('caseSelect');
  CASES.forEach(function (item, index) {
    var option = document.createElement('option');
    option.value = String(index);
    option.textContent = item.name;
    select.appendChild(option);
  });

  function runCase(item) {
    return new Promise(function (resolve) {
      var frame = document.createElement('iframe');
      frame.width = item.startW || item.devW;
      frame.height = item.startH || item.devH;
      frame.src = '/app?mode=overlay&case=' + encodeURIComponent(JSON.stringify(item));
      var settled = false;
      frame.addEventListener('load', function () {
        var started = Date.now();
        var timer = setInterval(function () {
          var value = null;
          try {
            value = frame.contentWindow.__GEO_RESULT;
          } catch (error) {
            value = { case: item.name, error: 'cross-origin: ' + error };
          }
          if (value || Date.now() - started > 20000) {
            clearInterval(timer);
            if (settled) return;
            settled = true;
            frame.remove();
            resolve(value || { case: item.name, error: 'timeout' });
          }
        }, 40);
      });
      frames.appendChild(frame);
    });
  }

  async function runAll(list) {
    var results = [];
    for (var i = 0; i < list.length; i += 1) {
      stateEl.textContent = 'running ' + list[i].name + ' (' + (i + 1) + '/' + list.length + ')';
      results.push(await runCase(list[i]));
    }
    var payload = { generatedAt: new Date().toISOString(), cases: results };
    window.__GEO_PAYLOAD = payload;
    out.textContent = JSON.stringify(payload);
    stateEl.textContent = 'done: ' + results.length;
    document.title = 'GEO-DONE';
    // 回传给 localhost fixture 服务器（Codex 的浏览器运行用），失败不影响页面
    try {
      await fetch('/results', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      stateEl.textContent += ' (posted /results)';
    } catch (error) {
      stateEl.textContent += ' (post failed)';
    }
  }

  document.getElementById('runAll').addEventListener('click', function () { runAll(CASES); });
  document.getElementById('runOne').addEventListener('click', function () {
    var index = Number(select.value || 0);
    out.textContent = '';
    runAll([CASES[index]]);
  });

  var params = new URLSearchParams(window.location.search);
  if (params.get('all') === '1') {
    runAll(CASES);
  } else if (params.get('case')) {
    var wanted = params.get('case');
    var found = CASES.filter(function (item) { return item.name === wanted; });
    runAll(found.length ? found : CASES);
  }
})();
