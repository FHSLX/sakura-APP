# -*- coding: utf-8 -*-
"""Sakura 1.3.1 立绘兼容修复的真实生产行为测试。

覆盖：
  1. 合成夹具：新格式（visuals.resources / root+entry / resource.json / BOM /
     多形态 default / root='.'）、旧格式 portrait、默认 id 缺失/无效、
     重复 id、路径越界、非立绘类型、空配置、损坏 JSON、图片缺失、
     把 resource.json 当图片、配置与形态变更后的缓存失效、state 可恢复降级；
  2. 旧版对比：用派发前备份 backup/sakura-compat-20261011/http_server.py
     作为“旧实现”，证明核心新格式用例旧版失败、修复版通过（含真实安装角色）；
  3. 真实生产 HTTP 链路：state -> 图片 URL -> /asset/portrait，
     新格式必须返回 PNG 字节与 image/png，旧格式保持正常；
     并对真实安装的 nikaidou_shinku（新）与 Sakura（旧）跑同一链路。

只读取公开视觉元数据与 PNG 资源；不读取/输出提示词、聊天、用户配置或凭据。
所有合成夹具都写到 tests/_compat_tmp（会话内新建目录），不修改产品源码。
"""
from __future__ import annotations

import importlib.util
import json
import shutil
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

# 不要为被加载的备份/生产源码写 .pyc：备份目录必须保持与派发时逐字节一致。
sys.dont_write_bytecode = True

PNG_BASE = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082"
)
TOKEN = "compat-token"
REAL_CHARACTERS = Path("F:/sakura/characters")
NEW_FORMAT = ("nikaidou_shinku", "kuro_sakuramoyu", "nero_claudius")
LEGACY_FORMAT = ("Sakura", "N.A.V.I")


class Checker:
    def __init__(self) -> None:
        self.results: list[dict] = []

    def check(self, name: str, ok: bool, detail: object = "") -> bool:
        self.results.append({"name": name, "ok": bool(ok), "detail": "" if ok else str(detail)[:300]})
        print(("[PASS] " if ok else "[FAIL] ") + name + ("" if ok else f" -> {detail!r}"[:300]))
        return bool(ok)

    @property
    def failures(self) -> int:
        return sum(1 for item in self.results if not item["ok"])


def _plugin_dir(base: Path):
    """插件目录：**仓库根即插件**优先，兼容早期的 workspace/sakura_remote 结构。

    公开克隆仓库（release-work/app、release-work/plugin）的 plugin.yaml 与
    http_server.py 都在根目录；早期结构把它们放在 sakura_remote/ 子目录。
    """
    if (base / "plugin.yaml").is_file() and (base / "http_server.py").is_file():
        return base
    if (base / "sakura_remote" / "http_server.py").is_file():
        return base / "sakura_remote"
    return None


def _find_root() -> Path:
    for base in Path(__file__).resolve().parents:
        if _plugin_dir(base) is not None:
            return base
    raise SystemExit("找不到工作区根（plugin.yaml + http_server.py）")


def _compat_backup():
    """兼容对照基线（仅本机工作区有；公开克隆仓库没有，测试应跳过而不是失败）。"""
    for base in Path(__file__).resolve().parents:
        for candidate in (
            base / "backup" / "sakura-compat-20261011" / "sakura_remote" / "http_server.py",
            base / "backup" / "sakura-compat-20261011" / "http_server.py",
        ):
            if candidate.is_file():
                return candidate
    return None


def _validation_dir(root: Path) -> Path:
    for base in Path(__file__).resolve().parents:
        if base.name == "validation-sakura-compat-20261011":
            return base
    candidate = root / "validation-sakura-compat-20261011"
    return candidate


ROOT = _find_root()
PLUGIN = _plugin_dir(ROOT)
BACKUP_HTTP = _compat_backup()
if BACKUP_HTTP is None:
    # 公开克隆仓库不带 compat 备份基线：跳过对照，绝不当成失败。
    print("[SKIP] 未找到 backup/sakura-compat-20261011 对照基线；该用例只在本机工作区运行。")
    raise SystemExit(0)
# 宿主沙箱把 tests/ 与 sakura_remote/ 视为 shell 只读；夹具写到可写的 validation 目录。
FIXTURES = _validation_dir(ROOT) / "_compat_tmp"


