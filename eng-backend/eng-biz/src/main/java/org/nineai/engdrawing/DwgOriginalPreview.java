package org.nineai.engdrawing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用图纸转换程序把 DWG 画成原图。没有转换程序时返回空，由上层改画线段。
 * 预览图认字走 PaddleOCR（与原读图共用 8868），不再用本机 Swift Vision。
 */
public final class DwgOriginalPreview {

    private static final Logger log = LoggerFactory.getLogger(DwgOriginalPreview.class);
    private static final int TIMEOUT_SECONDS = 120;
    private static final String SCRIPT = "/aidrawing/render_dxf.py";
    /** 整图切成重叠块再认，不猜说明在左下还是右上。 */
    private static final int OCR_TILES = 3;
    private static final double OCR_TILE_SPAN = 0.46;
    private static final int OCR_TILE_SCALE = 2;
    /** 分块 OCR HTTP 并行上限，块数仍为 3×3。 */
    private static final int OCR_PARALLEL = 4;
    private static final ExecutorService OCR_TILE_POOL = Executors.newFixedThreadPool(OCR_PARALLEL, r -> {
        Thread t = new Thread(r, "eng-ocr-tile");
        t.setDaemon(true);
        return t;
    });

    private DwgOriginalPreview() {
    }

    public record Image(
            List<String> svgs,
            String dxfText,
            String recognizedText,
            DxfDetailView details,
            String pngBase64,
            String notesPngBase64,
            /** 左侧 NOTES 说明大块裁剪，供按序号读条 */
            String notesLegendPngBase64,
            /** 与预览同构图的高清底图（供①框选裁切 OCR，勿回传前端） */
            byte[] sheetHdPng) {
        /** 整图，给只认单页的旧调用。 */
        public String svg() {
            return svgs == null || svgs.isEmpty() ? null : svgs.get(0);
        }

        public DxfDetailView detailsOrEmpty() {
            return details == null ? DxfDetailView.empty() : details;
        }
    }

    /** 只要 DXF 文本 + ezdxf 明细，不画 SVG（转换轻量路径）。 */
    public record DxfBundle(String dxfText, DxfDetailView details) {
        public DxfDetailView detailsOrEmpty() {
            return details == null ? DxfDetailView.empty() : details;
        }
    }

    /** 转换或绘图失败时返回 null，不抛给页面。 */
    public static Image render(byte[] dwgBytes, String odaCommand, String pythonCommand) {
        return render(dwgBytes, odaCommand, pythonCommand, null, null);
    }

    /** 转换或绘图失败时返回 null，不抛给页面。ocr 空则跳过认字。 */
    public static Image render(
            byte[] dwgBytes,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr) {
        return render(dwgBytes, odaCommand, pythonCommand, ocr, null);
    }

