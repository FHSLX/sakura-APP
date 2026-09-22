# Sakura 手机端 WebView 外壳（Capacitor）

把电脑上 Sakura 的 `sakura_remote` 插件页面包成安卓 App，目标是「打开就进」，
不用每次手动敲 IP 和 token。

**它不是独立应用**：聊天、立绘、语音全部来自电脑上的 Sakura。
App 只做三件事 —— 记住服务器地址、用 WebView 加载页面、播放语音。

## 组成

```
phone_app/
├── www/index.html          连接设置页（填地址+token，测试连通性，存 localStorage）
├── capacitor.config.json   Capacitor 配置（androidScheme=https，webDir=www）
├── package.json            @capacitor/core|android|cli ^8.5.2
├── icon_source.png         从角色立绘裁出的 1024×1024 图标源图
├── build_apk.ps1           一键构建脚本
└── android/                原生工程（由 `npx cap add android` 生成后改造）
    └── app/src/main/
        ├── AndroidManifest.xml
        └── res/xml/network_security_config.xml   ← 允许明文 HTTP 的关键
```

## 构建

前置：JDK 17+（本机用 JDK 21）与 Android SDK（platform 36 + build-tools 36）。

```bat
REM 一次产出 debug + release 两个 APK
phone_app\build_apk.bat
```

或手动：

```powershell
cd phone_app
npm install
npx cap sync android
cd android
.\gradlew.bat assembleDebug assembleRelease
```

产物：

| 文件 | 大小 | 签名 | 用途 |
| --- | --- | --- | --- |
| `app-debug.apk` | 7.74 MB | Android Debug Key | 开发调试 |
| `app-release.apk` | 6.83 MB | `CN=Sakura Remote`（自签名） | 日常使用 |

> 优先用 `.bat`：PowerShell 5.1 的 `$PSScriptRoot` / 路径引用在 `-File` 模式下
> 容易踩坑，这个项目在此浪费过不少时间。`build_apk.bat` 是纯 ASCII，
> 且自动处理了下面的两个环境问题。

### 安装

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File phone_app\install_apk.ps1 -Release
```

debug 与 release 的**签名不同**，Android 不允许互相覆盖升级。
脚本检测到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 时会自动卸载旧版再装，
代价是 App 里保存的地址和 token 会被清掉（重新填一次即可）。

### 签名密钥

`sakura-release.jks` + `android/keystore.properties` 是本机自签名证书，
**只用于个人安装，不上架**。这两份请一起备份 ——
keystore 丢失后，已装的应用只能卸载重装，无法覆盖升级。
它们已加入 `.gitignore`，不会进版本库。

### ⚠️ `local.properties` 不能写中文路径

Java 的 `.properties` 默认按 **ISO-8859-1** 解析，`sdk.dir` 里出现中文会被读成乱码，
gradle 会直接报找不到 SDK。

本项目路径含中文时，Android Gradle 会拒绝，因此 `build_apk.bat` 会引用一个
**纯 ASCII 的目录联接**：

```bat
set ANDROID_SDK_ROOT=D:\Android\Sdk   REM 任意 ASCII 路径
REM 或让 build_apk.bat 自动建 ASCII 目录联接
```

## 为什么需要 network_security_config

Capacitor 的 WebView 跑在 `https://localhost`，向 `http://192.168.x.x:8770` 发请求
在 Android 9+ 默认会被拦。而 Android 的 `<domain>` 规则**只接受域名，不接受 IP 或网段**，
电脑端恰好只有裸 IP、没有域名，所以无法精确放行，只能：

```xml
<base-config cleartextTrafficPermitted="true" />
```

安全边界因此落在别处：手机端只访问你在连接页填的那一个地址、所有接口都要 token、
插件默认只监听 `127.0.0.1`、不要把端口映射到公网。
如果电脑有稳定域名（如 Tailscale MagicDNS），可以改成精确放行那个域名。

## 图标与启动图

图标源图从当前角色立绘裁出（`icon_source.png`），再用脚本缩放到各密度：

- `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`
- `.../ic_launcher_round.png`（内容缩到 70%，避免圆形遮罩裁到脸）
- `.../ic_launcher_foreground.png`（自适应图标前景）
- `drawable-port-*` / `drawable-land-*` / `drawable/splash.png`

自适应图标背景色 `ic_launcher_background` 设成了角色主题的 `#FFF6FA`。

## 桌面立绘与后台保活

### 为什么是悬浮窗

应用**不能**把内容直接画到桌面上。能达到「角色待在桌面、压在其他应用之上」的只有一条路：
悬浮窗（`SYSTEM_ALERT_WINDOW`，系统里叫「显示在其他应用上层」）。
Widget 做不到（尺寸受限、不能动画），动态壁纸则在桌面图标下面。

