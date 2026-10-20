#!/usr/bin/env python3
"""
Bridge Icon Generator
Renders the official Bridge brand icon and generates:
1. Master asset: assets/logo.png (512x512)
2. Android mipmaps: mdpi (48), hdpi (72), xhdpi (96), xxhdpi (144), xxxhdpi (192)
3. Android adaptive vector XML drawables (mipmap-anydpi-v26)
4. macOS multi-resolution AppIcon.icns (16 to 1024px)
"""

import os
import io
import struct
from PIL import Image, ImageDraw

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS_DIR = os.path.join(REPO_ROOT, "assets")
RES_DIR = os.path.join(REPO_ROOT, "android", "app", "src", "main", "res")
MACOS_RESOURCES = os.path.join(REPO_ROOT, "macos", "Resources")

os.makedirs(ASSETS_DIR, exist_ok=True)
os.makedirs(MACOS_RESOURCES, exist_ok=True)

def render_master_logo(size=1024):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    scale = size / 512.0

    # 1. Rounded canvas background
    # Gradient simulation from deep slate to indigo
    corner_radius = int(112 * scale)
    base_box = [0, 0, size, size]
    draw.rounded_rectangle(base_box, radius=corner_radius, fill=(15, 23, 42, 255), outline=(255, 255, 255, 25), width=int(3 * scale))

    # Inner soft lighting
    inner_box = [int(16 * scale), int(16 * scale), size - int(16 * scale), size - int(16 * scale)]
    draw.rounded_rectangle(inner_box, radius=int(96 * scale), fill=(24, 33, 56, 255))

    # 2. Main Continuity Bridge Arch
    # Arc bounding box: left 140, top 160, right 372, bottom 440
    arch_box = [int(130 * scale), int(140 * scale), int(382 * scale), int(450 * scale)]
    arch_width = int(22 * scale)
    draw.arc(arch_box, start=180, end=0, fill=(59, 130, 246, 255), width=arch_width)

    # Glowing inner arc line
    inner_arch_box = [int(150 * scale), int(180 * scale), int(362 * scale), int(450 * scale)]
    draw.arc(inner_arch_box, start=180, end=0, fill=(56, 189, 248, 200), width=int(10 * scale))

    # Center pulse node
    pulse_cx = int(256 * scale)
    pulse_cy = int(140 * scale)
    pulse_r = int(14 * scale)
    draw.ellipse([pulse_cx - pulse_r, pulse_cy - pulse_r, pulse_cx + pulse_r, pulse_cy + pulse_r], fill=(248, 250, 252, 255), outline=(56, 189, 248, 255), width=int(4 * scale))

    # 3. Left Node: Phone Glyph (Android)
    phone_x = int(108 * scale)
    phone_y = int(256 * scale)
    phone_w = int(76 * scale)
    phone_h = int(136 * scale)
    draw.rounded_rectangle([phone_x, phone_y, phone_x + phone_w, phone_y + phone_h], radius=int(18 * scale), fill=(30, 41, 59, 255), outline=(56, 189, 248, 255), width=int(7 * scale))
    # Phone screen
    draw.rounded_rectangle([phone_x + int(10 * scale), phone_y + int(24 * scale), phone_x + phone_w - int(10 * scale), phone_y + phone_h - int(24 * scale)], radius=int(8 * scale), fill=(56, 189, 248, 60))
    # Phone speaker
    draw.rounded_rectangle([phone_x + int(24 * scale), phone_y + int(10 * scale), phone_x + phone_w - int(24 * scale), phone_y + int(14 * scale)], radius=int(2 * scale), fill=(148, 163, 184, 255))
    # Phone home indicator
    draw.rounded_rectangle([phone_x + int(26 * scale), phone_y + int(120 * scale), phone_x + phone_w - int(26 * scale), phone_y + int(124 * scale)], radius=int(2 * scale), fill=(148, 163, 184, 255))

    # 4. Right Node: Laptop / Mac Display Glyph (macOS)
    mac_x = int(326 * scale)
    mac_y = int(264 * scale)
    mac_w = int(114 * scale)
    mac_h = int(80 * scale)
    draw.rounded_rectangle([mac_x, mac_y, mac_x + mac_w, mac_y + mac_h], radius=int(10 * scale), fill=(30, 41, 59, 255), outline=(129, 140, 248, 255), width=int(7 * scale))
    # Mac screen preview
    draw.rounded_rectangle([mac_x + int(8 * scale), mac_y + int(8 * scale), mac_x + mac_w - int(8 * scale), mac_y + mac_h - int(8 * scale)], radius=int(4 * scale), fill=(129, 140, 248, 60))
    # Stand
    stand_base_y = mac_y + mac_h
    draw.polygon([
        (mac_x + int(46 * scale), stand_base_y),
        (mac_x + int(42 * scale), stand_base_y + int(26 * scale)),
        (mac_x + int(72 * scale), stand_base_y + int(26 * scale)),
        (mac_x + int(68 * scale), stand_base_y)
    ], fill=(51, 65, 85, 255))
    draw.rounded_rectangle([mac_x + int(28 * scale), stand_base_y + int(24 * scale), mac_x + mac_w - int(28 * scale), stand_base_y + int(30 * scale)], radius=int(3 * scale), fill=(148, 163, 184, 255))

    return img

def render_round_icon(img):
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse([0, 0, size, size], fill=255)
    rounded = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rounded.paste(img, (0, 0), mask=mask)
    return rounded

print("Rendering master icon (1024x1024)...")
master_img = render_master_logo(1024)

# 1. Master logo PNG
logo_512 = master_img.resize((512, 512), Image.Resampling.LANCZOS)
logo_512.save(os.path.join(ASSETS_DIR, "logo.png"), "PNG")
print(f"Saved {os.path.join(ASSETS_DIR, 'logo.png')}")

# 2. Android density mipmaps
android_densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, dim in android_densities.items():
    target_dir = os.path.join(RES_DIR, folder)
    os.makedirs(target_dir, exist_ok=True)
    icon = master_img.resize((dim, dim), Image.Resampling.LANCZOS)
    icon.save(os.path.join(target_dir, "ic_launcher.png"), "PNG")

    round_icon = render_round_icon(icon)
    round_icon.save(os.path.join(target_dir, "ic_launcher_round.png"), "PNG")
    print(f"Generated Android {folder} ({dim}x{dim})")

# 3. macOS AppIcon.icns
icns_types = {
    "icp4": 16,
    "icp5": 32,
    "icp6": 64,
    "ic07": 128,
    "ic08": 256,
    "ic09": 512,
    "ic10": 1024,
}

chunks = bytearray()
for ostype, dim in icns_types.items():
    sub_img = master_img.resize((dim, dim), Image.Resampling.LANCZOS)
    buf = io.BytesIO()
    sub_img.save(buf, format="PNG")
    png_bytes = buf.getvalue()
    chunk_len = 8 + len(png_bytes)
    chunks.extend(ostype.encode("ascii") + struct.pack(">I", chunk_len) + png_bytes)

total_len = 8 + len(chunks)
icns_data = b"icns" + struct.pack(">I", total_len) + chunks

icns_path = os.path.join(MACOS_RESOURCES, "AppIcon.icns")
with open(icns_path, "wb") as f:
    f.write(icns_data)
print(f"Generated macOS AppIcon.icns ({len(icns_data)} bytes) at {icns_path}")
