"""sakura_remote 的 HTTP 服务层。

只使用 Python 标准库：ThreadingHTTPServer + 内嵌网页，
因此插件部署时不需要任何额外依赖或前端构建步骤。
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import ipaddress
import json
import os
import re
import secrets
import socket
import subprocess
import threading
import time
from collections import deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import parse_qs, quote, urlparse

try:
    from .web_ui import render_page
except ImportError:  # 宿主以顶层模块方式加载本文件时没有包上下文
    import sys as _sys

    _here = str(Path(__file__).resolve().parent)
    if _here not in _sys.path:
        _sys.path.insert(0, _here)
    from web_ui import render_page  # type: ignore[no-redef]


DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8770

# sakura.host.mobile 的 begin/poll/cancel 需要显式传入调用方插件 ID
# （框架只把 caller 放进 ContextVar，不会替你补第一个位置参数）。
PLUGIN_SERVICE_CALLER = "sakura.remote"
MAX_REQUEST_BYTES = 16 * 1024 * 1024
MAX_IMAGE_BYTES = 8 * 1024 * 1024
SOCKET_TIMEOUT_SECONDS = 30
CHAT_TIMEOUT_SECONDS = 120.0
CHAT_POLL_SECONDS = 0.2
TTS_TIMEOUT_SECONDS = 180.0
TTS_POLL_SECONDS = 0.15
TTS_CACHE_ENTRIES = 64
MAX_CONCURRENT_REQUESTS = 12
MAX_REQUESTS_PER_MINUTE = 240
TAILSCALE_CGNAT = ipaddress.ip_network("100.64.0.0/10")
STATIC_DIR_NAME = "static"

_MIME_BY_SUFFIX = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".gif": "image/gif",
    ".bmp": "image/bmp",
    ".avif": "image/avif",
}
_IMAGE_UPLOAD_TYPES = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/gif": ".gif",
    "image/heic": ".heic",
    "image/heif": ".heif",
}
_SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")

# 立绘名和语气名不一定对得上（例如语气「中性」没有明显的立绘关键词）。
# 这里只给出跨角色都安全的最小默认，用户可以用插件数据目录里的
# portrait_map.json 覆盖，key 是语气名，value 是立绘 key。
DEFAULT_PORTRAIT_OVERRIDES = {
    "中性": "站立待机",
    "平静": "站立待机",
}


class RemoteBusyError(RuntimeError):
    """另一个对话正在进行时抛出。"""


class RemoteUnavailableError(RuntimeError):
    """宿主能力不可用时抛出。"""


def manifest_path(value: Path) -> Path:
    """去掉 Windows 的 \\\\?\\ 扩展长度前缀。

    `Path.resolve()` 会给出带该前缀的路径，而 PowerShell 的
    `-File` / `Start-Process` **不接受**带前缀的路径 —— 传过去会静默失败
    （实测重启脚本进程起来了却什么都没做）。这里只剥离前缀，不做 resolve。
    """

    text = str(value)
    if text.startswith("\\\\?\\"):
        text = text[4:]
    return Path(text)


def run_remote_server(
    base_dir: Path,
    *,
    mobile_service: Any,
    character_service: Any,
    tts_service: Any = None,
    host: str = DEFAULT_HOST,
    port: int = DEFAULT_PORT,
    token: str = "",
    autoplay: bool = True,
    tts_enabled: bool = True,
    logger: Any = None,
    config_saver: Any = None,
) -> ThreadingHTTPServer:
    clean_token = token.strip() or secrets.token_urlsafe(10)
    # 端口已被占用就直接失败。
    # 为什么重要：远程重启会先起新进程再退旧进程，若新进程没能接管端口，
    # 旧进程一退就什么都没有了。有这个检查，新进程会立刻报错退出，
    # 旧进程发现自己还在服务就知道重启没成功，可以如实回报。
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        probe.bind(("127.0.0.1", port))
    except OSError as error:
        raise OSError(f"端口 {port} 已被占用：{error}") from error
    finally:
        probe.close()
    service = RemoteService(
        base_dir,
        mobile_service=mobile_service,
        character_service=character_service,
        tts_service=tts_service,
        logger=logger,
        autoplay=autoplay,
        tts_enabled=tts_enabled,
        token=clean_token,
        port=port,
        config_saver=config_saver,
    )
    handler_class = _build_handler(service, clean_token)
    server = RemoteHTTPServer((host, port), handler_class)
    server.service = service  # type: ignore[attr-defined]
    server.remote_token = clean_token  # type: ignore[attr-defined]
    service.log(
        "info",
        "server_created",
        {"host": host, "port": port, "tts": service.tts_available()},
    )
    return server


def mobile_access_urls(host: str, port: int, token: str) -> dict[str, Any]:
    query = f"?token={quote(token.strip())}"
    local_url = f"http://127.0.0.1:{int(port)}/{query}"
    lan_urls = [f"http://{address}:{int(port)}/{query}" for address in local_ipv4_addresses()]
    return {"host": host, "local_url": local_url, "lan_urls": lan_urls}


def local_ipv4_addresses() -> list[str]:
    addresses: set[str] = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addresses.add(str(info[4][0]))
    except OSError:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            addresses.add(str(sock.getsockname()[0]))
    except OSError:
        pass
    return sorted(address for address in addresses if _is_lan_ipv4(address))


def _is_lan_ipv4(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    if ip.version != 4 or ip.is_loopback or ip.is_unspecified or ip.is_multicast:
        return False
    return ip.is_private or ip in TAILSCALE_CGNAT


class RemoteHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 64

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        self._request_slots = threading.BoundedSemaphore(MAX_CONCURRENT_REQUESTS)
        self._rate_lock = threading.Lock()
        self._request_times: dict[str, deque[float]] = {}
        super().__init__(*args, **kwargs)

    def process_request(self, request: Any, client_address: Any) -> None:
        if not self._request_slots.acquire(blocking=False):
            request.close()
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()

    def allow_client_request(self, client_address: object) -> bool:
        client = _client_address_text(client_address).rsplit(":", 1)[0]
        now = time.monotonic()
        with self._rate_lock:
            requests = self._request_times.setdefault(client, deque())
            while requests and now - requests[0] >= 60:
                requests.popleft()
            if len(requests) >= MAX_REQUESTS_PER_MINUTE:
                return False
            requests.append(now)
            return True

    def get_request(self):  # type: ignore[no-untyped-def]
        request, client_address = super().get_request()
        request.settimeout(SOCKET_TIMEOUT_SECONDS)
        return request, client_address

    def handle_error(self, request: object, client_address: object) -> None:
        service = getattr(self, "service", None)
        if service is not None:
            service.log("warning", "http_connection_handler_failed", {
                "client": _client_address_text(client_address),
            })


class RemoteService:
    """把宿主 Host Service 收敛成手机端需要的那几个动作。"""

    def __init__(
        self,
        base_dir: Path,
        *,
        mobile_service: Any,
        character_service: Any,
        tts_service: Any,
        logger: Any,
        autoplay: bool = True,
        tts_enabled: bool = True,
        token: str = "",
        port: int = 0,
        config_saver: Any = None,
    ) -> None:
        self.base_dir = Path(base_dir)
        self.mobile = mobile_service
        self.characters = character_service
        self.tts = tts_service
        self.logger = logger
        self.autoplay = bool(autoplay)
        self.tts_enabled = bool(tts_enabled)
        self.token = str(token or "")
        # 远程重启脚本要拿它做端口探测，所以服务侧要记住实际监听端口
        self.port = int(port or 0)
        # 由插件注入：把设置写回 PluginConfig（网页不能直接碰宿主配置）
        self.config_saver = config_saver
        self._cache_dir = self.base_dir / "cache" / "tts"
        self._upload_dir = self.base_dir / "cache" / "uploads"
        # 插件数据目录形如 <user_root>/data/plugins/<plugin_id>，
        # 因此上溯两级就是 Sakura 的 data 目录。
        self._data_root = self.base_dir.parent.parent
        # 再上溯一级是 Sakura 根目录，配置在 <root>/config/ 下。
        # 当前角色记在 config/characters.yaml 的 current_character_id。
        self._config_root = self._data_root.parent / "config"
        self._artifact_root = self._data_root / "cache" / "plugin-artifacts"
        self._static_dir = Path(__file__).resolve().parent / STATIC_DIR_NAME
        self._state_lock = threading.RLock()
        self._portrait_cache: dict[str, tuple[str, bytes]] = {}
        self._tts_cache: dict[str, Path] = {}
        self._tts_lock = threading.RLock()
        self._character_cache: dict[str, Any] = {}

    # ---- 日志 -----------------------------------------------------

    def log(self, level: str, event: str, fields: Mapping[str, Any] | None = None) -> None:
        logger = self.logger
        if logger is None:
            return
        try:
            method = getattr(logger, level, None) or getattr(logger, "info")
            method(event, fields=dict(fields or {}))
        except Exception:  # noqa: BLE001
            pass

    def log_access(self, method: str, path: str, client: object, status: int) -> None:
        try:
            record = {
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
                "method": method,
                "path": path,
                "client": _client_address_text(client),
                "status": status,
            }
            path_obj = self.base_dir / "logs"
            path_obj.mkdir(parents=True, exist_ok=True)
            with (path_obj / "remote-access.log").open("a", encoding="utf-8") as file:
                file.write(json.dumps(record, ensure_ascii=False) + "\n")
        except OSError:
            pass

    # ---- 能力 -----------------------------------------------------

    def tts_available(self) -> bool:
        return bool(self.tts_enabled and self.tts is not None)

    # ---- 设置读写 -------------------------------------------------

    def ui_settings(self) -> dict[str, Any]:
        merged = {
            "autoplay": self.autoplay,
            "tts_enabled": self.tts_available(),
        }
        try:
            override = json.loads((self.base_dir / "config.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            override = {}
        if isinstance(override, Mapping):
            if "autoplay" in override:
                merged["autoplay"] = bool(override["autoplay"])
            if "tts_enabled" in override:
                merged["tts_enabled"] = bool(override["tts_enabled"]) and self.tts is not None
        return merged

    # 允许网页修改的字段；host/port/token 不在其中，改监听地址必须走桌面端设置，
    # 否则网页一改就把自己的连接掐断了。
    EDITABLE_FIELDS = ("autoplay", "tts_enabled")

    def settings_payload(self) -> dict[str, Any]:
        """给手机端配置页用的完整设置快照。"""

        return {
            "settings": self.ui_settings(),
            "editable": list(self.EDITABLE_FIELDS),
            "server": {
                "tts_service": self.tts is not None,
                "character_service": self.characters is not None,
                "portrait_overrides": self.portrait_overrides(),
            },
        }

    def save_settings(self, values: Mapping[str, Any]) -> dict[str, Any]:
        """只接受白名单字段，避免网页改坏监听配置。"""

        if not isinstance(values, Mapping):
            raise ValueError("设置内容无效。")
        updates: dict[str, Any] = {}
        for key in self.EDITABLE_FIELDS:
            if key in values:
                updates[key] = bool(values[key])
        if not updates:
            raise ValueError("没有可保存的设置项。")
        if self.config_saver is None:
            raise RemoteUnavailableError("当前环境不支持修改插件设置。")
        self.config_saver(updates)
        # 让内存里的值立刻生效，不必等插件重启
        if "autoplay" in updates:
            self.autoplay = bool(updates["autoplay"])
        if "tts_enabled" in updates:
            self.tts_enabled = bool(updates["tts_enabled"])
        self.log("info", "settings_saved", {"keys": sorted(updates)})
        return self.settings_payload()


    # ---- 角色与立绘 -------------------------------------------------

    def characters_list(self) -> list[dict[str, str]]:
        result = self.mobile.characters()
        if not isinstance(result, list):
            raise RemoteUnavailableError("角色列表不可用。")
        return [dict(item) for item in result if isinstance(item, Mapping)]

    def current_character_id(self) -> str:
        for item in self.characters_list():
            if str(item.get("current")) == "true":
                return str(item.get("id") or "")
        raise RemoteUnavailableError("当前角色尚未就绪。")

    def characters_payload(self) -> dict[str, Any]:
        """角色列表，供手机端展示与「切换角色」用。

        注意：宿主只允许手机端和**当前角色**对话
        （`sakura.host.mobile` 的 `_current_character` 在角色不匹配时直接抛
        `MOBILE_CHARACTER_NOT_CURRENT`），所以这里只能列出角色、
        不能真正切换当前角色 —— 切换必须由用户在电脑端操作。
        """
        items = []
        current = ""
        unavailable = ""
        try:
            raw_items = self.characters_list()
        except Exception as error:  # noqa: BLE001
            # 全新安装 / 角色服务未就绪时不该 500：配置页要用这个接口列角色，
            # 报错会让整块界面失去意义。退回空列表并说明原因。
            raw_items = []
            unavailable = type(error).__name__
            self.log("warning", "characters_unavailable", {"error_type": unavailable})
        for item in raw_items:
            is_current = str(item.get("current")) == "true"
            character_id = str(item.get("id") or "")
            if is_current:
                current = character_id
            items.append({
                "id": character_id,
                "name": str(item.get("name") or character_id),
                "initial_message": str(item.get("initial_message") or ""),
                "current": is_current,
            })
        payload = {"characters": items, "current": current}
        if unavailable:
            payload["unavailable"] = unavailable
        return payload

    # ---- 切换角色 -------------------------------------------------

    def character_config_path(self) -> Path:
        return self._config_root / "characters.yaml"

    def select_character(self, character_id: str) -> dict[str, Any]:
        """把「当前角色」写进 config/characters.yaml。

        为什么只能这样：没有任何 host service 能切换当前角色
        （`characters.settings.select` 挂在桌面 IPC 上，要 generation 凭据，
        不暴露给插件）。所以这里直接改配置文件。

        **必须重启 Sakura 才生效**：没有任何 watcher 监听这个文件，
        运行中的程序不会重新读它。所以返回值里带 restart_required，
        由手机端明确提示用户。
        """
        target = str(character_id or "").strip()
        if not target:
            raise ValueError("没有指定角色。")
        # 校验目标角色确实存在。角色服务读不到时给出可操作的说明，
        # 而不是让它变成 500 或写进一个不存在的 id。
        try:
            known = {str(item.get("id") or "") for item in self.characters_list()}
        except Exception as error:  # noqa: BLE001
            raise RemoteUnavailableError(
                "读不到电脑端的角色列表，暂时无法切换。请确认 Sakura 已启动。"
            ) from error
        if not known:
            raise ValueError("电脑端还没有任何角色，先在电脑端导入角色再切换。")
        if target not in known:
            raise ValueError("角色不存在：" + target)

        path = self.character_config_path()
        if not path.exists():
            raise RemoteUnavailableError("电脑端还没有角色配置文件，请先在电脑端选一次角色。")
        try:
            original = path.read_text(encoding="utf-8")
        except OSError as error:
            raise RemoteUnavailableError("读不到电脑端的角色配置。") from error

        marker = "current_character_id:"
        lines = original.splitlines()
        replaced = False
        for index, line in enumerate(lines):
            if line.lstrip().startswith(marker):
                indent = line[:len(line) - len(line.lstrip())]
                lines[index] = indent + marker + " " + target
                replaced = True
                break
        if not replaced:
            lines.insert(0, marker + " " + target)
        updated = "\n".join(lines)
        if original.endswith("\n"):
            updated += "\n"

        # 备份一次：这是改用户配置，出问题要能还原
        try:
            backup = path.with_suffix(path.suffix + ".bak-remote")
            if not backup.exists():
                backup.write_text(original, encoding="utf-8")
            temporary = path.with_suffix(path.suffix + ".tmp-remote")
            temporary.write_text(updated, encoding="utf-8")
            temporary.replace(path)
        except OSError as error:
            raise RemoteUnavailableError("写入角色配置失败。") from error

        self.log("info", "character_selected", {"character_id": target})
        payload = self.characters_payload()
        payload["restart_required"] = True
        payload["selected"] = target
        return payload

    # ---- 远程重启 -------------------------------------------------

    def sakura_root(self) -> Path:
        return self._data_root.parent

    def restart_sakura(self) -> dict[str, Any]:
        """重启电脑端的 Sakura，让配置改动（如切换角色）生效。

        为什么需要重启：`characters.yaml` 只在启动时读一次，运行中的会话把
        角色锁在内存里，改文件不会热生效（实测改完后用新角色聊天仍报
        MOBILE_CHARACTER_NOT_CURRENT）。

        真正干活的是一次性 PowerShell 脚本，必须独立于本进程运行 ——
        Sakura 退出会带走自己的子进程。脚本先优雅关闭、再启动、确认端口
        回来，失败会重试（见 restart_helper.ps1）。
        """
        if os.name != "nt":
            raise RemoteUnavailableError("远程重启目前只支持 Windows。")

        # 注意：这里刻意不用 Path.resolve()。
        # 它会返回带 \\?\ 扩展长度前缀的路径，而 PowerShell 的 -File
        # 不接受这种路径，脚本会立刻退出、连日志都来不及写
        #（实测 helper_pid 有了但什么都没发生）。
        helper = manifest_path(Path(__file__).parent / "restart_helper.ps1")
        if not helper.exists():
            raise RemoteUnavailableError("缺少重启脚本 restart_helper.ps1。")

        root = manifest_path(self.sakura_root())
        exe = root / "sakura.exe"
        if not exe.exists():
            raise RemoteUnavailableError("找不到 sakura.exe，无法重启。")

        # 记住自己的 pid 供 helper 参考；但 helper 实际按可执行文件路径清理，
        # 因为它可能是 PyInstaller 引导进程，真正跑服务的未必是这个 pid。
        previous_pid = os.getpid()
        self.log("info", "restart_spawn", {"helper": str(helper)})

        # helper 必须完全独立于 Sakura。
        #
        # 依赖 subprocess 的 creationflags 都不行（实测均失败）：
        #   * CREATE_NEW_PROCESS_GROUP —— 不足以脱离，Sakura 退出时 helper 被一起带走，
        #     停在「刚关闭旧进程」那一步，结果关掉后没人拉起来；
        #   * DETACHED_PROCESS —— 脚本启动即失败，日志全空；
        #   * cmd 的 `start /b` —— 同样日志全空；
        #   * WMI Win32_Process.Create —— 引号要跨 Python/PowerShell/WMI 三层转义，太脆。
        # 看起来 Sakura 把子进程限制在 Windows Job Object 里，继承来的进程逃不掉。
        #
        # 改用 Windows 计划任务：由任务计划服务创建进程，天然不属于调用者的 job。
        # 用参数列表传参，不需要任何引号转义。
        # 参数写进 JSON 文件，而不是命令行。
        # 原因：这个脚本是通过 `schtasks /TR` 拉起的，而那个参数有 **261 字符上限**，
        # 四个路径内联进去会超（实测 269，报「/TR 选项的值超出 261 字符」）。
        # 用配置文件后，任务命令行短且固定。
        config_path = self._data_root / "plugins" / "sakura.remote" / "restart-args.json"
        try:
            config_path.parent.mkdir(parents=True, exist_ok=True)
            config_path.write_text(
                json.dumps({
                    "oldPid": previous_pid,
                    "port": self.port,
                    "exePath": str(exe),
                    "workDir": str(root),
                    # 两段式重启的第二段要用它自己拉起自己
                    "helperPath": str(helper),
                }, ensure_ascii=False),
                encoding="utf-8",
            )
        except OSError as error:
            self.log("error", "restart_config_failed", {
                "error": f"{type(error).__name__}: {error}",
            })
            raise RemoteUnavailableError("写入重启参数失败。") from error

        task_name = "SakuraRemoteRestart"
        # 用 PowerShell 的 ScheduledTasks 模块，而不是 schtasks.exe。
        #
        # 为什么：schtasks 的 /TR 是**单个字符串**，引号要跨 shell 转义，
        # 极其容易出错 —— 实测 `\"` 写法会让它把 `-NoProfile` 当成独立参数并报
        # 「Invalid argument/option - '-NoProfile'」。而 New-ScheduledTaskAction
        # 的 -Execute / -Argument 是分开的参数，由 PowerShell 负责转义，不会踩这个坑。
        register = (
            "$ErrorActionPreference='Stop';"
            "$a=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '"
            + "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "
            + '"' + str(helper) + '"'
            + " -ConfigPath "
            + '"' + str(config_path) + '"'
            + " -Stage stop';"
            "$t=New-ScheduledTaskTrigger -Once -At (Get-Date);"
            "Register-ScheduledTask -TaskName '" + task_name + "' "
            "-Action $a -Trigger $t -Force | Out-Null;"
            "Start-ScheduledTask -TaskName '" + task_name + "';"
            "Start-Sleep -Seconds 2;"
            "Unregister-ScheduledTask -TaskName '" + task_name + "' -Confirm:$false"
        )
        run = subprocess.run(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
             "-Command", register],
            capture_output=True, timeout=60,
        )
        if run.returncode != 0:
            detail = (run.stderr or run.stdout or b"").decode("utf-8", "replace")[:300]
            self.log("error", "restart_task_failed", {"detail": detail})
            raise RemoteUnavailableError("创建或启动重启任务失败。")

        self.log("info", "restart_requested", {"pid": previous_pid})
        return {
            "ok": True,
            "restarting": True,
            "message": "正在重启电脑端 Sakura，大约 10–40 秒。"
                       "期间连接会断开，之后需要重新打开 App。",
        }

    def _manifest(self, character_id: str) -> dict[str, Any]:
        with self._state_lock:
            cached = self._character_cache.get(character_id)
            if cached is not None:
                return cached
        manifest_path = Path(
            self.characters.resolve_resource(character_id, "character.json")
        )
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise RemoteUnavailableError("角色配置读取失败。") from error
        if not isinstance(manifest, dict):
            raise RemoteUnavailableError("角色配置格式无效。")
        with self._state_lock:
            self._character_cache[character_id] = manifest
        return manifest

    def _portrait_map(self, character_id: str) -> dict[str, str]:
        manifest = self._manifest(character_id)
        portrait = manifest.get("portrait")
        result: dict[str, str] = {}
        if isinstance(portrait, Mapping):
            default = portrait.get("default")
            if isinstance(default, str) and default.strip():
                result["__default__"] = default.strip()
            expressions = portrait.get("expressions")
            if isinstance(expressions, Mapping):
                for key, value in expressions.items():
                    if isinstance(key, str) and isinstance(value, str) and value.strip():
                        result[key] = value.strip()
        visuals = manifest.get("visuals")
        if not result and isinstance(visuals, Mapping):
            for resource in visuals.get("resources") or []:
                if not isinstance(resource, Mapping):
                    continue
                entry = resource.get("entry")
                if isinstance(entry, str) and entry.strip():
                    result[str(resource.get("name") or "立绘")] = entry.strip()
        if not result:
            raise RemoteUnavailableError("当前角色没有可用立绘。")
        return result

    def state(self) -> dict[str, Any]:
        """当前角色、立绘、语气等状态。

        **没有可用角色时也要正常返回**，不能抛异常。
        为什么：全新安装时电脑端可能还没导入任何角色，而手机端
        `loadState()` 一旦失败就会整页显示「无法连接电脑端」，
        连配置页都打不开 —— 用户就没法自救（改地址、看说明）。
        实测过这个连锁反应，所以这里降级成「空角色」而不是报错。
        """
        try:
            character_id = self.current_character_id()
        except Exception as error:  # noqa: BLE001 - 无角色是正常状态，不是错误
            self.log("warning", "state_no_character", {
                "error_type": type(error).__name__,
            })
            return {
                "characterId": "",
                "displayName": "",
                "portraits": [],
                "defaultPortraitKey": "",
                "tones": [],
                "portraitOverrides": self.portrait_overrides(),
                "theme": {},
                "settings": self.ui_settings(),
                "noCharacter": True,
            }

        portraits = self._portrait_map(character_id)
        manifest = self._manifest(character_id)
        theme: dict[str, Any] = {}
        try:
            raw_theme = self.mobile.theme()
            if isinstance(raw_theme, Mapping):
                theme = dict(raw_theme)
        except Exception as error:  # noqa: BLE001 - 主题只是外观增强
            self.log("warning", "theme_unavailable", {"error_type": type(error).__name__})
        tones: list[str] = []
        reply = manifest.get("reply")
        if isinstance(reply, Mapping):
            raw_tones = reply.get("tones")
            if isinstance(raw_tones, list):
                tones = [str(item) for item in raw_tones if isinstance(item, str) and item.strip()]
        return {
            "characterId": character_id,
            "displayName": self._display_name(character_id),
            "portraits": [
                {"key": key, "url": self.portrait_url(character_id, key)}
                for key in portraits
            ],
            # 没有立绘时别去 next(iter(...)) —— 空集合会抛 StopIteration
            "defaultPortraitKey": (
                "__default__" if "__default__" in portraits
                else (next(iter(portraits)) if portraits else "")
            ),
            "tones": tones,
            "portraitOverrides": self.portrait_overrides(),
            "theme": theme,
            "settings": self.ui_settings(),
            "noCharacter": False,
        }

    def _display_name(self, character_id: str) -> str:
        # characters_list() 在角色服务不可用时会抛异常，而名字只是显示用，
        # 不该因此让整个 /api/state 失败 —— 拿不到就退回 id。
        try:
            for item in self.characters_list():
                if str(item.get("id")) == character_id:
                    return str(item.get("name") or character_id)
        except Exception as error:  # noqa: BLE001
            self.log("warning", "display_name_unavailable", {
                "error_type": type(error).__name__,
            })
        return character_id

    def portrait_overrides(self) -> dict[str, str]:
        """语气 -> 立绘 的手工映射，先取默认，再被用户文件覆盖。"""

        result = dict(DEFAULT_PORTRAIT_OVERRIDES)
        try:
            raw = json.loads(
                (self.base_dir / "portrait_map.json").read_text(encoding="utf-8")
            )
        except (OSError, ValueError):
            raw = None
        if isinstance(raw, Mapping):
            for key, value in raw.items():
                if isinstance(key, str) and isinstance(value, str) and value.strip():
                    result[key] = value.strip()
        return result

    def portrait_url(self, character_id: str, key: str) -> str:
        """返回自包含 token 的立绘地址。

        <img src> 不会经过 app.js 的 api() 包装，所以 token 必须在服务端拼好，
        否则手机端会拿到 400。
        """

        query = f"character={quote(character_id)}&key={quote(key)}"
        if self.token:
            query += f"&token={quote(self.token)}"
        return f"/asset/portrait?{query}"

    def portrait_bytes(self, character_id: str, key: str) -> tuple[str, bytes]:
        portraits = self._portrait_map(character_id)
        relative = portraits.get(key) or portraits.get("__default__")
        if not relative:
            raise RemoteUnavailableError("找不到该立绘。")
        cache_key = f"{character_id}\x00{relative}"
        with self._state_lock:
            cached = self._portrait_cache.get(cache_key)
            if cached is not None:
                return cached
        absolute = Path(self.characters.resolve_resource(character_id, relative))
        try:
            payload = absolute.read_bytes()
        except OSError as error:
            raise RemoteUnavailableError("立绘文件读取失败。") from error
        if not payload:
            raise RemoteUnavailableError("立绘文件为空。")
        media_type = _MIME_BY_SUFFIX.get(absolute.suffix.lower(), "application/octet-stream")
        entry = (media_type, payload)
        with self._state_lock:
            if len(self._portrait_cache) > 64:
                self._portrait_cache.clear()
            self._portrait_cache[cache_key] = entry
        return entry

    # ---- 诊断 -----------------------------------------------------

    # ---- 聊天 -----------------------------------------------------

    def chat(self, character_id: str, text: str, image_data_url: str = "") -> dict[str, Any]:
        clean_text = text.strip()
        if not clean_text and not image_data_url:
            raise ValueError("消息内容不能为空。")
        job_id = ""
        try:
            # 注意参数形状：sakura.host.mobile 的 begin/poll/cancel 第一个位置参数
            # 是调用方插件 ID（框架不会自动注入），history/characters 则不需要。
            started = self.mobile.begin(PLUGIN_SERVICE_CALLER, character_id, clean_text, image_data_url)
            if isinstance(started, Mapping):
                job_id = str(started.get("jobId") or "")
            if not job_id:
                raise RemoteUnavailableError("手机会话任务创建失败。")
            deadline = time.monotonic() + CHAT_TIMEOUT_SECONDS
            while time.monotonic() < deadline:
                state = self.mobile.poll(PLUGIN_SERVICE_CALLER, job_id)
                if isinstance(state, Mapping) and state.get("status") == "completed":
                    result = state.get("result")
                    if not isinstance(result, Mapping):
                        raise RemoteUnavailableError("手机会话结果无效。")
                    return dict(result)
                time.sleep(CHAT_POLL_SECONDS)
            self._cancel(job_id)
            raise TimeoutError("等待回复超时，请重试。")
        except Exception as error:  # noqa: BLE001
            code = str(getattr(error, "code", "") or "")
            if code == "CHAT_EXECUTION_LIMIT_EXCEEDED":
                raise RemoteBusyError("另一个对话正在进行，请稍后重试。") from error
            raise

    def _cancel(self, job_id: str) -> None:
        try:
            self.mobile.cancel(PLUGIN_SERVICE_CALLER, job_id)
        except Exception:  # noqa: BLE001
            pass

    def image_data_url(self, asset_url: str) -> str:
        """把 /api/upload 返回的资源路径转回 data URL 交给宿主。"""

        clean = str(asset_url or "").strip()
        if not clean:
            return ""
        prefix = "/cache/uploads/"
        if not clean.startswith(prefix):
            return ""
        name = clean[len(prefix):].strip()
        if not name or "/" in name or "\\" in name or ".." in name:
            return ""
        path = self._upload_dir / name
        try:
            payload = path.read_bytes()
        except OSError as error:
            raise ValueError("图片已过期，请重新选择。") from error
        if not payload:
            raise ValueError("图片已过期，请重新选择。")
        media_type = _MIME_BY_SUFFIX.get(path.suffix.lower(), "image/png")
        return f"data:{media_type};base64," + base64.b64encode(payload).decode("ascii")

    # ---- 语音 -----------------------------------------------------

    def synthesize(self, character_id: str, text: str, tone: str = "") -> Path:
        if not self.tts_available():
            raise RemoteUnavailableError("语音合成未启用。")
        clean_text = text.strip()
        if not clean_text:
            raise ValueError("语音文本为空。")
        cache_key = hashlib.sha256(
            f"{character_id}\x00{tone}\x00{clean_text}".encode("utf-8")
        ).hexdigest()
        with self._tts_lock:
            cached = self._tts_cache.get(cache_key)
        if cached is not None and cached.is_file():
            return cached

        request_id = f"remote-{secrets.token_hex(12)}"
        result = self.tts.begin(
            {
                "requestId": request_id,
                "characterId": character_id,
                "text": clean_text,
                "options": {"tone": tone, "portrait": ""},
            }
        )
        if not isinstance(result, Mapping):
            raise RemoteUnavailableError("语音合成服务返回无效结果。")
        if str(result.get("state")) == "failed":
            raise RemoteUnavailableError(
                f"语音合成失败：{result.get('errorCode') or 'unknown'}"
            )
        provider_id = str(result.get("providerId") or "")
        audio_path = self._await_tts(request_id, provider_id)
        if audio_path is None:
            raise TimeoutError("语音合成超时。")
        with self._tts_lock:
            self._tts_cache[cache_key] = audio_path
            if len(self._tts_cache) > TTS_CACHE_ENTRIES:
                for stale_key in list(self._tts_cache)[: len(self._tts_cache) - TTS_CACHE_ENTRIES]:
                    self._tts_cache.pop(stale_key, None)
        return audio_path

    def _await_tts(self, request_id: str, provider_id: str = "") -> Path | None:
        deadline = time.monotonic() + TTS_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            state = self.tts.poll(request_id)
            if not isinstance(state, Mapping):
                raise RemoteUnavailableError("语音合成服务返回无效结果。")
            status = str(state.get("state") or state.get("status") or "")
            if status == "running":
                time.sleep(TTS_POLL_SECONDS)
                continue
            if status in {"failed", "error"}:
                reason = str(state.get("errorCode") or state.get("error") or "unknown")
                raise RemoteUnavailableError(f"语音合成失败：{reason}")
            if status not in {"succeeded", "completed", "done", "finished"}:
                time.sleep(TTS_POLL_SECONDS)
                continue
            artifact = state.get("artifact")
            artifact_id = ""
            if isinstance(artifact, Mapping):
                artifact_id = str(artifact.get("artifactId") or "")
            path = self._locate_artifact(artifact_id)
            if path is None:
                raise RemoteUnavailableError("语音文件已失效，请重新发送。")
            return path
        return None

    def _locate_artifact(self, artifact_id: str) -> Path | None:
        """把 TTS Hub 返回的 opaque artifact 描述符还原成本机 wav 路径。

        Plugin API 只把 artifactId / mediaType / byteLength 交给消费方，
        没有给出路径；这里按 PluginArtifactStore 的固定目录布局反查，
        不依赖任何 Core 内部对象。
        """

        clean_id = str(artifact_id or "").strip()
        if not clean_id.startswith("artifact_"):
            return None
        root = self._artifact_root
        if not root.is_dir():
            return None
        try:
            for generation in root.iterdir():
                if not generation.is_dir():
                    continue
                for plugin_dir in generation.iterdir():
                    candidate = plugin_dir / clean_id / "payload.wav"
                    if candidate.is_file():
                        return candidate
        except OSError:
            pass
        # 兜底：兼容目录布局变化时按 artifactId 直接匹配。
        try:
            for candidate in root.glob(f"*/*/{clean_id}/payload.*"):
                if candidate.is_file():
                    return candidate
        except OSError:
            pass
        return None

    # ---- 上传 -----------------------------------------------------

    def store_upload(self, media_type: str, payload: bytes) -> str:
        suffix = _IMAGE_UPLOAD_TYPES.get(media_type)
        if suffix is None:
            raise ValueError("不支持的图片格式。")
        if not payload:
            raise ValueError("图片为空。")
        if len(payload) > MAX_IMAGE_BYTES:
            raise ValueError("图片过大。")
        self._upload_dir.mkdir(parents=True, exist_ok=True)
        name = f"{int(time.time() * 1000)}-{secrets.token_hex(6)}{suffix}"
        (self._upload_dir / name).write_bytes(payload)
        self._prune_uploads()
        return f"/cache/uploads/{name}"

    def _prune_uploads(self, keep: int = 24) -> None:
        try:
            files = sorted(
                (item for item in self._upload_dir.iterdir() if item.is_file()),
                key=lambda item: item.stat().st_mtime,
                reverse=True,
            )
        except OSError:
            return
        for stale in files[keep:]:
            try:
                stale.unlink()
            except OSError:
                pass

    def cached_asset(self, relative: str) -> tuple[str, bytes] | None:
        clean = relative.strip().lstrip("/")
        if not clean or ".." in clean:
            return None
        candidate = (self.base_dir / clean).resolve()
        try:
            candidate.relative_to(self.base_dir.resolve())
        except ValueError:
            return None
        if not candidate.is_file():
            return None
        try:
            payload = candidate.read_bytes()
        except OSError:
            return None
        media_type = _MIME_BY_SUFFIX.get(candidate.suffix.lower(), "application/octet-stream")
        return media_type, payload

    def static_file(self, relative: str) -> tuple[str, bytes] | None:
        clean = relative.strip().lstrip("/")
        if not clean or ".." in clean:
            return None
        candidate = (self._static_dir / clean).resolve()
        try:
            candidate.relative_to(self._static_dir.resolve())
        except ValueError:
            return None
        if not candidate.is_file():
            return None
        try:
            payload = candidate.read_bytes()
        except OSError:
            return None
        if candidate.suffix.lower() == ".css":
            return "text/css; charset=utf-8", payload
        if candidate.suffix.lower() == ".js":
            return "application/javascript; charset=utf-8", payload
        if candidate.suffix.lower() == ".webmanifest":
            return "application/manifest+json; charset=utf-8", payload
        return _MIME_BY_SUFFIX.get(candidate.suffix.lower(), "application/octet-stream"), payload


def _build_handler(service: RemoteService, token: str) -> type[BaseHTTPRequestHandler]:
    class RemoteRequestHandler(BaseHTTPRequestHandler):
        server_version = "SakuraRemote/0.1"
        protocol_version = "HTTP/1.1"

        # ---- GET ----
        def do_GET(self) -> None:  # noqa: N802
            status = HTTPStatus.OK.value
            try:
                parsed = urlparse(self.path)
                self._require_rate_limit()
                route = parsed.path
                if route in {"", "/"}:
                    self._require_token(parsed)
                    self._send_html(render_page(token, service.ui_settings()))
                    return
                if route in {"/app.css", "/app.js", "/manifest.webmanifest"}:
                    # 这三个由 <link>/<script>/manifest 直接请求，浏览器不会带 token；
                    # 它们本身不含任何用户数据（页面 HTML 才是需要 token 的入口）。
                    self._send_static(route)
                    return
                if route == "/asset/portrait":
                    self._send_portrait(parsed, token)
                    return
                if route.startswith("/cache/uploads/"):
                    self._send_cache(parsed, token)
                    return
                if route == "/api/status":
                    self._require_token(parsed)
                    self._send_json({"ok": True, "tts": service.tts_available()})
                    return
                if route == "/api/state":
                    self._require_token(parsed)
                    self._send_json(service.state())
                    return
                if route == "/api/settings":
                    self._require_token(parsed)
                    self._send_json(service.settings_payload())
                    return
                if route == "/api/characters":
                    self._require_token(parsed)
                    self._send_json(service.characters_payload())
                    return
                if route == "/api/history":
                    self._require_token(parsed)
                    params = parse_qs(parsed.query)
                    character_id = _first_query_value(params, "character_id")
                    limit = _safe_int(_first_query_value(params, "limit"), 50)
                    self._send_json(
                        {"history": service.mobile.history(character_id, limit)}
                    )
                    return
                status = HTTPStatus.NOT_FOUND.value
                self._send_error(HTTPStatus.NOT_FOUND, "Not found")
            except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
                status = 0
            except RemoteUnavailableError as error:
                status = HTTPStatus.SERVICE_UNAVAILABLE.value
                self._send_error(HTTPStatus.SERVICE_UNAVAILABLE, str(error))
            except Exception as error:  # noqa: BLE001
                status = HTTPStatus.BAD_REQUEST.value
                self._send_error(HTTPStatus.BAD_REQUEST, str(error))
            finally:
                if status:
                    service.log_access("GET", self.path, self.client_address, status)

        # ---- POST ----
        def do_POST(self) -> None:  # noqa: N802
            status = HTTPStatus.OK.value
            try:
                parsed = urlparse(self.path)
                self._require_rate_limit()
                route = parsed.path
                if route == "/api/chat":
                    payload = self._read_json_body()
                    self._require_token(parsed, payload)
                    image = str(payload.get("image_url") or payload.get("image") or "")
                    if not image:
                        image = service.image_data_url(
                            str(payload.get("image_asset") or "")
                        )
                    result = service.chat(
                        str(payload.get("character_id") or ""),
                        str(payload.get("text") or ""),
                        image,
                    )
                    self._send_json(result)
                    return
                if route == "/api/tts":
                    payload = self._read_json_body()
                    self._require_token(parsed, payload)
                    self._send_tts(payload)
                    return
                if route == "/api/upload":
                    payload = self._read_json_body()
                    self._require_token(parsed, payload)
                    media_type = str(payload.get("media_type") or "").lower()
                    encoded = str(payload.get("data") or "")
                    try:
                        raw = base64.b64decode(encoded, validate=True)
                    except (ValueError, binascii.Error) as error:
                        raise ValueError("图片编码无效。") from error
                    url = service.store_upload(media_type, raw)
                    self._send_json({"ok": True, "url": url})
                    return
                if route == "/api/restart":
                    payload = self._read_json_body()
                    self._require_token(parsed, payload)
                    self._send_json(service.restart_sakura())
                    return
                if route == "/api/characters":
                    payload = self._read_json_body()
                    self._require_token(parsed, payload)
                    self._send_json(
                        service.select_character(str(payload.get("character_id") or ""))
                    )
                    return
                if route == "/api/settings":
                    payload = self._read_json_body()
                    self._require_token(parsed, payload)
                    values = payload.get("settings")
                    if not isinstance(values, Mapping):
                        # 也接受扁平写法 {"autoplay": true}
                        values = {key: payload[key] for key in ("autoplay", "tts_enabled") if key in payload}
                    self._send_json(service.save_settings(values))
                    return
                status = HTTPStatus.NOT_FOUND.value
                self._send_error(HTTPStatus.NOT_FOUND, "Not found")
            except RemoteBusyError as error:
                status = HTTPStatus.CONFLICT.value
                self._send_json(
                    {"ok": False, "busy": True, "error": str(error)},
                    HTTPStatus.CONFLICT,
                )
            except RemoteUnavailableError as error:
                status = HTTPStatus.SERVICE_UNAVAILABLE.value
                self._send_error(HTTPStatus.SERVICE_UNAVAILABLE, str(error))
            except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
                status = 0
            except Exception as error:  # noqa: BLE001
                status = HTTPStatus.BAD_REQUEST.value
                self._send_error(HTTPStatus.BAD_REQUEST, str(error))
            finally:
                if status:
                    service.log_access("POST", self.path, self.client_address, status)

        def do_OPTIONS(self) -> None:  # noqa: N802
            self.send_response(HTTPStatus.NO_CONTENT.value)
            self._send_common_headers()
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, format: str, *args: object) -> None:
            return

        # ---- 路由辅助 ----
        def _send_portrait(self, parsed: Any, expected_token: str) -> None:
            self._require_token(parsed)
            params = parse_qs(parsed.query)
            character_id = _first_query_value(params, "character").strip()
            key = _first_query_value(params, "key").strip() or "__default__"
            if not character_id:
                character_id = service.current_character_id()
            media_type, payload = service.portrait_bytes(character_id, key)
            self._send_bytes(payload, media_type, cache_seconds=86400)

        def _send_cache(self, parsed: Any, expected_token: str) -> None:
            self._require_token(parsed)
            found = service.cached_asset(parsed.path)
            if found is None:
                self._send_error(HTTPStatus.NOT_FOUND, "Not found")
                return
            media_type, payload = found
            self._send_bytes(payload, media_type, cache_seconds=3600)

        def _send_static(self, route: str) -> None:
            """无需 token 的静态资源（浏览器直接加载，不含用户数据）。

            用 ETag 协商缓存而不是 max-age：改了 app.js/app.css 之后手机刷新
            就能立刻拿到新版，不用等缓存过期；没改则返回 304，依然省流量。
            """

            found = service.static_file(route)
            if found is None:
                self._send_error(HTTPStatus.NOT_FOUND, "Not found")
                return
            media_type, payload = found
            self._send_bytes(payload, media_type, cache_seconds=-1)

        def _send_tts(self, payload: Mapping[str, Any]) -> None:
            character_id = str(payload.get("character_id") or "")
            text = str(payload.get("text") or "")
            tone = str(payload.get("tone") or "")
            if not character_id:
                character_id = service.current_character_id()
            path = service.synthesize(character_id, text, tone)
            try:
                audio = path.read_bytes()
            except OSError as error:
                raise RemoteUnavailableError("语音文件读取失败。") from error
            self._send_bytes(audio, "audio/wav", cache_seconds=3600, etag_source=audio)

        # ---- 请求解析 ----
        def _read_json_body(self) -> dict[str, Any]:
            length = _safe_int(self.headers.get("Content-Length"), 0)
            if length <= 0:
                return {}
            if length > MAX_REQUEST_BYTES:
                raise ValueError("请求体过大。")
            raw = self.rfile.read(length)
            try:
                data = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, ValueError) as error:
                raise ValueError("请求体不是合法 JSON。") from error
            if not isinstance(data, dict):
                raise ValueError("请求体必须是 JSON object。")
            return data

        def _require_token(self, parsed: Any, data: Mapping[str, Any] | None = None) -> None:
            params = parse_qs(parsed.query)
            provided = (
                self.headers.get("X-Sakura-Remote-Token", "")
                or _first_query_value(params, "token")
                or str((data or {}).get("token") or "")
            ).strip()
            if not secrets.compare_digest(provided, token):
                raise PermissionError("配对码无效。")

        def _require_rate_limit(self) -> None:
            if not self.server.allow_client_request(self.client_address):  # type: ignore[attr-defined]
                raise ValueError("请求过于频繁，请稍后再试。")

        # ---- 响应 ----
        def _send_json(
            self,
            data: Mapping[str, Any],
            status: HTTPStatus = HTTPStatus.OK,
        ) -> None:
            payload = json.dumps(dict(data), ensure_ascii=False, default=str).encode("utf-8")
            self._send_bytes(payload, "application/json; charset=utf-8", status=status)

        def _send_html(self, html: str) -> None:
            self._send_bytes(html.encode("utf-8"), "text/html; charset=utf-8")

        def _send_error(self, status: HTTPStatus, message: str) -> None:
            self._send_json({"ok": False, "error": message}, status)

        def _send_bytes(
            self,
            payload: bytes,
            media_type: str,
            *,
            status: HTTPStatus = HTTPStatus.OK,
            cache_seconds: int = 0,
            etag_source: bytes | None = None,
        ) -> None:
            """cache_seconds 语义：
              > 0  强缓存 max-age，命中 ETag 时返回 304
              -1   协商缓存 no-cache + ETag：每次都校验，没变返回 304，
                   适合会随版本变化的 app.js / app.css
              0    不缓存
            """

            etag = '"' + hashlib.sha256(etag_source or payload).hexdigest()[:32] + '"'
            revalidate = cache_seconds != 0
            if revalidate:
                incoming = (self.headers.get("If-None-Match") or "").strip()
                if incoming and incoming == etag:
                    self.send_response(HTTPStatus.NOT_MODIFIED.value)
                    self._send_common_headers()
                    self.send_header("ETag", etag)
                    self.send_header("Cache-Control", self._cache_control(cache_seconds))
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
            self.send_response(status.value)
            self._send_common_headers()
            self.send_header("Content-Type", media_type)
            self.send_header("Content-Length", str(len(payload)))
            if revalidate:
                self.send_header("ETag", etag)
                self.send_header("Cache-Control", self._cache_control(cache_seconds))
            else:
                self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)

        @staticmethod
        def _cache_control(cache_seconds: int) -> str:
            if cache_seconds < 0:
                return "no-cache"
            return f"private, max-age={cache_seconds}"

        def _send_common_headers(self) -> None:
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header(
                "Access-Control-Allow-Headers",
                "Content-Type, X-Sakura-Remote-Token",
            )
            self.send_header("Referrer-Policy", "no-referrer")

    return RemoteRequestHandler


def _first_query_value(params: Mapping[str, list[str]], key: str) -> str:
    values = params.get(key) or []
    return values[0] if values else ""


def _safe_int(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _client_address_text(client_address: object) -> str:
    if isinstance(client_address, tuple) and len(client_address) >= 2:
        return f"{client_address[0]}:{client_address[1]}"
    return str(client_address)


__all__ = [
    "DEFAULT_HOST",
    "DEFAULT_PORT",
    "RemoteService",
    "run_remote_server",
    "mobile_access_urls",
]
