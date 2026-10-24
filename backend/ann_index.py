"""
Approximate nearest-neighbour index over every page descriptor in the library (faiss).

Each archive owns one contiguous block of ids, so archives can be added or removed
without rebuilding everything. Small libraries use an exact flat index; once the
library is large enough an IVF-PQ index (32 bytes per descriptor) is trained.
"""

import json
import os
import threading
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import faiss
import numpy as np

from backend.feature_store import FeatureStore, to_float
from backend.archive_handler import MangaArchive

INDEX_VERSION = 1
IVF_MIN_DESCRIPTORS = 600_000   # below this, exact search is fast enough
IVF_LISTS = 4096
PQ_BYTES = 32
TRAIN_SAMPLES = 200_000


class AnnIndex:
    def __init__(self, root: Path, store: FeatureStore):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.store = store
        self.index: Optional[faiss.Index] = None
        # folder name -> block info {"base", "n", "name"}
        self.blocks: Dict[str, Dict] = {}
        self.next_id = 0
        # _lock guards the index object for short operations (search, one add chunk);
        # _write_lock serializes writers so long additions never block searches for long.
        self._lock = threading.RLock()
        self._write_lock = threading.RLock()
        self._starts = np.zeros(0, np.int64)
        self._block_keys: List[str] = []

    # ---------- persistence ----------
    @property
    def _index_path(self) -> Path:
        return self.root / "ann.faiss"

    @property
    def _meta_path(self) -> Path:
        return self.root / "ann.json"

    def load(self) -> bool:
        with self._lock:
            if not (self._index_path.exists() and self._meta_path.exists()):
                return False
            try:
                meta = json.loads(self._meta_path.read_text(encoding="utf-8"))
                if meta.get("version") != INDEX_VERSION:
                    return False
                self.index = faiss.read_index(str(self._index_path))
                self.blocks = meta["blocks"]
                self.next_id = meta["next_id"]
                self._refresh_lookup()
                return True
            except Exception as e:
                print(f"[AnnIndex] load failed, will rebuild: {e}")
                self.index, self.blocks, self.next_id = None, {}, 0
                return False

    def save(self) -> None:
        # Readers (search) can keep running while the index is written; writers are excluded.
        with self._write_lock:
            if self.index is None:
                return
            tmp = self._index_path.with_suffix(".tmp")
            faiss.write_index(self.index, str(tmp))
            os.replace(tmp, self._index_path)
            self._meta_path.write_text(json.dumps(
                {"version": INDEX_VERSION, "blocks": self.blocks, "next_id": self.next_id}, ensure_ascii=False),
                encoding="utf-8")

    def _refresh_lookup(self) -> None:
        items = sorted(self.blocks.items(), key=lambda kv: kv[1]["base"])
        self._block_keys = [k for k, _ in items]
        self._starts = np.array([v["base"] for _, v in items], np.int64)

    # ---------- building ----------
    def _is_ivf(self) -> bool:
        return self.index is not None and faiss.try_extract_index_ivf(self.index) is not None

    def _new_index(self, sample: Optional[np.ndarray]) -> faiss.Index:
        if sample is None:
            return faiss.IndexIDMap2(faiss.IndexFlatIP(128))
        quantizer = faiss.IndexHNSWFlat(128, 32, faiss.METRIC_INNER_PRODUCT)
        ivf = faiss.IndexIVFPQ(quantizer, 128, IVF_LISTS, PQ_BYTES, 8, faiss.METRIC_INNER_PRODUCT)
        ivf.train(sample)
        return ivf

    def _sample(self, archives: List[MangaArchive]) -> np.ndarray:
        rng = np.random.default_rng(0)
        total = sum(self.store.get(a).count for a in archives)
        frac = min(1.0, TRAIN_SAMPLES / max(1, total))
        parts = []
        for a in archives:
            f = self.store.get(a)
            if f.count == 0:
                continue
            d = f.desc_rows(0, f.count)
            k = max(1, int(len(d) * frac))
            parts.append(to_float(d[np.sort(rng.choice(len(d), k, replace=False))]))
        return np.concatenate(parts)

    def add(self, archives: Iterable[MangaArchive], progress=None) -> bool:
        """Add archives that are not in the index yet (never removes anything)."""
        archives = [a for a in archives if self.store.has(a)]
        with self._write_lock:
            missing = [a for a in archives if self.store.folder(a).name not in self.blocks]
            changed = self._add(missing, archives, progress)
            if changed:
                self.save()
            return changed

    def sync(self, archives: Iterable[MangaArchive], progress=None) -> bool:
        """Make the index contain exactly these archives (features must already be in the store)."""
        archives = [a for a in archives if self.store.has(a)]
        wanted = {self.store.folder(a).name: a for a in archives}
        with self._write_lock:
            changed = False
            for k in [k for k in self.blocks if k not in wanted]:
                with self._lock:
                    b = self.blocks.pop(k)
                    self._refresh_lookup()
                    if self.index is not None:
                        self.index.remove_ids(faiss.IDSelectorRange(b["base"], b["base"] + b["n"]))
                changed = True
            missing = [a for k, a in wanted.items() if k not in self.blocks]
            changed = self._add(missing, archives, progress) or changed
            if changed:
                self.save()
            return changed

    def _add(self, missing: List[MangaArchive], everything: List[MangaArchive], progress) -> bool:
        if not missing and self.index is not None:
            return False
        total = sum(b["n"] for b in self.blocks.values()) + sum(self.store.get(a).count for a in missing)
        if total >= IVF_MIN_DESCRIPTORS and not self._is_ivf():
            # Library got big: train IVF-PQ once and re-add everything currently indexed.
            keep = set(self.blocks)
            pool = [a for a in everything if self.store.folder(a).name in keep or a in missing]
            print(f"[AnnIndex] training IVF-PQ on {total:,} descriptors...")
            new_index = self._new_index(self._sample(pool))
            with self._lock:
                self.index = new_index
                self.blocks, self.next_id = {}, 0
                self._refresh_lookup()
            missing = pool
        elif self.index is None:
            with self._lock:
                self.index = self._new_index(None)
        for i, a in enumerate(missing):
            feats = self.store.get(a)
            n = feats.count
            base = self.next_id
            ids = np.arange(base, base + n, dtype=np.int64)
            for s in range(0, n, 200_000):
                chunk = to_float(feats.desc_rows(s, s + 200_000))
                with self._lock:   # searches can run between chunks
                    self.index.add_with_ids(chunk, ids[s:s + 200_000])
            with self._lock:
                # the block becomes visible to searches only once it is complete
                self.blocks[self.store.folder(a).name] = {"base": base, "n": n, "name": a.name}
                self.next_id = base + n
                self._refresh_lookup()
            if progress:
                progress(i + 1, len(missing))
        return True

    # ---------- querying ----------
    def query(self, q: np.ndarray, k: int, nprobe: int):
        """kNN search + id lookup in one consistent snapshot: (D, block, row, block_keys)."""
        with self._lock:
            if self.index is None or self.index.ntotal == 0:
                empty = -np.ones((len(q), k), np.int64)
                return np.zeros((len(q), k), np.float32), empty, empty.copy(), []
            ivf = faiss.try_extract_index_ivf(self.index)
            if ivf is not None:
                ivf.nprobe = nprobe
            D, I = self.index.search(q, k)
            blk, row = self.locate(I)
            return D, blk, row, list(self._block_keys)

    def locate(self, ids: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        """Map descriptor ids -> (block index, row within that archive's store). -1 for unknown."""
        with self._lock:
            starts = self._starts
            if len(starts) == 0:
                return -np.ones_like(ids), -np.ones_like(ids)
            b = np.searchsorted(starts, ids, side="right") - 1
            b = np.clip(b, 0, len(starts) - 1)
            row = ids - starts[b]
            ns = np.array([self.blocks[k]["n"] for k in self._block_keys], np.int64)
            bad = (ids < 0) | (row < 0) | (row >= ns[b])
            b[bad] = -1
            row[bad] = -1
            return b, row

    def block_keys(self) -> List[str]:
        with self._lock:
            return list(self._block_keys)
