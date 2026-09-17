"""
Archive and Image Loader for Manga Panel Search.
Supports: cbz, cbr, cbt, cb7, zip, rar, tar, 7z, jpg, jpeg, png, webp.
"""

import io
import os
import re
import tarfile
import zipfile
from pathlib import Path
from typing import List, Optional, Tuple, Union

import cv2
import numpy as np
from PIL import Image

# Optional dependencies with graceful fallback
try:
    import py7zr
    HAS_7Z = True
except ImportError:
    HAS_7Z = False

try:
    import rarfile
    HAS_RAR = True
    import shutil
    candidate_tools = [
        shutil.which("unrar"),
        shutil.which("7z"),
        r"C:\Program Files\7-Zip\7z.exe",
        r"C:\Program Files (x86)\7-Zip\7z.exe",
        r"C:\Program Files\WinRAR\UnRAR.exe",
        r"C:\Program Files\WinRAR\WinRAR.exe",
    ]
    for c in candidate_tools:
        if c and Path(c).exists():
            rarfile.UNRAR_TOOL = str(c)
            break
except ImportError:
    HAS_RAR = False

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
ARCHIVE_EXTENSIONS = {
    ".cbz": "zip",
    ".zip": "zip",
    ".cbt": "tar",
    ".tar": "tar",
    ".cb7": "7z",
    ".7z": "7z",
    ".cbr": "rar",
    ".rar": "rar",
}


def natural_sort_key(s: str):
    """Sort strings with embedded numbers naturally (e.g. page_2 before page_10)."""
    return [int(text) if text.isdigit() else text.lower() for text in re.split(r"(\d+)", s)]


class MangaArchive:
    """Represents a single manga volume, archive, or folder of images."""

    def __init__(self, file_path: Union[str, Path]):
        self.path = Path(file_path).resolve()
        self.name = self.path.name
        self.is_dir = self.path.is_dir()
        self.archive_type = self._detect_type()
        self._page_list: Optional[List[str]] = None

    def _detect_type(self) -> str:
        if self.is_dir:
            return "folder"
        ext = self.path.suffix.lower()
        if ext in ARCHIVE_EXTENSIONS:
            return ARCHIVE_EXTENSIONS[ext]
        if ext in IMAGE_EXTENSIONS:
            return "image"
        return "unknown"

    @property
    def pages(self) -> List[str]:
        """Returns sorted list of internal page paths or image filenames."""
        if self._page_list is not None:
            return self._page_list

        entries = []
        if self.archive_type == "folder":
            for f in self.path.iterdir():
                if f.is_file() and f.suffix.lower() in IMAGE_EXTENSIONS:
                    entries.append(f.name)
            entries.sort(key=natural_sort_key)

        elif self.archive_type == "image":
            entries = [self.path.name]

        elif self.archive_type == "zip":
            with zipfile.ZipFile(self.path, "r") as zf:
                for name in zf.namelist():
                    if Path(name).suffix.lower() in IMAGE_EXTENSIONS and not name.startswith("__MACOSX"):
                        entries.append(name)
            entries.sort(key=natural_sort_key)

        elif self.archive_type == "tar":
            with tarfile.open(self.path, "r:*") as tf:
                for member in tf.getmembers():
                    if member.isfile() and Path(member.name).suffix.lower() in IMAGE_EXTENSIONS:
                        entries.append(member.name)
            entries.sort(key=natural_sort_key)

        elif self.archive_type == "7z":
            if not HAS_7Z:
                raise RuntimeError("py7zr is required to read 7z/cb7 archives.")
            with py7zr.SevenZipFile(self.path, "r") as sz:
                for name in sz.getnames():
                    if Path(name).suffix.lower() in IMAGE_EXTENSIONS:
                        entries.append(name)
            entries.sort(key=natural_sort_key)

        elif self.archive_type == "rar":
            if not HAS_RAR:
                raise RuntimeError("rarfile is required to read rar/cbr archives.")
            with rarfile.RarFile(self.path, "r") as rf:
                for name in rf.namelist():
                    if Path(name).suffix.lower() in IMAGE_EXTENSIONS:
                        entries.append(name)
            entries.sort(key=natural_sort_key)

        self._page_list = entries
        return self._page_list

    def __len__(self) -> int:
        return len(self.pages)

    def get_page_bytes(self, page_index: int) -> bytes:
        """Extract page image as raw bytes given 0-indexed page number."""
        if page_index < 0 or page_index >= len(self.pages):
            raise IndexError(f"Page index {page_index} out of range (0-{len(self.pages)-1})")

        page_name = self.pages[page_index]

        if self.archive_type == "folder":
            target = self.path / page_name
            return target.read_bytes()

        elif self.archive_type == "image":
            return self.path.read_bytes()

        elif self.archive_type == "zip":
            with zipfile.ZipFile(self.path, "r") as zf:
                return zf.read(page_name)

        elif self.archive_type == "tar":
            with tarfile.open(self.path, "r:*") as tf:
                f = tf.extractfile(page_name)
                if f is None:
                    raise IOError(f"Could not extract {page_name} from {self.name}")
                return f.read()

        elif self.archive_type == "7z":
            if not HAS_7Z:
                raise RuntimeError("py7zr is not installed.")
            import tempfile
            with tempfile.TemporaryDirectory() as td:
                with py7zr.SevenZipFile(self.path, "r") as sz:
                    sz.extract(path=td, targets=[page_name])
                target_p = Path(td) / page_name
                if not target_p.exists():
                    raise IOError(f"Could not extract {page_name} from {self.name}")
                return target_p.read_bytes()

        elif self.archive_type == "rar":
            if not HAS_RAR:
                raise RuntimeError("rarfile is not installed.")
            with rarfile.RarFile(self.path, "r") as rf:
                return rf.read(page_name)

        raise ValueError(f"Unsupported archive type {self.archive_type}")

    def get_page_image(self, page_index: int) -> Image.Image:
        """Extract page as PIL Image."""
        b = self.get_page_bytes(page_index)
        return Image.open(io.BytesIO(b)).convert("RGB")

    def get_page_cv(self, page_index: int, grayscale: bool = True) -> np.ndarray:
        """Extract page as OpenCV image (numpy array). Grayscale by default for matching."""
        b = self.get_page_bytes(page_index)
        nparr = np.frombuffer(b, np.uint8)
        flag = cv2.IMREAD_GRAYSCALE if grayscale else cv2.IMREAD_COLOR
        img = cv2.imdecode(nparr, flag)
        if img is None:
            raise ValueError(f"Failed to decode image for page {page_index} in {self.name}")
        return img


