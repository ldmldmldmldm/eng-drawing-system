package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 工程图生成：保存三维层次资产；合成时用层次 DXF 覆盖转换图爆炸槽并整页放大。
 */
public final class EngDrawingSupport {

    private static final Pattern ID = Pattern.compile("^[0-9a-fA-F\\-]{36}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EngDrawingSupport() {
    }

    /** 持久化层次 DXF + 元数据到 storage/stack。 */
    public static EngDrawingModels.StackAssetSummary saveStack(
            Path storageRoot,
            EngDrawingModels.StackSaveBody body,
            String pythonCommand,
            Long ownerUserId) throws IOException {
        if (body == null) {
            throw new IllegalArgumentException("层次数据为空");
        }
        List<Map<String, Object>> plates = body.plates() == null ? List.of() : body.plates();
        List<Map<String, Object>> callouts = body.callouts() == null ? List.of() : body.callouts();
        if (plates.isEmpty()) {
            throw new IllegalArgumentException("请先标注并生成层次图（板件为空）");
        }
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("plates", plates);
        job.put("callouts", callouts);
        job.put("drawingNo", blankTo(body.drawingNo(), ""));
        String jobJson = MAPPER.writeValueAsString(job);
        String drawingNo = blankTo(body.drawingNo(), "stack-explode");
        StackExplodeSupport.Export export = StackExplodeSupport.export(jobJson, drawingNo, pythonCommand);

        Path root = stackRoot(storageRoot);
        Files.createDirectories(root);
        String id = UUID.randomUUID().toString();
        byte[] dxf = Base64.getDecoder().decode(export.dxfBase64());
        Files.write(root.resolve(id + ".dxf"), dxf);
        // 优先按刚写出的 DXF 重渲（与 job/旁注一致；③回写不传前端 canvas 也能有预览）
        writeStackPreviewPng(root.resolve(id + ".png"), dxf, body.previewPngBase64(), pythonCommand);
        String title = blankTo(body.title(), drawingNo + "-层次图");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("drawingNo", drawingNo);
        meta.put("title", title);
        meta.put("savedAt", System.currentTimeMillis());
        meta.put("plateCount", plates.size());
        meta.put("calloutCount", callouts.size());
        meta.put("fileName", export.fileName());
        meta.put("job", job);
        meta.put("message", export.message());
        meta.put("warnings", export.warnings() == null ? List.of() : export.warnings());
        if (ownerUserId != null) {
            meta.put("ownerUserId", ownerUserId);
        }
        MAPPER.writeValue(root.resolve(id + ".json").toFile(), meta);
        return new EngDrawingModels.StackAssetSummary(
                id, drawingNo, title, (Long) meta.get("savedAt"), plates.size(), callouts.size());
    }

    /** @deprecated 兼容旧调用；请传 ownerUserId */
    public static EngDrawingModels.StackAssetSummary saveStack(
            Path storageRoot,
            EngDrawingModels.StackSaveBody body,
            String pythonCommand) throws IOException {
        return saveStack(storageRoot, body, pythonCommand, null);
    }

    public static List<EngDrawingModels.StackAssetSummary> listStacks(Path storageRoot) throws IOException {
        return listStacks(storageRoot, null, true, Set.of());
    }

