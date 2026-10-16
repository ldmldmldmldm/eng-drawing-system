package org.nineai.engdrawing;

import java.util.List;

/** 三维读取结果。图号用上传文件名。缺的栏写未标注，不编造尺寸。 */
public record StepScanView(
        String fileName,
        String drawingNo,
        String headerFileName,
        String headerPartNo,
        String originatingSystem,
        String timestamp,
        String author,
        String organization,
        String preprocessorVersion,
        String authorization,
        String fileDescription,
        String implementationLevel,
        String schema,
        String protocol,
        String lengthUnit,
        List<String> productNames,
        boolean productListTruncated,
        List<String> assemblyNames,
        List<String> revisions,
        List<String> layers,
        int solidCount,
        /** STEP MANIFOLD_SOLID_BREP 实体号，如 #73；按出现顺序。 */
        List<String> solidEntityIds,
        int assemblyUseCount,
        boolean assembly,
        boolean hasSolid,
        boolean canExplode,
        List<String> colors,
        List<String> properties,
        List<String> measures,
        String pmi,
        String envelope,
        List<double[]> edges,
        boolean edgesTruncated,
        String message) {
}
