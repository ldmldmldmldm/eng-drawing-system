"""用 ezdxf 从 DXF 抽出图层、文字、标注等明细。只抄图里有的，不编造。"""
from __future__ import annotations

import json
import sys
from collections import Counter

import ezdxf
from ezdxf import recover

MAX_LAYERS = 500
MAX_TEXTS = 5000
MAX_DIMS = 2000
MAX_BLOCKS = 1000
MAX_LAYOUTS = 64
MAX_TYPE_COUNTS = 200
CONTENT_LIMIT = 500


def clip(text: str, limit: int = CONTENT_LIMIT) -> str:
    if text is None:
        return ""
    value = str(text).replace("\r\n", "\n").replace("\r", "\n").strip()
    if len(value) > limit:
        return value[:limit]
    return value


def point_xy(entity):
    try:
        if entity.dxftype() == "MTEXT":
            loc = entity.dxf.insert
        elif entity.dxftype() in ("TEXT", "ATTRIB", "ATTDEF"):
            loc = entity.dxf.insert
        elif hasattr(entity.dxf, "defpoint"):
            loc = entity.dxf.defpoint
        elif hasattr(entity.dxf, "insert"):
            loc = entity.dxf.insert
        else:
            return None, None
        return float(loc.x), float(loc.y)
    except Exception:
        return None, None


def xy(pt):
    if pt is None:
        return None, None
    try:
        return float(pt.x), float(pt.y)
    except Exception:
        return None, None


def dim_type_name(entity) -> str:
    try:
        code = int(entity.dxf.dimtype) & 0xF
    except Exception:
        code = -1
    return {
        0: "LINEAR",
        1: "ALIGNED",
        2: "ANGULAR",
        3: "DIAMETER",
        4: "RADIUS",
        5: "ANGULAR3P",
        6: "ORDINATE",
    }.get(code, "UNKNOWN")


def dim_geometry(entity):
    """抽出标注字位置、被测两端/圆心、箭头指向。失败字段留空，不编造。"""
    dim_type = dim_type_name(entity)
    tx, ty = None, None
    try:
        tx, ty = xy(entity.dxf.text_midpoint)
    except Exception:
        pass
    if tx is None:
        tx, ty = point_xy(entity)
    p1x = p1y = p2x = p2y = None
    try:
        p1x, p1y = xy(entity.dxf.defpoint2)
        p2x, p2y = xy(entity.dxf.defpoint3)
    except Exception:
        pass
    if dim_type in ("DIAMETER", "RADIUS"):
        try:
            far_x, far_y = xy(entity.dxf.defpoint)
            cx, cy = xy(entity.dxf.defpoint4)
            if cx is not None:
                p1x, p1y = far_x, far_y
                p2x, p2y = cx, cy
        except Exception:
            pass
    ax = ay = None
    feat_x, feat_y = mid(p1x, p1y, p2x, p2y)
    if feat_x is not None and tx is not None:
        dx, dy = feat_x - tx, feat_y - ty
        n = (dx * dx + dy * dy) ** 0.5
        if n > 1e-9:
            ax, ay = dx / n, dy / n
    return dim_type, tx, ty, p1x, p1y, p2x, p2y, ax, ay


def mid(x1, y1, x2, y2):
    if x1 is None or y1 is None or x2 is None or y2 is None:
        return None, None
    return (x1 + x2) / 2.0, (y1 + y2) / 2.0


def leader_vertices(entity):
    pts = []
    kind = entity.dxftype()
    try:
        if kind == "LEADER":
            for v in entity.vertices:
                x, y = xy(v)
                if x is not None:
                    pts.append([x, y])
        elif kind in ("MLEADER", "MULTILEADER"):
            ctx = entity.context
            for leader in getattr(ctx, "leaders", []) or []:
                for line in getattr(leader, "lines", []) or []:
                    for v in getattr(line, "vertices", []) or []:
                        x, y = xy(v)
                        if x is not None:
                            pts.append([x, y])
    except Exception:
        pass
    return pts[:64]


def entity_text(entity) -> str:
    kind = entity.dxftype()
    try:
        if kind == "MTEXT":
            return clip(entity.plain_text())
        if kind in ("TEXT", "ATTRIB", "ATTDEF"):
            return clip(entity.dxf.get("text", ""))
        if kind.startswith("DIMENSION") or kind == "DIMENSION":
            raw = entity.dxf.get("text", "")
            if raw and str(raw).strip() and str(raw).strip() != "<>":
                return clip(raw)
            try:
                measured = entity.get_measurement()
                if measured is not None:
                    return clip(f"{measured:.6g}")
            except Exception:
                pass
            return ""
        if kind in ("MULTILEADER", "MLEADER"):
            try:
                return clip(entity.context.mtext.plain_text())
            except Exception:
                return clip(entity.dxf.get("text", ""))
    except Exception:
        return ""
    return ""


def layer_rows(doc):
    rows = []
    truncated = False
    for layer in doc.layers:
        if len(rows) >= MAX_LAYERS:
            truncated = True
            break
        try:
            name = layer.dxf.name
        except Exception:
            continue
        if not name:
            continue
        color = 7
        linetype = "Continuous"
        try:
            color = int(layer.dxf.color)
        except Exception:
            pass
        try:
            linetype = str(layer.dxf.linetype or "Continuous")
        except Exception:
            pass
        on = True
        frozen = False
        try:
            on = bool(layer.is_on())
        except Exception:
            pass
        try:
            frozen = bool(layer.is_frozen())
        except Exception:
            pass
        rows.append(
            {
                "name": name,
                "on": on,
                "frozen": frozen,
                "color": color,
                "linetype": linetype,
            }
        )
    return rows, truncated


