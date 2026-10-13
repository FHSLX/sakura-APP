'use strict';

const BOOT = window.__SAKURA__ || {};
const TOKEN = BOOT.token || '';

/**
 * 本次页面加载的代际会话 id（INT-01）。
 *
 * 由 static/page_session.js 在页面级引导位置统一生成，app.js 与
 * overlay_touch_region.js **共用同一个编号**：
 *   - 截图请求会带上它；原生只把结果送回「发起请求且会话仍匹配」的页面，
 *     WebView reload 之后旧会话的迟到结果会被丢弃，不会串到新页面或别的 WebView；
 *   - 两个模块不再各自随机生成，否则后登记的那个会覆盖先登记的会话，
 *     截图请求就会被原生当成「页面已刷新」而拒绝；
 *   - 启用/关闭穿透不刷新代际，只有导航/同 URL 刷新才会换新编号。
 *
 * 兜底：page_session.js 没能加载时退回「共享键」方案 —— 仍然保证 app.js 与
 * 穿透模块拿到同一个编号（谁先执行谁生成并写回 window 上的共享键），
 * 行为与旧版一致（仍然随机、仍然只在本次文档内有效），既不会因为缺模块
 * 就完全失去会话隔离，也不会退化成两个模块各随机一个而互相覆盖。
 */
function makeFallbackPageSession() {
  const KEY = '__sakuraSharedPageSessionId';
  const existing = typeof window !== 'undefined' ? window[KEY] : '';
  const id = (typeof existing === 'string' && existing)
    ? existing
    : 'ps-' + Date.now().toString(36) + '-' + Math.floor(Math.random() * 1e9).toString(36);
  if (typeof window !== 'undefined') window[KEY] = id;
  let registered = false;
  return {
    sessionId: id,
    register() {
      if (registered) return true;
      const native = nativeBridge();
      if (!native || typeof native.registerPageSession !== 'function') return false;
      try {
        native.registerPageSession(id);
        registered = true;
        return true;
      } catch (error) {
        return false;
      }
    },
    invalidate() {
      const native = nativeBridge();
      if (!native || typeof native.invalidatePageSession !== 'function') return false;
      try {
        native.invalidatePageSession(id);
        registered = false;
        return true;
      } catch (error) {
        return false;
      }
    },
  };
}

const PAGE_SESSION = (typeof window !== 'undefined' && window.SakuraPageSession)
  ? window.SakuraPageSession
  : makeFallbackPageSession();
const PAGE_SESSION_ID = PAGE_SESSION.sessionId;

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
  passThroughToggle: document.getElementById('passThroughToggle'),
  passThroughHint: document.getElementById('passThroughHint'),
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
  // 独立「拍照」入口：<input type="file" capture="environment">。
  // 与图库选择分开，原生才会收到 isCaptureEnabled() 并走系统相机。
  camera: document.getElementById('camera'),
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
  /**
   * 当前显示在页面上的立绘是否真的加载成功。
   *
   * 为什么需要：以前 setPortrait 在**开始加载前**就把 currentPortraitKey
   * 改成新 key，于是 A（慢）还没显示、B（快）已经显示时，旧请求 onload
   * 会把新图覆盖回旧图；失败时同 key 也因为「已经是当前 key」而不再重试。
   * 现在只有**最新一次加载成功**才提交 key，失败保留旧图并允许同 key 重试。
   */
  portraitReady: false,
  portraitFailed: '',
  tones: [],
  voiceEnabled: !!BOOT.tts,
  autoplay: !!BOOT.autoplay,
  audioUnlocked: false,
  busy: false,
  pending: [],
  playing: false,
  dirtyPlayback: false,
  lastText: '',
  /**
   * 语音任务代际。
   *
   * 静音 / 停止 / 切角色 / 页面销毁都会 +1；所有在途 TTS 与预取在真正播放
   * 前都要复核它。旧实现没有代际，静音后迟到的合成响应照样会 play()。
   */
  voiceEpoch: 0,
  /** key(角色+tone+文本) → {epoch, controller, promise}：有界预取缓存。 */
  ttsPrefetch: new Map(),
  /** 当前在途 TTS 请求数（含预取），用于限制并发。 */
  ttsInflight: 0,
  /** 去敏计数：请求数 / 预取数 / 复用数 / 取消数（报告与调试用）。 */
  ttsStats: { requests: 0, prefetches: 0, reused: 0, aborted: 0 },
  /**
   * 发送状态：函数级防重复。
   *
   * 只 disable 按钮是不够的 —— 回车连击、脚本调用都会绕过按钮。
   */
  sending: false,
  /**
   * 待发送记录 { text, file, requestId, chatDispatched }。
   *
   * 失败时用它恢复可编辑草稿；成功后才清空。
   */
  pendingSend: null,
  /** 会话内请求序号：用于区分同一任务迟到的响应。 */
  sendSeq: 0,
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
  /** 立绘是否上下浮动（无偏好时默认关） */
  portraitFloat: false,
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
  /**
   * 用户本次是否真的改过开关。
   *
   * 保存按钮只写「用户改过的」原生偏好，其余采纳原生当前值。否则一个停留在
   * 旧状态的 App/浮窗页面按一次保存，就会把别处新改的关闭偏好覆盖回开启。
   */
  floatDirty: false,
  passThroughDirty: false,
  /** 空对话状态提示只出现一次，避免每次点人物都刷屏。 */
  emptyDialogueNoted: false,
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
  // state 里保留**用户设置值**（滑块、持久化都用它），显示值另行夹取
  state.dialogX = offsetX;
  state.dialogY = offsetY;
  syncControl(el.dialogXRange, el.dialogXNumber, [el.dialogXValue], offsetX, '');
  syncControl(el.dialogYRange, el.dialogYNumber, [el.dialogYValue], offsetY, '');
  if (options.persist !== false) {
    try {
      localStorage.setItem(DIALOG_X_KEY, String(offsetX));
      localStorage.setItem(DIALOG_Y_KEY, String(offsetY));
    } catch (error) { /* 忽略 */ }
  }
  syncDialogOffset();
  updateSaveState();
}

/**
 * 对话框偏移的**显示值**：保留用户设置，但夹到卡片仍完整可见的范围。
 *
 * 真机「对话框左边被截断」的根因：旧实现把保存的 --pet-dialog-x 直接当
 * left 用。用户为了对旧版「margin-left 覆盖 auto 居中」的偏差，往往存了一个
 * 很大的负值；现在卡片恢复居中后，这个负值就把卡片推出窗口左边。
 *
 * 规则：
 *   - state.dialogX/dialogY（以及滑块、localStorage）保持用户设置，绝不清除；
 *   - 只夹 --pet-dialog-x/-y 的实际显示值；
 *   - 水平可用范围按「居中后左右各剩多少」算，垂直范围按卡片上下是否出界算。
 * 卡片未渲染（隐藏/自动隐藏）时不夹取，等重新显示时由 measure() 再夹。
 */
function syncDialogOffset() {
  const root = document.documentElement;
  const desiredX = state.dialogX || 0;
  const desiredY = state.dialogY || 0;
  let appliedX = desiredX;
  let appliedY = desiredY;
  const nav = el.msgNav;
  const measurable = !!(isOverlayPage() && nav && nav.offsetWidth
    && !nav.classList.contains('hidden')
    && !nav.classList.contains('auto-hidden'));
  if (measurable) {
    // 先归零量卡片的基础位置，再按可见区域夹取（量完立刻恢复为夹取后的值）
    root.style.setProperty('--pet-dialog-x', '0px');
    root.style.setProperty('--pet-dialog-y', '0px');
    const rect = nav.getBoundingClientRect();
    const vw = Math.max(0, Math.round(window.innerWidth || 0));
    const vh = Math.max(0, Math.round(window.innerHeight || 0));
    // 卡片居中：左右各余 (vw - w) / 2，偏移不能超过它
    const maxX = Math.max(0, Math.floor((vw - rect.width) / 2) - 1);
    // 三段正常流布局：偏移还必须避开立绘和输入栏，不能只检查屏幕边缘。
    const stageBottom = el.stage ? el.stage.getBoundingClientRect().bottom : 0;
    const composerTop = el.composer ? el.composer.getBoundingClientRect().top : vh;
    const maxUp = Math.max(0, Math.floor(rect.top - stageBottom));
    const maxDown = Math.max(0, Math.floor(Math.min(vh, composerTop) - rect.bottom));
    appliedX = Math.max(-maxX, Math.min(maxX, desiredX));
    appliedY = Math.max(-maxUp, Math.min(maxDown, desiredY));
  }
  state.dialogAppliedX = appliedX;
  state.dialogAppliedY = appliedY;
  root.style.setProperty('--pet-dialog-x', appliedX + 'px');
  root.style.setProperty('--pet-dialog-y', appliedY + 'px');
}

