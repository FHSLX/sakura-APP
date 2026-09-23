'use strict';

const BOOT = window.__SAKURA__ || {};
const TOKEN = BOOT.token || '';

const el = {
  stage: document.getElementById('stage'),
  portrait: document.getElementById('portrait'),
  portraitWrap: document.getElementById('portraitWrap'),
  mediaToggle: document.getElementById('mediaToggle'),
  mediaPanel: document.getElementById('mediaPanel'),
  mediaMenu: document.getElementById('mediaMenu'),
  mediaInfo: document.getElementById('mediaInfo'),
  mediaInfoText: document.getElementById('mediaInfoText'),
  mediaInfoThumb: document.getElementById('mediaInfoThumb'),
  mediaInfoClear: document.getElementById('mediaInfoClear'),
  name: document.getElementById('name'),
  voiceToggle: document.getElementById('voiceToggle'),
  clearButton: document.getElementById('clearButton'),
  settingsButton: document.getElementById('settingsButton'),
  configPanel: document.getElementById('configPanel'),
  configClose: document.getElementById('configClose'),
  scaleRange: document.getElementById('scaleRange'),
  scaleNumber: document.getElementById('scaleNumber'),
  scaleValueInline: document.getElementById('scaleValueInline'),
  scaleSmaller: document.getElementById('scaleSmaller'),
  scaleBigger: document.getElementById('scaleBigger'),
  scaleReset: document.getElementById('scaleReset'),
  portraitYRange: document.getElementById('portraitYRange'),
  portraitYNumber: document.getElementById('portraitYNumber'),
  portraitYValue: document.getElementById('portraitYValue'),
  floatToggle: document.getElementById('floatToggle'),
  fontRange: document.getElementById('fontRange'),
  fontNumber: document.getElementById('fontNumber'),
  fontValue: document.getElementById('fontValue'),
  dialogXRange: document.getElementById('dialogXRange'),
  dialogXNumber: document.getElementById('dialogXNumber'),
  dialogXValue: document.getElementById('dialogXValue'),
  dialogYRange: document.getElementById('dialogYRange'),
  dialogYNumber: document.getElementById('dialogYNumber'),
  dialogYValue: document.getElementById('dialogYValue'),
  showOriginalToggle: document.getElementById('showOriginalToggle'),
  showTranslationToggle: document.getElementById('showTranslationToggle'),
  autoScrollToggle: document.getElementById('autoScrollToggle'),
  autoplayToggle: document.getElementById('autoplayToggle'),
  ttsToggle: document.getElementById('ttsToggle'),
  saveVoice: document.getElementById('saveVoice'),
  voiceSaveHint: document.getElementById('voiceSaveHint'),
  connAddress: document.getElementById('connAddress'),
  charList: document.getElementById('charList'),
  charHint: document.getElementById('charHint'),
  restartButton: document.getElementById('restartButton'),
  restartHint: document.getElementById('restartHint'),
  connToken: document.getElementById('connToken'),
  tokenReveal: document.getElementById('tokenReveal'),
  tokenCopy: document.getElementById('tokenCopy'),
  connCharacter: document.getElementById('connCharacter'),
  connTts: document.getElementById('connTts'),
  connHint: document.getElementById('connHint'),
  switchServer: document.getElementById('switchServer'),
  petSection: document.getElementById('petSection'),
  petToggle: document.getElementById('petToggle'),
  petHint: document.getElementById('petHint'),
  batteryButton: document.getElementById('batteryButton'),
  chatButton: document.getElementById('chatButton'),
  quickVoice: document.getElementById('quickVoice'),
  quickClear: document.getElementById('quickClear'),
  msgNav: document.getElementById('msgNav'),
  msgPrev: document.getElementById('msgPrev'),
  msgNext: document.getElementById('msgNext'),
  msgCounter: document.getElementById('msgCounter'),
  msgText: document.getElementById('msgText'),
  msgName: document.getElementById('msgName'),
  msgBody: document.getElementById('msgBody'),
  bubbles: document.getElementById('bubbles'),
  hint: document.getElementById('hint'),
  form: document.getElementById('form'),
  /* measure() 要用它算输入栏高度。
     漏掉这个引用会让输入栏高度永远读到 0（回退成 70px），
     于是卡片上限算大、压住输入栏 —— 和当初 el.msgBody 漏掉导致卡片空白是同类 bug。 */
  composer: document.getElementById('composer'),
  miniBar: document.getElementById('miniBar'),
  miniButton: document.getElementById('miniButton'),
  miniConfigButton: document.getElementById('miniConfigButton'),
  saveState: document.getElementById('saveState'),
  saveAllButton: document.getElementById('saveAllButton'),
  pressFeedbackToggle: document.getElementById('pressFeedbackToggle'),
  screenPermButton: document.getElementById('screenPermButton'),
  screenPermHint: document.getElementById('screenPermHint'),
  bubbleHideRange: document.getElementById('bubbleHideRange'),
  bubbleHideNumber: document.getElementById('bubbleHideNumber'),
  bubbleHideValue: document.getElementById('bubbleHideValue'),
  text: document.getElementById('text'),
  image: document.getElementById('image'),
  send: document.getElementById('send'),
  screenshotButton: document.getElementById('screenshotButton'),
  status: document.getElementById('status'),
};

const state = {
  characterId: '',
  // 空字符串而不是某个角色名：全新安装时电脑端可能还没有角色，
  // 写死名字会显示成错误的人。真正有角色后由 /api/state 覆盖。
  displayName: '',
  portraitByKey: new Map(),
  portraitAlias: new Map(),
  portraitOverrides: new Map(),
  defaultPortraitKey: '',
  currentPortraitKey: '',
  tones: [],
  voiceEnabled: !!BOOT.tts,
  autoplay: !!BOOT.autoplay,
  audioUnlocked: false,
  busy: false,
  pending: [],
  playing: false,
  dirtyPlayback: false,
  lastText: '',
  /** 立绘缩放倍率，1.0 表示充满屏幕高度。 */
  portraitScale: 1.0,
  pinchStart: 0,
  pinchStartScale: 1.0,
  /** 配置页是否展开 */
  configOpen: false,
  /** 是否显示日文原文（中文始终显示） */
  showOriginal: true,
  showTranslation: true,
  /** 收到新消息是否自动滚到底部 */
  autoScroll: true,
  /** 服务端可编辑的开关，避免和服务端设置不一致 */
  serverSettings: { autoplay: true, tts_enabled: true },
  /** 连接信息（用于配置页展示） */
  serverAddress: '',
  serverToken: '',
  /** 用户点了开启桌面立绘但还在等授权 */
  pendingOverlay: false,
  /** 悬浮窗单句气泡：当前看第几条 / 总共几条 */
  messageIndex: 0,
  messageCount: 0,
  /** 立绘上下位置（%） */
  portraitY: 0,
  /** 对话框字体缩放（%） */
  fontScale: 100,
  /** 对话框像素微调 */
  dialogX: 0,
  dialogY: 0,
  /** 立绘是否上下浮动 */
  portraitFloat: true,
  /** token 是否明文显示（默认打码） */
  tokenVisible: false,
  /** 角色列表与当前角色 */
  characters: [],
  currentCharacter: '',
  /** 已写入配置、等待重启生效的角色 */
  selectedCharacter: '',
  /** 角色服务报错时的错误类型（用于给出可操作的提示） */
  charactersUnavailable: '',
  /** 待发送/刚发送的附件信息（文件名、缩略图），用于提示条 */
  pendingMedia: null,
  /** 本次会话截了几张图 */
  screenshotCount: 0,
  /** 对话框卡片上次实际渲染出来的高度（隐藏时用来给立绘补位） */
  lastNavHeight: 0,
  /**
   * 对话框自动隐藏秒数（0 = 一直显示），保存栏要用。
   *
   * 这里写字面量 12 而不是 BUBBLE_HIDE_DEFAULT —— 那个常量在后面才声明，
   * 引用它会触发 TDZ（实测报 "Cannot access 'state' before initialization"，
   * 整个页面都起不来）。数值需与 BUBBLE_HIDE_DEFAULT 保持一致。
   */
  bubbleHideSec: 12,
  /** 按下立绘时是否放大一点点（设置里可关）。 */
  pressFeedback: true,
};

const PORTRAIT_SCALE_KEY = 'sakura.remote.portraitScale';
const PORTRAIT_SCALE_MIN = 0.5;
const PORTRAIT_SCALE_MAX = 1.6;
const SHOW_ORIGINAL_KEY = 'sakura.remote.showOriginal';
const SHOW_TRANSLATION_KEY = 'sakura.remote.showTranslation';
const AUTO_SCROLL_KEY = 'sakura.remote.autoScroll';
// 立绘上下位置（%）、字体大小（%）、对话框水平/垂直位置（px）、立绘浮动开关
const PORTRAIT_Y_KEY = 'sakura.remote.portraitY';
const FONT_SCALE_KEY = 'sakura.remote.fontScale';
const DIALOG_X_KEY = 'sakura.remote.dialogX';
const DIALOG_Y_KEY = 'sakura.remote.dialogY';
const FLOAT_KEY = 'sakura.remote.portraitFloat';

const PORTRAIT_Y_LIMIT = 50;
const FONT_SCALE_MIN = 70;
const FONT_SCALE_MAX = 180;
const DIALOG_SHIFT_LIMIT = 60;

/* ---------- 显示微调：位置与字号 ---------- */

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  if (!isFinite(number)) return fallback;
  return Math.min(max, Math.max(min, Math.round(number)));
}

/** 同步一个「滑动条 + 数字输入框 + 数值文字」控件。
 * 数值文字在标签行上（不占控件行宽度）。 */
function syncControl(rangeEl, numberEl, textEls, value, suffix) {
  if (rangeEl) rangeEl.value = String(value);
  if (numberEl) {
    numberEl.value = String(value);
    // 记下最后一次有效值：输入框被清空时可以回退到它
    numberEl.dataset.last = String(value);
  }
  textEls.forEach((node) => {
    if (node) node.textContent = value + suffix;
  });
}

/**
 * 立绘上下位置。
 *
 * 正值往上。CSS 里立绘用 bottom 锚定（贴住下方对话框），
 * 所以往上移要减 bottom。
 */
function applyPortraitOffset(value, options = {}) {
  const percent = clampNumber(value, -PORTRAIT_Y_LIMIT, PORTRAIT_Y_LIMIT, 0);
  state.portraitY = percent;
  // 换算成像素再设：立绘高度由 flex 分配，拿百分比当基准不稳定
  //（实测「调了没反应」）。高度拿不到时先不设，等下次 relayout。
  const portraitEl = el.portrait;
  const height = portraitEl ? portraitEl.getBoundingClientRect().height : 0;
  // 正值往上：CSS 里立绘贴近下方对话框，所以往上要减小 Y
  let offsetPx = height > 0 ? Math.round(-height * percent / 100) : null;
  /*
   * 夹取：不允许把立绘推出 stage。
   *
   * 立绘底部贴着输入栏（输入栏现在是正常流元素，不再是 fixed），
   * 所以**任何向上平移都会直接切掉头顶**。
   *
   * 为什么会这样：戴锦时的缩放（--pet-scale）作用在 #portraitWrap 上，
   * 而布局高度仍是 stage 的 100% —— 也就是说缩小时图片在布局上并没有变小，
   * 上移多少就裁掉多少。滑块范围 ±50% 换算过来是 ±130px，远超可用空间。
   * 实测：portraitY=35 → 上移 91px → 立绘顶部到 -11px，头被切掉。
   *
   * 位置的安全范围就是「容器高 - 图片布局高」：
   *   - 上界 0：底部对齐，不能再低
   *   - 下界 = -(容器高 - 图片高)：顶部对齐，再往上就切头
   * 夹住的只是**实际位移**，不改用户设的百分比 ——
   * 这样把缩放改大之后，原先那个百分比会自动重新变得可用。
   */
  const target = el.portraitWrap || portraitEl;
  if (offsetPx !== null && target) {
    const containerH = target.clientHeight || 0;
    /*
     * 「图片高度」要用**缩放后的可视高度**（getBoundingClientRect），
     * 不能用 offsetHeight —— 两者差在 --pet-scale 上。
     *
     * --pet-scale 作用在 #portraitWrap 的 transform 上，布局高度仍是 stage 的 100%：
     *   - offsetHeight = 321（等于容器高）→ 可用位移算出来是 0，
     *     滑块会彻底失效（实测所有档位位移都是 0，等于把这个功能弄没了）；
     *   - getBoundingClientRect = 259（缩到 0.807 后的真实高度）
     *     → 可用位移 = 321 - 259 = 62px，滑块有正常的活动范围。
     * 用户看到、能被裁掉的是后者，所以按后者算才对。
     */
    const visualH = portraitEl ? portraitEl.getBoundingClientRect().height : height;
    const minOffset = -Math.max(0, containerH - visualH);
    offsetPx = Math.max(minOffset, Math.min(0, offsetPx));
  }
  if (target && offsetPx !== null) {
    target.style.setProperty('--pet-pan-y', offsetPx + 'px');
    state.portraitOffsetPx = offsetPx;
  } else if (percent === 0 && target) {
    // 高度还没量到（或本来就是 0）：至少把位置归零，别留着旧偏移
    target.style.setProperty('--pet-pan-y', '0px');
    state.portraitOffsetPx = 0;
  }
  syncControl(el.portraitYRange, el.portraitYNumber,
    [el.portraitYValue], percent, '%');
  if (options.persist !== false) {
    try {
      localStorage.setItem(PORTRAIT_Y_KEY, String(percent));
    } catch (error) { /* 忽略 */ }
  }
  // skipRelayout 用来打断递归：measure() 会调本函数重算像素，不能再触发 measure
  if (!options.skipRelayout
      && typeof relayoutOverlay === 'function' && isOverlayPage()) {
    relayoutOverlay();
  }
  updateSaveState();
}

/** 字体大小：只影响对话框里的文字。 */
function applyFontScale(value, options = {}) {
  const percent = clampNumber(value, FONT_SCALE_MIN, FONT_SCALE_MAX, 100);
  state.fontScale = percent;
  document.documentElement.style.setProperty('--pet-font-scale', (percent / 100).toFixed(2));
  syncControl(el.fontRange, el.fontNumber,
    [el.fontValue], percent, '%');
  if (options.persist !== false) {
    try {
      localStorage.setItem(FONT_SCALE_KEY, String(percent));
    } catch (error) { /* 忽略 */ }
  }
  // 字变了卡片高度也变，要让窗口跟着调整
  if (typeof relayoutOverlay === 'function' && isOverlayPage()) relayoutOverlay();
  updateSaveState();
}

/** 对话框位置：像素微调。 */
function applyDialogShift(x, y, options = {}) {
  const offsetX = clampNumber(x, -DIALOG_SHIFT_LIMIT, DIALOG_SHIFT_LIMIT, 0);
  const offsetY = clampNumber(y, -DIALOG_SHIFT_LIMIT, DIALOG_SHIFT_LIMIT, 0);
  state.dialogX = offsetX;
  state.dialogY = offsetY;
  const root = document.documentElement;
  root.style.setProperty('--pet-dialog-x', offsetX + 'px');
  root.style.setProperty('--pet-dialog-y', offsetY + 'px');
  syncControl(el.dialogXRange, el.dialogXNumber, [el.dialogXValue], offsetX, '');
  syncControl(el.dialogYRange, el.dialogYNumber, [el.dialogYValue], offsetY, '');
  if (options.persist !== false) {
    try {
      localStorage.setItem(DIALOG_X_KEY, String(offsetX));
      localStorage.setItem(DIALOG_Y_KEY, String(offsetY));
    } catch (error) { /* 忽略 */ }
  }
  updateSaveState();
}

/** 立绘上下浮动开关（呼吸动画）。 */
function applyPortraitFloat(value, options = {}) {
  state.portraitFloat = !!value;
  document.body.classList.toggle('no-float', !state.portraitFloat);
  if (el.floatToggle) el.floatToggle.checked = state.portraitFloat;
  if (options.persist !== false) {
    try {
      localStorage.setItem(FLOAT_KEY, state.portraitFloat ? 'true' : 'false');
    } catch (error) { /* 忽略 */ }
    updateSaveState();
  }
}

/* ---------- 立绘缩放 ---------- */

function clampPortraitScale(value) {
  const number = Number(value);
  if (!isFinite(number) || number <= 0) return 1.0;
  return Math.min(PORTRAIT_SCALE_MAX, Math.max(PORTRAIT_SCALE_MIN, number));
}

