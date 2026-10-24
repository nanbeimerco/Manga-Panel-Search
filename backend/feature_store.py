"""
Per-archive feature store.

Each archive gets one folder holding all of its pages' SIFT features:
  kp.npy       float32 (N, 2)   keypoint x, y in original page pixels
  desc.npy     uint8   (N, 128) RootSIFT descriptors scaled to 0-255
  offsets.npy  int64   (P + 1,) page p owns rows offsets[p]:offsets[p+1]
  shapes.npy   int32   (P, 2)   page width, height
  info.json    archive path / size / mtime, used to detect changes

This replaces the old one-pickle-per-page float32 cache (4x larger, slow to read).
"""

import hashlib
import json
import os
import pickle
import shutil
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable, Dict, Optional, Tuple

import cv2
import numpy as np

from backend.archive_handler import MangaArchive

STORE_VERSION = 1
PAGE_FEATURES = 2000      # SIFT keypoints kept per page
PAGE_MAX_DIM = 1400       # pages larger than this are downscaled before SIFT


def rootsift(desc: np.ndarray) -> np.ndarray:
    """RootSIFT (L1 normalize + sqrt). Result is L2-normalized float32."""
    d = desc.astype(np.float32)
    d /= d.sum(axis=1, keepdims=True) + 1e-7
    return np.sqrt(d)


def to_uint8(desc_f: np.ndarray) -> np.ndarray:
    return np.clip(np.rint(desc_f * 255.0), 0, 255).astype(np.uint8)


def to_float(desc_u8: np.ndarray) -> np.ndarray:
    """uint8 store format -> L2-normalized float32 for inner-product matching."""
    d = desc_u8.astype(np.float32)
    d /= np.linalg.norm(d, axis=1, keepdims=True) + 1e-7
    return d


def extract_sift(gray: np.ndarray, nfeatures: int, max_dim: Optional[int] = None,
                 scale: float = 1.0) -> Tuple[np.ndarray, np.ndarray]:
    """SIFT keypoints (x, y in input pixels) and RootSIFT float32 descriptors."""
    h, w = gray.shape[:2]
    s = scale
    if max_dim and max(h, w) * s > max_dim:
        s = max_dim / max(h, w)
    if s != 1.0:
        interp = cv2.INTER_AREA if s < 1.0 else cv2.INTER_CUBIC
        img = cv2.resize(gray, (max(1, int(w * s)), max(1, int(h * s))), interpolation=interp)
    else:
        img = gray
    sift = cv2.SIFT_create(nfeatures=nfeatures)
    kps, desc = sift.detectAndCompute(img, None)
    if desc is None or len(kps) == 0:
        return np.zeros((0, 2), np.float32), np.zeros((0, 128), np.float32)
    pts = np.array([k.pt for k in kps], np.float32) / s
    return pts, rootsift(desc)


def archive_key(archive: MangaArchive) -> str:
    h = hashlib.sha1(str(archive.path).lower().encode("utf-8")).hexdigest()[:16]
    safe = "".join(c if c.isalnum() else "_" for c in archive.path.stem)[:40]
    return f"{safe}_{h}"


def _fingerprint(archive: MangaArchive) -> Dict:
    try:
        st = archive.path.stat()
        size, mtime = (st.st_size, int(st.st_mtime)) if not archive.is_dir else (0, int(st.st_mtime))
    except OSError:
        size, mtime = -1, -1
    return {"path": str(archive.path), "size": size, "mtime": mtime, "pages": len(archive), "version": STORE_VERSION}


class ArchiveFeatures:
    """
    Features of one archive. Only the small offset/shape tables stay in memory; the big
    arrays are memory-mapped on each access and released right away, so Windows never
    keeps the files locked.
    """

    def __init__(self, folder: Path):
        self.folder = folder
        self.offsets = np.load(folder / "offsets.npy")
        self.shapes = np.load(folder / "shapes.npy")

    @property
    def page_count(self) -> int:
        return len(self.offsets) - 1

    @property
    def count(self) -> int:
        return int(self.offsets[-1])

    def desc_rows(self, start: int, stop: int) -> np.ndarray:
        m = np.load(self.folder / "desc.npy", mmap_mode="r")
        out = np.array(m[start:stop])
        del m
        return out

    def page(self, p: int) -> Tuple[np.ndarray, np.ndarray, Tuple[int, int]]:
        a, b = int(self.offsets[p]), int(self.offsets[p + 1])
        km = np.load(self.folder / "kp.npy", mmap_mode="r")
        kp = np.array(km[a:b])
        del km
        return kp, self.desc_rows(a, b), (int(self.shapes[p, 0]), int(self.shapes[p, 1]))


