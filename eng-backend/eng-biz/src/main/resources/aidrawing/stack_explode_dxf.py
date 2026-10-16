#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""层次爆炸 DXF：用 ezdxf R2018 写出，格式与图纸转换产物一致，便于国产 CAD 打开。"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import ezdxf

FONT_CANDIDATES = (
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/STHeiti Light.ttc",
    "/Library/Fonts/Arial Unicode.ttf",
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simsun.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
)

LAYER_GEOM = "STACK_EXPLODE"
LAYER_LEADER = "STACK_LEADER"
LAYER_TEXT = "STACK_TEXT"
LAYER_TABLE = "STACK_TABLE"
UNLABELED = "未标注"
# ACI 3 = 绿，贴近工程图材料表
COLOR_GREEN = 3
LINE1_RE = re.compile(
    r"^(.+?),\s*COLOR:\s*(.+?),\s*(.+?)\s*mm\s*THICK$",
    re.IGNORECASE,
)


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


def ensure_layers(doc) -> None:
    for name, color in (
        (LAYER_GEOM, 7),
        (LAYER_LEADER, 7),
        (LAYER_TEXT, 7),
        (LAYER_TABLE, COLOR_GREEN),
    ):
        if name not in doc.layers:
            doc.layers.add(name, color=color)


def add_line(msp, layer: str, x1: float, y1: float, x2: float, y2: float, color: int = 7) -> None:
    msp.add_line((x1, y1), (x2, y2), dxfattribs={"layer": layer, "color": color})


def add_text(
    msp,
    layer: str,
    x: float,
    y: float,
    height: float,
    text: str,
    color: int = 7,
) -> None:
    content = (text or "").replace("\r", " ").replace("\n", " ").replace("\\", "/")
    msp.add_text(
        content,
        dxfattribs={
            "layer": layer,
            "height": float(height),
            "style": "FACTORY_CN",
            "color": color,
            "insert": (float(x), float(y)),
        },
    )


def add_mtext(
    msp,
    layer: str,
    x: float,
    y: float,
    height: float,
    width: float,
    text: str,
    color: int = 7,
    attachment: int = 1,
) -> None:
    """栏内换行：attachment 1=左上 3=右上。"""
    content = (text or "").replace("\r\n", "\n").replace("\r", "\n").replace("\\", "/")
    body = content.replace("\n", "\\P")
    mtext = msp.add_mtext(
        body,
        dxfattribs={
            "layer": layer,
            "style": "FACTORY_CN",
            "char_height": float(height),
            "color": color,
            "attachment_point": int(attachment),
        },
    )
    mtext.set_location((float(x), float(y)))
    mtext.dxf.width = max(float(width), float(height) * 2)


def _safe_float(v, default: float | None = None) -> float | None:
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def text_width(text: str, height: float) -> float:
    """等宽近似：中文略宽；拉丁按比例字体偏宽估，避免左旁注右缘顶进序号框。"""
    w = 0.0
    for ch in text or "":
        if ord(ch) > 127:
            w += height * 1.05
        else:
            w += height * 0.78
    return max(w, height * 0.5)


def add_callout_label(
    msp,
    layer: str,
    x: float,
    y: float,
    height: float,
    text: str,
    *,
    right_align: bool = False,
    color: int = 7,
) -> None:
    """旁注说明：右对齐用 MTEXT 右中附着，右缘钉在 x。"""
    content = (text or "").replace("\r", " ").replace("\n", " ").replace("\\", "/").strip()
    if not content:
        return
    if not right_align:
        add_text(msp, layer, x, y, height, content, color)
        return
    # 栏宽略大于估宽，避免折行；附着点 6=中右
    col_w = max(text_width(content, height) * 1.2, height * 4)
    add_mtext(msp, layer, x, y, height, col_w, content, color, attachment=6)