### 悬浮窗里就是完整对话

悬浮窗承载一个 WebView，加载插件的 `?mode=overlay` 页面 —— **复用同一套对话逻辑**
（语气表情、TTS 播放、图片上传、角色缩放都已在页面里实现），所以悬浮窗和 App 里
看到的是同一个对话，不需要维护两套界面。

窗口结构：

```
┌──────────────────────────────┐
│ ⠿ 按住拖动       [对话][收起] │  ← 原生标题栏
├──────────────────────────────┤
│  立绘 + 对话气泡 + 输入栏      │  ← WebView（?mode=overlay）
└──────────────────────────────┘ ◢ 右下角原生缩放手柄
```

两个关键设计：

- **拖动必须用原生标题栏**：WebView 会吃掉触摸事件，所以拖动交给独立的
  `TextView` 标题栏，让网页自己处理点击、滚动、输入。
- **默认 `FLAG_NOT_FOCUSABLE`**：否则悬浮窗会抢走其他应用的输入。
  网页在输入框聚焦/失焦时通过 `SakuraNative.setKeyboardOpen()` 通知原生切换，
  软键盘才出得来。

### 调整大小

拖动右下角手柄改变窗口尺寸，松手后把「宽度占屏比」和「高宽比」写进
`PetConfig`，下次打开保持。范围：宽度 45%–98%，高宽比 0.5–2.4。

角色大小（缩放）是另一回事，由页面里的滑块/双指捏合控制，两者互不影响。

### 透明与缓存：两个必须踩对的点

**1. 透明要连 `html` 一起改。** 只给 `body` 设 `background: transparent` 不够，
`html` 的底色仍会透出来，表现为桌面上一层白块。
现在 `html.overlay-mode` 和 `body.overlay-mode` 都设了透明，
`#stage` / `#backdrop` / `#portraitShade` 也一并透明，立绘用
`mix-blend-mode: multiply` 去掉自带白底。

**2. 悬浮窗的 WebView 必须关缓存。** 实测它会连 **HTML 一起吃旧缓存** ——
改了 CSS 后悬浮窗一直跑旧版，页面上加 `?v=` 版本参数也救不了（页面根本没重新请求）。
所以 `PetWindow` 里设了：

```java
settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
webView.clearCache(true);
```

同时页面给 `app.css` / `app.js` 附加了基于文件 mtime 的版本参数作为第二道保险。
排查这类「改了没生效」的问题时，先确认加载的样式表 URL 里有没有版本号。

### 内嵌对话的布局约束

气泡区和输入栏用**绝对定位划分固定区域**（气泡 `top: 96px; bottom: 122px`），
不要改成 flex —— `html`/`body` 有 base 样式，body 的 `display` 会被后面的普通声明覆盖，
flex 在这里很容易静默失效。实测用 flex 时气泡底边 244、输入栏顶边 199，重叠 45px。

输入栏是 4 列 grid（`图 | 截屏 | 输入框 | 发送`）。
**加控件必须同步改 `grid-template-columns`**，否则多出来的控件换行 ——
`tests/bubble_render_check.js` 会断言列数不少于控件数。

## 手机截屏：让她看到你正在看什么

输入栏的「截屏」按钮会截当前手机屏幕并发给角色。

链路：

```
MediaProjection 截屏
  → 原生按最长边 1280 缩放 + JPEG q70 压缩
  → JS 拿到 data URL → POST /api/upload → 随消息给模型
```

**为什么在客户端压缩**：1080×2460 的原始截图 2–4 MB，
压完约 **150–300 KB**，省约 10 倍。电脑端不需要做任何改动，
复用已有的 `/api/upload` + `image_url` 链路。

**为什么不用电脑端的截图**：插件的 `sakura.host.screen.capture()` 只返回一个
`resourceId`，真正的 JPEG 取回函数 `consume_screen_resource()` 被明确标注为
**Core 专用**，不经过 `host.call`，插件拿不到图。
而且「学姐看到我正在看什么」在手机场景下本来就指手机屏幕。

**授权**：首次点截屏会弹系统的「开始录制或投放」对话框，允许后同一次调用直接出图。
授权不能持久化（Android 限制），进程结束后要重新授权。

**清屏 / 语音 / 设置**这些按钮在悬浮窗里保留，所以悬浮窗内就能完成日常操作。

### 点击穿透：只有立绘和控件吃点击

透明窗口仍会拦截其矩形范围内的**所有**触摸，于是桌面上会有一块
「看不见却点不动」的区域。解决方式是动态切换 `FLAG_NOT_TOUCHABLE`：

