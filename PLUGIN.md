# sakura_remote — 手机远程端插件

把手机变成 Sakura 桌宠的第二个屏幕：**AI 计算和语音合成全部留在电脑**，
手机只负责显示立绘、文字和播放语音。

```
手机（浏览器 / WebView App）
   │  立绘 PNG、WAV 语音、文字分段、图片上传
   ▼
sakura.remote 插件（跑在 Sakura 主进程的插件 Worker 里）
   ├── sakura.host.mobile     → 聊天、历史、主题
   ├── sakura.host.character  → 解析当前角色包里的立绘路径
   └── sakura.tts             → 合成语音（返回 audio/wav artifact）
```

## 功能

- **立绘联动**：手机全屏显示当前角色立绘，按回复的 `tone`（语气）自动切换表情。
- **角色大小可调**：设置面板里的滑块，或直接在立绘上双指捏合，范围 50%–160%。
  选择会存在本机，装成 App 时还会同步给桌面悬浮窗，两边一致。
- **手机桌面立绘**：角色能待在手机桌面上（悬浮窗实现），可拖动位置、双指缩放，轻点回到聊天。
  这是 Android 上唯一能让应用内容显示在桌面之上的方式。
- **后台保活**：前台服务 + 常驻通知 + 电池优化白名单，返回桌面或切到别的应用后依然存活。
- **分段对话**：模型回复的每个句段依次出现在气泡里，中文为主体、日文原文缩小作参考。
- **语音播放**：每段文字用 `raw_content`（日文原文）+ `tone` 送电脑端 TTS 合成，
  合成的 WAV 回传手机自动播放；下一段在上一段播放时就已经合成好，形成连续说话感。
- **图片消息**：手机拍照/相册图片先上传到插件缓存目录，再由插件转成 data URL 交给宿主。
- **同一份记忆**：聊天、历史、长期记忆都走桌面端同一条链路，手机和电脑看到的是同一段对话。

## 安装

插件不修改 Sakura 任何现有文件，装成两个互不干扰的目录：

```powershell
# 一键安装（在仓库根目录执行，会备份 plugins.yaml）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\install_to_sakura.ps1
```

| 目录 | 内容 | 升级插件时 |
| --- | --- | --- |
| `<Sakura>\plugins\user\sakura.remote\` | 插件代码 | 会被覆盖 |
| `<Sakura>\data\plugins\sakura.remote\` | 用户配置、日志、缓存 | 保留 |

> **注意**：用户配置必须写在 `data\plugins\<id>\config.json`。
> Sakura 的 `PluginConfig` 会把代码目录的 `config.json` 与用户目录的 `config.json` 合并，
> 且**用户目录优先**；所以安装脚本刻意不往代码目录放 `config.json`，
> 免得每次更新插件都把用户的端口和 token 冲掉。

手动安装就是上面两条 `xcopy` 的等价操作，注意 `config.json` 要放到 `data` 那一侧。

然后在 `<Sakura>\config\plugins.yaml` 里让插件处于启用状态（安装脚本会自动处理）：

```yaml
- id: sakura.remote
  enabled: true