def wrap_lines(text: str, height: float, max_w: float, max_lines: int = 4) -> list[str]:
    """按近似字宽折行；超长词强制切开。"""
    raw = (text or "").strip()
    if not raw:
        return [""]
    if max_w <= height:
        return [raw[: max(1, int(max_w / max(height * 0.5, 0.1)))]]
    lines: list[str] = []
    buf = ""
    for ch in raw:
        trial = buf + ch
        if text_width(trial, height) <= max_w:
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
        # 末行撑满后截断加省略
        last = lines[-1]
        while last and text_width(last + "…", height) > max_w:
            last = last[:-1]
        lines[-1] = (last or lines[-1][:1]) + "…"
    return lines or [raw]


def box_width_model(callout: dict) -> float:
    no = str(callout.get("calloutNo") or "").strip() or "?"
    return max(3.2, len(no) * 1.6 + 1.6)


def plate_bounds(job: dict) -> tuple[float, float, float, float]:
    """仅实体包围盒（用于左右分列中线）。"""
    min_x = min_y = float("inf")
    max_x = max_y = float("-inf")
    for plate in job.get("plates") or []:
        for seg in plate.get("segments") or []:
            try:
                min_x = min(min_x, float(seg["x1"]), float(seg["x2"]))
                max_x = max(max_x, float(seg["x1"]), float(seg["x2"]))
                min_y = min(min_y, float(seg["y1"]), float(seg["y2"]))
                max_y = max(max_y, float(seg["y1"]), float(seg["y2"]))
            except (KeyError, TypeError, ValueError):
                continue
    if not (min_x < max_x and min_y < max_y):
        return 0.0, 0.0, 100.0, 100.0
    return min_x, min_y, max_x, max_y


def is_left_callout(callout: dict, mid_x: float) -> bool:
    """与前端一致：序号方框中心在实体中线左侧即为左旁注。"""
    tx = _safe_float(callout.get("textX"))
    if tx is None:
        return False
    return tx + box_width_model(callout) / 2 < mid_x


def content_extents(job: dict) -> tuple[float, float, float, float]:
    """实体 + 旁注（含旁注全文伸出）包围盒；无内容时给默认框。"""
    min_x = min_y = float("inf")
    max_x = max_y = float("-inf")

    def absorb(x: float, y: float) -> None:
        nonlocal min_x, min_y, max_x, max_y
        min_x = min(min_x, x)
        min_y = min(min_y, y)
        max_x = max(max_x, x)
        max_y = max(max_y, y)

    pmin_x, pmin_y, pmax_x, pmax_y = plate_bounds(job)
    absorb(pmin_x, pmin_y)
    absorb(pmax_x, pmax_y)
    mid_x = (pmin_x + pmax_x) / 2

    for callout in job.get("callouts") or []:
        if not isinstance(callout, dict):
            continue
        ax = _safe_float(callout.get("anchorX"))
        ay = _safe_float(callout.get("anchorY"))
        tx = _safe_float(callout.get("textX"))
        ty = _safe_float(callout.get("textY"))
        if ax is not None and ay is not None:
            absorb(ax, ay)
        if tx is not None and ty is not None:
            box_w = box_width_model(callout)
            box_h = 2.8
            absorb(tx, ty - box_h / 2)
            absorb(tx + box_w, ty + box_h / 2)
            line1 = str(callout.get("line1") or "").strip()
            # 与编辑器一致：line2=特殊要求；旧数据可能只写 specialReq
            line2 = str(callout.get("line2") or callout.get("specialReq") or "").strip()
            text_h = 1.5
            tw = max(text_width(line1, text_h), text_width(line2, text_h) if line2 else 0.0)
            gap = 2.0
            # CAD Y 向上：line1 在上(ty+)、line2 在下(ty-)，与画布「材料上、特殊要求下」一致
            if is_left_callout(callout, mid_x):
                # 左侧：文字在方框左边（右缘钉在 tx-gap）
                absorb(tx - gap - tw * 1.2, ty)
                absorb(tx - gap - tw * 1.2, ty + (0.9 if line2 else 0.0))
                if line2:
                    absorb(tx - gap - tw * 1.2, ty - 1.0)
            else:
                absorb(tx + box_w + gap + tw * 1.2, ty)
                absorb(tx + box_w + gap + tw * 1.2, ty + (0.9 if line2 else 0.0))
                if line2:
                    absorb(tx + box_w + gap + tw * 1.2, ty - 1.0)
        for e in callout.get("elbows") or []:
            x = _safe_float(e.get("x"))
            y = _safe_float(e.get("y"))
            if x is not None and y is not None:
                absorb(x, y)

    if not (min_x < max_x and min_y < max_y):
        return 0.0, 0.0, 100.0, 100.0
    return min_x, min_y, max_x, max_y


