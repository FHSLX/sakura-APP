'use strict';
/*
 * 几何 fixture：在 app.js 之后注入的用例驱动。
 *
 * 只走真实用户路径与真实生产函数：
 *   - #scaleRange / #fontRange / #dialogXRange / #dialogYRange 的 input 事件
 *     → applyPortraitScale / applyFontScale / applyDialogShift；
 *   - #bubbles 注入合成历史行 + syncMessageNav(true) → applyMessageNav 真实渲染；
 *   - window resize → installContentWidthSync 的 measure()。
 * 结果写入 window.__GEO_RESULT（外层 fixture 读取）。
 */
(function () {
  var testCase = window.__GEO_CASE || {};
  var result = {
    case: testCase.name || 'default',
    caseInput: testCase,
    requests: (window.__GEO_LOG && window.__GEO_LOG.windowRequests) || [],
    measured: {},
    error: '',
  };

  function q(id) { return document.getElementById(id); }

  function setRange(id, value) {
    var node = q(id);
    if (!node) return false;
    node.value = String(value);
    node.dispatchEvent(new Event('input', { bubbles: true }));
    node.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }

  function portraitDataUrl(w, h) {
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '">'
      + '<rect width="100%" height="100%" fill="#8a4b6b"/></svg>';
    return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
  }

  function rectOf(id) {
    var node = q(id);
    if (!node) return null;
    var r = node.getBoundingClientRect();
    return { l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height };
  }

  function measure() {
    var root = getComputedStyle(document.documentElement);
    var body = q('msgBody');
    var card = q('msgNav');
    Object.assign(result.measured, {
      viewportW: window.innerWidth,
      viewportH: window.innerHeight,
      screenW: window.screen.width,
      screenH: window.screen.height,
      stage: rectOf('stage'),
      composer: rectOf('composer'),
      wrap: rectOf('portraitWrap'),
      img: rectOf('portrait'),
      card: rectOf('msgNav'),
      composer: rectOf('composer'),
      prev: rectOf('msgPrev'),
      next: rectOf('msgNext'),
      availH: root.getPropertyValue('--pet-avail-h').trim(),
      navH: root.getPropertyValue('--pet-nav-h').trim(),
      composerH: root.getPropertyValue('--pet-composer-h').trim(),
      dialogX: root.getPropertyValue('--pet-dialog-x').trim(),
      dialogY: root.getPropertyValue('--pet-dialog-y').trim(),
      wrapTransform: getComputedStyle(q('portraitWrap')).transform,
      cardClass: card ? card.className : '',
      textOverflow: body ? (body.scrollWidth - body.clientWidth) : null,
      portraitNaturalW: q('portrait') ? q('portrait').naturalWidth : 0,
      portraitNaturalH: q('portrait') ? q('portrait').naturalHeight : 0,
    });
    return result.measured;
  }

  function waitReady(done) {
    var started = Date.now();
    (function poll() {
      var portrait = q('portrait');
      var ready = document.body.classList.contains('overlay-mode')
        && portrait && portrait.naturalWidth > 0
        && typeof window.relayoutOverlay === 'function';
      if (ready || Date.now() - started > 10000) done(ready);
      else setTimeout(poll, 40);
    })();
  }

  function applyText() {
    var bubbles = q('bubbles');
    if (!bubbles || !testCase.text) return;
    bubbles.innerHTML = '';
    var row = document.createElement('div');
    row.className = 'bubbleRow assistant';
    row.dataset.role = 'assistant';
    if (testCase.text === 'long') {
      row.dataset.primary = '这是一段很长的合成回复文字，用来验证长消息换行之后对话框仍然完整显示在窗口里，'
        + '左右两侧的文字和上下翻页按钮都不能被裁掉，也不能把输入栏压住。';
    } else if (testCase.text === 'empty') {
      row.dataset.primary = '';
    } else {
      row.dataset.primary = '嗯。';
    }
    row.dataset.secondary = '';
    bubbles.appendChild(row);
    if (typeof window.syncMessageNav === 'function') window.syncMessageNav(true);
  }

  function finish() {
    measure();
    result.requests = (window.__GEO_LOG && window.__GEO_LOG.windowRequests) || [];
    window.__GEO_RESULT = result;
    document.title = 'GEO-CASE-DONE';
  }

  waitReady(function (ready) {
    if (!ready) {
      result.error = 'app not ready';
      finish();
      return;
    }
    try {
      var portrait = q('portrait');
      if (testCase.imgW && testCase.imgH) {
        portrait.src = portraitDataUrl(testCase.imgW, testCase.imgH);
      }
      setRange('scaleRange', Math.round((testCase.scale || 1) * 100));
      setRange('fontRange', testCase.font || 100);
      applyText();
      setRange('dialogXRange', testCase.dx || 0);
      setRange('dialogYRange', testCase.dy || 0);
      document.body.classList.add('no-float');
      window.dispatchEvent(new Event('resize'));
    } catch (error) {
      result.error = String(error && error.message ? error.message : error);
    }

    function afterVisible() {
      if (!testCase.hidden) {
        setTimeout(finish, 900);
        return;
      }
      var card = q('msgNav');
      card.classList.add('auto-hidden');
      window.dispatchEvent(new Event('resize'));
      setTimeout(function () {
        result.measured.hiddenCardH = card.getBoundingClientRect().height;
        card.classList.remove('auto-hidden');
        window.dispatchEvent(new Event('resize'));
        setTimeout(function () {
          measure();
          result.measured.reopenedCardH = card.getBoundingClientRect().height;
          result.measured.reopenedStageH = q('stage').getBoundingClientRect().height;
          window.__GEO_RESULT = result;
          document.title = 'GEO-CASE-DONE';
        }, 800);
      }, 800);
    }

    if (portrait.complete && portrait.naturalWidth > 0) {
      setTimeout(afterVisible, 700);
    } else {
      portrait.addEventListener('load', function () { setTimeout(afterVisible, 700); }, { once: true });
      setTimeout(afterVisible, 1500);   // 兜底，图片加载事件丢失也不挂住
    }
  });
})();
