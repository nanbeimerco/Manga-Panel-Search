"""
Accuracy / speed benchmark for the search engine.

Test images are named "<volume>巻<page>ページ.jpg" (e.g. 14巻95ページ.jpg).
The library is a folder of volume archives whose names contain "第<volume>巻".

Usage:
  python tests/bench_search.py --tests data/test --library "<folder of volumes>" [--cache <dir>]
"""

import argparse
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cv2
import numpy as np

from backend.archive_handler import scan_multiple_targets
from backend.search_engine import MangaSearchEngine


def parse_answer(name: str):
    """'14巻95ページ.jpg' or '... 第08巻.rar_page170.png' -> (volume, page). None = not in library."""
    m = re.search(r"(\d+)巻(\d+)ページ", name) or re.search(r"第(\d+)巻.*?page(\d+)", name, re.IGNORECASE)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def volume_of(archive_name: str):
    m = re.search(r"第(\d+)巻", archive_name)
    return int(m.group(1)) if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tests", required=True)
    ap.add_argument("--library", required=True)
    ap.add_argument("--cache", default=None)
    ap.add_argument("--top-k", type=int, default=5)
    args = ap.parse_args()

    user_dir = Path(os.environ["APPDATA"]) / "MangaPanelSearch"
    cache = Path(args.cache) if args.cache else user_dir / "index_v2"
    engine = MangaSearchEngine(cache_dir=cache, legacy_cache_dir=user_dir / ".cache_descriptors")

    t0 = time.perf_counter()
    archives = scan_multiple_targets([args.library])
    print(f"library: {len(archives)} archives, {sum(len(a) for a in archives)} pages "
          f"(scan {time.perf_counter() - t0:.1f}s)")

    t0 = time.perf_counter()
    engine.ensure_ready(archives)
    print(f"index ready: {time.perf_counter() - t0:.1f}s")

    tests = sorted(p for p in Path(args.tests).iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp"))
    top1 = top5 = 0
    times = []
    for p in tests:
        ans = parse_answer(p.name)
        img = cv2.imdecode(np.fromfile(str(p), np.uint8), cv2.IMREAD_COLOR)
        t0 = time.perf_counter()
        results = engine.search_query(img, archives, top_k=args.top_k)
        dt = time.perf_counter() - t0
        times.append(dt)

        got = []
        for r in results:
            got.append((volume_of(r.archive_name), r.page_index + 1, round(r.score, 3), r.inliers_count))
        rank = None
        for i, g in enumerate(got):
            if ans and (g[0], g[1]) == ans:
                rank = i + 1
                break
        if ans is None and not any(r.score >= 0.5 for r in results):
            rank = 1  # not in the library: correct when nothing confident is reported
        if rank == 1:
            top1 += 1
        if rank is not None:
            top5 += 1
        mark = "OK " if rank == 1 else (f"#{rank} " if rank else "NG ")
        print(f"{mark} {p.name:20s} answer={ans} {dt:6.1f}s  got={got[:3]}")

    n = len(tests)
    print(f"\nTop-1: {top1}/{n}  Top-{args.top_k}: {top5}/{n}  "
          f"avg time: {np.mean(times):.1f}s  max: {np.max(times):.1f}s")


if __name__ == "__main__":
    main()
