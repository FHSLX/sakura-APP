"""sakura_remote 插件：手机远程端。

把手机变成 Sakura 桌宠的远程显示器：
- 聊天与 AI 计算全部在电脑主进程完成（走 sakura.host.mobile）;
- 立绘图片由电脑读取并通过 HTTP 直接推给手机（走 sakura.host.character 解析资源）;
- 语音由电脑端 TTS 合成后以 WAV 形式回传（走 sakura.tts），手机只负责播放。

本模块只依赖 Python 标准库。
"""

from __future__ import annotations

import threading
from collections.abc import Mapping
from pathlib import Path
from typing import Any

try:
    from .http_server import (
        DEFAULT_HOST,
        DEFAULT_PORT,
        mobile_access_urls,
        run_remote_server,
    )
except ImportError:
    from http_server import (  # type: ignore[no-redef]
        DEFAULT_HOST,
        DEFAULT_PORT,
        mobile_access_urls,
        run_remote_server,
    )


PLUGIN_ID = "sakura.remote"
MOBILE_SERVICE = "sakura.host.mobile"
CHARACTER_SERVICE = "sakura.host.character"
SETTINGS_SECTION_ID = "sakura_remote"


class SakuraRemotePlugin:
    """手机远程端插件主体。"""

    def __init__(self) -> None:
        self._context: object | None = None
        self._logger: Any = None
        self._config: object | None = None
        self._mobile: object | None = None
        self._characters: object | None = None
        self._tts: object | None = None
        self._data_dir: Path | None = None
        self._server: Any | None = None
        self._thread: Any | None = None
        self._last_error = ""

    # ---- 生命周期 -------------------------------------------------

    def setup(self, context: object) -> None:
        self._context = context
        self._logger = getattr(context, "get")("sakura.host.logging")
        # 刻意不用 .resolve()：Windows 上它会给路径加 \\?\ 扩展长度前缀，
        # 而 PowerShell 的 -File / Start-Process 不接受这种路径，
        # 会让远程重启脚本静默启动失败（实测 helper 进程起来了但什么都没做）。
        self._data_dir = Path(getattr(context, "data_path")("."))
        self._config = getattr(context, "config")
        self._mobile = getattr(context, "get")(MOBILE_SERVICE)
        self._characters = getattr(context, "get")(CHARACTER_SERVICE)
        # TTS 走普通服务代理：没有该服务时只有语音功能不可用，文字和立绘照常工作。
        try:
            self._tts = getattr(context, "get")("sakura.tts")
        except Exception:  # noqa: BLE001 - TTS 是可选能力
            self._tts = None
        getattr(context, "effect")(self.stop)
        getattr(context, "on")("sakura.host.app.started", lambda _event: self.start())
        getattr(self._config, "on_change")(self._apply_config)
        try:
            getattr(context, "get")("sakura.host.settings").register(
                _settings_descriptor(),
                load=self.settings_values,
                save=self.save_settings_values,
                actions={"refresh_status": self.refresh_settings_status},
            )
        except Exception as error:  # noqa: BLE001 - 设置面板注册失败不应阻断服务
            self._log_warning("手机远程端设置注册失败", error)
        self._log_info(
            "手机远程端插件已就绪",
            {
                "mobile_service": self._mobile is not None,
                "character_service": self._characters is not None,
                "tts_service": self._tts is not None,
                "data_dir": str(self._data_dir) if self._data_dir else "",
                "config": self._raw_config(),
            },
        )

    def config(self) -> dict[str, Any]:
        config = self._require_config()
        return _normalized_config(getattr(config, "get")())

    def _raw_config(self) -> dict[str, Any]:
        """原始配置，用于诊断（不触发规范化）。"""

        try:
            return dict(getattr(self._require_config(), "get")())
        except Exception as error:  # noqa: BLE001
            return {"_config_error": f"{type(error).__name__}: {error}"}

    def settings_values(self) -> dict[str, Any]:
        status = self.status()
        running = "未启动"
        if status["enabled"]:
            if status["running"]:
                running = "运行中"
            elif status["error"]:
                running = "启动失败"
        token = str(status["token"])
        lan_urls = [str(item) for item in (status.get("lan_urls") or []) if item]
        # local_url / lan_urls 里已经带了 ?token=...（见 http_server.mobile_access_urls），
        # 所以这里直接挑一条内网地址给用户复制，**不要再拼一次 token**。
        phone_url = lan_urls[0] if lan_urls else str(status.get("local_url") or "")
        return {
            "enabled": bool(status["enabled"]),
            "host": str(status["host"]),
            "port": int(status["port"]),
            "token": token,
            "autoplay": bool(status["autoplay"]),
            "tts_enabled": bool(status["tts_enabled"]),
            "running": running,
            "local_url": str(status.get("local_url") or ""),
            "lan_urls": " ; ".join(lan_urls) or "未发现内网地址",
            "phone_url": phone_url,
            "error": str(status.get("error") or ""),
        }

    def save_settings_values(self, values: Mapping[str, Any]) -> list[str]:
        current = self.config()
        merged = _normalized_config({**current, **dict(values)})
        if merged["enabled"] and not str(values.get("token", current["token"])).strip():
            raise ValueError("启用手机远程端时访问 token 不能为空。")
        updates = {key: merged[key] for key in values if key in merged}
        return getattr(self._require_config(), "update")(updates)

    def refresh_settings_status(self, _values: Mapping[str, Any]) -> dict[str, Any]:
        values = self.settings_values()
        return {
            "values": {
                "running": values["running"],
                "local_url": values["local_url"],
                "lan_urls": values["lan_urls"],
                "phone_url": values["phone_url"],
                "error": values["error"],
            }
        }

    def save_remote_settings(self, updates: Mapping[str, Any]) -> None:
        """由手机端配置页调用：只允许改白名单字段。

        监听地址和端口是手机自己的连接参数，让网页改会把连接掐断，
        所以这里直接拒绝，引导用户去桌面端设置里改。
        """

        allowed = {key: bool(value) for key, value in updates.items()
                   if key in ("autoplay", "tts_enabled")}
        if not allowed:
            raise ValueError("没有可保存的设置项。")
        getattr(self._require_config(), "update")(allowed)

    # ---- 服务启停 -------------------------------------------------

    def start(self) -> None:
        if self._server is not None:
            return
        config = self.config()
        if not config["enabled"]:
            self._last_error = ""
            return
        if self._mobile is None or self._characters is None:
            self._last_error = "宿主移动端服务尚未就绪。"
            self._log_warning(self._last_error, None)
            return
        if self._data_dir is None:
            self._last_error = "插件数据目录尚未就绪。"
            self._log_warning(self._last_error, None)
            return
        try:
            server = run_remote_server(
                self._data_dir,
                mobile_service=self._mobile,
                character_service=self._characters,
                tts_service=self._tts,
                host=str(config["host"]),
                port=int(config["port"]),
                token=str(config["token"]),
                autoplay=bool(config["autoplay"]),
                tts_enabled=bool(config["tts_enabled"]),
                logger=self._logger,
                    config_saver=self.save_remote_settings,
            )
        except OSError as error:
            self._log_warning(
                "手机远程端监听失败",
                error,
                extra={"port": config["port"], "host": config["host"]},
            )
            self._last_error = f"监听失败：{error}"
            return

        thread = threading.Thread(
            target=server.serve_forever,
            name="SakuraRemoteServer",
            daemon=True,
        )
        self._server = server
        self._thread = thread
        self._last_error = ""
        thread.start()
        bound = getattr(server, "server_address", (config["host"], config["port"]))
        self._log_info(
            "手机远程端已启动",
            {
                "host": str(bound[0]),
                "port": int(bound[1]),
                "tts_available": bool(getattr(server, "service", None) and server.service.tts_available()),
                "data_dir": str(self._data_dir),
            },
        )

    def stop(self) -> None:
        server = self._server
        thread = self._thread
        self._server = None
        self._thread = None
        if server is None:
            return
        try:
            server.shutdown()
            server.server_close()
        except OSError:
            pass
        finally:
            if thread is not None:
                try:
                    if thread is not threading.current_thread():
                        thread.join(timeout=3)
                except Exception:  # noqa: BLE001
                    pass
            self._log_info("手机远程端已停止")

    def restart(self) -> None:
        self.stop()
        self.start()

    def status(self) -> dict[str, Any]:
        config = self.config()
        return {
            **config,
            "running": self._server is not None,
            "error": self._last_error,
            **mobile_access_urls(
                str(config["host"]),
                int(config["port"]),
                str(config["token"]),
            ),
        }

    def _apply_config(self, _values: Mapping[str, Any]) -> str:
        self.restart()
        config = self.config()
        return "error" if config["enabled"] and self._server is None else "applied"

    # ---- 内部工具 -------------------------------------------------

    def _require_config(self) -> object:
        if self._config is None:
            raise RuntimeError("手机远程端插件尚未初始化。")
        return self._config

    def _log_info(self, message: str, fields: Mapping[str, Any] | None = None) -> None:
        try:
            self._logger.info(message, fields=dict(fields or {}))
        except Exception:  # noqa: BLE001
            pass

    def _log_warning(
        self,
        message: str,
        error: object | None,
        *,
        extra: Mapping[str, Any] | None = None,
    ) -> None:
        fields: dict[str, Any] = dict(extra or {})
        if error is not None:
            fields["error_type"] = type(error).__name__
            fields["reason_code"] = str(getattr(error, "code", "") or "")
        try:
            self._logger.warning(message, fields=fields)
        except Exception:  # noqa: BLE001
            pass