```
指针在立绘/气泡/按钮/输入栏上 → 窗口接收触摸
指针在空白处（stage/backdrop）→ 整个窗口不接收触摸，点击穿透到桌面
```

判定在网页侧用 `document.elementFromPoint` 做，通过 `SakuraNative.setTouchable()`
通知原生。两个实现要点：

- **必须用 `pointerdown` 而不是 `click`**：一旦设成不可触摸，`click` 就不会再来，
  窗口会永久「点不动」。`pointerdown` 在按下瞬间仍能收到，判定完立刻放行，
  后续手势（滚动、点击）都还在同一次触摸序列里，所以不会丢。
- **按下后保持 1.5 秒可触摸**，让拖动标题栏、滚动气泡这类连续手势能完成。

### 弱化「框」的感觉

窗口是矩形，里面任何大面积底色都会显得像个框。所以：

- **标题栏不设整条背景**（原来是一块 90% 不透明的白色圆角矩形，
  横贯窗口顶部，视觉上就是一条边框）。现在只有「按住拖动」手柄和两个按钮
  各自带小胶囊底，其余透明。
- 窗口容器用 18dp 圆角 + `setClipToOutline`，四角不再是直角。
- `#stage` / `#backdrop` / `#portraitShade` 全部透明，立绘用
  `mix-blend-mode: multiply` 去掉自带白底。

### 悬浮窗的交互：全部在网页侧

窗口内没有任何原生 UI 条 —— 顶部那条「按住拖动 + 对话/缩短」横条会造成
一条横贯的矩形，正是要消除的「框」。所以拖动改成由网页手势驱动：

| 操作 | 行为 |
| --- | --- |
| 长按立绘并移动 | 拖动窗口（网页发 `SakuraNative.moveBy()`，原生改窗口坐标） |
| 长按立绘静止 5 秒 | 打开配置页 |
| 轻点 | 不做事 |

**为什么拖动放在网页侧**：去掉原生标题栏后窗口就没有自带拖动区了；
而且网页能精确判断按住的是立绘还是气泡/按钮，误触更少。

不拖动的原因要注意：`touchmove` 里一旦判定为拖动就**取消 5 秒定时器**
（`clearHold()`），否则拖到一半会突然弹出设置页。

「设置/语音/清屏」从顶栏移到了**配置页**（长按 5 秒打开）。语音和清屏做成了
「快捷操作」卡片，清屏只清手机显示，不动电脑端记录。

### 宽度跟随立绘

`--pet-content-w` 由 JS 按立绘的实际渲染宽度设置，气泡区和输入栏都用它，
所以拖动放大/缩小立绘时两者一起变宽变窄：

```
portraitWidth = 254px
bubblesWidth  = 254px   ← 一致
composerWidth = 254px
```

缩放、换表情都会改变立绘尺寸，靠一个 500ms 的定时器兜底同步最省事。

### 窗口尺寸与立绘缩放

窗口会调到接近整屏，让立绘有最大展示空间；立绘按「窗口实际可见区域」等比适配。

**为什么不能按 `screen` 算**：WebView 的布局视口在同一个进程里**不会**随窗口尺寸更新。
实测原生日志显示窗口已是 1080px，`window.innerWidth` 却一直停在 322（= 886/2.75）。
按 `screen.width`（393）算出来的立绘就会比窗口宽，被裁掉 29px。

所以可用区域只能取 `window.innerWidth/innerHeight`，缩放则用 CSS 变量：

```
容器 = 立绘在缩放 1.0 时铺满可用区域的尺寸
缩放 = min(用户设定值, 可用区域 / 容器尺寸)   ← 上限夹紧，保证永不裁切
```

实测（窗口 1048×2428，视口 381×883）：

| 缩放 | 立绘渲染 | 裁切 |
| --- | --- | --- |
| 1.0 | 369×386 | 0 |
| 0.7 | 258×270 | 0 |
| 0.4 | 185×193 | 0 |

**另一个坑**：`--pet-scale` 必须写在 `#portraitWrap` 上，因为 `applyPortraitScale`
写的是那个元素；写在 `:root` 会被它自己的内联值盖住，表现为「滑块完全没反应」。

### 只显示单句 + 上下切换

悬浮窗里气泡区很窄，显示整段历史会把立绘完全盖住。所以一次只显示一条，
顶部有 `▲ 14 / 14 ▼` 切换，完整对话历史在 App 内查看。

实现上所有气泡节点仍留在 DOM 里（语音播放、语气表情都依赖它们），
只是用 `.current` 控制哪一条可见 —— 所以翻历史不影响正在播放的语音。

气泡贴在输入栏上方（下边界定位 + `justify-content: flex-end`）。
用 `top`+`bottom` 两头夹会把气泡顶到屏幕上方，和输入栏离得很远。

