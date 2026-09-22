> 这是 [Sakura Desktop Pet](https://github.com/Rvosy/sakura) 的手机远程端插件与配套 App。
> 使用前请先安装并跑通官方 Sakura 桌面端。

## 下载哪个

| 文件 | 内容 | 装到哪 |
| :--- | :--- | :--- |
| `App-SakuraRemote.zip` | 手机 App。**解压后得到 `SakuraRemote-release.apk`**，传到手机安装 | 手机 |
| `plugin-sakura.remote-1.0.0.zip` | Sakura 插件。解压后把 `sakura.remote` 目录整个放进去 | 电脑 |
| `MANUAL.md` | 完整使用手册 | 参考 |

## 插件安装

解压 `plugin-sakura.remote-1.0.0.zip`，把其中的 `sakura.remote` 目录放到：

```
<Sakura安装目录>/plugins/user/sakura.remote/
```

目录名必须是 `sakura.remote`。然后打开 Sakura，进入 设置 → 手机远程端，启用开关。

## App 安装

解压 `App-SakuraRemote.zip` 得到 `SakuraRemote-release.apk`，传到手机安装。
安装时系统会提示「未知来源」，手动允许即可。

如果之前装过其他签名的版本，需要先卸载旧的，否则无法覆盖安装。

安装后按顺序做三件事：

1. 打开 App，填电脑的局域网 IP、端口和访问 token
2. 在配置页开启桌面立绘，系统会要求「显示在其他应用上层」权限
3. 在配置页点「申请后台保活」，避免被系统省电策略清理

## 系统要求

- 电脑：Windows 10 / 11，已安装并跑通 Sakura（Plugin API v4）
- 手机：Android 10 及以上（Android 14+ 最稳妥）

详细的版本兼容说明与已知问题见 [README](https://github.com/FHSLX/sakura-APP#readme)。

## 使用前请注意

- 插件默认监听 `0.0.0.0`，请把访问 token 改成足够长的随机值，不要将端口映射到公网
- 电脑需要保持开机、Sakura 需要保持运行，这是远程串流而非离线可用

完整安装与使用步骤见 `MANUAL.md`。
