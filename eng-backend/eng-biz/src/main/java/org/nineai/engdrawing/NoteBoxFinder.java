package org.nineai.engdrawing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从线段里找方框序号。竖向对齐、右侧有文字笔画的一列是材料说明，其余相近方框是视图序号。
 */
final class NoteBoxFinder {

    record Window(double x0, double y0, double x1, double y1) {
    }

    record Found(List<Window> notes, List<Window> callouts) {
        static Found empty() {
            return new Found(List.of(), List.of());
        }
    }

    private record Seg(double x1, double y1, double x2, double y2) {
    }

    private record Rect(double x0, double y0, double x1, double y1) {
        double width() {
            return x1 - x0;
        }

        double height() {
            return y1 - y0;
        }

        double cx() {
            return (x0 + x1) / 2.0;
        }

        double cy() {
            return (y0 + y1) / 2.0;
        }
    }

    private NoteBoxFinder() {
    }

    static Found find(String dxf) {
        List<Seg> segs = readLines(dxf);
        if (segs.size() < 8) {
            return Found.empty();
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Seg seg : segs) {
            minX = Math.min(minX, Math.min(seg.x1, seg.x2));
            minY = Math.min(minY, Math.min(seg.y1, seg.y2));
            maxX = Math.max(maxX, Math.max(seg.x1, seg.x2));
            maxY = Math.max(maxY, Math.max(seg.y1, seg.y2));
        }
        double span = Math.max(maxX - minX, maxY - minY);
        if (span < 1) {
            return Found.empty();
        }
        List<Rect> rects = uniqueRects(axisRects(segs, span));
        if (rects.isEmpty()) {
            return Found.empty();
        }
        List<Rect> column = noteColumn(rects, segs, span);
        if (column.size() < 2) {
            return Found.empty();
        }
        List<Window> notes = new ArrayList<>();
        for (Rect rect : column) {
            notes.add(noteWindow(rect, segs, span));
        }
        List<Window> callouts = new ArrayList<>();
        double noteH = column.get(0).height();
        for (Rect rect : rects) {
            if (column.contains(rect)) {
                continue;
            }
            if (rect.height() < noteH * 0.6 || rect.height() > noteH * 1.5) {
                continue;
            }
            double pad = rect.height() * 0.35;
            callouts.add(new Window(rect.x0 - pad, rect.y0 - pad, rect.x1 + pad, rect.y1 + pad));
        }
        return new Found(notes, callouts);
    }

    private static List<Seg> readLines(String dxf) {
        List<Seg> segs = new ArrayList<>();
        if (dxf == null || dxf.isBlank()) {
            return segs;
        }
        String[] rows = dxf.split("\\R", -1);
        boolean inBlock = false;
        for (int i = 0; i + 1 < rows.length; ) {
            String code = rows[i].trim();
            String value = rows[i + 1].trim();
            if (!"0".equals(code)) {
                i += 2;
                continue;
            }
            if ("BLOCK".equals(value)) {
                inBlock = true;
                i += 2;
                continue;
            }
            if ("ENDBLK".equals(value)) {
                inBlock = false;
                i += 2;
                continue;
            }
            if (inBlock) {
                i += 2;
                continue;
            }
            if ("LINE".equals(value)) {
                i = readLineEntity(rows, i + 2, segs);
                continue;
            }
            if ("LWPOLYLINE".equals(value)) {
                i = readLwPolylineEntity(rows, i + 2, segs);
                continue;
            }
            i += 2;
        }
        return segs;
    }

