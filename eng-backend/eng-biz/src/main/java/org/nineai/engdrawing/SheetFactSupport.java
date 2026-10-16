package org.nineai.engdrawing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把原图认出的文字按模切看图分栏。只收录对得上的原文，对不上不补，也不把绘图材料名当成零件材料。
 * TODO 人工核对（后续再做）：认字结果标成待核对，不自动把 O 改成 0、把 ÷ 改成 ±。
 * 只有人确认过的那一行才能当事实，并标明人工确认。没确认的保持未标注或待核对。
 */
public final class SheetFactSupport {

    private static final double MIN_STRONG = 0.80;
    private static final double MIN_SOFT = 0.45;
    private static final double TITLE_X = 0.60;
    private static final double TITLE_Y = 0.74;
    private static final int MAX_ITEMS = 200;
    private static final Pattern DRAWING_NO = Pattern.compile("\\b(\\d{3}-\\d{4,8})\\b");
    private static final Pattern APN = Pattern.compile("(?i)APN\\s*[:：]\\s*([0-9]{3}-[0-9A-Z][0-9A-Z\\-]*)");
    private static final Pattern SHEET = Pattern.compile("(?i)SHEET\\s+(\\d+)\\s+OF\\s+(\\d+)");
    private static final Pattern DATE = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{2,4}");
    private static final Pattern SCALE_VALUE = Pattern.compile("(?i)^NONE$|^\\d+\\s*:\\s*\\d+$");
    private static final Pattern TOLERANCE = Pattern.compile("(?i)^(X(?:\\.X+)+|ANGLES)\\s+±\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");
    private static final Pattern DIMENSION = Pattern.compile("(?i)^(?:\\d+\\s*[Xx×]\\s+)?\\d+(?:\\.\\d+)?\\s*[±÷]\\s*\\d+(?:\\.\\d+)?$");
    private static final Pattern MATERIAL_CALLOUT = Pattern.compile(
            "(?i).*(MATERIAL\\s*:|(?:P|Р)(?:E|Е)T\\s+TAPE|FOAM\\s+MATERIAL|LOW\\s+TACK\\s+LINER|ADHESIVE\\s+\\d).*");
    private static final Pattern REVISION_LINE = Pattern.compile("(?i).*\\bREVISION\\b.*");
    private static final Pattern INSPECTION = Pattern.compile("(?i).*\\b(FAI\\s*\\d+|SPC\\s*[A-Z0-9]+)\\b.*");
    private static final Set<String> LABELS = Set.of(
            "DRAFTER", "DESIGNER", "DATE", "TITLE", "SCALE", "SIZE", "REV", "SHT", "SHEET", "METRIC");
    private static final String[] PACKAGING_KEYS = {
            "carrier tape", "cover tape", "release liner", "reel quantity",
            "package quantity", "splice", "leader", "trailer"
    };

    private SheetFactSupport() {
    }

    public record Line(double confidence, double x, double y, double w, double h, String text) {
    }

    public record Classified(
            PdfFactSupport.Facts facts,
            List<String> materialLines,
            List<String> revisionLines,
            List<String> inspectionLines,
            List<String> otherLines) {
    }

    public static Classified classify(String tsv) {
        List<Line> lines = dedupe(parse(tsv));
        List<Line> title = new ArrayList<>();
        for (Line line : lines) {
            if (line.confidence() >= MIN_STRONG && inTitle(line)) {
                title.add(line);
            }
        }
        List<Line> material = unique(matching(lines, MIN_SOFT, SheetFactSupport::materialCallout));
        List<Line> revisions = unique(matching(lines, MIN_STRONG, line -> REVISION_LINE.matcher(line.text()).matches()
                && line.text().length() >= 8));
        List<Line> inspections = unique(matching(lines, MIN_SOFT, line -> INSPECTION.matcher(line.text()).matches()));
        List<Line> dimensions = unique(matching(lines, MIN_SOFT, line -> DIMENSION.matcher(line.text()).matches() && !inTitle(line)));
        List<Line> notes = unique(matching(lines, MIN_STRONG, line -> noteLine(line, material)));
        String titleText = titleText(lines);
        String drawingNo = drawingNo(title);
        String revision = revision(title, drawingNo);
        String scale = scale(title);
        String size = size(title);
        String unit = unit(lines);
        List<String> noteTexts = texts(notes);
        List<String> used = new ArrayList<>();
        used.addAll(texts(material));
        used.addAll(texts(revisions));
        used.addAll(texts(inspections));
        used.addAll(texts(dimensions));
        used.addAll(noteTexts);
        addIfLabeled(used, titleText);
        addIfLabeled(used, drawingNo);
        addIfLabeled(used, revision);
        addIfLabeled(used, scale);
        addIfLabeled(used, size);
        addIfLabeled(used, unit);
        List<String> other = other(lines, used);
        return new Classified(
                new PdfFactSupport.Facts(
                        MaterialLineSupport.extract(join(lines)),
                        drawingNo,
                        titleText,
                        first(APN, join(lines)),
                        revision,
                        sheet(lines),
                        size,
                        scale,
                        unit,
                        role(title, "DRAFTER"),
                        role(title, "DESIGNER"),
                        role(title, "DATE"),
                        packaging(noteTexts),
                        tolerances(title),
                        noteTexts,
                        texts(dimensions)),
                texts(material),
                texts(revisions),
                texts(inspections),
                other);
    }