def parse_callout_fields(callout: dict) -> tuple[str, str, str, str]:
    """返回 no, material, thickness_str, color（全文，不截断）。"""
    no = str(callout.get("calloutNo") or "").strip() or "?"
    material = str(callout.get("material") or "").strip()
    color = str(callout.get("color") or "").strip()
    thick_raw = callout.get("thickness")
    thick_num = _safe_float(thick_raw)

    if not material or not color or thick_num is None:
        line1 = str(callout.get("line1") or "").strip()
        m = LINE1_RE.match(line1)
        if m:
            if not material:
                material = m.group(1).strip()
            if not color:
                color = m.group(2).strip()
            if thick_num is None:
                thick_num = _safe_float(m.group(3))

    if not material:
        material = UNLABELED
    if not color:
        color = UNLABELED
    if thick_num is None:
        thick_s = f"{UNLABELED} mm"
    else:
        thick_s = f"{thick_num:g} mm"

    return no, material.upper(), thick_s, color.upper()


def build_material_rows(job: dict) -> list[tuple[str, str, str, str]]:
    rows: list[tuple[str, str, str, str]] = []
    for callout in job.get("callouts") or []:
        if not isinstance(callout, dict):
            continue
        rows.append(parse_callout_fields(callout))

    def sort_key(row: tuple[str, str, str, str]):
        no = row[0]
        try:
            return (0, float(no))
        except ValueError:
            return (1, no)

    rows.sort(key=sort_key)
    return rows


def fit_material_lines(text: str, text_h: float, mat_inner: float) -> tuple[list[str], float]:
    """材料名：优先换行；单行仍超宽则缩字；返回 (行列表, 字高)。"""
    cell_h = text_h
    lines = wrap_lines(text, cell_h, mat_inner, max_lines=4)
    if len(lines) == 1 and text_width(lines[0], cell_h) > mat_inner:
        shrink = cell_h
        while shrink > 0.9 and text_width(text, shrink) > mat_inner:
            shrink -= 0.12
        if text_width(text, shrink) <= mat_inner:
            return [text], shrink
        return wrap_lines(text, shrink, mat_inner, max_lines=4), shrink
    return lines, cell_h


