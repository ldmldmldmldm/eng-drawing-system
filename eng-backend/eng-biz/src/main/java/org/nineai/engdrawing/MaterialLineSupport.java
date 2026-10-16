package org.nineai.engdrawing;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只认图纸正文里的 MATERIAL: 行。没有这一行就标未标注，不从其他句子补牌号。
 */
public final class MaterialLineSupport {

    public static final String UNLABELED = "未标注";

    /**
     * 只认一行开头的 MATERIAL:。前面可以有区号和序号。
     * 「Carrier tape Material:」这种句子不是材料栏，不能抄进来。
     */
    private static final Pattern MATERIAL_LINE = Pattern.compile(
            "(?im)^\\s*(?:[A-H]\\s+)?(?:\\d+\\s*[.)]\\s*)?MATERIAL\\s*[:：]\\s*([^\\r\\n]+)");
    /** 图框区号被抽文本粘在行尾，不是材料。 */
    private static final Pattern TRAILING_ZONE = Pattern.compile("\\s+[A-H]$");

    private MaterialLineSupport() {
    }

    public static String extract(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            return UNLABELED;
        }
        Matcher matcher = MATERIAL_LINE.matcher(pageText);
        if (!matcher.find()) {
            return UNLABELED;
        }
        String value = matcher.group(1).trim().replaceAll("\\s+", " ");
        int fai = indexOfFai(value);
        if (fai > 0) {
            value = value.substring(0, fai).trim();
        }
        value = TRAILING_ZONE.matcher(value).replaceFirst("");
        if (value.isBlank()) {
            return UNLABELED;
        }
        return value;
    }

    /**
     * 与任务目录客户 DWG 存盘名 {@code customer.dwg} 主干一致，不可当图号
     * （恢复 DWG 时前端/后端常带此固定名，勿写入 eng_task.drawing_no）。
     */
    public static final String DWG_STORE_STEM = "customer";

    /** 图号取文件名主干，例如 806-49036-01.pdf。路径只留最后一段。 */
    public static String drawingNo(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return UNLABELED;
        }
        String base = fileName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.isBlank() ? UNLABELED : base.trim();
    }

    /** 可写入任务/产物的图号：非空、非「未标注」、非 DWG 存盘主干。 */
    public static boolean isUsableDrawingNo(String drawingNo) {
        if (drawingNo == null || drawingNo.isBlank()) {
            return false;
        }
        String t = drawingNo.trim();
        return !UNLABELED.equals(t) && !DWG_STORE_STEM.equalsIgnoreCase(t);
    }

    /** 文件名推图号不可用时，改用 preferred（如层次图号 / 任务已有图号）。 */
    public static String preferDrawingNo(String fromFileOrApply, String preferred) {
        if (isUsableDrawingNo(fromFileOrApply)) {
            return fromFileOrApply.trim();
        }
        if (isUsableDrawingNo(preferred)) {
            return preferred.trim();
        }
        return fromFileOrApply == null || fromFileOrApply.isBlank() ? UNLABELED : fromFileOrApply.trim();
    }

    private static int indexOfFai(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        return upper.indexOf(" FAI");
    }
}