    static List<Line> parse(String tsv) {
        List<Line> lines = new ArrayList<>();
        if (tsv == null || tsv.isBlank()) {
            return lines;
        }
        for (String raw : tsv.split("\\R")) {
            String[] parts = raw.split("\t", 6);
            if (parts.length < 6) {
                continue;
            }
            try {
                String text = clean(parts[5]);
                if (text.isEmpty()) {
                    continue;
                }
                lines.add(new Line(
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]),
                        Double.parseDouble(parts[4]),
                        text));
            } catch (NumberFormatException ex) {
                // 坏行丢掉，不当成图面文字
            }
        }
        return lines;
    }

    private static String clean(String text) {
        return text.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean inTitle(Line line) {
        return line.x() >= TITLE_X && line.y() >= TITLE_Y;
    }

    private static boolean materialCallout(Line line) {
        return line.text().length() >= 12
                && line.text().matches(".*\\d.*")
                && MATERIAL_CALLOUT.matcher(line.text()).matches();
    }

    private static boolean noteLine(Line line, List<Line> material) {
        if (line.text().length() < 16 || letters(line.text()).length() < 8) {
            return false;
        }
        if (materialCallout(line) || REVISION_LINE.matcher(line.text()).matches()) {
            return false;
        }
        if (inTitle(line)) {
            return false;
        }
        if (containsText(material, line.text())) {
            return false;
        }
        return line.x() < 0.55 || line.text().regionMatches(true, 0, "NOTE", 0, 4);
    }

    private static String drawingNo(List<Line> title) {
        Line best = null;
        String value = MaterialLineSupport.UNLABELED;
        for (Line line : title) {
            Matcher matcher = DRAWING_NO.matcher(line.text());
            if (!matcher.find()) {
                continue;
            }
            if (best == null || line.y() > best.y()) {
                best = line;
                value = matcher.group(1);
            }
        }
        return value;
    }

    private static String revision(List<Line> title, String drawingNo) {
        Line anchor = null;
        for (Line line : title) {
            if (line.text().contains(drawingNo) && !MaterialLineSupport.UNLABELED.equals(drawingNo)) {
                anchor = line;
                break;
            }
        }
        if (anchor == null) {
            return MaterialLineSupport.UNLABELED;
        }
        for (Line line : title) {
            if (Math.abs(line.y() - anchor.y()) > 0.03 || line.x() <= anchor.x()) {
                continue;
            }
            if (line.text().matches("\\d{2}")) {
                return line.text();
            }
        }
        return MaterialLineSupport.UNLABELED;
    }

    private static String titleText(List<Line> lines) {
        List<Line> pieces = new ArrayList<>();
        for (Line line : lines) {
            if (line.confidence() < MIN_SOFT || line.y() < 0.80 || line.y() > 0.88 || line.x() < 0.70) {
                continue;
            }
            if (!titlePiece(line.text())) {
                continue;
            }
            pieces.add(line);
        }
        pieces.sort(Comparator.comparingDouble(Line::y).thenComparingDouble(Line::x));
        if (pieces.isEmpty()) {
            return MaterialLineSupport.UNLABELED;
        }
        StringBuilder builder = new StringBuilder();
        for (Line piece : pieces) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(piece.text());
        }
        String value = builder.toString().trim();
        return value.isEmpty() ? MaterialLineSupport.UNLABELED : value;
    }

    private static boolean titlePiece(String text) {
        if (!text.contains(" ") || !text.matches("[A-Za-z][A-Za-z0-9 ,./+\\-]{5,}")) {
            return false;
        }
        String token = letters(text);
        if (LABELS.contains(token)) {
            return false;
        }
        return !(token.contains("DRAWING") || token.contains("NUMBER") || token.contains("NOTICE")
                || token.contains("PROPRIETARY") || token.contains("PROPERTY")
                || token.contains("DIMENSION") || token.contains("MILLIMETER") || token.contains("GENERATED")
                || token.contains("PROJECTION") || token.contains("REVISION") || token.contains("CONFIDENCE")
                || token.contains("TOLERANCE") || token.contains("THIRD"));
    }

    private static String scale(List<Line> title) {
        for (List<Line> row : rows(title, 0.03)) {
            boolean labeled = false;
            for (Line line : row) {
                if ("SCALE".equals(letters(line.text()))) {
                    labeled = true;
                    continue;
                }
                if (labeled && SCALE_VALUE.matcher(line.text()).matches()) {
                    return line.text();
                }
            }
        }
        return MaterialLineSupport.UNLABELED;
    }

    private static String size(List<Line> title) {
        for (Line line : title) {
            if (line.confidence() >= MIN_STRONG
                    && line.x() >= 0.60
                    && line.x() < 0.92
                    && line.y() >= 0.85
                    && line.text().matches("[A-E]")) {
                return line.text();
            }
        }
        return MaterialLineSupport.UNLABELED;
    }

    private static String unit(List<Line> lines) {
        boolean millimeter = false;
        boolean inch = false;
        for (Line line : lines) {
            if (line.confidence() < MIN_STRONG) {
                continue;
            }
            String token = letters(line.text());
            if ("METRIC".equals(token) || line.text().toUpperCase(Locale.ROOT).contains("MILLIMETER")) {
                millimeter = true;
            }
            if (line.text().toUpperCase(Locale.ROOT).contains("INCH")) {
                inch = true;
            }
        }
        if (millimeter == inch) {
            return MaterialLineSupport.UNLABELED;
        }
        return millimeter ? "毫米" : "英寸";
    }

    private static String role(List<Line> title, String label) {
        for (List<Line> row : rows(title, 0.03)) {
            for (int i = 0; i < row.size(); i++) {
                if (!label.equals(letters(row.get(i).text()))) {
                    continue;
                }
                for (int j = i + 1; j < row.size(); j++) {
                    String text = row.get(j).text().trim();
                    if (LABELS.contains(letters(text))) {
                        break;
                    }
                    if ("DATE".equals(label)) {
                        Matcher matcher = DATE.matcher(text);
                        if (matcher.find()) {
                            return matcher.group();
                        }
                        continue;
                    }
                    if (text.matches("[A-Za-z]{1,6}")) {
                        return text.toUpperCase(Locale.ROOT);
                    }
                }
            }
        }
        return MaterialLineSupport.UNLABELED;
    }

    private static String sheet(List<Line> lines) {
        Matcher matcher = SHEET.matcher(join(lines));
        if (!matcher.find()) {
            return MaterialLineSupport.UNLABELED;
        }
        return matcher.group(1) + " OF " + matcher.group(2);
    }

    private static List<String> tolerances(List<Line> title) {
        List<String> found = new ArrayList<>();
        for (Line line : title) {
            if (line.confidence() < MIN_STRONG) {
                continue;
            }
            Matcher matcher = TOLERANCE.matcher(line.text());
            if (matcher.matches()) {
                found.add(matcher.group(1).toUpperCase(Locale.ROOT) + " ±" + matcher.group(2));
            }
            if (found.size() >= MAX_ITEMS) {
                break;
            }
        }
        return found;
    }

    private static List<String> packaging(List<String> notes) {
        List<String> found = new ArrayList<>();
        for (String note : notes) {
            String lower = note.toLowerCase(Locale.ROOT);
            for (String key : PACKAGING_KEYS) {
                if (lower.contains(key)) {
                    found.add(note);
                    break;
                }
            }
        }
        return found;
    }

    private static List<String> other(List<Line> lines, List<String> used) {
        List<String> found = new ArrayList<>();
        for (Line line : lines) {
            if (line.confidence() < MIN_STRONG || line.text().length() < 8 || letters(line.text()).length() < 4) {
                continue;
            }
            String token = letters(line.text());
            if (LABELS.contains(token) || "NONE".equals(token) || line.text().matches("[A-E]")) {
                continue;
            }
            if (containsText(used, line.text()) || DIMENSION.matcher(line.text()).matches() || materialCallout(line)) {
                continue;
            }
            found.add(line.text());
            if (found.size() >= MAX_ITEMS) {
                break;
            }
        }
        return uniqueTexts(found);
    }

    private static List<Line> matching(List<Line> lines, double minimum, java.util.function.Predicate<Line> test) {
        List<Line> found = new ArrayList<>();
        for (Line line : lines) {
            if (line.confidence() < minimum || !test.test(line)) {
                continue;
            }
            found.add(line);
        }
        found.sort(Comparator.comparingDouble(Line::y).thenComparingDouble(Line::x));
        return found;
    }

    private static List<Line> unique(List<Line> lines) {
        List<Line> found = new ArrayList<>();
        for (Line line : lines) {
            String key = key(line.text());
            boolean nested = false;
            for (int i = 0; i < found.size(); i++) {
                String have = key(found.get(i).text());
                int shorter = Math.min(key.length(), have.length());
                boolean overlap = shorter >= 12 && (have.contains(key) || key.contains(have));
                if (have.equals(key) || overlap) {
                    if (line.text().length() > found.get(i).text().length()) {
                        found.set(i, line);
                    }
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                found.add(line);
            }
        }
        return found;
    }

    private static List<Line> dedupe(List<Line> lines) {
        return unique(lines);
    }

    private static List<List<Line>> rows(List<Line> lines, double gap) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(Line::y).thenComparingDouble(Line::x));
        List<List<Line>> grouped = new ArrayList<>();
        for (Line line : sorted) {
            if (grouped.isEmpty()) {
                grouped.add(new ArrayList<>());
            } else {
                List<Line> current = grouped.get(grouped.size() - 1);
                double previous = current.get(current.size() - 1).y();
                if (Math.abs(line.y() - previous) > gap) {
                    grouped.add(new ArrayList<>());
                }
            }
            grouped.get(grouped.size() - 1).add(line);
        }
        for (List<Line> row : grouped) {
            row.sort(Comparator.comparingDouble(Line::x));
        }
        return grouped;
    }

    private static List<String> texts(List<Line> lines) {
        List<String> found = new ArrayList<>();
        for (Line line : lines) {
            if (found.size() >= MAX_ITEMS) {
                break;
            }
            found.add(line.text());
        }
        return found;
    }

    private static List<String> uniqueTexts(List<String> lines) {
        List<String> found = new ArrayList<>();
        for (String line : lines) {
            String token = key(line);
            boolean nested = false;
            for (int i = 0; i < found.size(); i++) {
                String have = key(found.get(i));
                int shorter = Math.min(token.length(), have.length());
                boolean overlap = shorter >= 12 && (have.contains(token) || token.contains(have));
                if (have.equals(token) || overlap) {
                    if (line.length() > found.get(i).length()) {
                        found.set(i, line);
                    }
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                found.add(line);
            }
        }
        return found;
    }

    private static boolean containsText(List<?> lines, String text) {
        String key = key(text);
        for (Object line : lines) {
            String have = line instanceof Line ? ((Line) line).text() : String.valueOf(line);
            String other = key(have);
            int shorter = Math.min(key.length(), other.length());
            if (other.equals(key) || (shorter >= 12 && (other.contains(key) || key.contains(other)))) {
                return true;
            }
        }
        return false;
    }

    private static String key(String text) {
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String letters(String text) {
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    private static String join(List<Line> lines) {
        StringBuilder builder = new StringBuilder();
        for (Line line : lines) {
            builder.append(line.text()).append('\n');
        }
        return builder.toString();
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return MaterialLineSupport.UNLABELED;
        }
        return matcher.group(1);
    }

    private static void addIfLabeled(List<String> used, String value) {
        if (value != null && !value.isBlank() && !MaterialLineSupport.UNLABELED.equals(value)) {
            used.add(value);
        }
    }
}
