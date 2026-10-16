package org.nineai.engdrawing;

import java.util.List;

/**
 * ezdxf 从 DXF 抽出的图层/文字/标注明细。只含图里对象，不含炸开字。
 */
public record DxfDetailView(
        List<LayerRow> layers,
        List<TextRow> texts,
        List<DimRow> dimensions,
        List<LeaderRow> leaders,
        List<String> blockNames,
        List<String> layoutNames,
        List<TypeCount> typeCounts,
        boolean truncated,
        boolean layersTruncated,
        boolean textsTruncated,
        boolean dimensionsTruncated,
        String source,
        String message) {

    public record LayerRow(String name, boolean on, boolean frozen, int color, String linetype) {
    }

    public record TextRow(String kind, String layer, String content, Double x, Double y, String layout) {
    }

    /** 含尺寸类型、界线点、箭头方向；旧 JSON 缺字段时为空。 */
    public record DimRow(
            String kind,
            String layer,
            String text,
            Double measurement,
            Double x,
            Double y,
            String layout,
            String dimType,
            Double textX,
            Double textY,
            Double p1x,
            Double p1y,
            Double p2x,
            Double p2y,
            Double arrowX,
            Double arrowY) {

        public DimRow(String kind, String layer, String text, Double measurement, Double x, Double y, String layout) {
            this(kind, layer, text, measurement, x, y, layout, "", null, null, null, null, null, null, null, null);
        }
    }

    public record LeaderRow(
            String kind,
            String layer,
            String content,
            Double x,
            Double y,
            String layout,
            List<List<Double>> vertices) {
    }

    public record TypeCount(String type, int count) {
    }

    public static DxfDetailView empty() {
        return new DxfDetailView(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                false,
                "",
                "");
    }

    public boolean present() {
        return source != null && !source.isBlank()
                && (!layers.isEmpty() || !texts.isEmpty() || !dimensions.isEmpty()
                || (leaders != null && !leaders.isEmpty()) || !typeCounts.isEmpty());
    }
}
