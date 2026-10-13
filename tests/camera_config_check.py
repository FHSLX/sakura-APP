"""拍照入口的配置与生成页面结构回归（本地、离线、合成内容）。

检查的是**产物**而不是源码字符串：
  - web_ui.render_page() 生成的页面里，「拍照」菜单项在选择图片上方，
    并且是一个独立的 <input capture="environment" accept="image/*">；
  - AndroidManifest 只声明 ACTION_IMAGE_CAPTURE 的 queries，不含
    QUERY_ALL_PACKAGES，也没有新增 CAMERA 危险权限；
  - FileProvider 仍用 ${applicationId}.fileprovider + @xml/file_paths，
    辅助页仍未导出、无 noHistory（否则收不到 onActivityResult）。

用法: python tests/camera_config_check.py
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
failures: list[str] = []


def check(name: str, ok: bool, detail: object = "") -> None:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + ("" if ok else f" -> {detail!r}"))
    if not ok:
        failures.append(name)


class PageParser(HTMLParser):
    """记录元素顺序，够用来判断菜单项顺序与 input 属性。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.order: list[tuple[str, dict[str, str]]] = []
        self.labels_in_panel: list[str] = []
        self.inputs: dict[str, dict[str, str]] = {}
        self._panel_depth = 0
        self._div_stack: list[bool] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key: (value or "") for key, value in attrs}
        self.order.append((tag, values))
        if tag == "div":
            inside = values.get("id") == "mediaPanel"
            self._div_stack.append(inside)
            if inside:
                self._panel_depth = 1
            elif self._panel_depth:
                self._panel_depth += 1
        if tag == "label" and self._panel_depth:
            self.labels_in_panel.append(values.get("for", ""))
        if tag == "input":
            element_id = values.get("id")
            if element_id:
                self.inputs[element_id] = values

    def handle_endtag(self, tag: str) -> None:
        if tag == "div" and self._div_stack:
            inside = self._div_stack.pop()
            if inside:
                self._panel_depth = 0
            elif self._panel_depth:
                self._panel_depth -= 1


def parse_page() -> PageParser:
    if str(ROOT) not in sys.path:
        sys.path.insert(0, str(ROOT))
    import importlib

    web_ui = importlib.import_module("sakura_remote.web_ui")
    html = web_ui.render_page("synthetic-token", {"autoplay": True, "tts_enabled": True})
    parser = PageParser()
    parser.feed(html)
    return parser


def check_page() -> None:
    page = parse_page()

    camera_input = page.inputs.get("camera")
    image_input = page.inputs.get("image")
    check("页面存在独立的 #camera 文件输入", camera_input is not None, sorted(page.inputs))
    check("页面仍有 #image 图库输入", image_input is not None, sorted(page.inputs))
    if camera_input is None or image_input is None:
        return

    check("#camera 是 capture=environment 的图片入口",
          camera_input.get("type") == "file"
          and camera_input.get("capture") == "environment"
          and "image/*" in camera_input.get("accept", ""),
          camera_input)
    check("#image 不带 capture（保持纯图库）",
          "capture" not in image_input, image_input)
    check("拍照与图库是两个不同的 input 元素",
          camera_input.get("id") != image_input.get("id"), camera_input.get("id"))

    check("菜单里「拍照」在「选择图片」上方",
          page.labels_in_panel[:2] == ["camera", "image"], page.labels_in_panel)


def check_manifest() -> None:
    tree = ET.parse(ROOT / "phone_app/android/app/src/main/AndroidManifest.xml")
    manifest = tree.getroot()

    queries = manifest.findall("queries")
    check("Manifest 声明了 queries（Android 11+ 包可见性）", len(queries) == 1, len(queries))
    actions: list[str] = []
    for query in queries:
        for intent in query.findall("intent"):
            for action in intent.findall("action"):
                actions.append(action.get(ANDROID + "name", ""))
    check("queries 只声明 IMAGE_CAPTURE", actions == ["android.media.action.IMAGE_CAPTURE"],
          actions)
    check("没有 QUERY_ALL_PACKAGES（不扩大可见范围）",
          all("QUERY_ALL_PACKAGES" not in (action or "") for action in actions), actions)

    permissions = [item.get(ANDROID + "name", "")
                   for item in manifest.findall("uses-permission")]
    check("未申请 CAMERA 危险权限（调用外部相机不需要）",
          "android.permission.CAMERA" not in permissions, permissions)

    providers = {item.get(ANDROID + "name"): item for item in manifest.findall("application/provider")}
    provider = providers.get("androidx.core.content.FileProvider")
    check("仍使用 androidx FileProvider", provider is not None, sorted(providers))
    if provider is not None:
        check("FileProvider authority 未变",
              provider.get(ANDROID + "authorities") == "${applicationId}.fileprovider",
              provider.get(ANDROID + "authorities"))
        check("FileProvider 仍然不导出且允许临时授权",
              provider.get(ANDROID + "exported") == "false"
              and provider.get(ANDROID + "grantUriPermissions") == "true",
              provider.attrib)
        metadata = provider.find("meta-data")
        check("FileProvider 仍指向 @xml/file_paths",
              metadata is not None
              and metadata.get(ANDROID + "resource") == "@xml/file_paths",
              metadata.attrib if metadata is not None else None)

    activities = {item.get(ANDROID + "name"): item
                  for item in manifest.findall("application/activity")}
    chooser = activities.get(".FileChooserActivity")
    check("辅助页仍未导出", chooser is not None
          and chooser.get(ANDROID + "exported") == "false",
          chooser.attrib if chooser is not None else None)
    if chooser is not None:
        check("辅助页仍无 noHistory（否则收不到 onActivityResult）",
              chooser.get(ANDROID + "noHistory") != "true", chooser.attrib)
        check("辅助页仍有独立 taskAffinity",
              chooser.get(ANDROID + "taskAffinity") == "${applicationId}.picker",
              chooser.get(ANDROID + "taskAffinity"))

    paths = ET.parse(ROOT / "phone_app/android/app/src/main/res/xml/file_paths.xml").getroot()
    cache_paths = [item for item in paths if item.tag == "cache-path"]
    check("file_paths.xml 仍提供 cache-path（照片只写应用缓存）",
          len(cache_paths) == 1, [item.tag for item in paths])
    check("未新增 root-path / files-path 等更大共享路径",
          all(item.tag in ("external-path", "cache-path") for item in paths),
          [item.tag for item in paths])


def main() -> int:
    check_page()
    check_manifest()
    if failures:
        print(f"\n{len(failures)} 项失败: {', '.join(failures)}")
        return 1
    print("\nPASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