### 单句气泡 + 历史切换的验证脚本

```powershell
# 需要先 adb forward 到 WebView 调试端口
<Sakura>\python\python.exe tools\overlay_verify.py
```

它会触发进入悬浮模式，然后逐个缩放倍率量测「立绘尺寸 / 是否裁切 / 导航状态」。

**注意别用窗口尺寸区分 App 和悬浮窗**：窗口会被调成接近整屏（实测 1048×2428），
和 App 的整屏目标几乎一样大，靠宽度判断必然连错对象（我为此浪费了好几轮排查）。
用 URL 里的 `mode=overlay` 区分才可靠。

### Android WebView 不更新布局视口（重要）

**这是整套悬浮窗布局问题的总根源。**

窗口用 `WindowManager.updateViewLayout` 改尺寸后，**WebView 不会重算布局视口**：

```
setWindowSize(322, 700) 后 → window.innerHeight 仍是 338（旧值）
窗口实际 1080x1925，而 window.innerWidth/innerHeight = 322x338
```

后果是页面按**错误的视口**排版 —— 明明窗口很大，内容还是挤在旧尺寸里。
`location.reload()` 之后视口才同步（实测 reload 后窗口 886x1925、
视口 322x700，二者一致）。

`webView.onSizeChanged` 也不会触发重算。目前用的绕过方式是按尺寸变化 reload
（`syncViewportIfNeeded`）。

### 不要在布局计算里引入会抖动的量

`measure()` 一开始用「实测的卡片高度」算窗口高度，结果形成反馈环：

```
卡片高度 → 窗口高度 → 卡片重新布局 → 高度又变 → …
```

实测窗口请求值在 `462/648/623/564/757` 之间来回跳，永远收敛不了。
修法是**窗口高度只用稳定的量**（屏幕高度减固定的输入栏预算），
卡片高度只用于定位、不参与窗口尺寸计算。修完后请求值稳定在 `322x434`。

### CSS 也要防拉伸

`#msgText` 用 `flex: 1 1 auto` 会让卡片吃掉 stage 让出的空间，
实测卡片被拉到 260 高、又把输入栏压住。应该用 `flex: 0 0 auto`，
让 `#stage` 独占剩余空间（`flex: 1 1 auto; min-height: 0`）。

### 已知未解决

（原「对话框与输入栏重叠」已解决：根因是 JS 写死立绘像素高度、超出的部分盖住卡片。
改用 CSS `height:100%` + `object-fit:contain` 让立绘填满 flex 分配的空间后，
实测 `overlap = 0`。）

注意：对话框高度上限取屏幕高的 40%（`--pet-nav-h`），
超出的长回复在卡片内部滚动，不再撑高窗口。

### 排查悬浮窗布局的正确姿势

```powershell
<Sakura>\python\python.exe tools\overlay_verify.py
```

**必须按 URL 里的 `mode=overlay` 区分 App 和悬浮窗的 WebView**：
窗口会被调成接近整屏，靠尺寸判断必然连错对象。

**另外：探针不要有副作用。** 我曾在探针里调用 `applyMessageNav()` 把卡片清空后
再读长度，得出「内容正常」的错误结论，白排查了好几轮。

### 布局的自我放大循环（最隐蔽的一个坑）

`measure()` 曾经用 `window.innerHeight` 算可用高度，形成**自我放大**：

```
用 innerHeight 算布局 → 请求更大窗口 → 窗口变大导致视口变大
→ measure 又算出更大的布局 → 再请求更大窗口 → …
```

实测每 400ms 涨 12px，从 350 一路涨到 890（≈屏幕高）才停，连续几十次请求。
**修法：布局计算只用 `screen.width/height`**，这样 measure 的结果与窗口尺寸无关，
循环自然消失。

### 不要用 JS 写死立绘的像素尺寸

`measure()` 曾给立绘设 `width/height`（算出 395 高），而 flex 实际只分给它 188 ——
**多出来的 207px 全部盖在对话框上**，这就是卡片被压住的直接原因。

正确做法：**立绘尺寸交给 CSS**（`height:100%` + `object-fit: contain`），
stage 的高度由 `--pet-avail-h`（屏幕高 − 卡片 − 输入栏）决定，
JS 只负责用户的缩放倍率 `--pet-scale`。

### 最终稳定状态（实测）

```
窗口     886x2460（满屏）
视口     322x894        ← 与窗口一致（reload 同步过）
stage    0   → 467      ← 立绘填满
nav      467 → 825      ← 对话框，正好接在立绘下方
composer 826 → 895
total    894 = 视口      ✓
overlap  0               ✓
```

### 边框与角色一起缩放（自适应窗口）

缩放滑块现在**真的改变窗口大小**，而不是在固定窗口里缩放角色。