```

`plugins.yaml` 是「期望状态」，**优先级高于插件清单里的 `enabled`**，所以这里必须是 `true`。

重启 Sakura 后，在设置面板的「手机远程端」里确认开关已打开、端口和 token 正确，保存即可。

> 插件的默认 `config.json` 里 `enabled` 是 `false`，需要显式打开，避免装完就监听端口。

手机浏览器打开（token 换成你自己的）：

```
http://电脑IP:8770/?token=你的token
```

端口默认 `8770`，和官方「手机聊天」插件的 `8765` 不冲突，两个可以同时开。

## 设置项

| 字段 | 说明 |
| --- | --- |
| 启用手机远程端 | 是否启动 HTTP 服务 |
| 监听地址 | `127.0.0.1` 只本机（配合 Tailscale Serve 最安全）；`0.0.0.0` 供局域网访问 |
| 端口 | 默认 8770 |
| 访问 token | 网页和 API 的口令，正式使用务必改掉默认值 |
| 自动朗读语音 | 关掉则只在手机显示文字 |
| 合成语音 | 关掉则完全不请求 TTS，手机只显示文字和立绘 |

## 立绘与语气的对应

语气名和立绘名不一定能直接对上。插件按「完全同名 → 包含关系 → 单字重合」打分匹配，
匹配不到就回落到默认立绘。你也可以手工指定：

在插件数据目录里建 `portrait_map.json`（也就是 `<Sakura>\data\plugins\sakura.remote\portrait_map.json`）：

```json
{
  "中性": "站立待机",
  "不满": "不满无语",
  "惊讶": "两眼放光"
}
```

key 是语气名，value 必须是角色 `character.json` 里 `portrait.expressions` 的 key。
这份映射优先于自动匹配，但只在该 key 真的存在时生效。

## HTTP 接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/` | 手机网页 |
| GET | `/app.css` `/app.js` `/manifest.webmanifest` | 静态资源 |
| GET | `/api/status` | 服务状态 |
| GET | `/api/state` | 当前角色、立绘清单、主题色、语音设置 |
| GET | `/api/history?character_id=&limit=` | 历史消息 |
| GET | `/asset/portrait?character=&key=` | 立绘 PNG（带 ETag，浏览器会缓存） |
| GET | `/cache/uploads/<file>` | 已上传的图片 |
| POST | `/api/chat` | `{character_id, text, image_url?}` → 分段回复 |
| POST | `/api/tts` | `{character_id, text, tone?}` → `audio/wav` |
| POST | `/api/upload` | `{media_type, data(base64)}` → `{url}` |

所有接口都要 token：查询参数、JSON 体里的 `token`，或 `X-Sakura-Remote-Token` 请求头。

限制：请求体 16 MiB、图片 8 MiB、并发 12、每客户端每分钟 240 次请求、socket 超时 30 秒。

## 日志

- 插件日志：Sakura 的插件日志面板（事件名如 `server_created`）
- 访问日志：`<Sakura>\data\plugins\sakura.remote\logs\remote-access.log`

## 与宿主交互时踩过的坑

这几条都是在本机真机联调时实际踩到并修掉的，改代码时请留意。

### 1. `sakura.host.mobile` 的参数形状不统一

框架不会自动注入调用方插件 ID（`runtime_v4._route_service_call` 里是 `callback(*args)`），
但 `sakura.host.mobile` 的部分方法把插件 ID 作为**第一个位置参数**：

| 方法 | 正确调用 |
| --- | --- |
| `characters()` | 无参数 |
| `history(character_id, limit)` | 无 plugin_id |
| `begin(plugin_id, character_id, text, artifact)` | **要 plugin_id** |
| `poll(plugin_id, job_id)` | **要 plugin_id** |
| `cancel(plugin_id, job_id)` | **要 plugin_id** |

漏掉 plugin_id 会让参数整体左移一位：`character_id` 收到消息正文，宿主报
`MOBILE_CHARACTER_NOT_CURRENT`，而且失败发生在调模型之前（0 秒返回）。
`tests/plugin_runtime_smoke.py` 的桩件刻意复刻了这个签名，签名写错会被测试抓住。

### 2. 插件配置必须是「无 BOM」的 UTF-8

Sakura 用 `json.loads(path.read_text(encoding="utf-8"))` 读配置，
带 UTF-8 BOM 会直接抛 `PLUGIN_CONFIG_INVALID / Unexpected UTF-8 BOM`，插件启动失败。

> PowerShell 的 `Set-Content -Encoding UTF8` 在 Windows PowerShell 5.1 下**会写 BOM**。
> 要写无 BOM 文件请用：`[IO.File]::WriteAllText($path, $json, [Text.UTF8Encoding]::new($false))`，
> 或者用 `Set-Content -Encoding utf8NoBOM`（PowerShell 7+）。