function applyPortraitScale(value, options = {}) {
  state.portraitScale = clampPortraitScale(value);
  // 缩放交给外层 #portraitWrap（它只做居中+缩放），内层 img 只跑呼吸动画。
  // 同一个元素上 transform 不能既被缩放又被动画驱动，否则动画会覆盖缩放。
  const target = el.portraitWrap || el.portrait;
  if (target) target.style.setProperty('--pet-scale', state.portraitScale.toFixed(3));
  const percent = Math.round(state.portraitScale * 100) + '%';
  if (el.scaleRange) el.scaleRange.value = String(Math.round(state.portraitScale * 100));
  if (el.scaleNumber) el.scaleNumber.value = String(Math.round(state.portraitScale * 100));
  if (el.scaleValue) el.scaleValue.textContent = percent;
  if (el.scaleValueInline) el.scaleValueInline.textContent = percent;
  if (options.persist !== false) {
    try {
      localStorage.setItem(PORTRAIT_SCALE_KEY, String(state.portraitScale));
    } catch (error) { /* 存储不可用时忽略 */ }
  }
  // 装成 App 时同步给原生悬浮窗，桌面立绘也跟着变
  const native = nativeBridge();
  if (native && typeof native.setScale === 'function') {
    try {
      native.setScale(state.portraitScale);
    } catch (error) { /* 桥接不可用时忽略 */ }
  }
  // 悬浮窗里缩放会改变窗口大小，所以要做防抖后再重新布局。
  // 拖动滑块会连续触发几十次，不防抖就会连续请求窗口尺寸 + reload。
  // 用 typeof 守卫：这段代码会被测试桩件单独抽出来跑，桩件里没有这些函数。
  const inOverlay = typeof isOverlayPage === 'function' && isOverlayPage();
  if (inOverlay && typeof relayoutOverlay === 'function') {
    if (applyPortraitScale.timer) clearTimeout(applyPortraitScale.timer);
    applyPortraitScale.timer = setTimeout(() => {
      applyPortraitScale.timer = 0;
      relayoutOverlay();
    }, 450);
  }
  updateSaveState();
}

function nativeBridge() {
  return (typeof window !== 'undefined' && window.SakuraNative) ? window.SakuraNative : null;
}

/**
 * 给原生桥接补一个 JS 侧回调桩。
 *
 * addJavascriptInterface 注入的对象只能从网页侧扩展（原生方法的返回值是 Java 对象），
 * 而原生要主动把截图结果推给网页，所以约定网页先定义 onScreenCapture。
 */
function installBridgeCallbacks() {
  const native = nativeBridge();
  if (!native) return;
  try {
    if (typeof native.onScreenCapture !== 'function') {
      native.onScreenCapture = function (dataUrl, error) {
        onScreenCapture(dataUrl, error);
      };
    }
  } catch (error) {
    // 某些 WebView 不允许给注入对象挂属性，那就退化成只报错
  }
}

/**
 * 把当前地址回报给原生层作为配置兜底。
 *
 * 连接设置页跑在 https://localhost，远程页跑在 http://PC:8770 —— 两个不同源，
 * localStorage 不互通。如果只靠 localStorage，在远程页点「开启桌面立绘」时
 * 原生侧读不到地址，会误报「需要连接电脑」。
 */
function reportUrlToNative() {
  const native = nativeBridge();
  if (!native || typeof native.rememberUrl !== 'function') return;
  try {
    native.rememberUrl(window.location.href);
  } catch (error) { /* 桥接不可用时忽略 */ }
}

/**
 * 进入悬浮窗模式（?mode=overlay）。
 *
 * 悬浮窗里是原生标题栏 + 这个页面的 WebView，所以要：
 *  - 给 body 加 overlay-mode，让样式切成「叠在立绘上」的紧凑布局
 *  - 输入框聚焦时通知原生放开窗口焦点，否则软键盘弹不出来
 */
function applyOverlayMode() {
  const params = new URLSearchParams(window.location.search);
  if (params.get('mode') !== 'overlay') return false;
  document.documentElement.classList.add('overlay-mode');
  document.body.classList.add('overlay-mode');
  const input = el.text;
  if (input) {
    input.addEventListener('focus', () => setOverlayKeyboard(true));
    input.addEventListener('blur', () => setOverlayKeyboard(false));
  }
  // 临时输入（例如回复期间的快捷输入）也一并处理
  document.addEventListener('focusin', (event) => {
    if (event.target && event.target.tagName === 'TEXTAREA') setOverlayKeyboard(true);
  });
  document.addEventListener('focusout', (event) => {
    if (event.target && event.target.tagName === 'TEXTAREA') setOverlayKeyboard(false);
  });
  return true;
}

function setOverlayKeyboard(focused) {
  const native = nativeBridge();
  if (!native || typeof native.setKeyboardOpen !== 'function') return;
  try {
    native.setKeyboardOpen(!!focused);
  } catch (error) { /* 桥接不可用时忽略 */ }
}

/* ---------- 悬浮窗手势：按住拖动 / 静置 5 秒开设置 ---------- */

/**
 * 拖动与开设置都绑在立绘上。
 *
 * 规则（长按是同一个起手式）：
 *   - 按住后移动超过阈值  → 拖动窗口（发给原生 moveBy）
 *   - 按住不动满 5 秒     → 打开配置页
 *   - 只是轻点            → 显示/隐藏对话框
 *
 * 为什么拖动放在网页侧：去掉原生标题栏后窗口就没有自带拖动区了，
 * 而网页能精确判断用户按住的是立绘还是气泡/按钮，误触更少。
 */
function installOverlayGestures() {
  const target = el.portraitWrap || el.stage;
  const native = nativeBridge();
  if (!target || !native || typeof native.moveBy !== 'function') return;

  const DRAG_THRESHOLD = 6;    // px，超过才算拖动
  const HOLD_MS = 5000;        // 静置多久开设置

  let holding = false;
  let dragging = false;
  let longPressed = false;     // 长按已触发开设置，抬手时就不再算轻点
  let multiTouch = false;      // 本次手势是否出现了多指（让位穿透时会出现）
  let lastX = 0;
  let lastY = 0;
  let holdTimer = 0;
  let startX = 0;
  let startY = 0;

  function clearHold() {
    clearTimeout(holdTimer);
    holdTimer = 0;
  }

  /**
   * 下发一次位移。
   *
   * **单位是 CSS 像素，不要乘 devicePixelRatio。**
   *
   * touch.clientX 是 CSS 像素；原生把参数直接加到 WindowManager.LayoutParams
   * 的 x/y 上，而 layoutParams 与网页 CSS 视口是 1:1 的 ——
   * 实测 layoutParams.w / density == window.innerWidth（965/2.75 = 351，
   * 正好等于视口宽）。所以两边本来就同单位，直接传即可。
   *
   * 曾误以为原生用设备像素而乘上 dpr，结果灵敏度被放大 2.75 倍，
   * 窗口飞得比手指还快。判断单位前先做上面这个除法验证，别猜。
   *
   * 亚像素精度由原生负责：moveWindowBy 会累积不足 1px 的余量。
   */

  /**
   * 通知原生拖动开始/结束。
   *
   * 原生据此在窗口尺寸变化后**跳过位置夹取** —— 那个夹取会把刚拖出来的
   * 位移整个抵消，表现就是「拖不动，得先点一下」。
   * 老版本 APK 没有这个桥方法，所以调用要容错。
   */
  function setNativeDragging(value) {
    try {
      const n = nativeBridge();
      if (n && typeof n.setDragging === 'function') n.setDragging(!!value);
    } catch (error) { /* 忽略：旧版 APK 没这个方法 */ }
  }

  function move(dx, dy) {
    try {
      /*
       * 必须乘 devicePixelRatio —— 实测确认，不要再动这里。
       *
       * touch.clientX 是 CSS 像素，而原生把参数直接加到
       * WindowManager.LayoutParams.x/y 上，那是**设备像素**。
       *
       * 实测（把窗口位移换算回 CSS 像素后对比手指位移）：
       *   不乘 dpr：手指移动 100 CSS px，窗口只走 36 CSS px（欠走 2.75 倍）
       *   乘   dpr：手指移动 100 CSS px，窗口走 100 CSS px（1:1）
       *
       * 注意「layoutParams.w / density == innerWidth」这一点**不能**用来
       * 判断单位 —— 那两个值只是恰好相等（都是 351），但 moveBy 的入参
       * 加到的是设备像素的坐标上。单位要靠实测位移比来定，别靠推算。
       */
      const dpr = window.devicePixelRatio || 1;
      native.moveBy(dx * dpr, dy * dpr);
    } catch (error) { /* 忽略 */ }
  }

  target.addEventListener('touchstart', (event) => {
    /*
     * 已经有一根手指按着时（touches.length > 1），什么都不要做。
     *
     * 这里非常关键：让位状态下用户会用**第二根手指**去点桌面，
     * 而第二根手指落在屏幕上时，这个窗口可能已经被重新判定为可触摸
     *（比如第一根手指刚好抬了一下）—— 那样第二根手指就会变成「拖动立绘」，
     * 用户看到的就是「想去点图标，结果立绘乱走」。
     * 所以我们只认第一根手指建立的手势，多指期间一律不重新开始。
     */
    if (event.touches.length !== 1) {
      multiTouch = true;
      return;
    }
    if (holding) {
      return;   // 已经在手势中，不要用新手指的位置重置起点
    }
    const touch = event.touches[0];
    holding = true;
    dragging = false;
    longPressed = false;   // 每次按下都重置
    startX = touch.clientX;
    startY = touch.clientY;
    lastX = startX;
    lastY = startY;
    clearHold();
    /*
     * 按下就给出即时反馈 —— 不等松手。
     *
     *   按在人物本体  -> 轻微放大（body.pressing），做出「点到了」的体感
     * 按在透明处不做任何反馈。
     *
     * 透明处那一路会立刻把窗口设为不可触摸，所以底下的桌面图标**在按住期间**
     * 就能用另一根手指点到（松开第一根手指即恢复）。
     */
    pressFeedbackAt(touch.clientX, touch.clientY);
    holdTimer = setTimeout(() => {
      if (!dragging) {
        // 静置满 5 秒：开设置
        holding = false;
        longPressed = true;
        clearPressFeedback();
        openConfig(true);
      }
    }, HOLD_MS);
    /*
     * 阻止 WebView 的默认触摸行为。
     *
     * 不阻止的话，手指按在立绘（或它旁边的透明区）上，WebView 会启动
     * 系统级的点击/长按反馈 —— 长按还会浮出一张半透明的「幽灵图」盖在原图上。
     * 在透明悬浮窗里看就是「立绘变成半透明了」。
     *
     * 它是原生画的，CSS 的 -webkit-tap-highlight-color / touch-callout
     * 都管不到（实测加了也没用），只有 preventDefault 能拦住。
     *
     * 这里必须用 passive: false —— passive 监听器里的 preventDefault
     * 是无效的（浏览器会忽略并警告），那正是上一版没生效的原因。
     */
    event.preventDefault();
  }, { passive: false });

  target.addEventListener('touchmove', (event) => {
    // 多指期间不拖动：第二根手指是去点桌面的，不该带动立绘
    if (!holding || event.touches.length !== 1) return;
    const touch = event.touches[0];
    const dx = touch.clientX - lastX;
    const dy = touch.clientY - lastY;
    if (!dragging) {
      const moved = Math.hypot(touch.clientX - startX, touch.clientY - startY);
      if (moved < DRAG_THRESHOLD) return;
      dragging = true;
      clearHold();          // 开始拖动就不再触发开设置
      // 拖动期间关掉滤镜与过渡（见 app.css 的 body.dragging）
      clearPressFeedback();  // 拖动不是「按住」，撤掉按下反馈
      document.body.classList.add('dragging');
      /*
       * 告诉原生「正在拖动」。
       *
       * 原生在窗口尺寸变化后会做一次位置夹取（把窗口拉回屏幕内），
       * 而窗口尺寸是网页每 400ms 按立绘和对话框内容重算的 ——
       * 夹取会把刚拖出来的位移整个抵消，表现就是「拖不动，得先点一下」。
       * 拖动期间原生跳过夹取，松手后再正常校正。
       */
      setNativeDragging(true);
    }
    lastX = touch.clientX;
    lastY = touch.clientY;
    move(dx, dy);
    event.preventDefault();
  }, { passive: false });

  const end = (cancelled) => {
    /*
     * 判断这一次是「轻点」还是「长按/拖动」。
     *
     * 只看两件事：没拖动过、长按没触发过。
     *
     * 原来还额外要求 `holdTimer` 非零，那是个很脆的条件 —— `clearHold()`
     * 会把 holdTimer 清成 0，任何一次多余的 clearHold 都会让轻点被判成
     * 「不是轻点」，表现就是「点立绘没反应」。长按是否发生过，用
     * longPressed 这个显式标记更可靠。
     */
    const isTap = !cancelled && !dragging && !longPressed;
    holding = false;
    if (dragging) {
      setNativeDragging(false);   // 松手后恢复正常的 resize 校正
    }
    dragging = false;
    document.body.classList.remove('dragging');
    // 松手即恢复原样：半透明/放大都撤掉，桌面让位也收回
    clearPressFeedback();
    clearHold();
    // 位移已经逐次直接下发了，这里不需要再补发
    if (isTap) onPortraitTap(startX, startY, target);
  };
  target.addEventListener('touchend', (event) => {
    /*
     * 只有**所有**手指都抬起才收尾，多指期间不拖动。
     *
     * 多指时若只用第一根手指的坐标算位移，第二根手指移动会算出巨大的
     * 跳变，把立绘甩出去。所以多指期间直接不处理拖动。
     */
    if (event.touches && event.touches.length > 0) {
      event.preventDefault();
      return;   // 还有手指按着，保持让位
    }
    multiTouch = false;
    end(false);
    event.preventDefault();
  }, { passive: false });
  target.addEventListener('touchcancel', (event) => {
    if (event.touches && event.touches.length > 0) {
      event.preventDefault();
      return;
    }
    multiTouch = false;
    end(true);
    event.preventDefault();
  }, { passive: false });

  // 鼠标也支持一份，方便在电脑浏览器里调试
  target.addEventListener('mousedown', (event) => {
    holding = true;
    dragging = false;
    longPressed = false;
    startX = event.clientX;
    startY = event.clientY;
    lastX = startX;
    lastY = startY;
    clearHold();
    holdTimer = setTimeout(() => {
      if (!dragging) {
        holding = false;
        longPressed = true;
        openConfig(true);
      }
    }, HOLD_MS);
  });
  window.addEventListener('mousemove', (event) => {
    if (!holding) return;
    const dx = event.clientX - lastX;
    const dy = event.clientY - lastY;
    if (!dragging) {
      if (Math.hypot(event.clientX - startX, event.clientY - startY) < DRAG_THRESHOLD) return;
      dragging = true;
      clearHold();
    }
    lastX = event.clientX;
    lastY = event.clientY;
    // 鼠标路径保留增量方式（只在电脑浏览器里调试用，没有原生悬浮窗）
    try {
      const dpr = window.devicePixelRatio || 1;
      native.moveBy(dx * dpr, dy * dpr);
    } catch (error) { /* 忽略 */ }
  });
  window.addEventListener('mouseup', () => end(false));
}

/** 按下反馈（立绘放大）的开关存储键。 */
const PRESS_FEEDBACK_KEY = 'sakura.remote.pressFeedback';
/** 默认开启：这是「点到了」的主要体感来源。 */
const PRESS_FEEDBACK_DEFAULT = true;

let pressingActive = false;

/** 按下反馈是否开启。 */
function pressFeedbackEnabled() {
  return readBool(PRESS_FEEDBACK_KEY, PRESS_FEEDBACK_DEFAULT);
}

/** 设置里改这个开关时调用。 */
function setPressFeedbackEnabled(enabled, options = {}) {
  state.pressFeedback = !!enabled;
  if (el.pressFeedbackToggle) el.pressFeedbackToggle.checked = state.pressFeedback;
  if (options.persist !== false) {
    writeBool(PRESS_FEEDBACK_KEY, state.pressFeedback);
    updateSaveState();
  }
  // 关掉时立刻撤掉正在生效的放大
  if (!state.pressFeedback) {
    document.body.classList.remove('pressing');
    pressingActive = false;
  }
}

/**
 * 按下立绘时的即时反馈。
 *
 * 按在**人物本体**上：加 body.pressing，立绘轻微放大（可在设置里关掉）。
 * 按在**透明处**：进入穿透态 —— 立绘变半透明，同时把窗口设为不可触摸，
 *   让底下的桌面图标可以直接点。松手立刻恢复。
 *
 * 用 clientX/clientY 在立绘的 alpha 上采样来判断按到了哪里 ——
 * 这正是「按 PNG 轮廓做自适应边界」在**手势层面**能实现的部分：
 * 窗口本身仍然只能整块接收触摸（Android 只支持矩形区域），
 * 但按下的那一刻我们能精确知道手指在不在人物身上，并据此决定给什么反馈。
 */
/**
 * 立绘容器的缩放，统一由这里写。
 *
 * 为什么不用 CSS 变量 + calc 去合成：
 * 试过 `transform: scale(calc(var(--pet-scale) * (1 + var(--pet-press))))`，
 * 结果是「让位」那档生效、「按下」那档不生效 —— 同一套写法只差数值正负，
 * 表现却不一致，说明 calc 与自定义属性嵌套在这个 WebView 里不可靠。
 * 而且多条规则各自写 transform 会互相覆盖（同优先级下后者赢），
 * 按下/拖动切换时来回争抢，画面就一闪一闪。
 *
 * 现在只有**一个**地方写这个属性，取值在 JS 里算好，不存在争抢。
 */
function applyPortraitTransform() {
  const wrap = document.querySelector('#portraitWrap');
  if (!wrap) return;
  const base = state.portraitScale || 1;
  let factor = 1;
  if (document.body.classList.contains('pressing')) {
    factor = 1 + PRESS_LIFT;
  }
  /*
   * 居中方式两种模式不同，不能一律加 translateX(-50%)：
   *   overlay：容器是 position: relative + left: auto + margin: 0 auto，
   *            靠 auto margin 居中，再加 translateX 会把它推到左边；
   *   小球/其他：容器是 position: absolute + left: 50%，需要 translateX(-50%)。
   */
  const centeredByMargin = document.body.classList.contains('overlay-mode');
  const shift = centeredByMargin ? '' : 'translateX(-50%) ';
  wrap.style.transform = shift + 'scale(' + (base * factor).toFixed(4) + ')';
}