def _settings_descriptor() -> dict[str, Any]:
    return {
        "sectionId": SETTINGS_SECTION_ID,
        "title": "手机远程端",
        "order": 75,
        "fields": [
            {"key": "enabled", "label": "启用手机远程端", "type": "boolean", "default": False},
            {
                "key": "host",
                "label": "监听地址",
                "type": "string",
                "default": DEFAULT_HOST,
                "required": True,
                "maxLength": 255,
            },
            {
                "key": "port",
                "label": "端口",
                "type": "integer",
                "default": DEFAULT_PORT,
                "minimum": 1,
                "maximum": 65535,
            },
            {
                "key": "token",
                # 用 string 而不是 password：这是给用户自己看的连接凭据，
                # 密码框会把它遮住，用户就没法读到该在手机上填什么。
                # copyable 让它能一键复制。
                "label": "访问 token",
                "type": "string",
                "default": "sakura",
                "required": True,
                "copyable": True,
                "maxLength": 512,
                "description": "手机连接时要填这一串，请改成足够长的随机值。",
            },
            {
                "key": "autoplay",
                "label": "自动朗读语音",
                "type": "boolean",
                "default": True,
            },
            {
                "key": "tts_enabled",
                "label": "合成语音（关闭则手机只显示文字）",
                "type": "boolean",
                "default": True,
            },
            {"key": "running", "label": "运行状态", "type": "readonly", "default": "未启动"},
            {
                "key": "local_url",
                "label": "本机链接（电脑自己用）",
                "type": "readonly",
                "default": "",
                "copyable": True,
                "description": "在电脑浏览器里打开可预览效果。",
            },
            {
                "key": "lan_urls",
                "label": "内网链接（手机同一 WiFi 用）",
                "type": "readonly",
                "default": "未发现内网地址",
                "copyable": True,
                "description": "同一 WiFi 下手机可访问的地址，已带 token。",
            },
            {
                "key": "phone_url",
                "label": "手机直接打开这个地址",
                "type": "readonly",
                "default": "",
                "copyable": True,
                # 最有用的一个：完整地址（含 token），复制到手机浏览器就能用，
                # 不用手抄 IP 和 token。
                "description": "复制这条到手机浏览器即可；App 里则分两栏填 IP 和 token。",
            },
            {"key": "error", "label": "错误", "type": "readonly", "default": ""},
        ],
        "actions": [
            {"actionId": "refresh_status", "label": "刷新状态", "danger": False},
        ],
    }


def _normalized_config(value: Mapping[str, Any]) -> dict[str, Any]:
    token = str(value.get("token") or "sakura").strip() or "sakura"
    return {
        "enabled": _as_bool(value.get("enabled"), False),
        "host": str(value.get("host") or DEFAULT_HOST).strip() or DEFAULT_HOST,
        "port": _safe_port(value.get("port"), DEFAULT_PORT),
        "token": token,
        "autoplay": _as_bool(value.get("autoplay"), True),
        "tts_enabled": _as_bool(value.get("tts_enabled"), True),
    }


def _as_bool(value: object, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return default
    return str(value).strip().lower() not in {"0", "false", "no", "off", "disabled"}


def _safe_port(value: object, default: int) -> int:
    try:
        port = int(str(value).strip())
    except (TypeError, ValueError):
        return default
    return port if 1 <= port <= 65535 else default


__all__ = ["SakuraRemotePlugin", "PLUGIN_ID"]
