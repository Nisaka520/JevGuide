# -*- coding: utf-8 -*-
"""生成 JevGuide（弦外之音）的视觉资产：App 图标 + 首页横幅插画。
用法: python gen_assets.py   （输出到 ./out/）
"""
import base64
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SECRETS = os.path.join(HERE, "..", "..", "galgame", "build", "secrets.local.json")
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

cfg = json.load(open(SECRETS, encoding="utf-8"))
BASE, KEY = cfg["base_url"].rstrip("/"), cfg["api_key"]
MODEL = "gpt-image-2.5-flare-adobe"

ICON_PROMPT = (
    "Modern flat Android app icon, single rounded-square composition on solid deep navy background (#0F1420), "
    "centered subject occupying about 60% of canvas with generous even margins. "
    "Subject: a minimal rounded chat speech bubble, and inside the bubble a horizontal sound waveform whose bars "
    "gradually rise like a heartbeat; the last waveform bar subtly bends into a tiny heart silhouette. "
    "Color: smooth indigo-to-cyan gradient (#6C8CFF to #4FD8E8) with soft outer glow, flat illustration, "
    "clean thick rounded shapes, no text, no letters, no border, no watermark, not photorealistic."
)

BANNER_PROMPT = (
    "Wide abstract header illustration for a mobile app, flat minimal style, deep navy background (#101623). "
    "Soft glowing horizontal sound wave lines flowing left to right in indigo-cyan gradient (#6C8CFF to #4FD8E8), "
    "a few small floating rounded chat bubbles drifting above the waves, subtle sparkle dots, "
    "lots of empty space on the left third for text overlay, no text, no letters, no watermark, clean vector look."
)


def gen(prompt, size, name):
    body = json.dumps({"model": MODEL, "prompt": prompt, "size": size, "n": 1}).encode()
    req = urllib.request.Request(
        BASE + "/images/generations",
        data=body,
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
    )
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.load(r)
    url = d["data"][0]["url"]
    with urllib.request.urlopen(url, timeout=120) as r:
        png = r.read()
    path = os.path.join(OUT, name)
    open(path, "wb").write(png)
    print(f"{name}: {len(png)//1024}KB  {time.time()-t0:.0f}s  -> {path}")


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("all", "icon"):
        try:
            gen(ICON_PROMPT, "1024x1024", "icon_raw.png")
        except Exception as e:
            print("1024x1024 failed:", e, "-> retry 1024x1536")
            gen(ICON_PROMPT, "1024x1536", "icon_raw.png")
    if which in ("all", "banner"):
        gen(BANNER_PROMPT, "1536x1024", "banner_raw.png")
