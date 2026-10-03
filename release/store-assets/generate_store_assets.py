#!/usr/bin/env python3
"""Rebuild original ToviCam store artwork using Pillow; no downloaded imagery."""

from __future__ import annotations

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

from PIL import Image, ImageCms, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
SCALE = 4
GOLD = "#F4B942"
ICON_BACKGROUND = "#171717"
BACKGROUND = "#172019"
CREAM = "#F4EEDC"
CAMERA_PATH = (
    "M13,14h7l2,-3h6l2,3h5c2.2,0 4,1.8 4,4v17c0,2.2 -1.8,4 -4,4"
    "H13c-2.2,0 -4,-1.8 -4,-4V18c0,-2.2 1.8,-4 4,-4z"
)
FRAMES = (
    dict(x=627, y=144, w=176, h=242, angle=-10, paper="#7D897E", sky="#2C3A30", sun="#BCC5B7", land="#556658"),
    dict(x=689, y=104, w=176, h=242, angle=4, paper="#C3A264", sky="#99763D", sun="#EED391", land="#4D5C3D"),
    dict(x=747, y=164, w=160, h=223, angle=13, paper=GOLD, sky="#3D573A", sun="#D3B562", land="#1C3024"),
)


def font_path(bold: bool) -> Path:
    name = "Arial Bold.ttf" if bold else "Arial.ttf"
    candidates = (
        Path("/System/Library/Fonts/Supplemental") / name,
        Path("/usr/share/fonts/truetype/liberation2") / ("LiberationSans-Bold.ttf" if bold else "LiberationSans-Regular.ttf"),
        Path("/usr/share/fonts/truetype/dejavu") / ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"),
    )
    for path in candidates:
        if path.exists():
            return path
    raise FileNotFoundError("Install Arial, Liberation Sans or DejaVu Sans to render the editable artwork.")


def cubic(start, first, second, end):
    points = []
    for step in range(1, 33):
        t = step / 32
        a = 1 - t
        points.append(tuple(a**3 * start[i] + 3 * a*a*t * first[i] + 3*a*t*t * second[i] + t**3 * end[i] for i in (0, 1)))
    return points


def camera_points():
    points = [(13, 14), (20, 14), (22, 11), (28, 11), (30, 14), (35, 14)]
    points.extend(cubic((35, 14), (37.2, 14), (39, 15.8), (39, 18)))
    points.append((39, 35))
    points.extend(cubic((39, 35), (39, 37.2), (37.2, 39), (35, 39)))
    points.append((13, 39))
    points.extend(cubic((13, 39), (10.8, 39), (9, 37.2), (9, 35)))
    points.append((9, 18))
    points.extend(cubic((9, 18), (9, 15.8), (10.8, 14), (13, 14)))
    return points


def save_png(image: Image.Image, filename: str):
    profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    image.save(ROOT / filename, icc_profile=profile, optimize=True)


def render_icon():
    image = Image.new("RGBA", (512 * SCALE, 512 * SCALE), ICON_BACKGROUND)
    draw = ImageDraw.Draw(image)
    tx, ty, size = -20, -31.5, 11.5
    points = [((x * size + tx) * SCALE, (y * size + ty) * SCALE) for x, y in camera_points()]
    draw.polygon(points, fill=GOLD)
    cx, cy = (24 * size + tx) * SCALE, (26 * size + ty) * SCALE
    for radius, fill in ((8, ICON_BACKGROUND), (5, GOLD)):
        r = radius * size * SCALE
        draw.ellipse((cx-r, cy-r, cx+r, cy+r), fill=fill)
    image = image.resize((512, 512), Image.Resampling.LANCZOS)
    save_png(image, "store-icon.png")
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512" role="img" aria-labelledby="title desc">
  <title id="title">ToviCam store icon</title>
  <desc id="desc">Original yellow camera mark with concentric lens rings on a full square charcoal background.</desc>
  <rect width="512" height="512" fill="{ICON_BACKGROUND}"/>
  <g transform="translate({tx} {ty}) scale({size})">
    <path d="{CAMERA_PATH}" fill="{GOLD}"/>
    <circle cx="24" cy="26" r="8" fill="{ICON_BACKGROUND}"/>
    <circle cx="24" cy="26" r="5" fill="{GOLD}"/>
  </g>
