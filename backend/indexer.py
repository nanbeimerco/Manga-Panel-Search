"""
Library and Index Manager for Manga Archives.
Handles scanning multiple directories and individual files,
multi-threaded background feature caching, and progress reporting.
"""

import json
import os
import pickle
import queue
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Dict, List, Optional

from backend.archive_handler import MangaArchive, scan_multiple_targets
from backend.search_engine import MangaSearchEngine


class LibraryManager:
    """Manages multiple registered manga folders/files and handles background indexing."""

    def __init__(self, data_dir: Path, engine: MangaSearchEngine, config_file: Optional[Path] = None):
        self.default_data_dir = data_dir.resolve()
        self.data_dir = self.default_data_dir
        self.default_data_dir.mkdir(parents=True, exist_ok=True)
        self.config_file = config_file or (self.default_data_dir.parent / "config_paths.json")
        self.engine = engine

        self.target_paths: List[str] = self._load_target_paths()
        self.archives: List[MangaArchive] = []
        self._archive_lookup: Dict[str, MangaArchive] = {}

        # Indexing status
        self.is_indexing = False
        self.index_progress = 0.0
        self.total_pages = 0
        self.indexed_pages = 0
        self.current_file = ""
        self.last_error = ""

        self._lock = threading.Lock()
        self.num_workers = max(1, os.cpu_count() or 4)

        self.refresh_library()

    def _load_target_paths(self) -> List[str]:
        if self.config_file.exists():
            try:
                data = json.loads(self.config_file.read_text(encoding="utf-8"))
                if isinstance(data, list) and data:
                    return [str(Path(p).resolve()) for p in data if Path(p).exists()]
            except Exception:
                pass
        return [str(self.default_data_dir)]

    def _save_target_paths(self) -> None:
        try:
            self.config_file.write_text(json.dumps(self.target_paths, indent=2, ensure_ascii=False), encoding="utf-8")
        except Exception as e:
            print(f"Failed to save target paths: {e}")

    def add_target_path(self, path_str: str) -> bool:
        """Add a directory or single archive file path."""
        p = Path(path_str).resolve()
        if not p.exists():
            return False
        with self._lock:
            resolved_str = str(p)
            if resolved_str not in self.target_paths:
                self.target_paths.append(resolved_str)
                self._save_target_paths()
        self.refresh_library()
        return True

    def remove_target_path(self, path_str: str) -> bool:
        """Remove a path from registered targets."""
        with self._lock:
            resolved_str = str(Path(path_str).resolve())
            if resolved_str in self.target_paths:
                self.target_paths.remove(resolved_str)
                # Keep at least default data dir if empty
                if not self.target_paths:
                    self.target_paths = [str(self.default_data_dir)]
                self._save_target_paths()
        self.refresh_library()
        return True

    def get_target_paths(self) -> List[Dict[str, Any]]:
        with self._lock:
            result = []
            for p_str in self.target_paths:
                p = Path(p_str)
                result.append({
                    "path": p_str,
                    "name": p.name,
                    "is_dir": p.is_dir(),
                    "exists": p.exists(),
                })
            return result

    def refresh_library(self) -> None:
        """Scan all registered target paths (directories and individual files)."""
        with self._lock:
            found = scan_multiple_targets(self.target_paths)
            self.archives = found
            self._archive_lookup = {a.name: a for a in found}
            self.total_pages = sum(len(a) for a in self.archives)

    def get_archive(self, archive_name: str) -> Optional[MangaArchive]:
        with self._lock:
            return self._archive_lookup.get(archive_name)

    def get_status(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "is_indexing": self.is_indexing,
                "progress_percent": round(self.index_progress, 1),
                "total_archives": len(self.archives),
                "total_pages": self.total_pages,
                "indexed_pages": self.indexed_pages,
                "current_file": self.current_file,
                "target_paths_count": len(self.target_paths),
                "last_error": self.last_error,
            }

    def get_library_summary(self) -> List[Dict[str, Any]]:
        with self._lock:
            summary = []
            for arc in self.archives:
                cached_count = 0
                for idx in range(len(arc)):
                    cache_p = self.engine.get_cache_path(arc, idx)
                    if cache_p.exists():
                        cached_count += 1

                summary.append(
                    {
                        "name": arc.name,
                        "path": str(arc.path),
                        "type": arc.archive_type,
                        "page_count": len(arc),
                        "cached_pages": cached_count,
                        "is_fully_indexed": cached_count == len(arc),
                    }
                )
            return summary

    def start_indexing(self) -> bool:
        """Trigger background indexing of all unindexed pages across all target paths."""
        if self.is_indexing:
            return False

        thread = threading.Thread(target=self._indexing_worker, daemon=True)
        thread.start()
        return True

    def _indexing_worker(self) -> None:
        """
        High-throughput asynchronous indexing pipeline:
        1. Fast Skip: Instantly detects already cached pages on disk (0ms skip).
        2. I/O Prefetching (Producer): Asynchronously decompresses and loads images into memory buffer.
        3. GPU Acceleration (Consumer Workers): Extracts SIFT features via Intel Arc GPU OpenCL in parallel.
        """
        self.is_indexing = True
        self.last_error = ""
        self.refresh_library()

        try:
            total = self.total_pages
            if total == 0:
                with self._lock:
                    self.index_progress = 100.0
                    self.current_file = "Complete"
                return

            # 1. Fast Cache Pre-check: Partition into cached (0ms) and uncached tasks
            cached_count = 0
            uncached_tasks: List[Tuple[MangaArchive, int]] = []

            for arc in list(self.archives):
                if not arc.path.exists():
                    continue
                for page_idx in range(len(arc)):
                    cache_path = self.engine.get_cache_path(arc, page_idx)
                    if cache_path.exists():
                        cached_count += 1
                    else:
                        uncached_tasks.append((arc, page_idx))

            with self._lock:
                self.indexed_pages = cached_count
                self.index_progress = (cached_count / total) * 100.0 if total > 0 else 100.0
                self.current_file = f"キャッシュ確認中... ({cached_count}/{total})"

            if not uncached_tasks:
                with self._lock:
                    self.indexed_pages = total
                    self.index_progress = 100.0
                    self.current_file = "Complete"
                return

            # 2. Producer-Consumer Pipeline Setup
            num_workers = max(1, min(self.num_workers, 8))
            # Buffer queue: holds up to 32 pre-decoded grayscale images
            image_queue: queue.Queue = queue.Queue(maxsize=32)
            processed = cached_count

            # Producer: Asynchronous I/O Reader Thread
            def io_reader():
                try:
                    for arc, page_idx in uncached_tasks:
                        try:
                            img_gray = arc.get_page_cv(page_idx, grayscale=True)
                            image_queue.put((arc, page_idx, img_gray))
                        except Exception as read_err:
                            image_queue.put((arc, page_idx, None))
                finally:
                    # Send sentinel poison pills to stop consumer workers
                    for _ in range(num_workers):
                        image_queue.put(None)

            reader_thread = threading.Thread(target=io_reader, daemon=True)
            reader_thread.start()

            # Consumer: GPU SIFT Feature Extraction Workers
            def gpu_consumer_worker():
                nonlocal processed
                while True:
                    item = image_queue.get()
                    if item is None:
                        image_queue.task_done()
                        break

                    arc, page_idx, img_gray = item
                    try:
                        if img_gray is not None and len(img_gray.shape) >= 2:
                            h, w = img_gray.shape[:2]
                            pts, descs = self.engine.extract_features(img_gray, max_dim=1400)
                            cache_path = self.engine.get_cache_path(arc, page_idx)

                            with open(cache_path, "wb") as f:
                                pickle.dump({
                                    "pts": pts,
                                    "descs": descs,
                                    "shape": (w, h),
                                }, f, protocol=pickle.HIGHEST_PROTOCOL)

                            # Populate in-memory RAM cache
                            cache_key = self.engine.get_cache_key(arc.name, page_idx)
                            with self.engine._cache_lock:
                                self.engine._memory_cache[cache_key] = (pts, descs, (w, h))

                    except Exception as err:
                        self.last_error = f"Error indexing {arc.name} p.{page_idx}: {err}"
                    finally:
                        image_queue.task_done()
                        with self._lock:
                            processed += 1
                            self.indexed_pages = processed
                            self.index_progress = (processed / total) * 100.0
                            self.current_file = arc.name

            # Run parallel GPU consumer workers
            consumer_threads = []
            for _ in range(num_workers):
                t = threading.Thread(target=gpu_consumer_worker, daemon=True)
                t.start()
                consumer_threads.append(t)

            # Wait for all consumers to finish
            for t in consumer_threads:
                t.join()
            reader_thread.join()

            with self._lock:
                self.indexed_pages = total
                self.index_progress = 100.0
                self.current_file = "Complete"

        except Exception as e:
            self.last_error = str(e)
        finally:
            self.is_indexing = False
            self.refresh_library()