/** 立绘上下浮动开关（呼吸动画）。 */
function applyPortraitFloat(value, options = {}) {
  // Legacy preferences cannot restore the removed floating animation.
  state.portraitFloat = false;
  document.body.classList.add('no-float');
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
 * 登记本次页面加载的真实会话（RV-02 / INT-01）。
 *
 * 原生不再拿随机会话 id 和 URL 比较，而是按 (宿主 → 会话) 登记。
 * 登记只做一次、且与穿透模块共用同一个代际编号：重复调用是幂等的，
 * 启用/关闭穿透不会刷新会话，因此不会把在途截图请求作废。
 * 同 URL reload 会换新的代际编号；旧结果在主线程投递瞬间复核时会失效。
 * pagehide（含 reload / 关闭 / 离开）时主动注销，让在途结果立刻作废。
 */
function registerPageSession() {
  if (PAGE_SESSION && typeof PAGE_SESSION.register === 'function') {
    PAGE_SESSION.register();
  }
}

/**
 * 渲染可用性信号（FIX-BLANK-01）：告诉原生「现在页面里到底有没有可见内容」。
 *
 * 为什么必须由网页主动上报：
 *   原生 WebView 的 onPageFinished 在 HTTP 404/500 或错误页上也会触发，
 *   「文档加载完成」不等于「有可见立绘」。如果原生据此挂出完整透明窗口，
 *   就会留下用户看不到内容、却整块矩形拦点击的窗口（本轮要修的实际缺陷）。
 *   只有网页自己知道立绘是否真的解码显示，所以由网页上报：
 *     - bootOk：DOM 与桥就绪（此时还不允许挂完整窗口）；
 *     - portraitVisible：确认有可见且已解码的立绘（此时才允许）。
 *
 * 这些信号只带会话 id，不带 URL/token/聊天/图片内容；旧会话会被原生按代际丢弃。
 */
function notifyRenderSession(kind) {
  // 可选协作函数：旧页面/测试片段可能没有它们，typeof 守卫避免中断立绘流程
  if (typeof nativeBridge !== 'function') return false;
  const native = nativeBridge();
  if (!native) return false;
  const id = (typeof PAGE_SESSION_ID === 'string') ? PAGE_SESSION_ID : '';
  try {
    if (kind === 'bootOk' && typeof native.notifyOverlayBootOk === 'function') {
      native.notifyOverlayBootOk(id);
      return true;
    }
    if (kind === 'portraitVisible' && typeof native.notifyOverlayPortraitVisible === 'function') {
      native.notifyOverlayPortraitVisible(id);
      return true;
    }
    if (kind === 'bootFailed' && typeof native.notifyOverlayBootFailed === 'function') {
      native.notifyOverlayBootFailed(id);
      return true;
    }
  } catch (error) {
    // 桥不可用时静默：原生保持「没有可见内容」，不会挂出透明大窗
    return false;
  }
  return false;
}

/**
 * 只在确认有可见立绘时上报「可用」。
 *
 * 立绘是 <img>，仅把 src 赋值不代表解码成功；这里复核 complete/naturalWidth，
 * 避免「图片还没解码就允许原生挂完整窗口」。
 */
function notifyPortraitVisibleIfReady() {
  const portrait = el.portrait;
  const ready = !!(state.portraitReady
    && portrait
    && portrait.complete !== false
    && (portrait.naturalWidth === undefined || portrait.naturalWidth > 0));
  if (ready) {
    // 双保险：boot 就绪信号可能与立绘可见同帧，重复上报是幂等的
    notifyRenderSession('bootOk');
    notifyRenderSession('portraitVisible');
  }
  return ready;
}

function invalidatePageSession() {
  if (PAGE_SESSION && typeof PAGE_SESSION.invalidate === 'function') {
    PAGE_SESSION.invalidate();
    return;
  }
  const native = nativeBridge();
  if (!native || typeof native.invalidatePageSession !== 'function') return;
  try { native.invalidatePageSession(PAGE_SESSION_ID); } catch (error) { /* 桥不可用 */ }
}

if (typeof window !== 'undefined' && window.addEventListener) {
  window.addEventListener('pagehide', invalidatePageSession);
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
  if (!native) return;
  // 悬浮窗/小球页面以 ?mode=overlay|bubble 加载。它们的地址绝不能被当成
  //「用户当前连的地址」写回主配置 —— 否则旧立绘会把主 App 的新地址覆盖成旧地址。
  const mode = new URLSearchParams(window.location.search).get('mode');
  const overlayPage = mode === 'overlay' || mode === 'bubble';
  try {
    if (overlayPage) {
      if (typeof native.rememberOverlayUrl === 'function') {
        native.rememberOverlayUrl(window.location.href);
      }
      return;
    }
    if (typeof native.rememberUrl === 'function') {
      native.rememberUrl(window.location.href);
    }
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
  /*
   * 点非编辑控件时主动释放输入焦点。
   *
   * 为什么需要：悬浮窗里点开输入框后，原生窗口被临时改成可聚焦（否则软键盘
   * 弹不出来）。如果用户接着点了按钮/立绘/空白，而输入框自己不失焦，
   * 页面就会一直向原生上报「键盘打开」，窗口保持可聚焦 —— 用户看到的就是
   * 「焦点被对话框一直占着，别的界面点不动」。
   *
   * 这里只在「当前焦点在可编辑控件、而这次按下的目标不是可编辑控件」时
   * 才 blur，绝不在输入框之间互相切换时抢焦点，也不与新弹出的输入请求竞争。
   */
  const releaseOnPointer = (event) => releaseOverlayInputFocus(event && event.target);
  document.addEventListener('touchstart', releaseOnPointer, { passive: true, capture: true });
  document.addEventListener('mousedown', releaseOnPointer, { passive: true, capture: true });
  // 无障碍激活和程序化 click 不一定有 touchstart/mousedown；同样交还编辑焦点。
  document.addEventListener('click', releaseOnPointer, { capture: true });
  return true;
}

/** 该元素是否是可编辑控件（输入框/文本域/下拉框/富文本）。 */
function isEditableElement(node) {
  if (!node || !node.tagName) return false;
  const tag = String(node.tagName).toUpperCase();
  if (tag === 'TEXTAREA' || tag === 'INPUT' || tag === 'SELECT') return true;
  return !!node.isContentEditable;
}

/**
 * 点击非编辑控件时释放输入焦点（悬浮窗专用）。
 *
 * 允许 blur 的条件很窄：当前焦点确实在可编辑控件上，且本次按下的目标不是
 * 可编辑控件。满足时 blur 会触发 focusout，原生据此把窗口恢复成不可聚焦并
 * 收起软键盘。
 */
function releaseOverlayInputFocus(target) {
  if (isEditableElement(target)) return false;
  const active = (typeof document !== 'undefined') ? document.activeElement : null;
  if (!active || active === document.body) return false;
  if (!isEditableElement(active)) return false;
  if (active === target) return false;
  if (typeof active.blur === 'function') {
    try {
      active.blur();
    } catch (error) {
      return false;
    }
    return true;
  }
  return false;
}

function setOverlayKeyboard(focused) {
  const native = nativeBridge();
  if (!native || typeof native.setKeyboardOpen !== 'function') return;
  try {
    native.setKeyboardOpen(!!focused);
  } catch (error) { /* 桥接不可用时忽略 */ }
}

/* ---------- 精确局部穿透（API33+ AttachedSurfaceControl）----------
 *
 * Android 13 起有公开的 AttachedSurfaceControl.setTouchableRegion(Region)：
 * 前端预计算「立绘**外轮廓** + 可见控件」的输入区域，原生转成 surface 本地
 * 设备像素后一次设定。外轮廓以外的一次 DOWN/UP 由系统直接路由给下层 ——
 * 这才是真正的局部穿透；不是 touchstart 后切 FLAG_NOT_TOUCHABLE 那种假穿透。
 *
 * TS-03（用户授权）：外轮廓 = 每一行最左→最右不透明像素之间的一条带，
 * 因此**外部**透明区穿透，发缝/内部空隙不穿透（内部保持可点）。
 *
 * 模块本身在 overlay_touch_region.js；这里只做界面开关、状态提示和通知。
 */

function touchRegionModule() {
  return (typeof window !== 'undefined' && window.SakuraTouchRegion)
    ? window.SakuraTouchRegion : null;
}

function touchRegionSupported() {
  const native = nativeBridge();
  if (!native || typeof native.isTouchRegionSupported !== 'function') return false;
  try { return !!native.isTouchRegionSupported(); } catch (error) { return false; }
}

function isBubblePage() {
  const body = typeof document !== 'undefined' ? document.body : null;
  return !!(body && body.classList && body.classList.contains('bubble-mode'));
}

/** 穿透状态码 → 面向用户的一句话。 */
function passThroughMessage(status) {
  const reason = status && status.reason ? status.reason : 'idle';
  const map = {
    'applied': '已开启：立绘外轮廓以外的透明处会把轻点交给下层，人物与控件照常可点。',
    'applying': '正在应用轮廓…',
    'unchanged': '已开启：外轮廓以外的透明处会把轻点交给下层。',
    'idle': '正在准备轮廓…',
    'disabled': '未开启。开启后立绘外轮廓以外的透明处可直接点到下层桌面图标。',
    'api-unsupported': '当前系统不支持精确局部穿透（需要 Android 13 及以上）。',
    'bridge-missing': '原生桥不可用，无法开启穿透。',
    'bridge-error': '设定输入区域失败，已自动回到整窗可交互。',
    'alpha-scan-failed': '读取立绘透明通道失败，已回到整窗可交互。',
    'portrait-failed': '立绘加载失败，已回到整窗可交互。',
    'empty-region': '没有算出可交互区域，已回到整窗可交互。',
    'contour-too-complex': '轮廓过于复杂，为保证人物不被误穿透，已回到整窗可交互。',
    'image-too-large': '立绘尺寸超出上限，已回到整窗可交互。',
    'payload-too-large': '轮廓数据过大，已回到整窗可交互。',
    'payload-rejected': '轮廓数据被原生拒绝，已回到整窗可交互。',
    'native-error': '原生设定输入区域失败，正在重试；当前整窗可交互。',
    'native-disabled': '正在重新开启原生穿透…',
    'surface-not-ready': '窗口暂时不可用，正在重试；当前整窗可交互。',
    'session-not-registered': '页面会话尚未登记，正在重试。',
    'stale': '已有更新的一版轮廓，等待生效。',
    'bubble-not-ready': '小球布局尚未就绪，稍后重试。',
    'encode-failed': '轮廓编码失败，已回到整窗可交互。',
  };
  return map[reason] || ('穿透状态：' + reason);
}

/** 原生穿透状态（alpha 安全上限、简化级别等，模块不读这些）。 */
function readTouchRegionStatus() {
  const native = nativeBridge();
  if (!native || typeof native.touchRegionStatus !== 'function') return null;
  try { return JSON.parse(native.touchRegionStatus()); } catch (error) { return null; }
}

/** 本机是否具备精确穿透能力：优先用原生偏好快照里的 supported。 */
function passThroughSupported() {
  const capability = nativeDisplayPreferences();
  if (capability && capability.passThrough
      && typeof capability.passThrough.supported === 'boolean') {
    return !!capability.passThrough.supported;
  }
  return touchRegionSupported();
}

/**
 * 统一的穿透状态。
 *
 * 悬浮窗/小球页面由 SakuraTouchRegion 拥有几何与 ACK；App 页面没有安装模块，
 * 只读原生持久偏好（同一份状态，不依赖 localStorage 同步）。
 */
function passThroughStatus() {
  const mod = touchRegionModule();
  const installed = !!(mod && typeof mod.isInstalled === 'function' && mod.isInstalled());
  if (installed && typeof mod.status === 'function') {
    const status = mod.status();
    const native = readTouchRegionStatus();
    if (native) {
      status.alpha = native.alpha;
      status.windowAlpha = native.windowAlpha !== undefined ? native.windowAlpha : native.alpha;
      status.originalAlpha = native.originalAlpha;
      status.alphaReduced = !!native.alphaReduced;
      status.alphaSafe = native.alphaSafe;
      status.nativeActive = !!native.active;
      status.restoreResult = native.restoreResult || '';
      if (!status.level && typeof native.level === 'number') status.level = native.level;
      if (!status.tolerance && typeof native.tolerance === 'number') status.tolerance = native.tolerance;
    }
    return status;
  }
  const capability = nativeDisplayPreferences();
  if (!capability) return null;
  const pt = capability.passThrough || {};
  return {
    supported: !!pt.supported,
    desired: !!pt.value,
    hasPreference: !!pt.has,
    active: false,
    reason: pt.value ? 'idle' : 'disabled',
    approximated: false,
    rects: 0,
    segments: 0,
    level: 0,
    tolerance: 0,
  };
}

function renderPassThrough() {
  const supported = passThroughSupported();
  const inOverlay = isOverlayPage() || isBubblePage();
  const status = passThroughStatus();
  if (el.passThroughToggle) {
    // App 主界面也允许管理桌面立绘穿透：只持久化意图并经安全通道同步浮窗，
    // 不在 App 页裁剪区域。旧系统（API<=32）明确禁用并提示。
    el.passThroughToggle.disabled = !supported;
    el.passThroughToggle.checked = !!(status && status.desired);
  }
  if (!el.passThroughHint) return;
  if (!supported) {
    el.passThroughHint.textContent =
      '当前系统为 Android 12 或更低，不支持精确局部穿透（需要 Android 13 及以上）。'
      + '旧系统仍可正常拖动、缩放、点击人物与控件，不会用整窗穿透冒充。';
    return;
  }
  if (!inOverlay) {
    const saved = status && status.desired;
    el.passThroughHint.textContent = (saved ? '已保存：' : '')
      + '桌面立绘的外轮廓穿透。在这里改会保存到本机；浮窗运行时立即应用，'
      + '浮窗未运行则下次开启时生效。Android 13+ 会把立绘外轮廓以外的透明处一次轻点'
      + '交给下层；头发缝隙等内部空隙不穿透（保持人物可点）。App 页面本身不裁剪区域。';
    return;
  }
  let text = passThroughMessage(status);
  if (status && status.desired && status.level > 0) {
    const tolerance = typeof status.tolerance === 'number' ? status.tolerance : 0;
    text += '（轮廓按 ' + status.level + ' 级聚合近似，边缘容差约 '
      + tolerance.toFixed(1) + ' CSS 像素；已如实标注，不再宣称最多 1 设备像素）';
  }
  if (status && status.desired && status.alphaReduced) {
    text += ' 受 Android 12+ 触摸安全上限限制，整窗会轻微变淡；关闭穿透即恢复原不透明度。';
  }
  if (status && status.desired && status.restoreResult === 'degraded') {
    text += ' 本机型/ROM 已永久降级精确穿透，窗口不透明度已自动恢复正常（不会白变淡）。';
  }
  el.passThroughHint.textContent = text;
}

/** 立绘/控件几何变化后通知一次，模块会去抖提交。 */
function notifyTouchRegion(reason) {
  const mod = touchRegionModule();
  if (!mod || typeof mod.notify !== 'function') return;
  try { mod.notify(reason); } catch (error) { /* 忽略 */ }
}

/**
 * 开关透明处穿透。
 *
 * 两类宿主明确分开：
 *   - 悬浮窗/小球页面：由 SakuraTouchRegion 计算轮廓并提交区域（它会经原生
 *     偏好通道持久化用户意图）；
 *   - App / 普通页面：**不提交区域**，只写原生持久偏好；运行中的浮窗经
 *     同进程安全通道收到新值并重算轮廓。
 */
function setPassThroughEnabled(enabled, options = {}) {
  const mod = touchRegionModule();
  const installed = !!(mod && typeof mod.isInstalled === 'function' && mod.isInstalled());
  const inOverlay = isOverlayPage() || isBubblePage();
  if (installed && inOverlay) {
    if (!enabled) {
      mod.setEnabled(false, options);
      renderPassThrough();
      return true;
    }
    if (!passThroughSupported()) {
      systemNote('当前系统不支持精确局部穿透（需要 Android 13 及以上），旧系统保持整窗交互。');
      renderPassThrough();
      return false;
    }
    const ok = mod.setEnabled(true, options);
    renderPassThrough();
    return ok;
  }
  // App / 普通页面：只持久化意图，不裁剪区域。
  if (options.persist === false) return true;
  if (enabled && !passThroughSupported()) {
    systemNote('当前系统为 Android 12 或更低，不支持精确局部穿透，保持整窗交互。');
    renderPassThrough();
    return false;
  }
  const result = writeNativeDisplayPreferences({ passThrough: !!enabled });
  if (!result || result.ok !== true) {
    const reason = result && result.error ? result.error : 'unavailable';
    systemNote('保存穿透偏好失败（' + reason + '），未生效。');
    renderPassThrough();
    return false;
  }
  renderPassThrough();
  return true;
}

/**
 * 原生推送的偏好落地（浮窗页面）。
 *
 * 由 OverlayService 通过 evaluateJavascript 调用；只应用、不再写回原生，
 * 因此不会形成 App ↔ 浮窗的回环。
 */
function applyNativeDisplayPreferences(prefs) {
  if (!prefs || typeof prefs !== 'object') return false;
  let applied = false;
  if (typeof prefs.float === 'boolean') {
    // 原生推来的值是权威的：不算「用户改动」，也不应被随后的保存再写回去
    state.floatDirty = false;
    applyPortraitFloat(prefs.float, { persist: false });
    applied = true;
  }
  if (typeof prefs.passThrough === 'boolean') {
    state.passThroughDirty = false;
    setPassThroughEnabled(prefs.passThrough, { persist: false });
    applied = true;
  }
  renderPassThrough();
  return applied;
}

if (typeof window !== 'undefined') {
  window.SakuraDisplayPreferences = { apply: applyNativeDisplayPreferences };
}

/* ---------- 悬浮窗手势：按住拖动 / 静置1.5秒开设置 ---------- */

/**
 * 拖动与开设置都绑在立绘上。
 *
 * 规则（长按是同一个起手式）：
 *   - 按住后移动超过阈值  → 拖动窗口（发给原生 moveBy）
 *   - 按住不动约1.5秒     → 打开配置页
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
  const HOLD_MS = 1500;        // 静置约1.5秒开设置，与小球一致

  let holding = false;
  let dragging = false;
  let longPressed = false;     // 长按已触发开设置，抬手时就不再算轻点
  let multiTouch = false;      // 本次手势是否出现了多指（让位穿透时会出现）
  /*
   * 本次手势是否真的由一根手指开始。
   *
   * 为什么需要：多指起手时 touchstart 会直接 return（不设 holding）。
   * 如果抬手时只看「没拖动、没长按」，纯多指手势也会被判成一次轻点
   * （坐标还是上一次留下的），表现为「点一下只应开关一次，却多触发一次」。
   */
  let gestureStarted = false;
  let lastX = 0;
  let lastY = 0;
  let holdTimer = 0;
  let startX = 0;
  let startY = 0;
  /*
   * 轻点采样专用的**视口坐标**起点。
   *
   * 为什么不能复用 startX/startY：那两个是 screenX/screenY（屏幕绝对坐标），
   * 只有拖动需要它们（窗口一移动，client 参照就跟着动，会形成正反馈回路）。
   * 而 portraitAlphaAt 是按 getBoundingClientRect() 的 **client 坐标**采样的。
   * 上一版把 screen 坐标喂给 alpha 采样：浮窗一旦被用户拖离 (0,0)，
   * 采样点就偏出去，真机表现就是「点人物不显示对话」。
   *
   * 所以这里在按下那一刻单独记一份 client 起点，两套坐标互不干扰：
   *   拖动  -> startX/startY（screen）
   *   轻点  -> startClientX/startClientY（client）
   */
  let startClientX = 0;
  let startClientY = 0;
  // 绝对定位的「抓取点」：按下那一刻的窗口位置（CSS 像素）与手指位置
  let dragOriginWinX = 0;
  let dragOriginWinY = 0;
  let dragOriginX = 0;
  let dragOriginY = 0;

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

  /**
   * 触摸点的**屏幕绝对坐标**。
   *
   * 优先用 screenX/screenY —— 它们**不随窗口移动**，是拖动唯一正确的参照。
   *
   * 为什么不能用 clientX/clientY：视口长在窗口里面，我们一移动窗口，
   * 视口参照就跟着动，同一个物理手指位置读出的 client 坐标就变了；
   * 再据此算出新的窗口位置，形成正反馈回路。实测拖动中
   * client 与 screen 的差值有 165/252 种取值、单帧位移达 47px/6ms
   *（人手做不到），表现就是立绘一直抽搐。
   *
   * 个别环境可能不给这个字段，那时退回 client 坐标：会退化成有回路的行为，
   * 但总比完全不能拖好。
   */
  function touchScreenX(touch) {
    const v = touch.screenX;
    return (typeof v === 'number' && v > 0) ? v : touch.clientX;
  }

  function touchScreenY(touch) {
    const v = touch.screenY;
    return (typeof v === 'number' && v > 0) ? v : touch.clientY;
  }

  /**
   * 把窗口移到绝对位置（CSS 像素，窗口左上角）。
   *
   * 拖动一律走这里，不要用增量累加 —— 增量会把手指抖动积分成窗口漂移。
   */
  function moveTo(x, y) {
    try {
      const n = nativeBridge();
      // 桥上的方法名是 moveTo —— 原生 Host 接口里叫 setWindowPositionPx，
      // 桥接方法做了改名。用错名字会**静默失效**（实测踩过）。
      if (n && typeof n.moveTo === 'function') {
        n.moveTo(x, y);
      }
    } catch (error) { /* 忽略 */ }
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
    gestureStarted = true;
    dragging = false;
    longPressed = false;   // 每次按下都重置
    startX = touchScreenX(touch);
    startY = touchScreenY(touch);
    // 轻点采样用视口坐标（见 startClientX 的说明）；拖动仍用上面的 screen 坐标
    startClientX = (typeof touch.clientX === 'number') ? touch.clientX : startX;
    startClientY = (typeof touch.clientY === 'number') ? touch.clientY : startY;
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
    /*
     * 记录「抓取点」——绝对定位的基准，**必须在按下的这一刻记**。
     *
     * 为什么必须在 touchstart：
     *   一开始写成「越过拖动阈值那一帧才记」，但那一帧窗口已经被移动过了，
     *   于是整个拖动带一个固定偏移（实测 -30px）。
     *   按下时就记，偏移为 0。
     *
     * 为什么必须绝对定位（不要增量累加）：
     *   手指本身有约 ±15px 的高频抖动，增量累加会把它**积分**进去，
     *   窗口在 368px 跨度里持续来回漂 —— 实测方向反转 697 次，就是不停抽搐。
     *
     * 单位：overlayLayout 返回设备像素，窗口定位要 CSS 像素，按 dpr 换算。
     * 读不到就标 NaN，拖动时退回增量（读窗口位置绝不能抛异常，
     * 抛出去会中断整个 touchmove，拖动会彻底失效 —— 实测踩过）。
     */
    /*
     * 抓取点用 **screenX/screenY**（屏幕绝对坐标），不用 clientX/clientY。
     *
     * 原因是一个反馈回路：
     *   我们移动窗口 → 视口（长在窗口里）跟着动 → 同一个物理手指位置读出的
     *   clientX/Y 就变了 → 我们据此再算目标位置 → 又移动窗口 → 循环放大。
     * 实测 client 与 screen 的差值在拖动中变化 165/252 种取值，
     * 单帧位移高达 47px/6ms（人手不可能），表现就是抽搐。
     *
     * screenX/screenY 是屏幕绝对坐标，**不随窗口移动**，回路就被切断了。
     */
    dragOriginX = touchScreenX(touch);
    dragOriginY = touchScreenY(touch);
    try {
      const L = JSON.parse(native.overlayLayout());
      const dpr = window.devicePixelRatio || 1;
      dragOriginWinX = L.x / dpr;
      dragOriginWinY = L.y / dpr;
    } catch (error) {
      dragOriginWinX = NaN;
      dragOriginWinY = NaN;
    }
    holdTimer = setTimeout(() => {
      if (!dragging) {
        // 静置约1.5秒：开设置
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
      // 阈值判断同样用 screen 坐标，与后面的位移计算保持同一坐标系
      const moved = Math.hypot(touchScreenX(touch) - startX, touchScreenY(touch) - startY);
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
      /*
       * 记录「抓取点」——绝对定位的基准。
       *
       * 为什么必须绝对定位：
       *   原先用增量累加（每帧 layoutParams.x += dx）。手指本身有约 ±15px 的
       *   高频抖动（实测触摸流在 130-167 之间来回），累加会把这抖动**积分**进去，
       *   窗口就在 368px 的跨度里持续来回漂 —— 实测方向反转 697 次，
       *   看起来就是不停抽搐。
       *
       *   绝对定位下窗口位置 = 按下时的窗口位置 + (手指现在的位置 − 手指按下的位置)。
       *   抖动只是让结果在小范围内来回，**不会累积**，而且手指回到原点窗口就回原点。
       *
       * 基准（dragOrigin*）已经在 touchstart 时记好了，这里直接用。
       */
      lastX = touch.clientX;
      lastY = touch.clientY;
    }
    if (dragging) {
      if (isFinite(dragOriginWinX) && isFinite(dragOriginWinY)) {
        moveTo(dragOriginWinX + (touchScreenX(touch) - dragOriginX),
               dragOriginWinY + (touchScreenY(touch) - dragOriginY));
      } else {
        // 兜底：拿不到基准位置时退回增量
        move(touch.clientX - lastX, touch.clientY - lastY);
      }
      lastX = touch.clientX;
      lastY = touch.clientY;
    }
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
    const isTap = !cancelled && !dragging && !longPressed && gestureStarted;
    holding = false;
    gestureStarted = false;
    if (dragging) {
      setNativeDragging(false);   // 松手后恢复正常的 resize 校正
    }
    dragging = false;
    document.body.classList.remove('dragging');
    // 松手即恢复原样：半透明/放大都撤掉，桌面让位也收回
    clearPressFeedback();
    clearHold();
    // 位移已经逐次直接下发了，这里不需要再补发
    if (isTap) onPortraitTap(startClientX, startClientY, target);
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
    gestureStarted = true;
    dragging = false;
    longPressed = false;
    startX = event.clientX;
    startY = event.clientY;
    // 鼠标没有 screen/client 之分，两份都填成同一值，轻点路径保持一致
    startClientX = event.clientX;
    startClientY = event.clientY;
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
  /*
   * 缩放只能应用一次。
   *
   * 悬浮窗里 --pet-avail-h（stage 高度）已经按「适配尺寸 × 缩放」预留，
   * transform 若再乘一次 scale 就是双重缩放：
   *   - scale>1：可视高度 = 预留高度 × scale > 预留高度，头顶被 stage 裁掉；
   *   - scale<1：可视高度远小于预留高度，立绘上方空出一大片
   *     —— 真机「立绘裁切 / 与对话框间距异常」的根因。
   * 所以只有「布局没有包含缩放」的场景（App 页面）才在 transform 里乘 scale。
   * 用 --pet-avail-h 是否已写入判断，避免首帧（measure 还没跑）尺寸跳变。
   */
  const layoutIncludesScale = centeredByMargin
    && !!document.documentElement.style.getPropertyValue('--pet-avail-h');
  const visualScale = layoutIncludesScale ? factor : (base * factor);
  wrap.style.transform = shift + 'scale(' + visualScale.toFixed(4) + ')';
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
  // 拖动中不再给放大反馈：拖动本身已经有窗口跟随作为反馈，
  // 而缩放和拖动叠加会互相干扰（表现为抽搐）
  if (document.body.classList.contains('dragging')) {
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
 *
 * <p>「首次无本地历史」这一支要特别小心：恢复历史是异步的，恢复过程中
 * {@code addBubble/showBubble} 已经把卡片变可见了。旧实现在 await 之后
 * 又按「已可见」判断去 hideBubble，结果刚恢复就被自己隐藏（真机表现就是
 * 点人物只闪一下、仍旧不显示上次对话）。这里在异步之前记录用户意图序列号，
 * 恢复成功后**显示并立即返回**，不再走切换分支；期间用户又点过或角色切换过
 * 则直接放弃，避免晚到的恢复强行显示。
 */
async function toggleBubbleByTap() {
  if (!el.msgNav) return;
  if (state.dialogueRestorePending) {
    state.dialogueRestoreSeq = (state.dialogueRestoreSeq || 0) + 1;
    state.dialogueRestorePending = 0;
    return;
  }
  const rows = Array.from(el.bubbles.querySelectorAll('.bubbleRow'));
  if (latestContentIndex(rows) < 0) {
    // 记录「这一次点击的意图」：用户要打开。序列号用于丢弃晚到的恢复结果。
    const token = (state.dialogueRestoreSeq || 0) + 1;
    state.dialogueRestoreSeq = token;
    state.dialogueRestorePending = token;
    let restored;
    try {
      restored = await restoreLastDialogue();
    } finally {
      if (state.dialogueRestorePending === token) state.dialogueRestorePending = 0;
    }
    if (token !== (state.dialogueRestoreSeq || 0)) return;   // 期间用户又点过
    if (!restored) {
      if (!state.emptyDialogueNoted) {
        state.emptyDialogueNoted = true;
        systemNote('还没有对话记录，发一条消息试试。');
      }
      return;
    }
    // 恢复分支：显示后立即返回，绝不再因为它「已经可见」而隐藏
    showBubble();
    return;
  }
  if (el.msgNav.classList.contains('hidden')) {
    syncMessageNav(true);
    showBubble();
    return;
  }
  const hidden = el.msgNav.classList.contains('auto-hidden')
    || el.msgNav.classList.contains('auto-hidden-now');
  if (hidden) {
    showBubble();
    clearTimeout(bubbleHideTimer);   // 用户主动显示，别马上又被自动隐藏收走
  } else {
    hideBubble(true);
  }
}

/**
 * 恢复「上一次对话」。
 *
 * 关闭后重开、或气泡列表为空时，用户点人物应重新显示当前角色最近的有效对话。
 * 只用当前 `state.characterId` 拉取，避免串到其他角色；await 期间角色被切换、
 * 或用户又点了人物（序列号变化）时不强行显示。
 *
 * @return true 表示确实有可显示的对话
 */
async function restoreLastDialogue() {
  const requestedCharacter = state.characterId || '';
  const token = state.dialogueRestoreSeq || 0;
  let rows = Array.from(el.bubbles.querySelectorAll('.bubbleRow'));
  if (latestContentIndex(rows) < 0 && requestedCharacter) {
    await loadHistory(true, { restoreSeq: token, suppressAutoShow: true });
    // 晚到的响应：用户已经再次点击 / 角色已切换 → 不显示
    if (token !== (state.dialogueRestoreSeq || 0)) return false;
    if ((state.characterId || '') !== requestedCharacter) return false;
    rows = Array.from(el.bubbles.querySelectorAll('.bubbleRow'));
  }
  if (latestContentIndex(rows) < 0) return false;
  if (el.msgNav) el.msgNav.classList.remove('hidden');
  syncMessageNav(true);
  return true;
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

/**
 * 待发图片大小上限，与后端/原生对齐：
 * 后端 MAX_IMAGE_BYTES=8MiB、MAX_REQUEST_BYTES=16MiB。
 * 6MiB 二进制 → base64 约 8MiB，加 JSON 仍远低于 16MiB 请求体上限。
 */
const MAX_PENDING_IMAGE_BYTES = 6 * 1024 * 1024;
/** 截图 data URL 字符上限（6MiB 二进制 base64 约 8.4M 字符）。 */
const MAX_SCREENSHOT_CHARS = 9 * 1024 * 1024;

/** 释放待发附件持有的 object URL（选择图片的缩略图），避免大图泄漏。 */
function releaseMediaInfo(info) {
  if (info && info.objectUrl) {
    try { URL.revokeObjectURL(info.objectUrl); } catch (error) { /* 忽略 */ }
    info.objectUrl = '';
  }
}

function clearMediaInfo() {
  releaseMediaInfo(state.pendingMedia);
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

/** 选择图片的类型/大小校验；返回错误文案（空串 = 通过）。 */
function validatePickedFile(file) {
  if (!file) return '没有选中图片。';
  const type = String(file.type || '').toLowerCase();
  if (type && type.indexOf('image/') !== 0) return '只能选择图片文件。';
  if (file.size && file.size > MAX_PENDING_IMAGE_BYTES) {
    return '图片太大（上限 ' + Math.round(MAX_PENDING_IMAGE_BYTES / (1024 * 1024)) + ' MB）。';
  }
  return '';
}

/**
 * 待发图片可能来自两个 input：拍照（#camera）与图库（#image）。
 *
 * 优先用调用方指定的那个（change 事件是谁触发的），否则按「谁真的有文件」
 * 回退，最后才用图库 input。这样旧调用 `onPickedFile()` 的语义不变。
 */
function mediaFileInputs(preferred) {
  const list = [];
  const push = (node) => { if (node && list.indexOf(node) < 0) list.push(node); };
  push(preferred);
  push(el.camera);
  push(el.image);
  return list;
}

/** 当前有选中文件的输入框（没有则返回 null）。 */
function activeMediaInput(preferred) {
  const inputs = mediaFileInputs(preferred);
  for (const input of inputs) {
    if (input.files && input.files[0]) return input;
  }
  return null;
}

/** 清空拍照/图库两个 input，避免提交时重复带上旧文件。 */
function clearMediaInputs() {
  mediaFileInputs(null).forEach((input) => {
    try { input.value = ''; } catch (error) { /* 只读 input 忽略 */ }
    if (input.classList) input.classList.remove('selected');
  });
}

/**
 * 用户选好图片（图库或直接拍照）：只做本地预览，不上传、不发送。
 *
 * 与截图共用同一个 `state.pendingMedia`，两者互相替换（旧附件的 object URL 立即释放）。
 * 用户取消选择时**保留**已有附件，不做破坏性清理。
 */
function onPickedFile(input) {
  const source = activeMediaInput(input) || input || el.image;
  const file = source && source.files && source.files[0];
  if (!file) return;
  const problem = validatePickedFile(file);
  if (problem) {
    systemNote(problem);
    if (source) source.value = '';
    return;
  }
  const previous = state.pendingMedia;
  const info = { kind: 'file', name: describePickedFile(file), thumb: '', file: file, dataUrl: '' };
  // 用 objectURL 做缩略图，选完就能看到是哪张
  try {
    info.objectUrl = URL.createObjectURL(file);
    info.thumb = info.objectUrl;
  } catch (error) { /* 不支持就只显示文件名 */ }
  state.pendingMedia = info;
  releaseMediaInfo(previous);
  showMediaInfo(info);
  setStatus('图片已就绪，点发送才会发出去');
  setTimeout(() => setStatus(''), 2400);
}

/**
 * 截图本地预览（用户明确选择：先预览，点发送才上传）。
 *
 * 成功拿到截图只保存成待发附件并显示缩略图，**绝不**调用 /api/upload 或 /api/chat。
 * 再次截图会替换旧预览，不累积大图；格式/大小无效时提示且不动原附件。
 */
function previewScreenshot(dataUrl) {
  if (!/^data:(image\/[a-z0-9.+-]+);base64,/i.test(dataUrl)) {
    systemNote('截图格式无法识别，已忽略。');
    return false;
  }
  if (dataUrl.length > MAX_SCREENSHOT_CHARS) {
    systemNote('截图过大，已忽略（不影响已选图片）。');
    return false;
  }
  const previous = state.pendingMedia;
  const info = {
    kind: 'shot',
    name: '手机截屏（待发送，点发送才会发出去）',
    thumb: dataUrl,
    dataUrl: dataUrl,
    file: null,
  };
  state.pendingMedia = info;
  releaseMediaInfo(previous);
  showMediaInfo(info);
  return true;
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
 *   拖动          → 移动窗口（长按1.5秒则是打开设置）
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
     * 页面不可见（切到别的 App / 息屏 / 悬浮窗被隐藏）时不做这件事。
     *
     * 旧实现每 400ms 无条件量一次：即使没人看得见，也照样读
     * getBoundingClientRect / offsetHeight（强制布局）再比较窗口尺寸。
     * 恢复可见时由 visibilitychange 立刻补一次，不必靠定时器兜。
     */
    if (document.hidden) return;
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
    // 卡片宽高刚更新：把用户保存的对话框偏移夹到仍完整可见的范围
    if (typeof syncDialogOffset === 'function') syncDialogOffset();

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
    // 倍率大于一时原生会夹住窗口，但网页不会自动夹住 stage。
    // 在请求之前按同一宽高预算限制图片，保持宽高比并为卡片/输入栏保留空间。
    const fittedScale = Math.min(base * scale, maxPortraitW / naturalW, maxPortraitH / naturalH);
    const portraitW = Math.max(1, Math.round(naturalW * fittedScale));
    const portraitH = Math.max(1, Math.round(naturalH * fittedScale));

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
    // 卡片和输入栏的目标宽度来自屏幕；窗口必须使用相同的宽度预算。
    // 不能因缩小立绘而把已经按屏幕排版的卡片挤进另一尺寸的窗口。
    const wantW = Math.round(screenW);
    const wantH = Math.round(portraitH + chrome);
    syncDialogOffset();
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

  let measureFrame = 0;
  window.addEventListener('pagehide', () => {
    if (measureFrame) cancelAnimationFrame(measureFrame);
    measureFrame = 0;
  }, { once: true });
  const scheduleMeasure = () => {
    if (document.hidden || measureFrame) return;
    measureFrame = requestAnimationFrame(() => {
      measureFrame = 0;
      measure();
    });
  };
  if (portrait.complete) measure();
  portrait.addEventListener('load', scheduleMeasure);
  window.addEventListener('resize', scheduleMeasure);
  window.addEventListener('touchend', scheduleMeasure);
  window.addEventListener('mouseup', scheduleMeasure);
  if (typeof ResizeObserver === 'function') {
    const resizeObserver = new ResizeObserver(scheduleMeasure);
    for (const target of [el.msgNav, el.msgBody, el.composer]) {
      if (target) resizeObserver.observe(target);
    }
    window.addEventListener('pagehide', () => resizeObserver.disconnect(), { once: true });
  }
  // 恢复可见时立刻补一次（隐藏期间定时器会被 measure 自己的 early-return 跳过）
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) scheduleMeasure();
  });
  // 暴露给历史导航：卡片内容变了要重新量高度
  relayoutOverlay = scheduleMeasure;
}

/* ---------- 手机截屏（让学姐看到你正在看什么）---------- */

let screenshotWaiting = false;

/**
 * 请求一次手机截屏，预览后由用户手动发送。
 *
 * 链路：原生 MediaProjection 截图 → 原生按最长边 1280 缩放 + JPEG q70 压缩
 * → 这里拿到 data URL → 走已有的 /api/upload → 随消息一起给模型。
 *
 * 为什么在客户端压缩：1080×2460 的原始截图 2–4 MB，压完约 150–300 KB，
 * 局域网和 4G 下都轻松，电脑端也不需要改任何东西。
 */
function requestScreenshot() {
  const native = nativeBridge();
  if (!native || (typeof native.requestScreenshot !== 'function'
      && typeof native.requestScreenshotWithSession !== 'function')) {
    systemNote('截图需要在手机 App 内使用。');
    return;
  }
  if (screenshotWaiting) return;
  screenshotWaiting = true;
  setStatus('正在截屏…');
  try {
    // 优先带 pageSessionId：结果只送回本次页面会话
    if (typeof native.requestScreenshotWithSession === 'function') {
      native.requestScreenshotWithSession(PAGE_SESSION_ID);
    } else {
      native.requestScreenshot();
    }
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
  /*
   * 用户明确选择：先本地预览，点「发送」才上传。
   * 这里**绝不**调用 /api/upload 或 /api/chat，也不自动发送。
   */
  if (previewScreenshot(dataUrl)) {
    setStatus('截图已就绪，点发送发给角色');
    setTimeout(() => setStatus(''), 2600);
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
 * 并且会处理长按。小球是独立的小窗口，语义不同 ——
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
  applyPortraitFloat(readBool(FLOAT_KEY, false), { persist: false });
  applyBubbleHideDelay(stored(BUBBLE_HIDE_DELAY_KEY, BUBBLE_HIDE_DEFAULT), { persist: false });
  setPressFeedbackEnabled(readBool(PRESS_FEEDBACK_KEY, PRESS_FEEDBACK_DEFAULT), { persist: false });
  // 浮动偏好历史上只存本机 localStorage：只有原生有**明确**偏好时才覆盖回放，
  // 否则升级用户已有的本机选择会被默认值抹掉。
  const nativePrefs = nativeDisplayPreferences();
  if (nativePrefs && nativePrefs.float && nativePrefs.float.has === true
      && typeof nativePrefs.float.value === 'boolean') {
    applyPortraitFloat(!!nativePrefs.float.value, { persist: false });
  }
  // 刚回放完偏好，还不算「用户改动」
  state.floatDirty = false;
  state.passThroughDirty = false;
  updateSaveState._loading = false;
  setSaveState('设置会自动保存', false);
}

/**
 * 读取原生持久偏好快照（App / 浮窗 / 小球共用）。
 *
 * 返回 {float:{has,value}, passThrough:{has,value,supported}} 或 null。
 * 刻意**不**依赖两个 WebView 的 localStorage 自动同步。
 */
function nativeDisplayPreferences() {
  const native = nativeBridge();
  if (!native || typeof native.displayPreferences !== 'function') return null;
  try {
    return JSON.parse(native.displayPreferences());
  } catch (error) {
    return null;
  }
}

/** 写入原生持久偏好；返回回执对象或 null（无桥/异常/畸形）。 */
function writeNativeDisplayPreferences(payload) {
  const native = nativeBridge();
  if (!native || typeof native.setDisplayPreferences !== 'function') return null;
  let raw;
  try {
    raw = native.setDisplayPreferences(JSON.stringify(payload));
  } catch (error) {
    return { ok: false, error: 'bridge-error' };
  }
  try {
    return JSON.parse(raw);
  } catch (error) {
    return { ok: false, error: 'bad-reply' };
  }
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

/**
 * 读取「可见控件当前值」并立即套用（含尚未 blur 的数字框）。
 *
 * 用户的真实抱怨是「改了数字框、没点别处，直接点保存 → 没生效」：
 * 旧实现只写 state（state 要等 change/blur 才更新）。这里直接从 DOM 取当前值，
 * 用与滑块相同的 apply 函数套用，再统一持久化。
 */
function commitVisibleDisplayValues() {
  const pickNumber = (numberEl, rangeEl, fallback) => {
    if (numberEl && numberEl.value !== '' && isFinite(Number(numberEl.value))) {
      return Number(numberEl.value);
    }
    if (rangeEl && rangeEl.value !== '' && isFinite(Number(rangeEl.value))) {
      return Number(rangeEl.value);
    }
    return fallback;
  };
  const scalePercent = pickNumber(el.scaleNumber, el.scaleRange,
    Math.round((state.portraitScale || 1) * 100));
  applyPortraitScale(scalePercent / 100, { persist: false });
  applyPortraitOffset(pickNumber(el.portraitYNumber, el.portraitYRange, state.portraitY || 0),
    { persist: false });
  applyFontScale(pickNumber(el.fontNumber, el.fontRange, state.fontScale || 100),
    { persist: false });
  applyDialogShift(
    pickNumber(el.dialogXNumber, el.dialogXRange, state.dialogX || 0),
    pickNumber(el.dialogYNumber, el.dialogYRange, state.dialogY || 0),
    { persist: false }
  );
  applyBubbleHideDelay(
    pickNumber(el.bubbleHideNumber, el.bubbleHideRange, state.bubbleHideSec || 0),
    { persist: false });
  if (el.showOriginalToggle) applyShowOriginal(el.showOriginalToggle.checked);
  if (el.showTranslationToggle) applyShowTranslation(el.showTranslationToggle.checked);
  if (el.autoScrollToggle) applyAutoScroll(el.autoScrollToggle.checked);
  if (el.pressFeedbackToggle) {
    setPressFeedbackEnabled(el.pressFeedbackToggle.checked, { persist: false });
  }
}

/** 把可见显示值写进本机存储并**回读校验**；返回失败的 key 列表。 */
function persistDisplayValuesLocal() {
  const writes = [
    [PORTRAIT_SCALE_KEY, String(state.portraitScale || 1)],
    [PORTRAIT_Y_KEY, String(state.portraitY || 0)],
    [FONT_SCALE_KEY, String(state.fontScale || 100)],
    [DIALOG_X_KEY, String(state.dialogX || 0)],
    [DIALOG_Y_KEY, String(state.dialogY || 0)],
    [FLOAT_KEY, state.portraitFloat ? 'true' : 'false'],
    [SHOW_ORIGINAL_KEY, state.showOriginal ? 'true' : 'false'],
    [SHOW_TRANSLATION_KEY, state.showTranslation ? 'true' : 'false'],
    [AUTO_SCROLL_KEY, state.autoScroll ? 'true' : 'false'],
    [PRESS_FEEDBACK_KEY, state.pressFeedback ? 'true' : 'false'],
    [BUBBLE_HIDE_DELAY_KEY,
      String(Math.max(0, Math.min(60, Math.round(Number(state.bubbleHideSec) || 0))))],
  ];
  const failed = [];
  writes.forEach(([key, value]) => {
    try {
      localStorage.setItem(key, value);
      if (localStorage.getItem(key) !== value) failed.push(key);
    } catch (error) {
      failed.push(key);
    }
  });
  return failed;
}

/**
 * 把「浮动 + 穿透」偏好同步到原生（App/浮窗/小球共用一份），并回读校验。
 *
 * 不依赖 localStorage 自动同步：原生偏好是事实来源，运行中的浮窗经同进程
 * 安全通道收到新值后自己重算轮廓。
 */
function syncNativeDisplayPreferences() {
  const native = nativeBridge();
  if (!native || typeof native.setDisplayPreferences !== 'function') {
    return { ok: true, skipped: true };
  }
  const capability = nativeDisplayPreferences();
  const payload = {};
  /*
   * 只写「用户本次真的改过」的原生偏好；其余采纳原生当前值。
   *
   * 为什么必须这样：App 与浮窗是两个 WebView。一个停留在旧状态的页面按保存，
   * 如果无条件把自己控件里的值写回原生，就会把另一个页面（或上次会话）刚保存的
   * 「关闭穿透」覆盖回开启 —— 正是真机「关闭后重开仍半透明」的同步缺陷。
   */
  if (state.floatDirty) {
    payload.float = !!state.portraitFloat;
  } else if (capability && capability.float && capability.float.has === true
      && typeof capability.float.value === 'boolean') {
    applyPortraitFloat(!!capability.float.value, { persist: false });
  }
  const toggleUsable = !!(el.passThroughToggle && !el.passThroughToggle.disabled);
  if (toggleUsable) {
    if (state.passThroughDirty) {
      payload.passThrough = !!el.passThroughToggle.checked;
    } else if (capability && capability.passThrough
        && typeof capability.passThrough.value === 'boolean') {
      // 没改过就跟随原生，别用旧页面的勾选状态覆盖
      el.passThroughToggle.checked = !!capability.passThrough.value;
      setPassThroughEnabled(!!capability.passThrough.value, { persist: false });
    }
  }
  if (!Object.keys(payload).length) {
    return { ok: true, unchanged: true };
  }
  const result = writeNativeDisplayPreferences(payload);
  if (!result || result.ok !== true) {
    return { ok: false, error: (result && result.error) ? result.error : 'unavailable' };
  }
  const prefs = nativeDisplayPreferences();
  if (payload.float !== undefined && prefs && prefs.float
      && !!prefs.float.value !== !!state.portraitFloat) {
    return { ok: false, error: 'readback-mismatch' };
  }
  if (payload.passThrough !== undefined && prefs && prefs.passThrough
      && typeof prefs.passThrough.value === 'boolean'
      && !!prefs.passThrough.value !== !!payload.passThrough) {
    return { ok: false, error: 'readback-mismatch' };
  }
  state.floatDirty = false;
  state.passThroughDirty = false;
  return { ok: true };
}

/**
 * 保存按钮：保存**可见控件当前值**（含尚未 blur 的数字框、浮动与穿透偏好）。
 *
 * 分别处理：
 *   - 本机显示设置 → localStorage（回读校验，失败如实报「本机存储不可用」）；
 *   - 桌面立绘偏好（浮动/穿透）→ 原生持久状态（浮窗运行时立即同步）；
 *   - 电脑端设置（自动朗读/语音合成）→ 由「保存到电脑端」按钮单独提交，
 *     失败原因由它自己的提示显示，不在这里假装成功。
 */
function saveAllDisplaySettings() {
  commitVisibleDisplayValues();
  const failed = persistDisplayValuesLocal();
  if (failed.length) {
    setSaveState('保存失败：本机存储不可用（' + failed.length + ' 项未写入）', false);
    return;
  }
  const native = syncNativeDisplayPreferences();
  if (!native.ok) {
    setSaveState('本机显示设置已保存；桌面立绘偏好保存失败：' + native.error, false);
    return;
  }
  setSaveState(native.skipped
    ? '已保存到本机 ✓（电脑端设置请点「保存到电脑端」）'
    : '已保存到本机与桌面立绘偏好 ✓', true);
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
  renderPassThrough();
}

function isInsideApp() {
  return !!nativeBridge();
}

function renderPortraitAccessibilityGuide() {
  const guide = document.getElementById('portraitAccessibilityGuide');
  const native = window.SakuraNative;
  const available = !!(native && typeof native.portraitAccessibilityConnected === 'function'
    && typeof native.requestPortraitAccessibilitySettings === 'function' && passThroughSupported());
  if (!guide) return;
  guide.classList.toggle('hidden', !available);
  if (!available) return;
  let connected = false;
  try { connected = !!native.portraitAccessibilityConnected(); } catch (_) { }
  const button = document.getElementById('portraitAccessibilitySettings');
  if (button) button.classList.toggle('hidden', connected);
  const text = document.getElementById('portraitAccessibilityGuideText');
  if (text) text.textContent = connected
    ? '不透明穿透服务已连接。开启穿透后，人物和对话框保持原不透明度，外部透明处可点击桌面。'
    : '要让人物和对话框不变淡，同时让外部透明处可点击桌面，请在系统无障碍服务中开启“Sakura 立绘不透明穿透”。安装 App 不会自动开启这项授权。服务不读取界面、不模拟点击、不截屏。';
}

function installPortraitAccessibilityGuide() {
  const native = window.SakuraNative;
  if (!native || typeof native.portraitAccessibilityConnected !== 'function'
      || typeof native.requestPortraitAccessibilitySettings !== 'function' || !passThroughSupported()) return;
  const button = document.getElementById('portraitAccessibilitySettings');
  if (button) button.addEventListener('click', () => {
    try { native.requestPortraitAccessibilitySettings(); }
    catch (_) { setStatus('请在系统无障碍中开启 Sakura 立绘不透明穿透。'); }
  });
  const key = 'sakura.portraitAccessibilityGuide.v1';
  const dismiss = document.getElementById('portraitAccessibilityGuideDismiss');
  let seen = false;
  try { seen = localStorage.getItem(key) === '1'; } catch (_) { }
  if (dismiss) {
    dismiss.classList.toggle('hidden', seen);
    dismiss.addEventListener('click', () => {
      try { localStorage.setItem(key, '1'); } catch (_) { }
      dismiss.classList.add('hidden');
      openConfig(false);
    });
  }
  renderPortraitAccessibilityGuide();
  if (!seen) openConfig(true);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) renderPortraitAccessibilityGuide();
  });
}

function openConfig(open) {
  state.configOpen = !!open;
  if (!el.configPanel) return;
  el.configPanel.classList.toggle('hidden', !state.configOpen);
  if (el.settingsButton) el.settingsButton.classList.toggle('on', state.configOpen);
  if (state.configOpen) {
    renderPortraitAccessibilityGuide();
    renderConfig();
    loadCharacters();
  }
  // 配置浮层出现/消失会遮住或让出立绘，可交互区域随之变化
  notifyTouchRegion('config-panel');
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

/**
 * 立绘加载代次：latest-wins。
 *
 * 只要发起新的一次加载，代次就加一；只有「最新代次」的 onload 才允许
 * 提交 currentPortraitKey 与 el.portrait.src。旧请求（慢 A）回来时因为代次
 * 已经过期，直接丢弃，不会把新图（快 B）覆盖回去。
 */
let portraitGeneration = 0;

function setPortrait(tone) {
  const key = resolvePortraitKey(tone);
  if (!key) return;
  const url = state.portraitByKey.get(key);
  if (!url) return;
  // 已经成功显示同一张：不重复加载（但上次失败时允许同 key 重试）
  if (key === state.currentPortraitKey && state.portraitReady) return;
  const generation = ++portraitGeneration;
  el.portrait.classList.add('switching');
  const next = new Image();
  next.onload = () => {
    if (generation !== portraitGeneration) return;   // 陈旧请求，丢弃
    el.portrait.src = url;
    // 只有最新一次**加载成功**才提交 key
    state.currentPortraitKey = key;
    state.portraitReady = true;
    state.portraitFailed = '';
    window.__sakuraPortraitKey = key;
    requestAnimationFrame(() => el.portrait.classList.remove('switching'));
    // 换图后 alpha 轮廓变了，通知穿透模块重新扫描
    notifyTouchRegion('portrait-loaded');
    if (typeof relayoutOverlay === 'function') relayoutOverlay();
    // 立绘已确认可见：这是原生唯一允许挂完整悬浮窗的依据（FIX-BLANK-01）
    if (typeof notifyPortraitVisibleIfReady === 'function') notifyPortraitVisibleIfReady();
  };
  next.onerror = () => {
    if (generation !== portraitGeneration) return;
    // 保留当前可见的旧图，不提前提交 key；同一个 key 下次调用会重新加载
    state.portraitReady = false;
    state.portraitFailed = key;
    el.portrait.classList.remove('switching');
    notifyTouchRegion('portrait-failed');
    // 立绘失败但没有可见旧图时，必须让原生知道「现在没有可见内容」，
    // 否则窗口会停在完整透明形态。有可见旧图时保持挂载（页面仍有内容）。
    if (!el.portrait || !el.portrait.complete || el.portrait.naturalWidth === 0) {
      if (typeof notifyRenderSession === 'function') notifyRenderSession('bootFailed');
    }
  };
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
  if (!options.suppressAutoShow) scheduleBubbleAutoHide();
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
/**
 * 一条气泡行是否有可显示内容。
 *
 * 「正在输入」占位气泡（typing）没有 dataset.primary，只有三个点；
 * 系统提示可能为空。把它们当成「最近一条对话」会显示成空卡片
 *（真机表现就是对话框一直是空的/「待输入」）。
 */
function rowHasContent(row) {
  if (!row) return false;
  const primary = String(row.dataset.primary || '').trim();
  const secondary = String(row.dataset.secondary || '').trim();
  return !!(primary || secondary);
}

/** 最近一条**有内容**的气泡行下标；没有就返回 -1。 */
function latestContentIndex(rows) {
  for (let index = rows.length - 1; index >= 0; index -= 1) {
    if (rowHasContent(rows[index])) return index;
  }
  return -1;
}

function syncMessageNav(showLatest = true) {
  const rows = Array.from(el.bubbles.querySelectorAll('.bubbleRow'));
  state.messageCount = rows.length;
  const latest = latestContentIndex(rows);
  if (!rows.length) {
    state.messageIndex = 0;
  } else if (showLatest || state.messageIndex >= rows.length) {
    // 首次/重开优先定位到最近一条**有内容**的对话，而不是空的占位行
    state.messageIndex = latest >= 0 ? latest : rows.length - 1;
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
  let row = total ? rows[state.messageIndex] : null;
  // 当前定位到的是空占位（正在输入/空系统行）时，回退到最近一条有内容的对话，
  // 绝不让占位符顶掉真实对话。
  if (total && !rowHasContent(row)) {
    const fallback = latestContentIndex(rows);
    if (fallback >= 0) {
      state.messageIndex = fallback;
      row = rows[fallback];
    }
  }
  if (el.msgBody) {
    const role = row ? (row.dataset.role || '') : '';
    const primary = row ? (row.dataset.primary || '') : '';
    const secondary = row ? (row.dataset.secondary || '') : '';
    const contentKey = JSON.stringify([role, primary, secondary]);
    // Periodic layout measurements must not rebuild unchanged dialogue DOM.
    if (el.msgBody._contentKey !== contentKey) {
      el.msgBody._contentKey = contentKey;
      el.msgBody.innerHTML = '';
      el.msgBody.className = 'msgBody ' + role;
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
      el.msgBody.scrollTop = 0;
    }
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
  // 卡片显隐/高度变化会改变可交互控件矩形，通知穿透模块重算
  if (!options.skipRelayout) notifyTouchRegion('message-nav');
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
  if (immediate) {
    state.dialogueRestoreSeq = (state.dialogueRestoreSeq || 0) + 1;
    state.dialogueRestorePending = 0;
  }
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
  /** 人物本体的透明判定阈值，与 installClickThrough 保持一致。 */
  const PORTRAIT_ALPHA_MIN = 16;
  window.addEventListener('pointerdown', (event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    // 点在这些交互区域里不收起
    if (target.closest(KEEP)) return;
    /*
     * 人物本体的「收/放」由 touchend 的单一所有者（onPortraitTap →
     * toggleBubbleByTap）处理。pointerdown 属于同一次手势的**更早阶段**，
     * 如果这里先 hideBubble，touchend 再 toggle，同一次点击的状态就被预改写了
     *（表现为点人物时卡片闪一下就没了）。所以只在透明处/非人物区域收起。
     */
    if (portraitAlphaAt(event.clientX, event.clientY) >= PORTRAIT_ALPHA_MIN) return;
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

/** 同时进行的 TTS 请求上限：1 段在播 + 最多 2 段预取。 */
const TTS_PREFETCH_MAX = 2;

function ensureAudio() {
  if (!audio) {
    audio = new Audio();
    audio.preload = 'auto';
    audio.addEventListener('play', () => {
      state.playing = true;
      el.portrait.classList.add('speaking');
    });
    /*
     * 这里**不再**挂全局 ended/error 去 drainQueue。
     *
     * 旧实现让共享 player 的全局回调先启动下一段，再运行上一段的 once 清理，
     * 两段会抢同一个 player；stopVoice 也无法只清掉某一代的监听。
     * 现在每一段自己在 playSegment 里挂 ended/error（带 finished 幂等标记），
     * 结束清理与下一段启动只有一个所有者、只发生一次。
     */
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
      // 用户手势解锁成功后：先用已准备好的当前音频（不重新 POST 合成），
      // 没有准备结果才按队列继续。
      if (typeof playPreparedAudio === 'function' && playPreparedAudio()) return;
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

/** 语音任务代际：静音/停止/切角色/页面销毁都会 +1，让在途结果作废。 */
function bumpVoiceEpoch() {
  state.voiceEpoch = (state.voiceEpoch || 0) + 1;
  return state.voiceEpoch;
}

function voiceEpoch() {
  return state.voiceEpoch || 0;
}

/** 相同角色 + tone + 文本视为同一段，可复用预取/服务端缓存。 */
function ttsKey(segment) {
  return (segment.characterId || '') + '\u0000' + (segment.tone || '')
    + '\u0000' + (segment.rawText || '');
}

/** 发起一次 TTS 请求；signal 用于静音/停止时取消等待。 */
function ttsRequest(segment, signal) {
  return fetch(api('/api/tts'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Sakura-Remote-Token': TOKEN },
    body: JSON.stringify({
      token: TOKEN,
      character_id: segment.characterId,
      text: segment.rawText,
      tone: segment.tone,
    }),
    signal: signal,
  }).then(async (response) => {
    if (!response.ok) {
      let message = '语音合成失败';
      try {
        const data = await response.json();
        if (data && data.error) message = data.error;
      } catch (error) { /* 保持默认文案 */ }
      throw new Error(message);
    }
    return response.blob();
  });
}

/** 所有在途 TTS 任务的登记表（当前段 + 预取）；stopVoice 用它统一取消。 */
function ttsTaskRegistry() {
  if (!state.ttsTasks || typeof state.ttsTasks.set !== 'function') {
    state.ttsTasks = new Map();
  }
  return state.ttsTasks;
}

/** 去敏计数（报告/调试用）；state 没带就惰性建。 */
function voiceStats() {
  if (!state.ttsStats || typeof state.ttsStats !== 'object') {
    state.ttsStats = { requests: 0, prefetches: 0, reused: 0, aborted: 0 };
  }
  return state.ttsStats;
}

/**
 * 发起一次 TTS 并登记为**可取消任务**。
 *
 * 每个任务有自己的 id/epoch/AbortController；结果包成
 * {ok, blob} / {ok:false, error}，绝不抛出。任务结算只注销自己，
 * 因此 stopVoice 清表之后，旧任务的完成回调不会改动新任务的任何计数。
 */
function startTts(segment, epoch) {
  voiceStats().requests += 1;
  const id = (state.voiceTaskSeq || 0) + 1;
  state.voiceTaskSeq = id;
  const task = {
    id: id,
    epoch: epoch,
    key: ttsKey(segment),
    segment: segment,
    controller: null,
    promise: null,
    cleanup: null,
  };
  if (typeof AbortController === 'function') {
    task.controller = new AbortController();
  }
  ttsTaskRegistry().set(id, task);
  task.promise = ttsRequest(segment, task.controller ? task.controller.signal : undefined)
    .then((blob) => ({ ok: true, blob: blob }))
    .catch((error) => ({ ok: false, error: error }))
    .then((result) => {
      // 只注销自己；stopVoice 已清表时这是无操作，不会动新任务
      ttsTaskRegistry().delete(id);
      return result;
    });
  return task;
}

/** 取消一个任务：注销 → abort → 执行它自己的清理（幂等）。 */
function cancelVoiceTask(task) {
  if (!task) return;
  ttsTaskRegistry().delete(task.id);
  if (state.ttsPrefetch && state.ttsPrefetch.get
      && state.ttsPrefetch.get(task.key) === task) {
    state.ttsPrefetch.delete(task.key);
  }
  if (task.controller) {
    try {
      task.controller.abort();
      voiceStats().aborted += 1;
    } catch (error) { /* 忽略 */ }
  }
  const cleanup = task.cleanup;
  task.cleanup = null;
  if (typeof cleanup === 'function') cleanup();
}

/**
 * 有界预取后续语音。
 *
 * 只在**当前段真的开始播放之后**调用：首段优先，预取绝不挤占它；
 * 最多预取 TTS_PREFETCH_MAX 段（current 不计入 prefetch 上限）。
 */
function prefetchUpcoming(epoch) {
  if (epoch !== voiceEpoch()) return;
  if (!state.voiceEnabled || !state.audioUnlocked) return;
  if (!state.ttsPrefetch || typeof state.ttsPrefetch.set !== 'function') {
    state.ttsPrefetch = new Map();
  }
  const limit = Math.min(TTS_PREFETCH_MAX, state.pending.length);
  for (let index = 0; index < limit; index += 1) {
    if (state.ttsPrefetch.size >= TTS_PREFETCH_MAX) break;
    const segment = state.pending[index];
    if (!segment || !segment.rawText) continue;
    const key = ttsKey(segment);
    if (state.ttsPrefetch.has(key)) continue;
    voiceStats().prefetches += 1;
    state.ttsPrefetch.set(key, startTts(segment, epoch));
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
  playSegment(next, voiceEpoch());
}

async function playSegment(segment, epoch) {
  if (epoch !== voiceEpoch()) return;
  if (!state.audioUnlocked) {
    // 没解锁：保留待播段，等用户手势后再播，不在这里重复 POST
    state.pending.unshift(segment);
    showHintOnce();
    return;
  }
  if (!state.ttsPrefetch || typeof state.ttsPrefetch.get !== 'function') {
    state.ttsPrefetch = new Map();
  }
  // 优先复用预取；否则发起新任务。任务 id 是取消/归属的唯一凭据。
  // 预取任务完成后会从 ttsTasks 注销，但仍留在 ttsPrefetch 里可复用，
  // 所以只按 epoch 判断（被取消的预取已从 ttsPrefetch 移除）。
  const key = ttsKey(segment);
  let task = state.ttsPrefetch.get(key);
  if (task && task.epoch === epoch) {
    state.ttsPrefetch.delete(key);
    voiceStats().reused += 1;
  } else {
    task = startTts(segment, epoch);
  }
  // 认领为当前任务所有者：只有它能改 busy/playing/队列
  state.voiceTask = task;
  state.busy = true;
  setStatus(state.displayName + ' 正在说话…');
  const player = ensureAudio();
  let url = '';
  let finished = false;
  /** 任务自己的清理：幂等，先拆监听再清 player，绝不自动 drain。 */
  const cleanup = () => {
    if (finished) return;
    finished = true;
    if (task.cleanup === cleanup) task.cleanup = null;
    player.removeEventListener('ended', finish);
    player.removeEventListener('error', finish);
    if (segment.bubble) {
      segment.bubble.classList.remove('speaking');
      // 音频提前结束（或出错）时，别让文字停在半截
      finishBubbleText(segment.bubble);
    }
    if (url) {
      URL.revokeObjectURL(url);
      url = '';
    }
    if (player === audio) {
      try { player.pause(); } catch (error) { /* 忽略 */ }
      try { player.removeAttribute('src'); } catch (error) { /* 忽略 */ }
      try { player.load(); } catch (error) { /* 忽略 */ }
    }
    if (state.voiceTask === task) state.voiceTask = null;
    if (epoch === voiceEpoch()) {
      state.playing = false;
      if (el.portrait) el.portrait.classList.remove('speaking');
    }
  };
  /** 正常结束/出错：清理一次，且只有仍是当前所有者才继续下一句。 */
  const finish = () => {
    if (finished) return;
    const owner = state.voiceTask === task && epoch === voiceEpoch();
    cleanup();
    if (owner) drainQueue();
  };
  task.cleanup = cleanup;
  try {
    const result = await task.promise;
    // 旧任务复核：绝不碰新任务的 busy/队列/player
    if (epoch !== voiceEpoch() || state.voiceTask !== task) {
      return;
    }
    if (!result || !result.ok) {
      throw (result && result.error) || new Error('语音合成失败');
    }
    url = URL.createObjectURL(result.blob);
    segment.bubble.classList.add('speaking');
    setPortrait(segment.tone);
    player.src = url;
    /*
     * 逐字显示：等到这条语音真的要出声了才开始打字。
     *
     * 时长优先取 blob 的实际时长；某些容器格式（如部分 WAV）在
     * duration 上会报 Infinity 或 NaN，那就按字数估算 —— revealBubbleText
     * 里会退回 TYPE_FALLBACK_MS 的节奏，不会卡住。
     */
    let audioSeconds = result.blob && isFinite(result.blob.duration) && result.blob.duration > 0
      ? result.blob.duration : 0;
    try {
      await player.play();
    } catch (error) {
      // 旧任务的 play 拒绝：只清理自己，不影响新任务
      if (epoch !== voiceEpoch() || state.voiceTask !== task) {
        cleanup();
        return;
      }
      /*
       * 只有「需要用户手势才能出声」这一种失败才回到可点提示；
       * 同时保存已准备好的音频（blob + objectURL），用户再点一次时直接播放，
       * 不重新 POST 合成同一段。
       */
      state.audioUnlocked = false;
      state.busy = false;
      state.playing = false;
      state.voiceTask = null;
      if (task.cleanup === cleanup) task.cleanup = null;
      state.preparedUnlock = { segment: segment, blob: result.blob, url: url };
      url = '';   // 所有权交给 preparedUnlock
      if (segment.bubble) segment.bubble.classList.remove('speaking');
      revealBubbleText(segment.bubble, 0);
      showHintOnce();
      return;
    }
    if (epoch !== voiceEpoch() || state.voiceTask !== task) {
      cleanup();
      return;
    }
    if (!audioSeconds && isFinite(player.duration) && player.duration > 0) {
      audioSeconds = player.duration;
    }
    revealBubbleText(segment.bubble, audioSeconds);
    state.busy = false;
    player.addEventListener('ended', finish);
    player.addEventListener('error', finish);
    setStatus('');
    // 当前段已开始播放：预取后续（首段优先，不挤占）
    prefetchUpcoming(epoch);
  } catch (error) {
    if (epoch !== voiceEpoch() || state.voiceTask !== task) {
      cleanup();
      return;
    }
    state.busy = false;
    systemNote(error && error.message ? error.message : '语音请求失败');
    cleanup();
    // 失败句仍显示文字并继续后续句
    drainQueue();
  }
}

/**
 * 用户手势解锁成功后，先播放**已准备好**的当前音频，不重新 POST 合成。
 *
 * @return true 表示确实消费了一份准备结果
 */
function playPreparedAudio() {
  const prepared = state.preparedUnlock;
  if (!prepared) return false;
  state.preparedUnlock = null;
  const player = ensureAudio();
  const epoch = voiceEpoch();
  const segment = prepared.segment;
  let url = prepared.url || '';
  let finished = false;
  const task = {
    id: -1, epoch: epoch, key: ttsKey(segment), segment: segment,
    controller: null, promise: null, cleanup: null,
  };
  state.voiceTask = task;
  state.busy = true;
  const finish = () => {
    if (finished) return;
    finished = true;
    player.removeEventListener('ended', finish);
    player.removeEventListener('error', finish);
    if (url) { URL.revokeObjectURL(url); url = ''; }
    if (segment.bubble) {
      segment.bubble.classList.remove('speaking');
      finishBubbleText(segment.bubble);
    }
    if (state.voiceTask === task) state.voiceTask = null;
    state.busy = false;
    state.playing = false;
    if (el.portrait) el.portrait.classList.remove('speaking');
    drainQueue();
  };
  task.cleanup = () => {
    if (finished) return;
    finished = true;
    player.removeEventListener('ended', finish);
    player.removeEventListener('error', finish);
    if (url) { URL.revokeObjectURL(url); url = ''; }
    if (state.voiceTask === task) state.voiceTask = null;
  };
  segment.bubble.classList.add('speaking');
  setPortrait(segment.tone);
  player.src = url;
  player.addEventListener('ended', finish);
  player.addEventListener('error', finish);
  const attempt = player.play();
  if (attempt && typeof attempt.then === 'function') {
    attempt.then(() => {
      state.busy = false;
      revealBubbleText(segment.bubble,
        prepared.blob && prepared.blob.duration ? prepared.blob.duration : 0);
    }).catch(() => {
      state.busy = false;
      task.cleanup();
      // 还是放不出来：保留这份准备结果，等下一次用户手势
      state.preparedUnlock = { segment: segment, blob: prepared.blob, url: url };
      showHintOnce();
    });
  }
  return true;
}

function stopVoice() {
  // 换代：所有在途 await 的代际复核都会失败，绝不改新任务状态
  bumpVoiceEpoch();
  // 先清理当前任务的 URL/监听（不 drain），再取消所有在途与预取
  const current = state.voiceTask;
  state.voiceTask = null;
  cancelVoiceTask(current);
  const tasks = Array.from(ttsTaskRegistry().values());
  ttsTaskRegistry().clear();
  tasks.forEach((task) => cancelVoiceTask(task));
  if (state.ttsPrefetch && typeof state.ttsPrefetch.clear === 'function') {
    state.ttsPrefetch.clear();
  }
  const prepared = state.preparedUnlock;
  state.preparedUnlock = null;
  if (prepared && prepared.url) {
    try { URL.revokeObjectURL(prepared.url); } catch (error) { /* 忽略 */ }
  }
  if (state.ttsInflight !== undefined) state.ttsInflight = 0;
  // 再把挂起的逐字动画收尾，最后清队列（顺序反了会遍历到空数组）
  state.pending.forEach(function (seg) { if (seg.bubble) finishBubbleText(seg.bubble); });
  state.pending.length = 0;
  state.busy = false;
  state.playing = false;
  if (audio) {
    try { audio.pause(); } catch (error) { /* 忽略 */ }
    try { audio.removeAttribute('src'); } catch (error) { /* 忽略 */ }
    try { audio.load(); } catch (error) { /* 个别实现没有 load */ }
  }
  if (el.portrait) el.portrait.classList.remove('speaking');
  // 正在播放的那条气泡也要停掉「说话中」样式（音频已 pause）
  if (el.bubbles && el.bubbles.querySelectorAll) {
    Array.prototype.forEach.call(
      el.bubbles.querySelectorAll('.bubble.speaking'),
      function (node) { node.classList.remove('speaking'); }
    );
  }
  // 注意：停止后**不** drainQueue —— 用户明确停止，不该自动播下一句
}

/* ---------- 状态与历史 ---------- */

async function loadState() {
  const data = await fetchJson('/api/state');
  const nextCharacter = data.characterId || '';
  // 切角色：立刻停掉上一角色的语音并取消在途合成，迟到音频不得出声
  if (state.characterId && nextCharacter && nextCharacter !== state.characterId) {
    stopVoice();
  }
  state.characterId = nextCharacter;
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
  const initialKey = state.portraitByKey.has(state.defaultPortraitKey)
    ? state.defaultPortraitKey
    : (state.portraitByKey.keys().next().value || '');
  const initial = initialKey ? state.portraitByKey.get(initialKey) : '';
  if (initial) {
    // 首次进入直接指派 src：同步提交 key 并作废此前任何在途加载代次
    portraitGeneration += 1;
    el.portrait.src = initial;
    state.currentPortraitKey = initialKey;
    state.portraitReady = true;
    state.portraitFailed = '';
    window.__sakuraPortraitKey = initialKey;
    // 初始图也要等真正解码成功才允许原生挂完整窗口：
    // 这里可能是缓存命中（onload 立即触发），也可能是首次下载。
    if (typeof el.portrait.addEventListener === 'function') {
      el.portrait.addEventListener('load', () => notifyPortraitVisibleIfReady(), { once: true });
    }
  } else {
    state.currentPortraitKey = '';
    state.portraitReady = false;
  }
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

function renderHistory(items, options = {}) {
  el.bubbles.innerHTML = '';
  // 记录这批历史属于哪个角色：切换角色/旧请求迟到时不允许串显示
  if (el.bubbles.dataset) el.bubbles.dataset.characterId = state.characterId || '';
  let added = 0;
  items.forEach((item) => {
    if (!item) return;
    if (item.role === 'user') {
      if (String(item.content || '').trim()) {
        addBubble('user', item.content || '', { suppressAutoShow: !!options.suppressAutoShow });
        added += 1;
      }
    } else if (item.role === 'assistant') {
      const raw = String(item.raw_content || '').trim();
      const shown = String(item.content || '').trim();
      if (!raw && !shown) return;   // 空占位不渲染，避免顶掉真实对话
      addBubble('assistant', raw || shown, {
        secondary: raw ? shown : '', suppressAutoShow: !!options.suppressAutoShow,
      });
      added += 1;
    }
  });
  // 历史为空/全是空占位时保持空状态，而不是留一张空卡片
  if (!added) syncMessageNav(true);
  scrollBubbles();
}

async function loadHistory(silent, options = {}) {
  if (!state.characterId) return;
  const requestedCharacter = state.characterId;
  const requestSeq = (state.historyRequestSeq || 0) + 1;
  state.historyRequestSeq = requestSeq;
  try {
    const data = await fetchJson(
      '/api/history?character_id=' + encodeURIComponent(requestedCharacter) + '&limit=40'
    );
    // 迟到的响应：期间角色已切换就不要渲染，避免泄露/串入其他角色对话
    if (requestedCharacter !== state.characterId || requestSeq !== state.historyRequestSeq) return;
    if (options.restoreSeq !== undefined
        && options.restoreSeq !== (state.dialogueRestoreSeq || 0)) return;
    renderHistory(data.history || [], options);
  } catch (error) {
    if (requestedCharacter !== state.characterId) return;
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

/**
 * 失败时把内容恢复成可编辑草稿。
 *
 * 不覆盖用户已经新输入的内容 —— 发送失败后用户往往已经在重新打字。
 */
function restoreSendDraft(value) {
  if (!value) return false;
  if (!el.text) return false;
  const current = String(el.text.value || '');
  if (current.trim()) return false;
  el.text.value = value;
  try {
    el.text.dispatchEvent(new Event('input'));
  } catch (error) { /* 桩环境没有 Event 时忽略 */ }
  return true;
}

/**
 * 发送失败/结果未知的统一处理。
 *
 * 关键区分：
 *   - 请求**还没发出去**（上传失败、服务端明确报 busy）→ 只是失败，
 *     恢复草稿让用户重发；
 *   - 请求**已经发出但响应丢失** → 服务端可能已经完成，标「结果未知」，
 *     先刷新历史，绝不自动重复请求模型（跨重启也无法声称恰好一次）。
 */
function handleSendFailure(error, pending) {
  const message = error && error.message ? error.message : String(error);
  const dispatched = !!(pending && pending.chatDispatched);
  const definitelyRejected = !!(error && error.busy);
  const unknown = dispatched && !definitelyRejected;
  if (unknown) {
    systemNote('结果未知：请求可能已经送达角色，但响应丢失。已先刷新历史；'
      + '如果历史里没有这条回复，再点发送重试。');
    // 结果未知先以服务端历史为准，不自动重发
    if (typeof loadHistory === 'function') loadHistory(true);
  } else {
    systemNote(message);
  }
  const restored = restoreSendDraft(pending && pending.text);
  if (restored) {
    setStatus('发送失败，内容已保留，可再次发送重试。');
    setTimeout(() => setStatus(''), 2600);
  } else {
    setStatus('');
  }
}

/** 当前待发附件（选择图片/拍照或截图）；没有就返回 null。 */
function pendingAttachmentOf() {
  const media = state.pendingMedia;
  if (media && (media.kind === 'file' || media.kind === 'shot')) return media;
  // 网页 input 里可能还有没走 onPickedFile 的文件（拍照与图库两个入口）
  const source = activeMediaInput(null);
  const file = source && source.files && source.files[0];
  if (file) {
    return { kind: 'file', name: describePickedFile(file), file: file, thumb: '', dataUrl: '' };
  }
  return null;
}

/**
 * 上传待发附件，返回服务端资源地址。
 *
 * 结果缓存在 `media.asset`：聊天请求失败后重试时复用已上传地址，不重复上传；
 * 上传失败则抛错，附件原样保留（用户可重试或替换）。
 */
async function uploadPendingAttachment(media) {
  if (media.asset) return media.asset;
  let asset = '';
  if (media.kind === 'shot' && media.dataUrl) {
    const match = /^data:([^;,]+);base64,(.*)$/.exec(media.dataUrl);
    if (!match) throw new Error('截图格式无法识别。');
    setStatus('正在上传截图…');
    const result = await postJson('/api/upload', {
      media_type: match[1].toLowerCase(),
      data: match[2],
    });
    asset = result.url || '';
  } else if (media.file) {
    setStatus('正在上传图片…');
    asset = await uploadImage(media.file);
  } else {
    throw new Error('附件已失效，请重新选择。');
  }
  if (!asset) throw new Error('上传没有返回地址');
  media.asset = asset;
  return asset;
}

async function send(text) {
  const value = String(text || '').trim();
  const media = pendingAttachmentOf();
  if (!value && !media) return;
  // 函数级防重复：回车连击/脚本调用都拦得住
  if (state.sending) {
    systemNote('上一条还在发送中，请稍候。');
    return;
  }
  state.sending = true;
  const requestId = 'send-' + (++state.sendSeq) + '-' + Date.now().toString(36);
  const pending = { text: value, media: media, requestId: requestId, chatDispatched: false };
  state.pendingSend = pending;
  el.send.disabled = true;
  stopVoice();
  unlockAudio();
  let attachment = '';
  let thinking = null;
  try {
    if (media) attachment = await uploadPendingAttachment(media);
    const label = media ? (media.kind === 'shot' ? '（手机截屏）' : '（已附加图片）') : '';
    addBubble('user', value + (label ? '\n' + label : ''));
    // 文本先清空；附件要等**整条消息成功**才清，失败可原样重试
    if (el.text) el.text.value = '';
    thinking = addBubble('assistant', '', { typing: true });
    setStatus(state.displayName + ' 正在思考…');
    pending.chatDispatched = true;
    const data = await postJson('/api/chat', {
      character_id: state.characterId,
      text: value,
      image_asset: attachment,
    });
    thinking.row.remove();
    thinking = null;
    // 成功才清空附件；且必须是本次这个对象 ——
    // 发送期间用户新选的图片不能被误删
    if (media && state.pendingMedia === media) {
      releaseMediaInfo(media);
      state.pendingMedia = null;
      showMediaInfo(null);
    }
    // 成功才清空附件；拍照与图库两个 input 都要清，否则下次提交会带上旧文件
    clearMediaInputs();
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
    state.pendingSend = null;   // 成功：草稿记录作废
    setStatus('');
    if (state.voiceEnabled && !state.audioUnlocked) showHintOnce();
  } catch (error) {
    // 无论成功失败都先清掉「正在思考」占位，避免它永远转圈
    if (thinking) {
      thinking.row.remove();
      thinking = null;
    }
    handleSendFailure(error, pending);
  } finally {
    state.sending = false;
    el.send.disabled = false;
    state.dirtyPlayback = true;
  }
}

/* ---------- 事件绑定 ---------- */

el.form.addEventListener('submit', (event) => {
  event.preventDefault();
  send(el.text.value);
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

// 页面销毁/离开：取消在途语音，迟到音频不得出声（对象 URL 由各段释放）
window.addEventListener('pagehide', () => { stopVoice(); });

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

if (el.saveAllButton) {
  el.saveAllButton.addEventListener('click', saveAllDisplaySettings);
}

if (el.pressFeedbackToggle) {
  el.pressFeedbackToggle.addEventListener('change', () => {
    setPressFeedbackEnabled(el.pressFeedbackToggle.checked);
  });
}

/* 透明处穿透开关：默认在 API33+ 的悬浮窗里开启，随时可关掉恢复整窗交互。 */
if (el.passThroughToggle) {
  el.passThroughToggle.addEventListener('change', () => {
    state.passThroughDirty = true;   // 用户本次真的动过 → 保存时允许写原生
    setPassThroughEnabled(el.passThroughToggle.checked);
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
  if (!native || (typeof native.requestScreenPermission !== 'function'
      && typeof native.requestScreenPermissionWithSession !== 'function')) {
    if (el.screenPermHint) {
      el.screenPermHint.textContent = '截屏授权需要在手机 App 内操作（当前是悬浮窗页面）。';
    }
    return;
  }
  try {
    if (typeof native.requestScreenPermissionWithSession === 'function') {
      native.requestScreenPermissionWithSession(PAGE_SESSION_ID);
    } else {
      native.requestScreenPermission();
    }
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
      el.screenPermHint.textContent = '截图任务正在进行中。';
    } else {
      // 每次都重新授权：不再声称「授过一次以后就免弹」
      el.screenPermHint.textContent =
        '系统每次截图都会重新确认一次。点了「截取屏幕」后在系统弹窗里选「立即开始」即可。';
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
/* 拍照 / 图库两个入口：都走同一套「本地预览 + 点发送才上传」流程 */
function wireMediaInput(input) {
  if (!input) return;
  // 选完图片/拍完照就收起菜单，否则菜单会一直盖在输入框上方
  input.addEventListener('change', () => {
    closeMediaMenu();
    onPickedFile(input);
  });
}
wireMediaInput(el.image);
wireMediaInput(el.camera);

if (el.mediaInfoClear) {
  el.mediaInfoClear.addEventListener('click', () => {
    // 取消已选图片：清空拍照/图库两个 input，否则提交时还会带上旧文件
    clearMediaInputs();
    clearMediaInfo();
  });
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
  registerPageSession();
  reportUrlToNative();
  // 悬浮窗模式：切换紧凑布局并接管软键盘
  applyOverlayMode();
  // 迷你图标模式：只剩一个圆形头像
  applyBubbleMode();  // 再应用一次显示偏好。
  // 模式确定后再次同步显示偏好；旧浮动值只走兼容禁用路径。
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
  // 精确局部穿透：只在悬浮窗/小球页面有意义。模块会先冻结位移动画，
  // 再扫描 alpha 轮廓并提交给原生（API33+ 才真正生效）。
  if ((document.body.classList.contains('overlay-mode')
      || document.body.classList.contains('bubble-mode'))
      && window.SakuraTouchRegion
      && typeof window.SakuraTouchRegion.install === 'function') {
    try {
      window.SakuraTouchRegion.install();
    } catch (error) {
      systemNote('穿透初始化失败，保持整窗交互。');
    }
  }
  // 支持 ?config=1 直接展开配置页：方便大屏适配验证和分享「怎么配置」给别人看
  const params = new URLSearchParams(window.location.search);
  const wantConfig = params.get('config') === '1';
  try {
    await loadState();
    await loadHistory(true);
    // 历史加载完成后再定位一次：确保首次打开/关闭重开都显示当前角色最近的有效对话，
    // 而不是空的占位行。
    if (document.body.classList.contains('overlay-mode')) {
      syncMessageNav(true);
    }
    if (state.voiceEnabled && state.autoplay) unlockAudio();
    renderConfig();
  } catch (error) {
    systemNote('初始化失败：' + (error && error.message ? error.message : error));
    setStatus('无法连接电脑端，请确认 Sakura 正在运行。');
    renderConfig();
    // 明确上报 boot 失败：原生据此立即把完整窗口降为有界恢复卡片，
    // 绝不留下「看不到内容却拦点击」的透明大窗。
    if (typeof notifyRenderSession === 'function') notifyRenderSession('bootFailed');
  }
  installPortraitAccessibilityGuide();
  if (wantConfig) openConfig(true);
  // boot 完成（DOM、模式、历史都就绪）：通知原生「页面已就绪」。
  // 注意这只代表 boot 完成，不代表有可见立绘 —— 完整窗口仍等立绘可见信号。
  if (typeof notifyRenderSession === 'function') notifyRenderSession('bootOk');
}

boot();