def draw_material_table(msp, job: dict) -> None:
    """
    内容包围盒右下角材料表：绿框绿字，表头在下，序号自下而上递增。
    材料列超长自动换行；仍超则缩字，避免穿出格线。
    """
    rows = build_material_rows(job)
    header = ("NO.", "MATERIAL NAME", "THICKNESS", "COLOR")

    min_x, min_y, max_x, max_y = content_extents(job)
    span_x = max(max_x - min_x, 1.0)
    span_y = max(max_y - min_y, 1.0)

    text_h = max(1.2, min(2.2, span_y * 0.016, span_x * 0.011))
    pad = text_h * 0.35
    line_gap = text_h * 1.25

    col_ws = [0.0, 0.0, 0.0, 0.0]
    for cell in (header[0],) + tuple(r[0] for r in rows):
        col_ws[0] = max(col_ws[0], text_width(cell, text_h) + pad * 2)
    for cell in (header[2],) + tuple(r[2] for r in rows):
        col_ws[2] = max(col_ws[2], text_width(cell, text_h) + pad * 2)
    for cell in (header[3],) + tuple(r[3] for r in rows):
        col_ws[3] = max(col_ws[3], text_width(cell, text_h) + pad * 2)
    mat_natural = text_width(header[1], text_h) + pad * 2
    for r in rows:
        mat_natural = max(mat_natural, text_width(r[1], text_h) + pad * 2)
    mat_cap = max(span_x * 0.28, text_h * 22, 36.0)
    col_ws[1] = max(min(mat_natural, mat_cap), text_h * 10 + pad * 2)
    mat_inner = max(col_ws[1] - pad * 2, text_h * 4)

    # (no, mat_lines, thick, color, row_h, cell_h)
    packed: list[tuple[str, list[str], str, str, float, float]] = []
    packed.append((header[0], [header[1]], header[2], header[3], text_h * 1.55, text_h))
    for r in rows:
        lines, cell_h = fit_material_lines(r[1], text_h, mat_inner)
        rh = max(text_h * 1.55, len(lines) * line_gap + pad)
        packed.append((r[0], lines, r[2], r[3], rh, cell_h))

    table_w = sum(col_ws)
    table_h = sum(p[4] for p in packed)
    gap = max(span_x * 0.06, text_h * 6, 8.0)
    x0 = max_x + gap
    y_bottom = min_y
    y = y_bottom

    for no, mat_lines, thick, color, rh, cell_h in packed:
        x = x0
        for w in col_ws:
            add_line(msp, LAYER_TABLE, x, y, x + w, y, COLOR_GREEN)
            add_line(msp, LAYER_TABLE, x, y + rh, x + w, y + rh, COLOR_GREEN)
            add_line(msp, LAYER_TABLE, x, y, x, y + rh, COLOR_GREEN)
            add_line(msp, LAYER_TABLE, x + w, y, x + w, y + rh, COLOR_GREEN)
            x += w
        # NO
        add_text(msp, LAYER_TABLE, x0 + pad, y + (rh - text_h) * 0.5, text_h, no, COLOR_GREEN)
        # MATERIAL
        mx = x0 + col_ws[0]
        if len(mat_lines) > 1:
            add_mtext(
                msp,
                LAYER_TABLE,
                mx + pad,
                y + rh - pad * 0.5,
                cell_h,
                mat_inner,
                "\n".join(mat_lines),
                COLOR_GREEN,
                attachment=1,
            )
        else:
            add_text(
                msp,
                LAYER_TABLE,
                mx + pad,
                y + (rh - cell_h) * 0.5,
                cell_h,
                mat_lines[0] if mat_lines else "",
                COLOR_GREEN,
            )
        # THICKNESS / COLOR
        tx = mx + col_ws[1]
        add_text(msp, LAYER_TABLE, tx + pad, y + (rh - text_h) * 0.5, text_h, thick, COLOR_GREEN)
        cx = tx + col_ws[2]
        add_text(msp, LAYER_TABLE, cx + pad, y + (rh - text_h) * 0.5, text_h, color, COLOR_GREEN)
        y += rh

    add_line(msp, LAYER_TABLE, x0, y_bottom, x0 + table_w, y_bottom, COLOR_GREEN)
    add_line(msp, LAYER_TABLE, x0, y_bottom + table_h, x0 + table_w, y_bottom + table_h, COLOR_GREEN)
    add_line(msp, LAYER_TABLE, x0, y_bottom, x0, y_bottom + table_h, COLOR_GREEN)
    add_line(msp, LAYER_TABLE, x0 + table_w, y_bottom, x0 + table_w, y_bottom + table_h, COLOR_GREEN)