```
窗口 = 立绘 + 对话框 + 输入栏
```

实测：

| 缩放 | 窗口尺寸 |
| --- | --- |
| 1.00 | 1080 × 2305 |
| 0.70 | 767 × 1977 |
| 0.45 | 556 × 1757 |

实现要点（每一条都是踩出来的）：

1. **布局只用 `screen`，绝不读 `innerWidth/innerHeight`。**
   改窗口后 WebView 不更新布局视口，读它会形成自我放大循环
   （每 400ms 涨 12px，一路涨到屏幕高才停）。
2. **立绘尺寸不写死像素。** 由 `--pet-avail-h` 决定 stage 高度，
   立绘用 `height:100%` + `object-fit:contain` 填满，
   JS 只负责缩放倍率 `--pet-scale`。写死像素会和 flex 分配不一致，
   超出的部分会压住对话框（实测超出 207px）。
3. **记住自己请求的窗口尺寸。** 存进 `sessionStorage`
   （`sakura.overlayWin`）。reload 之后布局仍能读到准确尺寸，
   不受陈旧视口影响，也避免重复请求。
4. **缩放变化要防抖**（450ms）。拖滑块会连续触发几十次，
   不防抖就会连续请求窗口尺寸 + reload。
5. **尺寸变了才 reload**（`syncViewportIfNeeded`），并且把已同步的尺寸
   记进 `sessionStorage`，防止 reload → measure → reload 死循环。

### 显示微调：位置、字号、字幕

配置页「显示」里可以调：

| 项目 | 范围 | 说明 |
| --- | --- | --- |
| 角色大小 | 25–160% | 窗口跟着缩放，不是固定窗口里缩放 |
| 立绘上下位置 | −50–50% | 正数往上（CSS 里立绘贴住下方对话框，所以往上要减小 Y） |
| 立绘上下浮动 | 开关 | 关掉会移除呼吸动画，但保留上面的位置偏移 |
| 字体大小 | 70–180% | 只影响对话框文字 |
| 对话框水平/垂直位置 | ±60px | 像素微调 |
| 中文译文 / 日文原文 | 两个开关 | 双语字幕各自可关 |

**每个滑动条旁边都有数字输入框**，可以直接敲数值精调。
两个输入双向联动：拖滑块同步数字框，改数字框同步滑块。
数字框用 `change` 而不是 `input` —— 输入过程中（想输负号、删空重输）
每敲一个字符就套用会很难用；回车可以立即生效。

**字幕兜底**：两个字幕都关掉时气泡里会没有任何文字，所以会强制显示日文原文
（`body.show-original-fallback`）。宁可显示一种，也不要出现空气泡 ——
用户会以为功能坏了。

只用日文原文时，`.textOriginal` 会切换成正文样式（15px、不透明、主色），
否则会沿用它作为「次要内容」时的小字浅色样式，很难看清。

### 对话框高度按文字自适应

原来卡片固定占屏幕 40%，短消息时留一大片空白。现在按内容实测：

```js
// 关键：测量前必须先把卡片高度清零
el.msgNav.style.height = '0px';
const natural = el.msgText.scrollHeight;   // 这才是内容真实高度
el.msgNav.style.removeProperty('height');
```

**为什么必须清零**：`--pet-nav-h` 是给卡片的固定高度，它会把卡片撑高，
于是 `scrollHeight` 返回「被撑高后」的值，永远等于上限
（实测无论文字长短都是 304）。清零后才拿到真实内容高度（实测 232）。

高度按 8px 量化，避免亚像素抖动导致反复请求窗口尺寸。
超过上限（屏幕高 40%）的长回复在卡片内部滚动。

### 不要再靠 reload 同步视口

曾经用「视口与目标尺寸不一致就 `location.reload()`」来同步布局视口。
**这条路是错的**：`measure()` 每 400ms 跑一次，每次都会判断出「不一致」，
于是连续 reload —— **触发服务端速率限制（240 次/分钟）返回 400 JSON**，
WebView 把 JSON 当页面显示，整个界面变成
`{"ok": false, "error": "..."}`。

正确做法：**在加载页面前就把窗口尺寸定好**。
网页算出的尺寸通过 `SakuraNative.setWindowSize()` 落盘到
`PetConfig.wantWindowWidth/Height`，原生在 `attachOverlay()` 里
建窗口时直接用这个值 —— 视口天生就是对的，不需要任何 reload。

### token 的显示/复制

配置页「连接」里的 token 默认打码（`sak****770`），旁边两个按钮：

- **显示 / 隐藏**：切换明文。明文用等宽字体 + 完整换行，方便逐字核对。
- **复制**：写入剪贴板。

