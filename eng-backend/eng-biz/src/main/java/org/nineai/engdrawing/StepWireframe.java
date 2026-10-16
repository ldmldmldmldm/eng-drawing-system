package org.nineai.engdrawing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 STEP 文本里的边和圆抽出线框。只画文件里有的直线和圆弧，不补曲面。
 * 爆炸层次图按实体/Z 向分层后在工厂图里投影，不在本类里编造外形。
 */
public final class StepWireframe {

    static final int MAX_SEGMENTS = 8000;
    private static final int CIRCLE_STEPS = 24;
    private static final double FULL = Math.PI * 2;
    private static final Set<String> WANTED = Set.of(
            "CARTESIAN_POINT", "DIRECTION", "VERTEX_POINT", "AXIS2_PLACEMENT_3D", "CIRCLE", "EDGE_CURVE",
            "MANIFOLD_SOLID_BREP", "CLOSED_SHELL", "OPEN_SHELL", "ADVANCED_FACE", "FACE",
            "FACE_BOUND", "FACE_OUTER_BOUND", "EDGE_LOOP", "ORIENTED_EDGE", "CONNECTED_FACE_SET");
    private static final Pattern TRIPLE = Pattern.compile(
            "\\(([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[Ee][+-]?\\d+)?),([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[Ee][+-]?\\d+)?),([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[Ee][+-]?\\d+)?)\\)");
    private static final Pattern HASH = Pattern.compile("#(\\d+)");
    private static final Pattern AXIS = Pattern.compile("#(\\d+),#(\\d+),#(\\d+)");
    private static final Pattern CIRCLE = Pattern.compile(
            "#(\\d+),([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[Ee][+-]?\\d+)?)");
    private static final Pattern EDGE = Pattern.compile("#(\\d+),#(\\d+),#(\\d+),\\.(T|F)\\.");

    private StepWireframe() {
    }

    /** 一个实体或一层 Z 向切片的线框。solidId 为 STEP 号如 #73，切片时为 Z1… */
    public record Body(String solidId, List<double[]> segments, double zMin, double zMax, double zMid) {
    }

    public record Model(List<double[]> segments, boolean truncated, List<Body> bodies) {
        static Model empty() {
            return new Model(List.of(), false, List.of());
        }

        public Model(List<double[]> segments, boolean truncated) {
            this(segments, truncated, List.of());
        }
    }

    /** 线框包围盒，是模型范围不是图纸标注尺寸。 */
    static String envelope(List<double[]> segments, String unit) {
        if (segments == null || segments.isEmpty()) {
            return MaterialLineSupport.UNLABELED;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double[] edge : segments) {
            if (edge == null || edge.length < 6) {
                continue;
            }
            minX = Math.min(minX, Math.min(edge[0], edge[3]));
            minY = Math.min(minY, Math.min(edge[1], edge[4]));
            minZ = Math.min(minZ, Math.min(edge[2], edge[5]));
            maxX = Math.max(maxX, Math.max(edge[0], edge[3]));
            maxY = Math.max(maxY, Math.max(edge[1], edge[4]));
            maxZ = Math.max(maxZ, Math.max(edge[2], edge[5]));
        }
        if (!Double.isFinite(minX)) {
            return MaterialLineSupport.UNLABELED;
        }
        String size = formatLen(maxX - minX) + " × " + formatLen(maxY - minY) + " × " + formatLen(maxZ - minZ);
        if (unit == null || unit.isBlank() || MaterialLineSupport.UNLABELED.equals(unit)) {
            return size + "（单位未标注）";
        }
        return size + " " + unit;
    }