def draw_callouts(msp, job: dict) -> None:
    """旁注：与编辑器一致——左侧文字在框左、引线接框右；右侧相反。"""
    pmin_x, _, pmax_x, _ = plate_bounds(job)
    mid_x = (pmin_x + pmax_x) / 2
    text_h = 1.5
    for callout in job.get("callouts") or []:
        if not isinstance(callout, dict):
            continue
        try:
            ax = float(callout["anchorX"])
            ay = float(callout["anchorY"])
            tx = float(callout["textX"])
            ty = float(callout["textY"])
        except (KeyError, TypeError, ValueError):
            continue
        left = is_left_callout(callout, mid_x)
        box_w = box_width_model(callout)
        box_h = 2.8
        # 引线终点：左旁注接方框右中，右旁注接方框左中
        attach_x = tx + box_w if left else tx
        path = [(ax, ay)]
        for e in callout.get("elbows") or []:
            try:
                path.append((float(e["x"]), float(e["y"])))
            except (KeyError, TypeError, ValueError):
                continue
        path.append((attach_x, ty))
        for i in range(len(path) - 1):
            add_line(msp, LAYER_LEADER, path[i][0], path[i][1], path[i + 1][0], path[i + 1][1])

        corners = [
            (tx, ty - box_h / 2),
            (tx + box_w, ty - box_h / 2),
            (tx + box_w, ty + box_h / 2),
            (tx, ty + box_h / 2),
            (tx, ty - box_h / 2),
        ]
        for i in range(len(corners) - 1):
            add_line(
                msp,
                LAYER_LEADER,
                corners[i][0],
                corners[i][1],
                corners[i + 1][0],
                corners[i + 1][1],
            )

        no = str(callout.get("calloutNo") or "").strip() or "?"
        line1 = str(callout.get("line1") or "").strip()
        line2 = str(callout.get("line2") or callout.get("specialReq") or "").strip()
        add_text(msp, LAYER_TEXT, tx + box_w / 2 - len(no) * 0.45, ty - 0.55, 1.8, no)
        gap = 2.0
        # CAD Y 向上：材料 line1 在上，特殊要求 line2 在下（对齐编辑器画布）
        if left:
            # 右对齐：右缘钉在方框左侧，避免估宽偏小导致文字顶进序号框
            if line2:
                add_callout_label(msp, LAYER_TEXT, tx - gap, ty + 0.9, text_h, line1, right_align=True)
                add_callout_label(msp, LAYER_TEXT, tx - gap, ty - 1.0, text_h, line2, right_align=True)
            else:
                add_callout_label(msp, LAYER_TEXT, tx - gap, ty, text_h, line1, right_align=True)
        else:
            y1 = ty + (0.9 if line2 else 0.55)
            add_callout_label(msp, LAYER_TEXT, tx + box_w + gap, y1, text_h, line1)
            if line2:
                add_callout_label(msp, LAYER_TEXT, tx + box_w + gap, ty - 1.0, text_h, line2)


def build(job: dict, out_path: Path) -> list[str]:
    warnings: list[str] = []
    doc = ezdxf.new("R2018")
    try:
        doc.units = 4  # mm
    except Exception:
        pass
    try:
        doc.header["$UCSICON"] = 0
    except Exception:
        pass
    font = pick_font(warnings)
    ensure_style(doc, font)
    ensure_layers(doc)
    msp = doc.modelspace()

    for plate in job.get("plates") or []:
        for seg in plate.get("segments") or []:
            try:
                x1 = float(seg["x1"])
                y1 = float(seg["y1"])
                x2 = float(seg["x2"])
                y2 = float(seg["y2"])
            except (KeyError, TypeError, ValueError):
                continue
            add_line(msp, LAYER_GEOM, x1, y1, x2, y2)

    draw_callouts(msp, job)

    try:
        draw_material_table(msp, job)
    except Exception as ex:
        warnings.append("材料表写入失败: " + str(ex))

    doc.saveas(str(out_path))
    return warnings


def main() -> int:
    if len(sys.argv) < 3:
        print(json.dumps({"ok": False, "warnings": ["参数不足：job.json out.dxf"]}), flush=True)
        return 1
    job_path = Path(sys.argv[1])
    out_path = Path(sys.argv[2])
    try:
        job = json.loads(job_path.read_text(encoding="utf-8"))
    except Exception:
        print(json.dumps({"ok": False, "warnings": ["job.json 无法读取"]}), flush=True)
        return 1
    try:
        warnings = build(job, out_path)
    except Exception as ex:
        print(json.dumps({"ok": False, "warnings": ["层次爆炸 DXF 无法生成: " + str(ex)]}), flush=True)
        return 1
    if not out_path.is_file() or out_path.stat().st_size <= 0:
        print(json.dumps({"ok": False, "warnings": ["输出文件为空"]}), flush=True)
        return 1
    print(json.dumps({"ok": True, "warnings": warnings}, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
