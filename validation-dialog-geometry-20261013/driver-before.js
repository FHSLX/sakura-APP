'use strict';
/*
 * 几何 fixture：在 app.js 之前注入的桩。
 *
 * 目的：让真实生产 app.css + app.js 跑在真实浏览器排版引擎里，
 * 同时把「原生窗口/设备屏幕」变成可控输入：
 *   - window.screen 固定为用例指定的设备逻辑宽高；
 *   - SakuraNative.setWindowSize(w,h) 按原生真实 clamp
 *     （max(160dp, min(screen, want))，见 OverlayService.applyWindowSizeInternal）
 *     调整 iframe 尺寸，模拟 WebView 视口跟随窗口变化；
 *   - 记录每次窗口请求，供断言原生 clamp 行为。
 */
(function () {
  var params = new URLSearchParams(window.location.search);
  var testCase = {};
  try {
    testCase = JSON.parse(params.get('case') || '{}');
  } catch (error) {
    testCase = {};
  }
  window.__GEO_CASE = testCase;

  var devW = Math.max(1, Math.round(testCase.devW || 393));
  var devH = Math.max(1, Math.round(testCase.devH || 852));
  var fakeScreen = {
    width: devW,
    height: devH,
    availWidth: devW,
    availHeight: devH,
    colorDepth: 24,
    pixelDepth: 24,
    orientation: { type: 'portrait-primary', angle: 0 },
  };
  function installScreen(target) {
    try {
      Object.defineProperty(target, 'screen', {
        configurable: true, get: function () { return fakeScreen; },
      });
      return true;
    } catch (error) {
      return false;
    }
  }
  if (!installScreen(window)) {
    installScreen(Object.getPrototypeOf(window));
  }

  window.__GEO_LOG = { windowRequests: [], screen: { w: devW, h: devH } };

  var native = window.SakuraNative || {};
  native.setKeyboardOpen = function(open){document.body.dataset.keyboardOpen=String(open);};
  native.isTouchRegionSupported = function () { return false; };
  native.hasScreenPermission = function () { return false; };
  native.requestScreenshotWithSession = function () { return 'rejected'; };
  native.registerPageSession = function () { };
  native.invalidatePageSession = function () { };
  native.setPassThroughEnabled = function () { };
  native.isOverlayRunning = function () { return true; };
  native.displayPreferences = function () { return ''; };
  native.setDisplayPreferences = function () { return ''; };
  native.setWindowSize = function (w, h) {
    // 原生真实 clamp：max(160dp, min(screen, want))，单位都是 CSS px
    var cssW = Math.max(160, Math.min(devW, Math.round(w)));
    var cssH = Math.max(160, Math.min(devH, Math.round(h)));
    window.__GEO_LOG.windowRequests.push({
      wantW: Math.round(w), wantH: Math.round(h), cssW: cssW, cssH: cssH,
    });
    var frame = window.frameElement;
    if (frame) {
      setTimeout(function(){frame.style.width = cssW + 'px';frame.style.height = cssH + 'px';},testCase.resizeDelay || 0);
    }
  };
  window.SakuraNative = native;
})();
