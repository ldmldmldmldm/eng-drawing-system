package org.nineai.engdrawing;

import java.util.List;
import java.util.Map;

/** 工程图生成：层次资产与合成结果。 */
public final class EngDrawingModels {

    private EngDrawingModels() {
    }

    /** 已保存的三维层次资产摘要。 */
    public record StackAssetSummary(
            String id,
            String drawingNo,
            String title,
            long savedAt,
            int plateCount,
            int calloutCount) {
    }

    /** 层次资产详情（不含 DXF 二进制，下载走单独接口）。 */
    public record StackAsset(
            String id,
            String drawingNo,
            String title,
            long savedAt,
            int plateCount,
            int calloutCount,
            String fileName,
            Map<String, Object> job) {
    }

    /** 保存层次请求：与 stack-explode 同一套 plates/callouts。 */
    public record StackSaveBody(
            String drawingNo,
            String title,
            List<Map<String, Object>> plates,
            List<Map<String, Object>> callouts,
            String previewPngBase64) {
    }

    /** 合成工程图结果。 */
    public record ComposeResult(
            String fileName,
            String drawingNo,
            String stackAssetId,
            String message,
            List<String> warnings,
            String previewPngBase64,
            String previewSvg,
            String zipBase64,
            String dxfFileName,
            String dxfBase64,
            Map<String, Object> explosionSlot) {
    }
}