默认打码是为了「别人瞄一眼屏幕看不到完整口令」，但自己核对时必须能看全。
复制做了兜底：悬浮窗从 `http://` 加载，属**非安全上下文**，
`navigator.clipboard` 可能不可用，所以保留 `execCommand('copy')` 兜底 ——
否则点了「复制」毫无反应。

### 立绘浮动与位置：为什么曾经「点了没反应」

两个真 bug，都已修：

**1. 浮动开关被「锁存」。** `boot()` 里 `loadDisplayPrefs()` 跑在
`applyOverlayMode()` **之前** —— 那时 body 上还没有 `overlay-mode`，
之后没有任何地方重新应用偏好。实测：localStorage 里
`portraitFloat = "true"`，body 上却还挂着 `no-float`，浮动被锁死。
修法：模式确定后再重放一次 `loadDisplayPrefs()`。

**2. 上下位置用百分比，基准不稳定。** 立绘高度是 flex 分配的，
`translateY(-30%)` 的百分比基准跟着布局变，实测「调了没反应」。
修法：**换算成像素**再设（`-height * percent / 100`），
并在 `measure()` 里按新高度重算。

CSS 上也要注意：关闭浮动的规则必须和开启规则**同特异性且更靠后**：

```css
/* 少写 .overlay-mode 就会和上面的规则打平，谁生效取决于先后顺序 */
body.overlay-mode.no-float #portrait,
body.no-float #portrait { animation: none; ... }
```

实测（用配置页真实控件走一遍）：

```
启动后        animation=breathe  noFloat=false     浮动在跑
数字框输 25   panY=-71px（285×25%）                位置生效
关浮动        animation=none     noFloat=true
开浮动        animation=breathe  noFloat=false
```

### 角色列表（为什么不能切换）

配置页有「角色」卡片，列出电脑端所有角色并标出**当前**那个。

**手机端无法切换角色**，这是宿主的硬限制，我逐层查证过：

| 环节 | 结论 |
| --- | --- |
| `sakura.host.mobile` | `_current_character()` 在角色不匹配时直接抛 `MOBILE_CHARACTER_NOT_CURRENT` |
| 16 个 `sakura.host.*` 的 exports | **没有一个**能切换当前角色 |
| `HOST_CHARACTER_SERVICE` | 只有 `current / get / update / resolve_resource` |
| `characters.settings.select` | 存在，但挂在**桌面 IPC** 上，要求 `generationId` + `generationCredential`，且凭据不暴露给插件 |
| `CharacterSettingsBoundary` | 由 PySide6 前端调用，插件拿不到 |
| `characters.yaml` | `current_character_id` 存在这里，插件**能写**；但没有任何 watcher，**运行中的程序不会热生效**，只能重启 |
| 官方 `sakura_mobile` | 同样只读当前角色 → 说明是刻意设计 |

所以点非当前角色时，界面给出确切指引（角色 id + 去哪里切），
而不是假装切换成功。

## 运行模式：App 会自动收起

这是最容易踩的坑：**悬浮窗会被自己 App 的前台窗口盖住**，
用户在设置页点「开启」后看到的还是 App 界面，会误判成「没显示」。

所以「开启桌面立绘」走的是**进入立绘模式**：

1. 原生开启前台服务 + 悬浮窗
2. `moveTaskToBack(true)` 把 App 收到后台
3. 角色就留在桌面上了

回到对话有两条路：
- **点桌面上的角色** → 拉起 App 并直接加载远程页（`EXTRA_OPEN_CHAT`）
- **点通知栏的「对话」** 或从应用图标进入

配置页里也有「收起 App，回桌面看立绘」按钮，随时可以切回去。

### 启动时自动恢复

`PetConfig.overlayEnabled` 记录「用户是否希望立绘常驻」：

- 用户点开关开启 → 置 `true`
- 用户在通知栏点「关闭」→ 置 `false`
- **进程被系统回收不会改它**（所以下次打开 App 能自动恢复）

App 启动时若该标记为 `true` 且已配置且权限还在，就自动拉起悬浮窗 ——
不需要每次手动点开关。权限被撤销时**不会**自动弹设置页打扰用户，
只把状态显示成「之前开过但权限被撤销了」。

### 权限：必须手动授予

`SYSTEM_ALERT_WINDOW` 属于特殊权限，**adb 无法授予，也无法用代码绕过** ——
这是 Android 防恶意悬浮窗的设计。未授权时点开启会跳转系统设置页，回来后自动继续。

MIUI/HyperOS 上还需要额外注意：
- 「后台弹出界面」权限（否则从悬浮窗启动 Activity 会被拦，点角色回不到对话）
- 电池优化白名单（点「申请后台保活」会拉起系统弹窗）

### 保活分层

