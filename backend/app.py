"""
FastAPI Server for Manga Panel Search Application.
Provides REST APIs for searching panels, indexing volumes, and serving page images.
"""

import io
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from backend.indexer import LibraryManager
from backend.search_engine import MangaSearchEngine

app = FastAPI(title="Manga Panel Search API", version="1.0.0")

# Allow CORS for potential Android or external web clients
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
import os
import shutil
import sys

# Robust path resolution for dev environment and PyInstaller bundles
if getattr(sys, "frozen", False):
    BASE_DIR = Path(sys.executable).resolve().parent
    # Check _internal/static first (PyInstaller --onedir mode)
    if (BASE_DIR / "_internal" / "static").exists():
        STATIC_DIR = BASE_DIR / "_internal" / "static"
    elif hasattr(sys, "_MEIPASS") and (Path(sys._MEIPASS) / "static").exists():
        STATIC_DIR = Path(sys._MEIPASS) / "static"
    elif (BASE_DIR / "static").exists():
        STATIC_DIR = BASE_DIR / "static"
    else:
        STATIC_DIR = Path.cwd() / "static"
else:
    BASE_DIR = Path(__file__).resolve().parent.parent
    STATIC_DIR = BASE_DIR / "static"

def get_user_data_dir() -> Path:
    """Return persistent user data directory in APPDATA (Windows) or home dir."""
    if sys.platform == "win32" and "APPDATA" in os.environ:
        user_dir = Path(os.environ["APPDATA"]) / "MangaPanelSearch"
    else:
        user_dir = Path.home() / ".manga_panel_search"
    user_dir.mkdir(parents=True, exist_ok=True)
    return user_dir

USER_DATA_DIR = get_user_data_dir()
DATA_DIR = USER_DATA_DIR / "data"
CACHE_DIR = USER_DATA_DIR / ".cache_descriptors"
CONFIG_FILE = USER_DATA_DIR / "config_paths.json"
GROUPS_CONFIG_FILE = USER_DATA_DIR / "library_groups.json"

DATA_DIR.mkdir(parents=True, exist_ok=True)
CACHE_DIR.mkdir(parents=True, exist_ok=True)
STATIC_DIR.mkdir(parents=True, exist_ok=True)

def migrate_legacy_data():
    """Migrate config and cache from legacy installation or dev paths if not already present."""
    candidates = []
    if getattr(sys, "frozen", False):
        candidates.append(Path(sys.executable).resolve().parent)
    candidates.append(Path(__file__).resolve().parent.parent)

    local_appdata = os.environ.get("LOCALAPPDATA")
    if local_appdata:
        installed_backend = Path(local_appdata) / "Programs" / "Manga Panel Search" / "resources" / "manga_backend"
        if installed_backend.exists():
            candidates.append(installed_backend)

    for src in candidates:
        if src.resolve() == USER_DATA_DIR.resolve():
            continue
        # Copy config_paths.json if missing in USER_DATA_DIR
        src_cfg = src / "config_paths.json"
        if src_cfg.exists() and not CONFIG_FILE.exists():
            try:
                shutil.copy2(src_cfg, CONFIG_FILE)
            except Exception:
                pass
        # Copy library_groups.json if missing in USER_DATA_DIR
        src_grp = src / "library_groups.json"
        if src_grp.exists() and not GROUPS_CONFIG_FILE.exists():
            try:
                shutil.copy2(src_grp, GROUPS_CONFIG_FILE)
            except Exception:
                pass
        # Copy any cached .pkl files into USER_DATA_DIR
        src_cache = src / ".cache_descriptors"
        if src_cache.exists() and src_cache.is_dir():
            for pkl in src_cache.glob("*.pkl"):
                dst_pkl = CACHE_DIR / pkl.name
                if not dst_pkl.exists():
                    try:
                        shutil.copy2(pkl, dst_pkl)
                    except Exception:
                        pass

migrate_legacy_data()

from backend.library_manager import LibraryGroupManager

# Engine & Manager Singletons
engine = MangaSearchEngine(max_features=2000, cache_dir=CACHE_DIR)
library = LibraryManager(data_dir=DATA_DIR, engine=engine, config_file=CONFIG_FILE)
group_manager = LibraryGroupManager(config_file=GROUPS_CONFIG_FILE)

if library.archives:
    group_manager.auto_organize_if_empty([a.name for a in library.archives])


class SetDirRequest(BaseModel):
    directory: str


