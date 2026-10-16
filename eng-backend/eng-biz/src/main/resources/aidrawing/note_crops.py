"""按给定窗口把 DXF 局部放大成白底 PNG。每个窗口一张图。"""
import json
import os
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import ezdxf
from ezdxf.addons.drawing import Frontend, RenderContext
from ezdxf.addons.drawing.config import BackgroundPolicy, ColorPolicy, Configuration
from ezdxf.addons.drawing.matplotlib import MatplotlibBackend

CONFIG = Configuration(
    background_policy=BackgroundPolicy.WHITE,
    color_policy=ColorPolicy.BLACK,
    min_lineweight=0.25,
)


def pick_layout(doc):
    papers = []
    for layout in doc.layouts:
        if layout.name == "Model":
            continue
        if len(layout) > 20:
            papers.append(layout)
    model = doc.modelspace()
    chosen = papers[0] if papers else model
    for layout in papers[1:]:
        if len(layout) > len(chosen):
            chosen = layout
    if len(model) >= len(chosen):
        return model
    return chosen


def save_windows(doc, layout, boxes, kind, out_dir, written):
    if not boxes:
        return
    fig = plt.figure()
    ax = fig.add_axes((0, 0, 1, 1))
    backend = MatplotlibBackend(ax, adjust_figure=False)
    Frontend(RenderContext(doc), backend, config=CONFIG).draw_layout(layout, finalize=True)
    for index, box in enumerate(boxes):
        target = os.path.join(out_dir, f"{kind[:-1]}-{index}.png")
        try:
            x0, y0, x1, y1 = box["x0"], box["y0"], box["x1"], box["y1"]
            width = max(x1 - x0, 0.2)
            height = max(y1 - y0, 0.2)
            pixels = min(3200.0 / width, 280.0 / height)
            pixels = max(pixels, 6.0)
            dpi = max(72.0, min(500.0, pixels * 25.4))
            fig.set_size_inches(width / 25.4, height / 25.4)
            ax.set_xlim(x0, x1)
            ax.set_ylim(y0, y1)
            ax.axis("off")
            fig.savefig(target, dpi=dpi, facecolor="white")
        except Exception:
            continue
        if os.path.isfile(target) and os.path.getsize(target) > 32:
            written.append({"kind": kind, "index": index, "file": target})
    plt.close(fig)


def main():
    doc = ezdxf.readfile(sys.argv[1])
    spec = json.load(open(sys.argv[2], encoding="utf-8"))
    out_dir = sys.argv[3]
    os.makedirs(out_dir, exist_ok=True)
    layout = pick_layout(doc)
    written = []
    save_windows(doc, layout, spec.get("notes") or [], "notes", out_dir, written)
    save_windows(doc, layout, spec.get("callouts") or [], "callouts", out_dir, written)
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as handle:
        json.dump(written, handle)


if __name__ == "__main__":
    main()
