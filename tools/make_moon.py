"""月の写真（NASA 3D Resources の Images and Textures/Moon/Moon.jpg）を、
表側（経度 -90°〜90°）の正距円筒図法 2048x2048 に変換する。

使い方: python3 tools/make_moon.py Moon.jpg app/src/main/assets/space/moon.jpg
"""
import sys
import numpy as np
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
im = np.asarray(Image.open(src).convert("RGB")).astype(np.float32)
# 写真の中の月の円（明るい部分の外接範囲から求めた値）
cx, cy, R = 1014.0, 960.0, 763.0 * 0.99
W = H = 2048
lon = (np.arange(W) + 0.5) / W * np.pi - np.pi / 2
lat = np.pi / 2 - (np.arange(H) + 0.5) / H * np.pi
LON, LAT = np.meshgrid(lon, lat)
x = cx + R * np.cos(LAT) * np.sin(LON)
y = cy - R * np.sin(LAT)
xi = np.clip(x, 0, im.shape[1] - 2)
yi = np.clip(y, 0, im.shape[0] - 2)
x0 = xi.astype(int)
y0 = yi.astype(int)
fx = (xi - x0)[..., None]
fy = (yi - y0)[..., None]
c = (im[y0, x0] * (1 - fx) * (1 - fy) + im[y0, x0 + 1] * fx * (1 - fy)
     + im[y0 + 1, x0] * (1 - fx) * fy + im[y0 + 1, x0 + 1] * fx * fy)
# 写真の周辺減光を弱める
mu = np.clip(np.cos(LAT) * np.cos(LON), 0.25, 1)[..., None]
c = c / (0.7 + 0.3 * mu)
c = c / np.percentile(c, 99.7) * 240
Image.fromarray(np.clip(c, 0, 255).astype(np.uint8)).save(dst, quality=88, optimize=True)