def walk_layouts(doc):
    found = []
    try:
        names = list(doc.layout_names())
    except Exception:
        names = ["Model"]
    for name in names:
        if len(found) >= MAX_LAYOUTS:
            break
        try:
            layout = doc.layout(name)
        except Exception:
            continue
        found.append((name or "Model", layout))
    if not found:
        found.append(("Model", doc.modelspace()))
    return found


def collect(doc):
    layers, layers_truncated = layer_rows(doc)
    texts = []
    dims = []
    leaders = []
    texts_truncated = False
    dims_truncated = False
    leaders_truncated = False
    type_counter = Counter()
    layouts_out = []

    for layout_name, layout in walk_layouts(doc):
        layouts_out.append(layout_name)
        try:
            entities = list(layout)
        except Exception:
            continue
        for entity in entities:
            try:
                kind = entity.dxftype()
            except Exception:
                continue
            type_counter[kind] += 1
            layer = "0"
            try:
                layer = str(entity.dxf.layer or "0")
            except Exception:
                pass
            if kind in ("TEXT", "MTEXT", "ATTRIB", "ATTDEF"):
                if len(texts) >= MAX_TEXTS:
                    texts_truncated = True
                    continue
                content = entity_text(entity)
                if not content:
                    continue
                x, y = point_xy(entity)
                texts.append(
                    {
                        "kind": kind,
                        "layer": layer,
                        "content": content,
                        "x": x,
                        "y": y,
                        "layout": layout_name,
                    }
                )
            elif kind == "DIMENSION" or kind.endswith("_DIMENSION"):
                if len(dims) >= MAX_DIMS:
                    dims_truncated = True
                    continue
                content = entity_text(entity)
                measurement = None
                try:
                    measurement = float(entity.get_measurement())
                except Exception:
                    measurement = None
                dim_type, tx, ty, p1x, p1y, p2x, p2y, ax, ay = dim_geometry(entity)
                x, y = tx, ty
                if x is None:
                    x, y = point_xy(entity)
                dims.append(
                    {
                        "kind": kind,
                        "layer": layer,
                        "text": content,
                        "measurement": measurement,
                        "x": x,
                        "y": y,
                        "layout": layout_name,
                        "dimType": dim_type,
                        "textX": tx,
                        "textY": ty,
                        "p1x": p1x,
                        "p1y": p1y,
                        "p2x": p2x,
                        "p2y": p2y,
                        "arrowX": ax,
                        "arrowY": ay,
                    }
                )
            elif kind in ("MULTILEADER", "MLEADER", "LEADER"):
                content = entity_text(entity)
                verts = leader_vertices(entity)
                x, y = point_xy(entity)
                if verts:
                    x, y = verts[-1][0], verts[-1][1]
                if len(leaders) >= MAX_TEXTS:
                    leaders_truncated = True
                else:
                    leaders.append(
                        {
                            "kind": kind,
                            "layer": layer,
                            "content": content,
                            "x": x,
                            "y": y,
                            "layout": layout_name,
                            "vertices": verts,
                        }
                    )
                if content:
                    if len(texts) >= MAX_TEXTS:
                        texts_truncated = True
                    else:
                        texts.append(
                            {
                                "kind": kind,
                                "layer": layer,
                                "content": content,
                                "x": x,
                                "y": y,
                                "layout": layout_name,
                            }
                        )

    blocks = []
    blocks_truncated = False
    try:
        for block in doc.blocks:
            name = block.name
            if not name or name.startswith("*"):
                continue
            if len(blocks) >= MAX_BLOCKS:
                blocks_truncated = True
                break
            blocks.append(name)
    except Exception:
        pass

    type_counts = [
        {"type": name, "count": count}
        for name, count in type_counter.most_common(MAX_TYPE_COUNTS)
    ]
    truncated = layers_truncated or texts_truncated or dims_truncated or leaders_truncated or blocks_truncated
    return {
        "layers": layers,
        "texts": texts,
        "dimensions": dims,
        "leaders": leaders,
        "blockNames": blocks,
        "layoutNames": layouts_out,
        "typeCounts": type_counts,
        "truncated": truncated,
        "layersTruncated": layers_truncated,
        "textsTruncated": texts_truncated,
        "dimensionsTruncated": dims_truncated,
        "source": "ezdxf",
        "message": "已按 DXF 对象抽出图层、文字、标注、引线及尺寸定义点；炸开字与代理对象不在此列。",
    }


def main():
    if len(sys.argv) < 2:
        print("{}", end="")
        sys.exit(1)
    path = sys.argv[1]
    try:
        doc, _auditor = recover.readfile(path)
    except Exception:
        try:
            doc = ezdxf.readfile(path)
        except Exception:
            print(
                json.dumps(
                    {
                        "layers": [],
                        "texts": [],
                        "dimensions": [],
                        "leaders": [],
                        "blockNames": [],
                        "layoutNames": [],
                        "typeCounts": [],
                        "truncated": False,
                        "layersTruncated": False,
                        "textsTruncated": False,
                        "dimensionsTruncated": False,
                        "source": "",
                        "message": "ezdxf 未能打开这份 DXF。",
                    },
                    ensure_ascii=False,
                ),
                end="",
            )
            sys.exit(1)
    payload = collect(doc)
    print(json.dumps(payload, ensure_ascii=False), end="")


if __name__ == "__main__":
    main()
