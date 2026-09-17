"""
Automated Test for Manga Panel Search Engine.
Generates synthetic comic pages, archives them into CBZ,
crops a sample panel, and asserts that the search engine
correctly identifies the exact volume, page number, and bounding box.
"""

import shutil
import zipfile
from pathlib import Path

import cv2
import numpy as np

from backend.archive_handler import MangaArchive
from backend.search_engine import MangaSearchEngine


def create_mock_manga_page(page_num: int, width: int = 800, height: int = 1200) -> np.ndarray:
    """Draw a mock manga page with panels, screentone patterns, and mock dialogues."""
    canvas = np.ones((height, width), dtype=np.uint8) * 255

    # Page frame borders
    margin = 40
    cv2.rectangle(canvas, (margin, margin), (width - margin, height - margin), 0, 3)

    # Panel split lines
    mid_y = height // 2
    cv2.line(canvas, (margin, mid_y), (width - margin, mid_y), 0, 3)

    # Top Panel details
    cv2.putText(canvas, f"PAGE {page_num}", (margin + 20, margin + 40), cv2.FONT_HERSHEY_SIMPLEX, 0.8, 0, 2)
    # Add some line art / shapes in top panel
    cv2.circle(canvas, (margin + 150, margin + 200), 80, 0, 2)
    cv2.rectangle(canvas, (margin + 350, margin + 100), (margin + 550, margin + 350), 0, 2)

    # Bottom Panel: Distinct feature for testing
    # Let's draw unique geometric patterns depending on page number
    center_x, center_y = width // 2, mid_y + (height - mid_y) // 2
    for r in range(20, 120, 20):
        cv2.circle(canvas, (center_x + (page_num * 10), center_y), r, 0, 1)

    # Draw mock speech bubble with text
    bubble_x, bubble_y = margin + 80, mid_y + 80
    cv2.ellipse(canvas, (bubble_x + 100, bubble_y + 100), (90, 60), 0, 0, 360, 0, 2)
    cv2.putText(
        canvas,
        f"TEST-{page_num}",
        (bubble_x + 40, bubble_y + 105),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.9,
        0,
        2,
    )

    # Add mock screentone dots in a region
    tone_x, tone_y = width - margin - 200, mid_y + 50
    for y in range(tone_y, tone_y + 150, 6):
        for x in range(tone_x, tone_x + 150, 6):
            canvas[y, x] = 0

    return canvas


def test_search_pipeline():
    test_dir = Path("./test_workspace").resolve()
    if test_dir.exists():
        shutil.rmtree(test_dir)
    test_dir.mkdir(parents=True, exist_ok=True)

    try:
        # 1. Create a mock CBZ archive with 5 pages
        cbz_path = test_dir / "Test_Volume_01.cbz"
        pages_to_create = 5
        target_page_idx = 2  # Page 3 (0-indexed 2)

        cropped_panel_gray = None
        crop_box = None

        with zipfile.ZipFile(cbz_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for p in range(pages_to_create):
                page_img = create_mock_manga_page(p + 1)
                img_bytes = cv2.imencode(".png", page_img)[1].tobytes()
                zf.writestr(f"page_{p+1:03d}.png", img_bytes)

                if p == target_page_idx:
                    # Crop the bottom-left speech bubble and circle pattern as the query panel
                    # [y1:y2, x1:x2]
                    h, w = page_img.shape
                    mid_y = h // 2
                    x1, y1 = 40, mid_y + 20
                    x2, y2 = 450, h - 40
                    cropped_panel_gray = page_img[y1:y2, x1:x2].copy()
                    crop_box = (x1, y1, x2 - x1, y2 - y1)

        print(f"Created mock CBZ at {cbz_path} with {pages_to_create} pages.")
        assert cropped_panel_gray is not None

        # Save query panel image
        query_path = test_dir / "query_panel.png"
        cv2.imwrite(str(query_path), cropped_panel_gray)
        print(f"Saved query panel to {query_path} (size: {cropped_panel_gray.shape})")

        # 2. Run Search Engine
        engine = MangaSearchEngine(max_features=2000, cache_dir=test_dir / ".cache")
        archive = MangaArchive(cbz_path)

        print("Executing search across archive pages...")
        results = engine.search_query(cropped_panel_gray, [archive], top_k=3, min_score=0.20)

        print(f"Found {len(results)} match(es):")
        for r in results:
            print(
                f"  - {r.archive_name} Page {r.page_number} ({r.page_filename}): "
                f"Score={r.score:.3f}, Inliers={r.inliers_count}, BBox={r.bounding_box}"
            )

        # Assertions
        assert len(results) > 0, "Should have at least 1 match"
        top_match = results[0]
        assert top_match.page_index == target_page_idx, f"Expected page {target_page_idx}, got {top_match.page_index}"
        assert top_match.inliers_count >= 15, f"Expected >= 15 inliers, got {top_match.inliers_count}"

        # Check that detected bounding box overlaps significantly with real crop box
        det_bbox = top_match.bounding_box
        print(f"Ground truth box: {crop_box}")
        print(f"Detected box:     ({det_bbox['x']}, {det_bbox['y']}, {det_bbox['w']}, {det_bbox['h']})")

        # Check horizontal and vertical centers overlap
        gt_cx = crop_box[0] + crop_box[2] / 2
        gt_cy = crop_box[1] + crop_box[3] / 2
        det_cx = det_bbox["x"] + det_bbox["w"] / 2
        det_cy = det_bbox["y"] + det_bbox["h"] / 2

        assert abs(gt_cx - det_cx) < 50, "Bounding box X center should be close"
        assert abs(gt_cy - det_cy) < 50, "Bounding box Y center should be close"

        print(">>> ALL TESTS PASSED SUCCESSFULLY! <<<")

    finally:
        # Cleanup
        if test_dir.exists():
            shutil.rmtree(test_dir)


if __name__ == "__main__":
    test_search_pipeline()