### 3. 用户配置必须放 `data\plugins\<id>\config.json`

`PluginConfig.get()` 会把「插件代码目录的 `config.json`」与「用户数据目录的 `config.json`」
合并，且**用户目录优先**。所以安装脚本刻意不往代码目录放 `config.json`，
否则每次更新插件都会把用户的端口和 token 冲掉。

### 4. 静态资源不能要求 token，也不能用长 max-age

`<link>`、`<script>`、`manifest` 三个请求由浏览器直接发起，**不会带查询参数**，
所以 `/app.css`、`/app.js`、`/manifest.webmanifest` 必须免 token。
立绘 URL 是服务端拼的，`<img src>` 同样不会经过前端的 `api()` 包装，
因此 token 要在**服务端**拼进 URL。

静态资源用 **`no-cache` + ETag 协商缓存**，不要用 `max-age`：
用 `max-age=300` 时，改完 `app.js` 手机最多要等 5 分钟才生效，
排查时极易被误判成「代码没部署」。协商缓存没改返回 304 依然省流量。

### 5. 缩放不要用 height/width，也不要和动画共用 transform

立绘元素带着常驻的呼吸动画。实测（Android WebView / Chromium）：

- 带运行中 CSS 动画的元素，改 `height`/`width`（**即使 `!important`**）在视觉上不生效 ——
  合成层接管了尺寸，`getComputedStyle` 也会返回陈旧值。
- 把缩放和动画都写在同一个元素的 `transform` 上，动画会覆盖缩放值。

所以结构拆成两层：**外层 `#portraitWrap` 负责 `translateX(-50%) scale(var(--pet-scale))`，
内层 `#portrait` 只跑 `breathe`/`speak` 动画**。
`tests/portrait_scale_check.js` 会断言这个结构不被改回去。

### 6. 息屏时不要相信真机测量

Android 在息屏后 WebView 停止渲染，此时通过 DevTools 读到的
`getComputedStyle` / `getBoundingClientRect` 都是**陈旧值**，
`adb shell screencap` 也只能拿到黑屏。排查渲染问题前先确认
`dumpsys power | grep mWakefulness` 是 `Awake`。

## 手机端配置页

页面右上角「设置」打开配置页，分五块：

| 区块 | 内容 |
| --- | --- |
| 连接 | 当前电脑地址、token（打码显示）、当前角色、语音合成是否可用 |
| 显示 | 角色大小滑块（50%–160%）、显示/隐藏日文原文、新消息自动滚动 |
| 语音 | 自动朗读、允许电脑合成语音 → 保存到电脑端 |
| 手机桌面 | 开启/关闭桌面立绘、申请后台保活（仅 App 内可用） |
| 怎么配置 | 软件内操作说明，五步走完就能用 |

也可以用 `?config=1` 直接展开配置页，方便把「怎么配置」发给别人看。

### 大屏适配

13 寸平板（横屏约 1024–1400 CSS px）和普通手机共用同一套页面，靠媒体查询切换：

| 视口宽度 | 布局 |
| --- | --- |
| < 760px | 单列卡片，铺满屏 |
| ≥ 760px | 两列卡片，正文放大到 14px |
| ≥ 1100px | 三列卡片，正文 14.5px、行高 1.75，留白加大 |
| ≥ 1000px | 立绘靠左（26%），气泡与输入栏收到右侧，避免角色被完全遮住 |

配置页是整屏浮层，`#configBody` 显式设了 `min-width: 0`、
grid 用 `minmax(0, 1fr)` —— 否则长内容（token、IP）会把列撑出视口，
表现为右侧卡片被裁掉。

### 验证大屏布局

用电脑上的 Edge/Chrome 以平板尺寸真实渲染并截图即可，例如：

```powershell
msedge --headless=new --screenshot=out.png --window-size=1280,900 `
  "http://127.0.0.1:8770/?token=<你的 token>&config=1"