/** 按下时放大多少（1.008 的量级，手机上不再明显跳动）。 */
const PRESS_LIFT = 0.008;

function pressFeedbackAt(x, y) {
  if (!el.body) {
    el.body = document.body;
  }
  // 只在按到人物本体时给放大反馈；按在透明处什么都不做
  //（以前这里会让位给桌面，那套穿透机制已整个去掉）
  if (portraitAlphaAt(x, y) < 16) {
    return;
  }
  if (pressFeedbackEnabled()) {
    el.body.classList.add('pressing');
    pressingActive = true;
    applyPortraitTransform();
  }
}

/** 松手（或手势被打断）时撤销按下的放大反馈。 */
function clearPressFeedback() {
  if (!pressingActive) {
    return;
  }
  pressingActive = false;
  (el.body || document.body).classList.remove('pressing');
  applyPortraitTransform();   // 松手立刻复原
}

/**
 * 轻点立绘：显示 / 隐藏对话框。
 *
 * 用户想「点一下学姐看看她说了什么，再点一下收起来」，所以做成切换。
 * 显示时不重新计时自动隐藏 —— 那是用户主动要求的，等 12 秒就自动收掉会很突兀。
 */
function toggleBubbleByTap() {
  if (!el.msgNav) return;
  if (el.msgNav.classList.contains('hidden')) return;   // 没内容，别切
  const hidden = el.msgNav.classList.contains('auto-hidden')
    || el.msgNav.classList.contains('auto-hidden-now');
  if (hidden) {
    showBubble();
    clearTimeout(bubbleHideTimer);   // 用户主动显示，别马上又被自动隐藏收走
  } else {
    hideBubble(true);
  }
}

/* ---------- 已选内容提示条 ----------
 *
 * 用户需要知道「选了什么图 / 截了几张」，否则发出去心里没底。
 * 这里维护一个待发送附件：{ kind: 'file'|'shot', name, thumb, dataUrl, asset }
 */

/**
 * 提示条显隐会改变输入栏高度，必须同步两件事：
 *
 *  1. --pet-composer-h（CSS 的 max-height 要用它算卡片上限）
 *  2. 窗口尺寸（输入栏变高，窗口也要跟着变）
 *
 * 只做 1 不做 2 会让窗口高度与内容不符；只做 2 不做 1 更糟 ——
 * measure() 量输入栏时提示条可能还没显示（实测读到 70px，实际 112px），
 * 于是 max-height 算大 42px，卡片正好压住输入栏。
 * 所以这里先立即由 DOM 实测更新变量，再触发一次完整重排。
 */
function relayoutForMediaInfo() {
  const syncComposerVar = () => {
    if (el.composer) {
      const h = Math.round(el.composer.getBoundingClientRect().height);
      if (h > 0) {
        document.documentElement.style.setProperty(
          '--pet-composer-h', Math.max(70, h + 8) + 'px');
      }
    }
    if (typeof relayoutOverlay === 'function') relayoutOverlay();
  };
  // 等一帧让提示条完成布局，再量高度
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(syncComposerVar);
  } else {
    setTimeout(syncComposerVar, 16);
  }
}

function showMediaInfo(info) {
  if (!el.mediaInfo) return;
  if (!info) {
    el.mediaInfo.classList.add('hidden');
    relayoutForMediaInfo();
    return;
  }
  el.mediaInfo.classList.remove('hidden');
  if (el.mediaInfoText) el.mediaInfoText.textContent = info.name || '';
  if (el.mediaInfoThumb) {
    if (info.thumb) {
      el.mediaInfoThumb.src = info.thumb;
      el.mediaInfoThumb.classList.remove('hidden');
    } else {
      el.mediaInfoThumb.removeAttribute('src');
      el.mediaInfoThumb.classList.add('hidden');
    }
  }
  relayoutForMediaInfo();
}

function clearMediaInfo() {
  state.pendingMedia = null;
  showMediaInfo(null);
}

/** 已选文件：显示文件名和大小。 */
function describePickedFile(file) {
  if (!file) return '';
  const kb = file.size ? Math.round(file.size / 1024) : 0;
  const size = kb >= 1024 ? (kb / 1024).toFixed(1) + ' MB' : kb + ' KB';
  // 文件名可能为空（部分相机/分享来源），退回一个通用描述
  const name = file.name && file.name.trim() ? file.name.trim() : '已选图片';
  return name + (kb ? '（' + size + '）' : '');
}

function onPickedFile() {
  const file = el.image && el.image.files && el.image.files[0];
  if (!file) {
    state.pendingMedia = null;
    showMediaInfo(null);
    return;
  }
  const info = { kind: 'file', name: describePickedFile(file), thumb: '' };
  state.pendingMedia = info;
  showMediaInfo(info);
  // 用 objectURL 做缩略图，选完就能看到是哪张
  try {
    info.thumb = URL.createObjectURL(file);
    showMediaInfo(info);
  } catch (error) { /* 不支持就只显示文件名 */ }
}

/**
 * 截图：记一条「最近截图」并显示缩略图。
 *
 * 截图是立刻发出去的（没有"先选后发"的中间态），所以这里的作用是
 * 让用户确认「刚才截到了什么」，而不是等待发送。
 */
function noteScreenshot(dataUrl) {
  const round = (state.screenshotCount || 0) + 1;
  state.screenshotCount = round;
  const info = {
    kind: 'shot',
    name: '截图 ' + round + '（已发送）',
    thumb: dataUrl,
  };
  state.pendingMedia = info;
  showMediaInfo(info);
  // 自动淡出，避免一直占着空间。给足时间让用户看清缩略图和序号 ——
  // 3 秒太短，截图后往往还没看清就消失了。
  clearTimeout(noteScreenshot._timer);
  noteScreenshot._timer = setTimeout(() => {
    if (state.pendingMedia === info) clearMediaInfo();
  }, 8000);
}

/* ---------- 媒体下拉菜单（图片 / 截屏）---------- */

function setMediaMenuOpen(open) {
  if (!el.mediaPanel || !el.mediaToggle) return;
  el.mediaPanel.classList.toggle('hidden', !open);
  el.mediaToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  el.mediaToggle.classList.toggle('active', !!open);
}

function closeMediaMenu() {
  setMediaMenuOpen(false);
}

/* ---------- 立绘上的轻点与拖动 ----------
 *
 * 需要区分三种手势：
 *   轻点人物本体  → 收放对话框
 *   按住人物本体  → 轻微放大（体感反馈，设置里可关）
 *   拖动          → 移动窗口（长按 5 秒则是打开设置）
 *
 * 曾经还有第四种：点透明处把这一下让给桌面图标。那套机制**已整个去掉** ——
 * 它依赖 WindowManager 的 FLAG_NOT_TOUCHABLE，而 Android 在手势开始时就读定了
 * 触摸归属，导致「点一下就穿过去」根本做不到，只能退化成
 * 「按住 + 另一根手指」这种非常规操作，还容易误触、甚至把窗口卡在点不动的状态。
 * 详见 README 的已知限制。
 */

/** 记录指针按下的位置，用来区分「轻点」和「拖动」。 */
function installPointerHistory() {
  let downX = 0;
  let downY = 0;
  window.addEventListener('pointerdown', (event) => {
    downX = event.clientX;
    downY = event.clientY;
  }, { passive: true, capture: true });
  // 只用 down→up 的位移判断轻点就够了，**不要**累积 pointermove 的最大位移：
  // 指针拖远再拖回来时那个历史值仍然很大，会把之后的轻点全部误判成拖动。
  window.__petDownPoint = () => ({ x: downX, y: downY });
}

/**
 * 取样立绘图片某点的 alpha。
 *
 * 立绘 PNG 自带透明通道（实测 RGBA），所以能直接按像素判断。
 * 采样用 1×1 的 canvas，只读一个像素，开销可以忽略。
 */
function portraitAlphaAt(x, y) {
  const img = el.portrait;
  if (!img) return 0;
  const rect = img.getBoundingClientRect();
  if (!rect.width || !rect.height) return 0;
  if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return 0;
  const naturalW = img.naturalWidth || 0;
  const naturalH = img.naturalHeight || 0;
  if (!naturalW || !naturalH) return 0;

  // object-fit: contain —— 先算出图片在框内的实际显示区域
  const fitScale = Math.min(rect.width / naturalW, rect.height / naturalH);
  if (!fitScale) return 0;
  const shownW = naturalW * fitScale;
  const shownH = naturalH * fitScale;
  // object-position: bottom center
  const offsetX = rect.left + (rect.width - shownW) / 2;
  const offsetY = rect.bottom - shownH;

  const px = Math.floor((x - offsetX) / fitScale);
  const py = Math.floor((y - offsetY) / fitScale);
  if (px < 0 || py < 0 || px >= naturalW || py >= naturalH) return 0;

  try {
    if (!portraitAlphaAt._canvas) {
      const canvas = document.createElement('canvas');
      canvas.width = 1;
      canvas.height = 1;
      portraitAlphaAt._canvas = canvas;
      portraitAlphaAt._ctx = canvas.getContext('2d', { willReadFrequently: true });
    }
    const ctx = portraitAlphaAt._ctx;
    if (!ctx) return 0;
    ctx.clearRect(0, 0, 1, 1);
    ctx.drawImage(img, px, py, 1, 1, 0, 0, 1, 1);
    return ctx.getImageData(0, 0, 1, 1).data[3];
  } catch (error) {
    // 取不到像素时按「不透明」处理：宁可点不动，也不要把界面点穿
    return 255;
  }
}

/**
 * 命中这些区域的轻点一律不穿透（它们是真正的交互控件）。
 *
 * 注意**不要**把 `#stage` / `#portrait` 放进来：立绘的透明部分本来就是要
 * 穿透给桌面的。人物本体不用担心误穿透 —— 靠 alpha 采样判断，
 * 不透明处 alpha=255 会直接返回；拖动/长按也会被位移阈值排除。
 */
const CLICK_THROUGH_KEEP = [
  '#msgNav', '#msgText', '#msgButtons',
  '#composer', '#form', '#text', '#send', '#status',
  '#mediaMenu', '#mediaPanel', '#mediaToggle',
  '#configPanel', '#configBody', '#configHeader',
  '#hint',
].join(',');

/**
 * 立绘轻点的统一入口（穿透 / 收放气泡）。
 *
 * 挂在 window 上是为了让 installOverlayGestures 的 touchend 也能调用 ——
 * 两处各自判定会出现「同一个轻点被处理两次」或互相抢先。
 */
function onPortraitTap(x, y, targetEl) {
  if (typeof window.__petOnPortraitTap === 'function') {
    window.__petOnPortraitTap(x, y, targetEl);
  }
}

/**
 * 立绘上的轻点处理。
 *
 * 这里**只做一件事**：点在人物本体上时收放对话框。
 *
 * 原来还带一套「点击穿透到桌面」的机制（按住透明处 → 窗口让位 →
 * 另一根手指点桌面图标），但它依赖 Android 的 FLAG_NOT_TOUCHABLE，
 * 而系统在手势开始时就读定了触摸归属，导致体验一直不理想：
 * 用户得记住「按住 + 另一根手指」这种非常规操作，还容易误触。
 * 权衡之后**整个去掉**，换来的好处是：
 *   - 立绘永远不会突然变半透明
 *   - 不会出现「点不动」的状态（那个状态需要兜底定时器来救）
 *   - 少一条跨 JS/原生的状态链路，闪烁和时序问题的来源也少了
 *
 * 如果以后还想要桌面图标可点，正确做法是在悬浮窗上加一个「暂时让位」
 * 按钮（整窗隐藏几秒），而不是靠透明区判定 —— 参见 README 的已知限制。
 */
function installClickThrough() {
  /** 点在人物本体上（alpha 足够高）才算「点到角色」。 */
  const ALPHA_MIN = 16;

  /** 这些容器里的点击交给控件自己处理，不要当成「点到立绘」。 */
  const CLICK_THROUGH_KEEP = [
    '#composer', '#msgNav', '#configPanel', '#topbar', '#bubbles',
    '#mediaPanel', '#miniBar', '#hint', '#form', 'button', 'input',
    'textarea', 'select', 'label', 'a'
  ];

  function handlePortraitTap(x, y, targetEl) {
    const target = targetEl || null;
    if (target && target instanceof Element && target.closest(CLICK_THROUGH_KEEP.join(','))) {
      return;   // 交互控件，交给它自己处理
    }
    if (portraitAlphaAt(x, y) >= ALPHA_MIN) {
      toggleBubbleByTap();
    }
  }

  window.__petOnPortraitTap = handlePortraitTap;
}

/**
 * 让窗口尺寸跟随立绘。
 *
 * 这是必须的：窗口尺寸写死时，放大立绘会超出窗口被裁掉（显示不全）。
 *
 * 顺序很重要 —— 先把「立绘适配屏幕」的尺寸算出来设置给立绘，
 * 再量它渲染后的实际大小去定窗口。反过来（用自然宽×缩放直接当窗口尺寸）
 * 会算出比屏幕还大的窗口，被原生夹掉之后立绘仍然被裁。
 */
