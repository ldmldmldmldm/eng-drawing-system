package org.nineai.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 工程图任务请求/响应 */
public final class EngTaskDtos {

    private EngTaskDtos() {
    }

    @Data
    public static class CreateRequest {
        private String title;
    }

    @Data
    public static class UpdateRequest {
        private String title;
        private Integer currentStep;
        private String status;
        private String stackAssetId;
        /** true=跳过层次；false=取消跳过（如又保存了层次） */
        private Boolean stackSkipped;
        /** true=跳过①抽取；false=取消跳过（如又完成核对） */
        private Boolean extractSkipped;
        private String stepFileName;
        private String drawingNo;
        private String message;
    }

    @Data
    public static class CompleteRequest {
        private String stackAssetId;
        private String drawingNo;
        private String message;
        /** 直接写路径（已落盘） */
        private String resultPreviewPngPath;
        private String resultDxfPath;
        private String resultZipPath;
        /** Base64 产物：服务端落到任务目录并回写路径 */
        private String resultPreviewPngBase64;
        private String resultDxfBase64;
        private String resultZipBase64;
    }

    /**
     * 审校快照：抽取结果 + 材料/译文确认态。
     * 文本态存 annot.json；预览图/裁剪图由服务落独立文件，接口进出时带回。
     */
    @Data
    public static class AnnotSnapshot {
        private Integer version;
        private String fileName;
        private String drawingNo;
        private String message;
        private List<String> warnings;
        private Object notes;
        private Object materials;
        /** ③ 对照用真 2D 基线（与采用结果 materials 分离） */
        private Object materialsBaseline;
        private Object sheetLayout;
        private Boolean materialsConfirmed;
        private Boolean notesConfirmed;
        /** ① 逐条 NOTE 对图确认 */
        private List<Boolean> noteVerified;
        /** ① 逐条材料对图确认 */
        private List<Boolean> materialVerified;
        /** ④ 逐条 NOTE 译文确认 */
        private List<Boolean> noteTranslateVerified;
        /** ④ 逐条材料审校确认 */
        private List<Boolean> materialReviewVerified;
        /** ① 空 NOTE 列表时显式确认 */
        private Boolean emptyNotesAcked;
        /** ① 空材料列表时显式确认 */
        private Boolean emptyMaterialsAcked;
        private Integer wizardStep;
        /** 客户图纸预览 PNG（Base64）；落盘 convert-preview.png，不写进 annot.json */
        private String previewPngBase64;
        /** 单页 SVG 预览；落盘 convert-preview.svg */
        private String previewSvg;
        /** 多页 SVG；落盘 convert-preview-svgs.json */
        private List<String> previewSvgs;
        /** 材料裁剪图 Base64 列表；落盘 convert-material-crops.json */
        private List<String> materialCrops;
        /** true：仅高清预览，待用户选截图/自动抽取 */
        private Boolean sheetReady;
    }

    @Data
    public static class Page {
        private long total;
        private int page;
        private int size;
        private List<Map<String, Object>> items;
    }
}
