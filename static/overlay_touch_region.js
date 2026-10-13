/*
 * 悬浮窗「精确局部穿透」的前端几何模块。
 *
 * 职责：
 *   1. 一次性扫描立绘 PNG 的 alpha，缓存成「按行的不透明段」；
 *   2. 按当前 object-fit / object-position / transform 把行段映射到
 *      WebView 内容坐标系（CSS 像素）；
 *   3. 合并真实可见且可交互的控件矩形；
 *   4. 带上 revision / pageSessionId / CSS 视口尺寸提交给原生
 *      OverlayTouchRegionController，由它转成 surface 本地设备像素的 Region。
 *
 * 本轮（TS-03，用户新授权）——**只做外轮廓，内部空隙填平**：
 *   - 每一行的输入区域 = 该行「最左 → 最右不透明格子」的一条连续带：左右两端
 *     严格贴合真实外轮廓（外部透明区照常穿透），行内空隙（发缝、腋下、裙摆
 *     分叉、两腿之间）被并入，不再单独开孔；
 *   - 填充是**保守方向**：只会把内部空隙并进来，绝不丢任何一个不透明块，
 *     也不越过该行左右外边界；跨度异常（> MAX_FILL_GAP_CELLS 格）时才拆带，
 *     拆带只减少覆盖、不产生假可触摸区；
 *   - 用户已确认「发缝与内部空隙不穿透」是可接受的取舍，因此不再宣称内部
 *     透明缝都穿透；界面提示、状态字段与证据描述同步改为外轮廓语义。
 *
 * 本轮（TS-01）修正（保留）：
 *   - **有界保守简化**取代「超复杂图整窗回退」：alpha 按**有界瓦片**读取原图
 *     （每块 canvas/getImageData ≤ MAX_TILE_PIXELS），把任意 alpha ≥ 阈值的
 *     **源像素 OR 进受限网格**（不平均、不最近邻丢点），再按 2 的幂聚合
 *     （格内任一不透明 → 整格不透明，max-pooling），从最细到最粗挑一个
 *     「矩形数 ≤ 预算、行段 ≤ 预算」的级别。聚合只会**增加**可点区域，
 *     绝不丢不透明像素 —— 细发丝、单像素点、底部肢体都不会被截断；
 *   - 聚合后的坐标**保留原网格格宽与末尾边界**：非整除尺寸不会把
 *     ceil(grid/block) 当均匀新格数缩放（避免边界漂移）；
 *   - 最粗级别下网格至多 6×H 个格子，矩形数有**硬上限**，不存在「整窗回退」；
 *     `contour-too-complex` 只保留给「控件自身超限」这种真正的容量问题；
 *   - 扫描内存、矩形数、JSON 长度都在前端先收紧（比原生 1536 矩形更保守），
 *     并把**实际简化级别与边缘容差**如实放进载荷/状态，不再宣称「最多 1 设备像素」。
 *
 * 本轮（RV-03 / RV-05 / RV-06）修正（保留）：
 *   - 区分 queued 与 native applied ACK：只有原生 touchRegionStatus() 回报
 *     revision 已应用才认为成功并写 lastSubmitted；失败不缓存成功签名；
 *   - desired=true 时支持图片恢复与 native 重新开启，受控自动重试（内容变化重置预算）；
 *   - 页面会话在 boot 时通过 registerPageSession 登记、pagehide 时注销，
 *     原生按会话 + revision 裁决（详见 RegionSessionGate）。
 *
 * 本轮（INT-01）修正：
 *   - 代际会话不再由本模块自行随机生成，而是读 static/page_session.js 建立的
 *     **共享编号**，与 app.js 完全一致；登记幂等，开启/关闭穿透不刷新代际，
 *     因此不会把在途截图请求判成「页面已刷新」。pagehide 注销统一由
 *     page_session.js 处理，且只注销自己那一代。
 *
 * 本轮（TS-02）性能止损（用户反馈：开启穿透后变淡且稍卡）：
 *   - **几何脏版本**：每次提交前先用「立绘矩形 + 视口 + 控件可见性/位置」的
 *     廉价几何签名判断要不要重建载荷。旧实现每轮都先跑完整 mapRunsToCss +
 *     collectControlRects + JSON.stringify，再拿签名去重 —— 去重成功也白算一遍；
 *   - **原图扫描只跟图片代次绑定**：`scanCache` 以「图片 URL + 原图尺寸」为键，
 *     扫描失败/原生报错的重试路径**不再清缓存**（旧实现每 500ms 重扫一张
 *     1.8M 像素原图，实测 3.6M 像素/秒）；
 *   - **ACK 查询只在真有 pending 时进行**：稳定态改为低频健康检查（指数退避，
 *     上限 30s），不再每 500ms 过桥读一次 JSON 状态；
 *   - **页面不可见/关闭就停工作**：visibilitychange(pagehide) 停止轮询，
 *     恢复可见时重启；关闭穿透后不再有任何定时任务；
 *   - 原生暂时性拒绝（surface 未就绪等）走**可恢复**错误码，不再一次就被判死。
 *
 * 明确不做（否则就是上一版失败的假穿透）：
 *   - 不在 touchstart 之后切 FLAG_NOT_TOUCHABLE；
 *   - 不用 CSS pointer-events 冒充；不注入合成点击；
 *   - 不用「扫过位置的并集」冒充当前精确孔洞（所以启用时冻结位移动画）。
 *
 * 该模块只依赖 window.SakuraNative 桥与 DOM，可在普通浏览器里降级为 no-op，
 * 因此适合用 Node + 假 DOM 做离线单测。
 */
