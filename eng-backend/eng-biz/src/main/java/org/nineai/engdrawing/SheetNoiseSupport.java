package org.nineai.engdrawing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 客户图噪声检出：外框 + 与框线相连的角落表格（标题栏/修订表）。
 * 依据 AAAK 45 份：先认内框四边，再按贴框共线网格收紧表窗；比例仅兜底。
 */
final class SheetNoiseSupport {

    record Frame(double x0, double y0, double x1, double y1) {
        double width() {
            return Math.max(0, x1 - x0);
        }

        double height() {
            return Math.max(0, y1 - y0);
        }

        Map<String, Double> toMap() {
            Map<String, Double> map = new LinkedHashMap<>();
            map.put("x0", x0);
            map.put("y0", y0);
            map.put("x1", x1);
            map.put("y1", y1);
            return map;
        }
    }

    record Chrome(Frame frame, SheetLayoutSupport.Window title, SheetLayoutSupport.Window revTr,
                  SheetLayoutSupport.Window revTl) {
    }

    private record HSeg(double x0, double x1, double y, double len) {
    }

    private record VSeg(double y0, double y1, double x, double len) {
    }

    private SheetNoiseSupport() {
    }

    static Chrome detect(SheetLayoutSupport.Window panel, List<SheetLayoutSupport.Seg> segs) {
        if (panel == null || segs == null || segs.isEmpty()) {
            return fallback(panel);
        }
        List<HSeg> rawH = new ArrayList<>();
        List<VSeg> rawV = new ArrayList<>();
        collectHv(segs, panel, rawH, rawV);
        List<HSeg> horiz = mergeH(rawH);
        List<VSeg> vert = mergeV(rawV);
        Frame frame = findFrame(panel, horiz, vert);
        if (frame == null) {
            return fallback(panel);
        }
        SheetLayoutSupport.Window title = detectTitleBr(frame, horiz, vert);
        if (title == null) {
            title = fallbackTitle(panel);
        }
        SheetLayoutSupport.Window revTr = detectRevTop(frame, horiz, vert, true);
        SheetLayoutSupport.Window revTl = detectRevTop(frame, horiz, vert, false);
        return new Chrome(frame, title, revTr, revTl);
    }

    private static Chrome fallback(SheetLayoutSupport.Window panel) {
        if (panel == null) {
            return new Chrome(null, null, null, null);
        }
        double band = Math.max(5.0, Math.min(panel.width(), panel.height()) * 0.022);
        Frame frame = new Frame(panel.x0() + band, panel.y0() + band, panel.x1() - band, panel.y1() - band);
        return new Chrome(frame, fallbackTitle(panel), null, null);
    }

    private static SheetLayoutSupport.Window fallbackTitle(SheetLayoutSupport.Window panel) {
        double pw = panel.width();
        double ph = panel.height();
        // 比旧 36%×24% 更保守，避免误伤贴角视图
        return new SheetLayoutSupport.Window(
                panel.x1() - pw * 0.22,
                panel.y0(),
                panel.x1(),
                panel.y0() + ph * 0.20);
    }

    private static void collectHv(
            List<SheetLayoutSupport.Seg> segs,
            SheetLayoutSupport.Window panel,
            List<HSeg> horiz,
            List<VSeg> vert) {
        double margin = Math.max(panel.width(), panel.height()) * 0.05;
        double x0 = panel.x0() - margin;
        double x1 = panel.x1() + margin;
        double y0 = panel.y0() - margin;
        double y1 = panel.y1() + margin;
        final double tol = 0.5;
        for (SheetLayoutSupport.Seg seg : segs) {
            double ax = seg.x1();
            double ay = seg.y1();
            double bx = seg.x2();
            double by = seg.y2();
            double mx = (ax + bx) / 2.0;
            double my = (ay + by) / 2.0;
            if (mx < x0 || mx > x1 || my < y0 || my > y1) {
                continue;
            }
            if (Math.abs(by - ay) <= tol && Math.abs(bx - ax) > tol) {
                double left = Math.min(ax, bx);
                double right = Math.max(ax, bx);
                horiz.add(new HSeg(left, right, (ay + by) / 2.0, right - left));
            } else if (Math.abs(bx - ax) <= tol && Math.abs(by - ay) > tol) {
                double bottom = Math.min(ay, by);
                double top = Math.max(ay, by);
                vert.add(new VSeg(bottom, top, (ax + bx) / 2.0, top - bottom));
            }
        }
    }

