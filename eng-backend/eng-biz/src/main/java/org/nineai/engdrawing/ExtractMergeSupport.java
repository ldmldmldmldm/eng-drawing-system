package org.nineai.engdrawing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 抽取多源融合与质检：矢量/规则优先，legend/boxed 纠偏补缺；
 * 无矢量或 OCR 粘连时 legend 优先覆盖。
 */
final class ExtractMergeSupport {

    private static final Pattern MOSTLY_BLANK = Pattern.compile("^[\\s·.•…]+$");
    /** 正文中再出现下一编号（粘连特征；含无句点「2 GEOMETRIC」） */
    private static final Pattern EMBEDDED_NOTE_NO = Pattern.compile(
            "(?i)(?:\\[\\s*[1-9]\\d{0,2}\\s*\\]|(?<![\\d.])[1-9]\\d{0,2}\\s*[.)、．](?!\\d)"
                    + "|(?<![\\d.A-Za-z])[1-9]\\d{0,2}(?=\\s+[A-Z\\[]))");
    private static final int GLUE_LEN = 280;

    private ExtractMergeSupport() {
    }

    /** 矢量字过少：笔画字图纸，不能信 OCR 规则为主。 */
    static boolean isVectorPoor(String vectorText) {
        if (vectorText == null || vectorText.isBlank()) {
            return true;
        }
        int letters = 0;
        for (int i = 0; i < vectorText.length(); i++) {
            if (Character.isLetter(vectorText.charAt(i))) {
                letters++;
                if (letters >= 80) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 单条疑似多条粘连：过长或内含多个序号。 */
    static boolean looksGlued(String original) {
        if (original == null || original.isBlank()) {
            return false;
        }
        String t = original.trim();
        if (t.length() >= GLUE_LEN) {
            return true;
        }
        return countEmbeddedNoteNos(t) >= 2;
    }

    static boolean notesLookGlued(List<DrawingConvertView.NoteLine> notes) {
        if (notes == null || notes.isEmpty()) {
            return true;
        }
        for (DrawingConvertView.NoteLine n : notes) {
            if (n != null && looksGlued(n.original())) {
                return true;
            }
        }
        return false;
    }

    static int countEmbeddedNoteNos(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int n = 0;
        java.util.regex.Matcher m = EMBEDDED_NOTE_NO.matcher(text);
        while (m.find()) {
            n++;
            if (n >= 8) {
                return n;
            }
        }
        return n;
    }

    /**
     * 是否需要 legend 视觉。
     * 无矢量 / 粘连长文 / 条数过少 → 必须跑；短且干净才可跳过。
     */
    static boolean needsLegendVision(List<DrawingConvertView.NoteLine> ruleNotes) {
        return needsLegendVision(ruleNotes, false);
    }

    static boolean needsLegendVision(List<DrawingConvertView.NoteLine> ruleNotes, boolean vectorPoor) {
        if (vectorPoor) {
            return true;
        }
        if (ruleNotes == null || ruleNotes.isEmpty()) {
            return true;
        }
        if (ruleNotes.size() < 2) {
            return true;
        }
        if (notesLookGlued(ruleNotes)) {
            return true;
        }
        int weak = 0;
        for (DrawingConvertView.NoteLine n : ruleNotes) {
            if (n == null) {
                weak++;
                continue;
            }
            String orig = n.original() == null ? "" : n.original().trim();
            if (orig.length() < 12) {
                weak++;
            }
        }
        return weak * 2 >= ruleNotes.size();
    }

    /** 规则材料过稀或字段空时才跑方框 vision。 */
    static boolean needsBoxedVision(List<DrawingConvertView.MaterialRow> ruleMats) {
        if (ruleMats == null || ruleMats.isEmpty()) {
            return true;
        }
        if (ruleMats.size() == 1 && isUnlabeled(ruleMats.get(0).materialEn())
                && isUnlabeled(ruleMats.get(0).materialZh())) {
            return true;
        }
        int thin = 0;
        for (DrawingConvertView.MaterialRow row : ruleMats) {
            if (row == null) {
                thin++;
                continue;
            }
            if (isUnlabeled(row.thickness()) || isUnlabeled(row.materialEn())) {
                thin++;
            }
        }
        return thin * 2 >= ruleMats.size();
    }

    static List<DrawingConvertView.NoteLine> mergeNotes(
            List<DrawingConvertView.NoteLine> rule,
            List<DrawingConvertView.NoteLine> legend,
            List<String> warnings) {
        return mergeNotes(rule, legend, warnings, false);
    }

    /**
     * @param preferLegend true：无矢量/OCR 粘连时以 legend 为准，规则仅补缺号
     */
    static List<DrawingConvertView.NoteLine> mergeNotes(
            List<DrawingConvertView.NoteLine> rule,
            List<DrawingConvertView.NoteLine> legend,
            List<String> warnings,
            boolean preferLegend) {
        if (preferLegend && legend != null && !legend.isEmpty()) {
            Map<String, DrawingConvertView.NoteLine> byNo = new LinkedHashMap<>();
            for (DrawingConvertView.NoteLine n : legend) {
                if (n == null || blank(n.noteNo())) {
                    continue;
                }
                String no = digits(n.noteNo());
                if (!no.isEmpty()) {
                    byNo.put(no, n);
                }
            }
            int ruleFill = 0;
            if (rule != null) {
                for (DrawingConvertView.NoteLine n : rule) {
                    if (n == null || blank(n.noteNo()) || looksGlued(n.original())) {
                        continue;
                    }
                    String no = digits(n.noteNo());
                    if (no.isEmpty() || byNo.containsKey(no)) {
                        continue;
                    }
                    byNo.put(no, n);
                    ruleFill++;
                }
            }
            if (warnings != null) {
                warnings.add("无矢量或 OCR 粘连：NOTE 以说明区视觉为主"
                        + (ruleFill > 0 ? "，规则补入 " + ruleFill + " 条缺号" : ""));
            }
            List<DrawingConvertView.NoteLine> out = new ArrayList<>(byNo.values());
            out.sort(Comparator.comparingInt(n -> parseInt(n.noteNo(), 999)));
            return out;
        }
        Map<String, DrawingConvertView.NoteLine> byNo = new LinkedHashMap<>();
        if (rule != null) {
            for (DrawingConvertView.NoteLine n : rule) {
                if (n == null || blank(n.noteNo())) {
                    continue;
                }
                byNo.put(digits(n.noteNo()), n);
            }
        }
        int legendOnly = 0;
        int conflicts = 0;
        if (legend != null) {
            for (DrawingConvertView.NoteLine n : legend) {
                if (n == null || blank(n.noteNo())) {
                    continue;
                }
                String no = digits(n.noteNo());
                if (no.isEmpty()) {
                    continue;
                }
                DrawingConvertView.NoteLine prior = byNo.get(no);
                if (prior == null) {
                    byNo.put(no, n);
                    legendOnly++;
                    continue;
                }
                String a = safe(prior.original());
                String b = safe(n.original());
                if (looksGlued(a) && !looksGlued(b)) {
                    byNo.put(no, preferCoords(n, prior));
                    conflicts++;
                    continue;
                }
                if (!looksGlued(a) && looksGlued(b)) {
                    continue;
                }
                if (a.equalsIgnoreCase(b) || a.isEmpty() || b.isEmpty()) {
                    byNo.put(no, preferNote(prior, n));
                    continue;
                }
                if (similar(a, b)) {
                    // 相似时取较短干净者，避免 OCR 粘连球
                    DrawingConvertView.NoteLine pick = a.length() <= b.length() ? prior : n;
                    DrawingConvertView.NoteLine other = pick == prior ? n : prior;
                    byNo.put(no, preferCoords(pick, other));
                    continue;
                }
                conflicts++;
                DrawingConvertView.NoteLine pick = looksGlued(a) ? n : (looksGlued(b) ? prior
                        : (a.length() <= b.length() ? prior : n));
                DrawingConvertView.NoteLine other = pick == prior ? n : prior;
                byNo.put(no, preferCoords(pick, other));
                if (warnings != null) {
                    warnings.add("NOTE " + no + " 规则与说明区识读不一致，已保留较干净原文，请核对");
                }
            }
        }
        if (warnings != null) {
            if (legendOnly > 0) {
                warnings.add("说明区视觉补入 " + legendOnly + " 条规则未抽到的 NOTE");
            }
            if (conflicts == 0 && legend != null && !legend.isEmpty() && !byNo.isEmpty()) {
                warnings.add("NOTE 已按规则∩说明区融合（同号不整表覆盖）");
            }
        }
        List<DrawingConvertView.NoteLine> out = new ArrayList<>(byNo.values());
        out.sort(Comparator.comparingInt(n -> parseInt(n.noteNo(), 999)));
        return out;
    }

    /**
     * 材料融合：boxed 字段优先，规则补漏号与空槽；最后槽位规范化。
     */
    static List<DrawingConvertView.MaterialRow> mergeMaterials(
            List<DrawingConvertView.MaterialRow> rule,
            List<DrawingConvertView.MaterialRow> boxed,
            List<String> warnings) {
        Map<String, DrawingConvertView.MaterialRow> byNo = new LinkedHashMap<>();
        if (rule != null) {
            for (DrawingConvertView.MaterialRow row : rule) {
                if (row == null) {
                    continue;
                }
                String no = digits(row.noteNo());
                if (no.isEmpty() && isUnlabeled(row.materialEn()) && isUnlabeled(row.materialZh())) {
                    continue;
                }
                if (DrawingConvertSupport.isShellMaterialName(no, row.materialEn())
                        || DrawingConvertSupport.isNotesComplianceText(row.materialEn())
                        || DrawingConvertSupport.isNotesComplianceText(row.materialZh())) {
                    continue;
                }
                String key = no.isEmpty() ? "_r" + byNo.size() : no;
                byNo.put(key, normalizeSlots(row));
            }
        }
        int boxedOnly = 0;
        int filled = 0;
        if (boxed != null) {
            for (DrawingConvertView.MaterialRow row : boxed) {
                if (row == null) {
                    continue;
                }
                DrawingConvertView.MaterialRow norm = normalizeSlots(row);
                String no = digits(norm.noteNo());
                if (no.isEmpty()) {
                    continue;
                }
                if (DrawingConvertSupport.isShellMaterialName(no, norm.materialEn())
                        || DrawingConvertSupport.isNotesComplianceText(norm.materialEn())
                        || DrawingConvertSupport.isNotesComplianceText(norm.materialZh())) {
                    continue;
                }
                DrawingConvertView.MaterialRow prior = byNo.get(no);
                if (prior == null) {
                    byNo.put(no, norm);
                    boxedOnly++;
                    continue;
                }
                DrawingConvertView.MaterialRow merged = fuseMaterial(prior, norm);
                if (!sameMaterial(prior, merged)) {
                    filled++;
                }
                byNo.put(no, merged);
            }
        }
        if (warnings != null) {
            if (boxedOnly > 0) {
                warnings.add("方框视觉补入 " + boxedOnly + " 行规则未抽到的材料");
            }
            if (filled > 0) {
                warnings.add("已用方框识读补全 " + filled + " 行材料字段");
            }
            if (boxed != null && !boxed.isEmpty()) {
                warnings.add("材料已按规则∪方框融合（同号字段互补，非整表覆盖）");
            }
        }
        List<DrawingConvertView.MaterialRow> out = new ArrayList<>();
        int seq = 1;
        List<DrawingConvertView.MaterialRow> sorted = new ArrayList<>(byNo.values());
        sorted.sort(Comparator.comparingInt(r -> parseInt(r.noteNo(), 999)));
        for (DrawingConvertView.MaterialRow row : sorted) {
            out.add(withSeq(seq++, normalizeSlots(row)));
        }
        if (out.isEmpty()) {
            out.add(new DrawingConvertView.MaterialRow(
                    1, "", MaterialLineSupport.UNLABELED, "", MaterialLineSupport.UNLABELED,
                    "unlabeled", "", MaterialLineSupport.UNLABELED));
        }
        return out;
    }

    /** 槽位：料号/厚度/颜色空则「未标注」；中文空则保持空（留给④翻译），禁止用英文回填中文。 */
    static DrawingConvertView.MaterialRow normalizeSlots(DrawingConvertView.MaterialRow row) {
        if (row == null) {
            return new DrawingConvertView.MaterialRow(
                    1, "", MaterialLineSupport.UNLABELED, "", MaterialLineSupport.UNLABELED,
                    "unlabeled", "", MaterialLineSupport.UNLABELED);
        }
        String en = slotText(row.materialEn());
        String zh = blankZh(row.materialZh());
        return new DrawingConvertView.MaterialRow(
                row.seq(),
                digits(row.noteNo()),
                en,
                zh,
                slotText(row.thickness()),
                blank(row.source()) ? "customer_note" : row.source(),
                row.role() == null ? "" : row.role().trim(),
                slotText(row.color()));
    }

    /** 材料中文：空/未标注保持空串，④ 再译。 */
    private static String blankZh(String value) {
        if (value == null || value.isBlank() || MaterialLineSupport.UNLABELED.equals(value.trim())) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    /** 抽取质检：缺号/重号/空字段/条数异常。 */
    static List<String> qualityWarnings(
            List<DrawingConvertView.NoteLine> notes,
            List<DrawingConvertView.MaterialRow> materials) {
        List<String> out = new ArrayList<>();
        Set<String> noteNos = new LinkedHashSet<>();
        Set<String> dupNotes = new LinkedHashSet<>();
        if (notes != null) {
            for (DrawingConvertView.NoteLine n : notes) {
                if (n == null) {
                    continue;
                }
                String no = digits(n.noteNo());
                if (no.isEmpty()) {
                    out.add("存在无序号 NOTE，请核对");
                    continue;
                }
                if (!noteNos.add(no)) {
                    dupNotes.add(no);
                }
                if (safe(n.original()).length() < 6) {
                    out.add("NOTE " + no + " 原文过短，请对照图纸");
                }
                if (looksGlued(n.original())) {
                    out.add("NOTE " + no + " 疑似多条粘连，请拆分核对");
                }
            }
        }
        if (!dupNotes.isEmpty()) {
            out.add("NOTE 序号重复：" + String.join(",", dupNotes));
        }
        Set<String> matNos = new LinkedHashSet<>();
        Set<String> dupMats = new LinkedHashSet<>();
        int unlabeledFields = 0;
        if (materials != null) {
            for (DrawingConvertView.MaterialRow row : materials) {
                if (row == null) {
                    continue;
                }
                String no = digits(row.noteNo());
                if (!no.isEmpty()) {
                    if (!matNos.add(no)) {
                        dupMats.add(no);
                    }
                }
                if (isUnlabeled(row.materialEn()) && isUnlabeled(row.materialZh())) {
                    unlabeledFields++;
                }
                if (isUnlabeled(row.thickness())) {
                    unlabeledFields++;
                }
                if (isUnlabeled(row.color())) {
                    unlabeledFields++;
                }
            }
        }
        if (!dupMats.isEmpty()) {
            out.add("材料序号重复：" + String.join(",", dupMats));
        }
        if (unlabeledFields > 0 && materials != null && materials.size() > 1) {
            out.add("材料表有 " + unlabeledFields + " 处字段为未标注，请对照图纸补全");
        }
        if (notes != null && notes.size() >= 3 && materials != null && materials.size() >= 2) {
            int missingInMat = 0;
            for (String no : matNos) {
                if (!noteNos.contains(no) && parseInt(no, 0) >= 10) {
                    // 方框材料号常 ≥10，未必出现在 NOTES 列表，不报错
                    continue;
                }
            }
            if (missingInMat > 0) {
                out.add("有 " + missingInMat + " 个材料序号未出现在 NOTE 列表");
            }
        }
        return out;
    }

    private static DrawingConvertView.NoteLine preferNote(
            DrawingConvertView.NoteLine a, DrawingConvertView.NoteLine b) {
        String ao = safe(a.original());
        String bo = safe(b.original());
        if (ao.isEmpty()) {
            return preferCoords(a, b);
        }
        if (bo.isEmpty()) {
            return a;
        }
        return ao.length() >= bo.length() ? a : preferCoords(a, b);
    }

    private static DrawingConvertView.NoteLine preferCoords(
            DrawingConvertView.NoteLine keepText, DrawingConvertView.NoteLine other) {
        Double x = keepText.x() != null ? keepText.x() : other.x();
        Double y = keepText.y() != null ? keepText.y() : other.y();
        return new DrawingConvertView.NoteLine(
                keepText.noteNo(),
                keepText.original(),
                keepText.translated(),
                keepText.status(),
                x,
                y);
    }

    private static DrawingConvertView.MaterialRow fuseMaterial(
            DrawingConvertView.MaterialRow rule, DrawingConvertView.MaterialRow boxed) {
        String en = preferField(boxed.materialEn(), rule.materialEn());
        String zh = preferField(boxed.materialZh(), rule.materialZh());
        if (isUnlabeled(zh) && !isUnlabeled(en)) {
            zh = en;
        }
        String thick = preferField(boxed.thickness(), rule.thickness());
        String color = preferField(boxed.color(), rule.color());
        String role = blank(boxed.role()) ? safe(rule.role()) : boxed.role().trim();
        String source = "fused";
        String no = digits(boxed.noteNo());
        if (no.isEmpty()) {
            no = digits(rule.noteNo());
        }
        return new DrawingConvertView.MaterialRow(
                rule.seq(), no, en, zh, thick, source, role, color);
    }

    private static String preferField(String primary, String fallback) {
        if (!isUnlabeled(primary)) {
            return slotText(primary);
        }
        return slotText(fallback);
    }

    private static boolean sameMaterial(DrawingConvertView.MaterialRow a, DrawingConvertView.MaterialRow b) {
        return safe(a.materialEn()).equalsIgnoreCase(safe(b.materialEn()))
                && safe(a.thickness()).equalsIgnoreCase(safe(b.thickness()))
                && safe(a.color()).equalsIgnoreCase(safe(b.color()));
    }

    private static DrawingConvertView.MaterialRow withSeq(int seq, DrawingConvertView.MaterialRow row) {
        return new DrawingConvertView.MaterialRow(
                seq, row.noteNo(), row.materialEn(), row.materialZh(),
                row.thickness(), row.source(), row.role(), row.color());
    }

    private static boolean similar(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String y = b.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        return x.contains(y) || y.contains(x);
    }

    private static String slotText(String value) {
        if (value == null || value.isBlank() || MOSTLY_BLANK.matcher(value).matches()) {
            return MaterialLineSupport.UNLABELED;
        }
        String t = value.replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || MaterialLineSupport.UNLABELED.equals(t)) {
            return MaterialLineSupport.UNLABELED;
        }
        return t;
    }

    private static boolean isUnlabeled(String value) {
        return value == null || value.isBlank() || MaterialLineSupport.UNLABELED.equals(value.trim());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String digits(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\D", "");
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(digits(s));
        } catch (Exception ex) {
            return fallback;
        }
    }
}
