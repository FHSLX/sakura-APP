## 下载哪个

| 文件 | 装到哪 |
| :--- | :--- |
| `App-SakuraRemote.zip` | **手机**。解压后得到 `SakuraRemote-release.apk` 再安装 |
| `plugin-sakura.remote-1.2.1.zip` | **电脑**。解压后把 `sakura.remote` 目录放到 Sakura 的 `plugins/user/` 下 |
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

## v1.2.1 更新内容

### 修好了

- **拖动灵敏度纠正**：之前对屏幕密度处理反了，手指移动 100 像素窗口只走 36 像素。
  现在实测 1:1 精确跟随（慢速拖动也不丢位移）。
- **拖动改为对齐屏幕刷新**：位移合并到每帧一次性应用，手指快速移动时不再一卡一卡。
- **设置页新增「授权截屏权限」按钮**：可以先单独把系统授权授好，
  之后「截取屏幕」就不会再被弹窗打断。
  之前授权框根本弹不出来（系统悬浮窗会把它压掉），现在会先让位再请求。

> **升级方式**：App 覆盖安装即可；插件直接替换 `plugins/user/sakura.remote/` 目录。