```

加 `?config=1` 会直接展开配置页，方便验证布局。

注意：无头浏览器的窗口有**最小宽度**（约 500px），
`--window-size=430,900` 截出来的图虽然 430 宽，但 CSS 视口更窄，
会让人误判成「右侧被裁」。要验证窄屏建议用 iframe 把视口宽度钉准，
而不是直接调小窗口。

## 排查

| 现象 | 原因与处理 |
| --- | --- |
| 手机打不开网页 | 确认 Sakura 在运行、插件已启用、设置里开关已打开；看访问日志有没有请求进来 |
| 只能显示文字没有声音 | 先点一下屏幕（浏览器要求用户手势才允许播放）；确认「合成语音」开着、Sakura 里 TTS 引擎正常 |
| 语音报「语音文件已失效」 | TTS artifact 被回收，重新发一条消息即可 |
| 立绘一直是同一张 | 在 `portrait_map.json` 里手工指定语气对应的立绘 |
| 端口被占用 | 换一个端口，例如 8771，保存后插件会重启服务 |
| 外网访问 | 不要直接映射端口；用 `tailscale serve --bg --http=8770 127.0.0.1:8770` |
| 聊天报 `MOBILE_CHARACTER_NOT_CURRENT` | 检查 `begin/poll/cancel` 是否传了 plugin_id（见上文第 1 条） |
| 插件启动报 `PLUGIN_CONFIG_INVALID` | 配置文件带了 UTF-8 BOM（见上文第 2 条） |
| 启动时报 `PLUGIN_DEPENDENCY_INSTALL_FAILED` | 插件依赖目录里有**重复包版本**（安装被打断的残骸），uv 覆盖安装会返回非 0。用 uv 对同一个 `--target` 重跑一次 `pip install --requirements` 即可修复 |

## 排查「角色不是当前角色」

聊天接口报 `MOBILE_CHARACTER_NOT_CURRENT` 时，按这个顺序查：

1. **参数形状**：`sakura.host.mobile` 的 `begin/poll/cancel` 需要把插件 id
   作为**第一个位置参数**（见上文第 1 条）。漏了它，后面的参数会整体错位，
   `character_id` 收到的其实是消息文本。
2. **角色是否一致**：`blame` 落在会话角色上时，用配置页的「角色」卡片
   确认电脑端当前是哪个角色 —— 手机端只能和当前角色对话。
3. **日志**：`<Sakura>/data/plugins/sakura.remote/logs/remote-access.log`
   记录了每个请求的路径、状态码和来源，先看有没有 400/404。

> 早期版本有个 `GET /api/debug` 诊断端点，会输出插件上下文类型和角色详情。
> 它属于排查用的临时接口、暴露面偏大，**已在发布版中移除**。

## 已知约束

- **电脑必须开着、Sakura 必须运行**，这是远程串流，不是离线可用。
- 语音延迟等于 TTS 合成时间（本机实测每段 3–6 秒），文字和立绘是立刻出现的。
- 语音走 32 kHz 单声道 WAV，一段 10 秒约 640 KB，局域网和 4G 都没问题。
- TTS 结果通过 Plugin API 的 opaque artifact 描述符返回，插件是按
  `data/cache/plugin-artifacts/<generation>/<plugin>/<artifactId>/payload.wav`
  这一固定布局反查文件的。Sakura 若改动该布局，需要同步调整
  `http_server.py` 的 `_locate_artifact`。

## 手机联调

手机不在身边也能验证，但真机调试能省很多来回。两种方式：

**方式一：什么都不装（最快）**
手机和电脑连同一个 WiFi，浏览器打开 `http://192.168.1.100:8770/?token=你的token`。

**方式二：USB + adb（能看到手机画面和报错）**

1. 手机开「开发者选项」→「USB 调试」，USB 连电脑并在手机上点「允许」；
2. 下载 [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)，
   把 `platform-tools` 目录放到 `tools\platform-tools`；
