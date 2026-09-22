"""按插件运行时的方式加载 sakura_remote，用桩上下文验证整条链路。

用法：
    F:\\sakura\\python\\python.exe tests\\plugin_runtime_smoke.py

它会：
1. 把 sakura_remote 目录当作顶层模块导入（和 plugin_runner_v4 一致）；
2. 用一个桩 context 完成 setup()，只实现插件真正用到的 API；
3. 触发 sakura.host.app.started，让插件真的监听一个端口；
4. 通过 HTTP 验证聊天分段、立绘、语音三条链路都通。
"""

from __future__ import annotations

import json
import sys
import threading
import time
import urllib.error
import urllib.request
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def _plugin_root():
    """插件源码目录。

    Registry 投稿要求 plugin.yaml 在仓库根目录，
    早期在 sakura_remote/ 子目录，两种都认。"""
    for candidate in (ROOT, ROOT / "sakura_remote"):
        if (candidate / "plugin.yaml").exists():
            return candidate
    raise SystemExit("plugin.yaml not found under " + str(ROOT))

PLUGIN_ROOT = _plugin_root()
sys.path.insert(0, str(PLUGIN_ROOT))

TOKEN = "runtime-token"
PORT = 8792


class StubConfig:
    def __init__(self, values):
        self._values = dict(values)
        self._handlers = []

    def get(self):
        return dict(self._values)

    def update(self, values):
        self._values.update(values)
        for handler in self._handlers:
            handler(dict(self._values))
        return "applied"

    def on_change(self, handler):
        self._handlers.append(handler)
        return lambda: None


class StubLogger:
    def __init__(self):
        self.lines = []

    def _record(self, level, message, fields=None):
        self.lines.append((level, message, dict(fields or {})))
        return True

    def debug(self, message, *, fields=None):
        return self._record("debug", message, fields)

    def info(self, message, *, fields=None):
        return self._record("info", message, fields)

    def warning(self, message, *, fields=None):
        return self._record("warning", message, fields)

    def error(self, message, *, fields=None):
        return self._record("error", message, fields)


class StubMobile:
    """故意复刻 sakura.host.mobile 的真实位置参数形状。

    begin/poll/cancel 的第一个位置参数是调用方插件 ID（框架不会注入），
    history/characters 不需要。签名写错就会在这里立刻暴露。
    """

    def __init__(self):
        self.calls = []
        self.expected_plugin_id = "sakura.remote"

    def characters(self):
        self.calls.append(("characters",))
        return [{"id": "Sakura", "name": "夜乃桜", "initial_message": "…", "current": "true"}]

    def history(self, character_id, limit=50):
        self.calls.append(("history", character_id, limit))
        return [{"created_at": "t", "role": "assistant", "content": "在的哦。", "raw_content": "いるよ。", "translation": "在的哦。"}]

    def theme(self):
        self.calls.append(("theme",))
        return {"primary_color": "#d55b91"}

    def _check_owner(self, plugin_id):
        if plugin_id != self.expected_plugin_id:
            raise AssertionError(
                f"begin/poll/cancel 的第一参数必须是插件 ID，收到 {plugin_id!r}"
            )

    def begin(self, plugin_id, character_id, text, artifact_descriptor=None):
        self._check_owner(plugin_id)
        self.calls.append(("begin", plugin_id, character_id, text))
        return {"jobId": "stub-job"}

    def poll(self, plugin_id, job_id):
        self._check_owner(plugin_id)
        self.calls.append(("poll", plugin_id, job_id))
        return {
            "status": "completed",
            "result": {
                "character_id": "Sakura",
                "reply": "在的哦。",
                "segments": [
                    {"content": "在的哦。", "raw_content": "いるよ。", "translation": "在的哦。", "tone": "中性", "portrait": "站立待机"}
                ],
            },
        }

    def cancel(self, plugin_id, job_id):
        self._check_owner(plugin_id)
        self.calls.append(("cancel", plugin_id, job_id))
        return {"accepted": True}


class StubCharacters:
    def __init__(self, fixtures: Path):
        self._fixtures = fixtures

    def resolve_resource(self, character_id, relative_path):
        target = self._fixtures / Path(relative_path).name
        if not target.is_file():
            raise RuntimeError(f"CHARACTER_RESOURCE_INVALID: {relative_path}")
        return str(target)


