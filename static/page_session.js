/*
 * 页面级「代际会话」的唯一来源（INT-01）。
 *
 * 为什么必须有这个文件：
 *   修 INT-01 之前，app.js 自己拼 `PAGE_SESSION_ID`，overlay_touch_region.js 又用
 *   `makeSessionId()` 拼了另一个随机编号，两者在**同一个文档**里先后调用同一个
 *   `registerPageSession` 桥。原生按「宿主 pageId → 当前会话」只记一个值，
 *   后登记的那个模块把先登记的覆盖掉：
 *     - 触摸区域模块登记的会话赢了；
 *     - 截图请求却仍带 app.js 的会话；
 *     - 原生按 (pageId=overlay) 复核，认为「页面已刷新」，合法截图被拒。
 *
 * 现在的契约（对应复审补充里的验收）：
 *   1. 每个文档只有一个代际编号，app.js 与穿透模块**共用**它；
 *   2. 整个文档生命周期只登记一次；启用/关闭穿透不得刷新代际，
 *      否则开关一次穿透就会把正在进行的截图会话作废；
 *   3. 导航、同 URL 刷新必然产生新代际 —— 本文件随 HTML 重新解析执行，
 *      `makeSessionId()` 会拿到新的时间戳 + 随机后缀；
 *   4. 旧页的迟到注销只注销**它自己**那一代（invalidate 带 sessionId），
 *      不会清掉新页刚登记的会话。
 *
 * 纯浏览器 API，无构建步骤；Node 下也可加载，便于行为测试直接跑真实引导逻辑。
 */
(function (global) {
  'use strict';

  var STARTED_AT = Date.now();

  function makeSessionId() {
    return 'ps-' + STARTED_AT.toString(36) + '-'
      + Math.floor(Math.random() * 1e9).toString(36);
  }

  var sessionId = makeSessionId();
  var registeredSessionId = '';

  function nativeBridge() {
    return global.SakuraNative || null;
  }

  var api = {
    /** 本代际的会话 id（字符串，非空）。 */
    get sessionId() {
      return sessionId;
    },
    /** 会话 id 的别名，语义更明确。 */
    get generationId() {
      return sessionId;
    },
    /** 本代际的创建时间（毫秒）。 */
    get startedAt() {
      return STARTED_AT;
    },
    /** 是否已经向原生登记过本代际。 */
    get registered() {
      return registeredSessionId === sessionId;
    },
    /** 生成一个新代际（仅供离线测试与极少数显式重生成场景）。 */
    renew: function () {
      sessionId = makeSessionId();
      registeredSessionId = '';
      return sessionId;
    },
    /**
     * 登记当前代际。幂等：同一代际重复调用只过一次桥，不刷新原生会话。
     *
     * @return true = 当前代际已登记（含本次登记成功或此前已登记）
     */
    register: function () {
      if (registeredSessionId === sessionId) {
        return true;
      }
      var n = nativeBridge();
      if (!n || typeof n.registerPageSession !== 'function') {
        return false;
      }
      try {
        n.registerPageSession(sessionId);
      } catch (error) {
        return false;
      }
      registeredSessionId = sessionId;
      // 渲染代际（FIX-BLANK-01）：告诉原生「旧页面内容已不可信」。
      // 原生据此把渲染可用性回到 LOADING：没有可见立绘就不挂完整透明窗口。
      // 这是可选桥：旧版原生没有该方法时静默跳过，不影响会话登记。
      try {
        if (typeof n.notifyOverlayPageSession === 'function') {
          n.notifyOverlayPageSession(sessionId);
        }
      } catch (error) {
        // 忽略：渲染代际只是可见性优化，不影响截图会话
      }
      return true;
    },
    /** 注销当前代际（只注销自己这一代，旧页迟到注销清不掉新页）。 */
    invalidate: function () {
      var n = nativeBridge();
      if (!n || typeof n.invalidatePageSession !== 'function') {
        return false;
      }
      try {
        n.invalidatePageSession(sessionId);
      } catch (error) {
        return false;
      }
      if (registeredSessionId === sessionId) {
        registeredSessionId = '';
      }
      return true;
    }
  };

  global.SakuraPageSession = api;

  // 页面离开（含 reload / 关闭 / 导航）时注销本代际。用捕获阶段注册，
  // 保证即使某次导航后事件顺序变化也先于卸载逻辑执行。
  if (global.document && global.document.addEventListener) {
    global.document.addEventListener('pagehide', function () {
      api.invalidate();
    }, true);
  }
}(typeof window !== 'undefined' ? window : globalThis));
