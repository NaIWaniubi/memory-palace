#!/usr/bin/env python3
"""生成空间提示音资产（M13）。

为什么需要自己生成：PICO Spatial SDK 的空间音频走 AudioResource.load(FROM_ASSETS)，
音源必须是真实音频文件；官方 Sample 用的是鸟鸣素材，与本项目语义不符
（我们要的是"这里有一张卡到期了"的方向提示，不是环境音）。

生成物（全部为 48kHz / 16-bit / 单声道 WAV）：
  cue_due.wav    到期提示钟声（两声上行短音），空间定位为"目标卡片方向"
  cue_place.wav  放置确认提示（一声上行滑音）
  cue_done.wav   复习完成提示（一声下行滑音）

用法：python scripts/gen_audio.py
输出：app/src/main/assets/audio/*.wav
"""

import math
import os
import struct
import wave

SAMPLE_RATE = 48000
OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "audio",
)


def _envelope(i: int, n: int, attack: float = 0.02, release: float = 0.35) -> float:
    """短促起音 + 指数衰减，避免爆音（空间音频对方位判断靠波形包络，突变会引入咔哒）。"""
    t = i / SAMPLE_RATE
    total = n / SAMPLE_RATE
    a = min(1.0, t / attack) if attack > 0 else 1.0
    r = math.exp(-max(0.0, total - t) / release)
    return a * r


def _tone(duration: float, freq: float, gain: float = 0.6, phase: float = 0.0) -> list:
    n = int(SAMPLE_RATE * duration)
    out = []
    for i in range(n):
        t = i / SAMPLE_RATE
        # 叠一点二次谐波，听感更"电子"，也更像设备提示音而非乐器
        v = math.sin(2 * math.pi * freq * t + phase)
        v += 0.25 * math.sin(4 * math.pi * freq * t + phase)
        out.append(v * gain * _envelope(i, n))
    return out


def _sweep(duration: float, f0: float, f1: float, gain: float = 0.55) -> list:
    n = int(SAMPLE_RATE * duration)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / SAMPLE_RATE
        k = t / duration
        freq = f0 + (f1 - f0) * k
        phase += 2 * math.pi * freq / SAMPLE_RATE
        out.append(math.sin(phase) * gain * _envelope(i, n, attack=0.01, release=0.25))
    return out


def _silence(duration: float) -> list:
    return [0.0] * int(SAMPLE_RATE * duration)


def _write(name: str, samples: list) -> str:
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name)
    frames = bytearray()
    for s in samples:
        v = max(-1.0, min(1.0, s))
        frames += struct.pack("<h", int(v * 32767))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(bytes(frames))
    return path


def build_cue_due() -> list:
    """到期提示：两声上行（880Hz → 1174Hz），间隔 90ms。总长约 0.62s。"""
    return (
        _tone(0.13, 880.0)
        + _silence(0.09)
        + _tone(0.22, 1174.66)
        + _silence(0.18)
    )


def build_cue_place() -> list:
    """放置确认：一声上行滑音 520→1040Hz。总长约 0.35s。"""
    return _sweep(0.35, 520.0, 1040.0) + _silence(0.15)


def build_cue_done() -> list:
    """复习完成：一声下行滑音 1040→660Hz。总长约 0.45s。"""
    return _sweep(0.45, 1040.0, 660.0) + _silence(0.2)


def main() -> None:
    for name, builder in (
        ("cue_due.wav", build_cue_due),
        ("cue_place.wav", build_cue_place),
        ("cue_done.wav", build_cue_done),
    ):
        path = _write(name, builder())
        size = os.path.getsize(path)
        print(f"{path}  ({size} bytes)")


if __name__ == "__main__":
    main()