| 层 | 做法 | 作用 |
| --- | --- | --- |
| 前台服务 | `startForeground` + `foregroundServiceType="specialUse"` | 返回桌面后不被立刻回收 |
| 常驻通知 | 低优先级通知（`IMPORTANCE_MIN`）+ 对话/关闭按钮 | 系统要求前台服务必须有通知 |
| 电池白名单 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 避免省电策略杀掉服务 |
| `START_STICKY` | `onStartCommand` 返回值 | 被回收后系统会尝试重启 |

Android 14 起前台服务必须声明类型，`specialUse` 还要在 manifest 里用
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途，否则安装即失败。

### 立绘的交互

- **单指拖动**移动位置
- **双指捏合**缩放（与网页共用同一个 `scale`，两边始终一致）
- **轻点**回到对话
- 网络失败时保留上一次的立绘，不打扰用户；每 45 秒刷新一次表情

## 角色大小

网页和桌面立绘共用 `PetConfig` 里同一个 `scale` 键：

- 网页滑块/双指捏合 → `localStorage` + 原生桥接 → 悬浮窗实时跟着变
- 悬浮窗双指捏合 → 写回同一个键 → 网页下次读取时同步

实现上有个坑（详见 `sakura_remote/README.md` 第 5 条）：
缩放和呼吸动画不能共用同一个 `transform`，所以 DOM 拆成
外层 `#portraitWrap`（缩放/居中）+ 内层 `#portrait`（动画）两层。


App 里的连接页把地址和 token 存进 `localStorage`，然后跳转到
`http://<host>/?token=<token>` —— 也就是插件提供的页面。
所以**改手机端界面本质上是改 `sakura_remote/static/`**，这个外壳很少需要动。

> 注意两个页面是**不同源**：连接页在 `https://localhost`，远程页在
> `http://<电脑IP>:8770`，localStorage 不互通。
> 所以远程页会通过 `SakuraNative.rememberUrl()` 把地址回报给原生，
> 原生侧用它兜底同步配置 —— 否则在远程页点「开启桌面立绘」会误报「未配置」。

## 远程连接（异地组网）

App 连接的是「电脑的某个可达地址」，所以任何能让手机访问到电脑 8770 端口的方案都能用，
包括 **蒲公英 / ZeroTier / Tailscale** 这类异地组网。

以蒲公英为例：

1. 电脑和手机都装蒲公英客户端并登录同一账号，加入同一个组网；
2. 在蒲公英控制台查电脑成员的**虚拟 IP**（常见形如 `172.16.0.x`）；
3. App 连接页把地址填成 `虚拟IP:8770`（不是局域网 IP）；
4. 电脑端 Sakura 的「手机远程端」监听地址保持 `0.0.0.0`。

**不需要改 App**：`network_security_config.xml` 已经是全域允许明文 HTTP，
虚拟网段会被放行（Android 的 `<domain>` 规则本身也不支持 IP 和网段）。

两个注意点：
- 蒲公英要**两端都保持在线**；免费版经转发节点的实际带宽通常够用，见下面测算。
- 电脑防火墙要对这个端口放行（首次监听时弹的提示选「专用网络」允许）。

### 带宽需求（本机实测）

| 项目 | 实测值 |
| --- | --- |
| 单张立绘 PNG | 平均 1.36 MB（15 张共 19.9 MB） |
| TTS 音频 | 32 kHz / 16 bit / 单声道 = **64 KB/秒（512 kbps）** |
| 一段 4.32 秒语音 | 276 KB，合成耗时 5.0 秒（约 0.9× 实时） |

由此推算：

| 场景 | 平均带宽 | 说明 |
| --- | --- | --- |
| 只看立绘 + 打字（关语音） | 几乎为 0 | 立绘仅在首次或换表情时下载 |
| 语音对话（说话占约 30%） | **约 150 kbps** | 推荐预留 **2 Mbps** |
| 语音几乎不停（占 100%） | 512 kbps | 网络下限 **1 Mbps** |
| 首次进入 | 瞬时 2–3 MB | 立绘 + 历史，之后走缓存 |

**结论：3 Mbps 以上很舒服，1 Mbps 勉强可用。**

想明显降带宽，可把语音从 WAV 转成 Opus/AAC —— 同样内容 48–64 kbps 就够，
**约省 10 倍**，代价是电脑端多一步转码。

## 原生桥接：为什么要 MainActivity + RemoteBridge

Capacitor 把本地页跑在 `https://localhost`。从 https 页面用
`location.replace('http://192.168.x.x:8770/...')` 跳到 http 属于**跨源导航**，
WebView 会静默拦掉 —— 现象是「测试连接」成功、消息显示「正在进入…」，但页面一动不动。

所以连接页优先调用原生桥接：

