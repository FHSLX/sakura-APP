# 上传到 GitHub

仓库已经在 `<仓库目录>` 初始化好并提交了 2 个 commit。

> ⚠️ **不要**直接在 `<本项目源码目录>` 里 `git init` —— 那个目录有 1.8 GB 的
> Android SDK / gradle 缓存，以及签名密钥，很容易误提交。
> 已经给你准备好了干净的 `<仓库目录>`（95 个文件，4 MB）。

---

## 一、先在 GitHub 上建空仓库

打开 https://github.com/new

| 项 | 填什么 |
| --- | --- |
| Repository name | `sakura-remote`（或你喜欢的名字） |
| Description | 把手机变成 Sakura 桌宠的第二个屏幕 |
| Public / Private | 按需 |
| **Initialize this repository with** | **全都不要勾**（README / .gitignore / license 都别加，否则会冲突） |

建好后记下仓库地址，形如：

```
https://github.com/<你的用户名>/sakura-remote.git
```

---

## 二、关联并推送

```powershell
cd <仓库目录>

# 关联远端（把 URL 换成你自己的）
git remote add origin https://github.com/<你的用户名>/sakura-remote.git

# 推送
git push -u origin main
```

如果提示要登录，推荐用 **GitHub CLI** 或 **Personal Access Token**：

```powershell
# 方式一：GitHub CLI（推荐）
winget install GitHub.cli
gh auth login
gh repo create sakura-remote --public --source=. --push

# 方式二：用令牌
# GitHub → Settings → Developer settings → Personal access tokens
# 生成后 push 时用户名填 GitHub 用户名，密码填令牌
```

---

## 三、推送前建议再确认一次

```powershell
cd <仓库目录>

# 1) 确认没有任何密钥/构建产物
git ls-files | Select-String -Pattern '\.jks|keystore|local\.properties|\.apk$|node_modules'

# 2) 确认没有本机信息（IP、绝对路径、用户名）
git grep -nE '192\.168\.[0-9]+\.[0-9]+|[A-Za-z]:\\\\(sakura|Sakura|Users)'

# 3) 确认工作区干净
git status
```

两个命令都应该**没有输出**。有输出就说明有东西要清理，
先 `git rm --cached <文件>` 再从 `.gitignore` 补规则。

---

## 四、推送之后

### 1. 打版本标签

```powershell
cd <仓库目录>
git tag -a v1.0.0 -m "首个公开版本"
git push origin v1.0.0
```

### 2. 发布 Release（让别人能直接下载安装）

GitHub → Releases → Draft a new release → 选 `v1.0.0` → 上传附件：

| 附件 | 从哪来 |
| --- | --- |
| `SakuraRemote-release.apk` | `dist\release-*\SakuraRemote-release.apk` |
| `plugin-sakura.remote-1.0.0.zip` | `dist\release-*\plugin-sakura.remote-1.0.0.zip` |
| `MANUAL.md` | `dist\release-*\MANUAL.md` |

> 这些在 `.gitignore` 里被排除了（二进制不适合进 git 历史），
> 所以走 Release 附件而不是提交进仓库。

### 3. 仓库设置建议

- **About** → 填 Description，加 Topics：`sakura` `android` `overlay` `desktop-pet` `tts`
- **Settings → Actions** 可选：加一个跑 `tests/` 的 CI

---

## 五、后续更新代码时

改了 `<本项目源码目录>` 里的源码后，需要同步到仓库目录：

```powershell
# 同步插件源码（不动 .git）
robocopy <本项目源码目录>\sakura_remote <仓库目录>\sakura_remote /MIR `
  /XD __pycache__ /XF *.pyc

# 同步文档
Copy-Item <本项目源码目录>\README.md <仓库目录>\README.md -Force
Copy-Item <本项目源码目录>\docs\使用手册.md <仓库目录>\docs\ -Force
Copy-Item <本项目源码目录>\docs\使用手册.md <仓库目录>\docs\manual.md -Force

# 提交
cd <仓库目录>
git add -A
git commit -m "描述你的改动"
git push
```

> **注意同步脚本本身**：`tools\` 和 `phone_app\README.md` 在仓库里是
> 脱敏过的版本（去掉了本机 IP、绝对路径）。如果你直接整体覆盖，
> 会把私人信息带回去 —— 同步后记得再跑一遍第三节的检查。

---

## 附：仓库里有什么

```
sakura-remote/
├── README.md               项目介绍、安装、使用、疑难解答
├── LICENSE                 MIT
├── .gitignore              排除密钥/构建产物/缓存
├── .gitattributes          统一换行符，标记二进制
├── docs/
│   ├── manual.md           完整使用手册（15 节）
│   └── 使用手册.md          同上，中文名
├── sakura_remote/          插件（Python + 前端）
├── phone_app/              Android 外壳
│   ├── android/app/src/main/java/com/sakura/remote/   9 个 Java 类
│   ├── www/                连接设置页
│   └── build_apk.bat       可移植的构建脚本
├── tools/                  安装、发布、联调脚本
└── tests/                  回归测试（宿主契约 / 前端渲染 / 主流程）
```

**不含**（被 `.gitignore` 排除）：

- 签名密钥（`*.jks`、`keystore.properties`）
- 构建产物（`build/`、`*.apk`、`.gradle/`）
- Android SDK 与 node_modules
- 运行时日志与缓存
