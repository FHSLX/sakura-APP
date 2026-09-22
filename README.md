<div align="center">

# Sakura 手机远程端

**把手机变成 Sakura 桌宠的第二个屏幕**

AI 计算和语音合成都留在电脑 · 手机只负责显示和播放

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Plugin API](https://img.shields.io/badge/Sakura%20Plugin%20API-v4-ff69b4.svg)](#环境要求)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg)](#环境要求)

[功能](#功能) · [安装](#安装) · [使用](#使用) · [配置](#配置项) · [疑难解答](#疑难解答) · [技术说明](#技术说明) · [鸣谢](#鸣谢)

</div>

---

> **这是 [Sakura Desktop Pet](https://github.com/Rvosy/sakura) 的第三方插件 + 配套 App，不是官方项目。**
> 使用前请先安装并跑通官方的 Sakura 桌面端。
> 官方仓库：[**github.com/Rvosy/sakura**](https://github.com/Rvosy/sakura)

---

## 这是什么

Sakura 是一个桌面 AI 伴侣。这个项目让你能在手机上看到她 —— **同一份记忆、同一个角色**，
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

**所以手机不发烫、不耗电，换手机也不用重新配置模型。**

> ⚠️ 电脑必须开着、Sakura 必须运行。这是远程串流，不是离线可用。

---

## 功能

| 功能 | 说明 |
| :--- | :--- |
| 💬 **对话** | 和电脑端同一个角色、**同一份记忆**，手机电脑看到的是同一段对话 |
| 🎭 **立绘联动** | 按回复语气自动切换表情 |
| 🔊 **语音** | 电脑合成、手机自动播放，下一段在你听上一段时就已合成好 |
| 🖼️ **图片** | 发相册图片或拍照，发送前显示文件名和缩略图 |
| 📸 **截屏** | 让角色「看到」你手机屏幕上的内容（自动隐藏桌宠，不拍到自己） |
| 🐾 **桌面立绘** | 角色待在桌面上，可拖动、可缩放、**点透明处能点到桌面图标** |
| 🔵 **圆形小球** | 收成一个小头像球，点一下展开 |
| 🎚️ **精细调节** | 角色大小/位置、字体大小、对话框位置，滑块 + 数字直输 |
| 🔄 **远程重启** | 手机上直接重启电脑端 Sakura |

---

## 环境要求

| 项目 | 要求 |
| :--- | :--- |
| Sakura | 支持 Plugin API **v4** |
| 电脑 | Windows（远程重启功能依赖 Windows 计划任务） |
| 手机 | Android **8.0+** |
| 网络 | 同一 WiFi，或组网工具（Tailscale / ZeroTier / 蒲公英） |

---

## 安装

### 一、电脑端插件

把 `sakura_remote/` 复制到：

```
<Sakura安装目录>/plugins/user/sakura.remote/
```

**目录名必须是 `sakura.remote`**（它就是插件 id）。

然后 Sakura → 设置 → **手机远程端** → 启用。

### 二、手机端 App

```bash
# 用预编译的 APK（推荐）
adb install SakuraRemote-release.apk

# 或自己构建
cd phone_app
npm install
npx cap sync android
build_apk.bat
```

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

### 一些踩过的坑

项目里记录了若干**真实踩坑过程**，对想改这个项目的人应该有用：

- [为什么点击穿透要按像素判断](sakura_remote/README.md#点击穿透透明处让给桌面)
- [为什么远程重启要分两段计划任务](sakura_remote/README.md#远程重启)
- [为什么卡片会压住输入栏](sakura_remote/README.md#宽度区分目标宽度和当前视口)
- [Android 14 的 MediaProjection 前台服务要求](phone_app/README.md)

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

## 贡献

欢迎提 Issue 和 PR。改前端不需要构建步骤 —— 改完 `static/` 下的文件刷新页面即可
（插件会按文件修改时间自动加版本号，不会吃到旧缓存）。

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
