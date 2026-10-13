"""VOICE-01 服务端同 key 在途去重的真实生产行为测试。

只驱动 http_server.RemoteService.synthesize 的真实去重/缓存实现；
TTS 供应商用最小替身（不调用任何真实语音服务，不读取真实内容）。

覆盖：
  1. 同一角色+tone+文本并发 N 次 → 只调用一次供应商（在途合流）；
  2. 完成后再次请求 → 命中缓存，不再调用供应商；
  3. 不同文本 → 各自调用一次；
  4. leader 失败：等待者不挂死、明确报错；后续重试能重新成为 leader。

用法: py -3.13 tests/tts_dedup_check.py
"""

from __future__ import annotations

import logging
import shutil
import sys
import threading
import time
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_ROOT = ROOT if (ROOT / "plugin.yaml").is_file() else ROOT / "sakura_remote"
sys.path.insert(0, str(PLUGIN_ROOT))

import http_server  # noqa: E402

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: object = "") -> None:
    print(f"[{'PASS' if condition else 'FAIL'}] {name}" + ("" if condition else f" -> {detail!r}"))
    if not condition:
        FAILURES.append(name)


class FakeTTS:
    """最小 TTS 供应商替身：begin 时写一个合法 WAV 产物。"""

    GENERATION = "sakura.tts.genie"

    def __init__(self, base_dir: Path, *, fail_times: int = 0, begin_delay: float = 0.15) -> None:
        self.calls = 0
        self.fail_times = fail_times
        self.begin_delay = begin_delay
        self._lock = threading.Lock()
        self._requests: dict[str, str] = {}
        # 与 RemoteService._artifact_root 对齐
        self._artifact_root = (base_dir.parent.parent / "cache" / "plugin-artifacts").resolve()

    def begin(self, payload):
        with self._lock:
            self.calls += 1
            call_index = self.calls
        time.sleep(self.begin_delay)
        if call_index <= self.fail_times:
            return {"state": "failed", "errorCode": "TTS_SYNTHETIC_FAILURE"}
        request_id = payload["requestId"]
        artifact_id = "artifact_" + format(call_index, "032x")
        directory = self._artifact_root / self.GENERATION / "sakura.tts" / artifact_id
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / "payload.wav"
        with wave.open(str(path), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(32000)
            handle.writeframes(b"\x00\x00" * 3200)
        self._requests[request_id] = artifact_id
        return {"state": "running", "requestId": request_id, "providerId": self.GENERATION}

    def poll(self, request_id):
        artifact_id = self._requests.get(request_id)
        if artifact_id is None:
            return {"state": "failed", "errorCode": "TTS_JOB_NOT_FOUND"}
        return {
            "state": "succeeded",
            "requestId": request_id,
            "providerId": self.GENERATION,
            "artifact": {"artifactId": artifact_id, "mediaType": "audio/wav", "byteLength": 6400},
        }


def build_service(base_dir: Path, fake: FakeTTS) -> http_server.RemoteService:
    return http_server.RemoteService(
        base_dir,
        mobile_service=None,
        character_service=None,
        tts_service=fake,
        logger=logging.getLogger("tts-dedup"),
        token="synthetic-token",
    )


def main() -> int:
    # 用工作区内的临时目录：系统 temp 在受限沙箱下可能无法清理
    tmp = ROOT / "validation-ux-media-20261012" / ".tmp-tts-dedup"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True, exist_ok=True)
    try:
        base = tmp
        data_dir = base / "data" / "plugins" / "sakura_remote"
        data_dir.mkdir(parents=True, exist_ok=True)
        fake = FakeTTS(data_dir)
        service = build_service(data_dir, fake)

        # 1) 并发同一句：只允许一次供应商调用
        results: list[object] = []
        errors: list[object] = []
        barrier = threading.Barrier(6)

        def worker() -> None:
            try:
                barrier.wait(timeout=5)
                results.append(service.synthesize("role-a", "同一句话", "tone-x"))
            except Exception as error:  # noqa: BLE001
                errors.append(error)

        threads = [threading.Thread(target=worker) for _ in range(6)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=30)
        check("并发同 key 全部成功返回", len(results) == 6 and not errors,
              {"ok": len(results), "errors": [str(e) for e in errors]})
        check("并发同 key 只调用一次供应商", fake.calls == 1, fake.calls)
        check("并发结果指向同一份音频", len({str(p) for p in results}) == 1,
              {str(p) for p in results})

        # 2) 完成后再次请求：命中缓存
        again = service.synthesize("role-a", "同一句话", "tone-x")
        check("重复请求命中缓存不调用供应商", fake.calls == 1, fake.calls)
        check("缓存命中返回同一路径", str(again) == str(results[0]), (str(again), str(results[0])))

        # 3) 不同文本各自一次
        service.synthesize("role-a", "另一句话", "tone-x")
        check("不同文本各调用一次供应商", fake.calls == 2, fake.calls)

        # 4) leader 失败：等待者不挂死；后续重试可重新成为 leader
        failing = FakeTTS(data_dir, fail_times=1, begin_delay=0.05)
        failing_service = build_service(data_dir, failing)
        fail_errors: list[object] = []
        fail_barrier = threading.Barrier(3)

        def fail_worker() -> None:
            try:
                fail_barrier.wait(timeout=5)
                failing_service.synthesize("role-b", "会失败一次", "")
            except Exception as error:  # noqa: BLE001
                fail_errors.append(error)

        fail_threads = [threading.Thread(target=fail_worker) for _ in range(3)]
        for thread in fail_threads:
            thread.start()
        for thread in fail_threads:
            thread.join(timeout=30)
        check("leader 失败时所有等待者都明确报错（不挂死）",
              len(fail_errors) == 3, len(fail_errors))
        check("失败只调用一次供应商（没有惊群重试）", failing.calls == 1, failing.calls)
        retried = failing_service.synthesize("role-b", "会失败一次", "")
        check("失败后重试可以重新成为 leader", failing.calls == 2 and retried.is_file(),
              {"calls": failing.calls, "exists": retried.is_file()})
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print()
    if FAILURES:
        print(f"{len(FAILURES)} 项失败：{', '.join(FAILURES)}")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
