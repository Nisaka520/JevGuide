# -*- coding: utf-8 -*-
"""check_secrets.py —— 提交前自检：仓库里不该出现密钥/账号痕迹

用法（在仓库根目录）：python test/check_secrets.py
退出码 0 = 干净；1 = 有可疑内容。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SELF = os.path.abspath(__file__)

# 特征串用拼接写，免得本文件自己被自己命中
PATTERNS = [
    ("apikey" + r"_[0-9a-fA-F]{8}", "疑似真实 API key"),
    ("outlook" + r"\.com|gmail\.com|qq\.com", "邮箱账号"),
    ("wxid" + r"_[a-z0-9]{10,}", "真实 wxid"),
    ("sk-" + r"[A-Za-z0-9]{20,}", "疑似 OpenAI 风格密钥"),
]

SKIP_DIRS = {".git", "build", ".gradle", ".idea", "out", "dist"}
SKIP_FILES = {os.path.basename(SELF)}
TEXT_EXT = {".kt", ".kts", ".md", ".xml", ".json", ".properties", ".yml", ".yaml", ".txt", ".py", ".pro"}


def main() -> int:
    bad = 0
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn in SKIP_FILES:
                continue
            ext = os.path.splitext(fn)[1].lower()
            if ext not in TEXT_EXT:
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            try:
                text = io.open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                for pat, what in PATTERNS:
                    if re.search(pat, line):
                        print(f"✗ {rel}:{i}  {what}")
                        print(f"    {line.strip()[:120]}")
                        bad += 1
    if bad:
        print(f"\n❌ 共 {bad} 处问题，修掉再提交")
        return 1
    print("\n✅ 干净：没有密钥/账号痕迹")
    return 0


if __name__ == "__main__":
    sys.exit(main())
