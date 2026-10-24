"""
Search engine for manga panel matching.

Pipeline:
  1. SIFT (RootSIFT) features of the query at two scales.
  2. Stage 1 - every query descriptor looks up its nearest page descriptors in a faiss
     index over the whole library; each hit votes for its page. This narrows thousands
     of pages to a few dozen candidates in well under a second.
  3. Stage 2 - each candidate is matched exactly (ratio test + one-to-one constraint)
     and verified with RANSAC; the inlier count decides the result and the panel box.
"""

import gc
import os
import re
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

import cv2
import numpy as np

from backend.ann_index import AnnIndex
from backend.archive_handler import MangaArchive
from backend.feature_store import FeatureStore, extract_sift, to_float

QUERY_FEATURES = 1500          # per scale
QUERY_SCALES = (1.0, 0.5)
QUERY_MAX_DIM = 1000
ANN_K = 10
MIN_INLIERS = 8

ProgressFn = Callable[[float, str], None]


def normalize_manga_photo(img: np.ndarray) -> np.ndarray:
    """
    Adaptive paper normalization for photo/yellowed manga pages.
    High-precision NumPy percentile contrast stretching and background division.
    Restores crisp black ink lines and eliminates ambient room light & yellowing without float rounding artifacts.
    """
    if len(img.shape) == 3 and img.shape[2] == 3:
        # Check if actually color (saturation / channel variance)
        diff_rg = np.abs(img[:, :, 0].astype(np.int16) - img[:, :, 1].astype(np.int16))
        diff_gb = np.abs(img[:, :, 1].astype(np.int16) - img[:, :, 2].astype(np.int16))
        is_color = (np.mean(diff_rg) > 3.0) or (np.mean(diff_gb) > 3.0)
        if is_color:
            # Color artwork / colorized panel: min across color channels strongly enhances black ink line art
            # while pushing tinted/colored backgrounds towards white.
            gray = np.min(img, axis=2)
        else:
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    elif len(img.shape) == 3:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    else:
        gray = img.copy()

    # Check if image is already high-contrast crisp digital manga (clean whites & blacks)
    p_low = np.percentile(gray, 2)
    p_high = np.percentile(gray, 98)
    if p_low <= 25 and p_high >= 235:
        # Crisp digital scan already: bypass destructive background division to preserve delicate lines
        return gray

    h, w = gray.shape
    ksize = max(25, min(h, w) // 20)
    if ksize % 2 == 0:
        ksize += 1

    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (ksize, ksize))
    background = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, kernel)

    # Divide gray by background to flatten illumination & remove yellow/shadow
    background = np.maximum(background, 1)
    normalized = np.clip((gray.astype(np.float32) / background.astype(np.float32)) * 255.0, 0, 255).astype(np.uint8)

    # Contrast stretch (normalize 1st and 99th percentiles)
    p_low, p_high = np.percentile(normalized, 1), np.percentile(normalized, 99)
    if p_high > p_low:
        stretched = np.clip((normalized.astype(np.float32) - p_low) / (p_high - p_low) * 255.0, 0, 255).astype(np.uint8)
    else:
        stretched = normalized

    return stretched



def parse_manga_volume_and_page(archive_name: str, page_filename: str, page_number: int) -> Dict[str, Any]:
    """
    Parse archive name and page filename to determine volume name and within-volume page number.
    e.g.:
      - archive: '(一般コミック) [高橋留美子] 犬夜叉Inuyasha 全56巻.zip', filename: '《犬夜叉》Vol_02/inu02169.jpg'
        -> volume: 'Vol.02', volume_page: 169, label: '第02巻 169ページ (通算 363ページ)'
      - archive: '(一般コミック) [高橋留美子] らんま1／2 第10巻.rar', filename: '174.png'
        -> volume: '第10巻', volume_page: 174, label: '第10巻 174ページ'
    """
    clean_filename = page_filename.replace("\\", "/")
    parts = clean_filename.split("/")

    vol_str = ""
    vol_num = None
    page_in_vol = None

    # Case 1: check subfolder in archive (e.g. '《犬夜叉》Vol_02/inu02169.jpg')
    if len(parts) > 1:
        folder = parts[0]
        m_vol = re.search(r"(?:vol(?:ume)?|第)?\s*[\._\-]?\s*(\d+)(?:\s*巻)?", folder, re.IGNORECASE)
        if m_vol:
            vol_num = int(m_vol.group(1))
            vol_str = f"第{vol_num:02d}巻" if ("第" in folder or "巻" in folder) else f"Vol.{vol_num:02d}"

    # If no subfolder volume found, check archive_name (e.g. '第10巻.rar')
    if not vol_str:
        m_vol_arc = re.search(r"(?:vol(?:ume)?|第)\s*[\._\-]?\s*(\d+)(?:\s*巻)?", archive_name, re.IGNORECASE)
        if m_vol_arc:
            vol_num = int(m_vol_arc.group(1))
            vol_str = f"第{vol_num:02d}巻"

    # Extract page number within volume from the file basename (e.g. inu02169.jpg -> 169, page174.png -> 174)
    basename = parts[-1]
    name_no_ext = os.path.splitext(basename)[0]
    m_page = re.search(r"(?:page|p|_|-)?(\d+)$", name_no_ext, re.IGNORECASE)
    if m_page:
        num_str = m_page.group(1)
        if vol_num is not None and len(num_str) >= 4 and num_str.startswith(f"{vol_num:02d}"):
            page_in_vol = int(num_str[len(f"{vol_num:02d}"):])
        else:
            page_in_vol = int(num_str)

    # Format human-friendly display label
    if vol_str and page_in_vol is not None:
        if len(parts) > 1:
            display_label = f"{vol_str} {page_in_vol}ページ (通算 {page_number}ページ)"
        else:
            display_label = f"{vol_str} {page_in_vol}ページ"
    elif vol_str:
        display_label = f"{vol_str} {page_number}ページ"
    elif page_in_vol is not None:
        display_label = f"PAGE {page_in_vol}"
    else:
        display_label = f"PAGE {page_number}"

    return {
        "volume_name": vol_str,
        "volume_page": page_in_vol,
        "display_page_label": display_label,
    }


