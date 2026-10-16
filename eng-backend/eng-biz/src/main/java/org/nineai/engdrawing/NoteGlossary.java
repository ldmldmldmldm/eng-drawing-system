package org.nineai.engdrawing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NOTE 英译中：TERM/PHRASE 词典最长匹配（短词带词边界）。
 * 历史 EXAMPLE 已废弃，不再作为用户层级；脏长句归入 PHRASE。
 * 料号/标准号/数字/单位先保护再替换。
 */
public final class NoteGlossary {

    public static final String DICT = "dict";
    public static final String UNTRANSLATED = "untranslated";
    public static final String MANUAL = "manual";
    public static final String LLM = "llm";

    public static final String KIND_TERM = "TERM";
    public static final String KIND_PHRASE = "PHRASE";
    /** @deprecated 仅兼容旧数据/导入；会规范为 PHRASE */
    @Deprecated
    public static final String KIND_EXAMPLE = "EXAMPLE";

    private static final Pattern NOTE_PREFIX = Pattern.compile(
            "^\\s*(?:\\[)?(\\d{1,3})(?:\\])?\\s*[.)、．]\\s*");
    /** 含任意拉丁字母即视为英文（短词如 who/OK 也要走翻译，勿要求 ≥4 字母） */
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]");

    /** 不译区：标准号、料号样式、纯数字+单位。 */
    private static final Pattern PROTECT = Pattern.compile(
            "(?i)\\b(?:ASME|ISO|IEC|GB/?T?|DIN|JIS|ASTM|EN)\\s*[A-Z0-9][\\w./\\-]*"
                    + "|\\b[A-Z]{1,5}[-/][A-Z0-9][A-Z0-9./\\-]{2,}"
                    + "|\\b\\d+(?:\\.\\d+)?(?:\\s*(?:mm|um|μm|µm|cm|m|mil|in|°|deg))?\\b"
                    + "|±\\s*\\d+(?:\\.\\d+)?");

    private static final List<Phrase> BUILTIN = List.of(
            new Phrase("UNLESS OTHERWISE SPECIFIED", "除另有规定外", KIND_PHRASE),
            new Phrase("INTERPRET DRAWING PER ASME Y14.5", "按 ASME Y14.5 读图", KIND_PHRASE),
            new Phrase("DIMENSIONS ARE IN MILLIMETERS", "尺寸单位为毫米", KIND_PHRASE),
            new Phrase("DIMENSIONS ARE IN MILLIMETRES", "尺寸单位为毫米", KIND_PHRASE),
            new Phrase("ALL DIMENSIONS ARE IN MM", "全部尺寸单位为毫米", KIND_PHRASE),
            new Phrase("ALL DIMENSIONS IN MM", "全部尺寸单位为毫米", KIND_PHRASE),
            new Phrase("DIMENSIONS ARE IN MM", "尺寸单位为毫米", KIND_PHRASE),
            new Phrase("DO NOT SCALE DRAWING", "禁止按图缩放", KIND_PHRASE),
            new Phrase("DO NOT SCALE THIS DRAWING", "禁止按图缩放", KIND_PHRASE),
            new Phrase("DO NOT SCALE DRAWINGS", "禁止按图缩放", KIND_PHRASE),
            new Phrase("THIRD ANGLE PROJECTION", "第三角投影", KIND_PHRASE),
            new Phrase("FIRST ANGLE PROJECTION", "第一角投影", KIND_PHRASE),
            new Phrase("BREAK ALL SHARP EDGES", "锐边倒钝", KIND_PHRASE),
            new Phrase("REMOVE ALL BURRS AND SHARP EDGES", "去毛刺并倒钝锐边", KIND_PHRASE),
            new Phrase("REMOVE ALL BURRS", "去毛刺", KIND_PHRASE),
            new Phrase("SEE ASSEMBLY DRAWING", "见装配图", KIND_PHRASE),
            new Phrase("PRIMARY RELEASE LINER", "主离型膜", KIND_PHRASE),
            new Phrase("SECONDARY RELEASE LINER", "次离型膜", KIND_PHRASE),
            new Phrase("RELEASE COATING", "离型涂层", KIND_PHRASE),
            new Phrase("RELEASE LINER", "离型膜", KIND_PHRASE),
            new Phrase("CARRIER TAPE", "载带", KIND_PHRASE),
            new Phrase("COVER TAPE", "盖带", KIND_PHRASE),
            new Phrase("PRESSURE SENSITIVE ADHESIVE", "压敏胶", KIND_PHRASE),
            new Phrase("DIE CUT", "模切", KIND_PHRASE),
            new Phrase("KISS CUT", "半切", KIND_PHRASE),
            new Phrase("HALF CUT", "半切", KIND_PHRASE),
            new Phrase("LOW TACK", "低粘", KIND_PHRASE),
            new Phrase("HIGH TACK", "高粘", KIND_PHRASE),
            new Phrase("SYNTHETIC GRAPHITE", "合成石墨", KIND_PHRASE),
            new Phrase("PACKAGE QUANTITY", "包装数量", KIND_PHRASE),
            new Phrase("PART NUMBER", "件号", KIND_PHRASE),
            new Phrase("ITEM NO", "项号", KIND_PHRASE),
            new Phrase("MATERIAL:", "材料：", KIND_PHRASE),
            new Phrase("MATERIAL：", "材料：", KIND_PHRASE),
            new Phrase("THICKNESS:", "厚度：", KIND_PHRASE),
            new Phrase("THICKNESS：", "厚度：", KIND_PHRASE),
            new Phrase("COLOR:", "颜色：", KIND_PHRASE),
            new Phrase("COLOUR:", "颜色：", KIND_PHRASE),
            new Phrase("ADHESIVE", "胶", KIND_TERM),
            new Phrase("LINER", "离型膜", KIND_TERM),
            new Phrase("SHIM", "垫片", KIND_TERM),
            new Phrase("FOAM", "泡棉", KIND_TERM),
            new Phrase("TAPE", "胶带", KIND_TERM),
            new Phrase("GASKET", "垫片", KIND_TERM),
            new Phrase("GRAPHITE", "石墨", KIND_TERM),
            new Phrase("PSA", "压敏胶", KIND_TERM),
            new Phrase("POLYCARBONATE", "聚碳酸酯", KIND_TERM),
            new Phrase("TRANSLUCENT", "半透明", KIND_TERM),
            new Phrase("TRANSPARENT", "透明", KIND_TERM),
            new Phrase("BLACK", "黑", KIND_TERM),
            new Phrase("WHITE", "白", KIND_TERM),
            new Phrase("BLUE", "蓝", KIND_TERM),
            new Phrase("CLEAR", "透明", KIND_TERM),
            new Phrase("NOTES:", "说明：", KIND_PHRASE),
            new Phrase("NOTES", "说明", KIND_TERM));

    /** 库内 TERM/PHRASE。 */
    private static final CopyOnWriteArrayList<Phrase> EXTRA = new CopyOnWriteArrayList<>();

    private NoteGlossary() {
    }

    public record Translated(String original, String translated, String status) {
    }

    /**
     * 用库内短语替换 EXTRA。
     * pairs 元素可为 [en, zh] 或 [en, zh, kind]；非 TERM/PHRASE 不进入匹配词典。
     */
    public static void replaceExtraPhrases(List<String[]> enZhPairs) {
        List<Phrase> next = new ArrayList<>();
        if (enZhPairs != null) {
            for (String[] pair : enZhPairs) {
                if (pair == null || pair.length < 2) {
                    continue;
                }
                String en = pair[0] == null ? "" : pair[0].trim();
                String zh = pair[1] == null ? "" : pair[1].trim();
                String kind = pair.length >= 3 && pair[2] != null && !pair[2].isBlank()
                        ? pair[2].trim().toUpperCase(Locale.ROOT)
                        : KIND_PHRASE;
                if (en.isEmpty() || zh.isEmpty()) {
                    continue;
                }
                if (!KIND_TERM.equals(kind) && !KIND_PHRASE.equals(kind)) {
                    // 旧 EXAMPLE 等：按英文重归类为 TERM/PHRASE
                    kind = classifyKind(en);
                }
                next.add(new Phrase(en, zh, kind));
            }
        }
        EXTRA.clear();
        EXTRA.addAll(next);
    }

    /** 词数≤2 且不含冒号 → TERM，否则 PHRASE（不再产出 EXAMPLE）。 */
    public static String classifyKind(String en) {
        String s = en == null ? "" : en.trim();
        if (s.isEmpty()) {
            return KIND_PHRASE;
        }
        int words = s.split("\\s+").length;
        if (words <= 2 && !s.contains(":") && !s.contains("：")
                && s.length() <= 80
                && !s.contains("@@")
                && !s.contains("____")
                && countDigits(s) < 6) {
            return KIND_TERM;
        }
        return KIND_PHRASE;
    }

    /** 抽取不译：只保留原文，译文留给④。 */
    public static Translated defer(String original) {
        String src = original == null ? "" : original.trim();
        return new Translated(src, "", UNTRANSLATED);
    }

    /** 原文行首序号前缀（如「1. 」「2) 」「[3] 」）；无则空串。 */
    public static String leadingNumberPrefix(String original) {
        String src = original == null ? "" : original.trim();
        if (src.isEmpty()) {
            return "";
        }
        Matcher prefix = NOTE_PREFIX.matcher(src);
        if (prefix.find() && prefix.start() == 0) {
            return src.substring(0, prefix.end());
        }
        return "";
    }

    /** 去掉一层行首序号，供 LLM 只译正文。 */
    public static String stripLeadingNumber(String text) {
        String s = text == null ? "" : text.trim();
        String head = leadingNumberPrefix(s);
        if (head.isEmpty()) {
            return s;
        }
        return s.substring(head.length()).trim();
    }

    /** 连续去掉行首序号，处理「2. 2. xxx」。 */
    public static String stripAllLeadingNumbers(String text) {
        String s = text == null ? "" : text.trim();
        while (true) {
            String head = leadingNumberPrefix(s);
            if (head.isEmpty()) {
                break;
            }
            s = s.substring(head.length()).trim();
        }
        return s;
    }

    /**
     * 译文与原文对齐行首序号：先剥掉译文开头全部序号（防双号），再补回原文前缀。
     * 原文无行首序号则原样返回译文。
     */
    public static String ensureLeadingNumber(String original, String translated) {
        String zh = translated == null ? "" : translated.trim();
        String head = leadingNumberPrefix(original);
        if (zh.isEmpty()) {
            return zh;
        }
        if (head.isEmpty()) {
            return zh;
        }
        String body = stripAllLeadingNumbers(zh);
        if (body.isEmpty()) {
            return head.trim();
        }
        return head + body;
    }

    /** 保留编号前缀；保护不译区；TERM/PHRASE 按长到短替换。 */
    public static Translated translate(String original) {
        String src = original == null ? "" : original.trim();
        if (src.isEmpty()) {
            return new Translated("", "", UNTRANSLATED);
        }
        String head = leadingNumberPrefix(src);
        String body = head.isEmpty() ? src : src.substring(head.length());
        if (!looksEnglish(body)) {
            // 中文/料号等：原文可作中文名；短英文勿标 dict 成功（否则 who are you 会原样「译」过）
            if (containsCjk(body) || looksKeepAsOriginal(body)) {
                return new Translated(src, src, DICT);
            }
            return new Translated(src, "", UNTRANSLATED);
        }

        ProtectBag protect = protectTokens(body);
        String out = protect.text();
        boolean hit = false;
        List<Phrase> ordered = allMatchPhrases();
        ordered.sort(Comparator
                .comparingInt((Phrase p) -> p.en.length())
                .reversed()
                .thenComparing(p -> p.en, String.CASE_INSENSITIVE_ORDER));
        for (Phrase phrase : ordered) {
            boolean boundary = needsWordBoundary(phrase);
            String next = replaceIgnoreCase(out, phrase.en, phrase.zh, boundary);
            if (!next.equals(out)) {
                hit = true;
                out = next;
            }
        }
        out = restoreTokens(out, protect.tokens());
        if (!hit) {
            return new Translated(src, src, UNTRANSLATED);
        }
        return new Translated(src, head + out, DICT);
    }

    static List<Phrase> allMatchPhrases() {
        List<Phrase> all = new ArrayList<>(BUILTIN.size() + EXTRA.size());
        all.addAll(BUILTIN);
        all.addAll(EXTRA);
        return all;
    }

    /** 短词 / 单双词 TERM 使用词边界，避免误伤。 */
    static boolean needsWordBoundary(Phrase phrase) {
        if (phrase == null || phrase.en.isEmpty()) {
            return false;
        }
        if (KIND_TERM.equals(phrase.kind)) {
            return true;
        }
        int words = phrase.en.trim().split("\\s+").length;
        return words <= 2 && phrase.en.length() <= 16 && !phrase.en.contains(":");
    }

    static boolean looksEnglish(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return LATIN_WORD.matcher(text).find();
    }

    /** 含汉字（含「未标注」等） */
    static boolean containsCjk(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3400 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF) {
                return true;
            }
        }
        return false;
    }

    /** 料号/标准号/纯数字：可原样作中文名，勿走「未译」失败态 */
    static boolean looksKeepAsOriginal(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            return false;
        }
        if (s.matches("^[\\d]+([./\\-_][\\d]+)*$")) {
            return true;
        }
        return !s.contains(" ")
                && s.matches("^[A-Za-z0-9.\\-_/]+$")
                && s.chars().anyMatch(Character::isDigit)
                && s.length() <= 24;
    }

    private static String replaceIgnoreCase(String src, String needle, String repl, boolean wordBoundary) {
        if (src == null || needle == null || needle.isEmpty()) {
            return src;
        }
        String quoted = Pattern.quote(needle);
        String regex = wordBoundary
                ? "(?<![A-Za-z0-9])" + quoted + "(?![A-Za-z0-9])"
                : quoted;
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        return p.matcher(src).replaceAll(Matcher.quoteReplacement(repl));
    }

    private static ProtectBag protectTokens(String text) {
        Map<String, String> tokens = new LinkedHashMap<>();
        Matcher m = PROTECT.matcher(text);
        StringBuffer sb = new StringBuffer();
        int i = 0;
        while (m.find()) {
            String key = "⟦P" + (i++) + "⟧";
            tokens.put(key, m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(key));
        }
        m.appendTail(sb);
        return new ProtectBag(sb.toString(), tokens);
    }

    private static String restoreTokens(String text, Map<String, String> tokens) {
        String out = text;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private static int countDigits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private record ProtectBag(String text, Map<String, String> tokens) {
    }

    record Phrase(String en, String zh, String kind) {
        Phrase {
            en = en == null ? "" : en;
            zh = zh == null ? "" : zh;
            kind = kind == null || kind.isBlank() ? KIND_PHRASE : kind;
        }
    }
}