function installContentWidthSync() {
  const wrap = el.portraitWrap;
  const portrait = el.portrait;
  const native = nativeBridge();
  if (!wrap || !portrait || !native || typeof native.setWindowSize !== 'function') return;

  function measure() {
    // 只服务悬浮窗；App 里的布局由 CSS 自己管，别插手
    if (!isOverlayPage()) return;
    /*
     * 拖动期间不重算窗口尺寸。
     * 除了避免和拖动抢主线程，更重要的是：窗口尺寸一变，
     * 立绘在窗口内的位置就会重排，拖动时看起来像「跳一下」。
     * 松手后的下一次定时重算会把尺寸校正回来。
     */
    if (document.body.classList.contains('dragging')) return;
    const naturalW = portrait.naturalWidth || 0;
    const naturalH = portrait.naturalHeight || 0;
    if (!naturalW || !naturalH) return;

    // 量高度之前先确保卡片有内容：空卡片和满卡片高度差很多，
    // 按空卡片算出来的窗口会把对话框压扁（实测只剩一个箭头的高度）。
    if (typeof applyMessageNav === 'function') {
      applyMessageNav({ skipRelayout: true });
    }

    const pad = 6;
    /*
     * 宽度一律以 screen.width（逻辑宽，如 393）为目标。
     *
     * 踩过的三个坑，都在这两个宽度上：
     *   1) 卡片宽度用 screenW 而视口只有 334 → 卡片 381 溢出 47px，
     *      对话框的 ▲▼ 按钮被挤出屏幕截断；
     *   2) 反过来把目标也改成 innerWidth → 窗口越量越窄（实测缩到 160px），
     *      因为视口本身受窗口尺寸影响，形成收缩循环；
     *   3) 目标用 innerWidth 时，启动那一刻窗口还没长到目标值（实测只有 319
     *      而屏幕是 393），于是窗口一直卡在 81% 宽度、右侧留白。
     *
     * 所以：窗口尺寸只按 screen 申请；卡片宽度在 CSS 里用 max-width 夹住，
     * 不依赖 JS 读视口。这样既不会溢出，也不会因视口滞后而缩水。
     */
    const screenW = Math.max(160, window.screen.width || window.innerWidth || 334);
    const screenH = window.screen.height || window.innerHeight || 640;
    const targetContentW = Math.max(120, screenW - pad * 2);

    // ---- 对话框高度：按文字内容自适应 ----
    // 固定占屏幕 40% 会在短消息时留下大片空白，所以按内容实测。
    //
    // 关键：测量前必须先把卡片高度清零。
    // 因为 --pet-nav-h 是给卡片的固定高度，它会把卡片撑高，
    // 于是 scrollHeight 返回的是「被撑高后」的值，永远等于上限
    //（实测无论文字长短都是 304）。清零后 scrollHeight 才是内容真实高度。
    // 宽度按目标值设定；万一视口还没长到那么宽，由 CSS 的
    // max-width: min(..., 100%) 兜住，不会溢出（见 app.css 的 #msgNav / #composer）。
    const contentW = targetContentW;
    document.documentElement.style.setProperty('--pet-content-w', contentW + 'px');
    const navCap = Math.max(110, Math.round(screenH * 0.4));
    // 卡片「真实渲染高度」缓存。
    //
    // 为什么需要单独一个值：navH 在算不出来时会回退成 navCap（屏幕高的 40%），
    // 而卡片实际可能只有一半高。用 navH 当补位量会让立绘下移过头
    //（实测 navH=358 而卡片只有约 200），把卡片那块空位补穿。
    // 所以每次卡片可见时都记下它的 offsetHeight，补位用这个。
    let navH = 0;
    const navVisible = !!(el.msgNav
      && !el.msgNav.classList.contains('hidden')
      && !el.msgNav.classList.contains('auto-hidden'));
    if (navVisible && el.msgText) {
      el.msgNav.style.height = '0px';
      const natural = el.msgText.scrollHeight || 0;
      el.msgNav.style.removeProperty('height');
      // 卡片本身的内边距和边框
      const box = Math.max(0, (el.msgNav.offsetHeight || 0)
        - (el.msgText.offsetHeight || 0));
      navH = Math.max(0, Math.min(navCap, Math.ceil((natural + box + 8) / 8) * 8));
      // 记下这次实际渲染出来的高度，供隐藏时补位使用
      const rendered = Math.round(el.msgNav.offsetHeight || 0);
      if (rendered > 0) state.lastNavHeight = rendered;
    }
    if (!navH) {
      navH = navVisible ? navCap : 0;
    }
    // 输入栏高度：用 offsetHeight，不用 getBoundingClientRect().height。
    //
    // 踩过的坑：measure() 是在窗口 resize 过程中跑的，那一刻
    // getBoundingClientRect() 会返回全 0（实测 measured=0 而直接量是 112）。
    // 于是 composerBudget 永远是最小值 70，而 CSS 的卡片上限按这个偏小的值算，
    // 结果卡片多占 42px、正好压住输入栏。
    const composerH = el.composer ? Math.round(el.composer.offsetHeight || 0) : 0;
    const composerBudget = Math.max(70, composerH + 8);
    // 卡片高度变成 0 之后（auto-hidden），navH 必须是 0，
    // 这样窗口也会跟着变矮，立绘顺势补位。
    // 注意别在这里再叠一个「补位偏移」—— 卡片实时收起已经让 stage 自动占满
    // 释放出来的空间，再手动位移就会补过头、立绘压住输入栏（实测 -42px 重叠）。
    document.documentElement.style.setProperty('--pet-nav-h', navH + 'px');
    document.documentElement.style.setProperty('--pet-composer-h', composerBudget + 'px');

    // ---- 立绘尺寸：由缩放倍率决定，并夹到可用空间内 ----
    // 注意用「当前视口高度」而不是 screen.height 来算上限。
    //
    // 踩过的坑：原来只有 screenH（895），于是立绘一路取到上限 321px，
    // 而实际视口只有 482px。对话框一隐藏、窗口变矮到 410 之后空间根本不够，
    // 立绘底部就压住输入栏（实测重叠 20px）。
    // 视口才是真正画得下的高度，screen 只是设备逻辑高，两者不一定相等。
    const scale = state.portraitScale || 1;
    const chrome = navH + composerBudget + pad * 2;
    /*
     * 可用高度取 screen.height 与当前视口的**较大值**。
     *
     * 为什么取大值而不是小值（取小值试过，是错的）：
     *   - 只按视口算会形成收缩循环：视口变小 → 立绘变小 → 窗口更矮 →
     *     视口再变小……实测一路掉到 185px；
     *   - 用 max 则立绘尺寸只由屏幕高决定，和当前窗口尺寸无关，
     *     不会互相追逐。
     * 窗口变矮时立绘也不缩，但那是必要的：变矮的原因是卡片收起，
     * 那部分空间正好由立绘补上，不会溢出。
     */
    const screenH2 = window.screen.height || screenH;
    const availH = Math.max(220, Math.max(screenH, screenH2));
    // 立绘按「目标宽度」适配（窗口会长到这么宽）；高度则夹在可用空间内。
    const maxPortraitW = targetContentW;
    const maxPortraitH = Math.max(100, availH - chrome - pad * 2);
    const base = Math.min(maxPortraitW / naturalW, maxPortraitH / naturalH);
    const portraitW = Math.max(40, Math.round(naturalW * base * scale));
    const portraitH = Math.max(40, Math.round(naturalH * base * scale));

    document.documentElement.style.setProperty('--pet-avail-h', portraitH + 'px');

    const root = document.documentElement;
    // 立绘尺寸走 CSS（stage 高度 + object-fit），JS 不再写死像素 ——
    // 写死会与 flex 实际分配不一致，超出的部分会压住对话框。
    wrap.style.removeProperty('height');
    wrap.style.removeProperty('width');
    // --pet-scale 必须写在 #portraitWrap 上（applyPortraitScale 写的是它）
    wrap.style.setProperty('--pet-scale', scale.toFixed(3));
  applyPortraitTransform();   // 缩放滑块变化时同步（保持同一个写入点）

    // 立绘高度刚确定，把「上下位置」按新高度重算成像素。
    // 不重算的话：设位置时若高度还是 0/旧值，偏移量就是错的，
    // 表现为「调了没反应」。
    if (state.portraitY) {
      applyPortraitOffset(state.portraitY, { persist: false, skipRelayout: true });
    }

    // ---- 窗口 = 立绘 + 对话框 + 输入栏 ----
    //
    // 这里**不要**用 window.innerHeight 去夹 wantH：悬浮窗改尺寸后 WebView 不会
    // 立刻更新布局视口，读到的 innerHeight 是启动瞬间的旧值（实测把窗口从 761
    // 压成 403，内容全挤在一起）。窗口高度只按实测内容算；
    // 「卡片不许压住输入栏」由 CSS 的 max-height 负责（见 app.css 的 #msgNav）。
    const wantW = Math.round(portraitW + pad * 2);
    const wantH = Math.round(portraitH + chrome);
    const last = readOverlayWindowSize();
    if (last && Math.abs(last.w - wantW) < 3 && Math.abs(last.h - wantH) < 3) {
      return;   // 尺寸没变，不再请求
    }
    rememberOverlayWindowSize(wantW, wantH);
    try {
      // 原生会把尺寸落盘，并在下次建窗口时直接使用 ——
      // 这样视口天生就是对的，不需要任何 reload。
      native.setWindowSize(wantW, wantH);
    } catch (error) { /* 忽略 */ }
  }

  if (portrait.complete) measure();
  portrait.addEventListener('load', measure);
  window.addEventListener('resize', measure);
  // 缩放、换表情、语气切换都会改变立绘尺寸，定时兜底最省事
  setInterval(measure, 400);
  // 暴露给历史导航：卡片内容变了要重新量高度
  relayoutOverlay = measure;
}

/* ---------- 手机截屏（让学姐看到你正在看什么）---------- */

let screenshotWaiting = false;

/**
 * 请求一次手机截屏并直接发给她。
 *
 * 链路：原生 MediaProjection 截图 → 原生按最长边 1280 缩放 + JPEG q70 压缩
 * → 这里拿到 data URL → 走已有的 /api/upload → 随消息一起给模型。
 *
 * 为什么在客户端压缩：1080×2460 的原始截图 2–4 MB，压完约 150–300 KB，
 * 局域网和 4G 下都轻松，电脑端也不需要改任何东西。
 */
function requestScreenshot() {
  const native = nativeBridge();
  if (!native || typeof native.requestScreenshot !== 'function') {
    systemNote('截图需要在手机 App 内使用。');
    return;
  }
  if (screenshotWaiting) return;
  screenshotWaiting = true;
  setStatus('正在截屏…');
  try {
    native.requestScreenshot();
  } catch (error) {
    screenshotWaiting = false;
    setStatus('');
    systemNote('截图失败：' + (error && error.message ? error.message : error));
  }
  // 保险：万一原生没回调，别把按钮一直锁着
  setTimeout(() => {
    if (screenshotWaiting) {
      screenshotWaiting = false;
      setStatus('');
      systemNote('截图超时，请重试。');
    }
  }, 20000);
}

/** 原生截完图回传（dataUrl 为空表示失败）。 */
function onScreenCapture(dataUrl, error) {
  screenshotWaiting = false;
  setStatus('');
  if (!dataUrl) {
    systemNote('截图失败：' + (error || '未知错误'));
    return;
  }
  sendWithScreenshot(dataUrl);
}

/** 把截图发给角色：上传拿到 URL，再当作一条带图消息发出去。 */
async function sendWithScreenshot(dataUrl) {
  // 先记一笔「截了什么」，让用户看到缩略图和序号
  noteScreenshot(dataUrl);
  const match = /^data:(image\/[a-z+]+);base64,(.*)$/i.exec(dataUrl);
  if (!match) {
    systemNote('截图格式无法识别。');
    return;
  }
  el.send.disabled = true;
  stopVoice();
  try {
    const kb = Math.round(match[2].length * 0.75 / 1024);
    setStatus('正在上传截图（约 ' + kb + ' KB）…');
    const uploaded = await postJson('/api/upload', {
      media_type: match[1].toLowerCase(),
      data: match[2],
    });
    const asset = uploaded.url || '';
    if (!asset) throw new Error('上传没有返回地址');

    addBubble('user', '（手机截屏）');
    const thinking = addBubble('assistant', '', { typing: true });
    setStatus(state.displayName + ' 正在看…');
    const data = await postJson('/api/chat', {
      character_id: state.characterId,
      text: '看看我的屏幕。',
      image_asset: asset,
    });
    thinking.row.remove();
    const segments = segmentsFromReply(data);
    for (const segment of segments) {
      // deferText：先建空气泡，等这段语音播放时再逐字填
      const node = addBubble('assistant', segment.rawText || segment.text, {
        secondary: segment.rawText ? segment.text : '',
        deferText: true,
      });
      segment.bubble = node.bubble;
      setPortrait(segment.tone);
      enqueueSegment(segment);
      if (!state.voiceEnabled) await sleep(180);
    }
    if (!segments.length) systemNote('（这条回复没有可显示内容）');
    setStatus('');
  } catch (err) {
    systemNote('截图发送失败：' + (err && err.message ? err.message : err));
    setStatus('');
  } finally {
    el.send.disabled = false;
    state.dirtyPlayback = true;
  }
}

/**
 * 缩小成圆形小球（悬浮窗专用）。
 *
 * 原生把窗口缩到 56dp 并加载 ?mode=bubble 页面，只剩一个圆头像。
 * 点小球恢复、长按打开设置、拖动移动位置 —— 都复用已有的桥方法。
 *
 * 注意 setBubbleMode 在 App 内的 ServiceBridgeHost 是空实现，
 * 所以先判断是不是悬浮窗，再给出可读提示。
 */
function minimizeToBubble() {
  const native = nativeBridge();
  if (!native || typeof native.setBubbleMode !== 'function') {
    systemNote('缩小为小球需要在桌面立绘模式下使用。');
    return false;
  }
  if (!isOverlayPage()) {
    systemNote('缩小为小球只在桌面立绘模式下有效。');
    return false;
  }
  try {
    native.setBubbleMode(true);
    return true;
  } catch (error) {
    systemNote('缩小失败：' + (error && error.message ? error.message : error));
    return false;
  }
}

function installMiniBar() {
  if (!el.miniButton || !isOverlayPage()) return;
  if (el.miniBar) el.miniBar.classList.remove('hidden');
  el.miniButton.addEventListener('click', (event) => {
    event.preventDefault();
    event.stopPropagation();
    minimizeToBubble();
  });
}

/**
 * 迷你图标模式（?mode=bubble）。
 *
 * 最小化成小图标后只剩一个圆形头像，点它请原生把窗口恢复成完整大小。
 * 复用同一个页面和立绘，所以不需要额外的图片接口。
 */
function applyBubbleMode() {
  const params = new URLSearchParams(window.location.search);
  if (params.get('mode') !== 'bubble') return false;
  document.documentElement.classList.add('bubble-mode');
  document.body.classList.add('bubble-mode');
  const wrap = el.portraitWrap;
  if (!wrap) return true;
  installBubbleGestures(wrap);
  return true;
}

/**
 * 小球的手势：轻点恢复、长按开设置、拖动移动位置。
 *
 * 为什么不复用 installOverlayGestures：那个函数把「轻点」留给语音解锁、
 * 并且会做 5 秒长按。小球是独立的小窗口，语义不同 ——
 * 轻点必须立刻恢复，长按只需 1.5 秒就能叫出设置（小球上不好按太久）。
 * 拖动同样按帧合并，理由与悬浮窗拖动一致（每帧一次跨进程调用）。
 */
function installBubbleGestures(target) {
  const native = nativeBridge();
  if (!native || typeof native.setBubbleMode !== 'function') return;

  const DRAG_THRESHOLD = 5;
  const HOLD_MS = 1500;

  let holding = false;
  let dragging = false;
  let moved = 0;
  let lastX = 0;
  let lastY = 0;
  let holdTimer = 0;
  let startX = 0;
  let startY = 0;

  function restore() {
    try { native.setBubbleMode(false); } catch (error) { /* 忽略 */ }
  }

  function begin(x, y) {
    holding = true;
    dragging = false;
    moved = 0;
    startX = x; startY = y; lastX = x; lastY = y;
    clearTimeout(holdTimer);
    holdTimer = setTimeout(() => {
      if (!dragging) {
        holding = false;
        openConfig(true);
      }
    }, HOLD_MS);
  }

  function dragTo(x, y) {
    if (!holding) return;
    const dx = x - lastX;
    const dy = y - lastY;
    moved = Math.max(moved, Math.hypot(x - startX, y - startY));
    if (!dragging) {
      if (moved < DRAG_THRESHOLD) return;
      dragging = true;
      document.body.classList.add('dragging');
      clearTimeout(holdTimer);   // 开始拖动就不叫设置了
    }
    lastX = x; lastY = y;
    // 与悬浮窗同一条路径：增量下发 + 乘 dpr（原生收设备像素），
    // 亚像素余量由原生累积。见悬浮窗 move() 里的实测说明。
    try {
      const dpr = window.devicePixelRatio || 1;
      native.moveBy(dx * dpr, dy * dpr);
    } catch (error) { /* 忽略 */ }
  }

  function finish() {
    const wasDragging = dragging;
    const wasHolding = holding;
    holding = false;
    dragging = false;
    document.body.classList.remove('dragging');
    clearTimeout(holdTimer);
    // 位移已经逐次直接下发了，这里不需要再补发
    // 没拖动、也没长按触发设置 → 视为轻点，恢复完整窗口
    if (wasHolding && !wasDragging) restore();
  }

  target.addEventListener('touchstart', (event) => {
    if (event.touches.length !== 1) return;
    const t = event.touches[0];
    begin(t.clientX, t.clientY);
  }, { passive: true });

  target.addEventListener('touchmove', (event) => {
    if (!holding || event.touches.length !== 1) return;
    const t = event.touches[0];
    // 传屏坐标：小球也用绝对定位（和悬浮窗同一套换算）
    dragTo(t.clientX, t.clientY, t.screenX, t.screenY);
    event.preventDefault();
  }, { passive: false });

  target.addEventListener('touchend', (event) => { finish(); event.preventDefault(); }, { passive: false });
  target.addEventListener('touchcancel', (event) => { finish(); event.preventDefault(); }, { passive: false });

  // 鼠标版本，方便在电脑浏览器里调试
  target.addEventListener('mousedown', (event) => begin(event.clientX, event.clientY));
  window.addEventListener('mousemove', (event) => dragTo(event.clientX, event.clientY));
  window.addEventListener('mouseup', finish);
}

function loadPortraitScale() {
  let stored = null;
  try {
    stored = localStorage.getItem(PORTRAIT_SCALE_KEY);
  } catch (error) {
    stored = null;
  }
  if (stored !== null) {
    applyPortraitScale(stored, { persist: false });
    return;
  }
  // 没存过就问原生（App 里可能是从桌面立绘那边调的）
  const native = nativeBridge();
  if (native && typeof native.getScale === 'function') {
    try {
      const value = native.getScale();
      if (typeof value === 'number' && isFinite(value) && value > 0) {
        applyPortraitScale(value, { persist: false });
        return;
      }
    } catch (error) { /* 忽略 */ }
  }
  applyPortraitScale(1.0, { persist: false });
}

function attachPinchZoom() {
  const stage = el.stage;
  if (!stage) return;
  const distance = (touches) => {
    const dx = touches[0].clientX - touches[1].clientX;
    const dy = touches[0].clientY - touches[1].clientY;
    return Math.hypot(dx, dy);
  };
  stage.addEventListener('touchstart', (event) => {
    if (event.touches.length === 2) {
      state.pinchStart = distance(event.touches);
      state.pinchStartScale = state.portraitScale;
    }
  }, { passive: true });
  stage.addEventListener('touchmove', (event) => {
    if (event.touches.length !== 2 || !state.pinchStart) return;
    event.preventDefault();
    const ratio = distance(event.touches) / state.pinchStart;
    applyPortraitScale(state.pinchStartScale * ratio, { persist: false });
  }, { passive: false });
  const finish = () => {
    if (!state.pinchStart) return;
    state.pinchStart = 0;
    applyPortraitScale(state.portraitScale);
  };
  stage.addEventListener('touchend', finish, { passive: true });
  stage.addEventListener('touchcancel', finish, { passive: true });
}

/* ---------- 显示偏好 ---------- */

function readBool(key, fallback) {
  try {
    const value = localStorage.getItem(key);
    if (value === null) return fallback;
    return value === 'true';
  } catch (error) {
    return fallback;
  }
}

function writeBool(key, value) {
  try {
    localStorage.setItem(key, value ? 'true' : 'false');
  } catch (error) { /* 存储不可用时忽略 */ }
}

function applyShowOriginal(value) {
  state.showOriginal = !!value;
  document.body.classList.toggle('hide-original', !state.showOriginal);
  if (el.showOriginalToggle) el.showOriginalToggle.checked = state.showOriginal;
  writeBool(SHOW_ORIGINAL_KEY, state.showOriginal);
  applySubtitleFallback();
  updateSaveState();
}

/** 中文译文开关（双语字幕的另一半）。 */
function applyShowTranslation(value) {
  state.showTranslation = !!value;
  document.body.classList.toggle('hide-translation', !state.showTranslation);
  if (el.showTranslationToggle) el.showTranslationToggle.checked = state.showTranslation;
  writeBool(SHOW_TRANSLATION_KEY, state.showTranslation);
  applySubtitleFallback();
  updateSaveState();
}

/**
 * 双语字幕兜底：两种都不显示时气泡里会没有任何文字。
 * 这种情况强制显示日文原文 —— 宁可显示一种，也不要出现空气泡
 *（用户会以为功能坏了）。
 */
