"""
Search Engine for Manga Panel Matching.
Uses SIFT (Scale-Invariant Feature Transform), FLANN / BFMatcher,
and RANSAC Homography for robust geometric verification and bounding box localization.
Optimized with RAM caching, multi-thread parallel matching, and adaptive paper illumination normalization.
"""

import heapq
import os
import pickle
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

import cv2
import numpy as np
import numba

@numba.njit(fastmath=True)
def _compute_page_lowe_score(sim: np.ndarray) -> float:
    M, N = sim.shape
    if N < 2 or M == 0:
        return 0.0

    seen = np.zeros(N, dtype=numba.boolean)
    margin_sum = 0.0

    for i in range(M):
        m1 = -1.0
        m2 = -1.0
        best_j = -1
        for j in range(N):
            val = sim[i, j]
            if val > m1:
                m2 = m1
                m1 = val
                best_j = j
            elif val > m2:
                m2 = val

        d1_sq = 2.0 - 2.0 * m1
        if d1_sq < 0.0:
            d1_sq = 0.0
        d2_sq = 2.0 - 2.0 * m2
        if d2_sq < 0.0:
            d2_sq = 0.0

        if d1_sq < 0.5625 * d2_sq and best_j >= 0:
            seen[best_j] = True
            margin = m1 - m2
            if margin > 0.05 and m1 >= 0.60:
                margin_sum += (margin - 0.05)

    unique_count = 0.0
    for j in range(N):
        if seen[j]:
            unique_count += 1.0

    return unique_count * 5.0 + margin_sum * 1.5

@numba.njit(parallel=True, fastmath=True)
def _batch_compute_lowe_scores(sim_concat: np.ndarray, meta: np.ndarray) -> np.ndarray:
    num_pages = len(meta)
    scores = np.empty(num_pages, dtype=np.float32)
    for p in numba.prange(num_pages):
        start = meta[p, 0]
        sz = meta[p, 1]
        sim_page = sim_concat[:, start : start + sz]
        scores[p] = _compute_page_lowe_score(sim_page)
    return scores

# Module-level JIT warmup
try:
    _ = _batch_compute_lowe_scores(np.zeros((2, 4), dtype=np.float32), np.array([[0, 4]], dtype=np.int32))
except Exception:
    pass

# Enable OpenCL GPU Hardware Acceleration (Intel Arc GPU / AMD / NVIDIA)
try:
    if cv2.ocl.haveOpenCL():
        cv2.ocl.setUseOpenCL(True)
        dev = cv2.ocl.Device.getDefault()
        print(f"[MangaSearchEngine] OpenCL Hardware Acceleration ENABLED: {dev.name()}")
    else:
        print("[MangaSearchEngine] OpenCL not supported, running on optimized CPU multi-threading.")
except Exception as e:
    print(f"[MangaSearchEngine] OpenCL init info: {e}")

import base64

# Embedded 119-byte ONNX MatMul model (A: [M, 128], B: [128, N] -> C: [M, N])
_MATMUL_ONNX_B64 = b"CA0SCW1hbmdhX2RtbDpiChEKAUEKAUISAUMiBk1hdE11bBIIZG1sX2dlbW1aFQoBQRIQCg4IARIKCgMSAU0KAwiAAVoVCgFCEhAKDggBEgoKAwiAAQoDEgFOYhUKAUMSEAoOCAESCgoDEgFNCgMSAU5CBAoAEBE="


class DirectMLGemmRunner:
    """DirectX 12 DirectML hardware acceleration runner for batch matrix multiplication."""

    def __init__(self):
        self._session = None
        self._available = False
        self._init_attempted = False
        self._lock = threading.Lock()

    def is_available(self) -> bool:
        if not self._init_attempted:
            self._initialize()
        return self._available

    def _initialize(self):
        with self._lock:
            if self._init_attempted:
                return
            self._init_attempted = True
            try:
                import onnxruntime as ort
                if "DmlExecutionProvider" not in ort.get_available_providers():
                    print("[DirectML] DmlExecutionProvider not found in ONNX Runtime.")
                    self._available = False
                    return

                model_bytes = base64.b64decode(_MATMUL_ONNX_B64)
                sess_opts = ort.SessionOptions()
                sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
                sess_opts.log_severity_level = 3  # Suppress verbose info logs
                self._session = ort.InferenceSession(
                    model_bytes,
                    sess_options=sess_opts,
                    providers=["DmlExecutionProvider", "CPUExecutionProvider"]
                )
                self._available = True
                print(f"[DirectML] DirectML GPU Acceleration ENABLED: {self._session.get_providers()}")
            except Exception as e:
                print(f"[DirectML] Init info (falling back to CPU BLAS): {e}")
                self._available = False
                self._session = None

    def run_gemm(self, A: np.ndarray, B: np.ndarray) -> np.ndarray:
        """
        Compute A @ B (where A is [M, 128], B is [128, N]).
        Falls back to NumPy CPU BLAS if DirectML is unavailable or fails.
        """
        if not self._init_attempted:
            self._initialize()
        if self._session is not None and self._available:
            try:
                return self._session.run(["C"], {"A": A, "B": B})[0]
            except Exception as e:
                print(f"[DirectML] Run error (fallback to CPU BLAS): {e}")
        return A @ B

    def cleanup(self):
        """Release session and force garbage collection to reclaim GPU shared memory immediately."""
        with self._lock:
            if self._session is not None:
                self._session = None
                self._init_attempted = False
                self._available = False
                import gc
                gc.collect()