    private static String formatLen(double value) {
        double abs = Math.abs(value) < 1e-6 ? 0 : value;
        String text = String.format(java.util.Locale.US, "%.3f", abs);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    /** 解析失败时返回空线框，不抛给页面。 */
    public static Model extract(String text) {
        if (text == null || text.isBlank()) {
            return Model.empty();
        }
        Map<Integer, double[]> points = new HashMap<>();
        Map<Integer, Integer> vertexPoint = new HashMap<>();
        Map<Integer, int[]> axes = new HashMap<>();
        Map<Integer, double[]> circles = new HashMap<>();
        Map<Integer, int[]> edgeById = new HashMap<>();
        Map<Integer, List<Integer>> refs = new HashMap<>();
        List<Integer> solidIds = new ArrayList<>();
        List<int[]> edges = new ArrayList<>();
        int index = 0;
        int length = text.length();
        while (index < length) {
            int hash = text.indexOf('#', index);
            if (hash < 0) {
                break;
            }
            if (hash > 0 && !boundary(text.charAt(hash - 1))) {
                index = hash + 1;
                continue;
            }
            int cursor = hash + 1;
            while (cursor < length && Character.isDigit(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor == hash + 1 || cursor >= length || text.charAt(cursor) != '=') {
                index = hash + 1;
                continue;
            }
            int id = parseId(text, hash + 1, cursor);
            if (id < 0) {
                index = cursor + 1;
                continue;
            }
            cursor++;
            while (cursor < length && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            int nameStart = cursor;
            while (cursor < length && isName(text.charAt(cursor))) {
                cursor++;
            }
            String name = text.substring(nameStart, cursor);
            while (cursor < length && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length || text.charAt(cursor) != '(') {
                index = Math.max(cursor, hash + 1);
                continue;
            }
            int bodyEnd = scanParen(text, cursor);
            if (bodyEnd < 0) {
                break;
            }
            if (WANTED.contains(name)) {
                keep(name, id, text.substring(cursor + 1, bodyEnd).replaceAll("\\s+", ""),
                        points, vertexPoint, axes, circles, edges, edgeById, refs, solidIds);
            }
            index = bodyEnd + 1;
        }
        return build(points, vertexPoint, axes, circles, edges, edgeById, refs, solidIds);
    }

    private static void keep(
            String name,
            int id,
            String body,
            Map<Integer, double[]> points,
            Map<Integer, Integer> vertexPoint,
            Map<Integer, int[]> axes,
            Map<Integer, double[]> circles,
            List<int[]> edges,
            Map<Integer, int[]> edgeById,
            Map<Integer, List<Integer>> refs,
            List<Integer> solidIds) {
        if ("CARTESIAN_POINT".equals(name) || "DIRECTION".equals(name)) {
            double[] triple = triple(body);
            if (triple != null) {
                points.put(id, triple);
            }
            return;
        }
        if ("VERTEX_POINT".equals(name)) {
            Matcher matcher = HASH.matcher(body);
            if (matcher.find()) {
                vertexPoint.put(id, Integer.parseInt(matcher.group(1)));
            }
            return;
        }
        if ("AXIS2_PLACEMENT_3D".equals(name)) {
            Matcher matcher = AXIS.matcher(body);
            if (matcher.find()) {
                axes.put(id, new int[]{
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                });
            }
            return;
        }
        if ("CIRCLE".equals(name)) {
            Matcher matcher = CIRCLE.matcher(body);
            if (matcher.find()) {
                circles.put(id, new double[]{
                        Integer.parseInt(matcher.group(1)),
                        Double.parseDouble(matcher.group(2))
                });
            }
            return;
        }
        if ("EDGE_CURVE".equals(name)) {
            Matcher matcher = EDGE.matcher(body);
            if (matcher.find()) {
                int[] row = new int[]{
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        "T".equals(matcher.group(4)) ? 1 : 0
                };
                edges.add(row);
                edgeById.put(id, row);
            }
            return;
        }
        if ("MANIFOLD_SOLID_BREP".equals(name)) {
            solidIds.add(id);
        }
        List<Integer> hashes = hashes(body);
        if (!hashes.isEmpty()) {
            refs.put(id, hashes);
        }
    }

    private static Model build(
            Map<Integer, double[]> points,
            Map<Integer, Integer> vertexPoint,
            Map<Integer, int[]> axes,
            Map<Integer, double[]> circles,
            List<int[]> edges,
            Map<Integer, int[]> edgeById,
            Map<Integer, List<Integer>> refs,
            List<Integer> solidIds) {
        List<double[]> segments = new ArrayList<>();
        boolean truncated = addEdges(segments, edges, points, vertexPoint, axes, circles, MAX_SEGMENTS);
        List<Body> bodies = new ArrayList<>();
        for (Integer solidId : solidIds) {
            if (solidId == null) {
                continue;
            }
            Set<Integer> edgeIds = walkEdges(solidId, refs, edgeById);
            List<int[]> picked = new ArrayList<>();
            for (Integer edgeId : edgeIds) {
                int[] row = edgeById.get(edgeId);
                if (row != null) {
                    picked.add(row);
                }
            }
            List<double[]> bodySegs = new ArrayList<>();
            addEdges(bodySegs, picked, points, vertexPoint, axes, circles, 4000);
            if (!bodySegs.isEmpty()) {
                bodies.add(bodyOf("#" + solidId, bodySegs));
            }
        }
        return new Model(segments, truncated, bodies);
    }

    private static boolean addEdges(
            List<double[]> segments,
            List<int[]> edges,
            Map<Integer, double[]> points,
            Map<Integer, Integer> vertexPoint,
            Map<Integer, int[]> axes,
            Map<Integer, double[]> circles,
            int cap) {
        boolean truncated = false;
        for (int[] edge : edges) {
            if (segments.size() >= cap) {
                truncated = true;
                break;
            }
            double[] start = pointOf(points, vertexPoint, edge[0]);
            double[] end = pointOf(points, vertexPoint, edge[1]);
            double[] circle = circles.get(edge[2]);
            if (circle != null) {
                truncated = addCircle(segments, points, axes, circle, start, end, edge[3] == 1) || truncated;
                continue;
            }
            if (start == null || end == null || same(start, end)) {
                continue;
            }
            segments.add(segment(start, end));
        }
        return truncated;
    }

    private static Set<Integer> walkEdges(
            int root,
            Map<Integer, List<Integer>> refs,
            Map<Integer, int[]> edgeById) {
        Set<Integer> seen = new HashSet<>();
        Set<Integer> edges = new HashSet<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(root);
        seen.add(root);
        while (!q.isEmpty()) {
            int cur = q.removeFirst();
            if (edgeById.containsKey(cur)) {
                edges.add(cur);
            }
            List<Integer> next = refs.get(cur);
            if (next == null) {
                continue;
            }
            for (Integer id : next) {
                if (id != null && seen.add(id)) {
                    q.add(id);
                }
            }
        }
        return edges;
    }

    static Body bodyOf(String solidId, List<double[]> segments) {
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double[] edge : segments) {
            if (edge == null || edge.length < 6) {
                continue;
            }
            minZ = Math.min(minZ, Math.min(edge[2], edge[5]));
            maxZ = Math.max(maxZ, Math.max(edge[2], edge[5]));
        }
        if (!Double.isFinite(minZ)) {
            minZ = 0;
            maxZ = 0;
        }
        return new Body(solidId, segments, minZ, maxZ, (minZ + maxZ) / 2);
    }

    /** 按 Z 均分成层，实体拆不出时给叠层图用。 */
    static List<Body> splitByZ(List<double[]> segments, int layers) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        int n = Math.max(1, layers);
        if (n == 1) {
            return List.of(bodyOf("Z1", segments));
        }
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double[] edge : segments) {
            if (edge == null || edge.length < 6) {
                continue;
            }
            minZ = Math.min(minZ, Math.min(edge[2], edge[5]));
            maxZ = Math.max(maxZ, Math.max(edge[2], edge[5]));
        }
        if (!Double.isFinite(minZ) || maxZ - minZ < 1e-9) {
            return List.of(bodyOf("Z1", segments));
        }
        double band = (maxZ - minZ) / n;
        List<List<double[]>> buckets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            buckets.add(new ArrayList<>());
        }
        for (double[] edge : segments) {
            if (edge == null || edge.length < 6) {
                continue;
            }
            double mid = (edge[2] + edge[5]) / 2;
            int idx = (int) Math.floor((mid - minZ) / band);
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= n) {
                idx = n - 1;
            }
            buckets.get(idx).add(edge);
        }
        List<Body> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!buckets.get(i).isEmpty()) {
                out.add(bodyOf("Z" + (i + 1), buckets.get(i)));
            }
        }
        return out.isEmpty() ? List.of(bodyOf("Z1", segments)) : out;
    }

    private static List<Integer> hashes(String body) {
        List<Integer> ids = new ArrayList<>();
        Matcher matcher = HASH.matcher(body);
        while (matcher.find()) {
            ids.add(Integer.parseInt(matcher.group(1)));
        }
        return ids;
    }

    /** @return 是否因为段数上限提前停下 */
    private static boolean addCircle(
            List<double[]> segments,
            Map<Integer, double[]> points,
            Map<Integer, int[]> axes,
            double[] circle,
            double[] start,
            double[] end,
            boolean forward) {
        int axisId = (int) circle[0];
        double radius = circle[1];
        if (radius <= 1e-9) {
            return false;
        }
        int[] axis = axes.get(axisId);
        if (axis == null) {
            return false;
        }
        double[] center = points.get(axis[0]);
        double[] normal = points.get(axis[1]);
        double[] ref = points.get(axis[2]);
        if (center == null || normal == null || ref == null) {
            return false;
        }
        double[] z = normalize(normal);
        double[] x = normalize(sub(ref, scale(z, dot(ref, z))));
        if (x == null || z == null) {
            return false;
        }
        double[] y = normalize(cross(z, x));
        if (y == null) {
            return false;
        }
        boolean closed = start == null || end == null || same(start, end);
        double angle1 = closed ? 0 : angle(start, center, x, y);
        double sweep = FULL;
        if (!closed) {
            double angle2 = angle(end, center, x, y);
            sweep = angle2 - angle1;
            if (forward) {
                if (sweep <= 1e-6) {
                    sweep += FULL;
                }
            } else if (sweep >= -1e-6) {
                sweep -= FULL;
            }
        }
        int steps = Math.max(4, (int) Math.ceil(Math.abs(sweep) / FULL * CIRCLE_STEPS));
        double[] previous = onCircle(center, x, y, radius, angle1);
        for (int step = 1; step <= steps; step++) {
            if (segments.size() >= MAX_SEGMENTS) {
                return true;
            }
            double[] current = onCircle(center, x, y, radius, angle1 + sweep * step / steps);
            segments.add(segment(previous, current));
            previous = current;
        }
        return false;
    }

    private static double[] pointOf(Map<Integer, double[]> points, Map<Integer, Integer> vertexPoint, int vertexId) {
        Integer pointId = vertexPoint.get(vertexId);
        if (pointId == null) {
            return null;
        }
        return points.get(pointId);
    }

    private static double[] triple(String body) {
        Matcher matcher = TRIPLE.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return new double[]{
                Double.parseDouble(matcher.group(1)),
                Double.parseDouble(matcher.group(2)),
                Double.parseDouble(matcher.group(3))
        };
    }

    private static double[] segment(double[] start, double[] end) {
        return new double[]{start[0], start[1], start[2], end[0], end[1], end[2]};
    }

    private static double[] onCircle(double[] center, double[] x, double[] y, double radius, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new double[]{
                center[0] + radius * (cos * x[0] + sin * y[0]),
                center[1] + radius * (cos * x[1] + sin * y[1]),
                center[2] + radius * (cos * x[2] + sin * y[2])
        };
    }

    private static double angle(double[] point, double[] center, double[] x, double[] y) {
        double vx = point[0] - center[0];
        double vy = point[1] - center[1];
        double vz = point[2] - center[2];
        return Math.atan2(vx * y[0] + vy * y[1] + vz * y[2], vx * x[0] + vy * x[1] + vz * x[2]);
    }

    private static boolean same(double[] left, double[] right) {
        double dx = left[0] - right[0];
        double dy = left[1] - right[1];
        double dz = left[2] - right[2];
        return dx * dx + dy * dy + dz * dz < 1e-12;
    }

    private static double dot(double[] left, double[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    private static double[] cross(double[] left, double[] right) {
        return new double[]{
                left[1] * right[2] - left[2] * right[1],
                left[2] * right[0] - left[0] * right[2],
                left[0] * right[1] - left[1] * right[0]
        };
    }

    private static double[] sub(double[] left, double[] right) {
        return new double[]{left[0] - right[0], left[1] - right[1], left[2] - right[2]};
    }

    private static double[] scale(double[] value, double factor) {
        return new double[]{value[0] * factor, value[1] * factor, value[2] * factor};
    }

    private static double[] normalize(double[] value) {
        double length = Math.sqrt(dot(value, value));
        if (length < 1e-9) {
            return null;
        }
        return scale(value, 1 / length);
    }

    private static boolean boundary(char previous) {
        return previous == ';' || previous == '\n' || previous == '\r' || previous == ' ' || previous == '\t';
    }

    private static boolean isName(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static int parseId(String text, int from, int to) {
        try {
            return Integer.parseInt(text.substring(from, to));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** 返回与 openIdx 处左括号配对的右括号下标。字符串里的括号不算。 */
    private static int scanParen(String text, int openIdx) {
        int depth = 0;
        boolean quote = false;
        for (int i = openIdx; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quote) {
                if (value == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                if (value == '\'') {
                    quote = false;
                }
                continue;
            }
            if (value == '\'') {
                quote = true;
                continue;
            }
            if (value == '(') {
                depth++;
            } else if (value == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