function applySubtitleFallback() {
  const noneVisible = !state.showOriginal && !state.showTranslation;
  document.body.classList.toggle('show-original-fallback', noneVisible);
}

function applyAutoScroll(value) {
  state.autoScroll = !!value;
  if (el.autoScrollToggle) el.autoScrollToggle.checked = state.autoScroll;
  writeBool(AUTO_SCROLL_KEY, state.autoScroll);
  updateSaveState();
}

function loadDisplayPrefs() {
  // 回放偏好期间不提示「有改动」，这不算用户的修改（见 updateSaveState）
  updateSaveState._loading = true;
  applyShowOriginal(readBool(SHOW_ORIGINAL_KEY, true));
  applyShowTranslation(readBool(SHOW_TRANSLATION_KEY, true));
  applyAutoScroll(readBool(AUTO_SCROLL_KEY, true));
  // 位置与字号：读回本地保存的值（没有就用默认）
  const stored = (key, fallback) => {
    try {
      const raw = localStorage.getItem(key);
      return raw === null ? fallback : Number(raw);
    } catch (error) {
      return fallback;
    }
  };
  applyPortraitOffset(stored(PORTRAIT_Y_KEY, 0), { persist: false });
  applyFontScale(stored(FONT_SCALE_KEY, 100), { persist: false });
  applyDialogShift(
    stored(DIALOG_X_KEY, 0),
    stored(DIALOG_Y_KEY, 0),
    { persist: false }
  );
  applyPortraitFloat(readBool(FLOAT_KEY, true), { persist: false });
  applyBubbleHideDelay(stored(BUBBLE_HIDE_DELAY_KEY, BUBBLE_HIDE_DEFAULT), { persist: false });
  setPressFeedbackEnabled(readBool(PRESS_FEEDBACK_KEY, PRESS_FEEDBACK_DEFAULT), { persist: false });
  updateSaveState._loading = false;
  setSaveState('设置会自动保存', false);
}

/* ---------- 配置页底部保存栏 ----------
 *
 * 显示类设置本来就是「改一下存一下」（立刻写 localStorage 并生效），
 * 但用户看不到这件事，会担心「改了到底有没有生效」。
 * 所以加一个显式的保存按钮 + 状态文字：
 *   - 任何设置变动 → 状态变成「有改动未保存」
 *   - 点保存 → 把所有显示偏好重新写一遍，状态变「已保存」并短暂高亮
 * 按钮不改变功能语义（不会出现「不点就不生效」），只是把已发生的事说清楚。
 */

function setSaveState(text, saved) {
  if (!el.saveState) return;
  el.saveState.textContent = text;
  el.saveState.classList.toggle('saved', !!saved);
  clearTimeout(setSaveState._timer);
  if (saved) {
    // 3 秒后回到中性提示，否则会一直停在「已保存」
    setSaveState._timer = setTimeout(() => {
      if (el.saveState) {
        el.saveState.textContent = '设置会自动保存';
        el.saveState.classList.remove('saved');
      }
    }, 3000);
  }
}

/** 有设置变动时调用：提示用户「已自动保存，也可点按钮确认」。 */
function updateSaveState() {
  // 启动时回放存储里的偏好也会走到这里，那不是「用户的改动」。
  // 不抑制的话一打开配置页就显示「有改动」，反而让人以为没保存好。
  if (updateSaveState._loading) return;
  setSaveState('已自动保存 · 可点右侧按钮确认', false);
}

function readNumber(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw === null ? fallback : Number(raw);
  } catch (error) {
    return fallback;
  }
}

/** 把所有显示偏好重新写进本机存储，并给出明确反馈。 */
function saveAllDisplaySettings() {
  try {
    localStorage.setItem(PORTRAIT_SCALE_KEY, String(state.portraitScale || 1));
    localStorage.setItem(PORTRAIT_Y_KEY, String(state.portraitY || 0));
    localStorage.setItem(FONT_SCALE_KEY, String(state.fontScale || 100));
    localStorage.setItem(DIALOG_X_KEY, String(state.dialogX || 0));
    localStorage.setItem(DIALOG_Y_KEY, String(state.dialogY || 0));
    localStorage.setItem(FLOAT_KEY, state.portraitFloat ? 'true' : 'false');
    localStorage.setItem(SHOW_ORIGINAL_KEY, state.showOriginal ? 'true' : 'false');
    localStorage.setItem(SHOW_TRANSLATION_KEY, state.showTranslation ? 'true' : 'false');
    localStorage.setItem(AUTO_SCROLL_KEY, state.autoScroll ? 'true' : 'false');
    localStorage.setItem(
      BUBBLE_HIDE_DELAY_KEY,
      String(Math.max(0, Math.min(60, Math.round(Number(state.bubbleHideSec) || 0))))
    );
  } catch (error) {
    setSaveState('保存失败：本机存储不可用', false);
    return;
  }
  setSaveState('已保存 ✓', true);
}

/**
 * 把一个「滑动条 + 数字框 + 文字」三联控件接好事件。
 *
 * 两个输入都要能改：拖滑块时同步数字，直接输数字时同步滑块。
 * 数字框用 change 而不是 input —— 输入过程中（比如想输 "-" 或删空）
 * 每敲一个字符都套用会很难用。
 */
function bindControl(rangeEl, numberEl, apply) {
  if (rangeEl) {
    rangeEl.addEventListener('input', () => apply(Number(rangeEl.value)));
  }
  if (numberEl) {
    const commit = () => {
      const value = Number(numberEl.value);
      if (!isFinite(value) || numberEl.value === '') {
        // 输入无效（空、或只有减号）时回退到上次有效值
        apply(Number(numberEl.dataset.last || 0));
        return;
      }
      apply(value);
    };
    numberEl.addEventListener('change', commit);
    // 回车立即生效，不用先点别处
    numberEl.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        event.preventDefault();
        commit();
        numberEl.blur();
      }
    });
  }
}

/* ---------- 配置页 ---------- */

function readConnectionInfo() {
  // App 内由原生页写进 localStorage；浏览器里可能是从 URL 直接进来的
  try {
    const saved = JSON.parse(localStorage.getItem('sakura.remote.server') || '{}');
    if (saved && typeof saved.server === 'string' && saved.server) {
      return { server: saved.server, token: typeof saved.token === 'string' ? saved.token : '' };
    }
  } catch (error) { /* 忽略 */ }
  const params = new URLSearchParams(window.location.search);
  const token = params.get('token') || '';
  const host = params.get('host') || location.host || '';
  return { server: host, token: token };
}

/**
 * token 打码显示。
 *
 * 默认打码是为了「别人瞄一眼屏幕也看不到完整口令」，
 * 但用户自己要核对/抄写时必须能看全 —— 所以配了「显示」按钮。
 */
function maskToken(token) {
  const value = String(token || '');
  if (!value) return '—';
  if (value.length <= 6) return '••••';
  return value.slice(0, 3) + '••••' + value.slice(-3);
}

/** 渲染 token 那一行：按当前显隐状态决定显示原文还是打码。 */
function renderToken() {
  const info = readConnectionInfo();
  const token = String(info.token || '');
  if (el.connToken) {
    // 显示模式下要能完整看到，所以不加省略号截断（用 CSS 换行处理长 token）
    el.connToken.textContent = state.tokenVisible ? (token || '—') : maskToken(token);
    el.connToken.classList.toggle('tokenPlain', state.tokenVisible);
  }
  if (el.tokenReveal) {
    el.tokenReveal.textContent = state.tokenVisible ? '隐藏' : '显示';
    el.tokenReveal.disabled = !token;
  }
  if (el.tokenCopy) {
    el.tokenCopy.disabled = !token;
  }
}

function toggleTokenVisible() {
  state.tokenVisible = !state.tokenVisible;
  renderToken();
}

/**
 * 复制 token 到剪贴板。
 *
 * 悬浮窗是从 http:// 加载的，navigator.clipboard 在非安全上下文里不可用，
 * 所以保留 execCommand 兜底 —— 否则点了「复制」毫无反应。
 */
async function copyToken() {
  const token = String(readConnectionInfo().token || '');
  if (!token) {
    setStatus('没有可复制的 token');
    return;
  }
  let copied = false;
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(token);
      copied = true;
    }
  } catch (error) { /* 落到下面的兜底 */ }
  if (!copied) {
    try {
      const helper = document.createElement('textarea');
      helper.value = token;
      helper.setAttribute('readonly', '');
      helper.style.position = 'fixed';
      helper.style.opacity = '0';
      document.body.appendChild(helper);
      helper.select();
      copied = document.execCommand('copy');
      document.body.removeChild(helper);
    } catch (error) { /* 复制失败 */ }
  }
  setStatus(copied ? 'token 已复制' : '复制失败，请长按手动选择');
  setTimeout(() => setStatus(''), 2200);
}

function renderConfig() {
  const info = readConnectionInfo();
  if (el.connAddress) el.connAddress.textContent = info.server || location.host || '—';
  renderToken();
  if (el.connCharacter) {
    el.connCharacter.textContent = state.characterId
      ? state.displayName + '（' + state.characterId + '）'
      : '—';
  }
  if (el.connTts) {
    el.connTts.textContent = state.serverSettings.tts_enabled ? '可用' : '已在电脑端关闭';
  }
  if (el.connHint) {
    el.connHint.textContent = isInsideApp()
      ? '换电脑或换 token 会回到连接设置页重新填写。'
      : '浏览器里换连接请直接改地址栏的 token 参数。';
  }
  if (el.autoplayToggle) el.autoplayToggle.checked = !!state.serverSettings.autoplay;
  if (el.ttsToggle) el.ttsToggle.checked = !!state.serverSettings.tts_enabled;
  renderPetPanel();
}

function isInsideApp() {
  return !!nativeBridge();
}

function openConfig(open) {
  state.configOpen = !!open;
  if (!el.configPanel) return;
  el.configPanel.classList.toggle('hidden', !state.configOpen);
  if (el.settingsButton) el.settingsButton.classList.toggle('on', state.configOpen);
  if (state.configOpen) {
    renderConfig();
    loadCharacters();
  }
}

/* ---------- 角色列表 ---------- */

/**
 * 拉取角色列表。
 *
 * 手机端**不能**直接切换角色（宿主的 `sakura.host.mobile` 只接受当前角色，
 * 且没有切换接口），所以切换走「改电脑端配置 + 重启 Sakura」这条路：
 * 电脑端没有 watcher 监听 characters.yaml，改完必须重启才生效。
 */
async function loadCharacters() {
  if (!el.charList) return;
  try {
    const data = await getJson('/api/characters');
    state.characters = Array.isArray(data.characters) ? data.characters : [];
    state.currentCharacter = data.current || '';
    state.selectedCharacter = data.selected || '';
    state.charactersUnavailable = data.unavailable || '';
    renderCharList();
  } catch (error) {
    if (el.charHint) el.charHint.textContent = '角色列表读取失败：'
      + (error && error.message ? error.message : error);
  }
}

function renderCharList() {
  if (!el.charList) return;
  const list = state.characters || [];
  el.charList.innerHTML = '';
  if (!list.length) {
    // 全新安装最常见就是这里：电脑端还没导入角色。
    // 说清「去哪加」而不是只显示一句「没有角色」。
    if (el.charHint) {
      el.charHint.textContent = state.charactersUnavailable
        ? '电脑端的角色服务还没就绪（' + state.charactersUnavailable + '）。'
          + '请确认 Sakura 已启动、并且已经导入至少一个角色。'
        : '电脑端还没有角色。请先在电脑端 Sakura 里导入角色'
          + '（角色放在 Sakura 的 characters 目录下），然后回到这里刷新。';
    }
    if (el.restartButton) el.restartButton.classList.remove('primaryButton');
    return;
  }
  const pending = state.selectedCharacter && state.selectedCharacter !== state.currentCharacter;
  if (el.charHint) {
    el.charHint.textContent = pending
      ? '已把「' + state.selectedCharacter + '」写入电脑端配置，'
        + '点下面「重启电脑端 Sakura」即可生效。'
      : '点一个角色即可切换。切换后需要重启 Sakura 才生效'
        + '（电脑端不监听角色配置文件）。当前是：'
        + (state.currentCharacter || '未知') + '。';
  }
  if (el.restartButton) {
    // 没有待生效的改动时不突出重启按钮，避免误点
    el.restartButton.classList.toggle('primaryButton', !!pending);
  }
  list.forEach((item) => {
    const row = document.createElement('button');
    row.type = 'button';
    const isCurrent = !!item.current;
    const isSelected = item.id === state.selectedCharacter && !isCurrent;
    row.className = 'charRow' + (isCurrent ? ' current' : '')
      + (isSelected ? ' pending' : '');
    const name = document.createElement('span');
    name.className = 'charName';
    name.textContent = item.name || item.id;
    const idNode = document.createElement('span');
    idNode.className = 'charId';
    idNode.textContent = item.id;
    row.appendChild(name);
    row.appendChild(idNode);
    if (isCurrent) {
      const badge = document.createElement('span');
      badge.className = 'charBadge';
      badge.textContent = '当前';
      row.appendChild(badge);
      row.disabled = true;
    } else if (isSelected) {
      const badge = document.createElement('span');
      badge.className = 'charBadge pendingBadge';
      badge.textContent = '待重启';
      row.appendChild(badge);
      row.addEventListener('click', () => selectCharacter(item));
    } else {
      row.addEventListener('click', () => selectCharacter(item));
    }
    el.charList.appendChild(row);
  });
}

/** 把所选角色写进电脑端配置（需重启 Sakura 生效）。 */
async function selectCharacter(item) {
  const label = item.name || item.id;
  const ok = window.confirm(
    '把当前角色切换为「' + label + '」？\n\n'
    + '手机端只能改电脑端的角色配置，改完需要重启 Sakura 才会生效。'
  );
  if (!ok) return;
  if (el.charHint) el.charHint.textContent = '正在写入电脑端配置…';
  try {
    const data = await postJson('/api/characters', { character_id: item.id });
    state.characters = Array.isArray(data.characters) ? data.characters : state.characters;
    state.currentCharacter = data.current || state.currentCharacter;
    state.selectedCharacter = data.selected || item.id;
    renderCharList();
    setStatus('已写入配置，重启后生效');
    // 直接问要不要现在重启 —— 多数人切完角色就是想立刻用
    if (window.confirm('已写入「' + label + '」。\n\n现在重启电脑端 Sakura 吗？')) {
      restartSakura();
    }
  } catch (error) {
    if (el.charHint) {
      el.charHint.textContent = '切换失败：'
        + (error && error.message ? error.message : error);
    }
  }
}

/**
 * 请求重启电脑端 Sakura。
 *
 * 服务器只是启动一个独立脚本就立刻返回，真正重启在后台进行，
 * 所以这里不能等结果 —— 连接马上会断开。
 */
async function restartSakura() {
  const ok = window.confirm(
    '重启电脑端 Sakura？\n\n'
    + '大约需要 10–40 秒。期间连接会断开，App 会暂时连不上，'
    + '等它起来后重新打开 App 即可。'
  );
  if (!ok) return;
  if (el.restartButton) el.restartButton.disabled = true;
  if (el.restartHint) el.restartHint.textContent = '正在通知电脑端重启…';
  try {
    const data = await postJson('/api/restart', {});
    if (el.restartHint) {
      el.restartHint.textContent = data && data.message
        ? data.message
        : '正在重启，请稍候…';
    }
  } catch (error) {
    // 重启开始后连接断开是正常的，所以这里只提示、不当失败
    if (el.restartHint) {
      el.restartHint.textContent = '连接已断开，电脑端可能正在重启。'
        + '等 10–40 秒后重新打开 App。';
    }
  }
  setStatus('电脑端正在重启');
  // 只有重启失败（连请求都没发出去）才恢复按钮，避免重复点
  setTimeout(() => {
    if (el.restartButton) el.restartButton.disabled = false;
  }, 30000);
}

async function saveVoiceSettings() {
  if (!el.saveVoice) return;
  el.saveVoice.disabled = true;
  if (el.voiceSaveHint) el.voiceSaveHint.textContent = '正在保存…';
  try {
    const data = await postJson('/api/settings', {
      settings: {
        autoplay: !!(el.autoplayToggle && el.autoplayToggle.checked),
        tts_enabled: !!(el.ttsToggle && el.ttsToggle.checked),
      },
    });
    const settings = (data && data.settings) || {};
    state.serverSettings = {
      autoplay: settings.autoplay !== false,
      tts_enabled: settings.tts_enabled !== false,
    };
    state.voiceEnabled = state.serverSettings.tts_enabled;
    updateVoiceButton();
    if (el.voiceSaveHint) el.voiceSaveHint.textContent = '已保存到电脑端。';
  } catch (error) {
    if (el.voiceSaveHint) {
      el.voiceSaveHint.textContent = '保存失败：' + (error && error.message ? error.message : error);
    }
  } finally {
    el.saveVoice.disabled = false;
    setTimeout(() => {
      if (el.voiceSaveHint) el.voiceSaveHint.textContent = '';
    }, 3000);
  }
}

/* ---------- 桌面立绘开关（仅 App 内可用）---------- */

function readPetStatus() {
  const native = nativeBridge();
  if (!native || typeof native.petStatus !== 'function') return null;
  try {
    return JSON.parse(native.petStatus());
  } catch (error) {
    return null;
  }
}

