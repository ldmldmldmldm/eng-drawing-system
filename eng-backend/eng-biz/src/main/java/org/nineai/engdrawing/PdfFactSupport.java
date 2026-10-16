package org.nineai.engdrawing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只抄二维文字里写明的栏。标签和数值不在同一行时不配对，避免把人名安到设计或日期上。
 */
public final class PdfFactSupport {

    private static final int MAX_ITEMS = 200;
    private static final Pattern DRAWING_NUMBER = Pattern.compile(
            "(?i)DRAWING\\s+NUMBER\\s*[:：]?\\s{0,40}([0-9]{3}-[0-9A-Z][0-9A-Z\\-]*)");
    private static final Pattern APN = Pattern.compile("(?i)APN\\s*[:：]\\s*([0-9]{3}-[0-9A-Z][0-9A-Z\\-]*)");
    private static final Pattern SHEET = Pattern.compile("(?i)^SHEET\\s+\\d+\\s+OF\\s+\\d+\\s*$");
    private static final Pattern TITLE_SAME = Pattern.compile("(?i)^TITLE\\s*[:：]\\s*(\\S.*)$");
    private static final Pattern REV_SAME = Pattern.compile("(?i)^REV(?:ISION)?\\.?\\s+([0-9]{1,4})$");
    private static final Pattern REV_LABEL = Pattern.compile("(?i)^REV(?:ISION)?\\.?$");
    private static final Pattern REV_TOKEN = Pattern.compile("(?i)^[0-9A-Z]{1,4}$");
    private static final Pattern SIZE_SAME = Pattern.compile("(?i)^SIZE\\s*[:：]?\\s*([A-E0-9]+)$");
    private static final Pattern SCALE_SAME = Pattern.compile("(?i)^SCALE\\s*[:：]?\\s+(\\S.*)$");
    private static final Pattern SCALE_CN = Pattern.compile("比例\\s*[:：]?\\s*(\\d+\\s*[:：]\\s*\\d+)");
    private static final Pattern ROLE_SAME = Pattern.compile("(?i)^(DRAFTER|DESIGNER|DATE)\\s*[:：]\\s*(\\S.*)$");
    private static final Pattern TOLERANCE_ROW = Pattern.compile(
            "(?i)^(X(?:\\.X+)+|ANGLES)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*$");
    private static final Pattern NOTE_HEADER = Pattern.compile("(?i)^NOTES?\\s*[:：].*$|^NOTES?\\s*$");
    private static final Pattern NOTE_ITEM = Pattern.compile("^\\d+\\s*[.、．]\\s*\\S.*");
    private static final Pattern DIMENSION = Pattern.compile(
            "(?i)^\\s*(?:[Ø⌀])?\\s*\\d+(?:\\.\\d+)?\\s*(?:±\\s*\\d+(?:\\.\\d+)?|(?:mm|毫米))\\s*$");
    private static final Pattern STOP = Pattern.compile(
            "(?i)^(DRAWING\\s+NUMBER|TITLE|SCALE|SIZE|SHEET|REV(?:ISION)?\\.?)\\b.*|"
                    + "^\\d{1,4}[/\\-]\\d{1,2}[/\\-]\\d{1,4}$|^APPLE\\b.*");
    private static final String[] PACKAGING_KEYS = {
            "carrier tape", "cover tape", "release liner", "reel quantity",
            "package quantity", "splice", "leader", "trailer"
    };

    private PdfFactSupport() {
    }

    public record Facts(
            String material,
            String titleBlockNo,
            String title,
            String apn,
            String revision,
            String sheet,
            String drawingSize,
            String scale,
            String unit,
            String drafter,
            String designer,
            String drawnDate,
            List<String> packaging,
            List<String> tolerances,
            List<String> notes,
            List<String> dimensionTexts) {
    }

    public static Facts read(String text) {
        String source = text == null ? "" : text;
        List<String> lines = List.of(source.split("\\R", -1));
        List<String> notes = notes(lines);
        return new Facts(
                MaterialLineSupport.extract(source),
                first(DRAWING_NUMBER, source),
                sameLine(lines, TITLE_SAME),
                first(APN, source),
                revision(lines),
                join(matching(lines, SHEET)),
                sameLine(lines, SIZE_SAME),
                scale(lines),
                unit(lines),
                role(lines, "DRAFTER"),
                role(lines, "DESIGNER"),
                role(lines, "DATE"),
                packaging(notes),
                tolerances(lines),
                notes,
                dimensions(lines));
    }

