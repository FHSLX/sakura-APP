# Publish a GitHub Release for this project.
#
# Deletes the old release (whose bundles predate the current fixes),
# then creates a fresh one with the current assets and the release notes.
#
# Auth: pass a GitHub token via -Token or the GH_TOKEN environment variable.
#   Create one at https://github.com/settings/tokens with the "repo" scope.
#   Revoke it when you are done -- this script never stores it.
#
# Usage:
#   $env:GH_TOKEN = "<token>"
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\publish_release.ps1
#
# Optional:
#   -Repo     owner/name      default FHSLX/sakura-APP
#   -Tag      v1.1.0          the release to create
#   -OldTag   v1.0.0          the release to delete first
#   -Bundle   <dir>           default: newest dist\release-* directory
#   -KeepOldRelease           don't delete the old release

[CmdletBinding()]
param(
    [string]$Token = $env:GH_TOKEN,
    [string]$Repo = "FHSLX/sakura-APP",
    [string]$Tag = "v1.1.0",
    [string]$OldTag = "v1.0.0",
    [string]$Bundle = "",
    [switch]$KeepOldRelease
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

if (-not $Token) {
    Write-Host "缺少 GitHub token。" -ForegroundColor Red
    Write-Host "  1) 打开 https://github.com/settings/tokens 建一个 classic token，勾 repo 权限"
    Write-Host "  2) 设置环境变量 GH_TOKEN，然后重新运行本脚本"
    Write-Host "  3) 用完记得把 token Revoke 掉"
    exit 1
}

$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "sakura-remote-release"
}
$api = "https://api.github.com/repos/$Repo"

# PowerShell 的自动变量 $args 不能当局部变量用，所以这里叫 $params。
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        $Body,
        [string]$ContentType = "application/json"
    )
    $params = @{
        Method      = $Method
        Uri         = $Uri
        Headers     = $headers
        TimeoutSec  = 120
        ErrorAction = "Stop"
    }
    if ($null -ne $Body) {
        if ($ContentType -eq "application/json") {
            $params.Body = $Body | ConvertTo-Json -Depth 6
            $params.ContentType = "application/json"
        } else {
            $params.Body = $Body
            $params.ContentType = $ContentType
        }
    }
    return Invoke-RestMethod @params
}

# 尺寸文案单独拼，不要写在格式串里跟 -f 混用 ——
# 形如 ("... {0:N0} KB)" -f x) 会让 PowerShell 解析器误判，报 Unexpected token 'KB'。
function Format-Kb {
    param([double]$Bytes)
    $kb = [math]::Round($Bytes / 1KB)
    return "$kb KB"
}

# ---- 1) Locate the build bundle -------------------------------------------------
if (-not $Bundle) {
    $Bundle = Get-ChildItem (Join-Path $root "dist") -Directory -Filter "release-*" |
        Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Bundle -or -not (Test-Path $Bundle)) {
    Write-Host "找不到发布包目录，请先运行 tools\build_release.ps1" -ForegroundColor Red
    exit 1
}
Write-Host "使用发布包: $Bundle"

$pluginVersion = $Tag.TrimStart("v")
$wanted = @(
    "SakuraRemote-release.apk",
    "plugin-sakura.remote-$pluginVersion.zip",
    "MANUAL.md",
    "README-install.md",
    "App-SakuraRemote.zip"
)
$files = @()
foreach ($name in $wanted) {
    $path = Join-Path $Bundle $name
    if (Test-Path $path) {
        $files += $path
    }
}
if ($files.Count -eq 0) {
    Write-Host "发布包里没有可上传的文件（期望 SakuraRemote-release.apk 等）" -ForegroundColor Red
    exit 1
}
Write-Host "将上传以下文件："
foreach ($f in $files) {
    Write-Host ("  - " + (Split-Path $f -Leaf))
}

$notesPath = Join-Path $root "docs\Release说明.md"
if (-not (Test-Path $notesPath)) {
    Write-Host "缺少 docs\Release说明.md（Release 正文）" -ForegroundColor Red
    exit 1
}
$notes = Get-Content $notesPath -Raw -Encoding UTF8

# ---- 2) Verify repo access ------------------------------------------------------
try {
    $repoInfo = Invoke-Api -Method GET -Uri $api
    Write-Host "目标仓库: $($repoInfo.full_name)  默认分支: $($repoInfo.default_branch)"
} catch {
    Write-Host "访问仓库失败：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "请检查 token 是否有效、是否有 repo 权限。"
    exit 1
}

# ---- 3) Delete the stale release ------------------------------------------------
if (-not $KeepOldRelease) {
    try {
        $releases = Invoke-Api -Method GET -Uri "$api/releases?per_page=100"
        foreach ($rel in $releases) {
            if ($rel.tag_name -eq $OldTag) {
                Write-Host "删除旧 Release $OldTag (id = $($rel.id))"
                Invoke-Api -Method DELETE -Uri "$api/releases/$($rel.id)" | Out-Null
                Write-Host "  已删除"
            }
        }
    } catch {
        Write-Host "删除旧 Release 时出错（继续）: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ---- 4) Create or update the target release -------------------------------------
$existing = $null
try {
    $existing = Invoke-Api -Method GET -Uri "$api/releases/tags/$Tag"
} catch {
    $existing = $null
}

if ($existing) {
    Write-Host "Release $Tag 已存在，更新正文并替换附件"
    Invoke-Api -Method PATCH -Uri "$api/releases/$($existing.id)" -Body @{
        name = $Tag
        body = $notes
    } | Out-Null
    foreach ($asset in $existing.assets) {
        Invoke-Api -Method DELETE -Uri "$api/releases/assets/$($asset.id)" | Out-Null
        Write-Host "  移除旧附件 $($asset.name)"
    }
    $release = Invoke-Api -Method GET -Uri "$api/releases/$($existing.id)"
} else {
    Write-Host "创建 Release $Tag"
    $release = Invoke-Api -Method POST -Uri "$api/releases" -Body @{
        tag_name         = $Tag
        target_commitish = $repoInfo.default_branch
        name             = $Tag
        body             = $notes
        draft            = $false
        prerelease       = $false
    }
}

# ---- 5) Upload assets -----------------------------------------------------------
$uploadBase = $release.upload_url -replace '\{\?[^}]*\}$', ''
foreach ($file in $files) {
    $name = Split-Path $file -Leaf
    $bytes = [IO.File]::ReadAllBytes($file)
    $uri = $uploadBase + "?name=" + [uri]::EscapeDataString($name)
    Write-Host ("上传 " + $name + "  (" + (Format-Kb $bytes.Length) + ")")
    try {
        Invoke-Api -Method POST -Uri $uri -Body $bytes -ContentType "application/octet-stream" | Out-Null
        Write-Host "  完成"
    } catch {
        Write-Host "  失败: $($_.Exception.Message)" -ForegroundColor Red
    }
}

# ---- 6) Summary -----------------------------------------------------------------
$final = Invoke-Api -Method GET -Uri "$api/releases/tags/$Tag"
Write-Host ""
Write-Host "完成: $($final.html_url)" -ForegroundColor Green
Write-Host "附件列表："
foreach ($a in $final.assets) {
    Write-Host ("  - " + $a.name + "  (" + (Format-Kb $a.size) + ")")
}
Write-Host ""
Write-Host "提示：token 只在本次运行中使用，没有写入任何文件。"
Write-Host "     用完请到 https://github.com/settings/tokens 把它 Revoke 掉。"