class FeatureStore:
    def __init__(self, root: Path, legacy_cache_dir: Optional[Path] = None):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.legacy_cache_dir = Path(legacy_cache_dir) if legacy_cache_dir else None
        self._open: Dict[str, ArchiveFeatures] = {}
        self._lock = threading.Lock()
        self._build_locks: Dict[str, threading.Lock] = {}

    def folder(self, archive: MangaArchive) -> Path:
        # The fingerprint is part of the name, so a changed archive gets a fresh folder and
        # never has to overwrite files that may still be memory-mapped (Windows locks them).
        fp = json.dumps(_fingerprint(archive), sort_keys=True)
        return self.root / f"{archive_key(archive)}_{hashlib.sha1(fp.encode()).hexdigest()[:8]}"

    def has(self, archive: MangaArchive) -> bool:
        return (self.folder(archive) / "info.json").exists()

    def get(self, archive: MangaArchive) -> ArchiveFeatures:
        folder = self.folder(archive)
        with self._lock:
            feats = self._open.get(folder.name)
        if feats is None:
            feats = ArchiveFeatures(folder)
            with self._lock:
                self._open[folder.name] = feats
        return feats

    def ensure(self, archive: MangaArchive, progress: Optional[Callable[[int, int], None]] = None) -> ArchiveFeatures:
        """Return features, building (or migrating from the old cache) when missing."""
        key = archive_key(archive)
        with self._lock:
            lock = self._build_locks.setdefault(key, threading.Lock())
        with lock:
            if not self.has(archive):
                if not self._migrate_legacy(archive, progress):
                    self._build(archive, progress)
        return self.get(archive)

    def _remove_stale(self, archive: MangaArchive, keep: Path) -> None:
        prefix = archive_key(archive) + "_"
        for old in self.root.glob(prefix + "*"):
            if old != keep and old.name[len(prefix):].isalnum():
                with self._lock:
                    self._open.pop(old.name, None)
                shutil.rmtree(old, ignore_errors=True)

    # ---------- writing ----------
    def _write(self, archive: MangaArchive, kps, descs, shapes) -> None:
        folder = self.folder(archive)
        tmp = folder.with_name(folder.name + ".tmp")
        shutil.rmtree(tmp, ignore_errors=True)
        tmp.mkdir(parents=True)
        counts = [len(k) for k in kps]
        offsets = np.zeros(len(counts) + 1, np.int64)
        offsets[1:] = np.cumsum(counts)
        np.save(tmp / "kp.npy", np.concatenate(kps) if kps else np.zeros((0, 2), np.float32))
        np.save(tmp / "desc.npy", np.concatenate(descs) if descs else np.zeros((0, 128), np.uint8))
        np.save(tmp / "offsets.npy", offsets)
        np.save(tmp / "shapes.npy", np.array(shapes, np.int32).reshape(-1, 2))
        (tmp / "info.json").write_text(json.dumps(_fingerprint(archive), ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, folder)
        self._remove_stale(archive, folder)

    def _build(self, archive: MangaArchive, progress) -> None:
        n = len(archive)
        kps = [np.zeros((0, 2), np.float32)] * n
        descs = [np.zeros((0, 128), np.uint8)] * n
        shapes = [(0, 0)] * n
        done = [0]
        done_lock = threading.Lock()

        def work(item):
            i, data = item
            try:
                gray = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_GRAYSCALE)
                if gray is not None:
                    pts, d = extract_sift(gray, PAGE_FEATURES, max_dim=PAGE_MAX_DIM)
                    kps[i], descs[i], shapes[i] = pts, to_uint8(d), (gray.shape[1], gray.shape[0])
            except Exception as e:
                print(f"[FeatureStore] {archive.name} p{i}: {e}")
            with done_lock:
                done[0] += 1
                if progress:
                    progress(done[0], n)

        workers = max(1, min(8, os.cpu_count() or 4))
        with ThreadPoolExecutor(workers) as ex:
            # map() keeps a bounded number of decoded pages in flight
            list(ex.map(work, archive.iter_page_bytes()))
        self._write(archive, kps, descs, shapes)

    def _migrate_legacy(self, archive: MangaArchive, progress) -> bool:
        """Convert the old per-page pickle cache (raw SIFT float32) without re-reading images."""
        if not self.legacy_cache_dir or not self.legacy_cache_dir.exists():
            return False
        n = len(archive)
        paths = []
        for i in range(n):
            safe = "".join(c if c.isalnum() else "_" for c in f"{archive.name}_{i}")
            p = self.legacy_cache_dir / f"{safe}.pkl"
            if not p.exists():
                return False
            paths.append(p)
        kps, descs, shapes = [], [], []
        for i, p in enumerate(paths):
            try:
                with open(p, "rb") as f:
                    data = pickle.load(f)
            except Exception:
                return False
            d = data.get("descs")
            pts = np.asarray(data.get("pts"), np.float32).reshape(-1, 2)
            if d is None or len(d) == 0:
                kps.append(np.zeros((0, 2), np.float32)); descs.append(np.zeros((0, 128), np.uint8))
            else:
                kps.append(pts); descs.append(to_uint8(rootsift(np.asarray(d))))
            shapes.append(tuple(data.get("shape", (0, 0))))
            if progress:
                progress(i + 1, n)
        self._write(archive, kps, descs, shapes)
        return True