@app.get("/api/status")
def get_status():
    """Get system status, indexing progress, and total counts."""
    return library.get_status()


@app.get("/api/library")
def get_library():
    """List all registered manga archives and their details."""
    return {"archives": library.get_library_summary(), "data_dir": str(library.data_dir)}


@app.post("/api/index/start")
def start_indexing():
    """Trigger background indexing for all registered volumes."""
    started = library.start_indexing()
    return {"started": started, "status": library.get_status()}


@app.post("/api/library/scan")
def scan_library():
    """Rescan the library folder for new archives."""
    before_names = set(a.name for a in library.archives)
    library.refresh_library()

    # If new archives appeared from registered directories
    new_names = [a.name for a in library.archives if a.name not in before_names]
    if new_names:
        for new_a_name in new_names:
            arc = library.get_archive(new_a_name)
            if arc:
                arc_p = Path(arc.path)
                for tp_str in library.target_paths:
                    tp = Path(tp_str)
                    if tp.is_dir():
                        try:
                            if arc_p.is_relative_to(tp):
                                group_manager.add_or_merge_folder_group(tp.name, [new_a_name])
                                break
                        except Exception:
                            pass

    all_names = [a.name for a in library.archives]
    group_manager.auto_organize_if_empty(all_names)
    return {"status": library.get_status(), "archives": library.get_library_summary()}


class PathRequest(BaseModel):
    path: str


@app.get("/api/paths")
def get_target_paths():
    """Get all registered folder and file paths."""
    return {"paths": library.get_target_paths(), "total_archives": len(library.archives)}


@app.post("/api/paths/add")
def add_target_path(req: PathRequest):
    """Add a new folder or single archive file path."""
    target_p = Path(req.path)
    if not target_p.exists():
        raise HTTPException(status_code=400, detail=f"Path does not exist: {req.path}")

    before_names = set(a.name for a in library.archives)

    success = library.add_target_path(req.path)
    if not success:
        raise HTTPException(status_code=400, detail=f"Failed to add path: {req.path}")

    # Determine newly added archives
    new_names = [a.name for a in library.archives if a.name not in before_names]
    if new_names:
        if target_p.is_dir():
            # Folder added -> automatically create/merge folder group with the folder's name!
            folder_name = target_p.name
            group_manager.add_or_merge_folder_group(folder_name, new_names)
        else:
            # Single archive added
            parent_name = target_p.parent.name
            if parent_name and parent_name.lower() not in ("data", "downloads", "desktop", "temp"):
                group_manager.add_or_merge_folder_group(parent_name, new_names)
            else:
                group_manager.auto_organize_if_empty([a.name for a in library.archives])

    return {"success": True, "paths": library.get_target_paths(), "status": library.get_status()}


@app.post("/api/paths/remove")
def remove_target_path(req: PathRequest):
    """Remove a folder or single archive file path."""
    success = library.remove_target_path(req.path)
    return {"success": success, "paths": library.get_target_paths(), "status": library.get_status()}


# --- Library Grouping & Search Filtering APIs ---

class CreateGroupRequest(BaseModel):
    name: str


class DeleteGroupRequest(BaseModel):
    group_id: str


class RenameGroupRequest(BaseModel):
    group_id: str
    name: str


class AssignGroupRequest(BaseModel):
    archive_names: List[str]
    target_group_id: Optional[str] = None


class ToggleRequest(BaseModel):
    target_type: str  # "group", "archive", "ungrouped", "all"
    target_id: Optional[str] = None
    enabled: bool


@app.get("/api/groups")
def get_groups():
    """Get folder grouping structure with checked states and counts."""
    if not library.archives:
        library.refresh_library()
    all_names = [a.name for a in library.archives]
    group_manager.auto_organize_if_empty(all_names)
    structure = group_manager.get_structure(all_names)
    enabled_count = len(group_manager.get_enabled_archive_names(all_names))
    return {
        "structure": structure,
        "total_archives": len(all_names),
        "enabled_archives": enabled_count,
    }


@app.post("/api/groups/create")
def create_group(req: CreateGroupRequest):
    """Create a new folder group."""
    group_id = group_manager.create_group(req.name)
    all_names = [a.name for a in library.archives]
    return {"success": True, "group_id": group_id, "structure": group_manager.get_structure(all_names)}