def _load(name: str, path: Path):
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# 先加载修复版：它会把 web_ui 注册进 sys.modules，备份副本随后能复用同一模块。
NEW = _load("compat_new_http_server", PLUGIN / "http_server.py")
OLD = _load("compat_old_http_server", BACKUP_HTTP)


# ---- 夹具构造 ---------------------------------------------------------

def png(tag: str) -> bytes:
    return PNG_BASE + ("\n" + tag).encode("ascii")


def write_bytes(relative: str, payload: bytes) -> Path:
    path = FIXTURES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return path


def write_json(relative: str, data: object, *, bom: bool = False) -> Path:
    path = FIXTURES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, ensure_ascii=False, indent=1)
    path.write_text(text, encoding="utf-8-sig" if bom else "utf-8")
    return path


def resource(resource_id: str, root: str, entry: str = "resource.json", name: str = "", rtype: str = NEW.PORTRAIT_RESOURCE_TYPE):
    return {"id": resource_id, "type": rtype, "root": root, "entry": entry, "name": name or resource_id}


def visuals_manifest(resources, default="__omitted__", extra=None):
    visuals: dict = {"resources": resources}
    if default != "__omitted__":
        visuals["default"] = default
    manifest = {"id": "fixture", "visuals": visuals}
    if extra:
        manifest.update(extra)
    return manifest


class PackageStore:
    """镜像宿主 PluginCharacterStore.resolve_resource 的只读解析器。"""

    def __init__(self, packages: dict[str, Path]) -> None:
        self.packages = {key: Path(value).resolve() for key, value in packages.items()}

    def resolve_resource(self, character_id: str, relative_path: str) -> str:
        package = self.packages.get(str(character_id))
        if package is None:
            raise RuntimeError("CHARACTER_NOT_FOUND")
        raw = str(relative_path or "").strip()
        lexical = Path(raw)
        if (
            not raw
            or lexical.is_absolute()
            or lexical.drive
            or raw.startswith(("\\", "//"))
            or "\\" in raw
            or ".." in lexical.parts
        ):
            raise RuntimeError("CHARACTER_RESOURCE_INVALID")
        try:
            resolved = (package / lexical).resolve(strict=True)
            resolved.relative_to(package)
        except (OSError, ValueError) as error:
            raise RuntimeError("CHARACTER_RESOURCE_INVALID") from error
        if not resolved.is_file():
            raise RuntimeError("CHARACTER_RESOURCE_INVALID")
        return str(resolved)


class FakeMobile:
    def __init__(self, character_id: str) -> None:
        self.character_id = character_id

    def characters(self):
        return [{"id": self.character_id, "name": self.character_id, "initial_message": "", "current": "true"}]

    def theme(self):
        return {"primary_color": "#d55b91"}


def make_service(module, packages: dict[str, Path], character_id: str):
    return module.RemoteService(
        FIXTURES / "plugin-data",
        mobile_service=FakeMobile(character_id),
        character_service=PackageStore(packages),
        tts_service=None,
        logger=None,
        token=TOKEN,
    )


def free_port() -> int:
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
    probe.close()
    return port


