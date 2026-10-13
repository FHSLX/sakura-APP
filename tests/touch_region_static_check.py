"""R2-1/R2-2 + INT-01 静态资源与页面接线检查（不依赖设备、不开端口）。

验证：
- /overlay_touch_region.js 能被 static_file 正确解析并以 JS 类型返回；
- /page_session.js（页面代际唯一来源）同样可被静态服务解析；
- 目录穿越被拒绝、缺失文件返回 None；
- 渲染出的页面先引入 page_session.js、再引入穿透模块与 app.js；
- 构建号把 overlay_touch_region.js 与 page_session.js 计入（避免旧缓存
  继续跑「两个模块各自随机会话」的旧逻辑）。

用法: python tests/touch_region_static_check.py
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def _plugin_root() -> Path:
    for candidate in (ROOT, ROOT / "sakura_remote"):
        if (candidate / "plugin.yaml").exists():
            return candidate
    raise SystemExit("plugin.yaml not found under " + str(ROOT))


PLUGIN_ROOT = _plugin_root()
sys.path.insert(0, str(PLUGIN_ROOT))

import http_server  # noqa: E402
import web_ui  # noqa: E402

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: object = "") -> None:
    print(f"[{'PASS' if condition else 'FAIL'}] {name}" + ("" if condition else f" -> {detail!r}"))
    if not condition:
        FAILURES.append(name)


def main() -> int:
    service = http_server.RemoteService(
        Path("."),
        mobile_service=None,
        character_service=None,
        tts_service=None,
        logger=logging.getLogger("static-check"),
        token="static-token",
    )

    found = service.static_file("/overlay_touch_region.js")
    check("穿透模块可被静态服务解析", found is not None, found)
    if found:
        media_type, payload = found
        check("穿透模块返回 JS 内容类型",
              media_type.startswith("application/javascript"), media_type)
        check("穿透模块内容非空且导出 SakuraTouchRegion",
              b"SakuraTouchRegion" in payload and len(payload) > 2000, len(payload))
        check("穿透模块含 API 版本能力探测",
              b"isTouchRegionSupported" in payload, "missing capability probe")

    session_file = service.static_file("/page_session.js")
    check("页面代际模块可被静态服务解析", session_file is not None, session_file)
    if session_file:
        media_type, payload = session_file
        check("页面代际模块返回 JS 内容类型",
              media_type.startswith("application/javascript"), media_type)
        check("页面代际模块导出 SakuraPageSession",
              b"SakuraPageSession" in payload, "missing export")

    for route in ("/app.js", "/app.css"):
        item = service.static_file(route)
        check(f"{route} 仍可访问", item is not None and len(item[1]) > 0,
              item and len(item[1]))

    check("目录穿越被拒绝",
          service.static_file("/../../secret.txt") is None, "traversal allowed")
    check("缺失文件返回 None",
          service.static_file("/not-there.js") is None, "unexpected file")

    page = web_ui.render_page("static-token", {"autoplay": True, "tts_enabled": True})
    check("页面引入 overlay_touch_region.js",
          'src="/overlay_touch_region.js' in page, "script tag missing")
    check("页面仍引入 app.js", 'src="/app.js' in page, "app.js missing")
    check("页面引入 page_session.js 且排在 app.js 之前",
          'src="/page_session.js' in page
          and page.index("/page_session.js") < page.index('src="/app.js'),
          "page_session.js must load first")
    check("页面带穿透开关控件",
          'id="passThroughToggle"' in page, "toggle missing")
    check("页面说明旧系统不支持精确穿透",
          "Android 13" in page, "version note missing")
    check("截屏说明改为每次需要系统确认",
          "每次" in page and "授权在本次开机内有效" not in page, "stale wording")

    # 构建号是否把这些静态文件计入。
    #
    # 直接改 mtime 需要写权限（受限 shell 里 static 目录可能只读），
    # 所以先在托管模式下改 Path.stat，验证「读数变化 → 构建号变化」；
    # 如果环境允许写，再补一次真实 utime 的端到端验证。
    WATCHED = ("app.css", "app.js", "overlay_touch_region.js", "page_session.js")
    original_stat = Path.stat

    class _Stat:
        def __init__(self, mode, size, mtime):
            self.st_mode = mode
            self.st_size = size
            self.st_mtime = mtime

    def fake_stat(self: Path):
        result = original_stat(self)
        if self.name in WATCHED:
            return _Stat(result.st_mode, result.st_size, result.st_mtime + 5)
        return result

    before = web_ui._build_id()
    Path.stat = fake_stat
    try:
        after = web_ui._build_id()
    finally:
        Path.stat = original_stat
    check("构建号把静态资源 mtime 计入（含 page_session.js）", before != after,
          (before, after))

    web_ui_source = (PLUGIN_ROOT / "web_ui.py").read_text(encoding="utf-8")
    for name in WATCHED:
        check(f"_build_id 监视列表包含 {name}", f'"{name}"' in web_ui_source, "not watched")

    # 真实 utime 端到端验证（有写权限时才做，不把只读环境当失败）
    #
    # 注意：_build_id 取的是被监视文件的**最大 mtime**，所以随便挑一个文件 +5s
    # 不一定会改变构建号（它可能仍然不是最新）。这里把其中一个被监视文件临时
    # 设成未来时间，验证后立刻还原 —— 只改 mtime，不改内容。
    static_file = PLUGIN_ROOT / "static" / "overlay_touch_region.js"
    try:
        import os
        import time as _time
        original = static_file.stat().st_mtime
        baseline = web_ui._build_id()
        future = _time.time() + 30
        os.utime(static_file, (future, future))
        after_utime = web_ui._build_id()
        check("真实 mtime 变化也改变构建号", after_utime != baseline, (baseline, after_utime))
    except OSError as error:
        print(f"[SKIP] 真实 mtime 端到端验证（只读环境）：{error}")
    finally:
        try:
            os.utime(static_file, (original, original))
        except (OSError, UnboundLocalError):
            pass

    print()
    if FAILURES:
        print(f"{len(FAILURES)} 项失败：{', '.join(FAILURES)}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
