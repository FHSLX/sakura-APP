## 下载哪个

| 文件 | 装到哪 |
| :--- | :--- |
| `App-SakuraRemote.zip` | **手机**。解压后得到 `SakuraRemote-release.apk` 再安装 |
| `plugin-sakura.remote-1.2.0.zip` | **电脑**。解压后把 `sakura.remote` 目录放到 Sakura 的 `plugins/user/` 下 |
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

## v1.2.0 更新内容

这一版以「能用」和「体积」为主，修掉了三个影响手感的问题，安装包也小了三分之一。

### 修好了

- **拖动终于跟手了**：之前窗口只走手指的约 36%（CSS 像素与设备像素混用，
  差一个屏幕密度倍率）。现在是与手指 1:1 精确跟随。
- **轻点角色收放对话框**：之前轻点常常没反应（判定条件过脆）。
- **点立绘旁边的空白处不再出现半透明鬼影**：那是系统的长按反馈，
  现在已关掉。
- **文字跟着语音逐字出现**：没轮到的那条不会提前显示，读完刚好说完。

### 体积

- **安装包 6.9 MB → 4.8 MB**（开启 R8 代码压缩，并确认 JS 桥未受影响）
- 插件包 101 KB → 87 KB

### 结构（仅影响开发者）

- 插件源码移到仓库根目录，符合 Sakura Registry 收录要求
- 插件文档改名 `PLUGIN.md`（与项目 README 区分）

> **升级方式**：App 覆盖安装即可；插件直接替换 `plugins/user/sakura.remote/` 目录，
> 配置与聊天记录在 `data/plugins/sakura.remote/`，不受影响。
