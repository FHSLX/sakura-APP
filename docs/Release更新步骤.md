# 更新 GitHub Release（v1.0.0 → v1.1.0）

旧 Release（v1.0.0）里的包是修复前的，需要删掉换成 v1.1.0。
因为上传附件需要 GitHub 凭据，这一步只能你来做。

---

## 方式一：一条命令（推荐）

需要先在 GitHub 建一个 Personal Access Token：

1. 打开 <https://github.com/settings/tokens>
2. **Generate new token** → **Generate new token (classic)**
3. Note 填 `sakura-release`，Expiration 按需，勾选 **`repo`** 权限
4. 生成后复制 token（只显示一次）
5. **用完就可以在这个页面 Revoke 掉**

然后在本项目目录执行（把 `TOKEN` 换成你的 token）：

```powershell
cd <本项目源码目录>
$env:GH_TOKEN = "TOKEN"
powershell -NoProfile -ExecutionPolicy Bypass -File tools\publish_release.ps1
```

脚本会：删除旧的 `v1.0.0` Release → 建 `v1.1.0` → 上传附件并填好说明。

---

## 方式二：网页手动（约 2 分钟）

### 第 1 步：删掉旧 Release

1. 打开 <https://github.com/FHSLX/sakura-APP/releases>
2. 找到 `v1.0.0`，点右侧 **⋯** → **Delete**
3. 确认删除

> 只删 Release 不影响源码。标签 `v1.0.0` 可以留着，也可以一并删掉。

### 第 2 步：建 v1.1.0 并上传

1. 打开 <https://github.com/FHSLX/sakura-APP/releases/new>
2. **Choose a tag** 填 `v1.1.0` → 点 **Create new tag**
3. Release title 填 `v1.1.0`
4. 说明框粘贴 `docs/Release说明.md` 的内容
5. 把下面这几个文件拖进附件区，文件在
   `dist\release-20260923-033345\`：

   | 文件 | 大小 |
   | --- | --- |
   | `SakuraRemote-release.apk` | 7,026 KB |
   | `plugin-sakura.remote-1.1.0.zip` | 96 KB |
   | `MANUAL.md` | 16 KB |
   | `README-install.md` | 4 KB |
   | `App-SakuraRemote.zip` | 6,662 KB |

6. 点 **Publish release**

> **建议把裸的 `SakuraRemote-release.apk` 也传上去。**
> 旧 Release 里只有 `App-SakuraRemote.zip`（APK 的压缩包），
> 用户得先解压才能装 —— 多一步，容易卡住人。

---

## 上传后自检

浏览器打开 <https://github.com/FHSLX/sakura-APP/releases/latest>，
确认能看到 `v1.1.0` 和至少这三个附件：

- `SakuraRemote-release.apk`
- `plugin-sakura.remote-1.1.0.zip`
- `MANUAL.md`

然后 README 里的下载链接就都能点通了。
