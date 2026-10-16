"""把 DXF 画成白底矢量原图。第 1 页是整图，后面按视口或图面分块分页。"""
import copy
import os
import sys
from xml.etree import ElementTree as ET

import ezdxf
from ezdxf import bbox as ezbbox
from ezdxf.addons.drawing import Frontend, RenderContext
from ezdxf.addons.drawing.config import BackgroundPolicy, ColorPolicy, Configuration
from ezdxf.addons.drawing.layout import Layout as OutputLayout
from ezdxf.addons.drawing.layout import Page, Settings, Units
from ezdxf.addons.drawing.svg import SVGBackend
from ezdxf.math import BoundingBox2d, Vec2

MAX_PAGES = 16
CONFIG = Configuration(
    background_policy=BackgroundPolicy.WHITE,
    color_policy=ColorPolicy.BLACK,
    # 单位是 1/300 英寸。过粗会把剖面和打散文字糊成黑块，放大后更看不清。
    min_lineweight=1,
)
SETTINGS = Settings(fit_page=True, crop_at_margins=True)


def paper_layouts(doc):
    found = []
    for name in doc.layout_names():
        if name == "Model":
            continue
        layout = doc.layout(name)
        if any(True for _ in layout):
            found.append(layout)
    return found


def choose_layouts(doc):
    papers = []
    for layout in paper_layouts(doc):
        if viewport_boxes(layout) or len(layout) > 20:
            papers.append(layout)
    if papers:
        return papers
    return [doc.modelspace()]


def pick_ocr_layout(doc, layouts):
    chosen = layouts[0]
    count = len(chosen)
    for layout in layouts[1:]:
        size = len(layout)
        if size > count:
            chosen = layout
            count = size
    model = doc.modelspace()
    if len(model) > count:
        return model
    return chosen


def viewport_boxes(layout):
    if not hasattr(layout, "viewports"):
        return []
    main = layout.main_viewport() if hasattr(layout, "main_viewport") else None
    boxes = []
    for vp in layout.viewports():
        if main is not None and vp is main:
            continue
        ident = vp.dxf.get("id", 0)
        if ident == 1:
            continue
        status = vp.dxf.get("status", 1)
        if status is not None and status <= 0:
            continue
        p0, p1 = vp.clipping_rect()
        x0, x1 = min(p0.x, p1.x), max(p0.x, p1.x)
        y0, y1 = min(p0.y, p1.y), max(p0.y, p1.y)
        if (x1 - x0) < 1e-6 or (y1 - y0) < 1e-6:
            continue
        boxes.append(BoundingBox2d([Vec2(x0, y0), Vec2(x1, y1)]))
    return boxes


def entity_boxes(layout):
    boxes = []
    for entity in layout:
        if entity.dxftype() == "VIEWPORT":
            continue
        try:
            box = ezbbox.extents([entity], fast=True)
        except Exception:
            continue
        if box is None or not box.has_data:
            continue
        width = float(box.size.x)
        height = float(box.size.y)
        if width <= 0 and height <= 0:
            continue
        boxes.append((float(box.extmin.x), float(box.extmin.y), float(box.extmax.x), float(box.extmax.y)))
    return boxes


def spatial_boxes(layout):
    rects = entity_boxes(layout)
    if len(rects) < 8:
        return []
    xmin = min(row[0] for row in rects)
    ymin = min(row[1] for row in rects)
    xmax = max(row[2] for row in rects)
    ymax = max(row[3] for row in rects)
    span_x = xmax - xmin
    span_y = ymax - ymin
    if span_x < 1e-6 or span_y < 1e-6:
        return []
    full = span_x * span_y
    usable = []
    for x0, y0, x1, y1 in rects:
        width = x1 - x0
        height = y1 - y0
        area = width * height
        # 外框、通长边线会把左右视图粘成一块，分页时丢掉。
        if area > full * 0.4:
            continue
        if width > span_x * 0.8 and height < span_y * 0.05:
            continue
        if height > span_y * 0.8 and width < span_x * 0.05:
            continue
        usable.append((x0, y0, x1, y1))
    if len(usable) < 8:
        return []
    xmin = min(row[0] for row in usable)
    ymin = min(row[1] for row in usable)
    xmax = max(row[2] for row in usable)
    ymax = max(row[3] for row in usable)
    regions = split_regions(usable, (xmin, ymin, xmax, ymax))
    if len(regions) < 2:
        return []
    return [BoundingBox2d([Vec2(x0, y0), Vec2(x1, y1)]) for x0, y0, x1, y1 in regions]