function renderPetPanel() {
  if (!el.petSection) return;
  const status = readPetStatus();
  if (!status) {
    el.petSection.classList.add('unsupported');
    if (el.petHint) el.petHint.textContent = '桌面立绘需要在手机 App 内使用，浏览器里不支持。';
    if (el.petToggle) el.petToggle.disabled = true;
    if (el.batteryButton) el.batteryButton.disabled = true;
    return;
  }
  if (el.petToggle) {
    el.petToggle.textContent = status.running ? '关闭桌面立绘' : '开启桌面立绘';
    el.petToggle.classList.toggle('on', !!status.running);
  }
  if (el.batteryButton) {
    el.batteryButton.textContent = status.battery ? '已加入电池白名单' : '申请后台保活';
    el.batteryButton.classList.toggle('on', !!status.battery);
  }
  if (el.petHint) {
    if (status.running) {
      el.petHint.textContent = '桌面立绘运行中：单指拖动，双指捏合缩放，点角色回到对话。'
        + '想回到桌面看它就点上面的「收起 App」。';
    } else if (status.wanted && !status.canDraw) {
      el.petHint.textContent = '之前开过但悬浮窗权限被撤销了，重新授权即可恢复。';
    } else if (!status.canDraw) {
      el.petHint.textContent = '开启需要「显示在其他应用上层」权限，系统会弹窗让你授权。'
        + '授权后 App 会自动收起，角色就留在桌面上了。';
    } else {
      el.petHint.textContent = '开启后 App 会自动收起，角色留在桌面上；点角色可回到对话。';
    }
  }
}

/* ---------- 基础工具 ---------- */

function api(path) {
  const separator = path.includes('?') ? '&' : '?';
  return path + separator + 'token=' + encodeURIComponent(TOKEN);
}

function setStatus(text) {
  el.status.textContent = text || '';
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchJson(path, options = {}) {
  let response;
  try {
    response = await fetch(api(path), options);
  } catch (error) {
    throw new Error('连不上电脑端（' + (error && error.message ? error.message : error) + '）');
  }
  let data = {};
  try {
    data = await response.json();
  } catch (error) {
    data = {};
  }
  if (!response.ok) {
    const message = data && data.error ? data.error : '请求失败 HTTP ' + response.status;
    const failure = new Error(message);
    failure.busy = !!(data && data.busy);
    throw failure;
  }
  return data;
}

function postJson(path, body) {
  return fetchJson(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Sakura-Remote-Token': TOKEN },
    body: JSON.stringify(Object.assign({ token: TOKEN }, body)),
  });
}

/** GET 版：token 走查询串（和 /api/state 等读取接口一致）。 */
function getJson(path) {
  return fetchJson(api(path));
}

/* ---------- 立绘与表情 ---------- */

const TONE_STOPWORDS = ['有点', '有些', '非常', '特别', '十分', '稍微', '不太', '有点点', '的', '了', '着', '地', '得', '很', '挺'];

function toneTokens(tone) {
  let rest = String(tone || '').trim();
  TONE_STOPWORDS.forEach((word) => {
    rest = rest.split(word).join('');
  });
  const tokens = [];
  if (rest) tokens.push(rest);
  for (const char of rest) {
    if (/[\u4e00-\u9fff]/.test(char)) tokens.push(char);
  }
  return tokens;
}

function scorePortrait(tone, key) {
  if (!tone || !key) return 0;
  if (tone === key) return 1000;
  if (key.indexOf(tone) >= 0) return 600 - key.length;
  if (tone.indexOf(key) >= 0) return 400 - Math.abs(key.length - tone.length);
  let score = 0;
  const tokens = toneTokens(tone);
  tokens.forEach((token, index) => {
    if (token.length > 1 && key.indexOf(token) >= 0) {
      score += 120 - index * 2;
      return;
    }
    if (token.length === 1 && key.indexOf(token) >= 0) score += 40;
  });
  if (score > 0) score += Math.max(0, 24 - key.length);
  return score;
}

function resolvePortraitKey(tone) {
  const cleanTone = String(tone || '').trim();
  if (cleanTone && state.portraitOverrides.has(cleanTone)) {
    const forced = state.portraitOverrides.get(cleanTone);
    if (state.portraitByKey.has(forced)) return forced;
  }
  let bestScore = 0;
  let bestKey = '';
  state.portraitAlias.forEach((_value, key) => {
    if (key === '__default__') return;
    const score = scorePortrait(cleanTone, key);
    if (score > bestScore) {
      bestScore = score;
      bestKey = key;
    }
  });
  if (bestKey) return bestKey;
  if (state.defaultPortraitKey) return state.defaultPortraitKey;
  return state.portraitByKey.keys().next().value || '';
}

function setPortrait(tone) {
  const key = resolvePortraitKey(tone);
  if (!key || key === state.currentPortraitKey) return;
  const url = state.portraitByKey.get(key);
  if (!url) return;
  state.currentPortraitKey = key;
  el.portrait.classList.add('switching');
  const next = new Image();
  next.onload = () => {
    el.portrait.src = url;
    requestAnimationFrame(() => el.portrait.classList.remove('switching'));
  };
  next.onerror = () => el.portrait.classList.remove('switching');
  next.src = url;
}

function applyTheme(theme) {
  if (!theme || typeof theme !== 'object') return;
  const map = {
    primary_color: '--primary',
    primary_hover_color: '--primary-hover',
    accent_color: '--accent',
    text_color: '--text',
    secondary_text_color: '--secondary-text',
    muted_text_color: '--muted-text',
    page_background_color: '--page-bg',
    panel_background_color: '--panel-bg',
    input_background_color: '--input-bg',
    bubble_background_color: '--bubble-bg',
    border_color: '--border',
  };
  Object.keys(map).forEach((source) => {
    const value = theme[source];
    if (typeof value === 'string' && value) {
      document.documentElement.style.setProperty(map[source], value);
    }
  });
  document.documentElement.style.setProperty('--stage-bg', theme.page_background_color || '#fff6fa');
}

/* ---------- 气泡 ---------- */

function scrollBubbles() {
  if (!state.autoScroll) return;
  requestAnimationFrame(() => {
    el.bubbles.scrollTop = el.bubbles.scrollHeight;
  });
}

function addBubble(role, text, options = {}) {
  const row = document.createElement('div');
  row.className = 'bubbleRow ' + role;
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  if (options.typing) {
    bubble.classList.add('typing');
    for (let index = 0; index < 3; index += 1) bubble.appendChild(document.createElement('i'));
  } else {
    const secondary = String(options.secondary || '').trim();
    const primary = String(text || '').trim();
    // deferText：先建好空容器，等轮到这条语音播放时再逐字填进去。
    // 完整内容记在节点上，供 revealBubbleText 使用。
    const defer = !!options.deferText;
    if (secondary && secondary !== primary) {
      const original = document.createElement('div');
      original.className = 'textOriginal';
      original.textContent = defer ? '' : primary;
      const translation = document.createElement('div');
      translation.className = 'textTranslation';
      translation.textContent = defer ? '' : secondary;
      bubble.appendChild(original);
      bubble.appendChild(translation);
    } else {
      bubble.textContent = defer ? '' : primary;
    }
    bubble._pendingText = primary;
    bubble._pendingSecondary = secondary;
  }
  row.appendChild(bubble);
  el.bubbles.appendChild(row);
  // 悬浮窗里气泡行是隐藏的（display:none），没法再从 DOM 里读文字，
  // 所以在这里把内容存一份给历史导航用。
  if (!options.typing) {
    row.dataset.role = role;
    row.dataset.primary = String(text || '');
    row.dataset.secondary = String(options.secondary || '');
  }
  trimBubbles();
  scrollBubbles();
  // 两种模式都要同步：
  //   - 悬浮窗：切换「当前显示这一条」
  //   - App：显示/隐藏翻阅按钮并更新它的可用状态
  // 原来只在悬浮窗模式调用，导致 #msgNav 的 .hidden 类在 App 内永远不会被移除，
  // 翻阅按钮一直藏着（实测有 16 条历史也没有按钮）。
  syncMessageNav();
  // 有新内容就把对话框显示出来并重新计时（说完一段时间后才收起）
  scheduleBubbleAutoHide();
  return { row: row, bubble: bubble };
}

/* ---------- 单句气泡与历史切换 ---------- */

/* ---------- 悬浮窗布局参数 ---------- */

/**
 * 悬浮窗要记住自己请求的窗口尺寸。
 *
 * 为什么需要：改窗口尺寸后 Android WebView 不会重算布局视口
 *（实测窗口 2460，innerHeight 还是旧值），所以布局不能读 innerHeight。
 * 而「请求的尺寸」是我们自己算的，一定准确，reload 之后也能从
 * sessionStorage 读回来 —— 这样布局结果稳定、与视口无关。
 */
const OVERLAY_WIN_KEY = 'sakura.overlayWin';

function readOverlayWindowSize() {
  try {
    const raw = sessionStorage.getItem(OVERLAY_WIN_KEY) || '';
    const match = /^(\d+)x(\d+)$/.exec(raw);
    if (match) {
      return { w: Number(match[1]), h: Number(match[2]) };
    }
  } catch (error) { /* 存储不可用时当没有 */ }
  return null;
}

function rememberOverlayWindowSize(w, h) {
  try {
    sessionStorage.setItem(OVERLAY_WIN_KEY, Math.round(w) + 'x' + Math.round(h));
  } catch (error) { /* 忽略 */ }
}

/** 由 installContentWidthSync 赋值：重新测量并调整窗口尺寸。 */
var relayoutOverlay = null;

function isOverlayPage() {
  // 判空是为了能在 Node 测试桩里跑（桩件没有完整 DOM）
  const body = typeof document !== 'undefined' ? document.body : null;
  return !!(body && body.classList && body.classList.contains('overlay-mode'));
}

/**
 * 悬浮窗里一次只显示一条气泡，避免整段历史把立绘盖住。
 *
 * 全部气泡节点都还在 DOM 里（语音、语气表情都依赖它们），
 * 只是用 .current 控制哪一条可见。完整历史仍然在 App 内可看。
 */
function syncMessageNav(showLatest = true) {
  const rows = Array.from(el.bubbles.querySelectorAll('.bubbleRow'));
  state.messageCount = rows.length;
  if (!rows.length) {
    state.messageIndex = 0;
  } else if (showLatest || state.messageIndex >= rows.length) {
    state.messageIndex = rows.length - 1;
  } else if (state.messageIndex < 0) {
    state.messageIndex = 0;
  }
  applyMessageNav();
  return rows;
}

function applyMessageNav(options = {}) {
  const rows = Array.from(el.bubbles.querySelectorAll('.bubbleRow'));
  const total = rows.length;

  // ---- App 内：完整历史 + 翻阅 ----
  // 不隐藏任何气泡，只维护「当前定位到第几条」，由 stepMessage 滚动过去。
  // 翻阅按钮仍然显示，方便快速跳到上一条/下一条（历史很长时很实用）。
  if (!isOverlayPage()) {
    if (el.msgNav) el.msgNav.classList.toggle('hidden', total === 0);
    if (el.msgPrev) el.msgPrev.disabled = state.messageIndex <= 0;
    if (el.msgNext) el.msgNext.disabled = state.messageIndex >= total - 1;
    updateMsgCounter(total);
    return;
  }

  rows.forEach((row, index) => {
    row.classList.toggle('current', index === state.messageIndex);
  });
  if (el.msgNav) {
    el.msgNav.classList.toggle('hidden', total === 0);
  }
  if (el.msgPrev) el.msgPrev.disabled = state.messageIndex <= 0;
  if (el.msgNext) el.msgNext.disabled = state.messageIndex >= total - 1;
  updateMsgCounter(total);

  // 把当前这条渲染进对话框卡片（参考电脑端：文字 + 右侧竖排切换按钮）。
  // 内容取自 row.dataset —— 气泡行在悬浮窗里是 display:none，
  // 从隐藏 DOM 里读不到文字（querySelector('.bubble') 会返回 null）。
  const row = total ? rows[state.messageIndex] : null;
  if (el.msgBody) {
    el.msgBody.innerHTML = '';
    const role = row ? (row.dataset.role || '') : '';
    el.msgBody.className = 'msgBody ' + role;
    if (row) {
      const primary = row.dataset.primary || '';
      const secondary = row.dataset.secondary || '';
      if (secondary && secondary !== primary) {
        const original = document.createElement('div');
        original.className = 'textOriginal';
        original.textContent = primary;
        const translation = document.createElement('div');
        translation.className = 'textTranslation';
        translation.textContent = secondary;
        el.msgBody.appendChild(original);
        el.msgBody.appendChild(translation);
      } else {
        el.msgBody.textContent = primary;
      }
    }
    el.msgBody.scrollTop = 0;
  }
  /*
   * 短回复让卡片保持紧凑，长回复才放开宽度。
   *
   * 卡片宽度现在是 fit-content（见 app.css），所以「嗯」不会撑成大框。
   * 但纯 fit-content 对长文本会一直往宽里长到上限，再靠换行 —— 与原来一样；
   * 所以这里只负责在两档之间切换：短文本紧凑、长文本占满可用宽度。
   * 阈值取 24 个字：大约是一行中文的容量，超过就该换行了。
   */
  const CARD_WIDE_CHARS = 24;
  if (el.msgNav) {
    const body = el.msgBody ? el.msgBody.textContent : '';
    const len = body ? body.length : 0;
    el.msgNav.classList.toggle('wide', len > CARD_WIDE_CHARS);
  }

  if (el.msgName) {
    if (!row) {
      el.msgName.textContent = '';
    } else if (row.dataset.role === 'user') {
      el.msgName.textContent = '我';
    } else if (row.dataset.role === 'system') {
      el.msgName.textContent = '提示';
    } else {
      el.msgName.textContent = state.displayName || '角色';
    }
  }

  // 卡片内容变了，高度也就变了 —— 立刻重新量一次并调整窗口，
  // 否则窗口还是按「空卡片」的高度算的，输入栏会把卡片压住。
  // options.skipRelayout 用来打断递归：measure() 会先调本函数再量高度。
  if (!options.skipRelayout && typeof relayoutOverlay === 'function') {
    relayoutOverlay();
  }
}

/**
 * 更新「第几条 / 共几条」。
 *
 * 没有它的话，用户看到两个箭头不知道有没东西可翻、翻到哪了，
 * 很容易以为按钮没生效。总数 1 时显示成「1 / 1」，一眼看出没有更多。
 */
function updateMsgCounter(total) {
  if (!el.msgCounter) return;
  if (!total) {
    el.msgCounter.textContent = '';
    return;
  }
  el.msgCounter.textContent = (state.messageIndex + 1) + ' / ' + total;
}

function stepMessage(delta) {
  const rows = el.bubbles.querySelectorAll('.bubbleRow');
  const total = rows.length;
  if (!total) return;
  const next = Math.min(total - 1, Math.max(0, state.messageIndex + delta));
  if (next === state.messageIndex) return;
  state.messageIndex = next;
  applyMessageNav();
  // App 内不切换可见性，改为把这一条滚进视野并短暂高亮，
  // 否则按了翻页按钮画面没有任何变化，用户会以为坏了。
  if (!isOverlayPage()) {
    scrollToMessage(rows[next]);
  }
  // 翻页后重新计时，别刚翻到就自动消失
  scheduleBubbleAutoHide();
}

/** App 内翻阅：把指定气泡滚到视野中并短暂高亮。 */
function scrollToMessage(row) {
  if (!row) return;
  try {
    row.scrollIntoView({ block: 'center', behavior: 'smooth' });
  } catch (error) {
    // 老 WebView 不支持 options，退回直接定位
    row.scrollIntoView();
  }
  row.classList.add('navHit');
  clearTimeout(scrollToMessage._timer);
  scrollToMessage._timer = setTimeout(() => row.classList.remove('navHit'), 900);
}

/* ---------- 语音解锁提示：只出现一次 ----------
 *
 * 这条提示的用途是告诉用户「要轻触一下才能出声」（浏览器强制要求用户交互）。
 * 但它一直挂在立绘上很碍眼，所以按「只教一次」处理：
 * 首次展示并成功解锁后就永久记住，之后不再出现。
 *
 * 注意解锁状态本身不能持久化 —— 每次会话都要重新触摸一次（浏览器策略），
 * 所以分离成两个标记：SHOWN（提示教过没有）和 audioUnlocked（本次会话是否已解锁）。
 */
const HINT_SHOWN_KEY = 'sakura.remote.hintShown';

function hintAlreadyShown() {
  try {
    return localStorage.getItem(HINT_SHOWN_KEY) === '1';
  } catch (error) {
    return false;
  }
}

function markHintShown() {
  try {
    localStorage.setItem(HINT_SHOWN_KEY, '1');
  } catch (error) { /* 存不了就退化成每次都显示 */ }
}

/** 需要提示时调用：已经教过就不再显示。 */
function showHintOnce() {
  if (!el.hint) return;
  if (hintAlreadyShown()) return;
  el.hint.classList.add('show');
}

function hideHint() {
  if (!el.hint) return;
  el.hint.classList.remove('show');
  markHintShown();
}

/* ---------- 对话框自动隐藏 ----------
 *
 * 两种情况收起对话框：
 *   1. 一段时间没有新消息（默认 12 秒，可在配置页关掉或调时间）
 *   2. 点到卡片和输入栏以外的地方（点立绘、点透明处）
 *
 * 收起只是加 .auto-hidden 类（不 display:none），这样窗口尺寸不会变、
 * 不会因为隐藏而触发一次重新布局导致位置跳动；新消息来了直接移掉类即可。
 */