class SearchResult:
    def __init__(
        self,
        archive_name: str,
        archive_path: str,
        page_index: int,
        page_filename: str,
        score: float,
        inliers_count: int,
        total_matches: int,
        bounding_box: Optional[Dict[str, int]] = None,
        polygon: Optional[List[List[int]]] = None,
        aspect_ratio_discrepancy: float = 1.0,
    ):
        self.archive_name = archive_name
        self.archive_path = archive_path
        self.page_index = page_index
        self.page_filename = page_filename
        self.score = score
        self.inliers_count = inliers_count
        self.total_matches = total_matches
        self.bounding_box = bounding_box or {"x": 0, "y": 0, "w": 0, "h": 0}
        self.polygon = polygon or []
        self.aspect_ratio_discrepancy = round(float(aspect_ratio_discrepancy), 3)

        vol_meta = parse_manga_volume_and_page(self.archive_name, self.page_filename, self.page_number)
        self.volume_name = vol_meta["volume_name"]
        self.volume_page = vol_meta["volume_page"]
        self.display_page_label = vol_meta["display_page_label"]

    @property
    def page_number(self) -> int:
        return self.page_index + 1

    def to_dict(self) -> Dict[str, Any]:
        return {
            "archive_name": self.archive_name,
            "archive_path": self.archive_path,
            "page_index": self.page_index,
            "page_number": self.page_number,
            "page_filename": self.page_filename,
            "volume_name": self.volume_name,
            "volume_page": self.volume_page,
            "display_page_label": self.display_page_label,
            "score": round(float(self.score), 4),
            "inliers_count": int(self.inliers_count),
            "total_matches": int(self.total_matches),
            "bounding_box": self.bounding_box,
            "polygon": self.polygon,
            "aspect_ratio_discrepancy": self.aspect_ratio_discrepancy,
        }