class StubTTS:
    """按真实 TTS Hub 协议返回 opaque artifact（只有 artifactId），
    并把 wav 写到 PluginArtifactStore 的目录布局里，验证反查逻辑。
    """

    GENERATION = "18d0000000000000000000000000001"

    def __init__(self, tmp: Path):
        self._tmp = tmp
        self._requests: dict[str, Path] = {}
        self._artifact_root = (
            tmp / "plugin-data" / ".." / ".." / "cache" / "plugin-artifacts" / self.GENERATION
        ).resolve()

    def begin(self, payload):
        request_id = str(payload["requestId"])
        artifact_id = "artifact_" + format(len(self._requests) + 1, "032x")
        directory = self._artifact_root / "sakura.tts.genie" / artifact_id
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / "payload.wav"
        with wave.open(str(path), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(32000)
            handle.writeframes(b"\x00\x00" * 3200)
        self._requests[request_id] = path
        self._artifacts = getattr(self, "_artifacts", {})
        self._artifacts[request_id] = artifact_id
        return {"state": "running", "requestId": request_id, "providerId": "sakura.tts.genie"}

    def poll(self, request_id):
        artifact_id = getattr(self, "_artifacts", {}).get(request_id)
        if artifact_id is None:
            return {"state": "failed", "errorCode": "TTS_JOB_NOT_FOUND"}
        return {
            "state": "succeeded",
            "requestId": request_id,
            "providerId": "sakura.tts.genie",
            "artifact": {"artifactId": artifact_id, "mediaType": "audio/wav", "byteLength": 6400},
        }


class StubContext:
    plugin_id = "sakura.remote"

    def __init__(self, fixtures: Path, tmp: Path):
        self.config = StubConfig({
            "enabled": True,
            "host": "127.0.0.1",
            "port": PORT,
            "token": TOKEN,
            "autoplay": True,
            "tts_enabled": True,
        })
        self.logger = StubLogger()
        self._services = {
            "sakura.host.mobile": StubMobile(),
            "sakura.host.character": StubCharacters(fixtures),
            "sakura.tts": StubTTS(tmp),
            "sakura.host.settings": StubSettings(),
        }
        self._events = {}
        self._effects = []
        self._tmp = tmp

    def get(self, service_key):
        if service_key == "sakura.host.logging":
            return self.logger
        if service_key not in self._services:
            raise RuntimeError(f"SERVICE_MISSING: {service_key}")
        return self._services[service_key]

    def provide(self, service_key, service, *, exports=()):
        self._services[service_key] = service
        return lambda: None

    def on(self, name, handler):
        self._events.setdefault(name, []).append(handler)
        return lambda: None

    def effect(self, cleanup):
        self._effects.append(cleanup)
        return lambda: None

    def data_path(self, relative_path):
        target = (self._tmp / "plugin-data" / relative_path).resolve()
        target.mkdir(parents=True, exist_ok=True)
        return target

    def emit(self, name, payload=None):
        for handler in self._events.get(name, []):
            handler(payload or {})


class StubSettings:
    def register(self, descriptor, *, load=None, save=None, actions=None):
        if save is not None:
            values = load() if load else {}
            if not isinstance(values, dict):
                raise AssertionError("settings load 必须返回 dict")
        return lambda: None


def request(url, *, method="GET", payload=None, token=TOKEN, raw=False):
    headers = {"X-Sakura-Remote-Token": token}
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            body = response.read()
            return response.status, (body if raw else body.decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        body = error.read()
        return error.code, (body if raw else body.decode("utf-8", "replace"))


def main() -> int:
    fixtures = ROOT / "tests" / "fixtures"
    tmp = ROOT / "tests" / "_tmp" / "runtime"
    tmp.mkdir(parents=True, exist_ok=True)

    import plugin as plugin_module

    context = StubContext(fixtures, tmp)
    instance = plugin_module.SakuraRemotePlugin()
    instance.setup(context)

    failures: list[str] = []

    def check(name, condition, detail=""):
        print(f"[{'PASS' if condition else 'FAIL'}] {name}" + ("" if condition else f" -> {detail}"))
        if not condition:
            failures.append(name)

    check("setup() 注册了 app.started 事件", "sakura.host.app.started" in context._events)
    check("setup() 注册了清理副作用", len(context._effects) == 1)

    instance.start()
    time.sleep(0.4)
    status = instance.status()
    check("start() 后状态为运行中", status["running"] is True, status)
    check("状态里带内网链接字段", "lan_urls" in status and "local_url" in status, status)

    base = f"http://127.0.0.1:{PORT}"
    try:
        code, html = request(base + "/?token=" + TOKEN)
        check("网页可访问", code == 200 and "__SAKURA__" in html, code)

        code, payload = request(base + "/api/state?token=" + TOKEN)
        state = json.loads(payload) if code == 200 else {}
        check("state 读到角色和立绘", code == 200 and state.get("displayName") == "夜乃桜", payload)

        code, image = request(base + state["portraits"][0]["url"] + "&token=" + TOKEN, raw=True)
        check("立绘字节可取", code == 200 and image[:4] == b"\x89PNG", code)

        code, payload = request(
            base + "/api/chat",
            method="POST",
            payload={"character_id": "Sakura", "text": "在吗？"},
        )
        chat = json.loads(payload) if code == 200 else {}
        check("聊天分段透传", code == 200 and chat.get("segments", [{}])[0].get("tone") == "中性", payload)
        begin_calls = [c for c in context._services["sakura.host.mobile"].calls if c[0] == "begin"]
        check(
            "begin 传了插件 ID 作为第一参数",
            bool(begin_calls) and begin_calls[0][1] == "sakura.remote" and begin_calls[0][2] == "Sakura",
            begin_calls,
        )
        poll_calls = [c for c in context._services["sakura.host.mobile"].calls if c[0] == "poll"]
        check(
            "poll 传了插件 ID 作为第一参数",
            bool(poll_calls) and poll_calls[0][1] == "sakura.remote",
            poll_calls,
        )

        code, audio = request(
            base + "/api/tts",
            method="POST",
            payload={"character_id": "Sakura", "text": "いるよ。", "tone": "中性"},
            raw=True,
        )
        check("语音 WAV 可取", code == 200 and audio[:4] == b"RIFF", code)

        # 配置变更 -> on_change -> restart
        context.config.update({"port": PORT})
        time.sleep(0.3)
        check("配置变更后服务仍在运行", instance.status()["running"] is True, instance.status())
    finally:
        for effect in context._effects:
            effect()
        instance.stop()

    time.sleep(0.3)
    check("stop() 后状态为已停止", instance.status()["running"] is False, instance.status())

    print()
    if failures:
        print(f"{len(failures)} 项失败：" + ", ".join(failures))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