def split_regions(rects, box):
    """只把明显并排/叠放的图幅切开，几何裁切不互相重叠。"""
    parts = []
    queue = [(rects, box)]
    while queue:
        group, (x0, y0, x1, y1) = queue.pop(0)
        width = x1 - x0
        height = y1 - y0
        stop = (
            width < 1e-6
            or height < 1e-6
            or max(width / height, height / width) <= 2.2
            or len(parts) + len(queue) + 1 >= 4
        )
        if stop:
            parts.append((x0, y0, x1, y1))
            continue
        primary = 0 if width >= height else 1
        split = min_cut_split(group, primary) or min_cut_split(group, 1 - primary)
        if not split:
            parts.append((x0, y0, x1, y1))
            continue
        left_rows, right_rows, cut, axis = split
        if axis == 0:
            queue.append((left_rows, (x0, y0, cut, y1)))
            queue.append((right_rows, (cut, y0, x1, y1)))
        else:
            queue.append((left_rows, (x0, y0, x1, cut)))
            queue.append((right_rows, (x0, cut, x1, y1)))
    return parts


def min_cut_split(rects, axis):
    """图框贴在一起时几乎没有空隙，按穿越该缝最少的位置切开。"""
    start = axis
    end = axis + 2
    lo = min(row[start] for row in rects)
    hi = max(row[end] for row in rects)
    span = hi - lo
    if span < 1e-9:
        return None
    total = len(rects)
    min_side = max(8, int(total * 0.12))
    best = None
    steps = 80
    for step in range(1, steps):
        cut = lo + span * step / steps
        left = 0
        right = 0
        cross = 0
        for row in rects:
            if row[end] <= cut:
                left += 1
            elif row[start] >= cut:
                right += 1
            else:
                cross += 1
        if left < min_side or right < min_side:
            continue
        if cross > 12 and cross > 0.001 * min(left, right):
            continue
        score = (cross, abs(left - right))
        if best is None or score < best[0]:
            best = (score, cut)
    if best is None:
        return None
    cut = best[1]
    left_rows = [row for row in rects if (row[start] + row[end]) / 2 < cut]
    right_rows = [row for row in rects if (row[start] + row[end]) / 2 >= cut]
    if len(left_rows) < 3 or len(right_rows) < 3:
        return None
    left_span = max(row[end] for row in left_rows) - min(row[start] for row in left_rows)
    right_span = max(row[end] for row in right_rows) - min(row[start] for row in right_rows)
    if left_span < span * 0.28 or right_span < span * 0.28:
        return None
    return left_rows, right_rows, cut, axis


def useful_crop(full, box):
    if full is None or not full.has_data or box is None or not box.has_data:
        return False
    full_area = float(full.size.x) * float(full.size.y)
    box_area = float(box.size.x) * float(box.size.y)
    if full_area < 1e-9 or box_area < 1e-9:
        return False
    if box_area < full_area * 0.18:
        return False
    if box_area > full_area * 0.82:
        return False
    return True


def page_for(box):
    width = max(float(box.size.x), 1e-6)
    height = max(float(box.size.y), 1e-6)
    if width >= height:
        return Page(420, max(420 * height / width, 1), Units.mm)
    return Page(max(297 * width / height, 1), 297, Units.mm)