@app.post("/api/groups/delete")
def delete_group(req: DeleteGroupRequest):
    """Delete a folder group. Contained archives become ungrouped."""
    group_manager.delete_group(req.group_id)
    all_names = [a.name for a in library.archives]
    return {"success": True, "structure": group_manager.get_structure(all_names)}


@app.post("/api/groups/rename")
def rename_group(req: RenameGroupRequest):
    """Rename an existing folder group."""
    group_manager.rename_group(req.group_id, req.name)
    all_names = [a.name for a in library.archives]
    return {"success": True, "structure": group_manager.get_structure(all_names)}


@app.post("/api/groups/assign")
def assign_group(req: AssignGroupRequest):
    """Assign archives to a folder group (or None for ungrouped)."""
    group_manager.assign_archives_to_group(req.archive_names, req.target_group_id)
    all_names = [a.name for a in library.archives]
    return {"success": True, "structure": group_manager.get_structure(all_names)}


@app.post("/api/groups/toggle")
def toggle_item(req: ToggleRequest):
    """Toggle search inclusion for a group, single archive, ungrouped, or all."""
    all_names = [a.name for a in library.archives]
    if req.target_type == "group" and req.target_id:
        group_manager.toggle_group(req.target_id, req.enabled)
    elif req.target_type == "archive" and req.target_id:
        group_manager.toggle_archive(req.target_id, req.enabled)
    elif req.target_type == "ungrouped":
        group_manager.toggle_ungrouped(all_names, req.enabled)
    elif req.target_type == "all":
        for g in group_manager._data.get("groups", []):
            group_manager.toggle_group(g["id"], req.enabled)
        group_manager.toggle_ungrouped(all_names, req.enabled)

    structure = group_manager.get_structure(all_names)
    enabled_count = len(group_manager.get_enabled_archive_names(all_names))
    return {
        "success": True,
        "structure": structure,
        "total_archives": len(all_names),
        "enabled_archives": enabled_count,
    }


@app.get("/api/image/{archive_name}/{page_index}")
def get_page_image(archive_name: str, page_index: int):
    """Serve the raw original page image from an archive or folder."""
    archive = library.get_archive(archive_name)
    if not archive:
        raise HTTPException(status_code=404, detail=f"Archive not found: {archive_name}")

    try:
        data = archive.get_page_bytes(page_index)
        # Determine media type
        ext = Path(archive.pages[page_index]).suffix.lower()
        media_type = "image/png" if ext == ".png" else "image/jpeg"
        return Response(content=data, media_type=media_type)
    except IndexError:
        raise HTTPException(status_code=404, detail=f"Page {page_index} out of range")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


import asyncio
import json
import threading


@app.post("/api/search_stream")
async def search_stream(
    file: UploadFile = File(...),
    top_k: int = Form(5),
    min_score: float = Form(0.20),
    normalize_photo: bool = Form(True),
    min_panel_area_percent: float = Form(1.0),
    enable_gpu_dml: bool = Form(False),
    enable_coarse_clip: bool = Form(True),
):
    """
    Real-time streaming search endpoint returning non-blocking NDJSON events:
    - {"type": "progress", "current": 2500, "total": 10185, "percent": 24.5}
    - {"type": "result", "results": [...], "query_size": {...}}
    """
    if file.content_type and not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="File must be an image (PNG, JPG, etc.)")

    contents = await file.read()
    nparr = np.frombuffer(contents, np.uint8)
    query_img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    if query_img is None:
        raise HTTPException(status_code=400, detail="Invalid image file or cannot decode.")

    if not library.archives:
        library.refresh_library()

    if not library.archives:
        async def empty_gen():
            yield (json.dumps({"type": "error", "message": "No manga archives found in library."}) + "\n").encode("utf-8")
        return StreamingResponse(empty_gen(), media_type="application/x-ndjson")

    all_names = [a.name for a in library.archives]
    enabled_names = group_manager.get_enabled_archive_names(all_names)
    target_archives = [a for a in library.archives if a.name in enabled_names]

    if not target_archives:
        async def empty_gen():
            yield (json.dumps({"type": "error", "message": "検索対象のアーカイブが選択されていません。ライブラリ管理画面で検索対象にチェックを入れてください。"}) + "\n").encode("utf-8")
        return StreamingResponse(empty_gen(), media_type="application/x-ndjson")

    loop = asyncio.get_running_loop()
    async_q = asyncio.Queue()

    def on_progress(current, total):
        pct = round((current / max(1, total)) * 100, 1)
        loop.call_soon_threadsafe(
            async_q.put_nowait,
            {"type": "progress", "current": current, "total": total, "percent": pct}
        )

    def worker():
        try:
            results = engine.search_query(
                query_img=query_img,
                archives=target_archives,
                top_k=top_k,
                min_score=min_score,
                normalize_photo=normalize_photo,
                min_panel_area_percent=min_panel_area_percent,
                use_gpu_dml=enable_gpu_dml,
                enable_coarse_clip=enable_coarse_clip,
                progress_callback=on_progress,
            )
            formatted = []
            for r in results:
                d = r.to_dict()
                d["page_image_url"] = f"/api/image/{r.archive_name}/{r.page_index}"
                formatted.append(d)
            loop.call_soon_threadsafe(
                async_q.put_nowait,
                {
                    "type": "result",
                    "success": True,
                    "query_size": {"width": query_img.shape[1], "height": query_img.shape[0]},
                    "normalize_photo_applied": normalize_photo,
                    "gpu_dml_applied": enable_gpu_dml,
                    "target_archives_count": len(target_archives),
                    "total_library_archives": len(library.archives),
                    "results": formatted,
                }
            )
        except Exception as e:
            loop.call_soon_threadsafe(
                async_q.put_nowait,
                {"type": "error", "message": str(e)}
            )
        finally:
            loop.call_soon_threadsafe(async_q.put_nowait, None)

    threading.Thread(target=worker, daemon=True).start()

    async def stream_generator():
        while True:
            msg = await async_q.get()
            if msg is None:
                break
            yield (json.dumps(msg) + "\n").encode("utf-8")

    return StreamingResponse(stream_generator(), media_type="application/x-ndjson")