def scan_manga_directory(directory_path: Union[str, Path]) -> List[MangaArchive]:
    """Scan a directory recursively for supported manga archives and standalone folders."""
    p = Path(directory_path).resolve()
    if not p.exists() or not p.is_dir():
        return []

    results: List[MangaArchive] = []
    supported_exts = set(ARCHIVE_EXTENSIONS.keys()) | IMAGE_EXTENSIONS

    # Find archives and standalone images
    for root, dirs, files in os.walk(p):
        root_path = Path(root)

        # Check files
        for f in files:
            ext = Path(f).suffix.lower()
            if ext in supported_exts:
                file_full = root_path / f
                try:
                    archive = MangaArchive(file_full)
                    if len(archive) > 0:
                        results.append(archive)
                except Exception as e:
                    print(f"[Scan Warning] Could not parse {file_full.name}: {e}")

        # Check if directory itself is a manga folder (contains images directly)
        images_in_dir = [f for f in files if Path(f).suffix.lower() in IMAGE_EXTENSIONS]
        if images_in_dir and root_path != p:
            # If all/most files are images, treat the directory as a volume
            try:
                folder_archive = MangaArchive(root_path)
                if len(folder_archive) > 0 and not any(a.path == root_path for a in results):
                    results.append(folder_archive)
            except Exception:
                pass

    return results


def scan_multiple_targets(targets: List[Union[str, Path]]) -> List[MangaArchive]:
    """
    Scan multiple targets (can be directories, archives, or single image files).
    Deduplicates by resolved path.
    """
    all_archives: List[MangaArchive] = []
    seen_paths = set()
    supported_exts = set(ARCHIVE_EXTENSIONS.keys()) | IMAGE_EXTENSIONS

    for target in targets:
        p = Path(target).resolve()
        if not p.exists():
            continue

        if p.is_dir():
            # Scan directory recursively
            found = scan_manga_directory(p)
            for arc in found:
                if arc.path not in seen_paths:
                    seen_paths.add(arc.path)
                    all_archives.append(arc)
        elif p.is_file() and p.suffix.lower() in supported_exts:
            # Single archive or image file
            if p not in seen_paths:
                try:
                    arc = MangaArchive(p)
                    if len(arc) > 0:
                        seen_paths.add(p)
                        all_archives.append(arc)
                except Exception as e:
                    print(f"[Scan Warning] Could not parse single file {p.name}: {e}")

    return all_archives