    private static String revision(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            Matcher same = REV_SAME.matcher(line);
            if (same.matches()) {
                return same.group(1);
            }
            if (REV_LABEL.matcher(line).matches() && i + 1 < lines.size()) {
                String next = lines.get(i + 1).trim();
                if (REV_TOKEN.matcher(next).matches()) {
                    return next;
                }
            }
        }
        return MaterialLineSupport.UNLABELED;
    }

    private static String scale(List<String> lines) {
        Set<String> found = new LinkedHashSet<>();
        for (String raw : lines) {
            String line = raw.trim();
            Matcher same = SCALE_SAME.matcher(line);
            if (same.matches()) {
                found.add(collapse(same.group(1)));
            }
            Matcher chinese = SCALE_CN.matcher(line);
            if (chinese.find()) {
                found.add(collapse(chinese.group(1)));
            }
            if (found.size() >= MAX_ITEMS) {
                break;
            }
        }
        return join(new ArrayList<>(found));
    }

    private static String unit(List<String> lines) {
        boolean metricWord = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.toUpperCase(Locale.ROOT).contains("DIMENSIONS ARE IN MILLIMETERS")) {
                return "毫米";
            }
            if (line.toUpperCase(Locale.ROOT).contains("DIMENSIONS ARE IN INCHES")) {
                return "英寸";
            }
            if ("METRIC".equalsIgnoreCase(line)) {
                metricWord = true;
            }
        }
        return metricWord ? "毫米" : MaterialLineSupport.UNLABELED;
    }

    private static String role(List<String> lines, String label) {
        for (String raw : lines) {
            Matcher matcher = ROLE_SAME.matcher(raw.trim());
            if (matcher.matches() && label.equalsIgnoreCase(matcher.group(1))) {
                return collapse(matcher.group(2));
            }
        }
        return MaterialLineSupport.UNLABELED;
    }

    private static List<String> tolerances(List<String> lines) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!"TOLERANCES".equalsIgnoreCase(lines.get(i).trim())) {
                continue;
            }
            for (int j = i + 1; j < lines.size() && found.size() < MAX_ITEMS; j++) {
                String line = lines.get(j).trim();
                if (line.isEmpty()) {
                    break;
                }
                Matcher row = TOLERANCE_ROW.matcher(line);
                if (!row.matches()) {
                    break;
                }
                found.add(row.group(1).toUpperCase(Locale.ROOT) + "：" + row.group(2));
            }
        }
        return distinct(found);
    }

    private static List<String> notes(List<String> lines) {
        List<String> found = new ArrayList<>();
        boolean inNotes = false;
        StringBuilder current = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (NOTE_HEADER.matcher(line).matches()) {
                current = finishNote(found, current);
                inNotes = true;
                continue;
            }
            if (!inNotes) {
                continue;
            }
            if (line.isEmpty() || STOP.matcher(line).matches()) {
                current = finishNote(found, current);
                inNotes = false;
                continue;
            }
            if (NOTE_ITEM.matcher(line).matches()) {
                current = finishNote(found, current);
                current = new StringBuilder(collapse(line));
            } else if (current != null) {
                current.append(' ').append(collapse(line));
            }
        }
        finishNote(found, current);
        return distinct(found);
    }

    private static StringBuilder finishNote(List<String> found, StringBuilder current) {
        if (current != null && current.length() > 0 && found.size() < MAX_ITEMS) {
            found.add(current.toString());
        }
        return null;
    }

    private static List<String> packaging(List<String> notes) {
        List<String> found = new ArrayList<>();
        for (String note : notes) {
            if (found.size() >= MAX_ITEMS) {
                break;
            }
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

    private static List<String> dimensions(List<String> lines) {
        List<String> found = new ArrayList<>();
        for (String raw : lines) {
            if (found.size() >= MAX_ITEMS) {
                break;
            }
            String line = collapse(raw.trim());
            if (DIMENSION.matcher(line).matches()) {
                found.add(line);
            }
        }
        return distinct(found);
    }

    private static List<String> matching(List<String> lines, Pattern pattern) {
        List<String> found = new ArrayList<>();
        for (String raw : lines) {
            String line = collapse(raw.trim());
            if (pattern.matcher(line).matches() && found.size() < MAX_ITEMS) {
                found.add(line);
            }
        }
        return distinct(found);
    }

    private static String sameLine(List<String> lines, Pattern pattern) {
        for (String raw : lines) {
            Matcher matcher = pattern.matcher(raw.trim());
            if (matcher.matches()) {
                return collapse(matcher.group(1));
            }
        }
        return MaterialLineSupport.UNLABELED;
    }

    private static String first(Pattern pattern, String text) {
        if (text.isBlank()) {
            return MaterialLineSupport.UNLABELED;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return MaterialLineSupport.UNLABELED;
        }
        String value = matcher.group(1).trim();
        return value.isEmpty() ? MaterialLineSupport.UNLABELED : value;
    }

    private static String join(List<String> values) {
        if (values.isEmpty()) {
            return MaterialLineSupport.UNLABELED;
        }
        return String.join("；", values);
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static String collapse(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