const BUBBLE_HIDE_DELAY_KEY = 'sakura.remote.bubbleHideSec';
const BUBBLE_HIDE_DEFAULT = 12;

/*
 * 旧版本把「自动隐藏」的默认值写成过 30 秒（当时为方便测试调的），
 * 而且配置页的滑块范围是 0-60，用户很难判断 30 是不是自己设的。
 * 这里做一次性迁移：只把「恰好是 30」的旧值改回 12，
 * 之后用户在配置页主动设的 30 会被保留（migration 只跑一次）。
 */
const BUBBLE_HIDE_MIGRATION_KEY = 'sakura.remote.bubbleHideMigrated';
function migrateBubbleHideDelay() {
  try {
    if (localStorage.getItem(BUBBLE_HIDE_MIGRATION_KEY) === '1') return;
    if (localStorage.getItem(BUBBLE_HIDE_DELAY_KEY) === '30') {
      localStorage.setItem(BUBBLE_HIDE_DELAY_KEY, String(BUBBLE_HIDE_DEFAULT));
    }
    localStorage.setItem(BUBBLE_HIDE_MIGRATION_KEY, '1');
  } catch (error) { /* 存不了也无所谓 */ }
}
migrateBubbleHideDelay();

let bubbleHideTimer = 0;

function bubbleHideDelayMs() {
  // 0 表示关闭自动隐藏
  let seconds = BUBBLE_HIDE_DEFAULT;
  try {
    const raw = localStorage.getItem(BUBBLE_HIDE_DELAY_KEY);
    if (raw !== null && raw !== '') {
      const n = Number(raw);
      if (Number.isFinite(n) && n >= 0) seconds = n;
    }
  } catch (error) { /* 读不到就用默认 */ }
  return seconds * 1000;
}

function hideBubble(immediate) {
  if (!el.msgNav || el.msgNav.classList.contains('hidden')) return;
  if (state.bubbleAutoHideOff) return;
  el.msgNav.classList.add('auto-hidden');
  if (immediate) {
    // 立刻收起：把过渡也去掉，避免还要等动画
    el.msgNav.classList.add('auto-hidden-now');
  }
  // 立刻让立绘补位。等 measure() 的 400ms 定时器会让这段空隙被看见。
  refreshBubbleLayout();
}

function showBubble() {
  if (!el.msgNav) return;
  const wasHidden = el.msgNav.classList.contains('auto-hidden');
  el.msgNav.classList.remove('auto-hidden');
  el.msgNav.classList.remove('auto-hidden-now');
  // 弹出气泡时立绘要让位，同样立刻生效
  if (wasHidden) refreshBubbleLayout();
}

/* ---------- 逐字显示（打字机） ----------
 *
 * 为什么要做：一次回复会被切成多条 segment，每条各自合成一段语音。
 * 如果文字一次性全铺出来，用户会「先读完后听到」，语音和文字完全脱节，
 * 观感上像两条不相干的流。
 *
 * 所以改成：气泡先建好但**内容是空的**，等到这条 segment 的语音真正开始
 * 播放时，再按音频时长把文字逐字填进去 —— 读完刚好也说完了。
 *
 * 没开语音时（或合成失败）不能一直空着，退回到按字数估算的节奏补上，
 * 否则用户会看到一堆空气泡。
 */

/** 没有音频可依据时，每个字的间隔（毫秒）。 */
const TYPE_FALLBACK_MS = 55;
/** 逐字最快不超过这个间隔，避免长文本一闪而过。 */
const TYPE_MIN_MS = 18;
/**
 * 逐字最慢不超过这个间隔。
 *
 * 入参单位是秒；一旦上游给错单位或时长异常，间隔会被算成几十秒，
 * 看起来就是文字卡住不出来。有上限就只会慢一点，不会卡死。
 */
const TYPE_MAX_MS = 260;

/** 停掉某条气泡正在进行的逐字动画。 */
function stopBubbleTyping(bubble) {
  if (!bubble) return;
  if (bubble._typeTimer) {
    clearInterval(bubble._typeTimer);
    bubble._typeTimer = 0;
  }
}

/**
 * 把气泡里的文字逐字显示出来。
 *
 * durationMs：音频总时长；不给就按字数估算。
 * 结束时一定把完整内容补上，不能出现「显示到一半就停了」。
 */
function revealBubbleText(bubble, durationMs) {
  if (!bubble) return;
  stopBubbleTyping(bubble);

  const primary = String(bubble._pendingText || '');
  const secondary = String(bubble._pendingSecondary || '');
  const hasSecondary = secondary && secondary !== primary;

  const primaryEl = hasSecondary ? bubble.querySelector('.textOriginal') : bubble;
  const secondaryEl = hasSecondary ? bubble.querySelector('.textTranslation') : null;
  if (!primaryEl) return;

  const total = Math.max(primary.length, hasSecondary ? secondary.length : 0);
  if (!total) return;

  // 先把完整文字写回 dataset，历史导航/翻页仍能拿到全文
  const row = bubble.parentElement;
  if (row && row.dataset) {
    if (!row.dataset.primary) row.dataset.primary = primary;
    if (!row.dataset.secondary) row.dataset.secondary = secondary;
  }

  // 音频时长未知或过短时，按字数估算一个合理节奏
  let perChar = TYPE_FALLBACK_MS;
  if (durationMs && isFinite(durationMs) && durationMs > 0) {
    perChar = Math.max(TYPE_MIN_MS, (durationMs * 1000) / total);
  }
  /*
   * 兜底上限：单字间隔不能太大。
   *
   * 入参单位是**秒**（blob.duration 就是秒）。万一上游给错单位、或者拿到
   * 一个异常大的时长，perChar 会被算成几十秒 —— 现象就是「文字一直不出来」。
   * 实测踩过：把 1000（毫秒）当秒传进来，算出每字 50 秒，停在第一个字不动。
   * 超过上限就整体压缩，宁可快一点也不能卡住。
   */
  if (perChar > TYPE_MAX_MS) perChar = TYPE_MAX_MS;

  let shown = 0;
  const apply = () => {
    primaryEl.textContent = primary.slice(0, shown);
    if (secondaryEl) secondaryEl.textContent = secondary.slice(0, shown);
  };

  // 先清空再逐字填
  primaryEl.textContent = '';
  if (secondaryEl) secondaryEl.textContent = '';
  bubble.classList.add('revealing');

  bubble._typeTimer = setInterval(() => {
    shown += 1;
    if (shown >= total) {
      shown = total;
      apply();
      stopBubbleTyping(bubble);
      bubble.classList.remove('revealing');
      return;
    }
    apply();
  }, perChar);

  // 立即显示第一个字，避免开头有一个间隔的空白
  shown = 1;
  apply();
}

/** 立刻把内容补全（用于跳过动画、或语音提前结束）。 */
function finishBubbleText(bubble) {
  if (!bubble) return;
  stopBubbleTyping(bubble);
  bubble.classList.remove('revealing');
  const primary = String(bubble._pendingText || '');
  const secondary = String(bubble._pendingSecondary || '');
  const hasSecondary = secondary && secondary !== primary;
  if (hasSecondary) {
    const a = bubble.querySelector('.textOriginal');
    const b = bubble.querySelector('.textTranslation');
    if (a) a.textContent = primary;
    if (b) b.textContent = secondary;
  } else {
    bubble.textContent = primary;
  }
}

/**
 * 对话框显隐后刷新布局。
 *
 * 卡片高度收为 0 是 260ms 的过渡，窗口尺寸要等它走完才算得准 ——
 * 过渡中途量到的是中间值，窗口会算小或算大。
 * 所以先用 rAF 让类名生效（补位立刻可见），再等过渡结束补一次精确重算。
 */
function refreshBubbleLayout() {
  // 重排只服务悬浮窗（窗口尺寸 = 立绘 + 对话框 + 输入栏）。
  // App 内布局是满屏的，卡片显隐不影响窗口，跳过可省掉一次无谓的 DOM 测量。
  if (!isOverlayPage()) return;
  const apply = () => {
    if (typeof relayoutOverlay === 'function') relayoutOverlay();
  };
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(apply);
  } else {
    setTimeout(apply, 16);
  }
  // 过渡结束后再量一次，拿到收敛后的高度
  clearTimeout(refreshBubbleLayout._settle);
  refreshBubbleLayout._settle = setTimeout(apply, 320);
}

/** 有新内容时调用：先把对话框显示出来，再重新计时。 */
function scheduleBubbleAutoHide() {
  showBubble();
  clearTimeout(bubbleHideTimer);
  const delay = bubbleHideDelayMs();
  if (delay <= 0) {
    state.bubbleAutoHideOff = true;
    return;
  }
  state.bubbleAutoHideOff = false;
  bubbleHideTimer = setTimeout(() => hideBubble(false), delay);
}

/** 点到卡片/输入栏以外的地方 → 立刻收起。 */
function installBubbleTapToHide() {
  if (!isOverlayPage()) return;
  const KEEP = '#msgNav, #composer, #configPanel, #mediaMenu, #mediaPanel';
  window.addEventListener('pointerdown', (event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    // 点在这些交互区域里不收起
    if (target.closest(KEEP)) return;
    if (el.msgNav && el.msgNav.classList.contains('auto-hidden')) return;
    hideBubble(true);
  }, { passive: true, capture: true });
}

/** 配置页的「对话框自动隐藏」秒数（0 = 一直显示）。 */
function applyBubbleHideDelay(seconds, options = {}) {
  const value = Math.max(0, Math.min(60, Math.round(Number(seconds) || 0)));
  state.bubbleHideSec = value;   // 保存栏要用它写回存储
  const label = value === 0 ? '一直显示' : value + ' 秒';
  syncControl(el.bubbleHideRange, el.bubbleHideNumber, [el.bubbleHideValue], value, '');
  if (el.bubbleHideValue) el.bubbleHideValue.textContent = label;
  if (options.persist !== false) {
    try {
      localStorage.setItem(BUBBLE_HIDE_DELAY_KEY, String(value));
    } catch (error) { /* 存不了也不影响使用 */ }
    updateSaveState();
  }
  // 立即按新设置生效：0 就取消隐藏，否则重新计时
  if (value === 0) {
    state.bubbleAutoHideOff = true;
    clearTimeout(bubbleHideTimer);
    showBubble();
  } else {
    state.bubbleAutoHideOff = false;
    scheduleBubbleAutoHide();
  }
}

function trimBubbles(limit = 60) {
  while (el.bubbles.children.length > limit) {
    el.bubbles.removeChild(el.bubbles.firstChild);
  }
}

function systemNote(text) {
  const node = addBubble('system', text);
  return node;
}

/* ---------- 语音 ---------- */

let audio = null;

function ensureAudio() {
  if (!audio) {
    audio = new Audio();
    audio.preload = 'auto';
    audio.addEventListener('play', () => {
      state.playing = true;
      el.portrait.classList.add('speaking');
    });
    audio.addEventListener('ended', () => {
      state.playing = false;
      el.portrait.classList.remove('speaking');
      drainQueue();
    });
    audio.addEventListener('error', () => {
      state.playing = false;
      el.portrait.classList.remove('speaking');
      drainQueue();
    });
  }
  return audio;
}

function unlockAudio() {
  if (state.audioUnlocked) return;
  const player = ensureAudio();
  const silent = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YQAAAAA=';
  player.src = silent;
  const attempt = player.play();
  if (attempt && typeof attempt.then === 'function') {
    attempt.then(() => {
      state.audioUnlocked = true;
      hideHint();
      player.pause();
      drainQueue();
    }).catch(() => {
      showHintOnce();
    });
  }
}

function updateVoiceButton() {
  el.voiceToggle.textContent = state.voiceEnabled ? '语音' : '静音';
  el.voiceToggle.classList.toggle('off', !state.voiceEnabled);
  renderQuickActions();
}

function renderQuickActions() {
  if (el.quickVoice) {
    el.quickVoice.textContent = state.voiceEnabled ? '语音：开' : '语音：关';
    el.quickVoice.classList.toggle('on', state.voiceEnabled);
  }
}

function enqueueSegment(segment) {
  if (!state.voiceEnabled || !segment.rawText) {
    // 没有语音可依据，仍按估算节奏逐字显示 —— 不能留着空气泡
    if (segment.bubble) revealBubbleText(segment.bubble, 0);
    return;
  }
  state.pending.push(segment);
  drainQueue();
}

function drainQueue() {
  if (state.playing || state.busy) return;
  const next = state.pending.shift();
  if (!next) {
    if (state.dirtyPlayback) {
      state.dirtyPlayback = false;
      loadHistory(true);
    }
    return;
  }
  playSegment(next);
}

async function playSegment(segment) {
  if (!state.audioUnlocked) {
    state.pending.unshift(segment);
    showHintOnce();
    return;
  }
  const player = ensureAudio();
  state.busy = true;
  setStatus(state.displayName + ' 正在说话…');
  try {
    const response = await fetch(api('/api/tts'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Sakura-Remote-Token': TOKEN },
      body: JSON.stringify({
        token: TOKEN,
        character_id: segment.characterId,
        text: segment.rawText,
        tone: segment.tone,
      }),
    });
    if (!response.ok) {
      let message = '语音合成失败';
      try {
        const data = await response.json();
        if (data && data.error) message = data.error;
      } catch (error) {
        message = message;
      }
      systemNote(message);
      state.busy = false;
      // 合成失败不能连文字都不显示
      revealBubbleText(segment.bubble, 0);
      drainQueue();
      return;
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    segment.bubble.classList.add('speaking');
    setPortrait(segment.tone);
    player.onended = null;
    player.src = url;
    /*
     * 逐字显示：等到这条语音真的要出声了才开始打字。
     *
     * 时长优先取 blob 的实际时长；某些容器格式（如部分 WAV）在
     * duration 上会报 Infinity 或 NaN，那就按字数估算 —— revealBubbleText
     * 里会退回 TYPE_FALLBACK_MS 的节奏，不会卡住。
     */
    let audioSeconds = blob && isFinite(blob.duration) && blob.duration > 0 ? blob.duration : 0;
    if (!audioSeconds && isFinite(player.duration) && player.duration > 0) {
      audioSeconds = player.duration;
    }
    try {
      await player.play();
    } catch (error) {
      state.audioUnlocked = false;
      showHintOnce();
      state.pending.unshift(segment);
      state.busy = false;
      // 放不出来也必须把文字显示出来，否则这一条永远是空的
      revealBubbleText(segment.bubble, 0);
      return;
    }
    revealBubbleText(segment.bubble, audioSeconds);
    state.busy = false;
    const finish = () => {
      segment.bubble.classList.remove('speaking');
      // 音频提前结束（或出错）时，别让文字停在半截
      finishBubbleText(segment.bubble);
      URL.revokeObjectURL(url);
      drainQueue();
    };
    player.addEventListener('ended', finish, { once: true });
    player.addEventListener('error', finish, { once: true });
    setStatus('');
  } catch (error) {
    systemNote('语音请求失败：' + (error && error.message ? error.message : error));
    state.busy = false;
    drainQueue();
  }
}

function stopVoice() {
  // 先把挂起的逐字动画收尾，再清队列。
  // 顺序反了就遍历到已经被清空的数组，等于没执行。
  state.pending.forEach(function (seg) { if (seg.bubble) finishBubbleText(seg.bubble); });
  state.pending.length = 0;
  state.busy = false;
  if (audio) {
    audio.pause();
    audio.removeAttribute('src');
  }
  state.playing = false;
  el.portrait.classList.remove('speaking');
}

/* ---------- 状态与历史 ---------- */

async function loadState() {
  const data = await fetchJson('/api/state');
  state.characterId = data.characterId || '';
  // 拿不到名字时退回角色 id，再退回一个中性词 —— 不要写死某个角色名
  state.displayName = data.displayName || state.characterId || '角色';
  state.tones = Array.isArray(data.tones) ? data.tones : [];
  state.portraitByKey = new Map();
  state.portraitAlias = new Map();
  (data.portraits || []).forEach((item) => {
    if (item && item.key && item.url) {
      state.portraitByKey.set(item.key, item.url);
      state.portraitAlias.set(item.key, item.url);
    }
  });
  state.defaultPortraitKey = data.defaultPortraitKey || '';
  state.portraitOverrides = new Map(
    Object.entries(data.portraitOverrides || {}).filter(
      (entry) => typeof entry[1] === 'string' && entry[1]
    )
  );
  const initial = state.portraitByKey.get(state.defaultPortraitKey) || state.portraitByKey.values().next().value;
  if (initial) el.portrait.src = initial;
  state.currentPortraitKey = state.defaultPortraitKey;
  applyTheme(data.theme);
  const settings = data.settings || {};
  state.serverSettings = {
    autoplay: settings.autoplay !== false,
    tts_enabled: settings.tts_enabled !== false,
  };
  if (state.serverSettings.tts_enabled === false) state.voiceEnabled = false;
  if (state.serverSettings.autoplay === false) state.autoplay = false;
  el.name.textContent = state.displayName;
  document.title = state.displayName;
  el.text.placeholder = '对' + state.displayName + '说点什么…';
  updateVoiceButton();
}

function renderHistory(items) {
  el.bubbles.innerHTML = '';
  items.forEach((item) => {
    if (!item) return;
    if (item.role === 'user') {
      addBubble('user', item.content || '');
    } else if (item.role === 'assistant') {
      const raw = String(item.raw_content || '').trim();
      const shown = String(item.content || '').trim();
      addBubble('assistant', raw || shown, { secondary: raw ? shown : '' });
    }
  });
  scrollBubbles();
}

async function loadHistory(silent) {
  if (!state.characterId) return;
  try {
    const data = await fetchJson(
      '/api/history?character_id=' + encodeURIComponent(state.characterId) + '&limit=40'
    );
    renderHistory(data.history || []);
  } catch (error) {
    if (!silent) systemNote('历史记录读取失败：' + error.message);
  }
}

/* ---------- 发送 ---------- */

function segmentsFromReply(data) {
  const raw = Array.isArray(data.segments) ? data.segments : [];
  const result = [];
  raw.forEach((item) => {
    if (!item) return;
    const spoken = String(item.raw_content || '').trim();
    const shown = String(item.content || item.raw_content || '').trim();
    if (!shown && !spoken) return;
    result.push({
      characterId: data.character_id || state.characterId,
      text: shown,
      rawText: spoken,
      tone: String(item.tone || ''),
      portrait: String(item.portrait || ''),
    });
  });
  if (!result.length) {
    const fallback = String(data.reply || '').trim();
    if (fallback) {
      result.push({
        characterId: data.character_id || state.characterId,
        text: fallback,
        rawText: String(data.reply_raw || fallback),
        tone: String(data.tone || ''),
        portrait: '',
      });
    }
  }
  return result;
}

async function readImage(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

async function uploadImage(file) {
  const dataUrl = await readImage(file);
  const match = /^data:([^;,]+);base64,(.*)$/.exec(dataUrl);
  if (!match) throw new Error('图片格式无法识别。');
  const result = await postJson('/api/upload', {
    media_type: match[1].toLowerCase(),
    data: match[2],
  });
  return result.url || '';
}

async function send(text, file) {
  const value = String(text || '').trim();
  if (!value && !file) return;
  el.send.disabled = true;
  stopVoice();
  unlockAudio();
  let attachment = '';
  try {
    if (file) {
      setStatus('正在上传图片…');
      attachment = await uploadImage(file);
    }
    addBubble('user', value + (file ? '\n（已附加图片）' : ''));
    el.text.value = '';
    el.image.value = '';
    el.image.classList.remove('selected');
    // 图已经发出去了，提示条也该收起来
    clearMediaInfo();
    const thinking = addBubble('assistant', '', { typing: true });
    setStatus(state.displayName + ' 正在思考…');
    const data = await postJson('/api/chat', {
      character_id: state.characterId,
      text: value,
      image_url: attachment,
    });
    thinking.row.remove();
    const segments = segmentsFromReply(data);
    for (const segment of segments) {
      // deferText：先建空气泡，等这段语音播放时再逐字填
      const node = addBubble('assistant', segment.rawText || segment.text, {
        secondary: segment.rawText ? segment.text : '',
        deferText: true,
      });
      segment.bubble = node.bubble;
      setPortrait(segment.tone);
      enqueueSegment(segment);
      if (!state.voiceEnabled) await sleep(180);
    }
    if (!segments.length) systemNote('（这条回复没有可显示内容）');
    setStatus('');
    if (state.voiceEnabled && !state.audioUnlocked) showHintOnce();
  } catch (error) {
    const message = error && error.message ? error.message : String(error);
    systemNote(message);
    setStatus('');
  } finally {
    el.send.disabled = false;
    state.dirtyPlayback = true;
  }
}

/* ---------- 事件绑定 ---------- */

el.form.addEventListener('submit', (event) => {
  event.preventDefault();
  const file = el.image.files && el.image.files[0];
  send(el.text.value, file);
});

el.text.addEventListener('input', () => {
  el.text.style.height = 'auto';
  el.text.style.height = Math.min(128, el.text.scrollHeight) + 'px';
});

el.text.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    el.form.dispatchEvent(new Event('submit'));
  }
});



