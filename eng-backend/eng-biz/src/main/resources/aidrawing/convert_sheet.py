"""在 ODA DXF 上原位改 NOTES、加材料表。不炸开、不把视图折成线段。"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import ezdxf
from ezdxf import recover

NOTE_LINE = re.compile(
    r"^\s*(?:\[(\d{1,3})\]|(\d{1,3})\s*[.)、．])\s*(.+)$",
    re.I,
)
NOTE_BREAK = re.compile(r"(?<!\n)(?=\[\d{1,3}\])")
NOTES_HEADER = re.compile(r"^\s*NOTES?\s*[:：]?\s*$", re.I)
LAYER = "FACTORY_MAT"
FONT_CANDIDATES = [
    "/System/Library/Fonts/STHeiti Light.ttc",
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "/Library/Fonts/Arial Unicode.ttf",
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansSC-Regular.otf",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
]


def clip(text: str, limit: int = 400) -> str:
    value = "" if text is None else str(text)
    if len(value) > limit:
        return value[:limit]
    return value


def layouts(doc):
    found = [doc.modelspace()]
    try:
        names = list(doc.layout_names_in_taborder())
    except Exception:
        names = list(doc.layout_names())
    for name in names:
        if name == "Model":
            continue
        try:
            found.append(doc.layout(name))
        except Exception:
            continue
    return found


def entity_plain(entity) -> str:
    kind = entity.dxftype()
    try:
        if kind == "MTEXT":
            return entity.plain_text()
        if kind in ("TEXT", "ATTRIB", "ATTDEF"):
            return str(entity.dxf.get("text", "") or "")
    except Exception:
        return ""
    return ""


def set_text(entity, value: str) -> None:
    kind = entity.dxftype()
    text = "" if value is None else str(value).replace("\r\n", "\n").replace("\r", "\n")
    if kind == "MTEXT":
        entity.text = text.replace("\n", "\\P")
        try:
            entity.dxf.style = "FACTORY_CN"
        except Exception:
            pass
        return
    if kind in ("TEXT", "ATTRIB", "ATTDEF"):
        entity.dxf.text = text.replace("\n", " ")[:255]
        try:
            entity.dxf.style = "FACTORY_CN"
        except Exception:
            pass


def rewrite_block(plain: str, by_no: dict) -> tuple[str, bool]:
    """整段说明按编号替换。命中的条目用核对后的中文，并吃掉该条续行。"""
    text = "" if plain is None else str(plain).replace("\\P", "\n").replace("\r", "\n")
    text = NOTE_BREAK.sub("\n", text)
    lines = text.splitlines() or [text]
    out = []
    changed = False
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        match = NOTE_LINE.match(line)
        no = ""
        if match:
            no = match.group(1) or match.group(2) or ""
        if no and no in by_no:
            out.append(by_no[no])
            changed = True
            i += 1
            while i < len(lines) and not NOTE_LINE.match(lines[i].strip()):
                i += 1
            continue
        out.append(lines[i])
        i += 1
    return "\n".join(out), changed


def pick_font(warnings: list[str]) -> str:
    for path in FONT_CANDIDATES:
        if Path(path).is_file():
            return Path(path).name
    warnings.append("本机未找到中文字体，CAD 打开后中文可能显示为问号")
    return "txt.shx"


def ensure_style(doc, font_name: str) -> None:
    try:
        if "FACTORY_CN" in doc.styles:
            style = doc.styles.get("FACTORY_CN")
            style.dxf.font = font_name
            return
        doc.styles.new("FACTORY_CN", dxfattribs={"font": font_name})
    except Exception:
        pass


def replace_notes(doc, notes: list, warnings: list[str]) -> int:
    by_no = {}
    for row in notes or []:
        no = str(row.get("noteNo") or "").strip()
        translated = row.get("translated")
        if not no or translated is None:
            continue
        by_no[no] = str(translated)
    replaced = 0
    header_done = False
    for layout in layouts(doc):
        for entity in layout:
            kind = entity.dxftype()
            if kind not in ("TEXT", "MTEXT", "ATTRIB", "ATTDEF"):
                continue
            plain = entity_plain(entity).replace("\r", "\n").strip()
            if not plain:
                continue
            first = plain.split("\n", 1)[0].strip()
            if not header_done and NOTES_HEADER.match(first) and "\n" not in plain:
                set_text(entity, "说明")
                header_done = True
                replaced += 1
                continue
            rewritten, changed = rewrite_block(plain, by_no)
            if not changed:
                continue
            set_text(entity, rewritten)
            replaced += 1
    if notes and replaced == 0:
        warnings.append("未改到说明文字实体，请在 CAD 里核对 NOTES 图层")
    return replaced


def notes_anchor(job: dict):
    box = job.get("notesBox") or {}
    try:
        return {
            "minx": float(box.get("minx")),
            "miny": float(box.get("miny")),
            "maxx": float(box.get("maxx")),
            "maxy": float(box.get("maxy")),
        }
    except (TypeError, ValueError):
        return None


def pick_layout_for_table(doc):
    papers = []
    for layout in layouts(doc)[1:]:
        n = 0
        try:
            n = len(layout)
        except Exception:
            n = 0
        if n > 10:
            papers.append(layout)
    if papers:
        return papers[0]
    return doc.modelspace()


def layout_bounds(layout):
    minx = miny = float("inf")
    maxx = maxy = float("-inf")
    count = 0
    try:
        entities = list(layout)
    except Exception:
        entities = []
    for entity in entities:
        kind = ""
        try:
            kind = entity.dxftype()
        except Exception:
            continue
        pts = []
        try:
            if kind == "LINE":
                pts = [entity.dxf.start, entity.dxf.end]
            elif kind == "CIRCLE":
                c = entity.dxf.center
                r = float(entity.dxf.radius)
                pts = [(c.x - r, c.y - r), (c.x + r, c.y + r)]
            elif kind in ("LWPOLYLINE", "POLYLINE"):
                pts = list(entity.vertices()) if kind == "POLYLINE" else list(entity.get_points("xy"))
        except Exception:
            pts = []
        for pt in pts:
            try:
                x = float(pt[0] if not hasattr(pt, "x") else pt.x)
                y = float(pt[1] if not hasattr(pt, "y") else pt.y)
            except Exception:
                continue
            minx, miny = min(minx, x), min(miny, y)
            maxx, maxy = max(maxx, x), max(maxy, y)
            count += 1
            if count > 80000:
                break
        if count > 80000:
            break
    if count >= 2 and maxx - minx > 1 and maxy - miny > 1:
        return minx, miny, maxx, maxy
    try:
        ext = layout.extents()
        minx = float(ext.extmin.x)
        miny = float(ext.extmin.y)
        maxx = float(ext.extmax.x)
        maxy = float(ext.extmax.y)
        if maxx - minx < 1 or maxy - miny < 1:
            return None
        return minx, miny, maxx, maxy
    except Exception:
        return None


def draw_overlay(doc, job, warnings) -> bool:
    """说明是线条字时，只在原位置写中文，不铺色块。预览会把白底画成黑色。"""
    text = str(job.get("overlayText") or "").strip()
    norm = job.get("notesNorm") or {}
    if not text or not isinstance(norm, dict) or "x" not in norm:
        return False
    layout = pick_layout_for_table(doc)
    bounds = layout_bounds(layout)
    if not bounds:
        warnings.append("未能量到图幅，中文说明未盖到原位置")
        return False
    minx, miny, maxx, maxy = bounds
    sheet_w = maxx - minx
    sheet_h = maxy - miny
    try:
        nx = float(norm.get("x"))
        ny = float(norm.get("y"))
        nw = float(norm.get("w"))
        nh = float(norm.get("h"))
    except (TypeError, ValueError):
        return False
    x = minx + max(0.0, nx) * sheet_w
    w = min(max(nw * sheet_w, sheet_w * 0.2), sheet_w * 0.5)
    h = min(max(nh * sheet_h, sheet_h * 0.15), sheet_h * 0.72)
    y = maxy - (max(0.0, ny) + nh) * sheet_h
    if y < miny:
        y = miny + sheet_h * 0.04
    if x + w > maxx:
        x = maxx - w
    char_h = max(min(h / 36.0, sheet_h * 0.012), sheet_h * 0.007)
    try:
        mtext = layout.add_mtext(
            text.replace("\n", "\\P"),
            dxfattribs={
                "layer": LAYER,
                "style": "FACTORY_CN",
                "char_height": char_h,
                "color": 7,
            },
        )
        mtext.dxf.true_color = 0x000000
        mtext.set_location((x + char_h * 0.6, y + h - char_h * 1.2))
        mtext.dxf.width = max(w * 0.94, char_h * 8)
        return True
    except Exception:
        warnings.append("中文说明未写入")
        return False


def draw_table(doc, materials: list, job: dict, warnings: list[str]) -> bool:
    rows = materials or []
    rows = [row for row in rows if str(row.get("source") or "") != "unlabeled" and str(row.get("materialZh") or "") != "未标注"]
    if not rows:
        warnings.append("没有认到 MATERIAL 行，未加材料表")
        return False
    layout = pick_layout_for_table(doc)
    bounds = layout_bounds(layout)
    if bounds:
        minx, miny, maxx, maxy = bounds
        sheet_w = maxx - minx
        sheet_h = maxy - miny
    else:
        minx, miny, maxx, maxy = 0.0, 0.0, 420.0, 297.0
        sheet_w, sheet_h = maxx - minx, maxy - miny
        warnings.append("未量到图幅，材料表按 A3 比例绘制")
    row_h = max(sheet_h * 0.022, 4.0)
    table_w = min(sheet_w * 0.48, sheet_w * 0.55)
    col_ratio = [0.10, 0.16, 0.36, 0.16, 0.22]
    col_w = [table_w * r for r in col_ratio]
    headers = ["编号", "名称", "料号", "颜色", "厚度"]
    table_h = row_h * (len(rows) + 2)
    x = minx + sheet_w * 0.04
    y = miny + sheet_h * 0.05
    if x + table_w > maxx - sheet_w * 0.02:
        table_w = maxx - x - sheet_w * 0.02
        col_w = [table_w * r for r in col_ratio]
    text_h = max(min(row_h * 0.42, sheet_h * 0.012), sheet_h * 0.007)
    title = "材料表"
    try:
        layout.add_lwpolyline(
            [(x, y), (x + table_w, y), (x + table_w, y + table_h), (x, y + table_h), (x, y)],
            close=True,
            dxfattribs={"layer": LAYER, "color": 7, "true_color": 0x000000},
        )
    except Exception:
        warnings.append("材料表边框未写入")
        return False
    yy = y + table_h - row_h * 0.75
    add_text(layout, x + text_h * 0.4, yy, title, text_h)
    yy -= row_h
    draw_row(layout, x, yy - row_h * 0.15, col_w, row_h, headers, text_h)
    yy -= row_h
    for row in rows:
        cells = [
            str(row.get("noteNo") or ""),
            str(row.get("role") or row.get("materialZh") or ""),
            clip(row.get("partNo") or row.get("materialEn") or "", 64),
            clip(row.get("color") or "未标注", 24),
            clip(row.get("thickness") or "未标注", 28),
        ]
        draw_row(layout, x, yy - row_h * 0.15, col_w, row_h, cells, text_h * 0.92)
        yy -= row_h
        if yy < y + row_h * 0.2:
            warnings.append("材料表行过多，已截断")
            break
    return True


def draw_row(layout, x, y, col_w, row_h, cells, text_h):
    cx = x
    for i, width in enumerate(col_w):
        try:
            layout.add_lwpolyline(
                [(cx, y), (cx + width, y), (cx + width, y + row_h), (cx, y + row_h), (cx, y)],
                close=True,
                dxfattribs={"layer": LAYER, "color": 7, "true_color": 0x000000},
            )
        except Exception:
            pass
        text = cells[i] if i < len(cells) else ""
        add_text(layout, cx + text_h * 0.35, y + row_h * 0.28, text, text_h)
        cx += width


def add_text(layout, x, y, value, height):
    try:
        entity = layout.add_text(
            clip(value, 80),
            dxfattribs={
                "layer": LAYER,
                "style": "FACTORY_CN",
                "height": height,
                "color": 7,
                "true_color": 0x000000,
            },
        )
        entity.dxf.insert = (x, y)
    except Exception:
        try:
            mtext = layout.add_mtext(clip(value, 80), dxfattribs={"layer": LAYER, "color": 7})
            mtext.set_location((x, y))
            mtext.dxf.char_height = height
        except Exception:
            pass


def ensure_layer(doc):
    try:
        if LAYER not in doc.layers:
            doc.layers.add(LAYER, color=4)
    except Exception:
        pass


def blank(value) -> str:
    text = "" if value is None else str(value).strip()
    if text in ("未标注", "—", "-"):
        return ""
    return text


def text_width_approx(text: str, height: float) -> float:
    w = 0.0
    for ch in text or "":
        if ord(ch) > 127:
            w += height * 1.05
        else:
            w += height * 0.62
    return max(w, height * 0.5)


def wrap_cell_lines(text: str, height: float, max_w: float, max_lines: int = 4) -> list[str]:
    raw = (text or "").strip()
    if not raw:
        return [""]
    if max_w <= height:
        return [raw[: max(1, int(max_w / max(height * 0.5, 0.1)))]]
    lines: list[str] = []
    buf = ""
    for ch in raw:
        trial = buf + ch
        if text_width_approx(trial, height) <= max_w:
            buf = trial
            continue
        if buf:
            lines.append(buf)
            buf = ch
        else:
            lines.append(ch)
            buf = ""
        if len(lines) >= max_lines:
            break
    if buf and len(lines) < max_lines:
        lines.append(buf)
    elif buf and lines:
        last = lines[-1]
        while last and text_width_approx(last + "…", height) > max_w:
            last = last[:-1]
        lines[-1] = (last or lines[-1][:1]) + "…"
    return lines or [raw]


def fit_cell_text(text: str, height: float, max_w: float) -> tuple[list[str], float]:
    """换行优先；仍超宽则缩字。"""
    lines = wrap_cell_lines(text, height, max_w, max_lines=4)
    if len(lines) == 1 and text_width_approx(lines[0], height) > max_w:
        shrink = height
        while shrink > 1.0 and text_width_approx(text, shrink) > max_w:
            shrink -= 0.15
        if text_width_approx(text, shrink) <= max_w:
            return [text], shrink
        return wrap_cell_lines(text, shrink, max_w, max_lines=4), shrink
    return lines, height


# 模版：框线/表头字绿色；填入信息白色
LINE = 3
LABEL = 3
FILL = 7
TEXT = 7


def frame_layer(doc):
    for name, color in (("FRAME", LINE), ("CUSTOMER", 7)):
        try:
            if name not in doc.layers:
                doc.layers.add(name, color=color)
        except Exception:
            pass


def box(msp, x, y, w, h):
    msp.add_lwpolyline(
        [(x, y), (x + w, y), (x + w, y + h), (x, y + h)],
        close=True,
        dxfattribs={"layer": "FRAME", "color": LINE},
    )


def hline(msp, x0, x1, y):
    msp.add_line((x0, y), (x1, y), dxfattribs={"layer": "FRAME", "color": LINE})


def vline(msp, x, y0, y1):
    msp.add_line((x, y0), (x, y1), dxfattribs={"layer": "FRAME", "color": LINE})


def cell_text(msp, x, y, w, h, value, height=2.2, color=FILL):
    """格内文字：自动换行或缩字，不穿出格宽。"""
    text = blank(value)
    if not text:
        return
    inner_w = max(w - 1.6, height * 2)
    lines, use_h = fit_cell_text(text, height, inner_w)
    if len(lines) == 1:
        entity = msp.add_text(
            lines[0],
            dxfattribs={"layer": "FRAME", "height": use_h, "color": color, "style": "FACTORY_CN"},
        )
        entity.dxf.insert = (x + 0.8, y + max(0.4, (h - use_h) / 2))
        return
    body = "\\P".join(lines)
    try:
        mtext = msp.add_mtext(
            body,
            dxfattribs={
                "layer": "FRAME",
                "style": "FACTORY_CN",
                "char_height": use_h,
                "color": color,
                "attachment_point": 1,
            },
        )
        mtext.set_location((x + 0.8, y + h - 0.6))
        mtext.dxf.width = inner_w
    except Exception:
        entity = msp.add_text(
            clip(" ".join(lines), 80),
            dxfattribs={"layer": "FRAME", "height": use_h, "color": color, "style": "FACTORY_CN"},
        )
        entity.dxf.insert = (x + 0.8, y + max(0.4, (h - use_h) / 2))


def closed_grid(msp, x, y, col_w, row_h, n_rows):
    """完整闭合表格：外边框 + 全部竖线横线。等行高。"""
    width = sum(col_w)
    height = row_h * max(1, n_rows)
    box(msp, x, y, width, height)
    cx = x
    for w in col_w[:-1]:
        cx += w
        vline(msp, cx, y, y + height)
    for i in range(1, n_rows):
        hline(msp, x, x + width, y + row_h * i)
    return width, height


def closed_grid_var(msp, x, y, col_w, row_heights):
    """可变行高闭合表格。"""
    width = sum(col_w)
    height = sum(row_heights)
    box(msp, x, y, width, height)
    cx = x
    for w in col_w[:-1]:
        cx += w
        vline(msp, cx, y, y + height)
    yy = y
    for rh in row_heights[:-1]:
        yy += rh
        hline(msp, x, x + width, yy)
    return width, height


def sheet_size_label(sheet_w, sheet_h) -> str:
    """按幅面长边粗分 ISO 号（转换图单位近似 mm）。含 A2；与 page_sheet_size 地板对齐。"""
    long_edge = max(float(sheet_w), float(sheet_h))
    if long_edge >= 1100:
        return "A0"
    if long_edge >= 800:
        return "A1"
    if long_edge >= 590:
        return "A2"
    if long_edge >= 400:
        return "A3"
    return "A4"


def draw_inward_triangle(msp, cx, cy, pointing, size=2.2):
    """图框对中三角：指向图内。"""
    s = max(1.2, float(size))
    if pointing == "down":
        pts = [(cx, cy - s), (cx - s * 0.7, cy + s * 0.4), (cx + s * 0.7, cy + s * 0.4)]
    elif pointing == "up":
        pts = [(cx, cy + s), (cx - s * 0.7, cy - s * 0.4), (cx + s * 0.7, cy - s * 0.4)]
    elif pointing == "right":
        pts = [(cx + s, cy), (cx - s * 0.4, cy - s * 0.7), (cx - s * 0.4, cy + s * 0.7)]
    else:
        pts = [(cx - s, cy), (cx + s * 0.4, cy - s * 0.7), (cx + s * 0.4, cy + s * 0.7)]
    msp.add_lwpolyline(pts, close=True, dxfattribs={"layer": "FRAME", "color": LINE})


def draw_zone_chrome(msp, ox, oy, sheet_w, sheet_h, margin):
    """双框之间的区号 1–8 / A–F + 四边对中三角（贴公司模版）。"""
    m = max(4.0, float(margin))
    ix0, iy0 = ox + m, oy + m
    ix1, iy1 = ox + sheet_w - m, oy + sheet_h - m
    inner_w = max(ix1 - ix0, 1.0)
    inner_h = max(iy1 - iy0, 1.0)
    n_cols, n_rows = 8, 6
    col_w = inner_w / n_cols
    row_h = inner_h / n_rows
    # 顶：左→右 8…1；底同序
    for i in range(n_cols):
        num = str(n_cols - i)
        cx = ix0 + col_w * (i + 0.5)
        cell_text(msp, cx - col_w * 0.35, iy1, col_w * 0.7, m * 0.85, num, min(3.2, m * 0.45), color=LABEL)
        cell_text(msp, cx - col_w * 0.35, oy, col_w * 0.7, m * 0.85, num, min(3.2, m * 0.45), color=LABEL)
        if i > 0:
            vline(msp, ix0 + col_w * i, iy1, oy + sheet_h)
            vline(msp, ix0 + col_w * i, oy, iy0)
    # 左/右：下→上 A…F
    letters = "ABCDEF"
    for i in range(n_rows):
        letter = letters[i]
        cy = iy0 + row_h * (i + 0.5)
        cell_text(msp, ox, cy - m * 0.35, m * 0.9, m * 0.7, letter, min(3.2, m * 0.45), color=LABEL)
        cell_text(msp, ix1 + m * 0.05, cy - m * 0.35, m * 0.9, m * 0.7, letter, min(3.2, m * 0.45), color=LABEL)
        if i > 0:
            hline(msp, ox, ix0, iy0 + row_h * i)
            hline(msp, ix1, ox + sheet_w, iy0 + row_h * i)
    # 对中三角（顶/底/左/右中点，指向图内）
    mid_x = (ix0 + ix1) * 0.5
    mid_y = (iy0 + iy1) * 0.5
    tri = min(2.4, m * 0.32)
    draw_inward_triangle(msp, mid_x, (iy1 + oy + sheet_h) * 0.5, "down", tri)
    draw_inward_triangle(msp, mid_x, (oy + iy0) * 0.5, "up", tri)
    draw_inward_triangle(msp, (ox + ix0) * 0.5, mid_y, "right", tri)
    draw_inward_triangle(msp, (ix1 + ox + sheet_w) * 0.5, mid_y, "left", tri)


def draw_tooling_block(msp, x, y, w, h):
    """左上 Tooling / 保密等级：绿标签，白值空着；贴内框左上角。"""
    row_h = h / 2.0
    col_w = [w * 0.48, w * 0.52]
    closed_grid(msp, x, y, col_w, row_h, 2)
    cell_text(msp, x, y + row_h, col_w[0], row_h, "Tooling No", 2.6, color=LABEL)
    cell_text(msp, x, y, col_w[0], row_h, "保密等级", 2.6, color=LABEL)


CHECKLIST_ITEMS = [
    "使用的客户图版本与需求版本是否一致?",
    "内外品名是否一致?",
    "左下角主材信息栏是否与客户图材料代码要求的一致?(料号/厚度/颜色)",
    "尺寸图与客户原图是否一致?是否增加套切尺寸管控?胶面朝向描述是否正确?",
    "层次图结构与主视图结构是否一致?特殊信息是否备注?(胶面朝向/材料颜色/分层结构)",
    "排版图与文字要求的条规&包规数量是否一致?与PCC是否一致?",
    "客户图技术要求是否满足?(颜色/抗静电要求/主材性能参数等)",
    "DFM回复是否有放入工程图参考?",
]


def draw_checklist(msp, x, y, w, h):
    """点检项（图框外）：白线白字；「是否点检」列空着。"""
    white = FILL  # ACI 7
    title_h = min(8.0, h * 0.1)
    body_h = max(h - title_h, 20.0)
    cell_text(msp, x, y + body_h, w, title_h, "点检项", 3.4, color=white)
    n = 1 + len(CHECKLIST_ITEMS)
    row_h = body_h / n
    col_w = [w * 0.82, w * 0.18]
    # 白线闭合表（不用绿框 LINE）
    width = sum(col_w)
    height = row_h * n
    msp.add_lwpolyline(
        [(x, y), (x + width, y), (x + width, y + height), (x, y + height)],
        close=True,
        dxfattribs={"layer": "FRAME", "color": white},
    )
    cx = x
    for cw in col_w[:-1]:
        cx += cw
        msp.add_line((cx, y), (cx, y + height), dxfattribs={"layer": "FRAME", "color": white})
    for i in range(1, n):
        yy = y + row_h * i
        msp.add_line((x, yy), (x + width, yy), dxfattribs={"layer": "FRAME", "color": white})
    hy = y + body_h - row_h
    cell_text(msp, x, hy, col_w[0], row_h, "工程图点检项", 2.4, color=white)
    cell_text(msp, x + col_w[0], hy, col_w[1], row_h, "是否点检", 2.2, color=white)
    for i, item in enumerate(CHECKLIST_ITEMS):
        yy = y + body_h - row_h * (i + 2)
        cell_text(msp, x, yy, col_w[0], row_h, f"{i + 1}. {item}", 1.9, color=white)


def place_customer(src, target, window, warnings, *, one_to_one=False):
    """
    把客户图放到窗口，不画绿框。返回实际占用 (宽, 高)。
    one_to_one：尽量 1:1（窗口宽高仅作上限）；插入点为窗口左下角，不再竖直居中。
    """
    from ezdxf.addons.importer import Importer

    space = src.modelspace()
    bounds = layout_bounds(space)
    if not bounds:
        for layout in layouts(src)[1:]:
            bounds = layout_bounds(layout)
            if bounds:
                space = layout
                break
    if not bounds:
        warnings.append("客户图没有读出图元，原图区留空")
        return 0.0, 0.0
    block_name = "CUSTOMER"
    if block_name in target.blocks:
        block_name = "CUSTOMER_VIEW"
    block = target.blocks.new(block_name)
    importer = Importer(src, target)
    importer.import_entities(space, block)
    importer.finalize()
    minx, miny, maxx, maxy = bounds
    bw = max(maxx - minx, 1e-6)
    bh = max(maxy - miny, 1e-6)
    wx, wy, ww, wh = window
    if ww < 40 or wh < 40:
        warnings.append("客户原图区域过小")
        return 0.0, 0.0
    if one_to_one:
        cap = min(ww / bw, wh / bh)
        scale = 1.0 if cap >= 1.0 else cap * 0.96
        used_w = bw * scale
        used_h = bh * scale
        # 底边对齐窗口底：与下方转换图在 Y 上分开，避免叠到客户一行视图上
        ix = wx - minx * scale
        iy = wy - miny * scale
    else:
        scale = min(ww / bw, wh / bh) * 0.96
        used_w = bw * scale
        used_h = bh * scale
        ix = wx - minx * scale
        iy = wy + max(0.0, (wh - used_h) / 2) - miny * scale
    target.modelspace().add_blockref(
        block_name,
        (ix, iy),
        dxfattribs={"xscale": scale, "yscale": scale, "layer": "CUSTOMER"},
    )
    return used_w, used_h


def material_rows(job):
    """材料表行：优先审校结果（dwg/stack 等）；兼容抽取 source=customer_note。"""
    rows = []
    for row in job.get("materials") or []:
        src = str(row.get("source") or "")
        if src == "unlabeled":
            continue
        name = blank(row.get("materialEn")) or blank(row.get("materialZh"))
        if not name:
            continue
        no = blank(row.get("noteNo")) or str(row.get("seq") or len(rows) + 1)
        thick = blank(row.get("thickness"))
        color = blank(row.get("color"))
        rows.append((no, clip(name, 120), clip(thick, 24), clip(color, 20)))
    # 按编号从小到大，表头在下时从下往上画
    def note_key(item):
        try:
            return int(item[0])
        except Exception:
            return 999

    rows.sort(key=note_key)
    return rows


def strip_leading_note_no(text: str, no: str = "") -> str:
    """去掉译文开头已有序号，避免合成时变成「1. 1. …」。可连剥多层重复前缀。"""
    t = (text or "").strip()
    if not t:
        return t
    specific = (
        re.compile(rf"^\s*(?:\[\s*{re.escape(no)}\s*\]|{re.escape(no)}\s*[.．、)])\s*")
        if no
        else None
    )
    generic = re.compile(r"^\s*(?:\[\s*\d{1,3}\s*\]|\d{1,3}\s*[.．、)])\s*")
    for _ in range(4):
        nxt = specific.sub("", t, count=1) if specific else t
        if nxt == t:
            nxt = generic.sub("", t, count=1)
        nxt = nxt.strip()
        if nxt == t:
            break
        t = nxt
    return t


def notes_zh_lines(job):
    """审校后的中文 NOTE 行（有译文才入列）；标准图纸说明体。"""
    lines = []
    for row in job.get("notes") or []:
        if not isinstance(row, dict):
            continue
        zh = blank(row.get("translated"))
        if not zh:
            continue
        no = blank(row.get("noteNo"))
        # 有 noteNo 才剥前缀并统一加「N.」；无编号则原样输出
        if no:
            body = strip_leading_note_no(zh, no) or zh
            lines.append(f"{no}. {body}")
        else:
            lines.append(zh)
    return lines


def notes_char_width(ch: str, height: float) -> float:
    """
    说明专区字宽：按预览略偏宽估算，硬换行略早于渲染，避免穿出绿框。
    """
    if not ch:
        return 0.0
    if ord(ch) > 127:
        return height * 1.22
    return height * 1.05


def notes_wrap_inner_w(panel_w: float, char_h: float) -> float:
    """可写宽度：左右边距 + 少量安全边，供硬换行与占高估算共用。"""
    base = max(panel_w - 8.0, char_h * 8)
    return max(base * 0.96, char_h * 6)


def wrap_notes_zh_line(text: str, height: float, max_w: float) -> list[str]:
    """
    说明专区硬换行：中文按字切，英文尽量在空格断。
    写入 \\P 后 CAD 与 ezdxf 预览一致（预览引擎不会按宽度折无中文）。
    """
    raw = (text or "").strip()
    if not raw:
        return [""]
    if max_w <= height * 0.5:
        return [raw[:1]]
    lines: list[str] = []
    buf = ""
    buf_w = 0.0
    i = 0
    n = len(raw)
    while i < n:
        ch = raw[i]
        # ASCII 单词尽量整词保留
        if ch.isascii() and not ch.isspace():
            j = i + 1
            while j < n and raw[j].isascii() and not raw[j].isspace():
                j += 1
            word = raw[i:j]
            ww = sum(notes_char_width(c, height) for c in word)
            if buf and buf_w + ww > max_w:
                lines.append(buf.rstrip())
                buf, buf_w = "", 0.0
            if ww > max_w and not buf:
                for c in word:
                    cw = notes_char_width(c, height)
                    if buf and buf_w + cw > max_w:
                        lines.append(buf)
                        buf, buf_w = c, cw
                    else:
                        buf += c
                        buf_w += cw
                i = j
                continue
            buf += word
            buf_w += ww
            i = j
            continue
        if ch.isspace():
            cw = notes_char_width(ch, height)
            if buf and buf_w + cw > max_w:
                lines.append(buf.rstrip())
                buf, buf_w = "", 0.0
                i += 1
                continue
            if buf:
                buf += ch
                buf_w += cw
            i += 1
            continue
        # 中文及其它：按字切
        cw = notes_char_width(ch, height)
        if buf and buf_w + cw > max_w:
            lines.append(buf)
            buf, buf_w = ch, cw
        else:
            buf += ch
            buf_w += cw
        i += 1
    if buf:
        lines.append(buf.rstrip() if buf.strip() else buf)
    return lines or [raw]


def format_notes_zh_body(lines, char_h: float, panel_w: float) -> str:
    """
    标准图纸说明排版：
    - 每条说明按栏宽硬换行（\\P），预览与 CAD 一致
    - 条目之间空一行（\\P\\P）拉开行距
    """
    if not lines:
        return ""
    inner = notes_wrap_inner_w(panel_w, char_h)
    blocks = []
    for line in lines:
        wrapped = wrap_notes_zh_line(clip(line, 400), char_h, inner)
        blocks.append("\\P".join(wrapped))
    return "\\P\\P".join(blocks)


def estimate_notes_zh_body_h(lines, char_h: float, panel_w: float) -> float:
    """估算说明正文占高（含条目空行），用于超框时缩字。"""
    if not lines:
        return 0.0
    inner = notes_wrap_inner_w(panel_w, char_h)
    # 与 MTEXT line_spacing_factor=1.35、条目间 \\P\\P 空一行对齐
    line_gap = char_h * 1.35
    entry_gap = char_h * 1.35
    total = 0.0
    for idx, line in enumerate(lines):
        wrapped = wrap_notes_zh_line(clip(line, 400), char_h, inner)
        total += max(1, len(wrapped)) * line_gap
        if idx < len(lines) - 1:
            total += entry_gap
    return total


def estimate_notes_zh_w(lines) -> float:
    if not lines:
        return 0.0
    return 240.0


NOTES_CHAR_H = 3.2
NOTES_GAP = 10.0
NOTES_TITLE_H = 16.0
NOTES_PAD_BOTTOM = 8.0
# 与 AutoCAD/ezdxf 默认 MTEXT 行距一致（5/3），逐行 TEXT 用同一行距，框高不再靠猜
NOTES_LINE_SP = 5.0 / 3.0


def customer_note_col_w(job) -> float:
    """客户图 NOTE 列宽；异常值回退 240。"""
    widths = []
    layout = job.get("sheetLayout") or {}
    for panel in layout.get("panels") or []:
        if not isinstance(panel, dict):
            continue
        for region in panel.get("regions") or []:
            if not isinstance(region, dict) or str(region.get("kind") or "") != "note":
                continue
            try:
                w = float(region["x1"]) - float(region["x0"])
            except Exception:
                continue
            if 80.0 <= w <= 360.0:
                widths.append(w)
    if not widths:
        return 240.0
    widths.sort()
    return max(160.0, min(280.0, widths[len(widths) // 2]))


def notes_zh_flow(lines, panel_w: float, char_h: float):
    """硬换行后的行（空串=条目间隔）与行距；估算与绘制必须走这里。"""
    inner = notes_wrap_inner_w(panel_w, char_h)
    line_h = char_h * NOTES_LINE_SP
    parts: list[str] = []
    for idx, line in enumerate(lines):
        wrapped = wrap_notes_zh_line(clip(line, 400), char_h, inner)
        parts.extend(wrapped or [""])
        if idx < len(lines) - 1:
            parts.append("")
    return parts, line_h


def notes_zh_panel_need(lines, char_h: float = NOTES_CHAR_H, panel_w: float | None = None) -> tuple[float, float]:
    """说明专区需要的宽高：框线按实际行数圈住全文。"""
    w = float(panel_w) if panel_w and panel_w > 0 else estimate_notes_zh_w(lines)
    if not lines or w <= 0:
        return 0.0, 0.0
    parts, line_h = notes_zh_flow(lines, w, char_h)
    body_h = max(1, len(parts)) * line_h
    return w, NOTES_TITLE_H + body_h + NOTES_PAD_BOTTOM


def rects_overlap(ax, ay, aw, ah, bx, by, bw, bh, gap: float = 0.0) -> bool:
    return (
        ax < bx + bw + gap
        and ax + aw + gap > bx
        and ay < by + bh + gap
        and ay + ah + gap > by
    )


def pick_notes_zh_char_h(lines, panel_w: float, panel_h: float) -> float:
    """在专区高度内尽量用大字；装不下则逐步缩小。"""
    avail = max(panel_h - 22.0, 40.0)
    char_h = max(3.0, min(4.2, avail / max(len(lines) * 1.85, 10)))
    for _ in range(12):
        need = estimate_notes_zh_body_h(lines, char_h, panel_w)
        if need <= avail or char_h <= 2.4:
            break
        char_h = max(2.4, char_h - 0.15)
    return char_h


def draw_notes_zh_panel(msp, x, y, w, h, lines, warnings, char_h=None) -> bool:
    """说明区：逐行 TEXT 排版，框高按行数计算，避免 MTEXT 行距把字顶出框。"""
    if not lines or w < 20 or h < 24:
        return False
    use_h = float(char_h) if char_h else NOTES_CHAR_H
    parts, line_h = notes_zh_flow(lines, w, use_h)
    need_h = NOTES_TITLE_H + max(1, len(parts)) * line_h + NOTES_PAD_BOTTOM
    if need_h > h + 0.5:
        y -= need_h - h
        h = need_h
    box(msp, x, y, w, h)
    cell_text(msp, x + 2, y + h - 9, w - 4, 8, "说明（中文）", 3.2, color=LABEL)
    cursor = y + h - NOTES_TITLE_H - use_h
    for part in parts:
        if cursor < y + 1.0:
            warnings.append("中文说明过长，面板内已截断")
            break
        if part:
            try:
                entity = msp.add_text(
                    part,
                    dxfattribs={
                        "layer": LAYER,
                        "height": use_h,
                        "color": FILL,
                        "style": "FACTORY_CN",
                    },
                )
                entity.dxf.insert = (x + 4.0, cursor)
            except Exception:
                cell_text(msp, x + 3, cursor, w - 6, line_h, part, use_h, color=FILL)
        cursor -= line_h
    return True


def draw_material_table(msp, x, y, rows):
    """转换图左下材料表：表头在最下；材料名超长换行/缩字，不穿格。"""
    col_w = [18.0, 112.0, 40.0, 36.0]
    base_h = 7.0
    text_h = 2.0
    data = list(rows[:12]) if rows else []
    headers = ["NO", "MATERIAL NAME", "THICKNESS", "COLOR"]
    mat_inner = max(col_w[1] - 1.6, 8.0)

    # 表头 + 数据行高（材料列可多行）
    row_heights = [base_h]
    packed = [(headers[0], [headers[1]], headers[2], headers[3], text_h)]
    for row in data:
        no, name, thick, color = row
        lines, cell_h = fit_cell_text(str(name or ""), text_h, mat_inner)
        rh = max(base_h, len(lines) * (cell_h * 1.3) + 2.0)
        row_heights.append(rh)
        packed.append((str(no or ""), lines, str(thick or ""), str(color or ""), cell_h))

    if not data:
        # 空表也画一行空数据位
        row_heights.append(base_h)
        packed.append(("", [""], "", "", text_h))

    _width, height = closed_grid_var(msp, x, y, col_w, row_heights)
    yy = y
    for idx, (no, mat_lines, thick, color, cell_h) in enumerate(packed):
        rh = row_heights[idx]
        color_label = LABEL if idx == 0 else FILL
        cell_text(msp, x, yy, col_w[0], rh, no, text_h, color=color_label)
        # 材料列：多行用 MTEXT
        if len(mat_lines) > 1:
            try:
                mtext = msp.add_mtext(
                    "\\P".join(mat_lines),
                    dxfattribs={
                        "layer": "FRAME",
                        "style": "FACTORY_CN",
                        "char_height": cell_h,
                        "color": color_label,
                        "attachment_point": 1,
                    },
                )
                mtext.set_location((x + col_w[0] + 0.8, yy + rh - 0.6))
                mtext.dxf.width = mat_inner
            except Exception:
                cell_text(
                    msp,
                    x + col_w[0],
                    yy,
                    col_w[1],
                    rh,
                    " ".join(mat_lines),
                    cell_h,
                    color=color_label,
                )
        else:
            cell_text(
                msp,
                x + col_w[0],
                yy,
                col_w[1],
                rh,
                mat_lines[0] if mat_lines else "",
                cell_h,
                color=color_label,
            )
        cell_text(msp, x + col_w[0] + col_w[1], yy, col_w[2], rh, thick, text_h, color=color_label)
        cell_text(
            msp,
            x + col_w[0] + col_w[1] + col_w[2],
            yy,
            col_w[3],
            rh,
            color,
            text_h,
            color=color_label,
        )
        yy += rh
    return height


def draw_title_block(msp, x, y, w, h, drawing_no, sheet_w=None, sheet_h=None, sheet_label="", page_index=0, page_count=1):
    """转换图右下制图表：标签绿加大；人工填白空着；仅 PART NO 在有图号时白字写入。
    SCALE/SIZE/UNIT/SHEET 不自动填（工程图须用户自填，禁止臆造）。
    sheet_w/sheet_h/sheet_label/page_* 保留入参兼容旧调用，不再写入底栏。
    """
    col_w = [w * 0.24, w * 0.26, w * 0.16, w * 0.34]
    n_rows = 6
    row_h = h / n_rows
    closed_grid(msp, x, y, col_w, row_h, n_rows)
    label_h = min(3.2, max(2.6, row_h * 0.42))
    fill_h = min(3.4, max(2.8, row_h * 0.48))

    def row_y(i):
        return y + h - row_h * (i + 1)

    c0, c1, c2, c3 = col_w
    cell_text(msp, x, row_y(0), c0, row_h, "TOLERANCES", label_h, color=LABEL)
    cell_text(msp, x + c0, row_y(0), c1, row_h, "Designed by", label_h, color=LABEL)
    cell_text(msp, x + c0 + c1, row_y(0), c2, row_h, "DATE", label_h, color=LABEL)
    cell_text(msp, x + c0 + c1 + c2, row_y(0), c3, row_h, "TITLE", label_h, color=LABEL)

    cell_text(msp, x, row_y(1), c0, row_h, "尺寸", label_h, color=LABEL)
    cell_text(msp, x + c0, row_y(1), c1, row_h, "Checked by", label_h, color=LABEL)
    cell_text(msp, x + c0 + c1, row_y(1), c2, row_h, "DATE", label_h, color=LABEL)

    cell_text(msp, x, row_y(2), c0, row_h, "角度", label_h, color=LABEL)
    cell_text(msp, x + c0, row_y(2), c1, row_h, "Approved by", label_h, color=LABEL)
    cell_text(msp, x + c0 + c1, row_y(2), c2, row_h, "DATE", label_h, color=LABEL)
    cell_text(msp, x + c0 + c1 + c2, row_y(2), c3, row_h, "PART NO", label_h, color=LABEL)

    cell_text(msp, x, row_y(3), c0, row_h, "平面度", label_h, color=LABEL)
    part_no = blank(drawing_no)
    if part_no:
        cell_text(msp, x + c0 + c1 + c2, row_y(3), c3, row_h, part_no, fill_h, color=FILL)

    cell_text(msp, x, row_y(4), c0, row_h, "对称度", label_h, color=LABEL)
    cell_text(msp, x + c0, row_y(4), c1 + c2, row_h, "FIRST ANGLE PROJECTION", min(label_h, 2.8), color=LABEL)

    # 底栏：只留标签，白格留空由用户在 CAD 填写
    cell_text(msp, x, row_y(5), c0 * 0.42, row_h, "SCALE", label_h, color=LABEL)
    cell_text(msp, x + c0, row_y(5), c1 * 0.42, row_h, "SIZE", label_h, color=LABEL)
    cell_text(msp, x + c0 + c1, row_y(5), c2 * 0.45, row_h, "UNIT", label_h, color=LABEL)
    cell_text(msp, x + c0 + c1 + c2, row_y(5), c3 * 0.38, row_h, "SHEET", label_h, color=LABEL)


def draw_revision(msp, x, y, w, h):
    """转换图右上修订栏：贴内框；标题+表头绿加大；白字数据行空着；仅保留少量空行。"""
    # 标题行 + 表头行 + 2 行空数据（去掉多余无用行）
    n_rows = 4
    row_h = h / n_rows
    col_w = [max(18.0, w * 0.12), max(40.0, w * 0.58), max(36.0, w * 0.30)]
    total = sum(col_w)
    if total > 0 and abs(total - w) > 0.01:
        col_w = [c * w / total for c in col_w]
    # 外框 + 标题下横线；标题行不分列，避免竖线切断长标题
    box(msp, x, y, w, h)
    hline(msp, x, x + w, y + row_h * (n_rows - 1))
    # 下方 3 行三列表格线
    body_bottom = y
    body_top = y + row_h * (n_rows - 1)
    cx = x
    for cw in col_w[:-1]:
        cx += cw
        vline(msp, cx, body_bottom, body_top)
    for i in range(1, n_rows - 1):
        hline(msp, x, x + w, y + row_h * i)
    title = "REVISION HISTORY—ALL SHEETS ARE THE SAME REVISION STATUS"
    title_h = min(3.0, max(2.4, row_h * 0.55))
    cell_text(msp, x, y + row_h * (n_rows - 1), w, row_h, title, title_h, color=LABEL)
    head_h = min(2.8, max(2.4, row_h * 0.5))
    yy = y + row_h * (n_rows - 2)
    cx = x
    for i, name in enumerate(["REV", "DESCRIPTION", "NAME"]):
        cell_text(msp, cx, yy, col_w[i], row_h, name, head_h, color=LABEL)
        cx += col_w[i]


MAX_CLONE_ENTS = 28000
MIN_CLONE_ENTS = 20
PAGE_GAP = 40.0


def pick_source_space(doc):
    space = doc.modelspace()
    bounds = layout_bounds(space)
    best = space
    best_n = len(list(space)) if bounds else -1
    for layout in layouts(doc)[1:]:
        try:
            n = len(list(layout))
        except Exception:
            continue
        if n > best_n:
            best_n = n
            best = layout
    return best


def _entity_center(entity):
    try:
        from ezdxf import bbox as ezbbox

        box = ezbbox.extents([entity], fast=True)
        if box is None or not box.has_data:
            return None
        c = box.center
        return float(c.x), float(c.y)
    except Exception:
        return None


def _entity_bbox(entity):
    """返回 (minx, miny, maxx, maxy)；失败 None。"""
    try:
        from ezdxf import bbox as ezbbox

        box = ezbbox.extents([entity], fast=True)
        if box is None or not box.has_data:
            return None
        return (
            float(box.extmin.x),
            float(box.extmin.y),
            float(box.extmax.x),
            float(box.extmax.y),
        )
    except Exception:
        return None


def _in_window(cx, cy, window):
    return window["x0"] <= cx <= window["x1"] and window["y0"] <= cy <= window["y1"]


def _bbox_overlaps_window(bbox, window):
    if bbox is None or window is None:
        return False
    return not (
        bbox[2] < window["x0"]
        or bbox[0] > window["x1"]
        or bbox[3] < window["y0"]
        or bbox[1] > window["y1"]
    )


def expand_content_window(src_space, content, excludes=None, limit=None):
    """
    把 content 扩到「限位框内、非角落表」图元的真实外包。
    只扩不缩：修复量界偏窄导致右侧/下侧客户内容缺一块；不改 title/rev 排除语义。
    """
    if content is None or src_space is None:
        return content
    try:
        x0 = float(content["x0"])
        y0 = float(content["y0"])
        x1 = float(content["x1"])
        y1 = float(content["y1"])
    except Exception:
        return content
    ex_list = [e for e in (excludes or []) if e]
    grown = False
    for entity in src_space:
        center = _entity_center(entity)
        if center is None:
            continue
        if limit is not None and not _in_window(center[0], center[1], limit):
            continue
        if any(_in_window(center[0], center[1], ex) for ex in ex_list):
            continue
        bbox = _entity_bbox(entity)
        if bbox is None:
            continue
        nx0, ny0, nx1, ny1 = bbox
        if nx1 <= nx0 or ny1 <= ny0:
            continue
        # 限位框内再扩，避免吃到框外区号
        if limit is not None:
            nx0 = max(nx0, float(limit["x0"]))
            ny0 = max(ny0, float(limit["y0"]))
            nx1 = min(nx1, float(limit["x1"]))
            ny1 = min(ny1, float(limit["y1"]))
        if nx1 <= nx0 or ny1 <= ny0:
            continue
        if nx0 < x0 or ny0 < y0 or nx1 > x1 or ny1 > y1:
            grown = True
        x0, y0 = min(x0, nx0), min(y0, ny0)
        x1, y1 = max(x1, nx1), max(y1, ny1)
    if not grown:
        return content
    return {"x0": x0, "y0": y0, "x1": x1, "y1": y1}


def _add_cloned_primitive(block, entity, x0, y0, layer):
    """
    把单个实体以手工重建方式写入 block（坐标减窗原点）。
    支持 LINE/LWPOLYLINE/POLYLINE/CIRCLE/ARC/ELLIPSE/TEXT/MTEXT。
    返回写入条数（INSERT 展开后可能多条）。
    """
    kind = entity.dxftype()
    n = 0
    if kind == "LINE":
        s = entity.dxf.start
        t = entity.dxf.end
        block.add_line(
            (float(s.x) - x0, float(s.y) - y0),
            (float(t.x) - x0, float(t.y) - y0),
            dxfattribs={"layer": layer, "color": 7},
        )
        return 1
    if kind == "LWPOLYLINE":
        pts = [(float(p[0]) - x0, float(p[1]) - y0) for p in entity.get_points("xy")]
        if len(pts) >= 2:
            block.add_lwpolyline(pts, dxfattribs={"layer": layer, "color": 7, "closed": bool(entity.closed)})
            return 1
        return 0
    if kind == "POLYLINE":
        pts = []
        for v in entity.vertices:
            try:
                loc = v.dxf.location
                pts.append((float(loc.x) - x0, float(loc.y) - y0))
            except Exception:
                continue
        if len(pts) >= 2:
            closed = bool(getattr(entity, "is_closed", False))
            block.add_lwpolyline(pts, dxfattribs={"layer": layer, "color": 7, "closed": closed})
            return 1
        return 0
    if kind == "CIRCLE":
        c = entity.dxf.center
        block.add_circle(
            (float(c.x) - x0, float(c.y) - y0),
            float(entity.dxf.radius),
            dxfattribs={"layer": layer, "color": 7},
        )
        return 1
    if kind == "ARC":
        c = entity.dxf.center
        block.add_arc(
            (float(c.x) - x0, float(c.y) - y0),
            float(entity.dxf.radius),
            float(entity.dxf.start_angle),
            float(entity.dxf.end_angle),
            dxfattribs={"layer": layer, "color": 7},
        )
        return 1
    if kind == "ELLIPSE":
        try:
            # 近似：主轴方向的 lwpolyline 采样，避免丢椭圆轮廓
            pts = []
            for p in entity.flattening(0.5):
                pts.append((float(p.x) - x0, float(p.y) - y0))
            if len(pts) >= 3:
                block.add_lwpolyline(pts, dxfattribs={"layer": layer, "color": 7, "closed": True})
                return 1
        except Exception:
            return 0
        return 0
    if kind == "TEXT":
        try:
            insert = entity.dxf.insert
            height = float(getattr(entity.dxf, "height", 2.5) or 2.5)
            text = str(entity.dxf.text or "")
            if not text.strip():
                return 0
            block.add_text(
                text,
                dxfattribs={
                    "layer": layer,
                    "color": 7,
                    "height": height,
                    "insert": (float(insert.x) - x0, float(insert.y) - y0),
                    "rotation": float(getattr(entity.dxf, "rotation", 0.0) or 0.0),
                },
            )
            return 1
        except Exception:
            return 0
    if kind == "MTEXT":
        try:
            insert = entity.dxf.insert
            text = str(entity.text or getattr(entity.dxf, "text", "") or "")
            if not text.strip():
                return 0
            char_h = float(getattr(entity.dxf, "char_height", 2.5) or 2.5)
            width = float(getattr(entity.dxf, "width", 0.0) or 0.0)
            mtext = block.add_mtext(
                text,
                dxfattribs={
                    "layer": layer,
                    "color": 7,
                    "char_height": char_h,
                    "insert": (float(insert.x) - x0, float(insert.y) - y0),
                    "rotation": float(getattr(entity.dxf, "rotation", 0.0) or 0.0),
                },
            )
            if width > 0:
                mtext.dxf.width = width
            return 1
        except Exception:
            return 0
    if kind == "INSERT":
        # 展开块参照为虚拟实体再拷，避免整块 INSERT 丢失导致缺一块视图
        try:
            for ve in entity.virtual_entities():
                n += _add_cloned_primitive(block, ve, x0, y0, layer)
        except Exception:
            return n
        return n
    return 0

def measure_fillet_radius(space, frame):
    """
    由通长边在四角的缺口估计圆角半径。
    左/右边线顶端与顶边的差、顶/底边线端与侧边的差 → 取中位数。
    """
    try:
        fx0, fy0 = float(frame["x0"]), float(frame["y0"])
        fx1, fy1 = float(frame["x1"]), float(frame["y1"])
    except Exception:
        return 7.0
    fw, fh = max(fx1 - fx0, 1.0), max(fy1 - fy0, 1.0)
    edge = max(1.2, min(fw, fh) * 0.003)
    left_ys, right_ys, bot_xs, top_xs = [], [], [], []
    if space is None:
        return 7.0
    for entity in space:
        if entity.dxftype() != "LINE":
            continue
        try:
            s, t = entity.dxf.start, entity.dxf.end
            x1, y1, x2, y2 = float(s.x), float(s.y), float(t.x), float(t.y)
            length = ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5
            if abs(x1 - fx0) <= edge and abs(x2 - fx0) <= edge and length >= fh * 0.45:
                left_ys.extend([y1, y2])
            elif abs(x1 - fx1) <= edge and abs(x2 - fx1) <= edge and length >= fh * 0.45:
                right_ys.extend([y1, y2])
            elif abs(y1 - fy0) <= edge and abs(y2 - fy0) <= edge and length >= fw * 0.45:
                bot_xs.extend([x1, x2])
            elif abs(y1 - fy1) <= edge and abs(y2 - fy1) <= edge and length >= fw * 0.45:
                top_xs.extend([x1, x2])
        except Exception:
            continue
    gaps = []
    if left_ys:
        gaps.append(max(0.0, fy1 - max(left_ys)))
        gaps.append(max(0.0, min(left_ys) - fy0))
    if right_ys:
        gaps.append(max(0.0, fy1 - max(right_ys)))
        gaps.append(max(0.0, min(right_ys) - fy0))
    if top_xs:
        gaps.append(max(0.0, min(top_xs) - fx0))
        gaps.append(max(0.0, fx1 - max(top_xs)))
    if bot_xs:
        gaps.append(max(0.0, min(bot_xs) - fx0))
        gaps.append(max(0.0, fx1 - max(bot_xs)))
    usable = [g for g in gaps if 2.5 <= g <= min(fw, fh) * 0.08]
    if not usable:
        return 7.0
    usable.sort()
    return float(usable[len(usable) // 2])


def _frame_fillet_radius(frame, space=None):
    """优先用 frame.filletR；否则从 space 测缺口；再否则默认 7。"""
    if isinstance(frame, dict) and frame.get("filletR") is not None:
        try:
            return max(3.0, float(frame["filletR"]))
        except Exception:
            pass
    if space is not None and frame is not None:
        return measure_fillet_radius(space, frame)
    return 7.0


def _in_frame_corner_pocket(cx, cy, frame, radius):
    """图元中心是否落在四角圆角口袋（通长边留下的缺口）。"""
    try:
        fx0, fy0 = float(frame["x0"]), float(frame["y0"])
        fx1, fy1 = float(frame["x1"]), float(frame["y1"])
    except Exception:
        return False
    r = max(3.0, float(radius))
    pad = 2.0
    pockets = (
        (fx0 - pad, fy1 - r - pad, fx0 + r + pad, fy1 + pad),  # TL
        (fx1 - r - pad, fy1 - r - pad, fx1 + pad, fy1 + pad),  # TR
        (fx0 - pad, fy0 - pad, fx0 + r + pad, fy0 + r + pad),  # BL
        (fx1 - r - pad, fy0 - pad, fx1 + pad, fy0 + r + pad),  # BR
    )
    for x0, y0, x1, y1 in pockets:
        if x0 <= cx <= x1 and y0 <= cy <= y1:
            return True
    return False


def is_outer_frame_chrome(entity, frame, fillet_r, outside=True):
    """
    方案 A：是否属于一周外框噪声（通长边 / 四角圆角折线或 ARC / 可选框外）。
    不删贴边字画短线。
    """
    if frame is None:
        return False
    try:
        fx0, fy0 = float(frame["x0"]), float(frame["y0"])
        fx1, fy1 = float(frame["x1"]), float(frame["y1"])
    except Exception:
        return False
    fw, fh = max(fx1 - fx0, 1.0), max(fy1 - fy0, 1.0)
    edge = max(1.2, min(fw, fh) * 0.003)
    r = max(3.0, float(fillet_r or 7.0))
    kind = entity.dxftype()
    try:
        center = _entity_center(entity)
        if outside and center is not None:
            cx, cy = center
            if cx < fx0 - edge or cx > fx1 + edge or cy < fy0 - edge or cy > fy1 + edge:
                return True
        if kind == "LINE":
            s, t = entity.dxf.start, entity.dxf.end
            x1, y1, x2, y2 = float(s.x), float(s.y), float(t.x), float(t.y)
            length = ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5
            on_left = abs(x1 - fx0) <= edge and abs(x2 - fx0) <= edge
            on_right = abs(x1 - fx1) <= edge and abs(x2 - fx1) <= edge
            on_bottom = abs(y1 - fy0) <= edge and abs(y2 - fy0) <= edge
            on_top = abs(y1 - fy1) <= edge and abs(y2 - fy1) <= edge
            if (on_left or on_right) and length >= fh * 0.45:
                return True
            if (on_bottom or on_top) and length >= fw * 0.45:
                return True
            if center is not None and length <= max(4.5, r * 0.75):
                if _in_frame_corner_pocket(center[0], center[1], frame, r):
                    return True
        elif kind == "LWPOLYLINE":
            pts = list(entity.get_points("xy"))
            if len(pts) < 2:
                return False
            xs = [float(p[0]) for p in pts]
            ys = [float(p[1]) for p in pts]
            minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
            if abs((maxx - minx) - fw) < edge * 8 and abs((maxy - miny) - fh) < edge * 8:
                return True
            # 短肢落在角口袋
            if center is not None and (maxx - minx) <= r * 2.5 and (maxy - miny) <= r * 2.5:
                if _in_frame_corner_pocket(center[0], center[1], frame, r):
                    return True
        elif kind == "ARC":
            c = entity.dxf.center
            rad = float(entity.dxf.radius)
            cx, cy = float(c.x), float(c.y)
            if 1.0 <= rad <= max(r * 2.0, min(fw, fh) * 0.05):
                near_corner = (
                    (abs(cx - fx0) <= rad + edge * 2 or abs(cx - fx1) <= rad + edge * 2)
                    and (abs(cy - fy0) <= rad + edge * 2 or abs(cy - fy1) <= rad + edge * 2)
                )
                if near_corner:
                    return True
            if _in_frame_corner_pocket(cx, cy, frame, r):
                return True
    except Exception:
        return False
    return False


def strip_outer_frame_chrome(src_space, panels, warnings):
    """
    方案 A 最早一步：按每幅 frame 删除一周外框通长边 + 四角圆角（及框外区号）。
    在克隆进工程图之前执行；对照区若已 place 则不受影响。
    """
    if src_space is None:
        return 0
    total = 0
    for panel in panels or []:
        regions = region_map(panel if isinstance(panel, dict) else {})
        frame = regions.get("frame")
        if not frame:
            # 无 frame 时用 panel 内缩近似
            try:
                band = 12.0
                edge_reg = regions.get("edge")
                if edge_reg and edge_reg.get("x0"):
                    band = float(edge_reg["x0"])
                frame = {
                    "x0": float(panel["x0"]) + band,
                    "y0": float(panel["y0"]) + band,
                    "x1": float(panel["x1"]) - band,
                    "y1": float(panel["y1"]) - band,
                }
            except Exception:
                continue
        fillet_r = measure_fillet_radius(src_space, frame)
        frame = dict(frame)
        frame["filletR"] = fillet_r
        # 写回 panel regions 供后续克隆使用
        if isinstance(panel, dict):
            regs = panel.get("regions") or []
            for reg in regs:
                if isinstance(reg, dict) and reg.get("kind") == "frame":
                    reg["filletR"] = fillet_r
        doomed = []
        for entity in src_space:
            if is_outer_frame_chrome(entity, frame, fillet_r, outside=True):
                # 仅删本幅相关：中心在 panel 内（或框外贴本幅）
                center = _entity_center(entity)
                if center is None:
                    continue
                try:
                    px0, py0 = float(panel.get("x0", frame["x0"])), float(panel.get("y0", frame["y0"]))
                    px1, py1 = float(panel.get("x1", frame["x1"])), float(panel.get("y1", frame["y1"]))
                except Exception:
                    px0, py0, px1, py1 = frame["x0"], frame["y0"], frame["x1"], frame["y1"]
                # 框外区号仍属本幅周边
                margin = max(20.0, fillet_r * 3)
                if not (px0 - margin <= center[0] <= px1 + margin and py0 - margin <= center[1] <= py1 + margin):
                    continue
                doomed.append(entity)
        for entity in doomed:
            try:
                src_space.delete_entity(entity)
                total += 1
            except Exception:
                continue
        warnings.append(
            f"幅{panel.get('index', '?')} 已预处理剔除外框/圆角约 {len(doomed)} 条（r≈{fillet_r:.1f}）"
        )
    return total


def _is_sheet_border(entity, panel, band, frame=None, space=None):
    """
    克隆时兜底：一周外框通长线/四角圆角/框外。
    主路径已在 strip_outer_frame_chrome 预处理。
    """
    if frame is None and panel is None:
        return False
    use_frame = frame
    if use_frame is None and panel is not None:
        try:
            fx0, fy0 = float(panel["x0"]), float(panel["y0"])
            fx1, fy1 = float(panel["x1"]), float(panel["y1"])
            if band is not None and band >= 1:
                use_frame = {"x0": fx0 + band, "y0": fy0 + band, "x1": fx1 - band, "y1": fy1 - band}
            else:
                use_frame = {"x0": fx0, "y0": fy0, "x1": fx1, "y1": fy1}
        except Exception:
            return False
    fillet_r = _frame_fillet_radius(use_frame, space)
    return is_outer_frame_chrome(entity, use_frame, fillet_r, outside=True)


def clone_region_to_slot(
    src_space,
    target,
    block_name,
    window,
    slot,
    warnings,
    layer="CUSTOMER",
    exclude=None,
    panel=None,
    edge_band=None,
    scale_mode="fit",
    frame=None,
):
    """
    源窗克隆入槽。
    scale_mode:
      - fit：缩放到槽内（旧逻辑）
      - 1:1：不缩放，槽位应 ≥ 源窗；转换图可放大幅面来装下
    """
    if window is None or slot is None:
        return False
    x0, y0, x1, y1 = float(window["x0"]), float(window["y0"]), float(window["x1"]), float(window["y1"])
    bw = max(x1 - x0, 1e-3)
    bh = max(y1 - y0, 1e-3)
    sx, sy, sw, sh = slot
    if sw < 8 or sh < 8:
        return False
    excludes = []
    if isinstance(exclude, list):
        excludes = [e for e in exclude if e]
    elif exclude is not None:
        excludes = [exclude]
    name = block_name
    n = 1
    while name in target.blocks:
        name = f"{block_name}_{n}"
        n += 1
    block = target.blocks.new(name)
    count = 0
    truncated = False
    skipped_border = 0
    skipped_table = 0
    for entity in src_space:
        if count >= MAX_CLONE_ENTS:
            truncated = True
            break
        center = _entity_center(entity)
        if center is None:
            continue
        bbox = _entity_bbox(entity)
        # 中心在窗内，或外包与 content 相交（避免长视图中心落窗外被整段丢掉）
        in_content = _in_window(center[0], center[1], window) or _bbox_overlaps_window(bbox, window)
        if not in_content:
            continue
        if any(_in_window(center[0], center[1], ex) for ex in excludes):
            skipped_table += 1
            continue
        if _is_sheet_border(entity, panel, edge_band, frame=frame, space=src_space):
            skipped_border += 1
            continue
        try:
            added = _add_cloned_primitive(block, entity, x0, y0, layer)
            count += added
        except Exception:
            continue
    if count < MIN_CLONE_ENTS:
        try:
            target.blocks.delete_block(name)
        except Exception:
            pass
        if count > 0:
            warnings.append(f"{block_name} 区内图元过少（{count}），已跳过空克隆")
        return False
    if truncated:
        warnings.append(f"{block_name} 图元过多，已截断克隆")
    if skipped_border:
        warnings.append(f"{block_name} 已剔除贴边外框/区号约 {skipped_border} 条")
    if skipped_table:
        warnings.append(f"{block_name} 已剔除角落表约 {skipped_table} 条")
    if scale_mode == "1:1":
        scale = 1.0
        used_w, used_h = bw, bh
        # 槽比源大则居中；槽不够大时仍 1:1（幅面应已按源放大）
        ix = sx + max(0.0, (sw - used_w) / 2.0)
        iy = sy + max(0.0, (sh - used_h) / 2.0)
    else:
        scale = min(sw / bw, sh / bh) * 0.96
        used_w = bw * scale
        used_h = bh * scale
        ix = sx + (sw - used_w) / 2.0
        iy = sy + max(0.0, (sh - used_h) / 2.0)
    target.modelspace().add_blockref(
        name,
        (ix, iy),
        dxfattribs={"xscale": scale, "yscale": scale, "layer": layer},
    )
    return True


def estimate_material_table_h(job):
    """按材料名折行估算表高，避免爆炸槽与材料表重叠。"""
    rows = material_rows(job)
    data = list(rows[:12]) if rows else []
    col_mat = 112.0
    text_h = 2.0
    base_h = 7.0
    mat_inner = max(col_mat - 1.6, 8.0)
    total = base_h  # 表头
    if not data:
        return total + base_h
    for row in data:
        name = str(row[1] or "")
        lines, cell_h = fit_cell_text(name, text_h, mat_inner)
        total += max(base_h, len(lines) * (cell_h * 1.3) + 2.0)
    return total


def stack_extent(path: str):
    """层次 DXF 外包尺寸 (w, h)。失败返回 None。"""
    if not path:
        return None
    try:
        doc, _auditor = recover.readfile(str(path))
    except Exception:
        return None
    try:
        from ezdxf import bbox as ez_bbox

        ext = ez_bbox.extents(doc.modelspace())
        if ext is None or not ext.has_data:
            return None
        w = float(ext.size.x)
        h = float(ext.size.y)
        if w < 1e-3 or h < 1e-3:
            return None
        return w, h
    except Exception:
        return None


def prepare_stack_need(job, warnings):
    """
    工程图合成：读层次 DXF 外包，写入 job['_stackNeed']=(w,h)。
    始终覆盖材料层次示意（有 stackDxfPath 即走插入，不再画材料示意）。
    """
    path = blank(job.get("stackDxfPath"))
    if not path:
        job.pop("_stackNeed", None)
        return
    ext = stack_extent(path)
    if not ext:
        warnings.append("层次 DXF 无法量尺寸，爆炸槽按原尺寸插入")
        job["_stackNeed"] = None
        return
    pad = float(job.get("stackPad") or 1.08)
    job["_stackNeed"] = (ext[0] * pad, ext[1] * pad)


def page_sheet_size(panel, job, page_index):
    """
    转换图仅 1 页：中文说明 + 材料表 + 层次槽 + 图框。
    幅面不跟客户 content 走；不小于 A1（841×594）；层次需要时再放大。
    """
    margin = 8.0
    title_h = 96.0
    rev_h = 42.0
    # 说明区宽度跟客户 NOTE 列，高度按逐行排版
    cw, ch = 40.0, 40.0
    zh_lines = notes_zh_lines(job) if page_index == 0 else []
    note_w = customer_note_col_w(job) if zh_lines else 0.0
    _, note_h = notes_zh_panel_need(zh_lines, NOTES_CHAR_H, note_w)
    if zh_lines:
        ch = max(80.0, note_h)
    mat_h = estimate_material_table_h(job) if page_index == 0 else 0.0
    bottom_chrome = max(title_h, mat_h + 10) + 12
    top_chrome = rev_h + 16
    note_gap = 10.0 if note_w > 0 else 0.0
    sheet_w = max(841.0, cw + margin * 2 + 8 + note_w + note_gap)
    sheet_h = max(594.0, ch + bottom_chrome + top_chrome + margin * 2)

    # 策略 A：按层次外包放大第 1 页纸幅（只长层次槽，不挤占材料表/制图表语义）
    if page_index == 0 and job.get("_stackNeed"):
        try:
            need_w, need_h = float(job["_stackNeed"][0]), float(job["_stackNeed"][1])
        except Exception:
            need_w, need_h = 0.0, 0.0
        if need_w > 1 and need_h > 1:
            mat_w = 18.0 + 112.0 + 40.0 + 36.0

            def slot_w(sw: float) -> float:
                tw = min(320.0, sw * 0.42)
                # 与 draw_convert_page 一致：制图表贴内框右边（无 -2 缝）
                tx = sw - margin - tw
                ex0 = margin + 2 + mat_w + 10
                return max(40.0, tx - ex0 - 8)

            ex_w = slot_w(sheet_w)
            ex_h = max(title_h, mat_h + 10)
            if need_w > ex_w:
                sheet_w += need_w - ex_w
                # 制图表宽度随页宽变大，可能吃掉增量，再补一次
                ex_w2 = slot_w(sheet_w)
                if need_w > ex_w2:
                    sheet_w += need_w - ex_w2
            if need_h > ex_h:
                grow = need_h - ex_h
                bottom_chrome += grow
                sheet_h += grow
    return sheet_w, sheet_h, cw, ch, bottom_chrome, top_chrome


def insert_stack_into_slot(msp, target, stack_path, x, y, w, h, warnings):
    """把三维层次 DXF 等比塞进爆炸槽（始终覆盖原材料层次示意）。"""
    from ezdxf.addons.importer import Importer

    try:
        src, _auditor = recover.readfile(str(stack_path))
    except Exception:
        warnings.append("层次 DXF 无法打开，爆炸槽留空框")
        box(msp, x, y, w, h)
        cell_text(msp, x, y + h * 0.5 - 2, w, 6, "层次图（无法插入）", 2.4, color=LABEL)
        return
    space = src.modelspace()
    try:
        from ezdxf import bbox as ez_bbox

        ext = ez_bbox.extents(space)
        if ext is None or not ext.has_data:
            warnings.append("层次 DXF 无图元")
            box(msp, x, y, w, h)
            return
        minx, miny = float(ext.extmin.x), float(ext.extmin.y)
        maxx, maxy = float(ext.extmax.x), float(ext.extmax.y)
    except Exception:
        warnings.append("层次 DXF 量界失败")
        box(msp, x, y, w, h)
        return
    bw = max(maxx - minx, 1e-6)
    bh = max(maxy - miny, 1e-6)
    pad = 4.0
    aw = max(10.0, w - pad * 2)
    ah = max(10.0, h - pad * 2 - 8)
    scale = min(aw / bw, ah / bh)
    used_w = bw * scale
    used_h = bh * scale
    block_name = "ENG_STACK"
    n = 0
    while block_name in target.blocks:
        n += 1
        block_name = f"ENG_STACK_{n}"
    block = target.blocks.new(block_name)
    try:
        importer = Importer(src, target)
        importer.import_entities(space, block)
        importer.finalize()
    except Exception:
        warnings.append("层次 DXF 导入失败")
        box(msp, x, y, w, h)
        return
    box(msp, x, y, w, h)
    cell_text(msp, x + 2, y + h - 6, w - 4, 5, "层次图（三维）", 2.0, color=LABEL)
    ix = x + pad + (aw - used_w) / 2 - minx * scale
    iy = y + pad + (ah - used_h) / 2 - miny * scale
    msp.add_blockref(
        block_name,
        (ix, iy),
        dxfattribs={"xscale": scale, "yscale": scale, "layer": "FRAME"},
    )
    warnings.append(f"已用三维层次覆盖爆炸槽（{w:.0f}×{h:.0f}，缩放 {scale:.3f}）")


def draw_explosion_slot(msp, x, y, w, h, warnings, rows=None, target=None, stack_path=None):
    """爆炸槽：有 stack_path 则始终覆盖插入三维层次；否则材料表叠层示意。"""
    if w < 20 or h < 20:
        warnings.append("爆炸图预留区过小")
        return {"x": x, "y": y, "w": w, "h": h}
    path = blank(stack_path)
    if path and target is not None:
        insert_stack_into_slot(msp, target, path, x, y, w, h, warnings)
        return {"x": x, "y": y, "w": w, "h": h}
    box(msp, x, y, w, h)
    data = list(rows or [])
    if not data:
        cell_text(msp, x, y + h * 0.5 - 2, w, 6, "层次爆炸图（材料未标注）", 2.4, color=LABEL)
        return {"x": x, "y": y, "w": w, "h": h}
    draw_material_stack(msp, x, y, w, h, data)
    return {"x": x, "y": y, "w": w, "h": h}


def draw_material_stack(msp, x, y, w, h, rows):
    """材料表 → 等轴测叠层示意（下→上）。有厚度写厚度，没有写未标注。"""
    n = max(1, min(8, len(rows)))
    pad = 6.0
    area_x = x + pad
    area_y = y + pad + 8
    area_w = w * 0.52 - pad
    area_h = h - pad * 2 - 14
    lab_x = x + w * 0.55
    lab_w = w * 0.42
    gap = min(14.0, area_h / max(n, 1) * 0.35)
    plate_h = max(6.0, (area_h - gap * (n - 1)) / n)
    plate_w = min(area_w * 0.92, 120.0)
    skew = min(18.0, plate_w * 0.18)
    cell_text(msp, x + 2, y + h - 7, w - 4, 6, "层次示意（材料表）", 2.2, color=LABEL)
    # 材料表已按编号升序；叠层自下而上 = 表序（常见衬底在下）
    for i, row in enumerate(rows[:n]):
        no, name, thick, color = row
        base = area_y + i * (plate_h + gap)
        ox = area_x + (area_w - plate_w) * 0.1 + i * 1.2
        # 平行四边形：近似等轴测薄片
        pts = [
            (ox, base),
            (ox + plate_w, base),
            (ox + plate_w + skew, base + plate_h),
            (ox + skew, base + plate_h),
        ]
        msp.add_lwpolyline(pts, close=True, dxfattribs={"layer": "FRAME", "color": LINE})
        # 侧面厚度暗示
        msp.add_line(
            (ox + plate_w, base),
            (ox + plate_w + skew, base + plate_h),
            dxfattribs={"layer": "FRAME", "color": LINE},
        )
        ly = base + plate_h * 0.35
        label = f"[{no}] {clip(name, 28)}"
        cell_text(msp, lab_x, ly, lab_w, 5, label, 1.9, color=FILL)
        meta = " / ".join([p for p in (thick or "未标注", color or "未标注") if p])
        cell_text(msp, lab_x, ly - 4.2, lab_w, 4, meta, 1.6, color=FILL)
        # 引出线
        msp.add_line(
            (ox + plate_w + skew * 0.5, base + plate_h * 0.5),
            (lab_x - 2, ly + 1),
            dxfattribs={"layer": "FRAME", "color": LINE},
        )
    cell_text(msp, x + 2, y + 2, w - 4, 5, "非三维剖分，仅材料层次", 1.6, color=LABEL)


def region_map(panel):
    out = {}
    for region in panel.get("regions") or []:
        kind = str(region.get("kind") or "")
        if kind:
            out[kind] = region
    return out


def draw_convert_page(msp, target, src_space, ox, oy, sheet_w, sheet_h, job, panel, page_index, warnings,
                      content_w=None, content_h=None):
    """单页转换图：中文说明 + 材料/制图表 + 层次槽；不克隆客户视图。"""
    margin = 8.0
    box(msp, ox, oy, sheet_w, sheet_h)
    box(msp, ox + margin, oy + margin, sheet_w - margin * 2, sheet_h - margin * 2)
    draw_zone_chrome(msp, ox, oy, sheet_w, sheet_h, margin)

    # 内容区仍内缩 2（不改材料/克隆槽逻辑）；四角信息表贴内框共边（去掉缝隙）
    inner_x = ox + margin + 2
    inner_y = oy + margin + 2
    frame_x0 = ox + margin
    frame_y0 = oy + margin
    frame_x1 = ox + sheet_w - margin
    frame_y1 = oy + sheet_h - margin

    title_w = min(320.0, sheet_w * 0.42)
    title_h = 96.0
    rev_h = 42.0
    title_x = frame_x1 - title_w
    mat_h = 0.0
    explosion = None
    page_count = 1

    if page_index == 0:
        rows = material_rows(job)
        mat_h = draw_material_table(msp, inner_x, inner_y, rows)
        draw_title_block(
            msp,
            title_x,
            frame_y0,
            title_w,
            title_h,
            blank(job.get("drawingNo")),
            sheet_w=sheet_w,
            sheet_h=sheet_h,
            page_index=page_index,
            page_count=page_count,
        )
        draw_revision(msp, title_x, frame_y1 - rev_h, title_w, rev_h)
        tooling_w = min(120.0, sheet_w * 0.16)
        tooling_h = 22.0
        draw_tooling_block(msp, frame_x0, frame_y1 - tooling_h, tooling_w, tooling_h)
        # 点检项：图框外右下（贴外框右侧、底边对齐），白线白字
        check_w = min(220.0, max(160.0, sheet_w * 0.30))
        check_h = min(130.0, max(85.0, sheet_h * 0.20))
        draw_checklist(msp, ox + sheet_w + 8.0, oy, check_w, check_h)
        if not rows:
            warnings.append("没有认到 MATERIAL 行，转换图左下角只画了空表头")
        # 爆炸槽：材料表右侧、制图表左侧；有 stackDxfPath 则覆盖三维层次，否则材料示意
        mat_w = 18.0 + 112.0 + 40.0 + 36.0
        ex_x = inner_x + mat_w + 10
        ex_w = max(40.0, title_x - ex_x - 8)
        ex_h = max(title_h, mat_h + 10)
        # 策略 A：页幅已按 _stackNeed 加高时，槽高吃满；槽宽取页内可用宽（页已加宽）
        need = job.get("_stackNeed")
        if need:
            try:
                ex_h = max(ex_h, float(need[1]))
            except Exception:
                pass
        explosion = draw_explosion_slot(
            msp,
            ex_x,
            inner_y,
            ex_w,
            ex_h,
            warnings,
            rows,
            target=target,
            stack_path=blank(job.get("stackDxfPath")),
        )
        explosion["page"] = 0
        bottom_chrome = ex_h + 12
    else:
        cell_text(
            msp,
            inner_x,
            oy + sheet_h - margin - 10,
            120,
            8,
            f"转换图 第 {page_index + 1} 页",
            3.0,
            color=LABEL,
        )
        # 续页也贴右下制图表 + 右上修订栏（SCALE/SIZE/UNIT/SHEET 白格留空）
        draw_title_block(
            msp,
            title_x,
            frame_y0,
            title_w,
            title_h,
            blank(job.get("drawingNo")),
            sheet_w=sheet_w,
            sheet_h=sheet_h,
            page_index=page_index,
            page_count=page_count,
        )
        draw_revision(msp, title_x, frame_y1 - rev_h, title_w, rev_h)
        bottom_chrome = title_h + 8

    # 首页说明区：宽度跟客户 NOTE 列，不够则换行；框高按行数；顶对齐
    if page_index == 0:
        zh_lines = notes_zh_lines(job)
        if zh_lines:
            avail_top = frame_y1 - rev_h - 8.0
            note_w = customer_note_col_w(job)
            if explosion:
                ex_x = float(explosion.get("x") or 0)
                beside = ex_x - inner_x - NOTES_GAP
                if beside >= 120.0:
                    note_w = min(note_w, beside)
            _, note_h = notes_zh_panel_need(zh_lines, NOTES_CHAR_H, note_w)
            note_y = avail_top - note_h
            if note_y < inner_y:
                note_y = inner_y
            if draw_notes_zh_panel(
                msp, inner_x, note_y, note_w, note_h, zh_lines, warnings, char_h=NOTES_CHAR_H
            ):
                warnings.append(f"已写入中文说明 {len(zh_lines)} 条（独立专区）")
    return explosion


def draw_convert_sheet(msp, ox, oy, sheet_w, sheet_h, job, warnings):
    """兼容旧调用：无源图时只画材料与图框。"""
    draw_convert_page(msp, None, None, ox, oy, sheet_w, sheet_h, job, None, 0, warnings)


def expand_panels_content(src_space, panels, warnings):
    """
    合成前：按源图真实图元外包扩展每幅 content（只扩不缩）。
    须在 page_sheet_size 之前调用，否则纸幅仍按偏窄窗计算。
    不改 title/rev 排除区。
    """
    if src_space is None or not panels:
        return
    for i, panel in enumerate(panels):
        if not isinstance(panel, dict):
            continue
        regions = panel.get("regions")
        if not isinstance(regions, list):
            continue
        rmap = region_map(panel)
        content = rmap.get("content")
        if not content:
            continue
        excludes = [rmap[k] for k in ("title", "rev_tl", "rev_tr") if k in rmap]
        frame = rmap.get("frame")
        limit = frame if frame else {
            "x0": float(panel.get("x0", content["x0"])),
            "y0": float(panel.get("y0", content["y0"])),
            "x1": float(panel.get("x1", content["x1"])),
            "y1": float(panel.get("y1", content["y1"])),
        }
        expanded = expand_content_window(src_space, content, excludes=excludes, limit=limit)
        if expanded is content:
            continue
        if (
            abs(expanded["x0"] - content["x0"]) < 0.5
            and abs(expanded["y0"] - content["y0"]) < 0.5
            and abs(expanded["x1"] - content["x1"]) < 0.5
            and abs(expanded["y1"] - content["y1"]) < 0.5
        ):
            continue
        for region in regions:
            if isinstance(region, dict) and str(region.get("kind") or "") == "content":
                region["x0"] = expanded["x0"]
                region["y0"] = expanded["y0"]
                region["x1"] = expanded["x1"]
                region["y1"] = expanded["y1"]
                break
        warnings.append(f"第 {i + 1} 页 content 窗已按图元外包扩展（防客户内容缺一块）")


def build_template(src, target, job, warnings):
    """
    上下布局：
    上 = 客户原图对照（尽量 1:1 整图复制，不改客户图元）；
    下 = 单页转换图：中文说明 + 材料表 + 层次槽 + 图框（不克隆客户视图）。
    skipCustomerContent=True 时：无客户二维，只出图框+审校说明/材料+层次（若有）。
    """
    frame_layer(target)
    msp = target.modelspace()
    skip_customer = bool(job.get("skipCustomerContent"))

    layout = job.get("sheetLayout") or {}
    panels = layout.get("panels") or []
    customer_panels = max(1, int(layout.get("panelCount") or len(panels) or 1))

    # 工程图合成：先量层次外包，供转换页整页放大
    prepare_stack_need(job, warnings)

    sheet_w, sheet_h, cw, ch, _bc, _tc = page_sheet_size({}, job, 0)
    # 客户图与转换图之间的竖直间距（CAD Y 向上：客户在上、生成在下）
    gap_v = max(80.0, PAGE_GAP * 2)

    if skip_customer:
        warnings.append("未使用客户二维图纸，工程图不含客户原图对照")
    else:
        cust_y = sheet_h + gap_v
        # 窗口宽高只作 1:1 上限，正常一行客户图不缩小
        used_w, used_h = place_customer(
            src,
            target,
            (0.0, cust_y, 50000.0, 20000.0),
            warnings,
            one_to_one=True,
        )
        if used_w < 1:
            warnings.append("客户原图未放入，仍生成转换图")
        else:
            try:
                lab = msp.add_text(
                    f"客户原图对照（共 {customer_panels} 幅）",
                    dxfattribs={"layer": "CUSTOMER", "height": 5.0, "color": TEXT, "style": "FACTORY_CN"},
                )
                lab.dxf.insert = (0.0, cust_y + used_h + 4.0)
            except Exception:
                pass
        warnings.append("客户图纸仅上方原样对照，转换图不克隆客户视图")

    explosion = None
    slot = draw_convert_page(
        msp, target, None, 0.0, 0.0, sheet_w, sheet_h, job, None, 0, warnings,
        content_w=cw, content_h=ch,
    )
    if slot:
        explosion = slot
        job["explosionSlot"] = explosion
        mats = material_rows(job)
        if blank(job.get("stackDxfPath")):
            warnings.append("转换图已写入中文说明、材料表，并用三维层次覆盖爆炸槽")
        elif mats:
            warnings.append(f"转换图已写入中文说明、材料表，爆炸槽为材料层次示意（{len(mats)} 层）")
        else:
            warnings.append("转换图已写入中文说明与材料表；爆炸槽无材料可填")
    else:
        warnings.append("转换图已写入中文说明与材料表")

def main() -> int:
    if len(sys.argv) < 4:
        print(json.dumps({"ok": False, "warnings": ["参数不足"]}), flush=True)
        return 1
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    job_path = Path(sys.argv[3])
    warnings: list[str] = []
    job = json.loads(job_path.read_text(encoding="utf-8"))
    try:
        source, _auditor = recover.readfile(str(src))
    except Exception:
        if bool(job.get("skipCustomerContent")):
            # 无客户二维：空壳源图即可
            source = ezdxf.new("R2018")
            warnings.append("无客户 DXF，使用空源图生成工程图框")
        else:
            print(json.dumps({"ok": False, "warnings": ["DXF 无法打开"]}), flush=True)
            return 1
    target = ezdxf.new("R2018")
    try:
        target.units = 4
    except Exception:
        pass
    font = pick_font(warnings)
    ensure_style(target, font)
    try:
        build_template(source, target, job, warnings)
        target.saveas(str(dst))
    except Exception:
        print(json.dumps({"ok": False, "warnings": ["转换图无法生成"]}), flush=True)
        return 1
    slot = job.get("explosionSlot")
    print(
        json.dumps(
            {
                "ok": True,
                "replaced": 0,
                "covered": False,
                "table": True,
                "font": font,
                "warnings": warnings,
                "explosionSlot": slot,
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