dml_runner = DirectMLGemmRunner()

from backend.archive_handler import MangaArchive


def compute_global_descriptor(descs: Optional[np.ndarray]) -> np.ndarray:
    """
    Compute a 128-dimensional normalized global descriptor from SIFT descriptors.
    Uses mean pooling + L2 normalization for fast coarse filtering across thousands of pages in milliseconds.
    """
    if descs is None or len(descs) == 0:
        return np.zeros(128, dtype=np.float32)
    mean_vec = np.mean(descs, axis=0, dtype=np.float32)
    norm = np.linalg.norm(mean_vec)
    if norm > 1e-6:
        return mean_vec / norm
    return mean_vec


from backend.archive_handler import MangaArchive


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
            "score": round(float(self.score), 4),
            "inliers_count": int(self.inliers_count),
            "total_matches": int(self.total_matches),
            "bounding_box": self.bounding_box,
            "polygon": self.polygon,
            "aspect_ratio_discrepancy": self.aspect_ratio_discrepancy,
        }


class MangaSearchEngine:
    """Core matching engine utilizing SIFT, RANSAC, RAM caching, and parallel search."""

    def __init__(self, max_features: int = 2000, cache_dir: Optional[Path] = None):
        self.max_features = max_features
        self.cache_dir = cache_dir or Path(".cache_descriptors")
        self.cache_dir.mkdir(parents=True, exist_ok=True)

        # In-Memory RAM Cache to eliminate repeated disk I/O
        # Stores: (pts, descs, shape, bovw_hist)
        self._memory_cache: Dict[str, Tuple[np.ndarray, Optional[np.ndarray], Tuple[int, int], np.ndarray]] = {}
        self._cache_lock = threading.Lock()

        # Thread pool for parallel matching (matches CPU logical cores)
        self.num_workers = max(1, os.cpu_count() or 4)

        # Initialize SIFT
        self.sift = cv2.SIFT_create(nfeatures=self.max_features)

        # FLANN matcher & GPU-accelerated BruteForce Matcher
        FLANN_INDEX_KDTREE = 1
        index_params = dict(algorithm=FLANN_INDEX_KDTREE, trees=5)
        search_params = dict(checks=50)
        self.flann = cv2.FlannBasedMatcher(index_params, search_params)
        self.bf = cv2.BFMatcher(cv2.NORM_L2)

    def extract_features(self, img: np.ndarray, max_dim: int = 1200) -> Tuple[np.ndarray, np.ndarray]:
        """Extract SIFT keypoints and descriptors with optimal resolution and OpenCL GPU acceleration."""
        if len(img.shape) == 3:
            img_gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        else:
            img_gray = img

        h, w = img_gray.shape
        scale = 1.0
        if max(h, w) > max_dim:
            scale = max_dim / max(h, w)
            new_w, new_h = int(w * scale), int(h * scale)
        else:
            new_w, new_h = w, h

        # GPU acceleration via OpenCL UMat if available
        use_umat = cv2.ocl.useOpenCL()
        kps = None
        descs = None

        if use_umat:
            try:
                u_img = cv2.UMat(img_gray)
                if scale != 1.0:
                    u_img = cv2.resize(u_img, (new_w, new_h), interpolation=cv2.INTER_AREA)
                kps, u_descs = self.sift.detectAndCompute(u_img, None)
                if u_descs is not None:
                    descs = u_descs.get() if hasattr(u_descs, "get") else u_descs
            except Exception:
                use_umat = False

        if not use_umat or kps is None:
            if scale != 1.0:
                img_proc = cv2.resize(img_gray, (new_w, new_h), interpolation=cv2.INTER_AREA)
            else:
                img_proc = img_gray
            kps, descs = self.sift.detectAndCompute(img_proc, None)

        if kps is None or len(kps) == 0:
            return np.empty((0, 2), dtype=np.float32), np.empty((0, 128), dtype=np.float32)

        pts = np.array([kp.pt for kp in kps], dtype=np.float32)
        if scale != 1.0:
            pts /= scale

        return pts, descs

    def get_cache_key(self, archive_name: str, page_index: int) -> str:
        return f"{archive_name}_{page_index}"

    def get_cache_path(self, archive: MangaArchive, page_index: int) -> Path:
        safe_name = "".join(c if c.isalnum() else "_" for c in f"{archive.name}_{page_index}")
        return self.cache_dir / f"{safe_name}.pkl"

    def get_or_compute_page_features(
        self, archive: MangaArchive, page_index: int
    ) -> Tuple[np.ndarray, Optional[np.ndarray], Tuple[int, int]]:
        """Retrieve features from in-memory RAM cache, disk cache, or compute fresh."""
        cache_key = self.get_cache_key(archive.name, page_index)

        # 1. Fast path: In-Memory RAM Cache (nanosecond lookup, zero disk I/O)
        with self._cache_lock:
            if cache_key in self._memory_cache:
                return self._memory_cache[cache_key]

        # 2. Disk Cache
        cache_path = self.get_cache_path(archive, page_index)
        if cache_path.exists():
            try:
                with open(cache_path, "rb") as f:
                    data = pickle.load(f)
                    descs = data.get("descs")
                    if descs is not None and len(descs) > 0:
                        norms = np.linalg.norm(descs, axis=1, keepdims=True) + 1e-7
                        p_norm = (descs / norms).astype(np.float32)
                    else:
                        p_norm = None
                    entry = (data["pts"], p_norm, data["shape"])
                    with self._cache_lock:
                        self._memory_cache[cache_key] = entry
                    return entry
            except Exception:
                pass

        # 3. Compute from scratch
        img_gray = archive.get_page_cv(page_index, grayscale=True)
        h, w = img_gray.shape[:2]
        pts, descs = self.extract_features(img_gray, max_dim=1400)

        # Save to disk
        try:
            with open(cache_path, "wb") as f:
                pickle.dump({
                    "pts": pts,
                    "descs": descs,
                    "shape": (w, h),
                }, f, protocol=pickle.HIGHEST_PROTOCOL)
        except Exception:
            pass

        if descs is not None and len(descs) > 0:
            norms = np.linalg.norm(descs, axis=1, keepdims=True) + 1e-7
            p_norm = (descs / norms).astype(np.float32)
        else:
            p_norm = None

        entry = (pts, p_norm, (w, h))
        with self._cache_lock:
            self._memory_cache[cache_key] = entry
        return entry

    def match_query_to_page(
        self,
        query_pts: np.ndarray,
        query_descs: np.ndarray,
        query_shape: Tuple[int, int],
        page_pts: np.ndarray,
        page_descs: Optional[np.ndarray],
        page_shape: Tuple[int, int],
        ratio_threshold: float = 0.75,
        min_panel_area_percent: float = 2.0,
    ) -> Optional[SearchResult]:
        """
        Precise SIFT + RANSAC geometric verification.
        Includes Spatial Dispersion check and minimum panel size filtering.
        """
        if query_descs is None or page_descs is None:
            return None
        if len(query_descs) < 8 or len(page_descs) < 8:
            return None

        # KNN Match
        try:
            matches = self.flann.knnMatch(query_descs, page_descs, k=2)
        except Exception:
            matches = self.bf.knnMatch(query_descs, page_descs, k=2)

        good_matches = []
        for m_pair in matches:
            if len(m_pair) == 2:
                m, n = m_pair
                if m.distance < ratio_threshold * n.distance:
                    good_matches.append(m)

        # Early Rejection 1: Ratio count threshold
        if len(good_matches) < 8:
            return None

        # Approach A Early Rejection 2: Spatial dispersion on query side
        # True panels have features spread across the query; a single tiny dot cluster is noise.
        matched_q_pts = np.float32([query_pts[m.queryIdx] for m in good_matches])
        q_min_x, q_max_x = np.min(matched_q_pts[:, 0]), np.max(matched_q_pts[:, 0])
        q_min_y, q_max_y = np.min(matched_q_pts[:, 1]), np.max(matched_q_pts[:, 1])
        q_span_w = q_max_x - q_min_x
        q_span_h = q_max_y - q_min_y
        qw, qh = query_shape

        # If matching points are concentrated in a microscopic spot (< 5% span), reject before RANSAC
        if q_span_w < qw * 0.05 or q_span_h < qh * 0.05:
            return None

        src_pts = matched_q_pts.reshape(-1, 1, 2)
        dst_pts = np.float32([page_pts[m.trainIdx] for m in good_matches]).reshape(-1, 1, 2)

        H, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 6.0)
        if H is None or mask is None:
            return None

        inliers = mask.ravel().tolist()
        inliers_count = int(sum(inliers))

        if inliers_count < 8:
            return None

        pw, ph = page_shape
        query_corners = np.float32([[0, 0], [qw, 0], [qw, qh], [0, qh]]).reshape(-1, 1, 2)

        try:
            projected_corners = cv2.perspectiveTransform(query_corners, H)
        except Exception:
            return None

        poly = projected_corners.reshape(-1, 2).astype(int).tolist()
        xs = [p[0] for p in poly]
        ys = [p[1] for p in poly]
        min_x = max(0, min(xs))
        max_x = min(pw, max(xs))
        min_y = max(0, min(ys))
        max_y = min(ph, max(ys))

        bbox_w = max_x - min_x
        bbox_h = max_y - min_y

        if bbox_w <= 10 or bbox_h <= 10:
            return None

        # Slider 2 Check: Minimum Panel Size Ratio
        page_area = max(1, pw * ph)
        bbox_area = bbox_w * bbox_h
        area_percent = (bbox_area / page_area) * 100.0

        if area_percent < min_panel_area_percent:
            # Drop matches that are smaller than the user-configured minimum panel size threshold
            return None

        # Robust score computation rewarding both inlier count, inlier ratio, and healthy panel area
        inlier_ratio = inliers_count / len(good_matches)
        size_factor = min(1.0, area_percent / 4.0)
        score = min(1.0, (inliers_count / 30.0) * 0.55 + inlier_ratio * 0.30 + size_factor * 0.15)

        return SearchResult(
            archive_name="",
            archive_path="",
            page_index=0,
            page_filename="",
            score=score,
            inliers_count=inliers_count,
            total_matches=len(good_matches),
            bounding_box={"x": int(min_x), "y": int(min_y), "w": int(bbox_w), "h": int(bbox_h)},
            polygon=poly,
        )

    def search_query(
        self,
        query_img: np.ndarray,
        archives: List[MangaArchive],
        top_k: int = 5,
        min_score: float = 0.20,
        normalize_photo: bool = True,
        min_panel_area_percent: float = 1.0,  # Slider: Minimum panel size % of page area
        use_gpu_dml: bool = False,
        enable_coarse_clip: bool = True,  # Stride-2 spatial subsampling in Stage 1 for fast coarse ranking
        progress_callback: Optional[Callable[[int, int], None]] = None,
    ) -> List[SearchResult]:
        """
        High-Performance Manga Panel Search Engine with BLAS / DirectML GEMM & Real-Time Progress:
        - Exhaustive coverage across all registered pages with high selectivity.
        - Hardware Acceleration: Intel oneMKL CPU BLAS or DirectX 12 DirectML (Intel Arc GPU XMX).
        - 2-Stage Coarse-to-Fine Architecture with Lowe's Distinctive / Margin Scoring + Fine RANSAC.
        - Stride-2 Spatial Subsampling in Stage 1: Halves FLOPs while preserving multi-scale feature coverage.
        - Fine-grained progress callback with smooth frontend animation interpolation.
        """
        qh, qw = query_img.shape[:2]

        # 1. Feature extraction with optimal resolution (Approach A)
        if normalize_photo:
            processed_img = normalize_manga_photo(query_img)
            query_pts, query_descs = self.extract_features(processed_img, max_dim=1000)
            if len(query_pts) < 8 or query_descs is None:
                query_pts, query_descs = self.extract_features(query_img, max_dim=1000)
        else:
            query_pts, query_descs = self.extract_features(query_img, max_dim=1000)

        if len(query_pts) < 8 or query_descs is None:
            return []

        # L2-normalize query descriptors for fast BLAS cosine distance
        q_norms = np.linalg.norm(query_descs, axis=1, keepdims=True) + 1e-7
        q_norm = (query_descs / q_norms).astype(np.float32)

        # In fast mode, subsample query features evenly to 500 points to slash GEMM FLOPs
        # while keeping 100% full page descriptors intact for maximum selectivity
        if enable_coarse_clip and len(q_norm) > 500:
            step = len(q_norm) / 500.0
            q_indices = [int(i * step) for i in range(500)]
            qn_stage1 = q_norm[q_indices]
        else:
            qn_stage1 = q_norm

        # 2. Build list of all targets across archives (100% exhaustive coverage)
        if hasattr(archives, "archives"):
            target_archives = archives.archives
        else:
            target_archives = archives

        all_targets: List[Tuple[MangaArchive, int]] = []
        for archive in target_archives:
            for page_idx in range(len(archive)):
                all_targets.append((archive, page_idx))

        total_targets = len(all_targets)
        if total_targets == 0:
            return []

        if progress_callback:
            progress_callback(0, total_targets)

        # 3. Two-Stage Coarse-to-Fine Search Architecture
        # Stage 1: Ultra-fast GEMM + Parallel Numba JIT Lowe's Scoring (0% - 90% progress)
        # Stage 2: Fine Geometric Verification (RANSAC) on top candidates only (90% - 100% progress)
        TOP_K_CANDIDATES = 100
        candidate_heap: List[Tuple[float, int]] = []  # Min-heap of (coarse_score, target_idx)
        batch_size = 250
        processed_count = 0

        for b_start in range(0, total_targets, batch_size):
            batch_items = all_targets[b_start : b_start + batch_size]
            batch_descs = []
            batch_meta = []
            target_indices = []
            start_col = 0

            for offset, (arc, page_idx) in enumerate(batch_items):
                target_idx = b_start + offset
                try:
                    pts, p_norm, (pw, ph) = self.get_or_compute_page_features(arc, page_idx)
                    if p_norm is not None and len(pts) >= 8:
                        batch_descs.append(p_norm)
                        sz = len(p_norm)
                        batch_meta.append([start_col, sz])
                        target_indices.append(target_idx)
                        start_col += sz
                except Exception:
                    pass

                # Emit fine-grained progress during feature reading (every 50 pages)
                if progress_callback and (offset + 1) % 50 == 0:
                    curr = b_start + offset + 1
                    progress_callback(int(curr * 0.90), total_targets)

            if batch_descs:
                # Batch matrix multiplication across all pages in batch
                P_concat = np.vstack(batch_descs)
                if use_gpu_dml and dml_runner.is_available():
                    sim_concat = dml_runner.run_gemm(qn_stage1, P_concat.T)
                else:
                    sim_concat = qn_stage1 @ P_concat.T

                # Ultra-fast parallel Numba JIT Lowe's Distinctive / Margin scoring
                # Eliminates background noise accumulation with zero Python overhead
                meta_arr = np.array(batch_meta, dtype=np.int32)
                batch_scores = _batch_compute_lowe_scores(sim_concat, meta_arr)

                for score_val, curr_idx in zip(batch_scores, target_indices):
                    sc = float(score_val)
                    if len(candidate_heap) < TOP_K_CANDIDATES:
                        heapq.heappush(candidate_heap, (sc, curr_idx))
                    else:
                        if sc > candidate_heap[0][0]:
                            heapq.heapreplace(candidate_heap, (sc, curr_idx))

            processed_count += len(batch_items)
            if progress_callback:
                # Scale Stage 1 to 0% - 90%
                progress_callback(int(processed_count * 0.90), total_targets)

        # Stage 2: Fine Geometric Verification (RANSAC & Homography) on Top Candidates
        sorted_candidates = sorted(candidate_heap, key=lambda x: x[0], reverse=True)
        results: List[SearchResult] = []
        num_candidates = len(sorted_candidates)

        for c_idx, (score_val, target_idx) in enumerate(sorted_candidates):
            arc, page_idx = all_targets[target_idx]
            try:
                pts, p_norm, (pw, ph) = self.get_or_compute_page_features(arc, page_idx)
                if p_norm is None or len(pts) < 8:
                    continue

                sim = q_norm @ p_norm.T

                # Exact Top-2 partition for Lowe's ratio test
                idx = np.argpartition(sim, -2, axis=1)[:, -2:]
                val1 = np.take_along_axis(sim, idx[:, 1:], axis=1).squeeze(1)
                val2 = np.take_along_axis(sim, idx[:, :1], axis=1).squeeze(1)

                d1_sq = np.maximum(0.0, 2.0 - 2.0 * val1)
                d2_sq = np.maximum(0.0, 2.0 - 2.0 * val2)
                ratio_mask = d1_sq < 0.5625 * d2_sq  # 0.75^2
                good_count = int(np.count_nonzero(ratio_mask))
                if good_count < 8:
                    continue

                matched_q_indices = np.where(ratio_mask)[0]
                matched_p_indices = idx[:, 1:][ratio_mask].ravel()

                # Bijective 1-to-1 constraint: for each target feature, keep only the single best query match
                # Eliminates tone/hatch false positive clusters where hundreds of query points collapse into a few target points.
                best_match_for_target = {}
                for q_i, t_i in zip(matched_q_indices, matched_p_indices):
                    s_val = sim[q_i, t_i]
                    if t_i not in best_match_for_target or s_val > best_match_for_target[t_i][0]:
                        best_match_for_target[t_i] = (s_val, q_i)

                if len(best_match_for_target) < 8:
                    continue

                unique_t_indices = list(best_match_for_target.keys())
                unique_q_indices = [best_match_for_target[t][1] for t in unique_t_indices]

                matched_q_pts = query_pts[unique_q_indices]
                matched_p_pts = pts[unique_t_indices]

                # Spatial dispersion check on query side
                q_min_x, q_max_x = np.min(matched_q_pts[:, 0]), np.max(matched_q_pts[:, 0])
                q_min_y, q_max_y = np.min(matched_q_pts[:, 1]), np.max(matched_q_pts[:, 1])
                if (q_max_x - q_min_x < qw * 0.05) or (q_max_y - q_min_y < qh * 0.05):
                    continue

                src_pts = matched_q_pts.reshape(-1, 1, 2)
                dst_pts = matched_p_pts.reshape(-1, 1, 2)
                H, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 6.0)
                if H is None or mask is None:
                    continue

                inliers_count = int(np.sum(mask.ravel().astype(np.int32)))
                if inliers_count < 8:
                    continue

                # Project query corners
                query_corners = np.float32([[0, 0], [qw, 0], [qw, qh], [0, qh]]).reshape(-1, 1, 2)
                try:
                    projected_corners = cv2.perspectiveTransform(query_corners, H)
                except Exception:
                    continue

                poly_pts = projected_corners.reshape(-1, 2)
                poly = poly_pts.astype(int).tolist()
                xs = [p[0] for p in poly]
                ys = [p[1] for p in poly]
                min_x = max(0, min(xs))
                max_x = min(pw, max(xs))
                min_y = max(0, min(ys))
                max_y = min(ph, max(ys))

                bbox_w = max_x - min_x
                bbox_h = max_y - min_y
                if bbox_w <= 10 or bbox_h <= 10:
                    continue

                # Slider Check: Minimum Panel Size Ratio
                page_area = max(1, pw * ph)
                bbox_area = bbox_w * bbox_h
                area_percent = (bbox_area / page_area) * 100.0
                if area_percent < min_panel_area_percent:
                    continue

                # Robust score computation rewarding inlier count, inlier ratio, and panel area
                inlier_ratio = inliers_count / max(1, good_count)
                size_factor = min(1.0, area_percent / 4.0)
                score = min(1.0, (inliers_count / 30.0) * 0.55 + inlier_ratio * 0.30 + size_factor * 0.15)

                if score >= min_score:
                    results.append(SearchResult(
                        archive_name=arc.name,
                        archive_path=str(arc.path),
                        page_index=page_idx,
                        page_filename=arc.pages[page_idx],
                        score=score,
                        inliers_count=inliers_count,
                        total_matches=good_count,
                        bounding_box={"x": int(min_x), "y": int(min_y), "w": int(bbox_w), "h": int(bbox_h)},
                        polygon=poly,
                    ))
            except Exception:
                pass

            if progress_callback and num_candidates > 0:
                stage2_progress = int(total_targets * 0.90 + ((c_idx + 1) / num_candidates) * (total_targets * 0.10))
                progress_callback(min(total_targets, stage2_progress), total_targets)

        if progress_callback:
            progress_callback(total_targets, total_targets)

        # Release GPU shared memory immediately if DirectML was mobilized
        if use_gpu_dml:
            dml_runner.cleanup()

        # Sort descending by score & inliers
        results.sort(key=lambda r: (r.score, r.inliers_count), reverse=True)
        return results[:top_k]