</svg>
'''
    (ROOT / "store-icon.svg").write_text(svg, encoding="utf-8")


def frame_geometry(frame):
    w, h = frame["w"], frame["h"]
    return [(8, h * .64), (w * .35, h * .46), (w * .66, h * .61), (w-8, h * .4), (w-8, h-24), (8, h-24)]


def render_frame(frame):
    w, h = frame["w"], frame["h"]
    image = Image.new("RGBA", (w*SCALE, h*SCALE))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((0, 0, w*SCALE-1, h*SCALE-1), radius=16*SCALE, fill=frame["paper"])
    draw.rounded_rectangle((8*SCALE, 8*SCALE, (w-8)*SCALE, (h-24)*SCALE), radius=9*SCALE, fill=frame["sky"])
    cx, cy, radius = w * .55, h * .28, w * .21
    draw.ellipse(((cx-radius)*SCALE, (cy-radius)*SCALE, (cx+radius)*SCALE, (cy+radius)*SCALE), fill=frame["sun"])
    draw.polygon([(x*SCALE, y*SCALE) for x, y in frame_geometry(frame)], fill=frame["land"])
    return image.rotate(-frame["angle"], resample=Image.Resampling.BICUBIC, expand=True)


def draw_text(draw, value, x, baseline, size, fill, bold=False, spacing=0):
    font = ImageFont.truetype(str(font_path(bold)), size*SCALE)
    cursor = x*SCALE
    if spacing:
        for char in value:
            draw.text((cursor, baseline*SCALE), char, font=font, fill=fill, anchor="ls")
            cursor += draw.textlength(char, font=font) + spacing*SCALE
    else:
        draw.text((cursor, baseline*SCALE), value, font=font, fill=fill, anchor="ls")


def frame_svg(frame):
    x, y, w, h = (frame[key] for key in ("x", "y", "w", "h"))
    points = " ".join(f"{px:.2f},{py:.2f}" for px, py in frame_geometry(frame))
    return f'''  <g transform="translate({x} {y}) rotate({frame['angle']} {w/2} {h/2})">
    <rect width="{w}" height="{h}" rx="16" fill="{frame['paper']}"/>
    <rect x="8" y="8" width="{w-16}" height="{h-32}" rx="9" fill="{frame['sky']}"/>
    <circle cx="{w*.55:.2f}" cy="{h*.28:.2f}" r="{w*.21:.2f}" fill="{frame['sun']}"/>
    <polygon points="{points}" fill="{frame['land']}"/>
  </g>'''


def render_feature():
    image = Image.new("RGBA", (1024*SCALE, 500*SCALE), BACKGROUND)
    draw = ImageDraw.Draw(image)
    draw.ellipse((610*SCALE, -170*SCALE, 1190*SCALE, 530*SCALE), fill="#263025")
    draw.rectangle((1008*SCALE, 0, 1024*SCALE, 500*SCALE), fill=GOLD)
    draw.rounded_rectangle((80*SCALE, 172*SCALE, 124*SCALE, 178*SCALE), radius=3*SCALE, fill=GOLD)
    draw_text(draw, "ToviCam", 80, 125, 47, CREAM, bold=True, spacing=4)
    draw_text(draw, "A little camera.", 80, 262, 42, GOLD, bold=True)
    draw_text(draw, "A different view.", 80, 320, 42, CREAM, bold=True)
    draw_text(draw, "Color. Monochrome. Character.", 80, 390, 25, "#C8CDBD")
    for frame in FRAMES:
        layer = render_frame(frame)
        cx = (frame["x"] + frame["w"] / 2) * SCALE
        cy = (frame["y"] + frame["h"] / 2) * SCALE
        image.alpha_composite(layer, (round(cx-layer.width/2), round(cy-layer.height/2)))
    image = image.resize((1024, 500), Image.Resampling.LANCZOS).convert("RGB")
    save_png(image, "feature-graphic.png")
    frames = "\n".join(frame_svg(frame) for frame in FRAMES)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500" role="img" aria-labelledby="title desc">
  <title id="title">ToviCam feature graphic</title>
  <desc id="desc">ToviCam, a little camera, a different view. Original warm color and monochrome geometric postcards.</desc>
  <rect width="1024" height="500" fill="{BACKGROUND}"/>
  <ellipse cx="900" cy="180" rx="290" ry="350" fill="#263025"/>
  <rect x="1008" width="16" height="500" fill="{GOLD}"/>
  <rect x="80" y="172" width="44" height="6" rx="3" fill="{GOLD}"/>
  <g font-family="Arial, Helvetica, sans-serif">
    <text x="80" y="125" font-size="47" font-weight="700" letter-spacing="4" fill="{CREAM}">ToviCam</text>
    <text x="80" y="262" font-size="42" font-weight="700" fill="{GOLD}">A little camera.</text>
    <text x="80" y="320" font-size="42" font-weight="700" fill="{CREAM}">A different view.</text>
    <text x="80" y="390" font-size="25" fill="#C8CDBD">Color. Monochrome. Character.</text>
  </g>
{frames}
</svg>
'''
    (ROOT / "feature-graphic.svg").write_text(svg, encoding="utf-8")


def verify():
    expected = (("store-icon.png", (512, 512), "RGBA"), ("feature-graphic.png", (1024, 500), "RGB"))
    for name, size, mode in expected:
        path = ROOT / name
        with Image.open(path) as image:
            assert image.size == size, (name, image.size)
            assert image.mode == mode, (name, image.mode)
            assert image.info.get("icc_profile"), name
            if mode == "RGBA":
                assert image.getextrema()[3] == (255, 255), "Icon must have a full square background"
                assert path.stat().st_size <= 1024 * 1024, "Google Play icon limit exceeded"
        print(f"{name}: {size[0]} × {size[1]}, {mode}, sRGB, {path.stat().st_size:,} bytes")
    for name in ("store-icon.svg", "feature-graphic.svg"):
        ET.parse(ROOT / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    arguments = parser.parse_args()
    if not arguments.verify_only:
        render_icon()
        render_feature()
    verify()