    private static List<HSeg> mergeH(List<HSeg> raw) {
        TreeMap<Long, List<HSeg>> buckets = new TreeMap<>();
        final double yEps = 1.2;
        for (HSeg seg : raw) {
            long key = Math.round(seg.y() / yEps);
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(seg);
        }
        List<HSeg> out = new ArrayList<>();
        for (List<HSeg> group : buckets.values()) {
            group.sort(Comparator.comparingDouble(HSeg::x0));
            HSeg cur = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                HSeg next = group.get(i);
                if (next.x0() <= cur.x1() + 3.0) {
                    double x1 = Math.max(cur.x1(), next.x1());
                    cur = new HSeg(cur.x0(), x1, (cur.y() + next.y()) / 2.0, x1 - cur.x0());
                } else {
                    out.add(cur);
                    cur = next;
                }
            }
            out.add(cur);
        }
        return out;
    }

    private static List<VSeg> mergeV(List<VSeg> raw) {
        TreeMap<Long, List<VSeg>> buckets = new TreeMap<>();
        final double xEps = 1.2;
        for (VSeg seg : raw) {
            long key = Math.round(seg.x() / xEps);
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(seg);
        }
        List<VSeg> out = new ArrayList<>();
        for (List<VSeg> group : buckets.values()) {
            group.sort(Comparator.comparingDouble(VSeg::y0));
            VSeg cur = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                VSeg next = group.get(i);
                if (next.y0() <= cur.y1() + 3.0) {
                    double y1 = Math.max(cur.y1(), next.y1());
                    cur = new VSeg(cur.y0(), y1, (cur.x() + next.x()) / 2.0, y1 - cur.y0());
                } else {
                    out.add(cur);
                    cur = next;
                }
            }
            out.add(cur);
        }
        return out;
    }

    private static Frame findFrame(SheetLayoutSupport.Window panel, List<HSeg> horiz, List<VSeg> vert) {
        double minx = panel.x0();
        double miny = panel.y0();
        double maxx = panel.x1();
        double maxy = panel.y1();
        double pw = panel.width();
        double ph = panel.height();
        double inset = Math.max(6.0, Math.min(pw, ph) * 0.012);
        double outer = Math.max(25.0, Math.min(pw, ph) * 0.05);
        // 先取内缩带（AAAK 内框约 12.7）；没有再贴幅面边（合成图/无内缩）
        HSeg bottom = pickH(horiz, miny + inset * 0.3, miny + outer, pw, minx, maxx, true);
        if (bottom == null) {
            bottom = pickH(horiz, miny - 2, miny + outer, pw, minx, maxx, true);
        }
        HSeg top = pickH(horiz, maxy - outer, maxy - inset * 0.3, pw, minx, maxx, false);
        if (top == null) {
            top = pickH(horiz, maxy - outer, maxy + 2, pw, minx, maxx, false);
        }
        VSeg left = pickV(vert, minx + inset * 0.3, minx + outer, ph, miny, maxy, true);
        if (left == null) {
            left = pickV(vert, minx - 2, minx + outer, ph, miny, maxy, true);
        }
        VSeg right = pickV(vert, maxx - outer, maxx - inset * 0.3, ph, miny, maxy, false);
        if (right == null) {
            right = pickV(vert, maxx - outer, maxx + 2, ph, miny, maxy, false);
        }
        if (bottom == null || top == null || left == null || right == null) {
            return null;
        }
        return new Frame(left.x(), bottom.y(), right.x(), top.y());
    }

    private static HSeg pickH(
            List<HSeg> horiz, double y0, double y1, double pw, double minx, double maxx, boolean preferLow) {
        HSeg best = null;
        for (HSeg seg : horiz) {
            if (seg.y() < y0 || seg.y() > y1 || seg.len() < pw * 0.45) {
                continue;
            }
            if (seg.x0() < minx - 5 || seg.x1() > maxx + 5) {
                continue;
            }
            if (best == null
                    || seg.len() > best.len() + 1
                    || (Math.abs(seg.len() - best.len()) <= 1
                    && (preferLow ? seg.y() < best.y() : seg.y() > best.y()))) {
                best = seg;
            }
        }
        return best;
    }

    private static VSeg pickV(
            List<VSeg> vert, double x0, double x1, double ph, double miny, double maxy, boolean preferLeft) {
        VSeg best = null;
        for (VSeg seg : vert) {
            if (seg.x() < x0 || seg.x() > x1 || seg.len() < ph * 0.45) {
                continue;
            }
            if (seg.y0() < miny - 5 || seg.y1() > maxy + 5) {
                continue;
            }
            if (best == null
                    || seg.len() > best.len() + 1
                    || (Math.abs(seg.len() - best.len()) <= 1
                    && (preferLeft ? seg.x() < best.x() : seg.x() > best.x()))) {
                best = seg;
            }
        }
        return best;
    }

    private static SheetLayoutSupport.Window detectTitleBr(Frame frame, List<HSeg> horiz, List<VSeg> vert) {
        double fw = frame.width();
        double fh = frame.height();
        double maxH = fh * 0.32;
        List<HSeg> cands = new ArrayList<>();
        for (HSeg seg : horiz) {
            if (!near(seg.x1(), frame.x1(), 4.0)) {
                continue;
            }
            if (seg.y() < frame.y0() - 2 || seg.y() > frame.y0() + maxH) {
                continue;
            }
            if (seg.len() < fw * 0.08 || seg.len() >= fw * 0.55) {
                continue;
            }
            cands.add(seg);
        }
        if (cands.size() < 2) {
            return null;
        }
        List<HSeg> longish = new ArrayList<>();
        for (HSeg seg : cands) {
            if (seg.len() >= fw * 0.12) {
                longish.add(seg);
            }
        }
        if (longish.isEmpty()) {
            longish = cands;
        }
        double xLeft = Double.POSITIVE_INFINITY;
        double yTop = Double.NEGATIVE_INFINITY;
        for (HSeg seg : longish) {
            xLeft = Math.min(xLeft, seg.x0());
        }
        for (HSeg seg : cands) {
            yTop = Math.max(yTop, seg.y());
        }
        xLeft = Math.max(xLeft, frame.x1() - fw * 0.45);
        int vHits = 0;
        for (VSeg seg : vert) {
            if (seg.x() < xLeft - 2 || seg.x() > frame.x1() + 2) {
                continue;
            }
            if (!near(seg.y0(), frame.y0(), 4.0) || seg.len() < fh * 0.05) {
                continue;
            }
            vHits++;
            yTop = Math.max(yTop, seg.y1());
        }
        yTop = Math.min(yTop, frame.y0() + maxH);
        if (frame.x1() - xLeft < fw * 0.10 || yTop - frame.y0() < fh * 0.06) {
            return null;
        }
        if (clusterCount(cands) < 2 && vHits < 1) {
            return null;
        }
        return new SheetLayoutSupport.Window(xLeft, frame.y0(), frame.x1(), yTop);
    }

    private static SheetLayoutSupport.Window detectRevTop(
            Frame frame, List<HSeg> horiz, List<VSeg> vert, boolean right) {
        double fw = frame.width();
        double fh = frame.height();
        double maxH = fh * 0.28;
        double maxW = fw * 0.42;
        List<HSeg> cands = new ArrayList<>();
        for (HSeg seg : horiz) {
            boolean touch = right ? near(seg.x1(), frame.x1(), 4.0) : near(seg.x0(), frame.x0(), 4.0);
            if (!touch) {
                continue;
            }
            if (seg.y() < frame.y1() - maxH || seg.y() > frame.y1() + 2) {
                continue;
            }
            if (seg.len() < fw * 0.06 || seg.len() >= fw * 0.55) {
                continue;
            }
            cands.add(seg);
        }
        if (cands.size() < 2 || clusterCount(cands) < 2) {
            return null;
        }
        if (right) {
            double xLeft = Double.POSITIVE_INFINITY;
            double yBot = Double.POSITIVE_INFINITY;
            for (HSeg seg : cands) {
                xLeft = Math.min(xLeft, seg.x0());
                yBot = Math.min(yBot, seg.y());
            }
            xLeft = Math.max(xLeft, frame.x1() - maxW);
            for (VSeg seg : vert) {
                if (seg.x() < xLeft - 2 || seg.x() > frame.x1() + 2) {
                    continue;
                }
                if (!near(seg.y1(), frame.y1(), 4.0) || seg.len() < 3) {
                    continue;
                }
                yBot = Math.min(yBot, seg.y0());
            }
            if (frame.x1() - xLeft < fw * 0.08 || frame.y1() - yBot < fh * 0.012) {
                return null;
            }
            return new SheetLayoutSupport.Window(xLeft, yBot, frame.x1(), frame.y1());
        }
        double xRight = Double.NEGATIVE_INFINITY;
        double yBot = Double.POSITIVE_INFINITY;
        for (HSeg seg : cands) {
            xRight = Math.max(xRight, seg.x1());
            yBot = Math.min(yBot, seg.y());
        }
        xRight = Math.min(xRight, frame.x0() + maxW);
        if (xRight - frame.x0() < fw * 0.08 || frame.y1() - yBot < fh * 0.012) {
            return null;
        }
        return new SheetLayoutSupport.Window(frame.x0(), yBot, xRight, frame.y1());
    }

    private static int clusterCount(List<HSeg> cands) {
        List<Double> ys = new ArrayList<>();
        for (HSeg seg : cands) {
            ys.add(seg.y());
        }
        ys.sort(Double::compareTo);
        int clusters = 0;
        double last = Double.NEGATIVE_INFINITY;
        for (double y : ys) {
            if (y - last > 2.5) {
                clusters++;
            }
            last = y;
        }
        return clusters;
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }
}