class RunningServer:
    def __init__(self, module, packages: dict[str, Path], character_id: str) -> None:
        self.base = f"http://127.0.0.1:{free_port()}"
        self.server = module.run_remote_server(
            FIXTURES / "plugin-data",
            mobile_service=FakeMobile(character_id),
            character_service=PackageStore(packages),
            tts_service=None,
            host="127.0.0.1",
            port=int(self.base.rsplit(":", 1)[1]),
            token=TOKEN,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        time.sleep(0.2)

    def close(self) -> None:
        try:
            self.server.shutdown()
            self.server.server_close()
        except Exception:  # noqa: BLE001
            pass

    def get(self, path: str, *, token: str | None = TOKEN):
        headers = {"X-Sakura-Remote-Token": token} if token else {}
        request = urllib.request.Request(self.base + path, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read()


# ---- 夹具集合 ---------------------------------------------------------

def build_fixtures() -> dict[str, Path]:
    packages: dict[str, Path] = {}

    # 1. 新格式多形态：default 指向第二项，确保不按名字覆盖、也不取末项。
    for shape, tag in (("a", "A"), ("b", "B"), ("c", "C")):
        write_json(f"typed_multi/portrait/{shape}/resource.json",
                   {"default": "01.png", "expressions": {"害羞": "02.png"}})
        write_bytes(f"typed_multi/portrait/{shape}/01.png", png("multi-" + tag + "-1"))
        write_bytes(f"typed_multi/portrait/{shape}/02.png", png("multi-" + tag + "-2"))
    write_json("typed_multi/character.json", visuals_manifest(
        [resource("portrait-a", "portrait/a", name="形态A"),
         resource("portrait-b", "portrait/b", name="形态B"),
         resource("portrait-c", "portrait/c", name="形态C")],
        default="portrait-b"))
    packages["typed_multi"] = FIXTURES / "typed_multi"

    # 2. root='.' + BOM 配置：图片相对包根。
    write_json("typed_rootdot/resource.json",
               {"default": "img/default.png", "expressions": {"不满": "img/angry.png"}}, bom=True)
    write_bytes("typed_rootdot/img/default.png", png("rootdot-default"))
    write_bytes("typed_rootdot/img/angry.png", png("rootdot-angry"))
    write_json("typed_rootdot/character.json", visuals_manifest(
        [resource("portrait-default", ".", name="默认形态")], default="portrait-default"))
    packages["typed_rootdot"] = FIXTURES / "typed_rootdot"

    # 3. 缺省 default -> 取第一项（宿主语义），不是末项。
    for shape, tag in (("first", "F"), ("last", "L")):
        write_json(f"typed_first/portrait/{shape}/resource.json", {"default": "01.png"})
        write_bytes(f"typed_first/portrait/{shape}/01.png", png("first-" + tag))
    write_json("typed_first/character.json", visuals_manifest(
        [resource("portrait-first", "portrait/first"), resource("portrait-last", "portrait/last")]))
    packages["typed_first"] = FIXTURES / "typed_first"

    # 4. 重复 id。
    write_json("typed_dup/resource.json", {"default": "01.png"})
    write_bytes("typed_dup/01.png", png("dup"))
    write_json("typed_dup/character.json", visuals_manifest(
        [resource("portrait-x", ".", name="a"), resource("portrait-x", ".", name="b")],
        default="portrait-x"))
    packages["typed_dup"] = FIXTURES / "typed_dup"

    # 5. 无效默认 id。
    write_json("typed_bad_default/resource.json", {"default": "01.png"})
    write_bytes("typed_bad_default/01.png", png("bad-default"))
    write_json("typed_bad_default/character.json", visuals_manifest(
        [resource("portrait-x", ".", name="a")], default="portrait-missing"))
    packages["typed_bad_default"] = FIXTURES / "typed_bad_default"

    # 6. 非静态立绘类型。
    write_json("typed_live2d/character.json", visuals_manifest(
        [resource("model-1", "live2d", name="模型", rtype="sakura.visual.live2d@1")],
        default="model-1"))
    packages["typed_live2d"] = FIXTURES / "typed_live2d"

    # 7. 路径越界（root / entry / 图片路径）。
    write_json("typed_escape_root/character.json", visuals_manifest(
        [resource("portrait-x", "../outside")], default="portrait-x"))
    packages["typed_escape_root"] = FIXTURES / "typed_escape_root"
    write_json("typed_escape_entry/character.json", visuals_manifest(
        [resource("portrait-x", "portrait/x", entry="../evil.json")], default="portrait-x"))
    packages["typed_escape_entry"] = FIXTURES / "typed_escape_entry"
    write_json("typed_escape_image/resource.json", {"default": "../outside.png"})
    write_bytes("typed_escape_image/01.png", png("escape-image"))
    write_json("typed_escape_image/character.json", visuals_manifest(
        [resource("portrait-x", ".", name="x")], default="portrait-x"))
    packages["typed_escape_image"] = FIXTURES / "typed_escape_image"

    # 8. 图片缺失。
    write_json("typed_missing/resource.json", {"default": "missing.png"})
    write_json("typed_missing/character.json", visuals_manifest(
        [resource("portrait-x", ".", name="x")], default="portrait-x"))
    packages["typed_missing"] = FIXTURES / "typed_missing"

    # 9. 损坏 JSON / 空配置。
    write_bytes("typed_broken/resource.json", b"{ not json ")
    write_json("typed_broken/character.json", visuals_manifest(
        [resource("portrait-x", ".", name="x")], default="portrait-x"))
    packages["typed_broken"] = FIXTURES / "typed_broken"
    write_json("typed_empty/resource.json", {})
    write_json("typed_empty/character.json", visuals_manifest(
        [resource("portrait-x", ".", name="x")], default="portrait-x"))
    packages["typed_empty"] = FIXTURES / "typed_empty"

    # 10. 配置把 resource.json 本身当图片。
    write_json("typed_json_image/resource.json", {"default": "resource.json"})
    write_json("typed_json_image/character.json", visuals_manifest(
        [resource("portrait-x", ".", name="x")], default="portrait-x"))
    packages["typed_json_image"] = FIXTURES / "typed_json_image"

    # 11. 配置 / 形态变更后的缓存失效。
    write_json("typed_cache/resource.json", {"default": "img/a.png"})
    write_bytes("typed_cache/img/a.png", png("cache-v1"))
    write_bytes("typed_cache/img/b.png", png("cache-b"))
    write_json("typed_cache/character.json", visuals_manifest(
        [resource("portrait-a", "portrait/a", name="A"), resource("portrait-b", "portrait/b", name="B")],
        default="portrait-a"))
    write_json("typed_cache/portrait/a/resource.json", {"default": "img/a.png"})
    write_bytes("typed_cache/portrait/a/img/a.png", png("shape-a"))
    write_json("typed_cache/portrait/b/resource.json", {"default": "img/b.png"})
    write_bytes("typed_cache/portrait/b/img/b.png", png("shape-b"))
    packages["typed_cache"] = FIXTURES / "typed_cache"

    # 12. 旧格式 portrait.default/expressions。
    write_bytes("legacy_basic/portraits/A020.png", png("legacy-default"))
    write_bytes("legacy_basic/portraits/A081.png", png("legacy-shy"))
    write_json("legacy_basic/character.json", {
        "id": "legacy_basic",
        "portrait": {"default": "portraits/A020.png", "expressions": {"站立待机": "portraits/A020.png", "害羞脸红": "portraits/A081.png"}},
    })
    packages["legacy_basic"] = FIXTURES / "legacy_basic"

    # 13. 旧格式路径越界。
    write_json("legacy_escape/character.json", {
        "id": "legacy_escape",
        "portrait": {"default": "../outside.png", "expressions": {}},
    })
    packages["legacy_escape"] = FIXTURES / "legacy_escape"

    # 14. 有 visuals 时忽略 portrait（宿主语义）。
    write_json("typed_with_portrait/portrait/x/resource.json", {"default": "01.png"})
    write_bytes("typed_with_portrait/portrait/x/01.png", png("typed-wins"))
    write_bytes("typed_with_portrait/portraits/legacy.png", png("legacy-loses"))
    write_json("typed_with_portrait/character.json", {
        "id": "typed_with_portrait",
        "portrait": {"default": "portraits/legacy.png", "expressions": {}},
        "visuals": {"default": "portrait-x", "resources": [resource("portrait-x", "portrait/x", name="x")]},
    })
    packages["typed_with_portrait"] = FIXTURES / "typed_with_portrait"

    return packages


def unavailable_code(module, packages, character_id) -> str:
    service = make_service(module, packages, character_id)
    try:
        service._portrait_map(character_id)
        return ""
    except module.PortraitUnavailableError as error:
        return str(getattr(error, "code", ""))


# ---- 断言 -------------------------------------------------------------

def run(checker: Checker) -> None:
    packages = build_fixtures()

    # 1. 新格式多形态 default（不得按名字或取末项）
    service = make_service(NEW, packages, "typed_multi")
    mapping = service._portrait_map("typed_multi")
    checker.check(
        "新格式多形态按 visuals.default 选择",
        mapping == {"__default__": "portrait/b/01.png", "害羞": "portrait/b/02.png"},
        mapping,
    )
    checker.check("新格式动画路径不是 entry/resource.json", "resource.json" not in mapping.values(), mapping)
    checker.check("新格式动画路径不是末项资源", mapping["__default__"].startswith("portrait/b/"), mapping)

    # 2. root='.' + BOM
    service = make_service(NEW, packages, "typed_rootdot")
    mapping = service._portrait_map("typed_rootdot")
    checker.check(
        "root='.' + BOM 配置可读且图片相对包根",
        mapping == {"__default__": "img/default.png", "不满": "img/angry.png"},
        mapping,
    )
    entry = service.portrait_bytes("typed_rootdot", "__default__")
    checker.check("root='.' 的默认立绘返回 PNG", entry[0] == "image/png" and entry[1].startswith(b"\x89PNG"), entry[0])

    # 3. default 缺失取第一项
    service = make_service(NEW, packages, "typed_first")
    mapping = service._portrait_map("typed_first")
    checker.check("default 缺失时取第一项资源", mapping == {"__default__": "portrait/first/01.png"}, mapping)

    # 4-10. 确定行为
    for name, code in (
        ("typed_dup", "config_invalid"),
        ("typed_bad_default", "config_invalid"),
        ("typed_live2d", "unsupported_type"),
        ("typed_escape_root", "config_invalid"),
        ("typed_escape_entry", "config_invalid"),
        ("typed_escape_image", "config_invalid"),
        ("typed_broken", "config_invalid"),
        ("typed_empty", "config_invalid"),
    ):
        actual = unavailable_code(NEW, packages, name)
        checker.check(f"{name} 明确不可用（{code}）", actual == code, actual or "no error")

    # state 在这些角色上必须仍可返回
    service = make_service(NEW, packages, "typed_live2d")
    state = service.state()
    checker.check(
        "state 在无可用视觉时仍返回可恢复状态",
        state.get("portraits") == [] and state.get("portraitAvailable") is False
        and state.get("portraitIssue") == "unsupported_type" and state.get("noCharacter") is False,
        {key: state.get(key) for key in ("portraits", "portraitAvailable", "portraitIssue", "noCharacter")},
    )

    # 8. 图片缺失：map 有路径但读取给出确定错误，不冒充其它图片
    service = make_service(NEW, packages, "typed_missing")
    mapping = service._portrait_map("typed_missing")
    checker.check("图片缺失时 map 仍报出配置路径", mapping == {"__default__": "missing.png"}, mapping)
    try:
        service.portrait_bytes("typed_missing", "__default__")
        checker.check("缺失图片读取明确失败", False, "no error")
    except NEW.RemoteUnavailableError as error:
        checker.check("缺失图片读取明确失败", "立绘" in str(error) or "路径" in str(error), str(error))

    # 10. resource.json 不得作为图片返回
    service = make_service(NEW, packages, "typed_json_image")
    mapping = service._portrait_map("typed_json_image")
    checker.check("配置把 resource.json 当图片时 map 保留原路径", mapping == {"__default__": "resource.json"}, mapping)
    try:
        media_type, payload = service.portrait_bytes("typed_json_image", "__default__")
        checker.check("resource.json 不作为图片返回", False, (media_type, payload[:40]))
    except NEW.RemoteUnavailableError as error:
        checker.check("resource.json 不作为图片返回", "格式" in str(error), str(error))

    # 11. 缓存失效
    service = make_service(NEW, packages, "typed_cache")
    first = service.portrait_bytes("typed_cache", "__default__")
    checker.check("缓存首次命中形态 A", first[1] == png("shape-a"), first[1][:20])
    # 改配置内容（default 路径相同）+ 改图片字节
    write_json("typed_cache/portrait/a/resource.json",
               {"default": "img/a.png", "expressions": {"害羞": "img/a.png"}})
    write_bytes("typed_cache/portrait/a/img/a.png", png("shape-a-v2"))
    second = service.portrait_bytes("typed_cache", "__default__")
    checker.check("资源配置变更后缓存失效", second[1] == png("shape-a-v2"), second[1][:20])
    # 改选中形态
    write_json("typed_cache/character.json", visuals_manifest(
        [resource("portrait-a", "portrait/a", name="A"), resource("portrait-b", "portrait/b", name="B")],
        default="portrait-b"))
    third = service.portrait_bytes("typed_cache", "__default__")
    checker.check("选中形态变更后缓存失效", third[1] == png("shape-b"), third[1][:20])

    # 12/13/14 legacy
    service = make_service(NEW, packages, "legacy_basic")
    mapping = service._portrait_map("legacy_basic")
    checker.check(
        "旧格式 portrait.default/expressions 保持可用",
        mapping == {"__default__": "portraits/A020.png", "站立待机": "portraits/A020.png", "害羞脸红": "portraits/A081.png"},
        mapping,
    )
    checker.check("旧格式默认立绘返回 PNG", service.portrait_bytes("legacy_basic", "__default__")[0] == "image/png")
    checker.check("旧格式越界路径明确不可用", unavailable_code(NEW, packages, "legacy_escape") == "config_invalid")
    service = make_service(NEW, packages, "typed_with_portrait")
    checker.check(
        "有 visuals 时忽略 portrait（宿主语义）",
        service._portrait_map("typed_with_portrait") == {"__default__": "portrait/x/01.png"},
        service._portrait_map("typed_with_portrait"),
    )

    # 旧版 vs 新版：同一夹具上的 _portrait_map
    old_map = make_service(OLD, packages, "typed_rootdot")._portrait_map("typed_rootdot")
    new_map = make_service(NEW, packages, "typed_rootdot")._portrait_map("typed_rootdot")
    checker.check("旧版新格式解析把 entry 当图片（可复现失败）", old_map == {"默认形态": "resource.json"}, old_map)
    checker.check("新版新格式解析得到真实 PNG 路径", new_map == {"__default__": "img/default.png", "不满": "img/angry.png"}, new_map)

    # 真实安装角色：旧版失败 / 新版通过
    manifests: dict[str, dict] = {}
    for character_id in NEW_FORMAT + LEGACY_FORMAT:
        path = REAL_CHARACTERS / character_id / "character.json"
        if not path.is_file():
            continue
        manifests[character_id] = json.loads(path.read_text(encoding="utf-8-sig"))
    real_packages = {cid: REAL_CHARACTERS / cid for cid in manifests}
    if manifests:
        checker.check("找到真实安装角色", True, sorted(manifests))
    for character_id, manifest in manifests.items():
        visual_only = {key: manifest[key] for key in ("portrait", "visuals") if key in manifest}
        expected = _expected_map(REAL_CHARACTERS / character_id, visual_only)
        old_service = make_service(OLD, real_packages, character_id)
        old_service._manifest = lambda _, value=visual_only: value
        old_service.characters = PackageStore(real_packages)
        new_service = make_service(NEW, real_packages, character_id)
        old_actual = old_service._portrait_map(character_id)
        new_actual = new_service._portrait_map(character_id)
        old_paths_exist = all((REAL_CHARACTERS / character_id / p).is_file() for p in old_actual.values())
        schema = "新格式" if "visuals" in manifest else "旧格式"
        if "visuals" in manifest:
            checker.check(f"真实{schema} {character_id}：旧版失败", old_actual != expected or not old_paths_exist, old_actual)
            checker.check(f"真实{schema} {character_id}：新版通过", new_actual == expected, new_actual)
        else:
            checker.check(f"真实{schema} {character_id}：旧版本就正常", old_actual == expected, old_actual)
            checker.check(f"真实{schema} {character_id}：新版保持正常", new_actual == expected, new_actual)

    # 真实生产 HTTP 链路
    if manifests:
        for character_id in ("nikaidou_shinku", "Sakura"):
            package = REAL_CHARACTERS / character_id
            if not package.is_dir():
                continue
            server = RunningServer(NEW, {character_id: package}, character_id)
            try:
                status, _, body = server.get("/api/state?token=" + TOKEN)
                state = json.loads(body.decode("utf-8")) if status == 200 else {}
                url = next((item["url"] for item in state.get("portraits", []) if item["key"] == "__default__"), "")
                checker.check(f"真实 {character_id} state 返回默认立绘 URL", status == 200 and bool(url), (status, body[:120]))
                image_status, headers, image = server.get(url)
                checker.check(
                    f"真实 {character_id} /asset/portrait 返回 PNG",
                    image_status == 200 and headers.get("Content-Type") == "image/png" and image.startswith(b"\x89PNG"),
                    (image_status, headers.get("Content-Type"), image[:8]),
                )
            finally:
                server.close()

    # 合成 HTTP：旧版把 resource.json 当图片，新版返回 PNG
    old_server = RunningServer(OLD, {"typed_rootdot": packages["typed_rootdot"]}, "typed_rootdot")
    try:
        status, _, body = old_server.get("/api/state?token=" + TOKEN)
        state = json.loads(body.decode("utf-8")) if status == 200 else {}
        url = state.get("portraits", [{}])[0].get("url", "")
        image_status, headers, image = old_server.get(url)
        checker.check(
            "旧版 HTTP 把 resource.json 当图片返回",
            image_status == 200 and headers.get("Content-Type") != "image/png" and not image.startswith(b"\x89PNG"),
            (image_status, headers.get("Content-Type"), image[:20]),
        )
    finally:
        old_server.close()

    new_server = RunningServer(NEW, {"typed_rootdot": packages["typed_rootdot"]}, "typed_rootdot")
    try:
        status, _, body = new_server.get("/api/state?token=" + TOKEN)
        state = json.loads(body.decode("utf-8")) if status == 200 else {}
        default_item = next((item for item in state.get("portraits", []) if item["key"] == "__default__"), {})
        image_status, headers, image = new_server.get(default_item.get("url", "/nope"))
        checker.check(
            "新版 HTTP 新格式返回 PNG 字节与 image/png",
            status == 200 and image_status == 200 and headers.get("Content-Type") == "image/png" and image.startswith(b"\x89PNG"),
            (status, image_status, headers.get("Content-Type"), image[:8]),
        )
        json_item = next((item for item in state.get("portraits", []) if item["key"] == "不满"), {})
        json_status, _, _ = new_server.get(json_item.get("url", "/nope"))
        checker.check("新版 HTTP 表情切换路径同样可用", json_status == 200, json_status)
    finally:
        new_server.close()

    new_bad = RunningServer(NEW, {"typed_json_image": packages["typed_json_image"]}, "typed_json_image")
    try:
        status, _, body = new_bad.get("/api/state?token=" + TOKEN)
        state = json.loads(body.decode("utf-8")) if status == 200 else {}
        url = state.get("portraits", [{}])[0].get("url", "")
        image_status, _, _ = new_bad.get(url)
        checker.check("新版 HTTP 拒绝把 resource.json 当图片（非 200）", image_status != 200, image_status)
    finally:
        new_bad.close()

    # 真实角色也有 visuals 时忽略 portrait 的宿主语义已由上面覆盖；
    # 表情切换证据：新格式 keys 与语气标签一致
    if "nikaidou_shinku" in manifests:
        service = make_service(NEW, real_packages, "nikaidou_shinku")
        keys = set(service._portrait_map("nikaidou_shinku"))
        checker.check(
            "新格式立绘标签可直接匹配聊天语气（表情切换无 DTO 适配缺口）",
            {"中性", "不满", "害羞", "请求", "困惑", "惊讶", "得意"} <= keys,
            sorted(keys),
        )


def _expected_map(package: Path, manifest: dict) -> dict[str, str]:
    visuals = manifest.get("visuals") or {}
    resources = visuals.get("resources") or []
    selected_id = visuals.get("default")
    if selected_id is None and resources:
        selected_id = resources[0].get("id")
    selected = next((item for item in resources if item.get("id") == selected_id), None)
    if selected and selected.get("type") == "sakura.visual.portrait@1":
        prefix = "" if selected["root"] == "." else selected["root"] + "/"
        config = json.loads((package / (prefix + selected["entry"])).read_text(encoding="utf-8-sig"))
        result = {"__default__": prefix + config["default"]}
        result.update({key: prefix + value for key, value in config.get("expressions", {}).items()})
        return result
    portrait = manifest.get("portrait")
    if isinstance(portrait, dict):
        result = {"__default__": portrait["default"]}
        result.update(portrait.get("expressions", {}))
        return result
    return {}


def main() -> int:
    if FIXTURES.exists():
        shutil.rmtree(FIXTURES, ignore_errors=True)
    FIXTURES.mkdir(parents=True, exist_ok=True)
    checker = Checker()
    run(checker)
    summary = {
        "total": len(checker.results),
        "passed": len(checker.results) - checker.failures,
        "failed": checker.failures,
        "results": checker.results,
        "oldImplementation": str(BACKUP_HTTP.relative_to(ROOT)) if BACKUP_HTTP.is_relative_to(ROOT) else str(BACKUP_HTTP),
        "newImplementation": str((PLUGIN / "http_server.py").relative_to(ROOT))
        if (PLUGIN / "http_server.py").is_relative_to(ROOT) else str(PLUGIN / "http_server.py"),
    }
    validation = _validation_dir(ROOT)
    if validation.is_dir():
        validation.mkdir(parents=True, exist_ok=True)
        (validation / "portrait-compat-evidence.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{summary['passed']}/{summary['total']} 项通过，失败 {summary['failed']}")
    return 1 if checker.failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
