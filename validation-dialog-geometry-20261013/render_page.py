"""用真实 web_ui.render_page 生成几何 fixture 的应用页面（合成 token，无真实数据）。"""
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT if (ROOT / "plugin.yaml").is_file() else ROOT / "sakura_remote"))

import web_ui  # noqa: E402

target = HERE / "app-rendered.html"
target.write_text(
    web_ui.render_page("synthetic-token", {"autoplay": False, "tts_enabled": False}),
    encoding="utf-8",
)
print(target)
