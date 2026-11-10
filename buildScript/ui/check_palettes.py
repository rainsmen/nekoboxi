#!/usr/bin/env python3
"""Check actual generated theme colors, including selected cards and editor controls."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def luminance(value):
    channels = [int(value[-6:][i:i + 2], 16) / 255 for i in (0, 2, 4)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in channels]
    return sum(v * weight for v, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


minimum = (100.0, "")
count = 0
for mode in ("values", "values-night"):
    colors = {node.attrib["name"]: node.text for node in ET.parse(ROOT / "app/src/main/res" / mode / "m3-palettes.xml").getroot()}
    themes = [key.removesuffix("_primary") for key in colors if key.endswith("_primary")]
    assert len(themes) == 21, f"{mode}: expected all 21 existing theme IDs"
    for theme in themes:
        for background, foreground in (("primary", "onprimary"), ("secondary", "onsecondary"),
                                       ("surface", "onsurface"), ("surfacecontainerlow", "onsurface"),
                                       ("secondarycontainer", "onsurface"), ("error", "onerror")):
            low, high = sorted(luminance(colors[f"{theme}_{role}"]) for role in (background, foreground))
            contrast = (high + 0.05) / (low + 0.05)
            label = f"{mode}/{theme}/{background}:{foreground}"
            assert contrast >= 4.5, f"{label}: contrast {contrast:.2f}:1 is below 4.5:1"
            minimum = min(minimum, (contrast, label))
            count += 1
print(f"Checked {count} actual color pairs across 21 light/dark themes; minimum {minimum[0]:.2f}:1 ({minimum[1]}).")