3. 运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\connect_phone.ps1
```

脚本会建立 Chrome 调试端口隧道并列出手机上的页面，还可以用
`adb exec-out screencap -p > phone.png` 把手机画面截到电脑。

## 开发与验证

```powershell
# 纯桩验证：不依赖 Sakura，验证 HTTP 协议、立绘、TTS、上传（16 项）
<Sakura>\python\python.exe tests\fake_host_smoke.py

# 按插件运行时方式加载，验证 setup/start/stop 与配置变更（11 项）
<Sakura>\python\python.exe tests\plugin_runtime_smoke.py

# 验证语气→立绘匹配（用本机真实角色数据）
node tests\tone_portrait_check.js

# 验证桌宠式双行字幕渲染
node tests\bubble_render_check.js

# 验证插件能在 Sakura 的隔离运行时里加载
<Sakura>\python\python.exe -I -S `
  <Sakura>\core\app\plugins\plugin_runner_v4.py `
  --plugin-id sakura.remote --generation-id gen-test `
  --plugin-root <本目录>\sakura_remote `
  --data-dir <临时目录> --entry plugin:SakuraRemotePlugin --validate-entry
```

## 发布打包

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_release.ps1
```

脚本会：

1. 复制插件代码，排除 `__pycache__`、`*.pyc`；
2. 带上 release APK 和**已脱敏的** `tools\release_README.md`；
3. 跑隐私扫描：命中机器特征串（个人 token、局域网 IP、安装路径、用户名）
   **就失败并保留产物供检查**；
4. 打成 `plugin-sakura.remote-<版本>.zip` 和 `App-SakuraRemote.zip`。

**不要手工挑选文件** —— 我漏过一次：改了 `phone_app/www/index.html` 却忘了
`npx cap sync android`，结果 APK 里的 `assets/public/` 还是旧版本，
带着硬编码的局域网 IP。**改完 `www/` 必须 `cap sync` 再构建 APK。**

`tools\build_release.ps1` 必须保持**纯 ASCII**：Windows PowerShell 5.1
会把无 BOM 的 UTF-8 当 ANSI 读，中文注释会让脚本解析失败。

## 远程重启

配置页的「角色」卡片里有「重启电脑端 Sakura」按钮。切换角色后需要重启才生效，
所以这个按钮是配套的。

### 为什么必须重启

`config/characters.yaml` 只在启动时读一次，运行中的会话把角色锁在内存里。
实测：改完配置后用新角色聊天仍然报 `MOBILE_CHARACTER_NOT_CURRENT`，
而重启后就正常了。

### 实现：两段式计划任务

这是本项目里最折腾的一段，把踩过的坑记下来：

**1. helper 必须完全独立于 Sakura。** Sakura 退出会带走自己的子进程。
试过三种方式，**都失败**：

| 方式 | 结果 |
| --- | --- |
| `CREATE_NEW_PROCESS_GROUP` | 不够，helper 被一起带走，停在「刚关闭旧进程」 |
| `DETACHED_PROCESS` | 子进程启动即失败，日志全空（用最小脚本复现确认） |
| `cmd start /b` | 同样日志全空 |

**2. 用计划任务，但要分两段。** Task Scheduler 在 action 结束时会把
**它创建的整棵进程树**清掉 —— 所以「关闭旧进程」和「启动新进程」必须放在
两个不同的任务里，第二个任务有自己的进程树，它启动的 Sakura 才能活下来。

**3. 不要用 `schtasks.exe`。** 它的 `/TR` 是单个字符串，嵌套引号跨 shell
转义极其脆弱。实测 `\"` 写法会让它把 `-NoProfile` 当成独立参数并报
`Invalid argument/option - '-NoProfile'`。改用 PowerShell 的
`New-ScheduledTaskAction -Argument`（独立参数，不用转义）。

**4. `/TR` 有 261 字符上限。** 四个路径内联进去会超（实测 269），
所以参数改成写进 `restart-args.json`，任务命令行保持短且固定。

