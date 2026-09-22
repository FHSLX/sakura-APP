"""手机端网页外壳。

真正的样式与脚本放在 static/ 目录，便于单独维护；
本模块只负责注入运行时配置。
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any, Mapping


def _build_id() -> str:
    """按静态文件的修改时间生成构建号。

    为什么需要：静态资源已经发了 `Cache-Control: no-cache` + ETag，
    但实测 Android WebView（悬浮窗那个）仍会直接吃旧缓存 ——
    改了 CSS 之后悬浮窗还是老样式，排查时极易误判成「改动没生效」。

    用文件 mtime 而不是进程启动时间，是为了让「只更新静态文件」
    也能换掉版本号，不必重启 Sakura。
    """

    static_dir = Path(__file__).resolve().parent / "static"
    newest = 0
    for name in ("app.css", "app.js"):
        try:
            newest = max(newest, int((static_dir / name).stat().st_mtime))
        except OSError:
            continue
    return str(newest or int(time.time()))


def render_page(token: str, settings: Mapping[str, Any] | None = None) -> str:
    bootstrap = {
        "token": token,
        "autoplay": bool((settings or {}).get("autoplay", True)),
        "tts": bool((settings or {}).get("tts_enabled", True)),
    }
    return (
        _PAGE
        .replace("__BOOTSTRAP__", json.dumps(bootstrap, ensure_ascii=False))
        .replace("__BUILD__", _build_id())
    )


_PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, user-scalable=no">
<meta name="theme-color" content="#fff6fa">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>Sakura</title>
<link rel="manifest" href="/manifest.webmanifest">
<link rel="stylesheet" href="/app.css?v=__BUILD__">
</head>
<body>
<!-- 悬浮窗模式的窗口把手：拖动移动、右下角缩放。普通模式下隐藏。 -->
<div id="windowChrome" aria-hidden="true">
  <div id="dragHandle" title="拖动移动窗口">
    <span class="gripDot"></span><span class="gripDot"></span><span class="gripDot"></span>
  </div>
  <div id="resizeHandle" title="拖动缩放窗口"></div>
</div>

<div id="stage">
  <div id="backdrop"></div>
  <div id="portraitWrap"><img id="portrait" alt="" draggable="false"></div>
  <div id="portraitShade"></div>
</div>

<header id="topbar">
  <div id="name">Sakura</div>
  <div id="controls">
    <button id="settingsButton" class="iconButton" type="button" aria-label="设置">设置</button>
    <button id="voiceToggle" class="iconButton" type="button" aria-label="语音开关">语音</button>
    <button id="clearButton" class="iconButton" type="button" aria-label="清空聊天">清屏</button>
  </div>
</header>

<section id="configPanel" class="hidden" role="dialog" aria-label="配置">
  <header id="configHeader">
    <div>
      <div id="configTitle">配置</div>
      <div id="configSubtitle">手机端不参与计算，只负责显示和播放</div>
    </div>
    <button id="configClose" class="iconButton" type="button" aria-label="关闭">关闭</button>
  </header>

  <div id="configBody">
    <div class="configGrid">

      <!-- 连接 -->
      <div class="configCard" id="connSection">
        <div class="cardTitle">连接</div>
        <div class="kv">
          <span class="kvKey">电脑地址</span>
          <span class="kvValue" id="connAddress">—</span>
        </div>
        <div class="kv">
          <span class="kvKey">访问 token</span>
          <span class="kvValue tokenValue">
            <span id="connToken">—</span>
            <button id="tokenReveal" class="miniButton" type="button"
                    aria-label="显示 token">显示</button>
            <button id="tokenCopy" class="miniButton" type="button"
                    aria-label="复制 token">复制</button>
          </span>
        </div>
        <div class="kv">
          <span class="kvKey">当前角色</span>
          <span class="kvValue" id="connCharacter">—</span>
        </div>
        <div class="kv">
          <span class="kvKey">语音合成</span>
          <span class="kvValue" id="connTts">—</span>
        </div>
        <button id="switchServer" class="ghostButton" type="button">更换电脑 / token</button>
        <div class="settingHint" id="connHint"></div>
      </div>

      <!-- 显示 -->
      <div class="configCard">
        <div class="cardTitle">显示</div>

        <div class="fieldLabel"><span>角色大小</span><span id="scaleValueInline">100%</span></div>
        <div class="scaleRow">
          <button id="scaleSmaller" type="button" class="scaleStep">-</button>
          <input id="scaleRange" type="range" min="25" max="160" step="1" value="100">
          <button id="scaleBigger" type="button" class="scaleStep">+</button>
          <input id="scaleNumber" class="numberInput" type="number"
                 min="25" max="160" step="1" value="100" aria-label="角色大小百分比">
        </div>
        <div class="settingHint">也可以在立绘上双指捏合缩放。</div>
        <button id="scaleReset" class="ghostButton" type="button">恢复默认大小</button>

        <div class="fieldLabel"><span>立绘上下位置</span><span id="portraitYValue">0%</span></div>
        <div class="scaleRow">
          <input id="portraitYRange" type="range" min="-50" max="50" step="1" value="0">
          <input id="portraitYNumber" class="numberInput" type="number"
                 min="-50" max="50" step="1" value="0" aria-label="立绘上下位置百分比">
        </div>
        <div class="settingHint">正数把立绘往上移，负数往下移。</div>

        <label class="switchRow" for="floatToggle">
          <input id="floatToggle" type="checkbox">
          <span>立绘上下浮动</span>
        </label>

        <div class="fieldLabel"><span>字体大小</span><span id="fontValue">100%</span></div>
        <div class="scaleRow">
          <input id="fontRange" type="range" min="70" max="180" step="5" value="100">
          <input id="fontNumber" class="numberInput" type="number"
                 min="70" max="180" step="5" value="100" aria-label="字体大小百分比">
        </div>

        <div class="fieldLabel"><span>对话框水平位置</span><span id="dialogXValue">0</span></div>
        <div class="scaleRow">
          <input id="dialogXRange" type="range" min="-60" max="60" step="1" value="0">
          <input id="dialogXNumber" class="numberInput" type="number"
                 min="-60" max="60" step="1" value="0" aria-label="对话框水平位置">
        </div>

        <div class="fieldLabel"><span>对话框垂直位置</span><span id="dialogYValue">0</span></div>
        <div class="scaleRow">
          <input id="dialogYRange" type="range" min="-60" max="60" step="1" value="0">
          <input id="dialogYNumber" class="numberInput" type="number"
                 min="-60" max="60" step="1" value="0" aria-label="对话框垂直位置">
        </div>
        <div class="settingHint">对话框位置用像素微调，0 是默认位置。</div>

        <div class="fieldLabel"><span>双语字幕</span></div>
        <label class="switchRow" for="showTranslationToggle">
          <input id="showTranslationToggle" type="checkbox">
          <span>中文译文</span>
        </label>
        <label class="switchRow" for="showOriginalToggle">
          <input id="showOriginalToggle" type="checkbox">
          <span>日文原文</span>
        </label>
        <div class="settingHint">两个都关掉时会自动保留日文原文，避免气泡空白。</div>
        <label class="switchRow" for="autoScrollToggle">
          <input id="autoScrollToggle" type="checkbox">
          <span>新消息自动滚动到底部</span>
        </label>

        <div class="fieldLabel"><span>对话框自动隐藏</span><span id="bubbleHideValue">12 秒</span></div>
        <div class="scaleRow">
          <input id="bubbleHideRange" type="range" min="0" max="60" step="1" value="12">
          <input id="bubbleHideNumber" class="numberInput" type="number"
                 min="0" max="60" step="1" value="12" aria-label="对话框自动隐藏秒数">
        </div>
        <div class="settingHint">
          说完这么久后收起对话框，点立绘也会立刻收起。设成 0 表示一直显示。
        </div>
      </div>

      <!-- 语音 -->
      <div class="configCard">
        <div class="cardTitle">语音</div>
        <label class="switchRow" for="autoplayToggle">
          <input id="autoplayToggle" type="checkbox">
          <span>收到回复自动朗读</span>
        </label>
        <label class="switchRow" for="ttsToggle">
          <input id="ttsToggle" type="checkbox">
          <span>允许电脑合成语音</span>
        </label>
        <div class="settingHint">
          自动朗读需要先和页面有过一次交互（浏览器限制）。语音在电脑上合成后传过来，
          每段大概 3–6 秒。
        </div>
        <button id="saveVoice" class="primaryButton" type="button">保存到电脑端</button>
        <div class="settingHint" id="voiceSaveHint"></div>
      </div>

      <!-- 桌面立绘 -->
      <div class="configCard" id="petSection">
        <div class="cardTitle">手机桌面</div>
        <button id="petToggle" class="primaryButton" type="button">开启桌面立绘</button>
        <button id="chatButton" class="ghostButton" type="button">收起 App，回桌面看立绘</button>
        <button id="miniConfigButton" class="ghostButton" type="button">缩小成圆形小球</button>
        <button id="batteryButton" class="ghostButton" type="button">申请后台保活</button>
        <div class="settingHint" id="petHint"></div>
      </div>

      <!-- 截屏授权：单独做一个按钮，让用户可以先把系统权限授好，
           之后真正截图时就不会被系统弹窗打断。 -->
      <div class="configCard">
        <div class="cardTitle">截屏</div>
        <button id="screenPermButton" class="ghostButton" type="button">授权截屏权限</button>
        <div class="settingHint" id="screenPermHint">
          点一次会弹出系统的「开始录制或投放」确认框，选择「立即开始」即可。
          授权在本次开机内有效，之后「截取屏幕」就不会再弹窗。
        </div>
      </div>

      <!-- 快捷操作（悬浮窗顶栏已移除，这些放在这里） -->
      <div class="configCard">
        <div class="cardTitle">快捷操作</div>
        <button id="quickVoice" class="ghostButton" type="button">语音：开</button>
        <button id="quickClear" class="ghostButton" type="button">清空本机显示</button>
        <div class="settingHint">清屏只清手机上的显示，不删除电脑端聊天记录。</div>
      </div>

      <!-- 角色切换 -->
      <div class="configCard configCardWide">
        <div class="cardTitle">角色</div>
        <div class="settingHint" id="charHint">正在读取角色列表…</div>
        <div id="charList" class="charList"></div>
        <button id="restartButton" class="ghostButton" type="button">重启电脑端 Sakura</button>
        <div class="settingHint" id="restartHint">
          改完角色需要重启才生效。重启期间连接会断开，之后重新打开 App 即可。
        </div>
      </div>

      <!-- 软件内说明 -->
      <div class="configCard configCardWide">
        <div class="cardTitle">怎么配置（不熟悉就照这个顺序做）</div>
        <ol class="guideList">
          <li>
            <b>电脑上</b>：启动 Sakura，打开「设置 → 手机远程端」，
            确认开关已打开，记下<b>端口</b>（默认 <code>8770</code>）
            和<b>访问 token</b>；把监听地址设为 <code>0.0.0.0</code> 手机才能通过 WiFi 连上。
          </li>
          <li>
            <b>查电脑的局域网 IP</b>：电脑上打开命令提示符执行 <code>ipconfig</code>，
            找「IPv4 地址」，形如 <code>192.168.1.100</code>。
            手机和电脑要在同一个 WiFi 下。
          </li>
          <li>
            <b>手机上</b>：在连接页填 <code>IP:端口</code> 和 token，点「测试连接」，
            显示成功再点「进入」。填对一次之后会记住，以后打开直接进。
          </li>
          <li>
            <b>想要桌面立绘</b>：在上面「手机桌面」里点「开启桌面立绘」，
            系统会要求「显示在其他应用上层」权限，允许后角色就会出现在桌面上，
            可以拖动、双指缩放；再点「申请后台保活」，让它在后台不被回收。
          </li>
          <li>
            <b>连不上怎么办</b>：① 确认 Sakura 还在运行；② 确认手机和电脑同一 WiFi；
            ③ 电脑防火墙放行该端口（首次监听时会弹提示，选「专用网络」允许）；
            ④ 用 USB 调试时，在电脑执行 <code>adb reverse tcp:8770 tcp:8770</code>，
            地址改填 <code>127.0.0.1:8770</code>。
          </li>
        </ol>
        <div class="settingHint">
          本 App 只是 Sakura 的远程屏幕：聊天、立绘、语音全部由电脑上运行的 Sakura 完成，
          电脑关机或 Sakura 退出后手机端就用不了了。
        </div>
      </div>

    </div>
  </div>

  <!-- 保存栏：放在 #configBody 之外，才会固定在底部（跟 header 一样）。
       放在 body 里面会跟着内容一起滚走，用户滑到中间就看不见了。
       显示类设置（大小/位置/字号/开关）本来就是改一下存一下，
       这个按钮给用户一个明确的「我已经保存了」的确认点，
       并把状态写清楚，避免「改了到底有没有生效」的疑虑。 -->
  <div id="configFooter" class="configFooter">
    <span id="saveState" class="saveState">设置会自动保存</span>
    <button id="saveAllButton" class="primaryButton" type="button">保存设置</button>
  </div>
</section>

<section id="bubbles" aria-live="polite"></section>

<!-- 悬浮窗里只显示单句，切换按钮竖排在对话框右边缘内（参考电脑端布局）。
     完整对话历史在 App 内查看。 -->
<div id="msgNav" class="hidden">
  <div id="msgText">
    <div id="msgName"></div>
    <div id="msgBody"></div>
  </div>
  <div id="msgButtons">
    <button id="msgPrev" type="button" aria-label="上一条">&#9650;</button>
    <span id="msgCounter" aria-label="第几条">1 / 1</span>
    <button id="msgNext" type="button" aria-label="下一条">&#9660;</button>
  </div>
</div>

<div id="hint">轻触屏幕任意处即可开启语音</div>

<!-- 缩小为小球的按钮。
     原来它单独占一行（#miniBar），夹在立绘和输入栏之间 ——
     于是气泡隐藏后，立绘和输入栏之间会留出整整一行（约 25px）的空隙，
     看起来就是「对话框收起来了但中间还是隔得很远」。
     现在并进输入栏那一行，空隙自然消失。
     #miniBar 这个包裹层保留在 DOM 里（JS 与样式仍按 id 找它），
     但用 CSS 把它设为 display:contents，让它不产生任何盒子。 -->
<div id="miniBar" class="miniBar hidden"></div>

<footer id="composer">
  <form id="form">
    <!-- 图片和截屏收进下拉菜单：悬浮窗很窄，省下的横向空间留给输入框。
         菜单用 absolute 向上展开，不占布局空间。 -->
    <div id="mediaMenu" class="mediaMenu">
      <button id="mediaToggle" class="mediaButton" type="button"
              aria-haspopup="true" aria-expanded="false" title="发送图片或截屏">＋</button>
      <div id="mediaPanel" class="mediaPanel hidden">
        <label class="mediaItem" for="image">选择图片</label>
        <button id="screenshotButton" class="mediaItem" type="button">截取屏幕</button>
      </div>
    </div>
    <input id="image" type="file" accept="image/*" class="hidden">
    <textarea id="text" rows="1" placeholder="说点什么…" autocomplete="off"></textarea>
    <button id="miniButton" type="button" aria-label="缩小为小球" title="缩小为小球">⊙</button>
    <button id="send" type="submit">发送</button>
  </form>
  <!-- 已选内容提示：让用户看清选了什么图 / 截了几张，避免"发了但不知道发了啥" -->
  <div id="mediaInfo" class="mediaInfo hidden">
    <img id="mediaInfoThumb" class="mediaInfoThumb" alt="">
    <span id="mediaInfoText" class="mediaInfoText"></span>
    <button id="mediaInfoClear" class="mediaInfoClear" type="button" aria-label="取消">×</button>
  </div>
  <div id="status"></div>
</footer>

<script>window.__SAKURA__ = __BOOTSTRAP__;</script>
<script src="/app.js?v=__BUILD__"></script>
</body>
</html>
"""