    /**
     * @param stageHint 子阶段文案（ODA/渲图/OCR），供上层过程日志；可 null
     */
    public static Image render(
            byte[] dwgBytes,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            java.util.function.Consumer<String> stageHint) {
        String oda = resolveOda(odaCommand);
        if (oda == null || dwgBytes == null || dwgBytes.length == 0) {
            return null;
        }
        Path dir = null;
        long tAll = System.currentTimeMillis();
        try {
            dir = Files.createTempDirectory("ai-dwg-oda-");
            hint(stageHint, "正在转换图纸格式…");
            long t0 = System.currentTimeMillis();
            Path dxf = odaToDxf(dir, dwgBytes, oda);
            long odaMs = System.currentTimeMillis() - t0;
            if (dxf == null) {
                log.warn("[任务] 抽取渲图 ODA 失败 ms={}", odaMs);
                return null;
            }
            log.info("[任务] 抽取 ODA 完成 ms={}", odaMs);
            hint(stageHint, "格式转换完成，正在生成预览图…");
            Path pages = dir.resolve("pages");
            Files.createDirectory(pages);
            Path png = dir.resolve("preview.png");
            Path script = dir.resolve("render_dxf.py");
            if (!copyResource(SCRIPT, script)) {
                return null;
            }
            String python = pythonCommand == null || pythonCommand.isBlank() ? "python3" : pythonCommand;
            // 预览/OCR 统一高清 DPI；整图再分块认，避免密行糊掉
            boolean needOcr = ocr != null && ocr.enabled();
            String dpi = "300";
            t0 = System.currentTimeMillis();
            if (!run(python, TIMEOUT_SECONDS, script.toString(), dxf.toString(), pages.toString(), png.toString(), dpi, "1")) {
                log.warn("[任务] 抽取渲图 render_dxf 失败 odaMs={} renderMs={}", odaMs, System.currentTimeMillis() - t0);
                return null;
            }
            long renderMs = System.currentTimeMillis() - t0;
            log.info("[任务] 抽取渲图 render_dxf 完成 ms={}", renderMs);
            List<String> svgs = readSvgs(pages);
            if (svgs.isEmpty()) {
                return null;
            }
            // OCR 分块与 DXF 明细互不依赖，并行（块数/DPI 不变）
            hint(stageHint, needOcr ? "正在识别图上文字…" : "正在读取图纸结构…");
            t0 = System.currentTimeMillis();
            final DxfDetailView[] detailHold = new DxfDetailView[] {DxfDetailView.empty()};
            Thread detailThread = new Thread(
                    () -> detailHold[0] = DxfDetailSupport.fromFile(dxf, python), "eng-dxf-detail");
            detailThread.setDaemon(true);
            detailThread.start();
            String recognized = needOcr ? recognizeWholeSheet(png, dir, ocr) : "";
            try {
                detailThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            long ocrMs = System.currentTimeMillis() - t0;
            long detailMs = ocrMs;
            DxfDetailView details = detailHold[0] == null ? DxfDetailView.empty() : detailHold[0];
            // OCR 用完整 PNG；回传页面只留压缩预览，避免对比接口 OOM / Network Error
            byte[] sheetHd = encodeSheetHdPng(png);
            String pngB64 = encodePreviewPng(png);
            String notesB64 = notesCropBase64(png, dir, recognized);
            String legendB64 = notesLegendCropBase64(png, dir, recognized);
            // 原图 SVG 过大时只留第一页且截断，对比页优先用 PNG
            List<String> lightSvgs = lightSvgs(svgs);
            log.info(
                    "[任务] 抽取渲图分段 odaMs={} renderMs={} ocrMs={} detailMs={} totalMs={} needOcr={} ocrChars={} hdBytes={}",
                    odaMs,
                    renderMs,
                    ocrMs,
                    detailMs,
                    System.currentTimeMillis() - tAll,
                    needOcr,
                    recognized == null ? 0 : recognized.length(),
                    sheetHd == null ? 0 : sheetHd.length);
            hint(stageHint, "图纸预览与文字识别完成");
            return new Image(
                    lightSvgs,
                    Files.readString(dxf, StandardCharsets.ISO_8859_1),
                    recognized,
                    details,
                    pngB64,
                    notesB64,
                    legendB64,
                    sheetHd);
        } catch (IOException ex) {
            return null;
        } finally {
            deleteDir(dir);
        }
    }

    private static void hint(java.util.function.Consumer<String> stageHint, String message) {
        if (stageHint == null || message == null || message.isBlank()) {
            return;
        }
        try {
            stageHint.accept(message);
        } catch (Exception ignored) {
            // 进度钩子失败不影响抽取
        }
    }

    /** 只转 DXF 并抽明细；失败返回 null。 */
    public static DxfBundle toDxf(byte[] dwgBytes, String odaCommand, String pythonCommand) {
        String oda = resolveOda(odaCommand);
        if (oda == null || dwgBytes == null || dwgBytes.length == 0) {
            return null;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ai-dwg-dxf-");
            Path dxf = odaToDxf(dir, dwgBytes, oda);
            if (dxf == null) {
                return null;
            }
            String python = pythonCommand == null || pythonCommand.isBlank() ? "python3" : pythonCommand;
            DxfDetailView details = DxfDetailSupport.fromFile(dxf, python);
            return new DxfBundle(Files.readString(dxf, StandardCharsets.ISO_8859_1), details);
        } catch (IOException ex) {
            return null;
        } finally {
            deleteDir(dir);
        }
    }

    private static Path odaToDxf(Path dir, byte[] dwgBytes, String oda) throws IOException {
        Path inDir = Files.createDirectory(dir.resolve("in"));
        Path outDir = Files.createDirectory(dir.resolve("out"));
        Files.write(inDir.resolve("in.dwg"), dwgBytes);
        if (!run(oda, TIMEOUT_SECONDS, inDir.toString(), outDir.toString(), "ACAD2018", "DXF", "0", "1")) {
            return null;
        }
        Path dxf = outDir.resolve("in.dxf");
        if (!Files.isRegularFile(dxf) || Files.size(dxf) == 0) {
            return null;
        }
        return dxf;
    }

    /** 按文件名排序读取分页矢量，最多 16 页。 */
    private static List<String> readSvgs(Path dir) throws IOException {
        List<String> pages = new ArrayList<>();
        try (Stream<Path> walk = Files.list(dir)) {
            List<Path> files = walk
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("page-") && name.endsWith(".svg");
                    })
                    .sorted()
                    .toList();
            for (Path file : files) {
                if (!Files.isRegularFile(file) || Files.size(file) < 32) {
                    continue;
                }
                String markup = Files.readString(file, StandardCharsets.UTF_8);
                if (markup.contains("<svg")) {
                    pages.add(markup);
                }
                if (pages.size() >= 16) {
                    break;
                }
            }
        }
        return pages;
    }

    static String resolveOda(String configured) {
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        // Docker 约定：compose 把 Linux ODA 挂到 /opt/oda
        Path containerOda = Path.of("/opt/oda/ODAFileConverter");
        if (Files.isExecutable(containerOda)) {
            return containerOda.toString();
        }
        Path dir = Path.of(System.getProperty("user.dir", "."));
        for (int hop = 0; hop < 4 && dir != null; hop++) {
            Path bundled = dir.resolve("data/eng-drawing/tools/ODAFileConverter.app/Contents/MacOS/ODAFileConverter");
            if (Files.isExecutable(bundled)) {
                return bundled.toString();
            }
            dir = dir.getParent();
        }
        Path installed = Path.of("/Applications/ODAFileConverter.app/Contents/MacOS/ODAFileConverter");
        if (Files.isExecutable(installed)) {
            return installed.toString();
        }
        return null;
    }

    /** 对线段预览 PNG 认字（无 ODA 时补文字）。失败返回空。 */
    public static String recognizePng(byte[] pngBytes) {
        return recognizePng(pngBytes, null);
    }

    /** 对线段预览 PNG 认字。ocr 空或失败返回空。 */
    public static String recognizePng(byte[] pngBytes, AiDrawingPaddleOcr.Config ocr) {
        if (pngBytes == null || pngBytes.length < 32) {
            return "";
        }
        return AiDrawingPaddleOcr.recognizeToTsv(pngBytes, ocr);
    }

    private static String recognizePngFile(Path png, AiDrawingPaddleOcr.Config ocr) {
        if (png == null || !Files.isRegularFile(png)) {
            return "";
        }
        try {
            if (Files.size(png) < 32) {
                return "";
            }
            return AiDrawingPaddleOcr.recognizeToTsv(Files.readAllBytes(png), ocr);
        } catch (IOException ex) {
            return "";
        }
    }

    /**
     * 整页切成重叠块、放大后认字，坐标换回整页再去重。
     * 块全部失败时退回整页认一次。分块 HTTP 并行，块数仍为 3×3。
     */
    private static String recognizeWholeSheet(Path png, Path dir, AiDrawingPaddleOcr.Config ocr) {
        javax.imageio.ImageIO.setUseCache(false);
        java.awt.image.BufferedImage src;
        try {
            src = javax.imageio.ImageIO.read(png.toFile());
        } catch (IOException ex) {
            return recognizePngFile(png, ocr);
        }
        if (src == null || src.getWidth() < 80 || src.getHeight() < 80) {
            return recognizePngFile(png, ocr);
        }
        // 分块 OCR 前先拉对比度，减轻深底灰字漏识
        try {
            src = boostCadPreviewContrast(src);
        } catch (Throwable ignored) {
            // 增强失败仍用原图
        }
        record TileSpec(int index, Path tile, double x, double y) {
        }
        List<TileSpec> tiles = new ArrayList<>();
        int index = 0;
        double step = OCR_TILES <= 1 ? 0 : (1.0 - OCR_TILE_SPAN) / (OCR_TILES - 1);
        for (int row = 0; row < OCR_TILES; row++) {
            for (int col = 0; col < OCR_TILES; col++) {
                double x = col * step;
                double y = row * step;
                Path tile = dir.resolve("ocr-tile-" + index + ".png");
                if (cropFromImage(src, tile, x, y, OCR_TILE_SPAN, OCR_TILE_SPAN, OCR_TILE_SCALE)) {
                    tiles.add(new TileSpec(index, tile, x, y));
                }
                index++;
            }
        }
        String[] pages = new String[tiles.size()];
        long[] tileMs = new long[tiles.size()];
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++) {
            final int slot = i;
            TileSpec spec = tiles.get(i);
            futures.add(OCR_TILE_POOL.submit(() -> {
                long t0 = System.currentTimeMillis();
                pages[slot] = remapTsv(
                        recognizePngFile(spec.tile(), ocr), spec.x(), spec.y(), OCR_TILE_SPAN, OCR_TILE_SPAN);
                tileMs[slot] = System.currentTimeMillis() - t0;
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get(Math.max(30, TIMEOUT_SECONDS), TimeUnit.SECONDS);
            } catch (Exception ex) {
                // 单块失败保持空串，与串行路径一致
            }
        }
        StringBuilder all = new StringBuilder();
        int okTiles = 0;
        long slowestMs = 0;
        for (int i = 0; i < tiles.size(); i++) {
            slowestMs = Math.max(slowestMs, tileMs[i]);
            if (tileMs[i] >= 15_000) {
                log.warn("[任务] 抽取 OCR 单块偏慢 tile={} ms={}", tiles.get(i).index(), tileMs[i]);
            }
            if (pages[i] == null || pages[i].isBlank()) {
                continue;
            }
            okTiles++;
            if (all.length() > 0) {
                all.append('\n');
            }
            all.append(pages[i]);
        }
        String merged = dedupeTsv(all.toString());
        if (!merged.isBlank()) {
            log.info("[任务] 抽取 OCR 分块完成 tilesOk={}/{} slowestMs={} parallel={}",
                    okTiles, OCR_TILES * OCR_TILES, slowestMs, OCR_PARALLEL);
            return merged;
        }
        long t0 = System.currentTimeMillis();
        String fallback = recognizePngFile(png, ocr);
        log.info("[任务] 抽取 OCR 整页回退 ms={} chars={}", System.currentTimeMillis() - t0,
                fallback == null ? 0 : fallback.length());
        return fallback;
    }

    /** 把块内归一化坐标换回整页。 */
    static String remapTsv(String tsv, double xRatio, double yRatio, double wRatio, double hRatio) {
        if (tsv == null || tsv.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (SheetFactSupport.Line line : SheetFactSupport.parse(tsv)) {
            double x = clamp01(xRatio + line.x() * wRatio);
            double y = clamp01(yRatio + line.y() * hRatio);
            double w = clamp01(line.w() * wRatio);
            double h = clamp01(line.h() * hRatio);
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(String.format(java.util.Locale.US, "%.3f\t%.4f\t%.4f\t%.4f\t%.4f\t%s",
                    line.confidence(), x, y, w, h, line.text()));
        }
        return out.toString();
    }

    /** 重叠块里同一位置的同一句只留置信度更高的一条。 */
    static String dedupeTsv(String tsv) {
        List<SheetFactSupport.Line> lines = new ArrayList<>(SheetFactSupport.parse(tsv));
        lines.sort(Comparator.comparingDouble(SheetFactSupport.Line::confidence).reversed());
        List<SheetFactSupport.Line> kept = new ArrayList<>();
        for (SheetFactSupport.Line line : lines) {
            boolean same = false;
            for (SheetFactSupport.Line prior : kept) {
                if (!prior.text().equalsIgnoreCase(line.text())) {
                    continue;
                }
                double dx = (prior.x() + prior.w() / 2) - (line.x() + line.w() / 2);
                double dy = (prior.y() + prior.h() / 2) - (line.y() + line.h() / 2);
                if (Math.abs(dx) < 0.035 && Math.abs(dy) < 0.035) {
                    same = true;
                    break;
                }
            }
            if (!same) {
                kept.add(line);
            }
        }
        kept.sort(Comparator.comparingDouble(SheetFactSupport.Line::y).thenComparingDouble(SheetFactSupport.Line::x));
        StringBuilder out = new StringBuilder();
        for (SheetFactSupport.Line line : kept) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(String.format(java.util.Locale.US, "%.3f\t%.4f\t%.4f\t%.4f\t%.4f\t%s",
                    line.confidence(), line.x(), line.y(), line.w(), line.h(), line.text()));
        }
        return out.toString();
    }

    private static double clamp01(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    private static boolean cropRegion(
            Path source,
            Path target,
            double xRatio,
            double yRatio,
            double wRatio,
            double hRatio,
            int scale) {
        if (source == null || target == null || !Files.isRegularFile(source)) {
            return false;
        }
        try {
            javax.imageio.ImageIO.setUseCache(false);
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(source.toFile());
            return cropFromImage(src, target, xRatio, yRatio, wRatio, hRatio, scale);
        } catch (Throwable ex) {
            return false;
        }
    }

    private static boolean cropFromImage(
            java.awt.image.BufferedImage src,
            Path target,
            double xRatio,
            double yRatio,
            double wRatio,
            double hRatio,
            int scale) {
        if (src == null || target == null || src.getWidth() < 80 || src.getHeight() < 80) {
            return false;
        }
        try {
            int x0 = Math.max(0, (int) Math.round(src.getWidth() * xRatio));
            int y0 = Math.max(0, (int) Math.round(src.getHeight() * yRatio));
            int w = Math.max(40, (int) Math.round(src.getWidth() * wRatio));
            int h = Math.max(40, (int) Math.round(src.getHeight() * hRatio));
            if (x0 + w > src.getWidth()) {
                w = src.getWidth() - x0;
            }
            if (y0 + h > src.getHeight()) {
                h = src.getHeight() - y0;
            }
            if (w < 40 || h < 40) {
                return false;
            }
            java.awt.image.BufferedImage crop = src.getSubimage(x0, y0, w, h);
            int factor = scale < 1 ? 1 : Math.min(scale, 3);
            if (factor > 1) {
                int nw = w * factor;
                int nh = h * factor;
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                        nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = scaled.createGraphics();
                try {
                    g.setRenderingHint(
                            java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(crop, 0, 0, nw, nh, null);
                } finally {
                    g.dispose();
                }
                crop = scaled;
            }
            return javax.imageio.ImageIO.write(crop, "png", target.toFile());
        } catch (Throwable ex) {
            return false;
        }
    }

    /**
     * 从完整渲染图裁说明区，按原图像素回传。
     * 只包材料说明及其左侧方框序号，避免整页缩成看不清的字。
     */
    static String notesCropBase64(Path png, Path dir, String tsv) {
        double[] window = notesWindow(tsv);
        if (window == null || dir == null) {
            return "";
        }
        Path target = dir.resolve("notes-crop.png");
        if (!cropRegion(png, target, window[0], window[1], window[2], window[3], 1)) {
            return "";
        }
        try {
            String packed = shrinkPngBytes(Files.readAllBytes(target), 3200, 4_500_000);
            return packed == null ? "" : packed;
        } catch (IOException ex) {
            return "";
        }
    }

    /** 左侧 NOTES 说明大块裁剪（按序号读条用），与材料方框裁剪分离。 */
    static String notesLegendCropBase64(Path png, Path dir, String tsv) {
        double[] window = notesLegendWindow(tsv);
        if (window == null || dir == null) {
            return "";
        }
        Path target = dir.resolve("notes-legend.png");
        if (!cropRegion(png, target, window[0], window[1], window[2], window[3], 2)) {
            return "";
        }
        try {
            String packed = shrinkPngBytes(Files.readAllBytes(target), 3600, 5_500_000);
            return packed == null ? "" : packed;
        } catch (IOException ex) {
            return "";
        }
    }

    /**
     * 左侧 NOTES 标题区包围盒（页坐标 0–1）。
     * 正常客户图说明在左栏；找不到标题时取左上栏兜底。
     */
    static double[] notesLegendWindow(String tsv) {
        List<SheetFactSupport.Line> lines = SheetFactSupport.parse(tsv);
        if (lines.isEmpty()) {
            return new double[] {0.02, 0.04, 0.42, 0.62};
        }
        SheetFactSupport.Line seed = null;
        Pattern header = Pattern.compile("(?i).*\\bNOTES?\\b.*|.*UNLESS\\s+OTHERWISE.*");
        for (SheetFactSupport.Line line : lines) {
            if (line == null || line.text() == null) {
                continue;
            }
            if (header.matcher(line.text().trim()).matches()) {
                if (seed == null || line.x() < seed.x()) {
                    seed = line;
                }
            }
        }
        double top = seed == null ? 0.03 : Math.max(0.01, seed.y() - 0.015);
        double left = seed == null ? 0.02 : Math.max(0.01, seed.x() - 0.03);
        double right = seed == null
                ? 0.46
                : Math.min(0.62, Math.max(seed.x() + Math.max(seed.w(), 0.18) + 0.18, seed.x() + 0.36));
        double bottom = Math.min(0.72, seed == null ? 0.62 : seed.y() + 0.55);
        // 收入左栏内、标题以下的文字以扩大下界
        for (SheetFactSupport.Line line : lines) {
            if (line == null) {
                continue;
            }
            double midX = line.x() + line.w() / 2.0;
            double midY = line.y() + line.h() / 2.0;
            if (midX > right || midY < top || midY > 0.72) {
                continue;
            }
            bottom = Math.max(bottom, Math.min(0.74, line.y() + line.h() + 0.02));
            left = Math.min(left, Math.max(0.01, line.x() - 0.01));
        }
        double w = right - left;
        double h = bottom - top;
        if (w < 0.15 || h < 0.12) {
            return new double[] {0.02, 0.04, 0.42, 0.62};
        }
        return new double[] {left, top, w, h};
    }

    /** 材料说明文字的包围盒（页坐标 0–1）。没有材料行时返回 null。 */
    static double[] notesWindow(String tsv) {
        List<SheetFactSupport.Line> lines = SheetFactSupport.parse(tsv);
        List<SheetFactSupport.Line> seeds = new ArrayList<>();
        for (SheetFactSupport.Line line : lines) {
            if (isNoteSeed(line.text())) {
                seeds.add(line);
            }
        }
        if (seeds.isEmpty()) {
            return null;
        }
        double minX = 1;
        double minY = 1;
        double maxX = 0;
        double maxY = 0;
        for (SheetFactSupport.Line line : seeds) {
            minX = Math.min(minX, line.x());
            minY = Math.min(minY, line.y());
            maxX = Math.max(maxX, line.x() + line.w());
            maxY = Math.max(maxY, line.y() + line.h());
        }
        double bandTop = minY - 0.03;
        double bandBottom = maxY + 0.03;
        double bandRight = maxX + 0.02;
        for (SheetFactSupport.Line line : lines) {
            double midY = line.y() + line.h() / 2.0;
            if (midY < bandTop || midY > bandBottom || line.x() > bandRight) {
                continue;
            }
            minX = Math.min(minX, line.x());
            minY = Math.min(minY, line.y());
            maxX = Math.max(maxX, line.x() + line.w());
            maxY = Math.max(maxY, line.y() + line.h());
        }
        double pad = 0.02;
        double x = Math.max(0, minX - pad);
        double y = Math.max(0, minY - pad);
        double right = Math.min(1, maxX + pad);
        double bottom = Math.min(1, maxY + pad);
        double w = right - x;
        double h = bottom - y;
        if (w < 0.04 || h < 0.02) {
            return null;
        }
        return new double[] {x, y, w, h};
    }

    private static boolean isNoteSeed(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("MATERIAL")
                || upper.contains("TAPE")
                || upper.contains("LINER")
                || upper.contains("FOAM")
                || upper.contains("ADHESIVE")
                || upper.contains("COLOR")
                || upper.contains("THICK")
                || upper.contains("THK")
                || upper.contains("PSA");
    }

    /** 抽取预览：高清对照 NOTES/尺寸；最长边 6400，体积上限 12MB。 */
    private static final int PREVIEW_MAX_EDGE = 6400;
    private static final int PREVIEW_MAX_BYTES = 12_000_000;
    /** 原始渲图过大时仍尝试缩边回传，避免直接丢预览。 */
    private static final int PREVIEW_RAW_MAX_BYTES = 48_000_000;
    /** 框选 OCR 底图：同构图更高体积上限（不回传浏览器）。 */
    private static final int SHEET_HD_MAX_BYTES = 40_000_000;

    /**
     * 与预览同构图的高清底图字节（对比度增强后，边长上限同预览，体积可更大）。
     * 失败返回 null。
     */
    private static byte[] encodeSheetHdPng(Path png) {
        if (png == null || !Files.isRegularFile(png)) {
            return null;
        }
        try {
            byte[] raw = Files.readAllBytes(png);
            if (raw.length < 32) {
                return null;
            }
            byte[] boosted = boostCadPreviewPngBytes(raw);
            if (boosted != null && boosted.length >= 32) {
                raw = boosted;
            }
            String packed = shrinkPngBytes(raw, PREVIEW_MAX_EDGE, SHEET_HD_MAX_BYTES, PREVIEW_RAW_MAX_BYTES);
            if (packed == null || packed.isBlank()) {
                return raw.length <= SHEET_HD_MAX_BYTES ? raw : null;
            }
            return Base64.getDecoder().decode(packed);
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * 按归一化框（相对整图 0~1）裁切 PNG；包住选区，不内缩。
     */
    public static byte[] cropNormalizedRegion(byte[] pngBytes, double x, double y, double w, double h) {
        if (pngBytes == null || pngBytes.length < 32) {
            throw new IllegalArgumentException("高清底图不可用，请重新渲图后再截图抽取");
        }
        if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(w) && Double.isFinite(h))) {
            throw new IllegalArgumentException("选区坐标无效");
        }
        double x0 = Math.min(x, x + w);
        double y0 = Math.min(y, y + h);
        double x1 = Math.max(x, x + w);
        double y1 = Math.max(y, y + h);
        x0 = Math.max(0.0, Math.min(1.0, x0));
        y0 = Math.max(0.0, Math.min(1.0, y0));
        x1 = Math.max(0.0, Math.min(1.0, x1));
        y1 = Math.max(0.0, Math.min(1.0, y1));
        if (x1 - x0 < 0.002 || y1 - y0 < 0.002) {
            throw new IllegalArgumentException("选区过小");
        }
        try {
            javax.imageio.ImageIO.setUseCache(false);
            java.awt.image.BufferedImage src =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
            if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
                throw new IllegalArgumentException("高清底图无法解码");
            }
            int iw = src.getWidth();
            int ih = src.getHeight();
            int sx = Math.max(0, (int) Math.floor(x0 * iw));
            int sy = Math.max(0, (int) Math.floor(y0 * ih));
            int sx1 = Math.min(iw, (int) Math.ceil(x1 * iw));
            int sy1 = Math.min(ih, (int) Math.ceil(y1 * ih));
            int cw = Math.max(1, sx1 - sx);
            int ch = Math.max(1, sy1 - sy);
            if (cw < 8 || ch < 8) {
                throw new IllegalArgumentException("选区无效");
            }
            java.awt.image.BufferedImage crop = src.getSubimage(sx, sy, cw, ch);
            // 小选区略放大，便于 OCR（不改几何内容）
            double outScale = Math.min(2.5, Math.max(1.0, 2400.0 / Math.max(cw, ch)));
            int ow = Math.max(1, (int) Math.round(cw * outScale));
            int oh = Math.max(1, (int) Math.round(ch * outScale));
            java.awt.image.BufferedImage outImg =
                    new java.awt.image.BufferedImage(ow, oh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = outImg.createGraphics();
            try {
                g.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(crop, 0, 0, ow, oh, null);
            } finally {
                g.dispose();
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(outImg, "png", bos)) {
                throw new IllegalArgumentException("裁切图编码失败");
            }
            byte[] packed = bos.toByteArray();
            if (packed.length < 32) {
                throw new IllegalArgumentException("裁切图无效");
            }
            return packed;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("高清选区裁切失败：" + ex.getMessage());
        }
    }

    /** 预览 PNG 压到可回传体积；失败返回空串。深底 CAD 顺带拉对比度便于对图/OCR。 */
    private static String encodePreviewPng(Path png) {
        if (png == null || !Files.isRegularFile(png)) {
            return "";
        }
        try {
            byte[] raw = Files.readAllBytes(png);
            if (raw.length < 32) {
                return "";
            }
            byte[] boosted = boostCadPreviewPngBytes(raw);
            if (boosted != null && boosted.length >= 32) {
                raw = boosted;
            }
            // 与终稿预览同档，避免整页缩成不可读
            String packed = shrinkPngBytes(raw, PREVIEW_MAX_EDGE, PREVIEW_MAX_BYTES, PREVIEW_RAW_MAX_BYTES);
            return packed == null ? "" : packed;
        } catch (Throwable ex) {
            return "";
        }
    }

    /**
     * 深底工程图预览：压黑底、提亮灰字/线，几何与文案内容不变。
     * 供人眼核对与截图 OCR；失败返回 null。
     */
    public static byte[] boostCadPreviewPngBytes(byte[] raw) {
        if (raw == null || raw.length < 32) {
            return null;
        }
        try {
            javax.imageio.ImageIO.setUseCache(false);
            java.awt.image.BufferedImage src =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
                return null;
            }
            java.awt.image.BufferedImage boosted = boostCadPreviewContrast(src);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(boosted, "png", out)) {
                return null;
            }
            byte[] packed = out.toByteArray();
            return packed.length < 32 ? null : packed;
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * 直方图近似拉伸 + 轻度提亮：暗底更黑、灰字更近白，不改图元布局。
     */
    static java.awt.image.BufferedImage boostCadPreviewContrast(java.awt.image.BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] hist = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                hist[lumaOf(rgb)]++;
            }
        }
        int total = Math.max(1, w * h);
        int lo = 0;
        int hi = 255;
        int acc = 0;
        int loCut = Math.max(1, (int) (total * 0.015));
        int hiCut = Math.max(1, (int) (total * 0.015));
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= loCut) {
                lo = i;
                break;
            }
        }
        acc = 0;
        for (int i = 255; i >= 0; i--) {
            acc += hist[i];
            if (acc >= hiCut) {
                hi = i;
                break;
            }
        }
        if (hi <= lo + 12) {
            lo = Math.max(0, lo - 4);
            hi = Math.min(255, hi + 24);
        }
        double span = Math.max(12.0, hi - lo);
        // 对比略抬 + 中高亮再抬一点，黑底保持压暗
        double contrast = 1.22;
        double bright = 8.0;
        java.awt.image.BufferedImage dst =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int L = lumaOf(rgb);
                double t = (L - lo) / span;
                if (t < 0) {
                    t = 0;
                } else if (t > 1) {
                    t = 1;
                }
                // gamma < 1：中灰字提亮，仍压住接近黑的底
                t = Math.pow(t, 0.82);
                double targetL = t * 255.0;
                targetL = (targetL - 128.0) * contrast + 128.0 + bright;
                if (targetL < 0) {
                    targetL = 0;
                } else if (targetL > 255) {
                    targetL = 255;
                }
                // 近黑像素继续压成纯黑，减少 OCR 噪声底
                if (L <= lo + 6 && targetL < 36) {
                    dst.setRGB(x, y, 0x000000);
                    continue;
                }
                double scale = L <= 1 ? (targetL / 255.0) : (targetL / (double) L);
                int nr = clampByte(r * scale);
                int ng = clampByte(g * scale);
                int nb = clampByte(b * scale);
                dst.setRGB(x, y, (nr << 16) | (ng << 8) | nb);
            }
        }
        return dst;
    }

    private static int lumaOf(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (r * 30 + g * 59 + b * 11) / 100;
    }

    private static int clampByte(double v) {
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return (int) Math.round(v);
    }

    /** 对比/其它路径共用：压到最长边 1600；失败返回 null。 */
    public static String shrinkPngBytes(byte[] raw) {
        return shrinkPngBytes(raw, 1600, 2_500_000);
    }

    /** 转换图预览用更高边长，避免整张图缩成不可读。 */
    public static String shrinkPngBytes(byte[] raw, int maxEdge, int maxBytes) {
        return shrinkPngBytes(raw, maxEdge, maxBytes, 28_000_000);
    }

    public static String shrinkPngBytes(byte[] raw, int maxEdge, int maxBytes, int rawMaxBytes) {
        if (raw == null || raw.length < 32) {
            return null;
        }
        int edge = maxEdge < 800 ? 800 : maxEdge;
        int limit = maxBytes < 700_000 ? 700_000 : maxBytes;
        int rawLimit = rawMaxBytes < limit ? 28_000_000 : rawMaxBytes;
        try {
            if (raw.length <= 700_000) {
                return Base64.getEncoder().encodeToString(raw);
            }
            if (raw.length > rawLimit) {
                return null;
            }
            javax.imageio.ImageIO.setUseCache(false);
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
                return null;
            }
            double scale = Math.min(1.0, edge / (double) Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(
                    w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.drawImage(src, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(dst, "png", out)) {
                return null;
            }
            byte[] packed = out.toByteArray();
            if (packed.length == 0 || packed.length > limit) {
                return null;
            }
            return Base64.getEncoder().encodeToString(packed);
        } catch (Throwable ex) {
            return null;
        }
    }

    /** base64 PNG 再压一遍；失败返回 null。 */
    public static String shrinkPngBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            return shrinkPngBytes(Base64.getDecoder().decode(base64));
        } catch (Throwable ex) {
            return null;
        }
    }

    private static List<String> lightSvgs(List<String> svgs) {
        if (svgs == null || svgs.isEmpty()) {
            return List.of();
        }
        String first = svgs.get(0);
        if (first == null || first.length() > 600_000) {
            return List.of();
        }
        return List.of(first);
    }

    private static boolean copyResource(String resource, Path target) {
        try (InputStream in = DwgOriginalPreview.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean run(String command, int timeoutSeconds, String... args) {
        String[] all = new String[args.length + 1];
        all[0] = command;
        System.arraycopy(args, 0, all, 1, args.length);
        ProcessBuilder builder = new ProcessBuilder(all);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清不掉不影响预览结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
