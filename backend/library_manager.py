"""
Library Group Manager for Manga Panel Search.
Manages grouping of archives into folders/series, with parent and child selection checkboxes.
Persists configuration to data/library_groups.json.
"""

import json
import re
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional, Set


class LibraryGroupManager:
    """Manages folder groups and archive inclusion toggles for search filtering."""

    def __init__(self, config_file: Optional[Path] = None):
        self.config_file = config_file or Path("data/library_groups.json")
        self.config_file.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()

        # Schema:
        # {
        #   "groups": [
        #     {"id": str, "name": str, "enabled": bool, "archives": [str]}
        #   ],
        #   "disabled_archives": [str], # individually unchecked archives
        #   "ungrouped_enabled": bool
        # }
        self._data: Dict[str, Any] = {
            "groups": [],
            "disabled_archives": [],
            "ungrouped_enabled": True,
        }
        self.load()

    def load(self):
        with self._lock:
            if self.config_file.exists():
                try:
                    with open(self.config_file, "r", encoding="utf-8") as f:
                        self._data = json.load(f)
                except Exception as e:
                    print(f"[LibraryGroupManager] Error loading config: {e}")

    def save(self):
        with self._lock:
            try:
                with open(self.config_file, "w", encoding="utf-8") as f:
                    json.dump(self._data, f, ensure_ascii=False, indent=2)
            except Exception as e:
                print(f"[LibraryGroupManager] Error saving config: {e}")

    def auto_organize_if_empty(self, archive_names: List[str]):
        """
        If groups are empty, automatically group known manga series based on filename heuristics.
        E.g. groups all 'らんま1／2' volumes into 'らんま1/2' folder, and 'Mezon Ikkoku' into 'めぞん一刻'.
        """
        with self._lock:
            if self._data.get("groups"):
                return  # Already organized

            groups_dict: Dict[str, List[str]] = {}
            for name in archive_names:
                series = None
                if "らんま" in name or "Ranma" in name:
                    series = "らんま1/2"
                elif "めぞん" in name or "Mezon" in name or "Maison" in name:
                    series = "めぞん一刻"
                elif "犬夜叉" in name or "Inuyasha" in name:
                    series = "犬夜叉"
                elif "うる星" in name or "Urusei" in name:
                    series = "うる星やつら"
                else:
                    # Generic regex extract: e.g. [Author] Series_Name Vol...
                    m = re.search(r"\]\s*([^第0-9\[\]_]+)", name)
                    if m:
                        candidate = m.group(1).strip()
                        if len(candidate) >= 2:
                            series = candidate

                if series:
                    groups_dict.setdefault(series, []).append(name)

            new_groups = []
            for i, (series_name, members) in enumerate(groups_dict.items()):
                new_groups.append({
                    "id": f"group_{i+1}_{abs(hash(series_name)) % 10000}",
                    "name": series_name,
                    "enabled": True,
                    "archives": sorted(members),
                })

            self._data["groups"] = new_groups

        self.save()

    def get_structure(self, all_archive_names: List[str]) -> Dict[str, Any]:
        """
        Returns the full folder structure, including ungrouped archives,
        counts, and checked statuses.
        """
        with self._lock:
            disabled_set = set(self._data.get("disabled_archives", []))
            grouped_names: Set[str] = set()
            groups_res = []

            for g in self._data.get("groups", []):
                g_archives = []
                g_enabled = g.get("enabled", True)
                for a_name in g.get("archives", []):
                    if a_name in all_archive_names:
                        grouped_names.add(a_name)
                        is_checked = g_enabled and (a_name not in disabled_set)
                        g_archives.append({
                            "name": a_name,
                            "checked": is_checked,
                        })

                # Calculate parent checkbox state:
                # true if all checked, false if none checked, "indeterminate" if mixed
                checked_count = sum(1 for a in g_archives if a["checked"])
                parent_checked = (checked_count == len(g_archives)) if g_archives else g_enabled
                is_indeterminate = (0 < checked_count < len(g_archives))

                groups_res.append({
                    "id": g["id"],
                    "name": g["name"],
                    "enabled": parent_checked,
                    "is_indeterminate": is_indeterminate,
                    "count": len(g_archives),
                    "archives": g_archives,
                })

            # Ungrouped archives
            ungrouped_enabled = self._data.get("ungrouped_enabled", True)
            ungrouped = []
            for a_name in all_archive_names:
                if a_name not in grouped_names:
                    is_checked = ungrouped_enabled and (a_name not in disabled_set)
                    ungrouped.append({
                        "name": a_name,
                        "checked": is_checked,
                    })

            u_checked_count = sum(1 for a in ungrouped if a["checked"])
            u_parent_checked = (u_checked_count == len(ungrouped)) if ungrouped else ungrouped_enabled
            u_indeterminate = (0 < u_checked_count < len(ungrouped))

            return {
                "groups": groups_res,
                "ungrouped": {
                    "enabled": u_parent_checked,
                    "is_indeterminate": u_indeterminate,
                    "count": len(ungrouped),
                    "archives": ungrouped,
                },
            }

    def get_enabled_archive_names(self, all_archive_names: List[str]) -> Set[str]:
        """Returns the set of archive names that are currently enabled for search."""
        structure = self.get_structure(all_archive_names)
        enabled = set()

        for g in structure["groups"]:
            for a in g["archives"]:
                if a["checked"]:
                    enabled.add(a["name"])

        for a in structure["ungrouped"]["archives"]:
            if a["checked"]:
                enabled.add(a["name"])

        return enabled

    def create_group(self, name: str) -> str:
        """Create a new folder group and return its ID."""
        with self._lock:
            import time
            group_id = f"group_{int(time.time()*1000)}"
            self._data.setdefault("groups", []).append({
                "id": group_id,
                "name": name.strip() or "新規フォルダ",
                "enabled": True,
                "archives": [],
            })
        self.save()
        return group_id

    def delete_group(self, group_id: str):
        """Delete a folder group. Its archives automatically become ungrouped."""
        with self._lock:
            self._data["groups"] = [g for g in self._data.get("groups", []) if g["id"] != group_id]
        self.save()

    def rename_group(self, group_id: str, new_name: str):
        with self._lock:
            for g in self._data.get("groups", []):
                if g["id"] == group_id:
                    g["name"] = new_name.strip() or g["name"]
                    break
        self.save()

    def assign_archives_to_group(self, archive_names: List[str], target_group_id: Optional[str]):
        """Move archives to target group, or to ungrouped if target_group_id is None."""
        with self._lock:
            names_set = set(archive_names)
            # Remove from existing groups
            for g in self._data.get("groups", []):
                g["archives"] = [a for a in g.get("archives", []) if a not in names_set]

            # Add to target group if specified
            if target_group_id:
                for g in self._data.get("groups", []):
                    if g["id"] == target_group_id:
                        for a in archive_names:
                            if a not in g["archives"]:
                                g["archives"].append(a)
                        g["archives"].sort()
                        break
        self.save()

    def toggle_group(self, group_id: str, enabled: bool):
        """Toggle parent checkbox for a group, updating all contained archives."""
        with self._lock:
            disabled_set = set(self._data.get("disabled_archives", []))
            for g in self._data.get("groups", []):
                if g["id"] == group_id:
                    g["enabled"] = enabled
                    for a in g.get("archives", []):
                        if enabled:
                            disabled_set.discard(a)
                        else:
                            disabled_set.add(a)
                    break
            self._data["disabled_archives"] = list(disabled_set)
        self.save()

    def toggle_ungrouped(self, all_archive_names: List[str], enabled: bool):
        with self._lock:
            self._data["ungrouped_enabled"] = enabled
            disabled_set = set(self._data.get("disabled_archives", []))
            grouped_names = set()
            for g in self._data.get("groups", []):
                grouped_names.update(g.get("archives", []))

            for a in all_archive_names:
                if a not in grouped_names:
                    if enabled:
                        disabled_set.discard(a)
                    else:
                        disabled_set.add(a)
            self._data["disabled_archives"] = list(disabled_set)
        self.save()

    def toggle_archive(self, archive_name: str, enabled: bool):
        """Toggle child checkbox for a single archive."""
        with self._lock:
            disabled_set = set(self._data.get("disabled_archives", []))
            if enabled:
                disabled_set.discard(archive_name)
            else:
                disabled_set.add(archive_name)
            self._data["disabled_archives"] = list(disabled_set)
        self.save()

    def add_or_merge_folder_group(self, folder_name: str, archive_names: List[str]):
        """
        Creates or merges an archive group named after the uploaded/added folder,
        assigning the specified archive names directly to it.
        """
        with self._lock:
            folder_clean = folder_name.strip() or "新規フォルダ"
            target_group = None

            # Check if group with same name already exists
            for g in self._data.get("groups", []):
                if g["name"].strip().lower() == folder_clean.lower():
                    target_group = g
                    break

            if target_group is None:
                import time
                group_id = f"group_{int(time.time()*1000)}"
                target_group = {
                    "id": group_id,
                    "name": folder_clean,
                    "enabled": True,
                    "archives": [],
                }
                self._data.setdefault("groups", []).append(target_group)

            # Assign archives to this target group
            names_set = set(archive_names)
            # Remove from other groups first to avoid duplication
            for g in self._data.get("groups", []):
                if g["id"] != target_group["id"]:
                    g["archives"] = [a for a in g.get("archives", []) if a not in names_set]

            # Add to target group
            current_archives = set(target_group.get("archives", []))
            for a in archive_names:
                current_archives.add(a)
            target_group["archives"] = sorted(list(current_archives))

        self.save()


group_manager = LibraryGroupManager()