class MangaSearchEngine:
    """Feature store + ANN index + geometric verification."""

    def __init__(self, max_features: int = 2000, cache_dir: Optional[Path] = None,
                 legacy_cache_dir: Optional[Path] = None):
        self.cache_dir = Path(cache_dir or ".cache_index")
        self.store = FeatureStore(self.cache_dir / "features", legacy_cache_dir=legacy_cache_dir)
        self.ann = AnnIndex(self.cache_dir / "ann", self.store)
        self._ann_loaded = False
        self._index_lock = threading.Lock()
        self.num_workers = max(1, min(8, os.cpu_count() or 4))

    # ---------- index management ----------
    def load_index(self) -> None:
        with self._index_lock:
            if not self._ann_loaded:
                self.ann.load()
                self._ann_loaded = True

    def is_indexed(self, archive: MangaArchive) -> bool:
        return self.store.has(archive)

    def build_features(self, archive: MangaArchive, progress: Optional[Callable[[int, int], None]] = None) -> None:
        self.store.ensure(archive, progress)

    def sync_index(self, archives: List[MangaArchive], progress=None) -> bool:
        """Make the ANN index match the library: add indexed archives, drop removed ones."""
        self.load_index()
        return self.ann.sync(archives, progress)

    def ensure_ready(self, archives: List[MangaArchive], progress: Optional[ProgressFn] = None) -> None:
        """Build missing features and add them to the index (for searching un-indexed archives)."""
        self.load_index()
        todo = [a for a in archives if not self.store.has(a)]
        for i, a in enumerate(todo):
            def p(cur, tot, i=i, a=a):
                if progress:
                    progress(100.0 * (i + cur / max(1, tot)) / len(todo), f"インデックス作成中: {a.name} ({cur}/{tot})")
            self.store.ensure(a, p)
        # Only wait for the index writer when a search target is not in the index yet.
        missing = [a for a in archives if self.store.folder(a).name not in self.ann.blocks]
        if missing:
            if progress:
                progress(100.0, "検索インデックスを更新中…")
            self.ann.add(missing)

    # ---------- query ----------
    def _query_features(self, query_img: np.ndarray, normalize_photo: bool) -> Tuple[np.ndarray, np.ndarray]:
        gray = query_img if query_img.ndim == 2 else cv2.cvtColor(query_img, cv2.COLOR_BGR2GRAY)
        candidates = [normalize_manga_photo(query_img), gray] if normalize_photo else [gray]
        pts = np.zeros((0, 2), np.float32)
        descs = np.zeros((0, 128), np.float32)
        for img in candidates:
            ps, ds = [], []
            for s in QUERY_SCALES:
                p, d = extract_sift(img, QUERY_FEATURES, max_dim=int(QUERY_MAX_DIM * s), scale=s)
                ps.append(p)
                ds.append(d)
            pts, descs = np.concatenate(ps), np.concatenate(ds).astype(np.float32)
            if len(pts) >= MIN_INLIERS:
                break
        return pts, descs

    def _vote(self, qd: np.ndarray, targets: Dict[str, MangaArchive], n_candidates: int, nprobe: int):
        D, blk, row, keys = self.ann.query(qd, ANN_K, nprobe)

        # page index (within its archive) of every hit; -1 when not a search target
        page = np.full(blk.shape, -1, np.int64)
        offsets = {}
        for bi in np.unique(blk[blk >= 0]):
            arc = targets.get(keys[bi])
            if arc is None:
                continue
            offs = self.store.get(arc).offsets
            offsets[int(bi)] = offs
            m = blk == bi
            page[m] = np.searchsorted(offs, row[m], side="right") - 1

        # Lowe-style weight: how clearly a hit beats the k-th neighbour (local background)
        d2 = np.maximum(0.0, 2.0 - 2.0 * D)
        w = np.maximum(0.0, 1.0 - d2 / (0.8 * d2[:, -1:] + 1e-6))
        gid = np.where(page >= 0, blk * 1_000_000 + page, -1)
        m_all = (gid[:, :-1] >= 0) & (w[:, :-1] > 0)
        # each query descriptor votes at most once per page
        for c in range(1, ANN_K - 1):
            m_all[:, c] &= ~np.any(gid[:, :c] == gid[:, c:c + 1], axis=1)
        g = gid[:, :-1][m_all]
        if len(g) == 0:
            return []
        uniq, inv = np.unique(g, return_inverse=True)
        votes = np.bincount(inv, weights=w[:, :-1][m_all])
        b_of, p_of = uniq // 1_000_000, uniq % 1_000_000
        cnt = np.array([offsets[int(b)][p + 1] - offsets[int(b)][p] for b, p in zip(b_of, p_of)], np.float64)
        score = votes / np.sqrt(cnt + 1.0)
        top = np.argsort(-score)[:n_candidates]
        return [(targets[keys[b_of[i]]], int(p_of[i])) for i in top]

    def _verify(self, qk, qd, qshape, archive: MangaArchive, page_idx: int,
                min_panel_area_percent: float) -> Optional["SearchResult"]:
        pk, pd_u8, (pw, ph) = self.store.get(archive).page(page_idx)
        if len(pd_u8) < MIN_INLIERS:
            return None
        pd = to_float(pd_u8)
        sim = qd @ pd.T
        idx = np.argpartition(-sim, 1, axis=1)[:, :2]
        s2 = np.take_along_axis(sim, idx, axis=1)
        order = np.argsort(-s2, axis=1)
        idx = np.take_along_axis(idx, order, axis=1)
        s2 = np.take_along_axis(s2, order, axis=1)
        d1 = np.maximum(0.0, 2.0 - 2.0 * s2[:, 0])
        d2 = np.maximum(0.0, 2.0 - 2.0 * s2[:, 1])
        good = np.where(d1 < 0.64 * d2)[0]          # Lowe ratio test 0.8
        if len(good) < MIN_INLIERS:
            return None
        # one-to-one: keep only the strongest query match for each page keypoint
        by_strength = good[np.argsort(-s2[good, 0])]
        _, first = np.unique(idx[by_strength, 0], return_index=True)
        sel = by_strength[first]
        if len(sel) < MIN_INLIERS:
            return None

        src = qk[sel].astype(np.float32)
        dst = pk[idx[sel, 0]].astype(np.float32)
        thr = float(np.clip(0.005 * max(pw, ph), 4.0, 12.0))
        # Similarity transform (scale + rotation + shift): robust even with few inliers.
        A, mask = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=thr,
                                              maxIters=2000, confidence=0.995)
        if A is None or mask is None:
            return None
        inl = mask.ravel().astype(bool)
        n_inl = int(inl.sum())
        scale = float(np.sqrt(abs(np.linalg.det(A[:2, :2]))))
        if n_inl < MIN_INLIERS or not (0.03 < scale < 30.0):
            return None

        qw, qh = qshape
        corners = np.float32([[0, 0], [qw, 0], [qw, qh], [0, qh]])
        poly = corners @ A[:, :2].T + A[:, 2]
        # A homography refit on the inliers follows perspective (phone photos) when it is sane.
        if n_inl >= 12:
            H, _ = cv2.findHomography(src[inl], dst[inl], cv2.USAC_MAGSAC, thr)
            if H is not None:
                hp = cv2.perspectiveTransform(corners.reshape(-1, 1, 2), H).reshape(-1, 2)
                area_a = cv2.contourArea(poly.astype(np.float32))
                area_h = cv2.contourArea(hp.astype(np.float32))
                if cv2.isContourConvex(hp.astype(np.float32)) and area_a > 0 and 0.6 < area_h / area_a < 1.6:
                    poly = hp

        xs, ys = poly[:, 0], poly[:, 1]
        min_x, max_x = max(0, int(xs.min())), min(pw, int(xs.max()))
        min_y, max_y = max(0, int(ys.min())), min(ph, int(ys.max()))
        bw, bh = max_x - min_x, max_y - min_y
        if bw <= 10 or bh <= 10:
            return None
        area_percent = 100.0 * bw * bh / max(1, pw * ph)
        if area_percent < min_panel_area_percent:
            return None

        ratio = n_inl / len(sel)
        score = min(1.0, 0.65 * min(1.0, n_inl / 40.0) + 0.35 * ratio)
        return SearchResult(
            archive_name=archive.name,
            archive_path=str(archive.path),
            page_index=page_idx,
            page_filename=archive.pages[page_idx],
            score=score,
            inliers_count=n_inl,
            total_matches=len(sel),
            bounding_box={"x": min_x, "y": min_y, "w": bw, "h": bh},
            polygon=poly.astype(int).tolist(),
        )

    def search_query(
        self,
        query_img: np.ndarray,
        archives: List[MangaArchive],
        top_k: int = 5,
        min_score: float = 0.20,
        normalize_photo: bool = True,
        min_panel_area_percent: float = 1.0,
        use_gpu_dml: bool = False,          # kept for API compatibility; no longer used
        enable_coarse_clip: bool = True,    # fast mode: fewer probes and candidates
        progress_callback: Optional[ProgressFn] = None,
    ) -> List["SearchResult"]:
        def progress(pct: float, stage: str) -> None:
            if progress_callback:
                progress_callback(pct, stage)

        archives = list(getattr(archives, "archives", archives))
        if not archives:
            return []
        self.ensure_ready(archives, lambda p, s: progress(p * 0.5, s))

        progress(55, "入力画像の特徴を抽出中…")
        qh, qw = query_img.shape[:2]
        qk, qd = self._query_features(query_img, normalize_photo)
        if len(qk) < MIN_INLIERS:
            return []

        progress(65, "候補ページを絞り込み中…")
        targets = {self.store.folder(a).name: a for a in archives}
        n_cand = 40 if enable_coarse_clip else 100
        nprobe = 8 if enable_coarse_clip else 32
        candidates = self._vote(qd, targets, n_cand, nprobe)

        stage = f"上位 {len(candidates)} ページでコマの位置を照合中…"
        progress(75, stage)
        results: List[SearchResult] = []
        done = [0]
        lock = threading.Lock()

        def work(c):
            arc, p = c
            try:
                r = self._verify(qk, qd, (qw, qh), arc, p, min_panel_area_percent)
            except Exception as e:
                print(f"[MangaSearchEngine] verify {arc.name} p{p}: {e}")
                r = None
            with lock:
                done[0] += 1
                progress(75 + 25 * done[0] / max(1, len(candidates)), stage)
            return r

        with ThreadPoolExecutor(self.num_workers) as ex:
            for r in ex.map(work, candidates):
                if r is not None and r.score >= min_score:
                    results.append(r)

        progress(100, "完了")
        gc.collect()
        results.sort(key=lambda r: (r.score, r.inliers_count), reverse=True)
        # With a confident winner, drop weak look-alikes (typeset text and screentones match
        # many pages a little). Reused panels on other pages score close to the winner and stay.
        if results and results[0].score >= 0.5:
            floor = 0.25 * results[0].inliers_count
            results = [r for r in results if r.inliers_count >= floor]
        return results[:top_k]
