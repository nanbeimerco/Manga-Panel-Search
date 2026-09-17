"""
Automated Test for Archive Formats Handling.
Tests extraction from ZIP, CBZ, TAR, CBT, 7Z, and direct folders.
"""

import shutil
import tarfile
import zipfile
from pathlib import Path

import cv2
import numpy as np
import py7zr

from backend.archive_handler import MangaArchive, scan_manga_directory


def test_archives():
    test_dir = Path("./test_archives_dir").resolve()
    if test_dir.exists():
        shutil.rmtree(test_dir)
    test_dir.mkdir(parents=True, exist_ok=True)

    try:
        # Dummy image
        dummy_img = np.zeros((100, 100), dtype=np.uint8)
        img_bytes = cv2.imencode(".png", dummy_img)[1].tobytes()

        # 1. Test ZIP / CBZ
        cbz_file = test_dir / "manga_vol1.cbz"
        with zipfile.ZipFile(cbz_file, "w") as zf:
            zf.writestr("p1.png", img_bytes)
            zf.writestr("p2.png", img_bytes)
            zf.writestr("p10.png", img_bytes)

        arc_cbz = MangaArchive(cbz_file)
        assert len(arc_cbz) == 3
        # Test natural sorting (p1, p2, p10)
        assert arc_cbz.pages == ["p1.png", "p2.png", "p10.png"]
        cv_img = arc_cbz.get_page_cv(0)
        assert cv_img.shape == (100, 100)
        print("[OK] CBZ / ZIP test passed")

        # 2. Test TAR / CBT
        cbt_file = test_dir / "manga_vol2.cbt"
        with tarfile.open(cbt_file, "w") as tf:
            ti = tarfile.TarInfo(name="page_1.png")
            ti.size = len(img_bytes)
            import io
            tf.addfile(ti, io.BytesIO(img_bytes))

        arc_cbt = MangaArchive(cbt_file)
        assert len(arc_cbt) == 1
        assert arc_cbt.get_page_cv(0).shape == (100, 100)
        print("[OK] CBT / TAR test passed")

        # 3. Test 7Z / CB7
        cb7_file = test_dir / "manga_vol3.cb7"
        with py7zr.SevenZipFile(cb7_file, "w") as sz:
            sz.writestr(img_bytes, "001.png")

        arc_cb7 = MangaArchive(cb7_file)
        assert len(arc_cb7) == 1
        assert arc_cb7.get_page_cv(0).shape == (100, 100)
        print("[OK] CB7 / 7Z test passed")

        # 4. Test Scan Directory
        found = scan_manga_directory(test_dir)
        names = {a.name for a in found}
        assert "manga_vol1.cbz" in names
        assert "manga_vol2.cbt" in names
        assert "manga_vol3.cb7" in names
        print(f"[OK] Scan directory passed, found: {names}")

        print(">>> ALL ARCHIVE TESTS PASSED SUCCESSFULLY! <<<")

    finally:
        if test_dir.exists():
            shutil.rmtree(test_dir)


if __name__ == "__main__":
    test_archives()