(function (global) {
  'use strict';

  var ALPHA_MIN = 16;          // 与 app.js portraitAlphaAt 的 ALPHA_MIN 保持一致
  /*
   * 有界保守简化的预算（全部在前端先收紧，必须 ≤ 原生校验）。
   *
   * 原生 OverlayTouchRegionController：MAX_RECTS = 1536、视口 ∈ [32, 8192]、
   * 载荷 ≤ 98304 字符。这里 MAX_RECTS = 256 < 1536、
   * MAX_PAYLOAD_BYTES = 90000 < 98304，「前端更保守」这条约束由
   * tests/touch_region_limit_check.js 静态核对。
   */
  var MAX_SCAN_W = 360;        // alpha 网格宽度上限（约 360×384 格，网格本身 ≤ 0.14MB）
  var MAX_SCAN_H = 384;        // alpha 网格高度上限（防止超高图把网格撑爆）
  var MAX_SCAN_CELLS = MAX_SCAN_W * MAX_SCAN_H;
  var MAX_LEVEL = 6;           // 最多 2^6 = 64 倍聚合；最粗网格 ≤ 6×6 个格子
  var MAX_SEGMENTS = 1600;     // 选择简化级别时的行段预算（不用于失败）
  var MAX_RECTS = 256;         // 外轮廓总预算，保留控件与全部人物像素
  var MAX_PAYLOAD_BYTES = 90000;
  var MAX_IMAGE_DIM = 8192;    // 单边像素上限
  var MAX_IMAGE_AREA = 33554432; // 8192 × 4096：画布/解码的安全边界
  /*
   * 源图瓦片读取边界：每次 canvas/getImageData ≤ MAX_TILE_PIXELS 像素
   *（1M 像素 ≈ 4MB RGBA）；源图总像素超过 MAX_SCAN_SOURCE_PIXELS 直接明确失败，
   * 不做「偷偷最近邻降采样」。
   */
  var MAX_TILE_W = 1024;
  var MAX_TILE_H = 1024;
  var MAX_TILE_PIXELS = 1048576;
  var MAX_SCAN_SOURCE_PIXELS = 8000000;
  var SUBMIT_DEBOUNCE_MS = 60;
  var POLL_MS = 500;
  var MAX_NATIVE_RETRIES = 4;  // 原生失败后的受控重试预算（内容变化会重置）
  /*
   * 稳定态健康检查（TS-02）：没有 pending 时不必每 500ms 过桥读一次状态。
   * 起始 2 个轮询周期，每次翻倍，上限 60 个周期（≈30s）。
   */
  var HEALTH_BASE_TICKS = 2;
  var HEALTH_MAX_TICKS = 60;
  /** 需要并入可触摸区的真实交互控件（不要放 #stage / #portrait）。 */
  var CONTROL_SELECTORS = [
    '#msgNav', '#msgText', '#msgButtons', '#msgBody',
    '#composer', '#form', '#text', '#send',
    '#mediaMenu', '#mediaToggle', '#mediaPanel', '#mediaInfo', '#mediaInfoClear',
    '#configPanel', '#configHeader', '#configBody', '#configFooter',
    '#miniBar', '#miniButton', '#status', '#topbar', '#controls',
    '#hint.show'
  ];

  var STORAGE_KEY = 'sakura.remote.passThrough';

  var revision = 0;
  var sessionId = resolveSessionId();
  var registeredSessionId = '';
  var active = false;
  var desired = false;
  var installed = false;    // install() 是否在本页真正跑过（App 页不安装模块）
  var reason = 'idle';
  var approximated = false;
  var lastSubmitted = '';
  var pendingRevision = 0;
  var pendingSignature = '';
  var submitTimer = 0;
  var pollTimer = 0;
  var scanCache = null;     // { key, budget, grid, rows, width, height, level, segments, approximated, complete, failed }
  var retryBudget = 0;
  var nativeDisabled = false;
  var lastStatus = null;
  var stats = { rects: 0, segments: 0, submits: 0, level: 0, tolerance: 0, scans: 0, pixelReads: 0, filledGaps: 0 };

  /*
   * TS-02：脏版本 / 健康检查 / 诊断计数。
   *
   * layoutVersion 是「这一版几何算出来的签名」，只在真的要提交时才更新；
   * layoutVersion 记录上一次**提交成功构造过载荷**的签名，两者相同就整段跳过
   * 完整几何重建与序列化。imageKey 记录扫描缓存属于哪一代图片，图片换了即使
   * 布局没动也必须重扫。
   */
  var layoutVersion = '';
  var scanFailureKey = '';       // 扫描已明确失败的图片代次（不反复重试同一张）
  var visibilityBound = false;   // visibilitychange 监听已绑定
  var retryApplyOnly = false;    // 下一次去抖提交是否是「补发重试」
  var healthTicks = 0;           // 距下次健康检查还剩几个轮询周期
  var healthInterval = HEALTH_BASE_TICKS;
  var metrics = {
    geometryBuilds: 0,     // 真正重建过几何（mapRuns + 控件）的次数
    signatureChecks: 0,    // 几何签名比较次数（廉价路径）
    signatureHits: 0,      // 签名没变、整段跳过的次数
    submits: 0,            // 过桥提交次数
    submitSkipped: 0,      // 因签名未变而跳过的提交
    scanAttempts: 0,
    scanReuses: 0,         // 直接命中缓存的次数
    scanMsTotal: 0,
    scanPixels: 0,
    pollTicks: 0,
    healthChecks: 0,
    pausedTicks: 0,        // 因页面不可见/未启用而空转跳过的次数
    notifySkipped: 0,      // 未开启/不可见时直接跳过的 notify 次数
    visibilityStops: 0,
  };

  /**
   * 页面代际会话（INT-01）：优先用 static/page_session.js 建立的共享编号。
   *
   * 旧实现自己在模块顶层 `makeSessionId()` 生成随机编号，与 app.js 的
   * PAGE_SESSION_ID 不是同一个；两者都调用同一个 registerPageSession 桥，
   * 后登记的模块会覆盖先登记的会话，于是截图请求带的编号被原生判为
   * 「页面已刷新」而拒绝。现在只读共享代际，绝不自行生成。
   *
   * 兜底：page_session.js 缺席时按「本模块自己的代际」生成一次，保证
   * 仍是文档内唯一且可注销，不会退化成空字符串。
   */
  function makeSessionId() {
    return 'ps-' + Date.now().toString(36) + '-'
      + Math.floor(Math.random() * 1e9).toString(36);
  }

  function pageSession() {
    var page = global.SakuraPageSession;
    return page && typeof page.sessionId === 'string' && page.sessionId ? page : null;
  }

  /**
   * 共享代际的兜底存储键。
   *
   * page_session.js 缺席（旧缓存 / 极端加载失败）时，两个模块仍然必须拿到
   * **同一个**编号，否则又回到「后登记覆盖先登记」的老问题。约定：
   * 先执行的那个模块读不到就生成并写回，后执行的直接复用。
   *
   * 注意：这里必须用字面量，不能用 var 常量 —— `resolveSessionId()` 在本文件
   * 顶部就被调用，而 var 的初始化还没执行，会把编号写到 key=undefined 上。
   */
  function sharedFallbackSessionId() {
    var existing = global['__sakuraSharedPageSessionId'];
    if (typeof existing === 'string' && existing) {
      return existing;
    }
    var created = makeSessionId();
    global['__sakuraSharedPageSessionId'] = created;
    return created;
  }

  function resolveSessionId() {
    var page = pageSession();
    return page ? page.sessionId : sharedFallbackSessionId();
  }

  function nativeBridge() {
    return global.SakuraNative || null;
  }

  function doc() {
    return global.document;
  }

  function isBubbleMode() {
    var body = doc() && doc().body;
    return !!(body && body.classList.contains('bubble-mode'));
  }

  function supportsNative() {
    var n = nativeBridge();
    if (!n) return false;
    if (typeof n.isTouchRegionSupported === 'function') {
      try { return !!n.isTouchRegionSupported(); } catch (error) { return false; }
    }
    return typeof n.setTouchRegion === 'function';
  }

  // ---- 页面会话登记（RV-02 / RV-03 / INT-01）--------------------

  /**
   * 登记页面会话。幂等：同一个代际只过一次桥，重复调用不会刷新原生会话
   * （开启/关闭穿透必须保持会话不变，否则会作废在途截图）。
   * 共享代际在文档存续期间不会变化；只有整页重新加载才会产生新编号。
   */
  function registerSession() {
    var page = pageSession();
    if (page) {
      // 共享代际：app.js 与穿透模块登记的是同一个编号；register() 自身幂等
      if (sessionId !== page.sessionId) {
        // 正常情况下不会发生（模块只加载一次）；真发生了就以共享代际为准
        sessionId = page.sessionId;
      }
      if (typeof page.register === 'function') {
        try { page.register(); } catch (error) { /* 桥不可用 */ }
        return;
      }
    }
    // 兜底：共享键保证与 app.js 同编号；registeredSessionId 保证只过一次桥
    if (registeredSessionId === sessionId) {
      return;
    }
    var n = nativeBridge();
    if (n && typeof n.registerPageSession === 'function') {
      try {
        n.registerPageSession(sessionId);
        registeredSessionId = sessionId;
      } catch (error) { /* 桥不可用 */ }
    }
  }

  /**
   * 注销本代际。只注销自己这一代（invalidate 带 sessionId），
   * 旧页的迟到注销清不掉新页刚登记的会话。
   */
  function invalidateSession() {
    var page = pageSession();
    if (page && typeof page.invalidate === 'function') {
      try { page.invalidate(); } catch (error) { /* 桥不可用 */ }
      return;
    }
    var n = nativeBridge();
    if (n && typeof n.invalidatePageSession === 'function') {
      try { n.invalidatePageSession(sessionId); } catch (error) { /* 桥不可用 */ }
    }
    if (registeredSessionId === sessionId) {
      registeredSessionId = '';
    }
  }

  // ---- alpha 扫描 -------------------------------------------------

  function imageKey(img) {
    if (!img) return '';
    var src = img.currentSrc || img.src || '';
    return src + '|' + (img.naturalWidth | 0) + 'x' + (img.naturalHeight | 0);
  }

  function failedScan(code) {
    return {
      width: 0,
      height: 0,
      rows: [],
      approximated: false,
      segments: 0,
      level: 0,
      complete: false,
      failed: code
    };
  }

  /**
   * 把**源图像素足迹** OR 进受限网格：任何 alpha ≥ 阈值的源像素，把它
   * [x,x+1) × [y,y+1) 的足迹与**所有相交的网格格子**取并集。
   *
   * 为什么不是只落一个整数锚点：网格通常小于源图且不一定整除，一个源像素会
   * 横跨两个格子（其中心可能落在 floor 锚点的另一个格子外）。只 OR 锚点会在
   * 非整除尺寸下漏掉像素中心/边缘。这里按
   *   start = floor(v * grid / natural)，endExclusive = ceil((v + 1) * grid / natural)
   * 取所有相交格子并夹边界；每轴最多 2 格，即每像素最多 4 格，成本有界。
   *
   * 内存上限：网格 ≤ MAX_SCAN_W × MAX_SCAN_H（≤ 0.14MB）；
   * 每块 canvas/getImageData ≤ MAX_TILE_PIXELS 像素（≈4MB RGBA）；
   * 源图总像素 > MAX_SCAN_SOURCE_PIXELS 时明确失败（不做偷偷降采样）。
   *
   * 关键：**不做最近邻/平均降采样**。旧实现先把原图缩到 360 宽再聚合，
   * 一条只占 1 源像素的细线会在抽样时被整条丢掉；这里对每个源像素足迹做 OR，
   * 细线/孤立点/底边一定覆盖其全部相交格子，后续 max-pooling 只会扩大不会丢。
   */
  function scanAlphaGrid(img) {
    var d = doc();
    if (!d || !img) return null;
    var naturalW = img.naturalWidth | 0;
    var naturalH = img.naturalHeight | 0;
    if (naturalW <= 0 || naturalH <= 0) return null;
    if (naturalW > MAX_IMAGE_DIM || naturalH > MAX_IMAGE_DIM
        || naturalW * naturalH > MAX_IMAGE_AREA
        || naturalW * naturalH > MAX_SCAN_SOURCE_PIXELS) {
      return failedScan('image-too-large');
    }
    var gridW = Math.min(MAX_SCAN_W, naturalW);
    var idealH = Math.max(1, Math.round(gridW * naturalH / naturalW));
    var gridH = Math.min(idealH, MAX_SCAN_H);
    if (gridW * gridH > MAX_SCAN_CELLS) {
      gridH = Math.max(1, Math.floor(MAX_SCAN_CELLS / gridW));
    }
    var alpha = new Uint8Array(gridW * gridH);
    var canvas = d.createElement('canvas');
    stats.scans++;
    metrics.scanAttempts++;
    var scanStarted = Date.now();
    var tileW = Math.min(naturalW, MAX_TILE_W);
    var tileH = Math.min(naturalH, Math.max(1, Math.floor(MAX_TILE_PIXELS / tileW)));
    for (var sy0 = 0; sy0 < naturalH; sy0 += tileH) {
      var tileRows = Math.min(tileH, naturalH - sy0);
      for (var sx0 = 0; sx0 < naturalW; sx0 += tileW) {
        var tileCols = Math.min(tileW, naturalW - sx0);
        canvas.width = tileCols;
        canvas.height = tileRows;
        var ctx = null;
        try {
          ctx = canvas.getContext('2d', { willReadFrequently: true });
        } catch (error) { ctx = null; }
        if (!ctx) return null;
        ctx.clearRect(0, 0, tileCols, tileRows);
        try {
          if ('imageSmoothingEnabled' in ctx) ctx.imageSmoothingEnabled = false;
        } catch (error) { /* 个别实现只读 */ }
        try {
          // 1:1 取瓦片：同 origin 的图，否则 getImageData 会因污染抛错
          ctx.drawImage(img, sx0, sy0, tileCols, tileRows, 0, 0, tileCols, tileRows);
        } catch (error) { return null; }
        var data = null;
        try {
          data = ctx.getImageData(0, 0, tileCols, tileRows).data;
        } catch (error) { return null; }
        if (!data) return null;
        stats.pixelReads += tileCols * tileRows;
        metrics.scanPixels += tileCols * tileRows;
        for (var ty = 0; ty < tileRows; ty++) {
          var sourceY = sy0 + ty;
          var gy0 = Math.floor(sourceY * gridH / naturalH);
          var gy1 = Math.ceil((sourceY + 1) * gridH / naturalH);
          if (gy0 < 0) gy0 = 0; else if (gy0 >= gridH) gy0 = gridH - 1;
          if (gy1 > gridH) gy1 = gridH;
          if (gy1 <= gy0) gy1 = Math.min(gridH, gy0 + 1);
          var dataBase = ty * tileCols * 4;
          for (var tx = 0; tx < tileCols; tx++) {
            if (data[dataBase + tx * 4 + 3] < ALPHA_MIN) continue;
            var sourceX = sx0 + tx;
            var gx0 = Math.floor(sourceX * gridW / naturalW);
            var gx1 = Math.ceil((sourceX + 1) * gridW / naturalW);
            if (gx0 < 0) gx0 = 0; else if (gx0 >= gridW) gx0 = gridW - 1;
            if (gx1 > gridW) gx1 = gridW;
            if (gx1 <= gx0) gx1 = Math.min(gridW, gx0 + 1);
            for (var gy = gy0; gy < gy1; gy++) {
              var rowBase = gy * gridW;
              for (var gx = gx0; gx < gx1; gx++) {
                alpha[rowBase + gx] = 1;
              }
            }
          }
        }
      }
    }
    return {
      width: gridW,
      height: gridH,
      alpha: alpha,
      naturalW: naturalW,
      naturalH: naturalH,
      approximated: gridW < naturalW || gridH < idealH,
      scanMs: Date.now() - scanStarted
    };
  }

  /** 一个聚合块内是否存在被 OR 标记的格子（保守：任一命中即整块不透明）。 */
  function blockOpaque(grid, originX, originY, block) {
    var x1 = Math.min(grid.width, originX + block);
    var y1 = Math.min(grid.height, originY + block);
    var alpha = grid.alpha;
    for (var y = originY; y < y1; y++) {
      var base = y * grid.width;
      for (var x = originX; x < x1; x++) {
        if (alpha[base + x]) return true;
      }
    }
    return false;
  }

  /**
   * 按 2^level × 2^level 聚合网格，返回行段。
   *
   * 聚合是 max-pooling：只要块里有被标记的格子，整块就按不透明处理。
   * 因此 **level 越大只会多覆盖、绝不会少覆盖**，不透明人物/肢体永不被截断。
   *
   * 行段语义（本轮用户授权）：**只保证外轮廓**。每一行取「最左 → 最右不透明格子」
   * 的一条连续带，行内空隙（发缝、腋下、裙摆分叉、两腿之间）被填平，因此这些
   * 内部位置不再穿透；左右两端仍然严格贴合真实外轮廓，外部透明区照常穿透。
   * 填充是保守方向：只会把内部空隙并进来，不会丢任何一个不透明块，也不会越过
   * 该行的左右外边界。filledGaps 如实记录被并掉的行内空隙数，供验收侧核对
   * 「确实做了外轮廓填充」而不是「悄悄变成整图包围盒」。
   *
   * 返回值带 baseWidth/baseHeight/block：坐标映射必须按**原网格格宽和末尾边界**
   * 还原，不能把 ceil(grid/block) 当均匀新格数（非整除时会漂移）。
   */
  function rowsAtLevel(grid, level) {
    var block = 1 << level;
    var width = Math.max(1, Math.ceil(grid.width / block));
    var height = Math.max(1, Math.ceil(grid.height / block));
    var rows = [];
    var segments = 0;
    var filledGaps = 0;
    for (var y = 0; y < height; y++) {
      var runs = [];
      var x = 0;
      while (x < width) {
        while (x < width && !blockOpaque(grid, x * block, y * block, block)) x++;
        if (x >= width) break;
        var start = x;
        while (x < width && blockOpaque(grid, x * block, y * block, block)) x++;
        runs.push([start, x]);
      }
      if (runs.length > 1) {
        // 外轮廓填充：把行内空隙并进最左/最右之间。左右两端不动，内部不再开孔。
        filledGaps += runs.length - 1;
        runs = [[runs[0][0], runs[runs.length - 1][1]]];
      }
      segments += runs.length;
      rows.push(runs);
    }
    return {
      width: width,
      height: height,
      rows: rows,
      segments: segments,
      filledGaps: filledGaps,
      baseWidth: grid.width,
      baseHeight: grid.height,
      block: block
    };
  }

  /**
   * 行段数 ≤ 预算的最细（最精确）简化级别。
   *
   * 从最细往最粗扫，一旦满足预算立刻返回：真实立绘通常在 level 0/1 就满足，
   * 因此不会把 7 个级别全部算一遍（旧实现无条件算到底，白白多做粗级别的 max-pooling）。
   * 若所有级别都超预算（极端病态图），退到**最粗**级别 —— 它的网格至多
   * 6×H 格，矩形数仍有硬上限，绝不会「整窗回退」。
   */
  function chooseLevel(grid, budget) {
    var coarsest = null;
    for (var level = 0; level <= MAX_LEVEL; level++) {
      var rows = rowsAtLevel(grid, level);
      // 行段数是矩形数的上界：用满足预算的**最细**级别。
      if (rows.segments <= MAX_SEGMENTS && rows.segments <= budget) {
        return { level: level, rows: rows };
      }
      coarsest = { level: level, rows: rows };
    }
    return coarsest || { level: MAX_LEVEL, rows: rowsAtLevel(grid, MAX_LEVEL) };
  }

  /** 轮廓在当前显示尺寸下的边缘容差（CSS 像素）：一个**聚合格子**的边长。 */
  function outlineTolerance(img, scan) {
    var rect = img && img.getBoundingClientRect ? img.getBoundingClientRect() : null;
    if (!rect || !rect.width || !rect.height) return 0;
    var naturalW = img.naturalWidth || 0;
    var naturalH = img.naturalHeight || 0;
    if (!naturalW || !naturalH) return 0;
    var fitScale = Math.min(rect.width / naturalW, rect.height / naturalH);
    if (!fitScale || !isFinite(fitScale)) return 0;
    var baseWidth = scan.baseWidth || scan.width;
    var baseHeight = scan.baseHeight || scan.height;
    var block = scan.block || 1;
    // 一个聚合块覆盖 block 个原网格格：容差 = block × 原格宽（CSS 像素）
    var cellX = (naturalW * fitScale) / Math.max(1, baseWidth) * block;
    var cellY = (naturalH * fitScale) / Math.max(1, baseHeight) * block;
    return Math.max(cellX, cellY);
  }

  /**
   * 扫描立绘 alpha，返回**按预算选好简化级别**的行段与元数据。
   *
   * 返回形状兼容旧调用点（width/height/rows/approximated/segments/complete/failed），
   * 另加 level / baseWidth / baseHeight / block 供坐标映射与诊断使用。
   */
  function scanAlpha(img, budget) {
    var want = (typeof budget === 'number' && budget > 0) ? Math.min(budget, MAX_RECTS) : MAX_RECTS;
    var grid = scanAlphaGrid(img);
    if (!grid) return null;
    if (grid.failed) return grid;   // image-too-large：明确失败，不做残缺轮廓
    var picked = chooseLevel(grid, want);
    return {
      width: picked.rows.width,
      height: picked.rows.height,
      rows: picked.rows.rows,
      baseWidth: picked.rows.baseWidth,
      baseHeight: picked.rows.baseHeight,
      block: picked.rows.block,
      approximated: grid.approximated || picked.level > 0,
      segments: picked.rows.segments,
      filledGaps: picked.rows.filledGaps || 0,
      level: picked.level,
      complete: true,
      failed: ''
    };
  }

  /**
   * 取（并缓存）当前立绘的简化轮廓行段。
   *
   * 缓存键 = 图片 URL + 原图尺寸 + 本次预算。预算随控件数量变化，
   * 变化时只重算级别（网格复用），不必重新读原图瓦片。
   *
   * TS-02：缓存**始终绑定图片代次**（key）。原生报错的重试路径不再清空缓存，
   * 因此同一张图片在整个会话里最多只读一遍源像素；换图/换表情时 key 变化才重扫。
   * 已明确失败的图片（image-too-large 等）记在 scanFailureKey 上，不对同一张
   * 反复重试整图读取。
   */
  function ensureScan(img, budget) {
    var key = imageKey(img);
    if (!key || key.indexOf('|0x0') >= 0) return null;
    var want = (typeof budget === 'number' && budget > 0) ? Math.min(budget, MAX_RECTS) : MAX_RECTS;
    if (scanCache && scanCache.key === key && scanCache.budget === want) {
      metrics.scanReuses++;
      return scanCache;
    }
    if (scanFailureKey === key && (!scanCache || scanCache.key !== key)) {
      // 同一张图已经明确失败过：不重复读原图，直接沿用失败结论
      scanCache = {
        key: key, budget: want, grid: null, failed: scanFailureKey,
        width: 0, height: 0, rows: [], level: 0, block: 1, baseWidth: 0, baseHeight: 0,
        segments: 0, approximated: false, complete: false
      };
      return scanCache;
    }
    var grid = (scanCache && scanCache.key === key && scanCache.grid)
      ? scanCache.grid : scanAlphaGrid(img);
    if (!grid) {
      scanCache = null;
      return null;
    }
    if (grid.failed) {
      scanFailureKey = key;
      scanCache = {
        key: key, budget: want, grid: null, failed: grid.failed,
        width: 0, height: 0, rows: [], level: 0, block: 1, baseWidth: 0, baseHeight: 0,
        segments: 0, approximated: false, complete: false
      };
      return scanCache;
    }
    var picked = chooseLevel(grid, want);
    scanCache = {
      key: key,
      budget: want,
      grid: grid,
      scanMs: grid.scanMs || 0,
      width: picked.rows.width,
      height: picked.rows.height,
      rows: picked.rows.rows,
      baseWidth: picked.rows.baseWidth,
      baseHeight: picked.rows.baseHeight,
      block: picked.rows.block,
      level: picked.level,
      segments: picked.rows.segments,
      filledGaps: picked.rows.filledGaps || 0,
      approximated: grid.approximated || picked.level > 0,
      complete: true,
      failed: ''
    };
    metrics.scanMsTotal += scanCache.scanMs;
    return scanCache;
  }

  // ---- 几何映射 ---------------------------------------------------

  function sameRuns(a, b) {
    if (a === b) return true;
    if (!a || !b || a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i][0] !== b[i][0] || a[i][1] !== b[i][1]) return false;
    }
    return true;
  }

  /**
   * 把扫描行段映射到 CSS 视口矩形。
   *
   * 映射公式与 app.js 的 portraitAlphaAt 完全一致：
   *   object-fit: contain + object-position: bottom center，
   *   rect 用 getBoundingClientRect()（已经包含祖先 transform 的缩放与位移）。
   *
   * 返回 { rects, overflow }。overflow=true 表示矩形数超过上限。
   *
   * 坐标契约（TS-01）：聚合块可能非整除，所以必须用**原网格格宽 cellW/cellH
   * 与 block 的乘积**还原，并把 run/band 的末尾夹到原网格边界：
   *   x_natural = run[0] * block * cellW
   *   x_natural_end = min(run[1] * block, baseWidth) * cellW
   * 这样末尾边界正好落在原图右/下边界，不会出现 ceil(grid/block) 造成的漂移。
   */
  function mapRunsToCssEx(img, scan) {
    var rect = img.getBoundingClientRect();
    if (!rect.width || !rect.height) return { rects: [], overflow: false };
    var naturalW = img.naturalWidth || 0;
    var naturalH = img.naturalHeight || 0;
    if (!naturalW || !naturalH) return { rects: [], overflow: false };
    var fitScale = Math.min(rect.width / naturalW, rect.height / naturalH);
    if (!fitScale || !isFinite(fitScale)) return { rects: [], overflow: false };
    var shownW = naturalW * fitScale;
    var shownH = naturalH * fitScale;
    var offsetX = rect.left + (rect.width - shownW) / 2;
    var offsetY = rect.bottom - shownH;
    var baseWidth = scan.baseWidth || scan.width;
    var baseHeight = scan.baseHeight || scan.height;
    var block = scan.block || 1;
    var cellW = naturalW / Math.max(1, baseWidth);
    var cellH = naturalH / Math.max(1, baseHeight);
    var rects = [];
    var overflow = false;

    function flushBand(startRow, endRow, runs) {
      if (!runs || !runs.length) return;
      var endCellY = Math.min(endRow * block, baseHeight);
      var y0 = offsetY + startRow * block * cellH * fitScale;
      var y1 = offsetY + endCellY * cellH * fitScale;
      if (y1 - y0 < 0.5) return;
      for (var i = 0; i < runs.length; i++) {
        if (rects.length >= MAX_RECTS) {
          overflow = true;
          return;
        }
        var run = runs[i];
        var endCellX = Math.min(run[1] * block, baseWidth);
        var x0 = offsetX + run[0] * block * cellW * fitScale;
        var x1 = offsetX + endCellX * cellW * fitScale;
        if (x1 - x0 < 0.5) continue;
        rects.push([x0, y0, x1, y1]);
      }
    }

    var bandStart = -1;
    var bandRuns = null;
    for (var y = 0; y < scan.rows.length; y++) {
      var runs = scan.rows[y];
      if (bandStart < 0) {
        bandStart = y;
        bandRuns = runs;
        continue;
      }
      if (sameRuns(bandRuns, runs)) continue;
      flushBand(bandStart, y, bandRuns);
      bandStart = y;
      bandRuns = runs;
      if (overflow) break;
    }
    // 只有没有 overflow 时才把最后一个 band 延伸到扫描底部；
    // 一旦截断就绝不能把半截 band 拉到底（那会填出大片假可触摸区）
    if (!overflow) {
      flushBand(bandStart < 0 ? 0 : bandStart, scan.rows.length, bandRuns);
    }
    return { rects: rects, overflow: overflow };
  }

  function mapRunsToCss(img, scan) {
    return mapRunsToCssEx(img, scan).rects;
  }

  // ---- 控件矩形 ---------------------------------------------------

  function isVisible(el) {
    if (!el || el.disabled) return false;
    var view = doc().defaultView;
    var style = view && view.getComputedStyle ? view.getComputedStyle(el) : null;
    if (style) {
      if (style.display === 'none' || style.visibility === 'hidden') return false;
      var opacity = parseFloat(style.opacity);
      if (isFinite(opacity) && opacity <= 0.01) return false;
      if (style.pointerEvents === 'none') return false;
    }
    var rect = el.getBoundingClientRect();
    return rect.width > 1 && rect.height > 1;
  }

  // ---- 小球圆形区域 -----------------------------------------------

  function bubbleCircleRects() {
    var d = doc();
    var wrap = d && d.getElementById('portraitWrap');
    if (!wrap) return [];
    var rect = wrap.getBoundingClientRect();
    if (!rect.width || !rect.height) return [];
    var rx = rect.width / 2;
    var ry = rect.height / 2;
    var cx = rect.left + rx;
    var cy = rect.top + ry;
    var r = Math.min(rx, ry);
    if (r < 2) return [];
    var steps = 12;
    var rects = [];
    for (var i = 0; i < steps; i++) {
      var y0 = -r + (2 * r * i) / steps;
      var y1 = -r + (2 * r * (i + 1)) / steps;
      var ymid = (y0 + y1) / 2;
      var half = Math.sqrt(Math.max(0, r * r - ymid * ymid));
      if (half <= 0.5) continue;
      rects.push([cx - half, cy + y0, cx + half, cy + y1]);
    }
    return rects;
  }

  // ---- 几何脏版本（TS-02）-----------------------------------------

  /** 把浮点数按 0.25 取整，避免亚像素抖动把签名打散。 */
  function q4(value) {
    var number = Number(value);
    if (!isFinite(number)) return 'x';
    return String(Math.round(number * 4));
  }

  function rectNumbers(rect) {
    if (!rect) return 'n';
    return q4(rect.left) + ',' + q4(rect.top) + ',' + q4(rect.width)
      + ',' + q4(rect.height) + ',' + q4(rect.right) + ',' + q4(rect.bottom);
  }

  /**
   * 一次控件扫描同时产出：矩形（用于提交）与**有界状态签名**。
   *
   * 签名只收固定数量的槽位（每个选择器最多 4 个矩形）：控件集合变化必然改变
   * 签名，而元素数量异常大时签名长度仍有硬上限，不会因为 DOM 病态而爆内存。
   */
  function collectControlState() {
    var d = doc();
    var result = { rects: [], overflow: false, state: [], count: 0 };
    if (!d || !d.querySelectorAll) return result;
    var vw = global.innerWidth || 0;
    var vh = global.innerHeight || 0;
    var seen = [];
    var slotsPerSelector = 4;
    for (var s = 0; s < CONTROL_SELECTORS.length; s++) {
      var nodes;
      try {
        nodes = d.querySelectorAll(CONTROL_SELECTORS[s]);
      } catch (error) { continue; }
      var slots = 0;
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (seen.indexOf(el) >= 0) continue;
        seen.push(el);
        if (!isVisible(el)) continue;
        var list = el.getClientRects ? el.getClientRects() : [el.getBoundingClientRect()];
        for (var r = 0; r < list.length; r++) {
          var rect = list[r];
          if (!rect || rect.width <= 1 || rect.height <= 1) continue;
          var x0 = Math.max(0, rect.left);
          var y0 = Math.max(0, rect.top);
          var x1 = Math.min(vw || rect.right, rect.right);
          var y1 = Math.min(vh || rect.bottom, rect.bottom);
          if (x1 - x0 <= 1 || y1 - y0 <= 1) continue;
          result.count++;
          if (slots < slotsPerSelector) {
            result.state.push(CONTROL_SELECTORS[s] + ':' + x0.toFixed(1) + ',' + y0.toFixed(1)
              + ',' + x1.toFixed(1) + ',' + y1.toFixed(1));
            slots++;
          }
          if (result.rects.length >= MAX_RECTS) {
            return { rects: result.rects, overflow: true, state: result.state, count: result.count };
          }
          result.rects.push([x0, y0, x1, y1]);
        }
      }
    }
    return result;
  }

  /** 兼容旧导出：只要矩形。 */
  function collectControlRectsEx() {
    var state = collectControlState();
    return { rects: state.rects, overflow: state.overflow };
  }

  function collectControlRects() {
    return collectControlRectsEx().rects;
  }

  /**
   * 廉价几何签名：不读原图像素、不做 Region 运算、不序列化整个载荷。
   *
   * 覆盖所有会改变输入区域映射或控件可用性的因素：
   *   - 原图 URL + 原图尺寸（换图/换表情）；解码未完成时也不会误判为「没变」；
   *   - 立绘矩形（拖动、缩放、上下位置、CSS 过渡后的最终位置）；
   *   - CSS 视口（innerWidth/innerHeight，窗口尺寸变化）；
   *   - 控件可见性与矩形（卡片显隐、设置面板）；
   *   - 扫描的简化级别/网格（映射公式依赖 baseWidth/baseHeight/block）；
   *   - 边缘容差（写入载荷，取 0.25 CSS 像素精度）。
   *
   * 没有可用原图时返回 null，调用方必须走完整构建（会正确报 not-ready）。
   */
  function computeLayoutSignature(img, controls, controlsState, tolerance) {
    if (!img) return null;
    var naturalW = img.naturalWidth | 0;
    var naturalH = img.naturalHeight | 0;
    if (!naturalW || !naturalH) return null;
    var rect = img.getBoundingClientRect ? img.getBoundingClientRect() : null;
    if (!rect || !rect.width || !rect.height) return null;
    if (isBubbleMode()) {
      return 'b|' + rectNumbers(rect) + '|' + naturalW + 'x' + naturalH
        + '|' + (global.innerWidth || 0) + 'x' + (global.innerHeight || 0);
    }
    return 'o|' + imageKey(img) + '|' + rectNumbers(rect)
      + '|' + (global.innerWidth || 0) + 'x' + (global.innerHeight || 0)
      + '|c' + controls.count + '|' + controlsState.join(';')
      + '|g' + (scanCache ? (scanCache.baseWidth + 'x' + scanCache.baseHeight
        + '.' + scanCache.block + '.' + scanCache.level) : 'none')
      + '|t' + q4(tolerance);
  }


  /**
   * 不可自动重试的失败原因：重试也只会重复失败。
   *
   * TS-02：`payload-rejected` 移出这个集合 —— 原生拒绝既可能是载荷真的不合法，
   * 也可能是 surface 暂时没就绪（`onTouchRegionRejected` 的 reason 只给稳定码）。
   * 旧实现把它当成永久失败，于是「一次拒绝」后重试预算被清零、模块永远停在
   * `payload-rejected`，原生恢复也不再重试。现在它走有界重试；真正的确定性
   * 失败（超限、编码失败、API 不支持）仍然一次性停手。
   */
  function isPermanent(reasonCode) {
    return reasonCode === 'api-unsupported'
      || reasonCode === 'bridge-missing'
      || reasonCode === 'encode-failed'
      || reasonCode === 'payload-too-large';
  }

  /**
   * 失败安全：把窗口恢复成整窗可交互，并按需要重试。
   *
   * TS-02：**不清 scanCache**。原图 alpha 网格与「原生这一次为什么拒绝」无关，
   * 清缓存只会让每次重试都重读一张 1.8M 像素的原图（实测 3.6M 像素/秒）。
   * 图片真的换了时 ensureScan 的 key 不同，会自动重扫。
   */
  function failSafe(reasonCode) {
    var wasActive = active;
    reason = reasonCode;
    active = false;
    pendingRevision = 0;
    pendingSignature = '';
    lastSubmitted = '';
    // 新故障可以开始恢复；同一故障的重试不能重新补满预算。
    retryBudget = isPermanent(reasonCode) ? 0
      : (wasActive ? MAX_NATIVE_RETRIES : retryBudget);
    var body = doc() && doc().body;
    if (body) body.classList.remove('pass-through-active');
    var n = nativeBridge();
    if (n && typeof n.setPassThroughEnabled === 'function') {
      try { n.setPassThroughEnabled(false); } catch (error) { /* 忽略 */ }
      nativeDisabled = true;
    }
  }

  /** 重新开启原生穿透（失败后受控恢复）。 */
  function ensureNativeEnabled() {
    var n = nativeBridge();
    if (!n || typeof n.setPassThroughEnabled !== 'function') return;
    if (!nativeDisabled) return;
    try {
      n.setPassThroughEnabled(true);
      nativeDisabled = false;
    } catch (error) {
      nativeDisabled = true;
    }
  }

  function nativeErrorReason(code) {
    var text = code == null ? '' : String(code);
    if (text.indexOf('unsupported') >= 0) return 'api-unsupported';
    if (text.indexOf('surface-not-ready') >= 0) return 'surface-not-ready';
    if (text.indexOf('no-surface-control') >= 0) return 'surface-not-ready';
    return 'native-error';
  }

  /** 只有原生 ACK 才认为生效；写 lastSubmitted 以便去重。 */
  function ack(rev, signature) {
    if (rev >= revision) revision = rev;
    active = true;
    reason = 'applied';
    lastSubmitted = signature;
    pendingRevision = 0;
    pendingSignature = '';
    retryBudget = 0;
    nativeDisabled = false;
    var body = doc() && doc().body;
    if (body) body.classList.add('pass-through-active');
  }

  // ---- 提交 -------------------------------------------------------

  /**
   * 构造本轮的输入区域载荷。
   *
   * TS-02：先算**廉价几何签名**，与上次真正构造过载荷的签名一致时整段跳过
   * （不读原图、不做 Region 映射、不量控件、不序列化）。签名覆盖图片代次、
   * 立绘矩形、视口、控件可见性与矩形、扫描级别与容差，因此「跳过」不会漏掉
   * 任何会改变输入区域的变化。真正重建时才累加 metrics.geometryBuilds。
   *
   * @param knownControls  调用方已扫描过的控件状态；不传则内部扫描
   */
  function buildPayload(knownControls) {
    var d = doc();
    var img = d && d.getElementById('portrait');
    var rects;
    var approx = false;
    var level = 0;
    var tolerance = 0;
    var segments = 0;
    var filledGaps = 0;
    var controls = knownControls || null;
    var signature = '';
    if (isBubbleMode()) {
      rects = bubbleCircleRects();
      if (!rects.length) {
        reason = 'bubble-not-ready';
        return null;
      }
    } else {
      // 先量控件：控件矩形是固定且必须保留的，轮廓预算要把它们扣掉，
      // 保证两者相加不超原生上限（不是「超了才整窗回退」）。
      if (!controls) controls = collectControlState();
      if (controls.overflow || controls.rects.length >= MAX_RECTS) {
        // 控件自身就超限：这是真正的容量问题，不是轮廓复杂。
        failSafe('contour-too-complex');
        return null;
      }
      var outlineBudget = Math.max(64, MAX_RECTS - controls.rects.length);
      var scan = ensureScan(img, outlineBudget);
      if (!scan) {
        failSafe('alpha-scan-failed');
        return null;
      }
      if (scan.complete === false || scan.failed) {
        failSafe(scan.failed || 'alpha-scan-failed');
        return null;
      }
      approx = !!scan.approximated;
      level = scan.level || 0;
      segments = scan.segments || 0;
      filledGaps = scan.filledGaps || 0;
      stats.segments = segments;
      stats.level = level;
      stats.filledGaps = filledGaps;
      tolerance = outlineTolerance(img, scan);
      // 行段缓存只含网格几何；映射到 CSS 每次都按当前 rect 重算（缩放/拖动/旋转后不陈旧）。
      var mapped = mapRunsToCssEx(img, scan);
      if (mapped.overflow || mapped.rects.length > outlineBudget) {
        // 预算内仍溢出（理论上聚合到最粗级别不会发生）：退到保守的整图轮廓，
        // 由原生侧再裁决；不截断、也不留残缺行段。
        failSafe('contour-too-complex');
        return null;
      }
      rects = mapped.rects.concat(controls.rects);
    }
    if (!rects.length) {
      failSafe('empty-region');
      return null;
    }
    if (rects.length > MAX_RECTS) {
      failSafe('contour-too-complex');
      return null;
    }
    // 用**实际**扫描结果复算一次签名：首次提交时预判签名里还没有网格信息，
    // 复算后记进 layoutVersion，避免「扫描刚建立」白重建一轮。
    signature = computeLayoutSignature(img, controls, controls ? controls.state : [],
      isBubbleMode() ? 0 : tolerance);
    if (!signature) {
      failSafe('alpha-scan-failed');
      return null;
    }
    metrics.geometryBuilds++;
    stats.rects = rects.length;
    stats.tolerance = tolerance;
    approximated = approx;
    return {
      v: 1,
      revision: revision + 1,
      pageSessionId: sessionId,
      cssW: global.innerWidth || 1,
      cssH: global.innerHeight || 1,
      dpr: global.devicePixelRatio || 1,
      portraitKey: global.__sakuraPortraitKey || '',
      mode: isBubbleMode() ? 'bubble' : 'overlay',
      approximated: approx,
      level: level,
      tolerance: Math.round(tolerance * 100) / 100,
      segments: segments,
      // 外轮廓语义：fill=1 表示行内空隙已被填平（发缝/内部孔洞不再穿透），
      // 便于原生与验收侧如实区分「外轮廓填充」和「聚合近似」两件事。
      interiorFill: filledGaps > 0 ? 1 : 0,
      filledGaps: filledGaps,
      rects: rects,
      layoutSignature: signature
    };
  }

  /**
   * 立即提交一次（去抖由 scheduleSubmit 负责）。
   *
   * 关键：提交被受理（queued）≠ 已生效。这里只记录 pending，等
   * touchRegionStatus() 回报 revision 已应用才 ack；失败不缓存成功签名。
   *
   * TS-02 的两级判断：
   *   1. **廉价几何签名**：与上次真正构造过载荷的签名一致、且没有在途 pending
   *      时，直接跳过 —— 不量控件、不映射 Region、不序列化整份载荷；
   *   2. 签名变了才走完整构建，并只在**原生受理/成功**后才把签名记为已提交版本。
   *
   * @param applyOnly true = 「补发重试」：签名没变时**连一次 DOM 量取都不做**，
   *        直接用上一次已受理的几何补发一次（原生重试不该按轮重算几何）。
   */
  function submitNow(applyOnly) {
    if (!desired || isPageHidden()) return false;
    var n = nativeBridge();
    if (!n || typeof n.setTouchRegion !== 'function') {
      failSafe('bridge-missing');
      return false;
    }
    if (!supportsNative()) {
      failSafe('api-unsupported');
      return false;
    }
    var controls = null;
    var preSignature = null;
    if (applyOnly && layoutVersion) {
      // 复用上一次已构造的几何：原生恢复时补发，不重新量布局/不重扫原图
      preSignature = layoutVersion;
    } else {
      var d = doc();
      var img = d && d.getElementById('portrait');
      // 廉价预判：控件可见性/矩形、立绘矩形、视口、图片代次、扫描级别与容差
      controls = isBubbleMode() ? null : collectControlState();
      var preTolerance = (!isBubbleMode() && scanCache && img)
        ? outlineTolerance(img, scanCache) : 0;
      preSignature = computeLayoutSignature(img, controls, controls ? controls.state : [],
        preTolerance);
      metrics.signatureChecks++;
      if (preSignature && preSignature === layoutVersion
          && pendingRevision === 0 && active) {
        metrics.signatureHits++;
        metrics.submitSkipped++;
        reason = 'unchanged';
        return false;
      }
    }
    var payload = buildPayload(controls);
    if (!payload) return false;
    // revision 不参与「几何是否变化」的比较：否则每次自增都会让去重失效。
    // 先按 revision=0 生成签名，真的变了才赋新值。
    payload.revision = 0;
    var signature;
    try { signature = JSON.stringify(payload); } catch (error) { failSafe('encode-failed'); return false; }
    if (signature.length > MAX_PAYLOAD_BYTES) {
      failSafe('payload-too-large');
      return false;
    }
    if (pendingRevision > 0 && signature === pendingSignature) {
      // 已受理但还没拿到原生 ACK：不要重复过桥
      reason = 'applying';
      metrics.submitSkipped++;
      return false;
    }
    var nextRevision = revision + 1;
    payload.revision = nextRevision;
    var text;
    try { text = JSON.stringify(payload); } catch (error) { failSafe('encode-failed'); return false; }
    var code;
    try {
      code = n.setTouchRegion(text);
    } catch (error) {
      failSafe('bridge-error');
      return false;
    }
    stats.submits++;
    metrics.submits++;
    if (code === 'applied' || code === 'queued') {
      pendingRevision = nextRevision;
      pendingSignature = signature;
      // 载荷里带的是 buildPayload 里复核过的签名（可能因首次扫描而更新），
      // 记它而不是预判签名，避免「扫描刚建立」多触发一次无意义重建。
      layoutVersion = payload.layoutSignature || signature;
      if (code === 'applied') {
        ack(nextRevision, signature);
      } else {
        reason = 'applying';
      }
      return true;
    }
    if (code === 'stale') {
      // 原生已有更新的一版；等下一轮几何/ACK，不必失败
      reason = 'stale';
      return false;
    }
    if (code === 'not-registered') {
      registerSession();
      reason = 'session-not-registered';
      return false;
    }
    if (code === 'disabled') {
      nativeDisabled = true;
      reason = 'native-disabled';
      return false;
    }
    if (code === 'unsupported') {
      failSafe('api-unsupported');
      return false;
    }
    // 原生拒绝了这一次（可能只是 surface 暂时不可用）：走有界重试，不判死
    failSafe('native-error');
    return false;
  }

  function scheduleSubmit() {
    if (!desired) return;
    if (submitTimer) return;
    var raf = global.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };
    submitTimer = setTimeout(function () {
      submitTimer = 0;
      var applyOnly = retryApplyOnly;
      retryApplyOnly = false;
      raf(function () { submitNow(applyOnly); });
    }, SUBMIT_DEBOUNCE_MS);
  }

  /** 查询原生 ACK：applied revision 追平 pending 才算成功。 */
  function syncAck() {
    var n = nativeBridge();
    if (!n || typeof n.touchRegionStatus !== 'function') return;
    var status = null;
    try { status = JSON.parse(n.touchRegionStatus()); } catch (error) { return; }
    lastStatus = status;
    if (status && status.error) {
      failSafe(nativeErrorReason(status.error));
      return;
    }
    if (pendingRevision > 0 && status && typeof status.revision === 'number'
        && status.revision >= pendingRevision) {
      ack(pendingRevision, pendingSignature);
    }
  }

  // ---- 生命周期 ---------------------------------------------------

  /**
   * 读取用户对「透明处穿透」的持久意图。
   *
   * 有原生桥时**原生偏好就是唯一事实来源**：`passThrough.value` 已经是原生
   * 解析后的值（用户选过就是选择值，没选过就是原生默认值）。旧实现在
   * `has !== true` 时退回读本页 localStorage —— 而 App↔浮窗同步用的是
   * `persist:false`（不写 localStorage），于是本页可能残留一个旧的 '1'，
   * 在用户已经从 App 关掉穿透后把浮窗重新打开。这里彻底去掉这条回退。
   *
   * 只有完全没有原生 `displayPreferences`（纯浏览器）时才读 localStorage。
   */
  function readPersisted() {
    var n = nativeBridge();
    if (n && typeof n.displayPreferences === 'function') {
      try {
        var prefs = JSON.parse(n.displayPreferences());
        var entry = prefs && prefs.passThrough;
        if (entry && typeof entry.value === 'boolean') {
          return !!entry.value;
        }
      } catch (error) { /* 解析失败：宁可取默认，也不读可能过期的本地镜像 */ }
      return null;
    }
    try {
      var raw = global.localStorage && global.localStorage.getItem(STORAGE_KEY);
      if (raw === '1') return true;
      if (raw === '0') return false;
    } catch (error) { /* 存储不可用 */ }
    return null;
  }

  /**
   * 写入用户意图：原生偏好是唯一事实来源（可被 App 读回校验），
   * localStorage 只作为无原生桥时的兜底镜像。
   */
  function writePersisted(value) {
    var n = nativeBridge();
    if (n && typeof n.setDisplayPreferences === 'function') {
      try {
        n.setDisplayPreferences(JSON.stringify({ passThrough: !!value }));
      } catch (error) { /* 桥不可用：仍写本地镜像 */ }
    }
    try {
      if (global.localStorage) global.localStorage.setItem(STORAGE_KEY, value ? '1' : '0');
    } catch (error) { /* 忽略 */ }
  }

  function setEnabled(enabled, options) {
    var opts = options || {};
    desired = !!enabled;
    if (opts.persist !== false) writePersisted(desired);
    if (!desired) {
      active = false;
      reason = 'disabled';
      lastSubmitted = '';
      pendingRevision = 0;
      pendingSignature = '';
      retryBudget = 0;
      // TS-02：关闭后不再保留任何定时任务；几何版本清空，下次开启必须重算一次
      stopPolling();
      cancelScheduledSubmit();
      layoutVersion = '';
      var body = doc() && doc().body;
      if (body) body.classList.remove('pass-through-active');
      var n = nativeBridge();
      if (n && typeof n.setPassThroughEnabled === 'function') {
        try { n.setPassThroughEnabled(false); } catch (error) { /* 忽略 */ }
      }
      nativeDisabled = false;
      return true;
    }
    registerSession();
    if (!supportsNative()) {
      failSafe('api-unsupported');
      return false;
    }
    var n2 = nativeBridge();
    if (n2 && typeof n2.setPassThroughEnabled === 'function') {
      try { n2.setPassThroughEnabled(true); } catch (error) { /* 忽略 */ }
    }
    nativeDisabled = false;
    retryBudget = MAX_NATIVE_RETRIES;
    healthInterval = HEALTH_BASE_TICKS;
    healthTicks = 1;
    // 冻结位移动画后再算几何：否则静态 mask 会与动态立绘错位
    var body2 = doc() && doc().body;
    if (body2) body2.classList.add('pass-through-active');
    /*
     * 不做 scanCache = null 的整图重扫：原图 alpha 网格与窗口几何无关，
     * 坐标映射每次提交都会按当前 rect 重算。旧实现每次开启都要清 3 次缓存，
     * 触发最多 3 次全原图瓦片扫描（1316×1376 ≈ 1.8M 像素），是真机卡顿主因之一。
     * 只有图片本身变化时（ensureScan 的 key 不同）才会重新扫描。
     */
    scheduleSubmit();
    // 动画/过渡在下一帧才真正停下，再补一次（仍然只重算坐标，不重扫原图）
    if (global.requestAnimationFrame) {
      global.requestAnimationFrame(function () { scheduleSubmit(); });
    }
    setTimeout(function () { scheduleSubmit(); }, 320);
    startPolling();
    return true;
  }

  function install() {
    var d = doc();
    if (!d) return false;
    installed = true;
    registerSession();
    var persisted = readPersisted();
    /*
     * 默认：没有用户偏好时，API33+ 的悬浮窗按历史行为开启。
     * 用户明确选过（原生偏好 has=true 或本页 localStorage）就严格按选择执行，
     * App 与浮窗共用同一份原生偏好，不会互相覆盖。
     */
    var want = persisted === null ? true : persisted;
    if (!supportsNative()) {
      reason = 'api-unsupported';
      bindVisibility();
      startPolling();
      return false;
    }
    setEnabled(want, { persist: false });
    if (d.addEventListener) {
      // 窗口几何变化只需重算坐标映射（提交时按当前 rect 计算），
      // **不需要**重扫原图 alpha 网格。
      global.addEventListener('resize', function () { scheduleSubmit(); });
      global.addEventListener('orientationchange', function () {
        setTimeout(scheduleSubmit, 260);
      });
      // pagehide 注销由 page_session.js 在捕获阶段统一处理（INT-01）：
      // 两个模块各自再注册一次会重复注销，且旧页的注销语义不一致。
    }
    bindVisibility();
    startPolling();
    return true;
  }

  /**
   * 页面可见性绑定（TS-02）。
   *
   * 页面不可见（切到别的 App / 息屏 / 悬浮窗被隐藏）时静态轮廓已经不需要维护：
   * 继续轮询只会白白量布局、过桥读状态。这里不可见就**停止全部工作**，
   * 恢复可见时再重启轮询并重新提交一次（几何可能已经变了，脏签名会兜住）。
   */
  function bindVisibility() {
    var d = doc();
    if (visibilityBound || !d || !d.addEventListener) return;
    visibilityBound = true;
    d.addEventListener('visibilitychange', function () {
      if (d.hidden) {
        metrics.visibilityStops++;
        stopPolling();
        cancelScheduledSubmit();
        return;
      }
      if (!desired) return;
      healthInterval = HEALTH_BASE_TICKS;
      healthTicks = 1;
      startPolling();
      scheduleSubmit();
    });
  }

  /** 取消已排队的去抖提交（关闭/不可见时不该再补一发）。 */
  function cancelScheduledSubmit() {
    if (!submitTimer) return;
    try { clearTimeout(submitTimer); } catch (error) { /* 忽略 */ }
    submitTimer = 0;
    retryApplyOnly = false;
  }

  function isPageHidden() {
    var d = doc();
    return !!(d && d.hidden);
  }

  function startPolling() {
    if (pollTimer) return;
    // 布局/卡片/角色切换都会改变几何；低频轮询 + **廉价几何签名**去重。
    //
    // TS-02：
    //   - 稳定态只读签名（不量控件几何、不映射 Region、不序列化载荷），
    //     真正变化才会重建并过桥；
    //   - ACK 只在真有 pending 时每轮查询；稳定态退化为指数退避的健康检查
    //     （2 → 60 个周期，≈1s → 30s），不再每 500ms 过桥读一次 JSON 状态；
    //   - 页面不可见时整个轮询停下（bindVisibility 负责停/启）。
    pollTimer = setInterval(function () {
      metrics.pollTicks++;
      if (!desired) {
        metrics.pausedTicks++;
        return;
      }
      if (isPageHidden()) {
        metrics.pausedTicks++;
        stopPolling();
        return;
      }
      if (pendingRevision > 0) {
        syncAck();
        return;
      }
      if (!active && retryBudget > 0) {
        // 受控恢复：重新开启 native 并用**上一次已构造的几何**补发一次，
        // 预算有限。
        //
        // TS-02：重试既不清 scanCache（原图没变就没必要重读 1.8M 像素），
        // 也不重新量控件 —— 布局真的变了的时候，正常轮的几何签名会发现并重建。
        retryBudget--;
        nativeDisabled = true;
        ensureNativeEnabled();
        cancelScheduledSubmit();
        retryApplyOnly = true;
        scheduleSubmit();
        return;
      }
      if (!active) {
        // 预算用尽后只检查状态；明确观察到原生错误消失才开始新一轮。
        // 不能把一次空错误码当作“刚才被拒绝的载荷现在合法”。
        healthTicks--;
        if (healthTicks > 0) return;
        healthTicks = HEALTH_BASE_TICKS;
        var priorError = lastStatus && lastStatus.error;
        syncAck();
        if (priorError && lastStatus && !lastStatus.error) {
          retryBudget = MAX_NATIVE_RETRIES;
          ensureNativeEnabled();
          scheduleSubmit();
        }
        return;
      }
      if (active && (reason === 'unchanged' || reason === 'applied')) {
        // 稳定态：低频健康检查，其余周期一个 DOM 调用都不做
        healthTicks--;
        if (healthTicks > 0) {
          metrics.submitSkipped++;
          return;
        }
        healthTicks = healthInterval;
        if (healthInterval < HEALTH_MAX_TICKS) healthInterval *= 2;
        metrics.healthChecks++;
        syncAck();
        if (lastStatus && lastStatus.error) return;      }
      scheduleSubmit();
    }, POLL_MS);
    if (pollTimer && pollTimer.unref) pollTimer.unref();
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = 0;
    }
  }

  function status() {
    return {
      installed: installed,
      supported: supportsNative(),
      active: active,
      desired: desired,
      reason: reason,
      approximated: approximated,
      rects: stats.rects,
      segments: stats.segments,
      filledGaps: stats.filledGaps,
      interiorFill: stats.filledGaps > 0,
      level: stats.level,
      tolerance: stats.tolerance,
      maxRects: MAX_RECTS,
      submits: stats.submits,
      scans: stats.scans,
      pixelReads: stats.pixelReads,
      sessionId: sessionId,
      revision: revision,
      pending: pendingRevision > 0,
      pendingRevision: pendingRevision,
      retryBudget: retryBudget,
      nativeDisabled: nativeDisabled,
      lastError: lastStatus && lastStatus.error ? lastStatus.error : '',
      // TS-02：去敏计数（几何重建 / 签名命中 / 扫描复用 / 轮询与健康检查）
      geometryBuilds: metrics.geometryBuilds,
      signatureChecks: metrics.signatureChecks,
      signatureHits: metrics.signatureHits,
      submitSkipped: metrics.submitSkipped,
      scanAttempts: metrics.scanAttempts,
      scanReuses: metrics.scanReuses,
      scanMsTotal: metrics.scanMsTotal,
      pollTicks: metrics.pollTicks,
      healthChecks: metrics.healthChecks,
      pausedTicks: metrics.pausedTicks,
      notifySkipped: metrics.notifySkipped,
      visibilityStops: metrics.visibilityStops,
      layoutSignature: layoutVersion
    };
  }

  /**
   * 立绘加载成功/失败、角色切换、卡片/配置面板变化时由 app.js 调用。
   *
   * <p>**不在这里清空 alpha 网格缓存**：卡片显隐、配置面板开合只是几何变化，
   * 原图 alpha 与坐标映射无关。旧实现每次 notify 都清缓存，导致开一次设置
   * 面板就重扫整张 1.8M 像素原图。图片真的换了时 {@link ensureScan} 会因为
   * key（URL + 原图尺寸）不同而自动重扫。
   */
  function notify(reasonCode) {
    if (reasonCode) reason = reasonCode;
    if (reasonCode === 'portrait-failed') {
      failSafe('portrait-failed');
      return;
    }
    /*
     * TS-02：没开穿透、或页面根本不可见时，这次通知不需要量任何几何。
     * 旧实现会照常排一次去抖提交（enabled 时每秒两次完整重建）。
     * app.js 会在换表情/缩放/卡片变化时频繁调用本函数，这里必须廉价。
     * 重新开启时由 setEnabled 负责排第一次提交；恢复可见时由
     * visibilitychange 负责，两者都覆盖了「漏掉这次通知」的风险。
     */
    if (!desired || isPageHidden()) {
      metrics.notifySkipped++;
      return;
    }
    // 内容变化：重置恢复预算与健康检查节奏，图片恢复后允许重新开启 native。
    // **不清 scanCache / layoutVersion**：几何脏签名会自己发现变化，
    // 图片换了时 ensureScan 的 key 不同也会自动重扫。
    if (!isPermanent(reason)) retryBudget = MAX_NATIVE_RETRIES;
    healthInterval = HEALTH_BASE_TICKS;
    healthTicks = 1;
    if (!active) ensureNativeEnabled();
    scheduleSubmit();
  }

  global.SakuraTouchRegion = {
    install: install,
    setEnabled: setEnabled,
    isEnabled: function () { return desired; },
    isInstalled: function () { return installed; },
    submitNow: submitNow,
    notify: notify,
    status: status,
    stopPolling: stopPolling,
    _polling: function () { return !!pollTimer; },
    _metrics: function () { return metrics; },
    // 导出纯函数便于离线单测
    _scanAlpha: scanAlpha,
    _scanAlphaGrid: scanAlphaGrid,
    _rowsAtLevel: rowsAtLevel,
    _chooseLevel: chooseLevel,
    _outlineTolerance: outlineTolerance,
    _mapRunsToCss: mapRunsToCss,
    _mapRunsToCssEx: mapRunsToCssEx,
    _collectControlRects: collectControlRects,
    _collectControlState: collectControlState,
    _computeLayoutSignature: computeLayoutSignature,
    _bubbleCircleRects: bubbleCircleRects,
    _registerSession: registerSession,
    _invalidateSession: invalidateSession,
    _syncAck: syncAck,
    _limits: {
      maxRects: MAX_RECTS,
      maxSegments: MAX_SEGMENTS,
      maxLevel: MAX_LEVEL,
      maxScanW: MAX_SCAN_W,
      maxScanH: MAX_SCAN_H,
      maxPayloadBytes: MAX_PAYLOAD_BYTES,
      maxImageDim: MAX_IMAGE_DIM,
      maxImageArea: MAX_IMAGE_AREA,
      maxTilePixels: MAX_TILE_PIXELS,
      maxScanSourcePixels: MAX_SCAN_SOURCE_PIXELS,
      pollMs: POLL_MS,
      healthBaseTicks: HEALTH_BASE_TICKS,
      healthMaxTicks: HEALTH_MAX_TICKS
    },
    _sessionId: function () { return sessionId; },
    _reset: function () {
      revision = 0;
      // INT-01：不再自行生成新代际 —— 共享代际由 page_session.js 拥有，
      // 这里只重新读取，避免与 app.js 的会话编号分叉。
      sessionId = resolveSessionId();
      registeredSessionId = '';
      active = false;
      desired = false;
      reason = 'idle';
      approximated = false;
      lastSubmitted = '';
      pendingRevision = 0;
      pendingSignature = '';
      scanCache = null;
      retryBudget = 0;
      nativeDisabled = false;
      lastStatus = null;
      layoutVersion = '';
      scanFailureKey = '';
      healthTicks = 0;
      healthInterval = HEALTH_BASE_TICKS;
      stats = { rects: 0, segments: 0, submits: 0, level: 0, tolerance: 0, scans: 0, pixelReads: 0, filledGaps: 0 };
      metrics = {
        geometryBuilds: 0, signatureChecks: 0, signatureHits: 0, submits: 0, submitSkipped: 0,
        scanAttempts: 0, scanReuses: 0, scanMsTotal: 0, scanPixels: 0,
        pollTicks: 0, healthChecks: 0, pausedTicks: 0, notifySkipped: 0, visibilityStops: 0
      };
    }
  };
})(typeof window !== 'undefined' ? window : globalThis);