@app.post("/api/search")
async def search_panel(
    file: UploadFile = File(...),
    top_k: int = Form(5),
    min_score: float = Form(0.20),
    normalize_photo: bool = Form(True),
    min_panel_area_percent: float = Form(1.0),
    enable_gpu_dml: bool = Form(False),
    enable_coarse_clip: bool = Form(True),
    candidate_pool_size: Optional[int] = Form(None),
):
    """
    Search a cropped manga panel image with 100% full SIFT exhaustive matching.
    Supports optional adaptive paper illumination normalization and minimum panel size filtering.
    """
    if file.content_type and not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="File must be an image (PNG, JPG, etc.)")

    contents = await file.read()
    nparr = np.frombuffer(contents, np.uint8)
    query_img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    if query_img is None:
        raise HTTPException(status_code=400, detail="Invalid image file or cannot decode.")

    # Refresh library if empty
    if not library.archives:
        library.refresh_library()

    if not library.archives:
        return {
            "success": False,
            "message": "No manga archives found in library. Please add files to data directory.",
            "results": [],
        }

    all_names = [a.name for a in library.archives]
    enabled_names = group_manager.get_enabled_archive_names(all_names)
    target_archives = [a for a in library.archives if a.name in enabled_names]

    if not target_archives:
        return {
            "success": False,
            "message": "検索対象のアーカイブが選択されていません。ライブラリ管理画面で検索対象にチェックを入れてください。",
            "results": [],
        }

    # Perform matching on enabled archives with GPU acceleration & Approach A
    results = engine.search_query(
        query_img=query_img,
        archives=target_archives,
        top_k=top_k,
        min_score=min_score,
        normalize_photo=normalize_photo,
        min_panel_area_percent=min_panel_area_percent,
        use_gpu_dml=enable_gpu_dml,
        enable_coarse_clip=enable_coarse_clip,
    )

    formatted = []
    for r in results:
        d = r.to_dict()
        d["page_image_url"] = f"/api/image/{r.archive_name}/{r.page_index}"
        formatted.append(d)

    return {
        "success": True,
        "query_size": {"width": query_img.shape[1], "height": query_img.shape[0]},
        "normalize_photo_applied": normalize_photo,
        "gpu_dml_applied": enable_gpu_dml,
        "results": formatted,
    }


# Mount static assets
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


@app.get("/", response_class=HTMLResponse)
def index_page():
    """Serve the single-page application."""
    index_file = STATIC_DIR / "index.html"
    if index_file.exists():
        return HTMLResponse(content=index_file.read_text(encoding="utf-8"))
    return HTMLResponse("<h1>Manga Panel Search</h1><p>Static files loading...</p>")
