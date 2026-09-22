<div align="center">

# Sakura 手机远程端

### 一个 [Sakura Desktop Pet](https://github.com/Rvosy/sakura) 的**插件 + 配套 App**

**给 Sakura 桌宠加一个手机屏幕：手机上看她的立绘、听她说话、和她聊天**

AI 计算和语音合成都留在电脑 · 手机只负责显示和播放

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Plugin API](https://img.shields.io/badge/Sakura%20Plugin%20API-v4-ff69b4.svg)](#环境要求)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg)](#环境要求)

[灵感来源](#灵感来源) · [功能](#功能) · [安装](#安装) · [使用](#使用) · [配置](#配置项) · [疑难解答](#疑难解答) · [技术说明](#技术说明) · [**已知问题**](#已知问题与未实现功能) · [鸣谢](#鸣谢)

</div>

---

## 先读这一段

**本项目不自带任何 AI 能力，它只是 [Sakura Desktop Pet](https://github.com/Rvosy/sakura) 的一个扩展。**

它由两部分组成，**都建立在 Sakura 之上**：

| 组成 | 是什么 | 装在哪 |
| :--- | :--- | :--- |
| **插件** | 一个跑在 Sakura 主进程里的插件，把手机接进来 | 电脑上，放进 Sakura 的 `plugins/user/` |
| **App** | 一个安卓 WebView 外壳，显示插件提供的手机页面 | 手机上 |

```
        必须先有它                     然后才有本项目的意义
┌──────────────────────────┐      ┌────────────────────────────┐
│  Sakura Desktop Pet      │  ◀── │  本项目的插件               │
│  github.com/Rvosy/sakura │      │  （跑在 Sakura 里面）        │
│  ─ 对话模型 / TTS / 记忆   │      └────────────────────────────┘
│  ─ 角色卡与立绘资源        │      ┌────────────────────────────┐
└──────────────────────────┘  ◀── │  本项目的 App               │
                                   │  （显示插件给的手机页面）     │
                                   └────────────────────────────┘
```

**所以：**

1. **先安装并跑通官方 Sakura 桌面端** —— 官方仓库 <https://github.com/Rvosy/sakura>，
   作者 **Rvosy**（[B 站](https://space.bilibili.com/441427122)）。
2. 再装本项目的插件和 App。
3. 模型、语音、角色、记忆**全部由 Sakura 提供**，本项目一行都没有实现。

> 本项目与 Sakura 官方**无隶属关系**，是第三方扩展。详见 [鸣谢](#鸣谢)。

---

## 装好之后是什么样

手机装上 App、电脑装好插件后，你就能在手机上看到她 —— **同一份记忆、同一个角色**，
但所有重活（模型推理、语音合成）都跑在电脑上：

```
┌─────────────────────────┐              ┌──────────────────────────┐
│        手机              │              │          电脑             │
│                         │   WiFi/组网   │                          │
│  ┌───────────────────┐  │  ──────────▶ │  ┌────────────────────┐  │
│  │ Sakura App        │  │              │  │ Sakura 主程序       │  │
│  │ ├ 立绘显示         │  │              │  │ ├ 对话模型          │  │
│  │ ├ 文字气泡         │  │  ◀────────── │  │ ├ TTS 语音合成      │  │
│  │ ├ 语音播放         │  │              │  │ ├ 长期记忆          │  │
│  │ └ 桌面立绘 / 小球   │  │              │  │ └ 手机远程端插件     │  │
│  └───────────────────┘  │              │  └────────────────────┘  │
└─────────────────────────┘              └──────────────────────────┘
```

**手机端做的只有「显示」和「播放」，所以不发烫也不耗电，换手机不用重配模型。**

> **注意**：电脑必须开着、Sakura 必须运行。这是远程串流，不是离线可用。

---

## 灵感来源

Sakura 官方自带一个可选的手机网页插件
[**`sakura_mobile`（手机聊天）**](https://github.com/Rvosy/sakura/tree/main/plugins/optional/sakura_mobile)，
作者 **pa1n9**。它把手机浏览器接入桌面端 Sakura 的同一条聊天、历史和角色链 ——
**本项目正是受它启发**，思路和技术路线都建立在它的基础之上：

| | 官方 `sakura_mobile` | 本项目 |
| :--- | :--- | :--- |
| 定位 | 手机浏览器里**聊天** | 手机上看**立绘**、听**语音**、还能当**桌宠** |
| 界面 | 网页（手机浏览器打开） | 网页 + 安卓 App 外壳 |
| 立绘 | 无 | 全屏立绘，按语气切换表情 |
| 语音 | 无 | 电脑合成、手机自动播放 |
| 桌面立绘 | 无 | 悬浮窗，可拖动、可缩成小球 |
| 依赖的宿主服务 | `sakura.host.mobile` / `artifacts` / `settings` | 同上，另加 `character` |

**它趟平了最关键的一段路**：证明了 `sakura.host.mobile` 这一层服务接口
（`start` / `begin` / `poll` / `cancel`、图片走 `sakura.host.artifacts` 传 descriptor）
足以让第三方插件把手机接进来，而完全不用碰 Core 内部。

本项目的「聊天提交、历史读取、图片上传」这几块，基本沿用了它验证过的做法。
如果你只想要「用手机聊天」，直接用官方那个插件就够了；本项目多了立绘、语音和桌宠。

> `sakura_mobile` 是可选插件，新用户不预装，需要在 Sakura 设置里的本地插件安装入口导入。

---

## 功能

| 功能 | 说明 |
| :--- | :--- |
| **对话** | 和电脑端同一个角色、**同一份记忆**，手机电脑看到的是同一段对话 |
| **立绘联动** | 按回复语气自动切换表情 |
| **语音** | 电脑合成、手机自动播放，下一段在你听上一段时就已合成好 |
| **图片** | 发相册图片或拍照，发送前显示文件名和缩略图 |
| **截屏** | 让角色「看到」手机屏幕内容 —— **尚未能用，见[已知问题](#已知问题与未实现功能)** |
| **桌面立绘** | 角色待在桌面上，可拖动、可缩放、**点透明处能点到桌面图标** |
| **圆形小球** | 收成一个小头像球，点一下展开 |
| **精细调节** | 角色大小/位置、字体大小、对话框位置，滑块 + 数字直输 |
| **远程重启** | 手机上直接重启电脑端 Sakura |

---

## 环境要求

### 电脑端

| 项目 | 要求 |
| :--- | :--- |
| Sakura | 支持 Plugin API **v4**（[官方下载](https://github.com/Rvosy/sakura/releases)） |
| 系统 | Windows 10 / 11（**远程重启**功能依赖 Windows 计划任务，仅 Windows 可用；其余功能理论上跨平台，但未验证） |

### 手机端：适用的安卓版本

App 的 `minSdkVersion = 24`、`targetSdkVersion = 36`，也就是：

| 安卓版本 | API | 支持情况 |
| :--- | :--- | :--- |
| **Android 14 / 15 / 16** | 34–36 | **推荐**。本项目就是在这类系统上开发和实测的（小米 Android 14） |
| Android 12 / 12L / 13 | 31–33 | 应该可用，**未实测**。这几版要求前台服务声明类型，项目已声明，但没在真机验证过 |
| Android 10 / 11 | 29–30 | **有风险，未实测**。代码里给前台服务传了 `specialUse` 类型，而该类型是 Android 14 才引入的，这两版可能校验失败 |
| Android 8.0 / 9 | 26–28 | **未实测**，理论上可用（前台服务无需声明类型） |
| Android 7.x | 24–25 | 最低支持，**未实测**。通知渠道等新特性会自动降级 |

> 一句话：**Android 10 及以上都可以试，Android 14+ 最稳。**
> 如果你在 Android 10–13 上遇到启动就闪退，多半就是上面那个前台服务类型问题，
> 欢迎到 [Issues](https://github.com/FHSLX/sakura-APP/issues) 反馈。

### 其他要求

| 项目 | 要求 |
| :--- | :--- |
| 网络 | 同一 WiFi，或组网工具（Tailscale / ZeroTier / 蒲公英） |
| 手机权限 | 悬浮窗（显示在其他应用上层）、通知、电池优化白名单 —— **都要手动授予** |

---

## 安装

### 先下载

两部分安装包都在 **[Releases 页面](https://github.com/FHSLX/sakura-APP/releases/latest)**：

| 文件 | 是什么 | 装在哪 |
| :--- | :--- | :--- |
| **`SakuraRemote-release.apk`** | 手机 App | 手机 |
| **`plugin-sakura.remote-1.0.0.zip`** | Sakura 插件 | 电脑 |
| `MANUAL.md` | 完整使用手册 | 参考 |

> **如果 Releases 里还没有附件**，说明发布包还没上传。两条出路：
>
> 1. **自己构建** —— 见下方[自己构建安装包](#自己构建安装包)。插件是纯 Python 标准库，
>    复制目录即可；APK 需要 Android SDK。
> 2. **到 [Issues](https://github.com/FHSLX/sakura-APP/issues) 问一下**，或者按
>    [Release 流程](#release-流程)自己打一个 tag 触发自动构建。

### 一、电脑端插件

把 `sakura_remote/` 复制到：

```
<Sakura安装目录>/plugins/user/sakura.remote/
```

**目录名必须是 `sakura.remote`**（它就是插件 id）。

然后 Sakura → 设置 → **手机远程端** → 启用。

### 二、手机端 App

把 `SakuraRemote-release.apk` 传到手机安装。

> 这是**自签名**的 release 包，安装时系统会提示「未知来源」，手动允许即可。
>
> 如果之前装过 debug 版，两者签名不同**不能覆盖安装**，要先卸载旧的。

用 adb 装也行：

```bash
adb install SakuraRemote-release.apk
```

### 自己构建安装包

Releases 里没附件时，或者你想自己改代码：

```bash
# 插件：sakura_remote/ 目录本身就是完整插件（纯 Python 标准库，零依赖）
# 直接复制到 <Sakura>/plugins/user/sakura.remote/ 即可

# App：需要 Android SDK（platform 36）和 JDK 17+
cd phone_app
npm install
npx cap sync android
build_apk.bat            # Windows；产物在 android/app/build/outputs/apk/
```

签名配置：把 `phone_app/android/keystore.properties.example` 复制成
`keystore.properties` 并填自己的密钥；**没有它也能构建**，只是出来的包未签名、
多数系统装不上。

### Release 流程

仓库里带了一个 GitHub Actions 工作流（`.github/workflows/release.yml`），
**打 tag 就会自动构建并发布**，不需要本地环境：

```bash
git tag -a v1.0.0 -m "首个版本"
git push origin v1.0.0
```

它会产出三个附件：APK、插件 zip、使用手册。

> CI 构建的 APK 是**未签名**的（仓库里不能放签名密钥），部分系统会拒绝安装。
> 想让 CI 出签名包，把密钥做成 Secrets 再在 workflow 里引用即可
> （`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）。

### 三、连接

1. Sakura 插件设置面板里复制**「手机直接打开这个地址」**
2. 手机浏览器打开该地址，或在 App 里填 `IP:端口` + token
3. 配置页 → 手机桌面 → **开启桌面立绘**（授悬浮窗权限）
4. 配置页 → 手机桌面 → **申请后台保活**

> **首次连接前请先改 token。** 默认值是 `sakura`，插件监听 `0.0.0.0`，
> 同网段设备都能访问。改成足够长的随机串，并且不要映射到公网。

---

## 使用

### 桌面立绘上的手势

| 操作 | 效果 |
| :--- | :--- |
| 拖动 | 移动位置 |
| 双指捏合 | 缩放大小 |
| 长按 5 秒 | 打开设置 |
| 轻点**透明区域** | 穿透到下层桌面图标 |

### 圆形小球

输入栏上方 **⊙ 缩小**，或配置页 → 手机桌面 → 缩小成圆形小球。

| 操作 | 效果 |
| :--- | :--- |
| 轻点 | 恢复完整窗口 |
| 长按 1.5 秒 | 打开设置 |
| 拖动 | 移动小球 |

### 图片与截屏

点输入栏左边 **＋** → 选择图片 / 截取屏幕。

已选内容会显示 **缩略图 + 文件名大小 + 截图序号**，让你知道选了什么、截了几张。

---

## 配置项

配置文件：`<Sakura>/data/plugins/sakura.remote/config.json`

| 字段 | 默认 | 说明 |
| :--- | :--- | :--- |
| `enabled` | `false` | 是否启用 |
| `host` | `0.0.0.0` | 监听地址，手机要用 WiFi 连必须保持此值 |
| `port` | `8770` | 监听端口 |
| `token` | `sakura` | **访问口令，请务必修改** |
| `autoplay` | `true` | 收到回复自动朗读 |
| `tts_enabled` | `true` | 允许电脑端合成语音 |

手机端配置页还可以调（存在手机本地）：

- **显示**：角色大小、立绘上下位置、浮动开关、字体大小、对话框位置、双语字幕开关、自动隐藏秒数
- **语音**：自动朗读、允许合成
- **角色**：查看/切换电脑端角色

---

## 疑难解答

<details>
<summary><b>连不上电脑</b></summary>

按顺序检查：

1. 手机和电脑在**同一个 WiFi**
2. 电脑上 Sakura **正在运行**
3. 插件「启用手机远程端」是开
4. 监听地址是 **`0.0.0.0`**
5. **电脑防火墙**放行 8770 端口
6. token 完全一致（区分大小写，注意别多复制空格）

</details>

<details>
<summary><b>有文字但没有立绘</b></summary>

角色资源不完整。检查电脑上 `<Sakura>/characters/<角色id>/` 里有没有立绘 PNG。

</details>

<details>
<summary><b>桌面立绘打不开</b></summary>

1. 悬浮窗权限是否授予（系统设置 → 应用 → Sakura → 显示在其他应用上层）
2. 通知权限是否授予
3. 小米/红米还要开「后台弹出界面」

</details>

<details>
<summary><b>点了截屏没反应</b></summary>

第一次会弹**屏幕录制授权**对话框，需要点「立即开始」。
授权对话框如果在后台看不到，先切回 App 再点一次。

</details>

<details>
<summary><b>语音不出声</b></summary>

1. 先**轻触一下屏幕**解锁音频 —— 手机浏览器规定必须有用户交互才允许播放声音，这是系统限制
2. 检查「收到回复自动朗读」是否开着
3. 检查手机媒体音量

</details>

<details>
<summary><b>桌宠过一会儿消失了</b></summary>

省电策略把它清理了。去配置页点「申请后台保活」，
并在系统设置里把 Sakura 的电池策略设为**「无限制」**。

</details>

<details>
<summary><b>切换角色后没生效</b></summary>

切换角色是改电脑端配置文件，而电脑端**启动后就不再读它**。
所以需要重启 Sakura —— 用配置页的「重启电脑端 Sakura」按钮。

</details>

---

## 技术说明

### 架构

| 层 | 技术 | 说明 |
| :--- | :--- | :--- |
| 插件 | Python 3 + 标准库 | `ThreadingHTTPServer`，**零第三方依赖**，部署即用 |
| 前端 | 原生 JS + CSS | 无框架、无构建步骤，改完刷新即可 |
| App | Capacitor + Java | WebView 外壳 + 悬浮窗服务 |
| 通信 | HTTP + Token | 立绘 PNG / WAV 音频 / JSON |

### 为什么手机这么省电

所有 AI 计算和 TTS 都在电脑端。手机只做三件事：接收立绘和文字、播放音频、绘制界面。

### 带宽

| 场景 | 平均带宽 |
| :--- | :--- |
| 只看立绘 + 打字 | 几乎为 0 |
| 语音对话（说话占约 30%） | 约 150 kbps |
| 语音几乎不停 | 512 kbps |
| 首次进入 | 瞬时 2–3 MB（之后走缓存） |

**3 Mbps 以上很舒服，1 Mbps 勉强可用。**

立绘 PNG 平均 1.36 MB，只下载一次并缓存；语音是 32 kHz 单声道 WAV，64 KB/s。

### 数据流

```
手机输入文字/图片
   ↓
插件 POST /api/chat
   ↓
sakura.host.mobile  →  电脑端对话模型（含长期记忆）
   ↓ 返回带语气的分段回复
插件按语气映射立绘 → 手机显示
   ↓
每段日文原文 + 语气 → 电脑端 TTS → WAV 回传 → 手机播放
```

### 开发时最容易踩的两个坑

- **Android 14 起 MediaProjection 必须跑在 `mediaProjection` 类型的前台服务里**，
  在 Activity 回调里直接建 VirtualDisplay 会失败。见 [phone_app/README.md](phone_app/README.md)
- **`screen.width` 和 `window.innerWidth` 不是一回事**：悬浮窗的实际视口可能比屏幕窄
  （实测屏幕 393 而窗口 319），混用会把控件挤出屏幕。见 [sakura_remote/README.md](sakura_remote/README.md)

---

## 项目结构

```
.
├── sakura_remote/          Sakura 插件（Python + 前端）
│   ├── plugin.py           插件入口、设置面板注册
│   ├── http_server.py      HTTP 服务、路由、业务逻辑
│   ├── web_ui.py           手机页面模板
│   ├── restart_helper.ps1  远程重启脚本
│   └── static/             前端资源（app.js / app.css）
├── phone_app/              Android WebView 外壳（Capacitor）
│   └── android/app/src/main/java/com/sakura/remote/
│       ├── MainActivity.java    设置页 + 截图授权
│       ├── OverlayService.java  桌面立绘悬浮窗
│       ├── CaptureService.java  截屏前台服务
│       ├── PetWindow.java       悬浮窗 WebView
│       ├── RemoteBridge.java    JS ↔ Java 桥
│       └── PetConfig.java       手机本地配置
├── docs/                   使用手册
├── tools/                  开发与发布工具
└── tests/                  回归测试
```

---

## 开发

```bash
# 插件的回归测试（无需 Sakura 运行）
python tests/fake_host_smoke.py
python tests/plugin_runtime_smoke.py
node tests/bubble_render_check.js
node tests/portrait_scale_check.js
node tests/tone_portrait_check.js

# 把插件装进本机 Sakura
powershell -File tools/install_to_sakura.ps1

# 构建发布包（含隐私扫描）
powershell -File tools/build_release.ps1
```

### 关于代码编写方式

本项目的代码是**借助 DeepSeek 模型辅助编写**的（对话式结对：由人提出需求、判断方案取舍、
在真机上验证，模型负责查宿主 API、写实现、排查问题与补测试）。

需要说明的是，这不代表代码没有经过验证：项目里每一处行为都在真机上实测过，
关键结论都记在文档的「踩过的坑」里（包括若干只有实机才会暴露的问题，
比如 Android 14 的 MediaProjection 前台服务要求、
`Path.resolve()` 的 `\\?\` 前缀会让 PowerShell 脚本静默失败等）。
但**辅助编写仍可能留有疏漏**，请以实测为准；发现问题欢迎提 Issue。

---

## 已知问题与未实现功能

**这一节如实记录目前还没做好、或还没验证的地方**，避免你装完才发现。
带「已验证」标记的是我在真机上跑通过、有实测数据的。

### 未实现 / 不能用的功能

| 项目 | 状态 | 说明 |
| :--- | :--- | :--- |
| **截屏** | **不能用** | 点「截取屏幕」后，系统那道屏幕录制授权框**不弹出来**。代码链路是完整的（`CaptureService` 前台服务、`mediaProjection` 类型、权限声明都在），判断是 MIUI「应用正在显示悬浮窗时弹系统确认框」的限制，但**没在真机上确认成功过**，所以标为不可用 |
| 从桌面立绘退回 App | 只能手动 | 点通知栏，或重新打开 App。没有「点小球回 App」这类入口 |
| 主题跟随 | 部分 | Sakura 的主题色会用到，但桌面立绘的窗口材质不跟随 |
| 消息数量上限 | 设计如此 | 手机端历史最多保留 60 条（`trimBubbles`），完整历史在电脑端 |

### 已实现但只在单机上验证过

以下功能我在**一台设备**（小米 22041216C / Android 14 / MIUI）上实测通过，
**没有在其他机型或 Android 版本上验证**：

| 功能 | 实测结果 |
| :--- | :--- |
| 桌面立绘悬浮窗 | 已验证：877×1766 窗口，可拖动、按帧节流 |
| 圆形小球 | 已验证：缩到 154×154，轻点可恢复 |
| 点击穿透 | 已验证逻辑：透明处 `alpha=0` 会穿透，控件处被拦下 |
| 对话框自动隐藏 | 已验证：隐藏后立绘补位，间距 93px → 28px |
| 远程重启 | 已验证：手机触发 → 电脑重启 → 角色切换生效 |
| 角色切换 | 已验证（**需重启 Sakura 才生效**） |

**未验证的手势**（依赖真实多指/长按时序，无法用调试协议合成）：
长按拖动、长按 5 秒开设置、双指捏合缩放。

### 已知缺陷

| 现象 | 影响 | 备注 |
| :--- | :--- | :--- |
| 从 App 进桌面立绘时，约 0.8 秒内两者同时可见 | 轻微重影 | 为了先让前台服务起来再收 App，故意延后；正常使用感知很弱 |
| 悬浮窗宽度不会自动撑满屏幕 | 右侧可能留白 | 手机端保存了旧尺寸，清一次 App 数据可恢复 |
| 切换角色后必须重启 Sakura | 多一步操作 | 宿主只在启动时读角色配置，插件无法绕过 |
| 重启功能依赖 Windows 计划任务 | 被安全软件拦截会失败 | 日志里会写 `CRITICAL`，需手动启动 Sakura |

### 没做过的验证

- **全新安装**：电脑端一个角色都没有的情况下，配置页可用性只做了自动化测试，没在真机走一遍
- **大屏设备**：13 寸平板的配置页布局做过无头浏览器截图验证，没在真机上验证
- **长时间运行**：没有跑过 24 小时以上的稳定性测试
- **多设备同时连**：插件支持多连接，但没验证过两个手机同时用的表现

---

## 贡献

欢迎提 Issue 和 PR。改前端不需要构建步骤 —— 改完 `static/` 下的文件刷新页面即可
（插件会按文件修改时间自动加版本号，不会吃到旧缓存）。

**如果你修好了上面某个问题，特别欢迎 PR** —— 尤其是截屏那一项。

---

## 鸣谢

### Sakura Desktop Pet（本项目的基础）

本项目能够存在，完全依赖 [Rvosy](https://github.com/Rvosy) 开发的
**[Sakura Desktop Pet](https://github.com/Rvosy/sakura)** ——
一个能主动感知屏幕内容与系统事件的通用桌宠 Agent 框架。

- **项目地址**：<https://github.com/Rvosy/sakura>
- **作者**：Rvosy
- **B 站**：<https://space.bilibili.com/441427122>
- **许可**：MIT License，Copyright © 2026 Rvosy

感谢作者把插件系统设计得足够开放：`sakura.host.*` 这一层服务接口让第三方能在
不修改宿主体的情况下接入聊天、角色资源、语音与主题。没有这套设计，
「手机远程端」这种玩法根本无从实现。

> 角色「夜乃桜（Sakura）」及相关立绘、语音资源的版权属于其原作者，
> 本项目只做技术演示，不包含、也不分发任何角色资源。

### 官方手机网页插件（本项目的灵感来源）

Sakura 自带的 [`sakura_mobile`（手机聊天）](https://github.com/Rvosy/sakura/tree/main/plugins/optional/sakura_mobile)
由 **pa1n9** 开发。本项目受它启发，沿用了它验证过的
`sakura.host.mobile` 聊天链路与 `sakura.host.artifacts` 图片传递方式。
详见上文[灵感来源](#灵感来源)。

### 其他

- 感谢 [Shinsekai](https://github.com/RachelForster/Shinsekai) 项目 ——
  它在桌宠与插件生态上的探索，间接影响了 Sakura 的设计取向。
- 感谢 **DeepSeek** 在代码编写过程中提供的辅助（见上一节）。

---

## 许可证

[MIT](LICENSE)

---

<div align="center">
<sub>本项目是第三方插件，与 Sakura 官方无隶属关系。所有权利归各自作者所有。</sub>
</div>
