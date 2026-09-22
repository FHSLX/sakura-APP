"""用假宿主把 sakura_remote 的 HTTP 服务跑起来，验证协议与网页。

用法：
    F:\\sakura\\python\\python.exe tests\\fake_host_smoke.py

它不依赖 Sakura，只验证：
- 网页、静态资源、立绘接口能否返回；
- /api/chat 的分段结果是否正确透传；
- /api/tts 的合成与缓存路径是否工作；
- token 校验是否生效。
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

sys.path.insert(0, str(_plugin_root()))

import http_server  # noqa: E402

TOKEN = "test-token"


class FakeMobile:
    """复刻 sakura.host.mobile 的真实位置参数形状。

    begin/poll/cancel 的第一个位置参数是调用方插件 ID（框架不会注入），
    history/characters 不需要。签名写错会在这里直接暴露。
    """

    EXPECTED_PLUGIN_ID = "sakura.remote"

    def __init__(self) -> None:
        self._jobs: dict[str, dict] = {}
        self._owners: dict[str, str] = {}

    def characters(self):
        return [
            {"id": "Sakura", "name": "夜乃桜", "initial_message": "…", "current": "true"},
            {"id": "N.A.V.I.", "name": "N.A.V.I.", "initial_message": "…", "current": "false"},
        ]

    def history(self, character_id, limit=50):
        return [
            {"created_at": "2026-01-01T00:00:00", "role": "user", "content": "在吗？", "raw_content": "在吗？", "translation": ""},
            {"created_at": "2026-01-01T00:00:01", "role": "assistant", "content": "在的哦。", "raw_content": "いるよ。", "translation": "在的哦。"},
        ]

    def theme(self):
        return {"primary_color": "#d55b91", "page_background_color": "#fff6fa"}

    def _check_owner(self, plugin_id):
        if plugin_id != self.EXPECTED_PLUGIN_ID:
            raise AssertionError(
                f"begin/poll/cancel 的第一参数必须是插件 ID，收到 {plugin_id!r}"
            )

    def begin(self, plugin_id, character_id, text, artifact_descriptor=None):
        self._check_owner(plugin_id)
        job_id = f"job-{len(self._jobs) + 1}"
        self._jobs[job_id] = {"character_id": character_id, "text": text}
        self._owners[job_id] = plugin_id
        return {"jobId": job_id}

    def poll(self, plugin_id, job_id):
        self._check_owner(plugin_id)
        if self._owners.get(job_id) != plugin_id:
            raise RuntimeError("MOBILE_CHAT_JOB_NOT_FOUND")
        job = self._jobs.get(job_id)
        if job is None:
            raise RuntimeError("MOBILE_CHAT_JOB_NOT_FOUND")
        return {
            "status": "completed",
            "result": {
                "character_id": job["character_id"],
                "reply": "在的哦。\n今天也要加油。",
                "reply_raw": "いるよ。\n今日も頑張って。",
                "segments": [
                    {"content": "在的哦。", "raw_content": "いるよ。", "translation": "在的哦。", "tone": "中性", "portrait": "站立待机"},
                    {"content": "今天也要加油。", "raw_content": "今日も頑張って。", "translation": "今天也要加油。", "tone": "害羞", "portrait": "害羞脸红"},
                ],
            },
        }

    def cancel(self, plugin_id, job_id):
        self._check_owner(plugin_id)
        self._jobs.pop(job_id, None)
        self._owners.pop(job_id, None)
        return {"accepted": True}


class FakeCharacters:
    def resolve_resource(self, character_id, relative_path):
        target = ROOT / "tests" / "fixtures" / Path(relative_path).name
        if not target.is_file():
            raise RuntimeError(f"CHARACTER_RESOURCE_INVALID: {relative_path}")
        return str(target)


class NoCharacterMobile(FakeMobile):
    """模拟全新安装：电脑端一个角色都没有。

    `sakura.host.mobile` 在无角色时会抛 ASSISTANT_NOT_READY，
    插件必须能优雅降级，否则手机端整页打不开。
    """

    def characters(self):
        raise RuntimeError("ASSISTANT_NOT_READY")

    def theme(self):
        raise RuntimeError("ASSISTANT_NOT_READY")


class FakeTTS:
    """按真实 TTS Hub 协议返回 opaque artifact，并写入 PluginArtifactStore 目录布局。"""

    GENERATION = "18d0000000000000000000000000002"

    def __init__(self, tmp_dir: Path, data_dir: Path) -> None:
        self.tmp_dir = tmp_dir
        self.calls = 0
        self._requests: dict[str, str] = {}
        self._artifact_root = (data_dir.parent.parent / "cache" / "plugin-artifacts" / self.GENERATION).resolve()

    def begin(self, payload):
        self.calls += 1
        request_id = payload["requestId"]
        artifact_id = "artifact_" + format(self.calls, "032x")
        directory = self._artifact_root / "sakura.tts.genie" / artifact_id
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / "payload.wav"
        with wave.open(str(path), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(32000)
            handle.writeframes(b"\x00\x00" * 3200)
        self._requests[request_id] = artifact_id
        return {"state": "running", "requestId": request_id, "providerId": "sakura.tts.genie"}

    def poll(self, request_id):
        artifact_id = self._requests.get(request_id)
        if artifact_id is None:
            return {"state": "failed", "errorCode": "TTS_JOB_NOT_FOUND"}
        return {
            "state": "succeeded",
            "requestId": request_id,
            "providerId": "sakura.tts.genie",
            "artifact": {"artifactId": artifact_id, "mediaType": "audio/wav", "byteLength": 6400},
        }


def request(url: str, *, method="GET", payload=None, token=TOKEN, raw=False, headers_only=False):
    headers = {"X-Sakura-Remote-Token": token} if token is not None else {}
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            body = response.read()
            if headers_only:
                return response.status, dict(response.headers)
            return response.status, (body if raw else body.decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        body = error.read()
        if headers_only:
            return error.code, dict(error.headers)
        return error.code, (body if raw else body.decode("utf-8", "replace"))


def main() -> int:
    fixtures = ROOT / "tests" / "fixtures"
    fixtures.mkdir(parents=True, exist_ok=True)
    (fixtures / "character.json").write_text(
        json.dumps(
            {
                "id": "Sakura",
                "display_name": "夜乃桜",
                "portrait": {
                    "default": "portraits/A020.png",
                    "expressions": {"站立待机": "portraits/A020.png", "害羞脸红": "portraits/A081.png"},
                },
                "reply": {"tones": ["中性", "害羞"]},
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    (fixtures / "A020.png").write_bytes(
        bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082")
    )
    (fixtures / "A081.png").write_bytes(
        bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082")
    )

    tmp_dir = ROOT / "tests" / "_tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    data_dir = tmp_dir / "plugin-data"
    data_dir.mkdir(parents=True, exist_ok=True)

    server = http_server.run_remote_server(
        data_dir,
        mobile_service=FakeMobile(),
        character_service=FakeCharacters(),
        tts_service=FakeTTS(tmp_dir, data_dir),
        host="127.0.0.1",
        port=8791,
        token=TOKEN,
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    time.sleep(0.3)

    base = "http://127.0.0.1:8791"
    failures: list[str] = []

    def check(name: str, condition: bool, detail: object = "") -> None:
        status = "PASS" if condition else "FAIL"
        print(f"[{status}] {name}" + ("" if condition else f" -> {detail}"))
        if not condition:
            failures.append(name)

    try:
        status, html = request(base + "/?token=" + TOKEN)
        check("GET / 返回网页", status == 200 and "Sakura" in html and "__SAKURA__" in html, status)

        # 回归：<link>/<script>/manifest 是浏览器直接请求的，不会带 token。
        # 曾经因为要求 token 导致页面完全没有样式和脚本。
        for asset in ("/app.css", "/app.js", "/manifest.webmanifest"):
            status, body = request(base + asset, token=None)
            check(
                f"GET {asset} 无需 token 即可加载",
                status == 200 and len(body) > 0,
                (status, body[:60]),
            )

        status, css = request(base + "/app.css?token=" + TOKEN)
        check("GET /app.css", status == 200 and "--primary" in css, status)

        # 静态资源必须走协商缓存：改完代码手机刷新就能拿到新版，
        # 而不是等 max-age 过期（曾经因此让手机一直跑旧版 app.js）。
        status, headers = request(base + "/app.js", token=None, headers_only=True)
        cache_control = str(headers.get("Cache-Control", ""))
        etag = str(headers.get("ETag", ""))
        check(
            "静态资源用协商缓存（no-cache + ETag）",
            status == 200 and "no-cache" in cache_control and "max-age" not in cache_control and etag,
            {"Cache-Control": cache_control, "ETag": etag},
        )

        # 带上 ETag 再请求应该返回 304，避免重复传输
        req = urllib.request.Request(
            base + "/app.js", headers={"If-None-Match": etag}, method="GET"
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                not_modified = response.status
        except urllib.error.HTTPError as error:
            not_modified = error.code
        check("ETag 命中返回 304", not_modified == 304, not_modified)

        status, js = request(base + "/app.js?token=" + TOKEN)
        check("GET /app.js", status == 200 and "resolvePortraitKey" in js, status)

        status, manifest = request(base + "/manifest.webmanifest?token=" + TOKEN)
        check("GET /manifest.webmanifest", status == 200 and "short_name" in manifest, status)

        status, payload = request(base + "/api/status?token=" + TOKEN)
        check("GET /api/status", status == 200 and json.loads(payload).get("ok") is True, payload)

        status, payload = request(base + "/api/state?token=" + TOKEN)
        state = json.loads(payload) if status == 200 else {}
        keys = [item["key"] for item in state.get("portraits", [])]
        check("GET /api/state 返回角色与立绘", status == 200 and state.get("characterId") == "Sakura" and "站立待机" in keys, payload)
        check("GET /api/state 合并主题色", state.get("theme", {}).get("primary_color") == "#d55b91", state.get("theme"))

        portrait_url = state["portraits"][0]["url"] + "&token=" + TOKEN
        check(
            "state 返回的立绘 URL 自带 token",
            f"token={TOKEN}" in state["portraits"][0]["url"],
            state["portraits"][0]["url"],
        )
        status, image = request(base + portrait_url, raw=True)
        check("GET /asset/portrait 返回 PNG", status == 200 and image[:4] == b"\x89PNG", status)

        status, _ = request(
            base + state["portraits"][0]["url"].split("?")[0] + "?character=Sakura&key=__default__",
            token=None,
        )
        check("立绘没有 token 时被拒绝", status == 400, status)

        status, payload = request(base + "/api/history?character_id=Sakura&limit=10&token=" + TOKEN)
        check("GET /api/history", status == 200 and len(json.loads(payload).get("history", [])) == 2, payload)

        status, payload = request(
            base + "/api/chat",
            method="POST",
            payload={"character_id": "Sakura", "text": "在吗？"},
        )
        chat = json.loads(payload) if status == 200 else {}
        segments = chat.get("segments", [])
        check("POST /api/chat 透传分段", status == 200 and len(segments) == 2 and segments[1]["tone"] == "害羞", payload)

        status, audio = request(
            base + "/api/tts",
            method="POST",
            payload={"character_id": "Sakura", "text": "いるよ。", "tone": "中性"},
            raw=True,
        )
        check("POST /api/tts 返回 WAV", status == 200 and audio[:4] == b"RIFF", (status, audio[:4]))

        status, audio2 = request(
            base + "/api/tts",
            method="POST",
            payload={"character_id": "Sakura", "text": "いるよ。", "tone": "中性"},
            raw=True,
        )
        check("POST /api/tts 命中缓存", status == 200 and audio2 == audio, status)

        upload_png = (
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg=="
        )
        status, payload = request(
            base + "/api/upload",
            method="POST",
            payload={"media_type": "image/png", "data": upload_png},
        )
        uploaded = json.loads(payload) if status == 200 else {}
        check("POST /api/upload 返回资源地址", status == 200 and uploaded.get("url", "").startswith("/cache/uploads/"), payload)

        status, image = request(base + uploaded.get("url", "/nope") + "?token=" + TOKEN, raw=True)
        check("GET /cache/uploads 可回读", status == 200 and image[:4] == b"\x89PNG", status)

        status, payload = request(base + "/api/state", token="wrong-token")
        check("错误 token 被拒绝", status == 400 and "配对码无效" in payload, (status, payload))

        status, _ = request(base + "/api/nope?token=" + TOKEN)
        check("未知路径返回 404", status == 404, status)
    finally:
        server.shutdown()
        server.server_close()

    # ---- 全新安装：电脑端一个角色都没有 ----
    # 这里最容易出事：宿主在无角色时抛 ASSISTANT_NOT_READY，
    # 而手机端 loadState() 一旦失败就整页显示「无法连接电脑端」，
    # 连配置页都打不开 —— 用户就没法自救。所以这些接口必须降级而不是报错。
    bare_data = tmp_dir / "plugin-data-bare"
    bare_data.mkdir(parents=True, exist_ok=True)
    bare_server = http_server.run_remote_server(
        bare_data,
        mobile_service=NoCharacterMobile(),
        character_service=FakeCharacters(),
        tts_service=None,
        host="127.0.0.1",
        port=8792,
        token=TOKEN,
    )
    bare_thread = threading.Thread(target=bare_server.serve_forever, daemon=True)
    bare_thread.start()
    time.sleep(0.3)
    bare = "http://127.0.0.1:8792"
    try:
        status, payload = request(bare + "/api/state?token=" + TOKEN)
        body = json.loads(payload) if status == 200 else {}
        check("无角色时 /api/state 仍返回 200", status == 200, (status, payload[:120]))
        check("无角色时标记 noCharacter", body.get("noCharacter") is True, body)
        check("无角色时立绘列表为空", body.get("portraits") == [], body.get("portraits"))
        check("无角色时角色 id 为空", body.get("characterId") == "", body.get("characterId"))

        status, payload = request(bare + "/api/characters?token=" + TOKEN)
        body = json.loads(payload) if status == 200 else {}
        check("无角色时 /api/characters 仍返回 200", status == 200, (status, payload[:120]))
        check("无角色时清单为空", body.get("characters") == [], body)

        # 配置页要能用：这些是它依赖的接口
        status, payload = request(bare + "/api/settings?token=" + TOKEN)
        check("无角色时 /api/settings 可用", status == 200, status)

        status, payload = request(bare + "/?token=" + TOKEN)
        check("无角色时网页仍可打开", status == 200 and "__SAKURA__" in payload, status)

        # 切换角色应给出可读提示，而不是 500 或写坏配置。
        # 503 是准确语义：角色服务不可用（不是参数错）。
        status, payload = request(
            bare + "/api/characters",
            method="POST",
            payload={"character_id": "Sakura"},
        )
        check(
            "无角色时切换被明确拒绝",
            status == 503 and "角色" in payload,
            (status, payload[:120]),
        )
    finally:
        bare_server.shutdown()
        bare_server.server_close()

    print()
    if failures:
        print(f"{len(failures)} 项失败：" + ", ".join(failures))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