el.voiceToggle.addEventListener('click', () => {
  state.voiceEnabled = !state.voiceEnabled;
  updateVoiceButton();
  if (!state.voiceEnabled) {
    stopVoice();
    setStatus('已静音，仅显示文字');
  } else {
    unlockAudio();
    setStatus('已开启语音');
    drainQueue();
  }
  setTimeout(() => setStatus(''), 1600);
});

el.clearButton.addEventListener('click', () => {
  stopVoice();
  el.bubbles.innerHTML = '';
  setStatus('已清空本机显示（不删除电脑端记录）');
  setTimeout(() => setStatus(''), 2200);
});

document.addEventListener('touchstart', unlockAudio, { passive: true });
document.addEventListener('click', unlockAudio);

/* ---------- 配置页事件 ---------- */

if (el.settingsButton) {
  el.settingsButton.addEventListener('click', () => openConfig(!state.configOpen));
}
if (el.configClose) {
  el.configClose.addEventListener('click', () => openConfig(false));
}
// 点遮罩/空白处关闭；点在面板内部不关
if (el.configPanel) {
  el.configPanel.addEventListener('click', (event) => {
    if (event.target === el.configPanel) openConfig(false);
  });
}
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && state.configOpen) openConfig(false);
});

if (el.scaleSmaller) {
  el.scaleSmaller.addEventListener('click', () => applyPortraitScale(state.portraitScale - 0.1));
}
if (el.scaleBigger) {
  el.scaleBigger.addEventListener('click', () => applyPortraitScale(state.portraitScale + 0.1));
}
if (el.scaleReset) {
  el.scaleReset.addEventListener('click', () => applyPortraitScale(1.0));
}
// 角色大小：滑块与数字输入双向联动（百分数 → 倍率）
bindControl(el.scaleRange, el.scaleNumber,
  (percent) => applyPortraitScale(Number(percent) / 100));

// 立绘上下位置 / 字体大小 / 对话框位移：滑块与数字输入双向联动
bindControl(el.portraitYRange, el.portraitYNumber, applyPortraitOffset);
bindControl(el.fontRange, el.fontNumber, applyFontScale);
bindControl(el.dialogXRange, el.dialogXNumber,
  (value) => applyDialogShift(value, state.dialogY));
bindControl(el.dialogYRange, el.dialogYNumber,
  (value) => applyDialogShift(state.dialogX, value));
bindControl(el.bubbleHideRange, el.bubbleHideNumber, applyBubbleHideDelay);

if (el.floatToggle) {
  el.floatToggle.addEventListener('change', () => {
    applyPortraitFloat(el.floatToggle.checked);
  });
}

if (el.saveAllButton) {
  el.saveAllButton.addEventListener('click', saveAllDisplaySettings);
}

if (el.pressFeedbackToggle) {
  el.pressFeedbackToggle.addEventListener('change', () => {
    setPressFeedbackEnabled(el.pressFeedbackToggle.checked);
  });
}

/**
 * 单独申请截屏授权。
 *
 * 为什么要单独一个按钮：系统那道「开始录制或投放」确认框只会在真正截图时弹，
 * 用户往往在「想发张截图」的当口被打断，还得先处理授权。
 * 放到设置页可以先授好，之后截图就是一步到位。
 *
 * 注意这个按钮在悬浮窗的配置页里也能看到，但悬浮窗页面调不动授权
 * （必须由 Activity 走 startActivityForResult），所以那种情况给出提示。
 */
function requestScreenPermission() {
  if (el.screenPermHint) el.screenPermHint.textContent = '正在请求系统授权…';
  const native = nativeBridge();
  if (!native || typeof native.requestScreenPermission !== 'function') {
    if (el.screenPermHint) {
      el.screenPermHint.textContent = '截屏授权需要在手机 App 内操作（当前是悬浮窗页面）。';
    }
    return;
  }
  try {
    native.requestScreenPermission();
  } catch (error) {
    if (el.screenPermHint) {
      el.screenPermHint.textContent = '请求失败：' + (error && error.message ? error.message : error);
    }
    return;
  }
  // 原生那边是异步的（等系统确认框），这里给个稍后回来核对状态的提示
  setTimeout(() => {
    if (!el.screenPermHint) return;
    let granted = null;
    try {
      if (typeof native.hasScreenPermission === 'function') granted = native.hasScreenPermission();
    } catch (error) { granted = null; }
    if (granted === true) {
      el.screenPermHint.textContent = '已获得截屏权限，现在可以正常「截取屏幕」了。';
    } else if (granted === false) {
      el.screenPermHint.textContent = '尚未获得授权。请点上面的按钮，并在系统弹窗里选择「立即开始」。';
    }
  }, 2500);
}

if (el.screenPermButton) {
  el.screenPermButton.addEventListener('click', requestScreenPermission);
}

if (el.tokenReveal) {
  el.tokenReveal.addEventListener('click', toggleTokenVisible);
}
if (el.tokenCopy) {
  el.tokenCopy.addEventListener('click', () => { copyToken(); });
}
if (el.restartButton) {
  el.restartButton.addEventListener('click', restartSakura);
}

if (el.showOriginalToggle) {
  el.showOriginalToggle.addEventListener('change', () => {
    applyShowOriginal(el.showOriginalToggle.checked);
  });
}
if (el.showTranslationToggle) {
  el.showTranslationToggle.addEventListener('change', () => {
    applyShowTranslation(el.showTranslationToggle.checked);
  });
}
if (el.autoScrollToggle) {
  el.autoScrollToggle.addEventListener('change', () => {
    applyAutoScroll(el.autoScrollToggle.checked);
  });
}
if (el.saveVoice) {
  el.saveVoice.addEventListener('click', saveVoiceSettings);
}

if (el.screenshotButton) {
  el.screenshotButton.addEventListener('click', () => {
    closeMediaMenu();
    requestScreenshot();
  });
}

/* 媒体下拉菜单：把「图片」「截屏」收进一个 ＋ 按钮，省下横向空间 */
if (el.mediaToggle) {
  el.mediaToggle.addEventListener('click', (event) => {
    event.stopPropagation();
    setMediaMenuOpen(el.mediaPanel && el.mediaPanel.classList.contains('hidden'));
  });
}
if (el.image) {
  // 选完图片就收起菜单，否则菜单会一直盖在输入框上方
  el.image.addEventListener('change', () => {
  closeMediaMenu();
  onPickedFile();
});
if (el.mediaInfoClear) {
  el.mediaInfoClear.addEventListener('click', () => {
    // 取消已选图片：清空 input 里的文件，否则提交时还会带上
    if (el.image) el.image.value = '';
    el.image && el.image.classList.remove('selected');
    clearMediaInfo();
  });
}
}
if (el.mediaPanel) {
  // 点菜单内部不要触发「点空白关闭」
  el.mediaPanel.addEventListener('click', (event) => event.stopPropagation());
}
document.addEventListener('click', (event) => {
  if (!el.mediaMenu || el.mediaMenu.contains(event.target)) return;
  closeMediaMenu();
});
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closeMediaMenu();
});

/* 悬浮窗顶栏去掉了「语音/清屏」，这里补回同样的能力 */
if (el.quickVoice) {
  el.quickVoice.addEventListener('click', () => {
    state.voiceEnabled = !state.voiceEnabled;
    updateVoiceButton();
    if (!state.voiceEnabled) stopVoice();
    else unlockAudio();
    renderQuickActions();
  });
}
if (el.quickClear) {
  el.quickClear.addEventListener('click', () => {
    stopVoice();
    if (el.bubbles) el.bubbles.innerHTML = '';
    state.messageIndex = 0;
    state.messageCount = 0;
    if (isOverlayPage()) syncMessageNav();
    setStatus('已清空本机显示（不删除电脑端记录）');
    setTimeout(() => setStatus(''), 2200);
  });
}

/* 悬浮窗：上下按钮翻历史 */
if (el.msgPrev) el.msgPrev.addEventListener('click', () => stepMessage(-1));
if (el.msgNext) el.msgNext.addEventListener('click', () => stepMessage(1));

if (el.switchServer) {
  el.switchServer.addEventListener('click', () => {
    if (isInsideApp()) {
      try {
        nativeBridge().open('about:blank');
      } catch (error) { /* 忽略 */ }
      // 原生桥接的 open 只接受 http/https，这里直接提示回连接页更可靠
      if (el.connHint) el.connHint.textContent = '请用手机返回键退出 App，重新打开后即可在连接页修改。';
      return;
    }
    if (el.connHint) el.connHint.textContent = '浏览器里请直接修改地址栏的地址与 token。';
  });
}

if (el.petToggle) {
  el.petToggle.addEventListener('click', () => {
    const native = nativeBridge();
    if (!native) return;
    const status = readPetStatus() || {};
    try {
      if (status.running) {
        native.stopOverlay();
        state.pendingOverlay = false;
      } else {
        // 先把当前地址回报给原生，避免「未配置」误报
        reportUrlToNative();
        // 优先走「进入立绘模式」：原生会开启悬浮窗并收起 App，
        // 否则 App 在前台会把悬浮窗盖住，看起来像没显示。
        if (typeof native.enterOverlayMode === 'function') {
          native.enterOverlayMode();
          state.pendingOverlay = false;
        } else {
          const started = native.startOverlay();
          state.pendingOverlay = started === false;
        }
      }
    } catch (error) {
      systemNote('操作失败：' + (error && error.message ? error.message : error));
    }
    setTimeout(renderPetPanel, 800);
    setTimeout(renderPetPanel, 2500);
  });
}

if (el.miniConfigButton) {
  el.miniConfigButton.addEventListener('click', () => {
    // 在 App 里点它会先进入桌面立绘模式，再由那边缩小 —— 否则没有悬浮窗可缩
    const native = nativeBridge();
    if (!native || typeof native.setBubbleMode !== 'function') return;
    const done = minimizeToBubble();
    if (!done && native.enterOverlayMode) {
      // App 内：先起悬浮窗，缩小的动作交给悬浮窗页面里的按钮
      native.enterOverlayMode();
      systemNote('已开启桌面立绘，点立绘上方的「缩小」即可变成小球。');
    }
  });
}

if (el.chatButton) {
  el.chatButton.addEventListener('click', () => {
    const native = nativeBridge();
    if (native && typeof native.enterOverlayMode === 'function') {
      // 收起 App，回到桌面看立绘；点立绘即可回来对话
      native.enterOverlayMode();
      return;
    }
    // 浏览器里没有悬浮窗，直接滚到对话区
    if (el.bubbles) el.bubbles.scrollTop = el.bubbles.scrollHeight;
    openConfig(false);
    if (el.text) el.text.focus();
  });
}

if (el.batteryButton) {
  el.batteryButton.addEventListener('click', () => {
    const native = nativeBridge();
    if (!native) return;
    try {
      native.requestBatteryExemption();
    } catch (error) {
      systemNote('打不开电池优化设置：' + (error && error.message ? error.message : error));
    }
    setTimeout(renderPetPanel, 1200);
    setTimeout(renderPetPanel, 3000);
  });
}

document.addEventListener('visibilitychange', () => {
  if (document.hidden) return;
  // 从系统权限页返回时，如果之前点了「开启桌面立绘」就自动补一次
  if (state.pendingOverlay) {
    const native = nativeBridge();
    const status = readPetStatus() || {};
    if (native && !status.running) {
      if (status.canDraw) {
        try {
          native.startOverlay();
        } catch (error) { /* 忽略 */ }
        state.pendingOverlay = false;
      }
      // 还没授权就先留着标记，等用户再点一次
    } else {
      state.pendingOverlay = false;
    }
  }
  if (state.configOpen) renderConfig();
});
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && state.dirtyPlayback && !state.playing && !state.busy) {
    state.dirtyPlayback = false;
    loadHistory(true);
  }
});

async function boot() {
  updateVoiceButton();
  loadDisplayPrefs();
  loadPortraitScale();
  attachPinchZoom();
  // 把当前地址回报给原生，作为悬浮窗配置的兜底（两个源不互通）
  installBridgeCallbacks();
  reportUrlToNative();
  // 悬浮窗模式：切换紧凑布局并接管软键盘
  applyOverlayMode();
  // 迷你图标模式：只剩一个圆形头像
  applyBubbleMode();
  // 再应用一次显示偏好。
  // 上一行之前的 loadDisplayPrefs() 跑在 applyOverlayMode() 之前，
  // 那时 body 上还没有 overlay-mode，随后的模式切换/布局都会重建样式状态；
  // 不在模式确定后重放一次，就会出现「浮动开关点了没反应」
  //（实测存储里 portraitFloat=true，body 上却还挂着 no-float）。
  loadDisplayPrefs();
  // 悬浮窗手势与内容宽度跟随
  if (document.body.classList.contains('overlay-mode')) {
    installOverlayGestures();
    installContentWidthSync();
    // 立绘上的轻点：点在人物本体上收放对话框
    installPointerHistory();
    installClickThrough();
    installMiniBar();
    // 点立绘/透明处即收起对话框
    installBubbleTapToHide();
    // 首次进来先显示最新一条
    syncMessageNav(true);
  }
  // 支持 ?config=1 直接展开配置页：方便大屏适配验证和分享「怎么配置」给别人看
  const params = new URLSearchParams(window.location.search);
  const wantConfig = params.get('config') === '1';
  try {
    await loadState();
    await loadHistory(true);
    if (state.voiceEnabled && state.autoplay) unlockAudio();
    renderConfig();
  } catch (error) {
    systemNote('初始化失败：' + (error && error.message ? error.message : error));
    setStatus('无法连接电脑端，请确认 Sakura 正在运行。');
    renderConfig();
  }
  if (wantConfig) openConfig(true);
}

boot();