def write_svg(recorder, target, page, render_box=None):
    player = copy.copy(recorder.player())
    if render_box is None:
        render_box = player.bbox()
    if render_box is None or not render_box.has_data:
        return False
    grown = BoundingBox2d([render_box.extmin, render_box.extmax])
    pad = max(float(grown.size.x), float(grown.size.y)) * 0.02
    if pad > 0:
        try:
            grown.grow(pad)
        except ValueError:
            pass
    output_layout = OutputLayout(grown, flip_y=True)
    page = output_layout.get_final_page(page, SETTINGS)
    if page.width == 0 or page.height == 0:
        return False
    matrix = output_layout.get_placement_matrix(page, settings=SETTINGS, top_origin=True)
    player.transform(matrix)
    try:
        p1, p2 = page.get_margin_rect(top_origin=True)
        output_scale = SETTINGS.page_output_scale_factor(page)
        player.crop_rect(p1 * output_scale, p2 * output_scale, 0.1 * output_scale)
    except Exception:
        pass
    backend = recorder.make_backend(page, SETTINGS)
    player.replay(backend)
    xml = backend.get_xml_root_element()
    text = ET.tostring(xml, encoding="unicode", xml_declaration=True)
    if "<svg" not in text:
        return False
    # 线宽跟屏幕走，不跟放大倍数走，剖面和打散文字才分得开。
    if "</style>" in text:
        text = text.replace(
            "</style>",
            " .C1, path { vector-effect: non-scaling-stroke; stroke-width: 1px; }</style>",
            1,
        )
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(text)
    return True


def record_layout(doc, layout):
    backend = SVGBackend()
    Frontend(RenderContext(doc), backend, config=CONFIG).draw_layout(layout)
    return backend


def extra_boxes(layout):
    boxes = viewport_boxes(layout)
    if not boxes:
        boxes = spatial_boxes(layout)
    return sorted(boxes, key=lambda box: (-float(box.extmax.y), float(box.extmin.x)))


def render_pages(doc, out_dir, first_only=False):
    layouts = choose_layouts(doc)
    pages = []
    for layout in layouts:
        if len(pages) >= MAX_PAGES:
            break
        recorder = record_layout(doc, layout)
        full = recorder.player().bbox()
        page_path = os.path.join(out_dir, f"page-{len(pages) + 1:02d}.svg")
        if write_svg(recorder, page_path, Page(420, 297, Units.mm), None):
            pages.append(page_path)
        if first_only:
            continue
        for box in extra_boxes(layout):
            if len(pages) >= MAX_PAGES:
                break
            if not useful_crop(full, box):
                continue
            crop_path = os.path.join(out_dir, f"page-{len(pages) + 1:02d}.svg")
            if write_svg(recorder, crop_path, page_for(box), box):
                pages.append(crop_path)
    return pages


def write_png(layout, target, dpi=120):
    from ezdxf.addons.drawing.matplotlib import qsave

    # 白底 + COLOR 会让 ACI 7（填入白字/点检白线）看不见；用深底保留绿框与白字（对齐 CAD）
    config = Configuration(
        background_policy=BackgroundPolicy.BLACK,
        color_policy=ColorPolicy.COLOR,
        # 高 DPI 用更细线，避免放大后糊成黑块
        min_lineweight=0.15 if dpi >= 300 else (0.25 if dpi >= 200 else 0.35),
    )
    # 画幅英寸加大：终稿预览需要更清晰，长边像素 ≈ inches * dpi
    if dpi >= 360:
        inches = 24
    elif dpi >= 300:
        inches = 22
    elif dpi >= 200:
        inches = 20
    else:
        inches = 12
    # 略加深底色，减轻灰字发灰
    qsave(layout, target, bg="#050505", dpi=dpi, size_inches=(inches, 0), config=config)
    # A：渲后轻度对比/亮度增强，不改变图元内容，便于 OCR 与对图
    try:
        from PIL import Image, ImageEnhance

        im = Image.open(target).convert("RGB")
        im = ImageEnhance.Contrast(im).enhance(1.32)
        im = ImageEnhance.Brightness(im).enhance(1.1)
        im.save(target, format="PNG", optimize=True)
    except Exception:
        pass


def main():
    source = sys.argv[1]
    out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    doc = ezdxf.readfile(source)
    first_only = len(sys.argv) > 5 and sys.argv[5] in ("1", "first")
    pages = render_pages(doc, out_dir, first_only)
    if not pages:
        sys.exit(1)
    if len(sys.argv) > 3:
        try:
            dpi = 120
            if len(sys.argv) > 4:
                dpi = max(72, min(480, int(sys.argv[4])))
            write_png(pick_ocr_layout(doc, choose_layouts(doc)), sys.argv[3], dpi)
        except Exception:
            pass


if __name__ == "__main__":
    main()
