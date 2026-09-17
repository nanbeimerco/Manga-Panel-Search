"""
Generate mock manga samples for demo and testing.
Places a realistic sample volume into ./data/Sample_Manga_Vol01.cbz
and test query crop images into ./data/query_sample.png
"""

import zipfile
from pathlib import Path

import cv2
import numpy as np


def draw_realistic_manga_page(page_num: int, title: str = "SAMPLE CHRONICLES") -> np.ndarray:
    w, h = 1000, 1500
    page = np.ones((h, w), dtype=np.uint8) * 255

    # Page frame margin
    mx, my = 60, 60
    cv2.rectangle(page, (mx, my), (w - mx, h - my), 0, 4)

    # Top header text
    cv2.putText(page, f"- {title}  VOL.1  P.{page_num} -", (mx + 20, my - 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, 0, 2)

    # Panel split horizontal line
    p1_bottom = 680
    cv2.line(page, (mx, p1_bottom), (w - mx, p1_bottom), 0, 4)

    # Top Panel split vertical
    p1_mid_x = 480
    cv2.line(page, (p1_mid_x, my), (p1_mid_x, p1_bottom), 0, 3)

    # --- Panel 1 (Top Left) ---
    # Draw character face outline
    cv2.circle(page, (mx + 180, my + 250), 100, 0, 3)
    cv2.line(page, (mx + 130, my + 230), (mx + 160, my + 240), 0, 4)  # Left eye
    cv2.line(page, (mx + 200, my + 240), (mx + 230, my + 230), 0, 4)  # Right eye
    cv2.line(page, (mx + 165, my + 300), (mx + 195, my + 300), 0, 3)  # Mouth
    # Screentone shading
    for y in range(my + 100, my + 200, 5):
        for x in range(mx + 80, mx + 160, 5):
            page[y, x] = 0

    # Speech Bubble Top Left
    cv2.ellipse(page, (mx + 300, my + 140), (80, 50), 0, 0, 360, 0, 2)
    cv2.putText(page, "WHAT!?", (mx + 255, my + 148), cv2.FONT_HERSHEY_SIMPLEX, 0.8, 0, 2)

    # --- Panel 2 (Top Right): Unique per page ---
    center_x, center_y = p1_mid_x + 240, my + 300
    # Starburst effect lines
    for angle in range(0, 360, 15):
        rad = np.deg2rad(angle)
        x_end = int(center_x + 180 * np.cos(rad))
        y_end = int(center_y + 180 * np.sin(rad))
        cv2.line(page, (center_x, center_y), (x_end, y_end), 0, 1)

    cv2.circle(page, (center_x, center_y), 40 + (page_num * 8), 0, 3)
    cv2.putText(page, f"POWER {page_num * 1000}", (center_x - 70, center_y + 8), cv2.FONT_HERSHEY_SIMPLEX, 0.6, 0, 2)

    # --- Bottom Panel (Large Action/Scene) ---
    # Diagonal split line
    cv2.line(page, (mx + 400, p1_bottom), (mx + 200, h - my), 0, 3)

    # Bottom Left Bubble
    b_cx, b_cy = mx + 180, p1_bottom + 350
    cv2.ellipse(page, (b_cx, b_cy), (110, 80), 0, 0, 360, 0, 3)
    cv2.putText(page, f"SCENE-{page_num}", (b_cx - 65, b_cy - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.8, 0, 2)
    cv2.putText(page, "TARGET PANEL", (b_cx - 75, b_cy + 25), cv2.FONT_HERSHEY_SIMPLEX, 0.6, 0, 2)

    # Bottom Right: Dense detail cross-hatching
    for i in range(0, 300, 15):
        cv2.line(page, (w - mx - 300 + i, p1_bottom + 100), (w - mx - i, h - my - 100), 0, 1)
        cv2.line(page, (w - mx - i, p1_bottom + 100), (w - mx - 300 + i, h - my - 100), 0, 1)

    return page


def generate_samples():
    data_dir = Path("./data").resolve()
    data_dir.mkdir(parents=True, exist_ok=True)

    cbz_file = data_dir / "Sample_Manga_Vol01.cbz"
    total_pages = 6
    target_page_idx = 3  # Page 4 (1-indexed)

    crop_img = None

    with zipfile.ZipFile(cbz_file, "w", zipfile.ZIP_DEFLATED) as zf:
        for p in range(1, total_pages + 1):
            page_img = draw_realistic_manga_page(p)
            img_bytes = cv2.imencode(".png", page_img)[1].tobytes()
            zf.writestr(f"p_{p:03d}.png", img_bytes)

            if p == target_page_idx:
                # Crop Panel 1 (Top-Left panel: character face + WHAT!? speech bubble)
                # [y1:y2, x1:x2]
                x1, y1 = 55, 55
                x2, y2 = 475, 675
                crop_img = page_img[y1:y2, x1:x2].copy()

    print(f"Generated sample CBZ: {cbz_file} ({total_pages} pages)")

    # Save the sample query panel
    query_file = data_dir / "sample_query_panel.png"
    if crop_img is not None:
        cv2.imwrite(str(query_file), crop_img)
        print(f"Saved sample query panel: {query_file}")


if __name__ == "__main__":
    generate_samples()
