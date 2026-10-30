# SPDX-License-Identifier: Apache-2.0
"""Bounded JPEG decoding and colour/shape segmentation using NumPy and Pillow."""
import io
import math
import warnings
import time
import numpy as np
from PIL import Image, ImageDraw


def detect(data, processing_width=320, hue_degrees=24.0, min_saturation=.45,
           min_value=.15):
    if not 64 <= processing_width <= 1280:
        raise ValueError('processing_width must be 64..1280')
    if not all(math.isfinite(v) for v in (hue_degrees, min_saturation, min_value)) or not (
            1 <= hue_degrees <= 60 and 0 <= min_saturation <= 1 and 0 <= min_value <= 1):
        raise ValueError('Invalid colour thresholds')
    if len(data) > 2 * 1024 * 1024 or not data.startswith(b'\xff\xd8'):
        raise ValueError('Expected JPEG, at most 2 MiB')
    started = time.perf_counter()
    with warnings.catch_warnings():
        warnings.simplefilter('error', Image.DecompressionBombWarning)
        with Image.open(io.BytesIO(data)) as source:
            width, height = source.size
            if source.format != 'JPEG' or min(width, height) < 1 or max(width, height) > 8192 or width * height > 16_000_000:
                raise ValueError('Invalid/oversized JPEG dimensions')
            # JPEG draft downsampling occurs in the decoder before RGB allocation.
            source.draft('RGB', (processing_width, max(1, height * processing_width // width)))
            image = source.convert('RGB')
            image.thumbnail((processing_width, max(1, height * processing_width // width)))
    decoded = time.perf_counter()
    rgb = np.asarray(image, dtype=np.int16)
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    hi = rgb.max(axis=2)
    delta = hi - rgb.min(axis=2)
    mask = ((r == hi) & (delta > 0) & (hi >= 255 * min_value) &
            (delta >= hi * min_saturation) & (60 * np.abs(g - b) <= hue_degrees * delta))
    # Closing fills small seam/lettering gaps without merging all red objects.
    mask = _morph(_morph(mask, 3, True), 3, False)
    masked_at = time.perf_counter()
    circle = _circle(mask)
    fitted = time.perf_counter()
    result = dict(found=False, width=width, height=height, center_x=0., center_y=0.,
                  radius_px=0., diameter_fraction=0., error_x=0.,
                  decode_ms=(decoded-started)*1000, mask_ms=(masked_at-decoded)*1000,
                  circle_ms=(fitted-masked_at)*1000)
    masked = Image.new('RGB', image.size)
    if circle is not None:
        cx, cy, radius, support = circle
        sx, sy = width / image.width, height / image.height
        x, y, radius_px = cx * sx, cy * sy, radius * (sx + sy) / 2
        result.update(found=True, center_x=x, center_y=y, radius_px=radius_px,
                      diameter_fraction=2 * radius_px / width, error_x=2 * x / width - 1,
                      circle_support=support)
        gy, gx = np.ogrid[:image.height, :image.width]
        selected = mask & ((gx-cx)**2+(gy-cy)**2 <= radius**2)
        masked.paste(image, mask=Image.fromarray(selected.astype(np.uint8)*255))
        draw = ImageDraw.Draw(image)
        draw.ellipse((cx-radius, cy-radius, cx+radius, cy+radius), outline=(0, 255, 0), width=2)
        draw.line((cx - 6, cy, cx + 6, cy), fill=(0, 255, 0), width=2)
        draw.line((cx, cy - 6, cx, cy + 6), fill=(0, 255, 0), width=2)
    return result, image, masked


def _morph(mask, radius, dilate):
    """Separable binary max/min filter; vectorised boolean operations."""
    result = mask
    operation = np.logical_or if dilate else np.logical_and
    for axis in (0, 1):
        padding = [(0, 0), (0, 0)]
        padding[axis] = (radius, radius)
        padded = np.pad(result, padding, mode='edge')
        output = np.zeros_like(result) if dilate else np.ones_like(result)
        for offset in range(2*radius+1):
            part = (padded[offset:offset+result.shape[0], :] if axis == 0
                    else padded[:, offset:offset+result.shape[1]])
            operation(output, part, out=output)
        result = output
    return result


def _circle(mask):
    """Bounded RANSAC with vectorised scoring; no per-pixel Python loop."""
    eroded = _morph(mask, 1, False)
    yy, xx = np.nonzero(mask & ~eroded)
    if len(xx) < 20:
        return None
    rng = np.random.default_rng(42)
    if len(xx) > 1500:
        indices = rng.choice(len(xx), 1500, replace=False)
        xx, yy = xx[indices], yy[indices]
    xx, yy = xx.astype(np.float32), yy.astype(np.float32)
    points = np.stack((xx, yy), axis=1)
    triangles = points[rng.integers(0, len(points), (768, 3))]
    a, b, c = triangles[:, 0], triangles[:, 1], triangles[:, 2]
    determinant = 2 * (a[:, 0]*(b[:, 1]-c[:, 1]) + b[:, 0]*(c[:, 1]-a[:, 1]) + c[:, 0]*(a[:, 1]-b[:, 1]))
    valid = np.abs(determinant) > 1
    a, b, c, determinant = a[valid], b[valid], c[valid], determinant[valid]
    aa, bb, cc = (a*a).sum(1), (b*b).sum(1), (c*c).sum(1)
    cx = (aa*(b[:, 1]-c[:, 1]) + bb*(c[:, 1]-a[:, 1]) + cc*(a[:, 1]-b[:, 1])) / determinant
    cy = (aa*(c[:, 0]-b[:, 0]) + bb*(a[:, 0]-c[:, 0]) + cc*(b[:, 0]-a[:, 0])) / determinant
    radii = np.sqrt((a[:, 0]-cx)**2 + (a[:, 1]-cy)**2)
    h, w = mask.shape
    valid = (radii > 6) & (radii < min(w, h)*.55) & (cx >= 0) & (cx < w) & (cy >= 0) & (cy < h)
    cx, cy, radii = cx[valid], cy[valid], radii[valid]
    if not len(radii):
        return None
    scores = np.zeros(len(radii), dtype=np.float32)
    # Small batches bound temporary arrays to 64 x 1500 samples.
    for start in range(0, len(radii), 64):
        x, y, r = cx[start:start+64], cy[start:start+64], radii[start:start+64]
        squared = (xx[None, :]-x[:, None])**2 + (yy[None, :]-y[:, None])**2
        hits = (squared >= (r[:, None]-1.7)**2) & (squared <= (r[:, None]+1.7)**2)
        scores[start:start+64] = hits.sum(axis=1) / (2*np.pi*r)
    best = None
    best_score = .30
    for index in np.argsort(scores)[-24:]:
        x, y, r = cx[index], cy[index], radii[index]
        squared = (xx-x)**2 + (yy-y)**2
        inlier = (squared >= (r-1.7)**2) & (squared <= (r+1.7)**2)
        angles = np.arctan2(yy[inlier]-y, xx[inlier]-x)
        coverage = len(np.unique(np.clip(((angles+np.pi)*36/(2*np.pi)).astype(int), 0, 35))) / 36
        if coverage < .6:
            continue
        x0, x1 = max(0, int(x-r)), min(w, int(x+r+1))
        y0, y1 = max(0, int(y-r)), min(h, int(y+r+1))
        gy, gx = np.ogrid[y0:y1, x0:x1]
        inside = (gx-x)**2 + (gy-y)**2 < (r*.8)**2
        if not inside.any() or mask[y0:y1, x0:x1][inside].mean() <= .40:
            continue
        score = float(scores[index] * coverage)
        if score > best_score:
            best_score = score
            best = float(x), float(y), float(r), score
    return best


def jpeg(image):
    output = io.BytesIO()
    image.save(output, format='JPEG', quality=70)
    return output.getvalue()
