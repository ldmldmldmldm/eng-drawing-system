package org.nineai.engdrawing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 叠层/NOTE 文本解析辅助。供图纸转换抽编号 NOTE；材料只认原文，看不清写未标注。
 */
public final class DrawingStackMaterial {

    private static final Pattern NOTE_MATERIAL = Pattern.compile(
            "(?i)(?:\\[)?(\\d{1,3})(?:\\])?\\s*[.)]?\\s*"
                    + "((?:RELEASE\\s+LINER|ADHESIVE|SHIM|FOAM|TAPE|COVER(?:\\s+TAPE)?|"
                    + "CARRIER(?:\\s+TAPE)?|RELEASE\\s+COATING)[^\\r\\n]{0,40}?)?\\s*"
                    + "MATERIAL\\s*[:：]\\s*(.+)");
    /** 点号后不能直接跟数字，避免把 2.00±0.08 拆成编号 NOTE */
    private static final Pattern NOTE_LINE = Pattern.compile(
            "(?i)^(?:\\[)?([1-9]\\d{0,2})(?:\\])?\\s*[.)、．](?!\\d)\\s*(.+)$");
    /** 尺寸/公差行：几乎无字母，不应进 NOTE */
    private static final Pattern DIMENSION_NOTE_BODY = Pattern.compile(
            "(?i)^\\s*(?:[Ø⌀])?\\s*\\d+(?:\\.\\d+)?\\s*(?:±\\s*\\d+(?:\\.\\d+)?|(?:mm|毫米)).*"
                    + "|^\\s*\\d+(?:\\.\\d+)?\\s*[±÷].*"
                    + "|^\\s*\\d+(?:\\.\\d+)?\\s*$");

    private DrawingStackMaterial() {
    }

    /** 从纯文本抽出带编号的 NOTE 行（及 MATERIAL 整行）。 */
    static List<String> parseNumberedNotes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, String> byNo = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = NOTE_LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            String no = m.group(1);
            String body = m.group(2).trim();
            if (body.length() < 8 || isDimensionNoteBody(body)) {
                continue;
            }
            byNo.putIfAbsent(no, no + ". " + body);
        }
        Matcher mat = NOTE_MATERIAL.matcher(text);
        while (mat.find()) {
            String no = mat.group(1);
            byNo.putIfAbsent(no, clip(mat.group(0)));
        }
        List<Map.Entry<String, String>> rows = new ArrayList<>(byNo.entrySet());
        rows.sort(Comparator.comparingInt(e -> parseInt(e.getKey(), 999)));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> row : rows) {
            out.add(row.getValue());
        }
        return out;
    }

    /** 尺寸公差正文：字母过少则不当 NOTE */
    static boolean isDimensionNoteBody(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        String t = body.trim();
        if (DIMENSION_NOTE_BODY.matcher(t).matches()) {
            return true;
        }
        int letters = 0;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetter(t.charAt(i))) {
                letters++;
            }
        }
        if (letters < 4 && (t.indexOf('±') >= 0 || t.indexOf('+') >= 0 || t.matches(".*\\d\\.\\d.*"))) {
            return true;
        }
        return false;
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        String t = value.trim().replaceAll("\\s+", " ");
        return t.length() > 240 ? t.substring(0, 240) : t;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return fallback;
        }
    }
}
