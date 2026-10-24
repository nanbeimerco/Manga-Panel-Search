"""
Library and Index Manager for Manga Archives.
Handles scanning multiple directories and individual files,
multi-threaded background feature caching, and progress reporting.
"""

import json
import os
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from backend.archive_handler import MangaArchive, scan_multiple_targets
from backend.search_engine import MangaSearchEngine


class LibraryManager:
    """Manages multiple registered manga folders/files and handles background indexing."""

    def __init__(self, data_dir: Path, engine: MangaSearchEngine, config_file: Optional[Path] = None,
                 scan_now: bool = True):
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
        self.is_scanning = False

        self._lock = threading.Lock()
        if scan_now:
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
        self.is_scanning = True
        try:
            with self._lock:
                paths = list(self.target_paths)
            found = scan_multiple_targets(paths)
            with self._lock:
                self.archives = found
                self._archive_lookup = {a.name: a for a in found}
                self.total_pages = sum(len(a) for a in self.archives)
        finally:
            self.is_scanning = False

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
                "is_scanning": self.is_scanning,
            }

    def get_library_summary(self) -> List[Dict[str, Any]]:
        with self._lock:
            summary = []
            for arc in self.archives:
                cached_count = len(arc) if self.engine.is_indexed(arc) else 0
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
        """Build missing features and update the search index in the background."""
        with self._lock:
            if self.is_indexing:
                return False
            self.is_indexing = True
        threading.Thread(target=self._indexing_worker, daemon=True).start()
        return True

    def _indexing_worker(self) -> None:
        self.last_error = ""
        try:
            self.refresh_library()
            archives = [a for a in list(self.archives) if a.path.exists()]
            total = sum(len(a) for a in archives)
            done = sum(len(a) for a in archives if self.engine.is_indexed(a))
            with self._lock:
                self.indexed_pages = done
                self.index_progress = 100.0 * done / total if total else 100.0
                self.current_file = "準備中"

            for arc in archives:
                if self.engine.is_indexed(arc):
                    continue
                base = done

                def progress(cur, n, arc=arc, base=base):
                    with self._lock:
                        self.indexed_pages = base + cur
                        self.index_progress = 100.0 * (base + cur) / max(1, total)
                        self.current_file = arc.name
                try:
                    self.engine.build_features(arc, progress)
                except Exception as e:
                    self.last_error = f"{arc.name}: {e}"
                    print(f"[Indexer] {self.last_error}")
                done = base + len(arc)

            with self._lock:
                self.current_file = "検索インデックスを更新中"
            self.engine.sync_index(archives)
            with self._lock:
                self.indexed_pages = total
                self.index_progress = 100.0
                self.current_file = "Complete"
        except Exception as e:
            self.last_error = str(e)
            print(f"[Indexer] failed: {e}")
        finally:
            self.is_indexing = False
