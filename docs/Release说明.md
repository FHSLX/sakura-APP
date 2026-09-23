## 下载哪个

| 文件 | 装到哪 |
| :--- | :--- |
| `App-SakuraRemote.zip` | **手机**。解压后得到 `SakuraRemote-release.apk` 再安装 |
| `plugin-sakura.remote-1.2.3.zip` | **电脑**。解压后把 `sakura.remote` 目录放到 Sakura 的 `plugins/user/` 下 |
| `MANUAL.md` | 完整使用手册，参考用 |

两个都要装：插件装电脑，App 装手机。

> **升级注意**：如果装过旧版 App，因为签名相同可以直接覆盖安装；
> 插件直接覆盖 `plugins/user/sakura.remote/` 目录即可，
> 配置和聊天记录在 `data/plugins/sakura.remote/`，不会被覆盖。

## 使用前提

先安装并跑通官方 [Sakura Desktop Pet](https://github.com/Rvosy/sakura)，
本项目是它的插件与配套 App，自身不含对话模型和语音合成。

- 电脑：Windows 10 / 11
- 手机：Android 10 及以上（Android 14+ 最稳妥）

完整安装步骤见 `MANUAL.md`，已知问题与版本兼容说明见仓库首页的 README。

## v1.2.3 更新内容

### 修好了

- **拖动时立绘一直闪**。根因是同优先级的几条规则都在抢 transform 属性，
  位置靠后的那条把前面全盖掉 —— 按住放大实际没生效，而按下/拖动来回切换时
  画面就一闪一闪。现在全项目只有一个地方写 transform（JS 的
  applyPortraitTransform），不存在争抢。
- **立绘位置偏移**：居中方式两种模式不同（悬浮窗靠 auto margin，小球靠
  translateX(-50%)），之前一律加 translateX 会把悬浮窗里的立绘推到左边。已修正。
- **拖动期间停掉呼吸动画**：它动的是自定义属性，CSS 变量不做插值、只能跳变，
  也是闪烁的来源之一。

### 同时验证

- 单指拖动实测 1:1（手指 100 CSS px → 窗口 100.0 CSS px）
- 二指期间窗口漂移 0.0 CSS px（让位保持到最后一根手指抬起）