**5. `Path.resolve()` 会毁掉一切。** Windows 上它给路径加 `\\?\` 前缀，
而 PowerShell 的 `-File` / `Start-Process` 不接受这种路径 —— 脚本会静默退出。
`manifest_path()` 就是为剥离它而存在的。

### 实测

```
重启请求: HTTP 200
第 15 秒恢复
进程重启过: True（进程启动时刻变了）
小黑会话可用 → 角色切换生效
```

日志在 `<Sakura>\data\plugins\sakura.remote\logs\restart-*.log`。
若出现 `CRITICAL: ... needs manual start`，说明自动拉起失败，需要手动启动。

## 角色清单与全新安装

### 角色清单是**动态读取**的，没有任何写死

链路：

```
电脑端 <Sakura>/characters/*/character.json
  → CharacterRegistry(...).profiles      （宿主扫目录）
  → sakura.host.mobile 的 characters()    （宿主服务）
  → 插件 /api/characters                  （原样透传）
```

插件里不存在角色名或 id 的硬编码清单，**加/删角色会自动反映到手机端**。
唯一的例外是测试里用作示例的 id，与产品代码无关。

### 全新安装（电脑端还没有角色）

这是最容易出事的状态：宿主在无角色时会抛 `ASSISTANT_NOT_READY`，
而手机端 `loadState()` 一旦失败就整页显示「无法连接电脑端」——
**连配置页都打不开，用户没法自救**（改地址、看说明都做不到）。

所以这些接口在无角色时必须**降级返回而不是报错**：

| 接口 | 无角色时的行为 |
| --- | --- |
| `GET /api/state` | 200，`noCharacter: true`，空立绘/空语气 |
| `GET /api/characters` | 200，空列表；服务本身不可用时带 `unavailable` 字段 |
| `GET /api/settings` | 200（配置页要用） |
| `GET /` | 200（网页照常打开） |
| `POST /api/characters` | 503 + 可读说明（服务不可用 ≠ 参数错） |

顺带修掉一个隐藏崩溃点：`defaultPortraitKey` 原来写的是
`next(iter(portraits))`，**立绘为空时会抛 StopIteration**。

`tests/fake_host_smoke.py` 里有对应的回归测试
（`NoCharacterMobile` 模拟无角色），覆盖上面每一行。

### 前端也不再写死角色名

`displayName` 原来默认 `'Sakura'`，全新安装若不是这个角色会显示错名字。
现在退回顺序是：接口给的名字 → 角色 id → 中性词「角色」。

## 桌面端设置面板里怎么拿到 token

在 Sakura 的「设置 → 手机远程端」里，这几个字段是给用户抄连接信息的：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| 访问 token | `string`（**明文、可复制**） | 手机上要填的口令 |
| 本机链接 | readonly + copyable | 电脑上预览用 |
| 内网链接 | readonly + copyable | 同一 WiFi 下手机可访问的地址 |
| **手机直接打开这个地址** | readonly + copyable | 完整地址（已含 token），复制到手机浏览器即可 |

**token 字段原来写的是 `type: "password"`，这是个错误选择** ——
`password` 在面板里是屏蔽显示的，而它恰恰是用户必须读到、抄到手机上的信息，
结果就是「看得到字段名、看不到内容」，只能用文本编辑器去翻
`data/plugins/sakura.remote/config.json`。

改成 `string` 后明文显示，并保留 `copyable` 一键复制。
另外新增了 `phone_url`：`mobile_access_urls()` 生成的地址**本身就已经带
`?token=`**，所以直接取第一条内网地址即可，不要再拼一次 token（会重复）。

字段是否合法可以直接用宿主的校验函数验证，不必靠猜：

```python
from app.core_host.plugin_host_services import _settings_field
for raw in _settings_descriptor()["fields"]:
    _settings_field(raw)   # 不合法会抛 SETTINGS_DESCRIPTOR_INVALID
```

## 宽度：区分「目标宽度」和「当前视口」

这两个值混用会产生两种**相反**的 bug，我两个都踩过：

| 用错哪个 | 症状 |
| --- | --- |
| 卡片宽度用 `screen.width`（393），而视口只有 334 | 卡片 381px 溢出 47px，**对话框的 ▲▼ 按钮被挤出屏幕截断** |
| 反过来把**目标**也改成 `innerWidth` | 窗口越量越窄（实测缩到 160px）—— 视口本身受窗口尺寸影响，形成收缩循环 |

正确做法是分开用：

```js
const targetContentW = screen.width - pad*2;              // 窗口要长到这么宽
const contentW = min(targetContentW, innerWidth - pad*2); // 卡片夹在当前视口内
```

- **窗口尺寸**按 `screen`（目标）申请；
- **卡片宽度**夹到 `innerWidth`（不溢出）；
- **立绘适配**按目标宽度算（窗口会长到那么宽）。

实测结果：`navOverflowRight: -12`（留了余量）、`buttonsFullyVisible: true`。

## 文字跟着语音逐字出现

一次回复会被切成多条 segment，每条各自合成一段语音。如果文字一次性全铺出来，
用户会**先读完后听到**，语音和文字完全脱节，观感上像两条不相干的流。

现在的做法：

1. segment 到达时先建好气泡，但**内容是空的**（`addBubble(..., { deferText: true })`）。
   所以「还没轮到的对话」不会提前显示 —— 不会出现一堆文字堆在那儿等语音。
2. 轮到这条 segment 的语音**真正开始播放**时，用音频时长除以字数得到每字间隔，
   再把文字逐字填进去 —— 读完刚好也说完了。
3. 音频提前结束或出错时，`finishBubbleText()` 会把内容补全，
   不会停在半句话上。

### 几个边界

- **没开语音 / 合成失败 / 自动播放被拦**：不能留着空气泡，
  退回按字数估算的节奏（每字 55ms）照样逐字显示。
- **时长单位是秒**（`blob.duration` 就是秒）。踩过的坑：把 1000（毫秒）当秒传进去，
  算出每字 50 秒，画面停在第一个字不动，看起来像功能坏了。
  现在有 `TYPE_MAX_MS`（260ms）兜底，单位给错也只会慢一点，不会卡死。
- 逐字过程中气泡带一个闪烁光标（`.bubble.revealing::after`），
  让人知道还在出字。用 `::after` 而不是插节点 —— 文字是逐字重写
  `textContent` 的，插进去的真节点会被下一次重写抹掉。


## 立绘上的手势

立绘区域整块接收触摸，按手势区分：

| 手势 | 行为 |
| :--- | :--- |
| 轻点人物本体 | 收放对话框 |
| 按住人物本体 | 轻微放大（体感反馈，设置里可关） |
| 拖动 | 移动悬浮窗（位移按屏幕密度换算，与手指 1:1） |
| 长按 5 秒 | 打开设置 |

判定「有没有点到人物身上」靠 canvas 采样立绘的 alpha（见 `portraitAlphaAt`）。
对话框、输入栏等交互控件通过 `CLICK_THROUGH_KEEP` 白名单排除，
它们正常处理自己的点击。
## 输入栏的下拉菜单

「图」和「截屏」原来各占一个网格列，悬浮窗很窄时把输入框挤得只剩一点。
现在收进一个 ＋ 按钮的下拉面板（绝对定位，向上展开，不占布局空间），
输入框宽度从被挤压变成 **197px**（视口 334）。

**层级坑**：`#composer` 上有 `backdrop-filter: blur(10px)`，那会创建
**层叠上下文** —— 子元素 `.mediaPanel` 的 `z-index: 40` 再大也出不去，
实测菜单被对话框盖住、挡住正文最后两行。必须提升 `#composer` 自己：

```css
#composer:has(.mediaPanel:not(.hidden)) { z-index: 60; }
```

实测 `elementFromPoint` 命中的是菜单里的 `.mediaItem`，说明确实在最上层。
