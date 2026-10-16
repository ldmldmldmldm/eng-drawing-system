package org.nineai.engdrawing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户图分幅与区域窗口。AAAK 样张文字多为线段，多页多为 Model 横向拼幅。
 * 转换策略：剔除外框与角落表后，content 整体拷贝（含完整 NOTE + 视图相对位置）。
 * 详见 docs/AI图纸转换-版式与分幅.md。
 */
final class SheetLayoutSupport {

    /** 样张常见单幅宽（与 AAAK 普查一致）。 */
    static final double PANEL_W = 863.6;
    static final double PANEL_H = 558.8;

    record Window(double x0, double y0, double x1, double y1) {
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

    record Region(String kind, int panel, Window window) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("panel", panel);
            map.put("x0", window.x0());
            map.put("y0", window.y0());
            map.put("x1", window.x1());
            map.put("y1", window.y1());
            return map;
        }
    }

    record Panel(int index, Window window, List<Region> regions) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("x0", window.x0());
            map.put("y0", window.y0());
            map.put("x1", window.x1());
            map.put("y1", window.y1());
            List<Map<String, Object>> list = new ArrayList<>();
            for (Region region : regions) {
                list.add(region.toMap());
            }
            map.put("regions", list);
            return map;
        }
    }

    record Layout(List<Panel> panels, Window sheet) {
        static Layout empty() {
            return new Layout(List.of(), new Window(0, 0, PANEL_W, PANEL_H));
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("panelCount", panels.size());
            map.put("sheet", sheet.toMap());
            List<Map<String, Object>> list = new ArrayList<>();
            for (Panel panel : panels) {
                list.add(panel.toMap());
            }
            map.put("panels", list);
            return map;
        }
    }

    /** 短线段上限：笔画/字画，用于 NOTE 密度兜底。 */
    private static final double SHORT_SEG = 28.0;
    /** 贴边带宽比例：外框/区号。 */
    private static final double EDGE_BAND_RATIO = 0.022;

    /** 线段视图（包内噪声检出共用）。 */
    record Seg(double x1, double y1, double x2, double y2) {
        double length() {
            double dx = x2 - x1;
            double dy = y2 - y1;
            return Math.sqrt(dx * dx + dy * dy);
        }

        double cx() {
            return (x1 + x2) / 2.0;
        }

        double cy() {
            return (y1 + y2) / 2.0;
        }
    }

    private SheetLayoutSupport() {
    }

    static Layout analyze(String dxf) {
        NoteBoxFinder.Found found = NoteBoxFinder.find(dxf == null ? "" : dxf);
        List<Seg> segs = readSegs(dxf);
        double[] extent = extentOf(segs);
        if (extent == null) {
            return Layout.empty();
        }
        double minX = extent[0];
        double minY = extent[1];
        double maxX = extent[2];
        double maxY = extent[3];
        double width = maxX - minX;
        double height = maxY - minY;
        if (width < 10 || height < 10) {
            return Layout.empty();
        }
        int count = panelCount(width, height);
        double step = count <= 1 ? width : width / count;
        List<Panel> panels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double x0 = minX + step * i;
            double x1 = i == count - 1 ? maxX : minX + step * (i + 1);
            Window panelWindow = new Window(x0, minY, x1, maxY);
            panels.add(new Panel(i, panelWindow, regionsForPanel(i, panelWindow, found, segs)));
        }
        return new Layout(panels, new Window(minX, minY, maxX, maxY));
    }

    static int panelCount(double width, double height) {
        if (width < PANEL_W * 0.75) {
            return 1;
        }
        return Math.max(1, (int) Math.round(width / PANEL_W));
    }

    /**
     * content：内框内整体拷贝（NOTE 完整 + 视图相对位置原样）。
     * note：完整说明列（保证 content 包住；预览可调）。
     * frame / title / rev_*：噪声（外框与角落表）；edge：边带宽参数（x0 存带宽）。
     */
    private static List<Region> regionsForPanel(
            int index, Window panel, NoteBoxFinder.Found found, List<Seg> segs) {
        double pw = panel.width();
        double ph = panel.height();
        double band = Math.max(5.0, Math.min(pw, ph) * EDGE_BAND_RATIO);

        Window note = noteRegion(panel, found, segs);
        SheetNoiseSupport.Chrome chrome = SheetNoiseSupport.detect(panel, segs);
        Window title = chrome.title();
        Window revTl = chrome.revTl();
        Window revTr = chrome.revTr();

        // content = 内框矩形（不内缩）：贴框 NOTE/标注必须保留
        double cx0;
        double cy0;
        double cx1;
        double cy1;
        if (chrome.frame() != null) {
            SheetNoiseSupport.Frame frame = chrome.frame();
            cx0 = frame.x0();
            cy0 = frame.y0();
            cx1 = frame.x1();
            cy1 = frame.y1();
        } else {
            cx0 = panel.x0() + band;
            cy0 = panel.y0() + band;
            cx1 = panel.x1() - band;
            cy1 = panel.y1() - band;
        }
        // NOTE 完整拷贝：content 必须包住说明列（左缘可略贴框，避免裁掉字画）
        if (note.width() > 8 && note.height() > 8) {
            cx0 = Math.min(cx0, Math.max(panel.x0() + 2.0, note.x0() - 2.0));
            cy0 = Math.min(cy0, Math.max(panel.y0() + 2.0, note.y0() - 2.0));
            cx1 = Math.max(cx1, Math.min(panel.x1() - band, note.x1() + 6.0));
            cy1 = Math.max(cy1, Math.min(panel.y1() - 2.0, note.y1() + 2.0));
        }
        Window content = new Window(cx0, cy0, cx1, cy1);

        List<Region> regions = new ArrayList<>();
        if (content.width() > 20 && content.height() > 20) {
            regions.add(new Region("content", index, content));
        }
        if (note.width() > 8 && note.height() > 8) {
            regions.add(new Region("note", index, note));
        }
        if (chrome.frame() != null && chrome.frame().width() > 20 && chrome.frame().height() > 20) {
            SheetNoiseSupport.Frame frame = chrome.frame();
            regions.add(new Region("frame", index, new Window(frame.x0(), frame.y0(), frame.x1(), frame.y1())));
        }
        if (title != null && title.width() > 8 && title.height() > 8) {
            regions.add(new Region("title", index, title));
        }
        if (revTl != null && revTl.width() > 8 && revTl.height() > 8) {
            regions.add(new Region("rev_tl", index, revTl));
        }
        if (revTr != null && revTr.width() > 8 && revTr.height() > 8) {
            regions.add(new Region("rev_tr", index, revTr));
        }
        // edge 窗：x0=带宽，供脚本剔除贴边长线（其余坐标占位）
        regions.add(new Region("edge", index, new Window(band, 0, band, 0)));
        regions.sort(Comparator.comparing(Region::kind));
        return regions;
    }

    /**
     * NOTE 整列：方框列 + 左侧短线外接盒，Y 拉满列高。
     * 实现「完整拷贝」的关键：随 content 一次克隆，本窗保证 content 覆盖整列。
     */
    private static Window noteRegion(Window panel, NoteBoxFinder.Found found, List<Seg> segs) {
        double pw = panel.width();
        double ph = panel.height();
        List<NoteBoxFinder.Window> hits = new ArrayList<>();
        if (found != null && found.notes() != null) {
            for (NoteBoxFinder.Window note : found.notes()) {
                double cx = (note.x0() + note.x1()) / 2.0;
                if (cx >= panel.x0() && cx <= panel.x1()) {
                    hits.add(note);
                }
            }
        }
        double colLeft = panel.x0() + pw * 0.004;
        double colRightLimit = panel.x0() + pw * 0.40;
        if (hits.size() >= 2) {
            double x0 = panel.x1();
            double y0 = panel.y1();
            double x1 = panel.x0();
            double y1 = panel.y0();
            for (NoteBoxFinder.Window note : hits) {
                x0 = Math.min(x0, note.x0());
                y0 = Math.min(y0, note.y0());
                x1 = Math.max(x1, note.x1());
                y1 = Math.max(y1, note.y1());
            }
            double[] span = shortSpanInBand(segs, colLeft, colRightLimit, panel.y0(), panel.y1());
            if (span != null) {
                x0 = Math.min(x0, span[0]);
                x1 = Math.max(x1, span[1]);
                y0 = Math.min(y0, span[2]);
                y1 = Math.max(y1, span[3]);
            }
            x0 = Math.max(colLeft, Math.min(x0 - pw * 0.005, colLeft + pw * 0.02));
            x1 = Math.min(colRightLimit, Math.max(x1 + pw * 0.02, x0 + pw * 0.16));
            y0 = Math.max(panel.y0() + ph * 0.008, Math.min(y0, panel.y0() + ph * 0.06));
            y1 = Math.min(panel.y1() - ph * 0.008, Math.max(y1, panel.y0() + ph * 0.92));
            return new Window(x0, y0, x1, y1);
        }
        Window dense = densityNote(panel, segs);
        if (dense != null) {
            return dense;
        }
        return new Window(
                colLeft,
                panel.y0() + ph * 0.02,
                panel.x0() + pw * 0.30,
                panel.y1() - ph * 0.02);
    }

    private static double[] shortSpanInBand(
            List<Seg> segs, double x0, double x1, double y0, double y1) {
        if (segs == null || segs.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (Seg seg : segs) {
            if (seg.length() > SHORT_SEG) {
                continue;
            }
            double cx = seg.cx();
            double cy = seg.cy();
            if (cx < x0 || cx > x1 || cy < y0 || cy > y1) {
                continue;
            }
            minX = Math.min(minX, Math.min(seg.x1, seg.x2));
            maxX = Math.max(maxX, Math.max(seg.x1, seg.x2));
            minY = Math.min(minY, Math.min(seg.y1, seg.y2));
            maxY = Math.max(maxY, Math.max(seg.y1, seg.y2));
            n++;
        }
        if (n < 40 || !Double.isFinite(minX)) {
            return null;
        }
        return new double[] {minX, maxX, minY, maxY};
    }

    private static Window densityNote(Window panel, List<Seg> segs) {
        if (segs == null || segs.isEmpty()) {
            return null;
        }
        double pw = panel.width();
        double ph = panel.height();
        double left0 = panel.x0();
        double left1 = panel.x0() + pw * 0.42;
        int bins = 24;
        int[] hist = new int[bins];
        double binW = Math.max(1e-3, (left1 - left0) / bins);
        double yMin = panel.y1();
        double yMax = panel.y0();
        int total = 0;
        for (Seg seg : segs) {
            if (seg.length() > SHORT_SEG) {
                continue;
            }
            double cx = seg.cx();
            double cy = seg.cy();
            if (cx < left0 || cx > left1 || cy < panel.y0() || cy > panel.y1()) {
                continue;
            }
            int bi = Math.min(bins - 1, Math.max(0, (int) ((cx - left0) / binW)));
            hist[bi]++;
            total++;
            yMin = Math.min(yMin, cy);
            yMax = Math.max(yMax, cy);
        }
        if (total < 80) {
            return null;
        }
        int peak = 0;
        for (int v : hist) {
            peak = Math.max(peak, v);
        }
        if (peak < 20) {
            return null;
        }
        int thr = Math.max(8, peak / 4);
        int bestL = 0;
        int bestR = 0;
        int bestSum = -1;
        int i = 0;
        while (i < bins) {
            if (hist[i] < thr) {
                i++;
                continue;
            }
            int j = i;
            int sum = 0;
            while (j < bins && hist[j] >= thr) {
                sum += hist[j];
                j++;
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestL = i;
                bestR = j - 1;
            }
            i = j;
        }
        if (bestSum < 80 || bestL > bins / 3) {
            return null;
        }
        double x0 = left0 + bestL * binW;
        double x1 = left0 + (bestR + 1) * binW;
        x0 = Math.max(panel.x0() + pw * 0.004, x0 - binW);
        x1 = Math.min(panel.x0() + pw * 0.40, x1 + binW * 2);
        if (x1 - x0 < pw * 0.12) {
            x1 = Math.min(panel.x0() + pw * 0.40, Math.max(x1, x0 + pw * 0.18));
        }
        if (x1 - x0 < 12) {
            return null;
        }
        double y0 = Math.max(panel.y0() + ph * 0.015, yMin - ph * 0.03);
        double y1 = Math.min(panel.y1() - ph * 0.015, yMax + ph * 0.03);
        if (y1 - y0 < ph * 0.55) {
            y0 = panel.y0() + ph * 0.02;
            y1 = panel.y1() - ph * 0.02;
        }
        return new Window(x0, y0, x1, y1);
    }

    /** 返回 [minX,minY,maxX,maxY]；失败 null。 */
    static double[] lineExtent(String dxf) {
        return extentOf(readSegs(dxf));
    }

    private static double[] extentOf(List<Seg> segs) {
        if (segs == null || segs.size() < 8) {
            return null;
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
        if (!Double.isFinite(minX)) {
            return null;
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    private static List<Seg> readSegs(String dxf) {
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
                i = readLineSeg(rows, i + 2, segs);
                continue;
            }
            if ("LWPOLYLINE".equals(value)) {
                i = readLwPolylineSegs(rows, i + 2, segs);
                continue;
            }
            // CIRCLE/ARC 参与外包量界，避免 content 窗偏矮/偏窄导致克隆缺一块
            if ("CIRCLE".equals(value)) {
                i = readCircleSegs(rows, i + 2, segs);
                continue;
            }
            if ("ARC".equals(value)) {
                i = readArcSegs(rows, i + 2, segs);
                continue;
            }
            i += 2;
        }
        return segs;
    }

    private static int readCircleSegs(String[] rows, int start, List<Seg> segs) {
        Double cx = null;
        Double cy = null;
        Double r = null;
        int i = start;
        while (i + 1 < rows.length && !"0".equals(rows[i].trim())) {
            String group = rows[i].trim();
            String text = rows[i + 1].trim();
            i += 2;
            try {
                double number = Double.parseDouble(text);
                if ("10".equals(group)) {
                    cx = number;
                } else if ("20".equals(group)) {
                    cy = number;
                } else if ("40".equals(group)) {
                    r = number;
                }
            } catch (NumberFormatException ignored) {
                // 非坐标
            }
        }
        if (cx != null && cy != null && r != null && r > 0) {
            // 用外包对角线段参与 extentOf
            segs.add(new Seg(cx - r, cy - r, cx + r, cy + r));
        }
        return i;
    }

    private static int readArcSegs(String[] rows, int start, List<Seg> segs) {
        Double cx = null;
        Double cy = null;
        Double r = null;
        int i = start;
        while (i + 1 < rows.length && !"0".equals(rows[i].trim())) {
            String group = rows[i].trim();
            String text = rows[i + 1].trim();
            i += 2;
            try {
                double number = Double.parseDouble(text);
                if ("10".equals(group)) {
                    cx = number;
                } else if ("20".equals(group)) {
                    cy = number;
                } else if ("40".equals(group)) {
                    r = number;
                }
            } catch (NumberFormatException ignored) {
                // 非坐标
            }
        }
        if (cx != null && cy != null && r != null && r > 0) {
            segs.add(new Seg(cx - r, cy - r, cx + r, cy + r));
        }
        return i;
    }

    private static int readLineSeg(String[] rows, int start, List<Seg> segs) {
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
                // 非坐标
            }
        }
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            segs.add(new Seg(x1, y1, x2, y2));
        }
        return i;
    }

    private static int readLwPolylineSegs(String[] rows, int start, List<Seg> segs) {
        List<double[]> pts = new ArrayList<>();
        boolean closed = false;
        Double pendingX = null;
        int i = start;
        while (i + 1 < rows.length && !"0".equals(rows[i].trim())) {
            String group = rows[i].trim();
            String text = rows[i + 1].trim();
            i += 2;
            try {
                if ("70".equals(group)) {
                    closed = (Integer.parseInt(text) & 1) == 1;
                    continue;
                }
                double number = Double.parseDouble(text);
                if ("10".equals(group)) {
                    pendingX = number;
                } else if ("20".equals(group) && pendingX != null) {
                    pts.add(new double[] {pendingX, number});
                    pendingX = null;
                }
            } catch (NumberFormatException ignored) {
                pendingX = null;
            }
        }
        for (int p = 1; p < pts.size(); p++) {
            double[] a = pts.get(p - 1);
            double[] b = pts.get(p);
            segs.add(new Seg(a[0], a[1], b[0], b[1]));
        }
        if (closed && pts.size() >= 2) {
            double[] a = pts.get(pts.size() - 1);
            double[] b = pts.get(0);
            segs.add(new Seg(a[0], a[1], b[0], b[1]));
        }
        return i;
    }
}