    /**
     * 列出层次资产。非管理员：仅本人 ownerUserId，或无 owner 但已绑定到可访问任务的旧资产。
     */
    public static List<EngDrawingModels.StackAssetSummary> listStacks(
            Path storageRoot,
            Long currentUserId,
            boolean admin,
            Set<String> boundStackIds) throws IOException {
        Path root = stackRoot(storageRoot);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Set<String> bound = boundStackIds == null ? Set.of() : boundStackIds;
        List<EngDrawingModels.StackAssetSummary> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            for (Path file : files) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = MAPPER.readValue(file.toFile(), Map.class);
                if (!admin && !stackMetaVisibleTo(meta, currentUserId, bound)) {
                    continue;
                }
                out.add(toSummary(meta));
            }
        }
        out.sort(Comparator.comparingLong(EngDrawingModels.StackAssetSummary::savedAt).reversed());
        return out;
    }

    /** 从 meta 读 ownerUserId；无则 null */
    public static Long readOwnerUserId(Path storageRoot, String id) throws IOException {
        Map<String, Object> meta = readMeta(storageRoot, id);
        return metaOwnerUserId(meta);
    }

    private static Long metaOwnerUserId(Map<String, Object> meta) {
        Object raw = meta.get("ownerUserId");
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw != null) {
            try {
                return Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean stackMetaVisibleTo(
            Map<String, Object> meta, Long currentUserId, Set<String> bound) {
        if (currentUserId == null) {
            return false;
        }
        Long owner = metaOwnerUserId(meta);
        if (owner != null) {
            return currentUserId.equals(owner);
        }
        String id = String.valueOf(meta.getOrDefault("id", ""));
        return bound.contains(id);
    }

    public static EngDrawingModels.StackAsset loadStack(Path storageRoot, String id) throws IOException {
        Map<String, Object> meta = readMeta(storageRoot, id);
        @SuppressWarnings("unchecked")
        Map<String, Object> job = meta.get("job") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        return new EngDrawingModels.StackAsset(
                String.valueOf(meta.get("id")),
                String.valueOf(meta.getOrDefault("drawingNo", "")),
                String.valueOf(meta.getOrDefault("title", "")),
                ((Number) meta.getOrDefault("savedAt", 0L)).longValue(),
                ((Number) meta.getOrDefault("plateCount", 0)).intValue(),
                ((Number) meta.getOrDefault("calloutCount", 0)).intValue(),
                String.valueOf(meta.getOrDefault("fileName", "")),
                job);
    }

    public static byte[] loadStackDxf(Path storageRoot, String id) throws IOException {
        Path file = stackRoot(storageRoot).resolve(requireId(id) + ".dxf");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("没有这份层次 DXF");
        }
        return Files.readAllBytes(file);
    }

    /** 层次保存时写入的预览 PNG；不存在返回 null（可选产物）。 */
    public static byte[] loadStackPreviewPng(Path storageRoot, String id) throws IOException {
        Path file = stackRoot(storageRoot).resolve(requireId(id) + ".png");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    /**
     * 先 3D 层次已保存 → 客户 DWG 转换 → 覆盖爆炸槽并整页放大。
     */
    public static EngDrawingModels.ComposeResult compose(
            Path storageRoot,
            MultipartFile dwg,
            String stackAssetId,
            DrawingConvertView.ApplyBody annot,
            String odaConverter,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek) throws IOException {
        return compose(storageRoot, dwg, stackAssetId, annot, odaConverter, pythonCommand, ocr, deepseek, null);
    }

    /**
     * @param sourceDxfBytes ① 已落盘源 DXF；非空则跳过二次 ODA（有审校时再跳 OCR）
     */
    public static EngDrawingModels.ComposeResult compose(
            Path storageRoot,
            MultipartFile dwg,
            String stackAssetId,
            DrawingConvertView.ApplyBody annot,
            String odaConverter,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek,
            byte[] sourceDxfBytes) throws IOException {
        boolean noDwg = dwg == null || dwg.isEmpty();
        boolean skipStack = stackAssetId == null || stackAssetId.isBlank();
        if (noDwg && skipStack) {
            throw new IllegalArgumentException("请上传客户 DWG 或完成三维层次（二维与三维至少保留一个）");
        }
        return compose(
                storageRoot,
                noDwg ? new byte[0] : dwg.getBytes(),
                noDwg
                        ? "stack-only.dwg"
                        : (dwg.getOriginalFilename() == null ? "customer.dwg" : dwg.getOriginalFilename()),
                stackAssetId,
                annot,
                odaConverter,
                pythonCommand,
                ocr,
                deepseek,
                noDwg ? null : sourceDxfBytes);
    }

    /**
     * @param sourceDxfBytes ① 已落盘源 DXF；非空则跳过二次 ODA（有审校时再跳 OCR）
     */
    public static EngDrawingModels.ComposeResult compose(
            Path storageRoot,
            byte[] dwgBytes,
            String dwgFileName,
            String stackAssetId,
            DrawingConvertView.ApplyBody annot,
            String odaConverter,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek,
            byte[] sourceDxfBytes) throws IOException {
        boolean noDwg = dwgBytes == null || dwgBytes.length == 0;
        boolean skipStack = stackAssetId == null || stackAssetId.isBlank();
        if (noDwg && skipStack) {
            throw new IllegalArgumentException("请上传客户 DWG 或完成三维层次（二维与三维至少保留一个）");
        }
        // 合成须带审校 notes/materials（可为空列表），避免跳过④直出图
        if (annot == null || annot.notes() == null || annot.materials() == null) {
            throw new IllegalArgumentException("请先完成材料核对与翻译审校后再合成工程图");
        }
        byte[] stackDxf = skipStack ? new byte[0] : loadStackDxf(storageRoot, stackAssetId);
        EngDrawingModels.StackAsset asset = skipStack ? null : loadStack(storageRoot, stackAssetId);
        DrawingConvertView.Apply apply = DrawingConvertSupport.apply(
                noDwg ? new byte[0] : dwgBytes,
                noDwg
                        ? (asset != null && asset.drawingNo() != null && !asset.drawingNo().isBlank()
                        ? asset.drawingNo() + ".dwg"
                        : "stack-only.dwg")
                        : (dwgFileName == null || dwgFileName.isBlank() ? "customer.dwg" : dwgFileName),
                annot,
                odaConverter,
                pythonCommand,
                ocr,
                deepseek,
                stackDxf,
                noDwg ? null : sourceDxfBytes);
        List<String> warnings = new ArrayList<>(apply.warnings() == null ? List.of() : apply.warnings());
        String drawingNo;
        String message;
        if (skipStack) {
            warnings.add(0, "未使用三维层次（已跳过），工程图不含爆炸层次");
            drawingNo = blankTo(apply.drawingNo(), "");
            message = apply.message() != null && !apply.message().isBlank()
                    ? apply.message()
                    : "工程图已生成（未嵌入三维层次）";
        } else if (noDwg) {
            EngDrawingModels.StackAsset stack = Objects.requireNonNull(asset, "层次资产缺失");
            boolean stackOk = warnings.stream().anyMatch(w -> w != null && w.contains("已用三维层次覆盖爆炸槽"));
            boolean stackFail = warnings.stream().anyMatch(w -> w != null && (
                    w.contains("层次 DXF 无法打开")
                            || w.contains("层次 DXF 无图元")
                            || w.contains("层次 DXF 量界失败")
                            || w.contains("层次 DXF 导入失败")
                            || w.contains("没有放进")
                            || w.contains("未能嵌入")));
            if (stackOk && !stackFail) {
                warnings.add(0, "已用层次「" + stack.title() + "」放入工程图预留区（无客户二维）");
            } else {
                warnings.add(0, "层次「" + stack.title() + "」未能放入工程图预留区，请回②检查后重试");
            }
            drawingNo = MaterialLineSupport.preferDrawingNo(apply.drawingNo(), stack.drawingNo());
            message = stackOk && !stackFail
                    ? "工程图已生成：仅三维层次（无客户二维 content）"
                    : (apply.message() != null && !apply.message().isBlank()
                    ? apply.message()
                    : "工程图已生成，但三维层次图没有放进预留区");
        } else {
            EngDrawingModels.StackAsset stack = Objects.requireNonNull(asset, "层次资产缺失");
            boolean stackOk = warnings.stream().anyMatch(w -> w != null && w.contains("已用三维层次覆盖爆炸槽"));
            boolean stackFail = warnings.stream().anyMatch(w -> w != null && (
                    w.contains("层次 DXF 无法打开")
                            || w.contains("层次 DXF 无图元")
                            || w.contains("层次 DXF 量界失败")
                            || w.contains("层次 DXF 导入失败")
                            || w.contains("没有放进")
                            || w.contains("未能嵌入")));
            if (stackOk && !stackFail) {
                warnings.add(0, "已用层次「" + stack.title() + "」放入工程图预留区");
            } else {
                warnings.add(0, "层次「" + stack.title() + "」未能放入工程图预留区，请回②检查后重试");
            }
            drawingNo = MaterialLineSupport.preferDrawingNo(apply.drawingNo(), stack.drawingNo());
            message = stackOk && !stackFail
                    ? "工程图已生成：三维层次已放入，纸幅已按需要放大"
                    : (apply.message() != null && !apply.message().isBlank()
                    ? apply.message()
                    : "工程图已生成，但三维层次图没有放进预留区");
        }
        String dxfName = blankTo(apply.dxfFileName(), engineeringDxfFileName(apply.drawingNo()))
                .replace("-转换-", "-工程图-");
        if (!drawingNo.equals(blankTo(apply.drawingNo(), ""))) {
            dxfName = engineeringDxfFileName(drawingNo);
        }
        String dxfB64 = apply.dxfBase64() == null ? "" : apply.dxfBase64();
        return new EngDrawingModels.ComposeResult(
                apply.fileName(),
                drawingNo,
                skipStack ? "" : stackAssetId,
                message,
                warnings,
                apply.previewPngBase64(),
                apply.previewSvg(),
                apply.zipBase64(),
                dxfName,
                dxfB64,
                apply.explosionSlot());
    }

    private static EngDrawingModels.StackAssetSummary toSummary(Map<String, Object> meta) {
        return new EngDrawingModels.StackAssetSummary(
                String.valueOf(meta.get("id")),
                String.valueOf(meta.getOrDefault("drawingNo", "")),
                String.valueOf(meta.getOrDefault("title", "")),
                ((Number) meta.getOrDefault("savedAt", 0L)).longValue(),
                ((Number) meta.getOrDefault("plateCount", 0)).intValue(),
                ((Number) meta.getOrDefault("calloutCount", 0)).intValue());
    }

    private static Map<String, Object> readMeta(Path storageRoot, String id) throws IOException {
        Path file = stackRoot(storageRoot).resolve(requireId(id) + ".json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("没有这份层次资产，请先保存层次图");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = MAPPER.readValue(file.toFile(), Map.class);
        return meta;
    }

    /**
     * 落盘层次预览：先 DXF 重渲，失败再退前端 canvas PNG。
     * 渲染失败且无前端图时不写文件（预览可选，不阻断保存）。
     */
    private static void writeStackPreviewPng(
            Path pngPath, byte[] dxfBytes, String clientPreviewPngBase64, String pythonCommand) {
        String rendered = StackExplodeSupport.renderPreviewFromDxf(dxfBytes, pythonCommand);
        if (rendered != null && !rendered.isBlank()) {
            try {
                Files.write(pngPath, Base64.getDecoder().decode(rendered));
                return;
            } catch (IllegalArgumentException | IOException ignored) {
                // 退前端图
            }
        }
        if (clientPreviewPngBase64 == null || clientPreviewPngBase64.isBlank()) {
            return;
        }
        try {
            String b64 = clientPreviewPngBase64;
            int comma = b64.indexOf(',');
            if (b64.startsWith("data:") && comma > 0) {
                b64 = b64.substring(comma + 1);
            }
            Files.write(pngPath, Base64.getDecoder().decode(b64));
        } catch (IllegalArgumentException | IOException ignored) {
            // 预览可选
        }
    }

    private static Path stackRoot(Path storageRoot) {
        Path root = storageRoot == null
                ? Path.of("data/eng-drawing").toAbsolutePath().normalize()
                : storageRoot.toAbsolutePath().normalize();
        return root.resolve("stack");
    }

    /** 删除层次资产文件（.json/.dxf/.png）；编号非法则忽略。 */
    public static void deleteStackAsset(Path storageRoot, String stackAssetId) {
        if (stackAssetId == null || stackAssetId.isBlank()) {
            return;
        }
        String id;
        try {
            id = requireId(stackAssetId.trim());
        } catch (IllegalArgumentException ex) {
            return;
        }
        Path root = stackRoot(storageRoot);
        for (String suffix : List.of(".json", ".dxf", ".png")) {
            try {
                Files.deleteIfExists(root.resolve(id + suffix));
            } catch (IOException ignored) {
                // 尽力删，不阻断任务删除
            }
        }
    }

    private static String requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("层次资产编号无效");
        }
        return id;
    }

    /** 下载用工程图文件名：图号-工程图-yyyyMMdd-HHmmss.dxf */
    public static String engineeringDxfFileName(String drawingNo) {
        return engineeringDxfFileName(drawingNo, null);
    }

    public static String engineeringDxfFileName(String drawingNo, java.time.LocalDateTime at) {
        String no = sanitizeFileToken(drawingNo);
        if (no.isBlank()) {
            no = "eng";
        }
        java.time.LocalDateTime t = at != null ? at : java.time.LocalDateTime.now();
        String stamp = t.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return no + "-工程图-" + stamp + ".dxf";
    }

    /** 转换图（无层次合成）下载名 */
    public static String convertDxfFileName(String drawingNo) {
        String no = sanitizeFileToken(drawingNo);
        if (no.isBlank()) {
            no = "eng";
        }
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return no + "-转换-" + stamp + ".dxf";
    }

    public static String sanitizeFileToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return s.replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