    private static int readLineEntity(String[] rows, int start, List<Seg> segs) {
        Double x1 = null;
        Double y1 = null;
        Double x2 = null;
        Double y2 = null;
        int i = start;
        while (i + 1 < rows.length && !"0".equals(rows[i].trim())) {
            String group = rows[i].trim();
            String text = rows[i + 1].trim();
            i += 2;
            try {
                double number = Double.parseDouble(text);
                if ("10".equals(group)) {
                    x1 = number;
                } else if ("20".equals(group)) {
                    y1 = number;
                } else if ("11".equals(group)) {
                    x2 = number;
                } else if ("21".equals(group)) {
                    y2 = number;
                }
            } catch (NumberFormatException ignored) {
                // 非坐标组码
            }
        }
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            segs.add(new Seg(x1, y1, x2, y2));
        }
        return i;
    }

    /** 闭合/近似矩形的 LWPOLYLINE 拆成边，供方框检测。 */
    private static int readLwPolylineEntity(String[] rows, int start, List<Seg> segs) {
        List<double[]> pts = new ArrayList<>();
        boolean closed = false;
        int i = start;
        Double pendingX = null;
        while (i + 1 < rows.length && !"0".equals(rows[i].trim())) {
            String group = rows[i].trim();
            String text = rows[i + 1].trim();
            i += 2;
            if ("70".equals(group)) {
                try {
                    closed = (Integer.parseInt(text.trim()) & 1) == 1;
                } catch (NumberFormatException ignored) {
                    // ignore
                }
                continue;
            }
            try {
                double number = Double.parseDouble(text);
                if ("10".equals(group)) {
                    pendingX = number;
                } else if ("20".equals(group) && pendingX != null) {
                    pts.add(new double[] {pendingX, number});
                    pendingX = null;
                }
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        int n = pts.size();
        if (n < 2) {
            return i;
        }
        int edgeCount = closed || n >= 4 ? n : n - 1;
        for (int k = 0; k < edgeCount; k++) {
            double[] a = pts.get(k);
            double[] b = pts.get((k + 1) % n);
            if (!closed && k == n - 1) {
                break;
            }
            segs.add(new Seg(a[0], a[1], b[0], b[1]));
        }
        return i;
    }

    private static List<Rect> axisRects(List<Seg> segs, double span) {
        double tol = span * 0.0008;
        List<double[]> horiz = new ArrayList<>();
        List<double[]> vert = new ArrayList<>();
        for (Seg seg : segs) {
            double dx = Math.abs(seg.x2 - seg.x1);
            double dy = Math.abs(seg.y2 - seg.y1);
            if (dy <= tol && dx > tol * 4) {
                horiz.add(new double[] {(seg.y1 + seg.y2) / 2.0, Math.min(seg.x1, seg.x2), Math.max(seg.x1, seg.x2)});
            } else if (dx <= tol && dy > tol * 4) {
                vert.add(new double[] {(seg.x1 + seg.x2) / 2.0, Math.min(seg.y1, seg.y2), Math.max(seg.y1, seg.y2)});
            }
        }
        horiz.sort(Comparator.comparingDouble(row -> row[0]));
        double cell = span * 0.0015;
        Map<Integer, List<double[]>> vindex = new HashMap<>();
        for (double[] row : vert) {
            int key = (int) Math.round(row[0] / cell);
            vindex.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<Rect> rects = new ArrayList<>();
        for (int i = 0; i < horiz.size(); i++) {
            double[] bottom = horiz.get(i);
            double length = bottom[2] - bottom[1];
            if (length < span * 0.004 || length > span * 0.04) {
                continue;
            }
            int limit = Math.min(horiz.size(), i + 80);
            for (int j = i + 1; j < limit; j++) {
                double[] top = horiz.get(j);
                double height = top[0] - bottom[0];
                if (height <= tol) {
                    continue;
                }
                if (height > length * 1.6) {
                    break;
                }
                if (height < length * 0.55) {
                    continue;
                }
                double topLen = top[2] - top[1];
                if (Math.abs(topLen - length) > length * 0.15) {
                    continue;
                }
                if (Math.abs(top[1] - bottom[1]) > length * 0.2 || Math.abs(top[2] - bottom[2]) > length * 0.2) {
                    continue;
                }
                if (hasVert(vindex, cell, bottom[1], bottom[0], top[0], length)
                        && hasVert(vindex, cell, bottom[2], bottom[0], top[0], length)) {
                    double x0 = Math.min(bottom[1], top[1]);
                    double x1 = Math.max(bottom[2], top[2]);
                    rects.add(new Rect(x0, bottom[0], x1, top[0]));
                }
            }
        }
        return rects;
    }

    private static boolean hasVert(
            Map<Integer, List<double[]>> vindex,
            double cell,
            double x,
            double y0,
            double y1,
            double length) {
        int key = (int) Math.round(x / cell);
        for (int near = key - 1; near <= key + 1; near++) {
            List<double[]> rows = vindex.get(near);
            if (rows == null) {
                continue;
            }
            for (double[] row : rows) {
                if (Math.abs(row[0] - x) > length * 0.2) {
                    continue;
                }
                if (row[1] <= y0 + (y1 - y0) * 0.25 && row[2] >= y1 - (y1 - y0) * 0.25) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Rect> uniqueRects(List<Rect> rects) {
        List<Rect> kept = new ArrayList<>();
        for (Rect rect : rects) {
            boolean same = false;
            for (Rect prior : kept) {
                if (Math.abs(rect.cx() - prior.cx()) < rect.width() * 0.4
                        && Math.abs(rect.cy() - prior.cy()) < rect.height() * 0.4) {
                    same = true;
                    break;
                }
            }
            if (!same) {
                kept.add(rect);
            }
        }
        return kept;
    }

    /** 同一竖列、尺寸接近、右侧有文字的一组方框。 */
    private static List<Rect> noteColumn(List<Rect> rects, List<Seg> segs, double span) {
        List<Rect> best = List.of();
        int bestScore = -1;
        for (Rect seed : rects) {
            List<Rect> group = new ArrayList<>();
            for (Rect rect : rects) {
                if (Math.abs(rect.x0 - seed.x0) > span * 0.004) {
                    continue;
                }
                if (Math.abs(rect.width() - seed.width()) > seed.width() * 0.25) {
                    continue;
                }
                if (Math.abs(rect.height() - seed.height()) > seed.height() * 0.35) {
                    continue;
                }
                group.add(rect);
            }
            if (group.size() < 2) {
                continue;
            }
            int text = 0;
            for (Rect rect : group) {
                text += strokeCount(segs, rect.x1, rect.x1 + span * 0.08, rect.y0, rect.y1);
            }
            int score = group.size() * 1000 + text;
            if (score > bestScore) {
                bestScore = score;
                group.sort(Comparator.comparingDouble(Rect::y0));
                best = group;
            }
        }
        return best;
    }

    private static Window noteWindow(Rect rect, List<Seg> segs, double span) {
        double bin = Math.max(span * 0.002, 0.5);
        double gap = span * 0.02;
        double y0 = rect.y0 - rect.height() * 0.15;
        double y1 = rect.y1 + rect.height() * 0.15;
        double end = rect.x1;
        double x = rect.x1;
        double limit = rect.x0 + span * 0.42;
        boolean started = false;
        while (x < limit) {
            int count = strokeCount(segs, x, x + bin, y0, y1);
            if (count >= 2) {
                started = true;
                end = x + bin;
                x += bin;
            } else if (started && x - end >= gap) {
                break;
            } else {
                x += bin;
            }
        }
        if (end < rect.x1 + rect.width() * 4) {
            end = rect.x1 + rect.width() * 8;
        }
        double padY = rect.height() * 0.45;
        return new Window(rect.x0 - rect.width() * 0.2, rect.y0 - padY, end + bin, rect.y1 + padY);
    }

    private static int strokeCount(List<Seg> segs, double x0, double x1, double y0, double y1) {
        int count = 0;
        for (Seg seg : segs) {
            double mx = (seg.x1 + seg.x2) / 2.0;
            double my = (seg.y1 + seg.y2) / 2.0;
            if (mx >= x0 && mx < x1 && my >= y0 && my <= y1) {
                count++;
            }
        }
        return count;
    }
}