```js
window.SakuraNative.open('http://192.168.1.100:8770/?token=...')
```

`RemoteBridge` 校验只接受 http/https，然后 `MainActivity` 在 UI 线程调用
`webView.loadUrl()`。拿不到桥接时才退回 `location.href`（浏览器里也能用）。

## 不开触摸屏调 App（真机验证用）

App 开了 `webContentsDebuggingEnabled`，所以可以用 Chrome DevTools Protocol
从电脑直接读写页面、注入 JS，不需要碰手机：

```powershell
$adb = "<repo>\tools\platform-tools\adb.exe"
$pid = (& $adb shell pidof com.sakura.remote).Trim()
& $adb forward tcp:9222 localabstract:webview_devtools_remote_$pid
python tools\webview_cdp.py eval "document.title"
python tools\webview_cdp.py eval "({url: location.href, bubbles: document.querySelectorAll('.bubbleRow').length, audio: audio && !audio.paused})"
python tools\webview_cdp.py navigate "http://192.168.1.100:8770/?token=..."
```

这在 MIUI 上特别有用：小米默认拦 `adb shell input tap`，
但 DevTools 通道不受影响。

## 构建时踩到的坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Your project path contains non-ASCII characters` | 项目路径含非 ASCII 字符 | `gradle.properties` 加 `android.overridePathCheck=true` |
| gradle 找不到 SDK | `local.properties` 按 ISO-8859-1 解析，中文路径变乱码 | 用 ASCII 路径的 SDK，或让 `build_apk.bat` 自动建目录联接（`build_apk.ps1` 会自动处理 |
| wrapper 下载超时 | `services.gradle.org` 不可达 | 用腾讯云镜像手动下 gradle，再用 `.gradle-dist\gradle-8.14.3\bin\gradle.bat` |
| 依赖解析失败 | Maven Central / Google 太慢 | `gradle-mirror.init.gradle` 换阿里云镜像 |
| 插件启动报 `PLUGIN_CONFIG_INVALID` | PowerShell `Set-Content -Encoding UTF8` 写了 BOM | 用 `[IO.File]::WriteAllText(..., UTF8Encoding($false))` |
| 桥接方法抛 `A WebView method was called on thread 'JavaBridge'` | `addJavascriptInterface` 的方法**不在主线程**，而 WebView 的方法必须在主线程调用 | 不要在桥接方法里调 `WebView.getUrl()` 等；需要页面信息就让网页主动推过来（见 `rememberUrl`） |
| 改了 CSS/JS 但悬浮窗一直跑旧版 | 悬浮窗的 WebView **连 HTML 都缓存**，页面带 `?v=` 也救不了（根本没重新请求） | `PetWindow` 里 `setCacheMode(LOAD_NO_CACHE)` + `clearCache(true)` |
| 用 CDP 测原生桥接「没反应」也没日志 | **连错了 WebView**：App 和悬浮窗各有一个，`/json/list` 里两个都报 `visible:true` | 用窗口尺寸区分：整屏（width≥1000）是 App，小窗是悬浮窗。`ServiceBridgeHost.enterOverlayMode()` 本身是空实现，连错了自然什么都不发生 |
| 立绘整体半透明 | 用 `mix-blend-mode: multiply` 去抠白底，副作用是整张图变半透明 | 别用混合模式抠图；让 PNG 自带透明通道，`mix-blend-mode: normal` |
| `CalledFromWrongThreadException: Expected: main Calling: JavaBridge` | **桥接方法全跑在 JavaBridge 线程**，View / WindowManager / WebView 都是 UI 对象 | 所有窗口操作都 `handler.post(...)` 切主线程。这个坑我踩了两次：先是 `WebView.getUrl()`，后来是 `rootView.getWidth()` + `removeView/addView` |
| 悬浮窗消失 | 上面那个异常发生在 `setWindowSizePx` 里，把重挂载流程打断了，视图被摘下来没挂回去 | 除切线程外，所有用到 `rootView` 的地方都要判空 |
| 立绘越量越小 | 用 `wrap.clientWidth` 当可用宽度 —— 那是自己刚设过的值，缩放被反复乘进去 | 可用区域只能取 `window.innerWidth/innerHeight`；不要用被测元素自己的尺寸做输入 |
| 窗口被推出屏幕 | 用「保持右下角不动」锚定；窗口从 886 长到 1048 时坐标变成 (-99,-493) | 改成简单夹紧：`x = clamp(0, screenW - width, x)` |
| CDP 测桥接「没反应」且无日志 | 桥接方法有多个宿主实现，`ServiceBridgeHost.enterOverlayMode()` 是空实现 | 连对目标（用 URL 的 `mode=` 区分），否则测的是另一个宿主 |
