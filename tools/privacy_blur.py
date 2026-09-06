#!/usr/bin/env python3
"""Pixelate regions of a screenshot in place (privacy scrub before publishing captures).

usage: privacy_blur.py IMAGE [--statusbar] [--box x0 y0 x1 y1]... [--out PATH]
Boxes are fractions of the image width/height (0..1), top-left origin; --statusbar pixelates the top 3 %.
Pixelation uses a 24-px mosaic (unreadable, but the layout stays recognisable). Idempotent enough to re-run.
"""
import argparse
from PIL import Image

def pixelate(img, box, block=24):
    x0, y0, x1, y1 = box
    if x1 <= x0 or y1 <= y0:
        return
    region = img.crop((x0, y0, x1, y1))
    small = region.resize((max(1, (x1 - x0) // block), max(1, (y1 - y0) // block)), Image.BILINEAR)
    img.paste(small.resize(region.size, Image.NEAREST), (x0, y0))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--statusbar", action="store_true", help="pixelate the top 3%% strip")
    ap.add_argument("--box", nargs=4, type=float, action="append", default=[], metavar=("X0", "Y0", "X1", "Y1"))
    ap.add_argument("--out")
    a = ap.parse_args()
    img = Image.open(a.image).convert("RGB")
    w, h = img.size
    boxes = []
    if a.statusbar:
        boxes.append((0, 0, w, int(h * 0.03)))
    for x0, y0, x1, y1 in a.box:
        boxes.append((int(min(x0, x1) * w), int(min(y0, y1) * h), int(max(x0, x1) * w), int(max(y0, y1) * h)))
    for b in boxes:
        pixelate(img, b)
    img.save(a.out or a.image, optimize=True)
    print(f"{a.image}: {len(boxes)} region(s) pixelated ({w}x{h})")

if __name__ == "__main__":
    main()
