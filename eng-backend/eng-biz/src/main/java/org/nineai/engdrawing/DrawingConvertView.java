package org.nineai.engdrawing;

import java.util.List;

/** 图纸转换预览 / 生成结果。材料无 MATERIAL 行则为未标注。 */
public final class DrawingConvertView {

    private DrawingConvertView() {
    }

    public record NoteLine(
            String noteNo,
            String original,
            String translated,
            String status,
            Double x,
            Double y) {
    }

    public record MaterialRow(
            int seq,
            String noteNo,
            String materialEn,
            String materialZh,
            String thickness,
            String source,
            String role,
            String color) {
    }

    public record Preview(
            String fileName,
            String drawingNo,
            String message,
            List<String> warnings,
            List<NoteLine> notes,
            List<MaterialRow> materials,
            String previewPngBase64,
            String previewSvg,
            /** 多页 SVG（与 previewSvg 第 0 页一致）；单页时多为 1 项 */
            List<String> previewSvgs,
            List<String> materialCrops,
            java.util.Map<String, Object> sheetLayout,
            /**
             * true：仅完成 ODA+高清预览，NOTE/材料待用户选「截图抽取」或「自动抽取」。
             * null/false：已跑全量抽取（或旧任务无此字段）。
             */
            Boolean sheetReady) {
    }

    public record Apply(
            String fileName,
            String drawingNo,
            String message,
            List<String> warnings,
            String previewPngBase64,
            String previewSvg,
            String zipBase64,
            String dxfFileName,
            /** 转换 DXF Base64；工程图合成复用。 */
            String dxfBase64,
            /** 第 1 页爆炸槽；无则为 null。 */
            java.util.Map<String, Object> explosionSlot) {
    }

    public record ApplyBody(
            List<NoteLine> notes,
            List<MaterialRow> materials,
            /** 预览调框后的分幅；空则服务端重算。 */
            java.util.Map<String, Object> sheetLayout) {
    }

    /** kind：note（NOTE 原文）或 material（材料英文名）。 */
    public record TranslateBody(String text, String kind) {
    }

    public record TranslateResult(String original, String translated, String status) {
    }

    /**
     * ① 用户框选区域识别：OCR + Vision 结构化。
     * kind 为 note 或 material；未用 Vision 时 notes/materials 可能为空，ocrText 仍返回。
     */
    public record RegionExtractResult(
            String kind,
            String ocrText,
            boolean usedVision,
            List<NoteLine> notes,
            List<MaterialRow> materials,
            List<String> warnings) {
    }
}
