package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户 DWG → 中文 NOTE + 材料表 DXF。视图实体原样保留，不折线化、不编造材料。
 */
public final class DrawingConvertSupport {

    private static final Logger log = LoggerFactory.getLogger(DrawingConvertSupport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService STAGE_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "eng-extract-stage");
        t.setDaemon(true);
        return t;
    });
    private static final int TIMEOUT_SECONDS = 120;
    private static final String CONVERT_SCRIPT = "/aidrawing/convert_sheet.py";
    private static final String RENDER_SCRIPT = "/aidrawing/render_dxf.py";
    private static final String ROLE_ALT =
            "RELEASE\\s+LINER|RELEASE\\s+COATING|ADHESIVE|FOAM|LINER|PSA|SHIM|COVER(?:\\s+TAPE)?|CARRIER(?:\\s+TAPE)?|TAPE";
    /** 编号须是 1 起的整数。方框序号在图上常是单独的数字，后面紧跟「角色 MATERIAL」。 */
    private static final Pattern NOTE_HEAD = Pattern.compile(
            "^\\s*(?:\\[([1-9]\\d{0,2})\\]|(?<![\\d.])([1-9]\\d{0,2})\\s*[.)、．](?!\\d)"
                    + "|(?<![\\d.])([1-9]\\d{0,2})(?=\\s+(?i:" + ROLE_ALT + ")\\s+MATERIAL\\b))\\s*(.+)$",
            Pattern.DOTALL);
    /** 方框里只有序号、没有句点。下一行若是材料说明才并进去。 */
    private static final Pattern LONE_NOTE_NO = Pattern.compile(
            "^\\s*(?:\\[\\s*)?([1-9]\\d{0,2})(?:\\s*\\])?\\s*$");
    private static final Pattern ROLE_MATERIAL_LINE = Pattern.compile(
            "(?i)^(?:" + ROLE_ALT + ")\\s+MATERIAL\\b.*",
            Pattern.DOTALL);
    private static final Pattern MOSTLY_NUMBER = Pattern.compile("^[0-9.+\\-\\s]{4,}$");
    private static final Pattern NOTE_BREAK = Pattern.compile("(?<!\\n)(?=\\[\\d{1,3}\\])");
    /** NOTES 区块标题：优先只在该区内抽说明，避免尺寸公差冒充 */
    private static final Pattern NOTES_REGION_HEADER = Pattern.compile(
            "(?i)^\\s*NOTES?\\s*[:：].*$|^\\s*NOTES?\\s*$|.*\\bNOTES?\\b.*UNLESS\\s+OTHERWISE.*|"
                    + "(?i)^\\s*NOTES?\\s*\\(.*\\)\\s*$");
    private static final Pattern NOTES_SEED = Pattern.compile(
            "(?i).*\\bNOTES?\\b.*|.*UNLESS\\s+OTHERWISE.*");
    private static final Pattern NOTES_REGION_STOP = Pattern.compile(
            "(?i)^(DRAWING\\s+NUMBER|TITLE\\s*[:：]|SCALE\\s*[:：]|SIZE\\s*[:：]|SHEET\\s+\\d|"
                    + "REV(?:ISION)?\\.?\\s|APPLE\\b).*"
                    + "|^\\d{1,4}[/\\-]\\d{1,2}[/\\-]\\d{1,4}$");
    /** 标题栏区域（页坐标）：超过则不再并入 NOTES 锚点区 */
    private static final double TITLE_BAND_Y = 0.72;
    /** 整行像尺寸公差（含编号前缀） */
    private static final Pattern DIMENSION_NOTE_LINE = Pattern.compile(
            "(?i)^\\s*(?:\\[\\s*)?(?:[1-9]\\d{0,2})?(?:\\s*\\])?\\s*[.)、．]?\\s*"
                    + "(?:[Ø⌀])?\\s*\\d+(?:\\.\\d+)?\\s*[±÷].*"
                    + "|^\\s*(?:\\[\\s*)?(?:[1-9]\\d{0,2})?(?:\\s*\\])?\\s*[.)、．]\\s*"
                    + "\\d+(?:\\.\\d+)?\\s*(?:mm|毫米)?\\s*$");
    private static final Pattern MATERIAL_BODY = Pattern.compile(
            "(?i)MATERIAL\\s*[:：]\\s*([^\\r\\n]+)");
    private static final Pattern MATERIAL_ROLE = Pattern.compile(
            "(?i)(RELEASE\\s+LINER|RELEASE\\s+COATING|ADHESIVE|SHIM|FOAM|COVER(?:\\s+TAPE)?|CARRIER(?:\\s+TAPE)?|TAPE)\\s+MATERIAL");
    private static final Pattern THICKNESS = Pattern.compile(
            "(?i)THICKNESS\\s*[:：]\\s*([0-9]+(?:\\.[0-9]+)?(?:\\s*-\\s*[0-9]+(?:\\.[0-9]+)?)?(?:\\s*MM)?)");
    /** 认字常把小数点两边拆开：0 . 76、0. 76。 */
    private static final Pattern SPACED_DOT = Pattern.compile("(\\d)\\s*\\.\\s*(\\d+)");
    private static final String COLOR_WORDS =
            "TRANSLUCENT\\s+BLUE|TRANSLUCENT|TRANSPARENT|BLACK|WHITE|CLEAR|BLUE|GREEN|YELLOW|BROWN|ORANGE|GRAY|GREY|RED|PINK";
    /** 颜色词整段拿走。冒号可能被认成点。 */
    private static final Pattern COLOR_PHRASE = Pattern.compile(
            "(?i)\\bCOLOR\\s*[:：∶﹕.．]?\\s*(" + COLOR_WORDS + ")\\b");
    /** 第一条厚度结束，后面的杂字不再算进这条材料。 */
    private static final Pattern THICK_END = Pattern.compile(
            "(?i)\\d+(?:\\.\\d+)?(?:\\s*-\\s*\\d+(?:\\.\\d+)?)?\\s*mm(?:\\s*THICK(?:NESS)?)?");
    private static final Pattern THICK_PHRASE = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?(?:\\s*-\\s*\\d+(?:\\.\\d+)?)?)(\\s*mm)(?:\\s*THICK(?:NESS)?)?");
    /** 方框序号开头的一行。12. 这种圆点编号对不上。 */
    private static final Pattern BOXED_HEAD = Pattern.compile(
            "^\\s*(?:\\[\\s*([1-9]\\d{0,2})\\s*\\]|(?<![\\d.])([1-9]\\d{0,2})(?!\\d))\\s+(.+)$");
    /** 0.050 THICK、0.30mm THICK、0.530mm THK。 */
    private static final Pattern THICK_WORD = Pattern.compile(
            "(?i)(?<![\\d.])(\\d+(?:\\.\\d+)?(?:\\s*-\\s*\\d+(?:\\.\\d+)?)?)\\s*(mm)?\\s*(?:THICK(?:NESS)?|THK)\\b");
    /** 没有 mm、也没有 THICK 的厚度区间，如 0.05 - 0.10。 */
    private static final Pattern BARE_RANGE = Pattern.compile(
            "(?<![\\d.-])(\\d+\\.\\d+\\s*-\\s*\\d+\\.\\d+)(?![\\d.-])");
    private static final Pattern BARE_COLOR = Pattern.compile(
            "(?i)\\b(" + COLOR_WORDS + ")\\b");
    private static final Pattern LABEL_SPLIT = Pattern.compile(
            "^(.+?)\\s*[:：]\\s*(.*)$");
    private static final Pattern SQUARE_MATERIAL = Pattern.compile(
            "(?i)(?:\\[\\s*([1-9]\\d{0,2})\\s*\\]|(?<![\\d.])([1-9]\\d{0,2})(?!\\d))\\s+"
                    + "(" + ROLE_ALT + ")\\s+MATERIAL\\s*[:：]\\s*"
                    + "(.*?)(?=(?:\\[\\s*[1-9]\\d{0,2}\\s*\\]|(?<![\\d.])[1-9]\\d{0,2}(?!\\d))\\s+(?:"
                    + ROLE_ALT + ")\\s+MATERIAL\\b|$)",
            Pattern.DOTALL);
    /** 引线如 [14] PSA 没有 MATERIAL，从材料正文末尾切掉。 */
    private static final Pattern CALLOUT_TAIL = Pattern.compile(
            "(?i)(?:\\r?\\n)\\s*\\[\\s*[1-9]\\d{0,2}\\s*\\]\\s+(?:" + ROLE_ALT + ")\\b");
    /** 正文中粘上的下一条材料行起点（冒号可缺，序号可粘在角色上）。 */
    private static final Pattern EMBEDDED_MATERIAL = Pattern.compile(
            "(?i)(?<!\\d)(?:\\[\\s*)?([1-9]\\d{0,2})(?:\\s*[\\]])?\\s*(" + ROLE_ALT + ")\\s+MATERIAL\\b\\s*[:：]?");
    private static final Pattern MATERIAL_BREAK = Pattern.compile(
            "(?i)(?<!\\n)(?=(?:\\[\\s*[1-9]\\d{0,2}\\s*\\]\\s*|(?<![\\d.])[1-9]\\d{0,2}(?!\\d)\\s*)"
                    + "(?:" + ROLE_ALT + ")\\s+MATERIAL\\b)");
    private static final Pattern SPEC_GARBAGE = Pattern.compile(
            "(?i)\\b(FOLLOWING|MEASURE\\s+TOTAL|ALL\\s+HOMOGENEOUS|RECYCLED|ENVIRONMENTA|MANUFACTUR"
                    + "|(?<!\\d)1[345]\\s*(?:FOAM|ADHESIVE|LINER))\\b");
    private DrawingConvertSupport() {
    }

    public static DrawingConvertView.Preview preview(
            byte[] dwgBytes,
            String fileName,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr) {
        return preview(dwgBytes, fileName, odaCommand, pythonCommand, ocr, DrawingConvertDeepseek.Config.disabled());
    }

    /** 预览结果 + ODA 源 DXF + 高清底图（供框选 OCR；勿塞进 annot.json） */
    public record PreviewResult(
            DrawingConvertView.Preview preview, byte[] sourceDxfBytes, byte[] sheetHdPngBytes) {
        public PreviewResult(DrawingConvertView.Preview preview, byte[] sourceDxfBytes) {
            this(preview, sourceDxfBytes, null);
        }
    }

    /** 抽取子阶段进度（异步 job 过程日志 / 百分比）；同步预览可传 null。 */
    @FunctionalInterface
    public interface ExtractProgress {
        void onStage(String stage, int percent, String logLine);
    }

    /** 方框裁图 + 可选已完成的 readBoxed（与 legend 并行时预计算）。 */
    private record CropBundle(NoteCropRenderer.Images images, DrawingConvertDeepseek.BoxedRead read) {
        static CropBundle empty() {
            return new CropBundle(NoteCropRenderer.Images.empty(), DrawingConvertDeepseek.BoxedRead.missed());
        }
    }

    public static DrawingConvertView.Preview preview(
            byte[] dwgBytes,
            String fileName,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek) {
        return previewResult(dwgBytes, fileName, odaCommand, pythonCommand, ocr, deepseek, null).preview();
    }

    public static PreviewResult previewResult(
            byte[] dwgBytes,
            String fileName,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek) {
        return previewResult(dwgBytes, fileName, odaCommand, pythonCommand, ocr, deepseek, null);
    }

    /**
     * ① 仅解析图纸：ODA + 高清 PNG，不做 OCR/规则/视觉抽取。
     * 供用户先对图，再选截图抽取或自动抽取。
     */
    public static PreviewResult sheetPreviewResult(
            byte[] dwgBytes,
            String fileName,
            String odaCommand,
            String pythonCommand,
            ExtractProgress progress) {
        requireDwg(fileName);
        if (DwgOriginalPreview.resolveOda(odaCommand) == null) {
            throw new IllegalArgumentException(
                    "图纸转换需要 ODA File Converter：请放到 data/eng-drawing/tools/ODAFileConverter.app，"
                            + "或配置 ai-drawing.oda-converter / 环境变量 AI_DRAWING_ODA（Linux 可用 /opt/oda/ODAFileConverter）");
        }
        long tAll = System.currentTimeMillis();
        long t0 = System.currentTimeMillis();
        notifyProgress(progress, "解析图纸…", 18, "开始转换图纸并生成预览（暂不识别文字）");
        // ocr=null：跳过整页认字，只出预览
        DwgOriginalPreview.Image image = DwgOriginalPreview.render(
                dwgBytes,
                odaCommand,
                pythonCommand,
                null,
                hint -> notifyRenderHint(progress, hint));
        long renderMs = System.currentTimeMillis() - t0;
        notifyProgress(progress, "解析图纸…", 70, "图纸预览已生成");
        DxfDetailView details = image == null ? DxfDetailView.empty() : image.detailsOrEmpty();
        String dxfText = image == null || image.dxfText() == null ? "" : image.dxfText();
        if (!details.present() || dxfText.isBlank()) {
            t0 = System.currentTimeMillis();
            notifyProgress(progress, "补全图纸数据…", 78, "图纸信息不完整，正在再次转换");
            DwgOriginalPreview.DxfBundle bundle = DwgOriginalPreview.toDxf(dwgBytes, odaCommand, pythonCommand);
            log.info("[任务] 图纸预览二次 ODA/明细 ms={}", System.currentTimeMillis() - t0);
            if (bundle == null || bundle.dxfText() == null || bundle.dxfText().isBlank()) {
                throw new IllegalArgumentException("无法把图纸转成可编辑格式，请确认转换程序可用后重试");
            }
            if (!details.present()) {
                details = bundle.detailsOrEmpty();
            }
            if (dxfText.isBlank()) {
                dxfText = bundle.dxfText();
            }
        }
        notifyProgress(progress, "整理高清预览…", 90, "预览已就绪，请选择抽取方式");
        String png = image == null ? "" : (image.pngBase64() == null ? "" : image.pngBase64());
        if (png.isBlank()) {
            throw new IllegalArgumentException("未能生成图纸预览图，请确认转换程序可用后重试");
        }
        String svg = image == null || image.svgs() == null || image.svgs().isEmpty()
                ? "" : image.svgs().get(0);
        List<String> svgs = image == null || image.svgs() == null
                ? List.of()
                : List.copyOf(image.svgs());
        java.util.Map<String, Object> sheetLayout = SheetLayoutSupport.analyze(dxfText).toMap();
        DrawingConvertView.Preview preview = new DrawingConvertView.Preview(
                fileName,
                MaterialLineSupport.drawingNo(fileName),
                "高清预览已就绪，请选择「截图抽取」或「自动抽取」",
                List.of(),
                List.of(),
                List.of(),
                png,
                svg,
                svgs,
                List.of(),
                sheetLayout,
                true);
        byte[] sourceDxf = dxfText.isBlank()
                ? new byte[0]
                : dxfText.getBytes(StandardCharsets.ISO_8859_1);
        byte[] sheetHd = image == null ? null : image.sheetHdPng();
        log.info(
                "[任务] 图纸预览完成 file={} renderMs={} totalMs={} pngChars={} hdBytes={}",
                fileName,
                renderMs,
                System.currentTimeMillis() - tAll,
                png.length(),
                sheetHd == null ? 0 : sheetHd.length);
        return new PreviewResult(preview, sourceDxf, sheetHd);
    }

    public static PreviewResult previewResult(
            byte[] dwgBytes,
            String fileName,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek,
            ExtractProgress progress) {
        requireDwg(fileName);
        if (DwgOriginalPreview.resolveOda(odaCommand) == null) {
            throw new IllegalArgumentException(
                    "图纸转换需要 ODA File Converter：请放到 data/eng-drawing/tools/ODAFileConverter.app，"
                            + "或配置 ai-drawing.oda-converter / 环境变量 AI_DRAWING_ODA（Linux 可用 /opt/oda/ODAFileConverter）");
        }
        long tAll = System.currentTimeMillis();
        long t0 = System.currentTimeMillis();
        notifyProgress(progress, "解析图纸…", 18, "开始转换图纸、生成预览并识别文字");
        DwgOriginalPreview.Image image = DwgOriginalPreview.render(
                dwgBytes,
                odaCommand,
                pythonCommand,
                ocr,
                hint -> notifyRenderHint(progress, hint));
        long renderMs = System.currentTimeMillis() - t0;
        notifyProgress(progress, "解析图纸…", 40, "图纸预览与文字识别完成");
        DxfDetailView details = image == null ? DxfDetailView.empty() : image.detailsOrEmpty();
        String dxfText = image == null || image.dxfText() == null ? "" : image.dxfText();
        if (!details.present() || dxfText.isBlank()) {
            t0 = System.currentTimeMillis();
            notifyProgress(progress, "补全图纸数据…", 42, "图纸信息不完整，正在再次转换");
            DwgOriginalPreview.DxfBundle bundle = DwgOriginalPreview.toDxf(dwgBytes, odaCommand, pythonCommand);
            log.info("[任务] 抽取二次 ODA/明细 ms={}", System.currentTimeMillis() - t0);
            if (bundle == null || bundle.dxfText() == null || bundle.dxfText().isBlank()) {
                throw new IllegalArgumentException("无法把图纸转成可编辑格式，请确认转换程序可用后重试");
            }
            if (!details.present()) {
                details = bundle.detailsOrEmpty();
            }
            if (dxfText.isBlank()) {
                dxfText = bundle.dxfText();
            }
        }
        String vectorText = joinTexts(details);
        String ocrText = image == null ? "" : (image.recognizedText() == null ? "" : image.recognizedText());
        t0 = System.currentTimeMillis();
        notifyProgress(progress, "整理说明与材料…", 45, "正在从图纸中整理说明与材料");
        Extracted extracted = extract(vectorText, ocrText, details);
        long extractMs = System.currentTimeMillis() - t0;
        String png = image == null ? "" : (image.pngBase64() == null ? "" : image.pngBase64());
        String notesPng = image == null || image.notesPngBase64() == null ? "" : image.notesPngBase64();
        String legendPng = image == null || image.notesLegendPngBase64() == null
                ? "" : image.notesLegendPngBase64();
        String fallbackPng = notesPng.isBlank() ? png : notesPng;
        final Extracted extracted0 = extracted;
        final String dxfForBox = dxfText;
        final DrawingConvertDeepseek.Config ds = deepseek;
        final String py = pythonCommand;
        // legend 视觉 ∥ 方框裁图+readBoxed；合并改为多源融合（非整表覆盖）；低置信才跑 vision
        notifyProgress(progress, "核对说明区与材料框…", 50, "正在智能核对说明区与材料框（按需）");
        long[] legendHold = {0L};
        long[] boxedHold = {0L};
        final boolean vectorPoor = ExtractMergeSupport.isVectorPoor(vectorText);
        final boolean runLegend = deepseek != null && deepseek.enabled()
                && ExtractMergeSupport.needsLegendVision(extracted0.notes, vectorPoor);
        final boolean runBoxed = deepseek != null && deepseek.enabled()
                && ExtractMergeSupport.needsBoxedVision(extracted0.materials);
        final boolean preferLegendNotes = vectorPoor || ExtractMergeSupport.notesLookGlued(extracted0.notes);
        CompletableFuture<Extracted> legendFuture = CompletableFuture.supplyAsync(() -> {
            long s = System.currentTimeMillis();
            try {
                if (!runLegend) {
                    return resplitExtractedNotes(extracted0);
                }
                return enrichNotesByLegend(extracted0, legendPng, ds, preferLegendNotes);
            } finally {
                legendHold[0] = System.currentTimeMillis() - s;
            }
        }, STAGE_POOL);
        CompletableFuture<CropBundle> boxedFuture = CompletableFuture.supplyAsync(() -> {
            long s = System.currentTimeMillis();
            try {
                if (!runBoxed || ds == null || !ds.enabled() || dxfForBox == null || dxfForBox.isBlank()) {
                    return CropBundle.empty();
                }
                NoteBoxFinder.Found found = NoteBoxFinder.find(dxfForBox);
                NoteBoxFinder.Found notesOnly = new NoteBoxFinder.Found(found.notes(), List.of());
                NoteCropRenderer.Images images = NoteCropRenderer.render(dxfForBox, notesOnly, py);
                if (images == null || images.notes() == null || images.notes().isEmpty()) {
                    return new CropBundle(
                            images == null ? NoteCropRenderer.Images.empty() : images,
                            DrawingConvertDeepseek.BoxedRead.missed());
                }
                return new CropBundle(images, DrawingConvertDeepseek.readBoxed(images.notes(), ds));
            } finally {
                boxedHold[0] = System.currentTimeMillis() - s;
            }
        }, STAGE_POOL);
        long tVision = System.currentTimeMillis();
        Extracted afterLegend = legendFuture.join();
        CropBundle cropBundle = boxedFuture.join();
        long visionWallMs = System.currentTimeMillis() - tVision;
        long legendMs = legendHold[0];
        long boxedMs = boxedHold[0];
        notifyProgress(
                progress,
                "核对说明区与材料框…",
                78,
                "说明区与材料框核对完成");
        BoxedMaterials boxed = finishBoxedNotes(
                afterLegend, ocrText, fallbackPng, ds, cropBundle.images(), cropBundle.read());
        extracted = boxed.extracted();
        // 抽取不做语料/LLM 补译；中文留给④「重新翻译」
        notifyProgress(progress, "整理抽取结果…", 85, "说明与材料已整理，中文翻译留待后续步骤");
        long translateMs = 0L;
        // 槽位规范化 + 质检警告
        List<String> qaWarn = new ArrayList<>(extracted.warnings);
        if (vectorPoor) {
            qaWarn.add("图纸矢量文字很少，NOTE 以 OCR/说明区视觉为主，请重点核对");
        }
        if (!runLegend && deepseek != null && deepseek.enabled()) {
            qaWarn.add("规则 NOTE 已较完整，已跳过说明区视觉以加速");
        }
        if (!runBoxed && deepseek != null && deepseek.enabled()) {
            qaWarn.add("规则材料已较完整，已跳过方框视觉以加速");
        }
        int layoutCount = countLayouts(details);
        if (layoutCount > 1) {
            qaWarn.add("图纸含 " + layoutCount + " 个布局；文字识别以第 1 页为主，其余布局文字已合并");
        }
        List<DrawingConvertView.MaterialRow> slotted = new ArrayList<>();
        if (extracted.materials != null) {
            for (DrawingConvertView.MaterialRow row : filterValidMaterials(extracted.materials)) {
                slotted.add(ExtractMergeSupport.normalizeSlots(row));
            }
        }
        qaWarn.addAll(ExtractMergeSupport.qualityWarnings(extracted.notes, slotted));
        extracted = new Extracted(
                extracted.notes, slotted, dedupeWarnings(qaWarn), extracted.overlayText, extracted.notesNorm);
        notifyProgress(progress, "整理预览结果…", 90, "抽取完成，正在保存结果");
        String svg = image == null || image.svgs() == null || image.svgs().isEmpty()
                ? "" : image.svgs().get(0);
        List<String> svgs = image == null || image.svgs() == null
                ? List.of()
                : List.copyOf(image.svgs());
        String message = previewMessage(extracted);
        java.util.Map<String, Object> sheetLayout = SheetLayoutSupport.analyze(dxfText).toMap();
        DrawingConvertView.Preview preview = new DrawingConvertView.Preview(
                fileName,
                MaterialLineSupport.drawingNo(fileName),
                message,
                extracted.warnings,
                extracted.notes,
                extracted.materials,
                png,
                svg,
                svgs,
                boxed.crops(),
                sheetLayout,
                false);
        byte[] sourceDxf = dxfText.isBlank()
                ? new byte[0]
                : dxfText.getBytes(StandardCharsets.ISO_8859_1);
        byte[] sheetHd = image == null ? null : image.sheetHdPng();
        log.info(
                "[任务] 抽取分段耗时 file={} renderMs={} ruleExtractMs={} legendMs={} boxedMs={} visionWallMs={} translateMs={} totalMs={} notes={} materials={} hdBytes={}",
                fileName,
                renderMs,
                extractMs,
                legendMs,
                boxedMs,
                visionWallMs,
                translateMs,
                System.currentTimeMillis() - tAll,
                extracted.notes == null ? 0 : extracted.notes.size(),
                extracted.materials == null ? 0 : extracted.materials.size(),
                sheetHd == null ? 0 : sheetHd.length);
        return new PreviewResult(preview, sourceDxf, sheetHd);
    }

    private static void notifyProgress(ExtractProgress progress, String stage, int percent, String logLine) {
        log.info("[任务] 抽取阶段 pct={} stage={} detail={}", percent, stage, logLine);
        if (progress != null) {
            progress.onStage(stage, percent, logLine);
        }
    }

    private static void notifyRenderHint(ExtractProgress progress, String hint) {
        if (hint == null || hint.isBlank()) {
            return;
        }
        int pct = 20;
        if (hint.contains("转换图纸") || hint.contains("格式转换") || hint.contains("ODA")) {
            pct = 18;
        } else if (hint.contains("预览图") || hint.contains("渲图") || hint.contains("render")) {
            pct = 25;
        } else if (hint.contains("识别") || hint.contains("OCR")) {
            pct = 30;
        } else if (hint.contains("结构") || hint.contains("明细")) {
            pct = 35;
        }
        notifyProgress(progress, "解析图纸…", pct, hint);
    }

    public static DrawingConvertView.Apply apply(
            byte[] dwgBytes,
            String fileName,
            DrawingConvertView.ApplyBody body,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr) {
        return apply(dwgBytes, fileName, body, odaCommand, pythonCommand, ocr,
                DrawingConvertDeepseek.Config.disabled(), null, null);
    }

    public static DrawingConvertView.Apply apply(
            byte[] dwgBytes,
            String fileName,
            DrawingConvertView.ApplyBody body,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek) {
        return apply(dwgBytes, fileName, body, odaCommand, pythonCommand, ocr, deepseek, null, null);
    }

    /**
     * @param stackDxfBytes 非空时写入 job.stackDxfPath，覆盖爆炸槽并按层次整页放大（工程图合成）。
     */
    public static DrawingConvertView.Apply apply(
            byte[] dwgBytes,
            String fileName,
            DrawingConvertView.ApplyBody body,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek,
            byte[] stackDxfBytes) {
        return apply(dwgBytes, fileName, body, odaCommand, pythonCommand, ocr, deepseek, stackDxfBytes, null);
    }

    /** 无客户图合成时喂给 convert_sheet 的最小 DXF。 */
    private static final String MINIMAL_EMPTY_DXF = ""
            + "0\nSECTION\n2\nHEADER\n0\nENDSEC\n"
            + "0\nSECTION\n2\nTABLES\n0\nENDSEC\n"
            + "0\nSECTION\n2\nBLOCKS\n0\nENDSEC\n"
            + "0\nSECTION\n2\nENTITIES\n0\nENDSEC\n"
            + "0\nSECTION\n2\nOBJECTS\n0\nENDSEC\n"
            + "0\nEOF\n";

    /**
     * @param stackDxfBytes  非空时写入 job.stackDxfPath（工程图合成）
     * @param sourceDxfBytes ① 已落盘源 DXF；非空则跳过 ODA。有审校 notes/materials 时再跳过 OCR/再抽取
     */
    public static DrawingConvertView.Apply apply(
            byte[] dwgBytes,
            String fileName,
            DrawingConvertView.ApplyBody body,
            String odaCommand,
            String pythonCommand,
            AiDrawingPaddleOcr.Config ocr,
            DrawingConvertDeepseek.Config deepseek,
            byte[] stackDxfBytes,
            byte[] sourceDxfBytes) {
        boolean hasStack = stackDxfBytes != null && stackDxfBytes.length > 0;
        boolean noCustomerDwg = dwgBytes == null || dwgBytes.length == 0;
        boolean skipCustomerContent = false;
        String effectiveName = fileName;

        String dxfText;
        DxfDetailView details;
        List<DrawingConvertView.NoteLine> notes;
        List<DrawingConvertView.MaterialRow> materials;
        Extracted extracted;

        if (noCustomerDwg) {
            if (!hasStack) {
                throw new IllegalArgumentException("请上传客户 DWG 或完成三维层次（二维与三维至少保留一个）");
            }
            if (body == null || body.notes() == null || body.materials() == null) {
                throw new IllegalArgumentException("请先完成材料核对与翻译审校后再合成工程图");
            }
            skipCustomerContent = true;
            dxfText = MINIMAL_EMPTY_DXF;
            details = DxfDetailView.empty();
            notes = body.notes();
            materials = body.materials();
            extracted = new Extracted(notes, materials, List.of(), "", Map.of());
            if (effectiveName == null || effectiveName.isBlank()
                    || !effectiveName.toLowerCase(Locale.ROOT).endsWith(".dwg")) {
                effectiveName = "stack-only.dwg";
            }
        } else {
            requireDwg(effectiveName);
            boolean annotReady = body != null && body.notes() != null && body.materials() != null;
            boolean useCachedDxf = sourceDxfBytes != null && sourceDxfBytes.length > 0;

            if (useCachedDxf) {
                dxfText = new String(sourceDxfBytes, StandardCharsets.ISO_8859_1);
                details = DxfDetailSupport.fromText(dxfText, pythonCommand);
                if (annotReady) {
                    notes = body.notes();
                    materials = body.materials();
                    extracted = new Extracted(notes, materials, List.of(), "", Map.of());
                } else {
                    extracted = extract(joinTexts(details), "", details);
                    notes = extracted.notes;
                    materials = extracted.materials;
                }
            } else {
                if (DwgOriginalPreview.resolveOda(odaCommand) == null) {
                    throw new IllegalArgumentException(
                            "图纸转换需要 ODA File Converter：请放到 data/eng-drawing/tools/ODAFileConverter.app，"
                                    + "或配置 ai-drawing.oda-converter / 环境变量 AI_DRAWING_ODA（Linux 可用 /opt/oda/ODAFileConverter）");
                }
                // 已有审校时不必整页 OCR；无任务缓存时仍需 ODA
                AiDrawingPaddleOcr.Config ocrEff = annotReady ? null : ocr;
                DwgOriginalPreview.Image image = DwgOriginalPreview.render(dwgBytes, odaCommand, pythonCommand, ocrEff);
                details = image == null ? DxfDetailView.empty() : image.detailsOrEmpty();
                dxfText = image == null ? null : image.dxfText();
                if (dxfText == null || dxfText.isBlank() || !details.present()) {
                    DwgOriginalPreview.DxfBundle bundle = DwgOriginalPreview.toDxf(dwgBytes, odaCommand, pythonCommand);
                    if (bundle == null || bundle.dxfText() == null || bundle.dxfText().isBlank()) {
                        throw new IllegalArgumentException("无法把 DWG 转成可编辑图纸");
                    }
                    dxfText = bundle.dxfText();
                    if (!details.present()) {
                        details = bundle.detailsOrEmpty();
                    }
                }
                if (annotReady) {
                    notes = body.notes();
                    materials = body.materials();
                    extracted = new Extracted(notes, materials, List.of(), "", Map.of());
                } else {
                    String ocrText = image == null || image.recognizedText() == null ? "" : image.recognizedText();
                    String png = image == null || image.pngBase64() == null ? "" : image.pngBase64();
                    String notesPng = image == null || image.notesPngBase64() == null ? "" : image.notesPngBase64();
                    String legendPng = image == null || image.notesLegendPngBase64() == null
                            ? "" : image.notesLegendPngBase64();
                    extracted = withBoxedNotes(
                            enrichNotesByLegend(extract(joinTexts(details), ocrText, details), legendPng, deepseek),
                            dxfText,
                            ocrText,
                            notesPng.isBlank() ? png : notesPng,
                            deepseek,
                            pythonCommand).extracted();
                    notes = extracted.notes;
                    materials = extracted.materials;
                }
            }
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ai-dwg-convert-");
            Path inDxf = dir.resolve("in.dxf");
            Files.writeString(inDxf, dxfText, StandardCharsets.ISO_8859_1);
            Path job = dir.resolve("job.json");
            Map<String, Object> payload = jobOf(notes, materials, details, extracted.overlayText, extracted.notesNorm);
            payload.put("drawingNo", MaterialLineSupport.drawingNo(effectiveName));
            if (skipCustomerContent) {
                payload.put("skipCustomerContent", true);
                payload.put("sheetLayout", SheetLayoutSupport.Layout.empty().toMap());
            } else if (body != null && body.sheetLayout() != null && !body.sheetLayout().isEmpty()) {
                payload.put("sheetLayout", body.sheetLayout());
            } else {
                payload.put("sheetLayout", SheetLayoutSupport.analyze(dxfText).toMap());
            }
            if (stackDxfBytes != null && stackDxfBytes.length > 0) {
                Path stackPath = dir.resolve("stack.dxf");
                Files.write(stackPath, stackDxfBytes);
                payload.put("stackDxfPath", stackPath.toAbsolutePath().toString());
                payload.put("stackPad", 1.08);
            }
            Files.writeString(job, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
            Path script = dir.resolve("convert_sheet.py");
            if (!copyResource(CONVERT_SCRIPT, script)) {
                throw new IllegalArgumentException("图纸转换脚本不可用");
            }
            Path outDxf = dir.resolve("out.dxf");
            String python = pythonCommand == null || pythonCommand.isBlank() ? "python3" : pythonCommand;
            String json = capture(python, TIMEOUT_SECONDS, script.toString(),
                    inDxf.toAbsolutePath().toString(),
                    outDxf.toAbsolutePath().toString(),
                    job.toAbsolutePath().toString());
            if (!Files.isRegularFile(outDxf) || Files.size(outDxf) == 0) {
                throw new IllegalArgumentException("转换图生成失败");
            }
            ConvertReport report = parseReport(json);
            Rendered rendered = renderConverted(outDxf, dir, python);
            byte[] outBytes = Files.readAllBytes(outDxf);
            String drawingNo = MaterialLineSupport.drawingNo(effectiveName);
            boolean eng = stackDxfBytes != null && stackDxfBytes.length > 0;
            // 下载名：图号 + 类型 + 生成时间；不再打包 ZIP
            String dxfName = eng
                    ? EngDrawingSupport.engineeringDxfFileName(drawingNo)
                    : EngDrawingSupport.convertDxfFileName(drawingNo);
            String zip = "";
            List<String> warnings = new ArrayList<>(report.warnings);
            boolean stackEmbedded = warnings.stream().anyMatch(w -> w != null && w.contains("已用三维层次覆盖爆炸槽"));
            boolean stackFailed = eng && (!stackEmbedded
                    || warnings.stream().anyMatch(DrawingConvertSupport::isStackInsertFailure));
            if (eng && stackFailed) {
                warnings.add(0, "三维层次图没有放进工程图预留区，请回②重存层次后再生成");
            }
            if (skipCustomerContent) {
                warnings.add(0, "未使用客户二维图纸，工程图不含客户 content");
            }
            String message;
            if (skipCustomerContent && eng && stackEmbedded && !stackFailed) {
                message = "工程图已生成（仅三维层次，无客户二维）";
            } else if (!eng) {
                message = applyMessage(notes, materials, report);
            } else if (stackEmbedded && !stackFailed) {
                message = "工程图已生成（三维层次已放入预留区）";
            } else {
                message = "工程图已生成，但三维层次图没有放进预留区";
            }
            return new DrawingConvertView.Apply(
                    effectiveName,
                    drawingNo,
                    message,
                    warnings,
                    rendered.png,
                    rendered.svg,
                    zip,
                    dxfName,
                    Base64.getEncoder().encodeToString(outBytes),
                    report.explosionSlot);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalArgumentException("转换图无法写入");
        } finally {
            deleteQuietly(dir);
        }
    }

    private record BoxedMaterials(Extracted extracted, List<String> crops) {
    }

    /**
     * 先按方框逐条放大读取。没有读出材料时，再退回整页说明图。
     */
    private static BoxedMaterials withBoxedNotes(
            Extracted extracted,
            String dxfText,
            String ocrText,
            String fallbackPng,
            DrawingConvertDeepseek.Config deepseek,
            String pythonCommand) {
        if (deepseek != null && deepseek.enabled() && dxfText != null && !dxfText.isBlank()) {
            NoteBoxFinder.Found found = NoteBoxFinder.find(dxfText);
            NoteBoxFinder.Found notesOnly = new NoteBoxFinder.Found(found.notes(), List.of());
            NoteCropRenderer.Images images = NoteCropRenderer.render(dxfText, notesOnly, pythonCommand);
            DrawingConvertDeepseek.BoxedRead read = DrawingConvertDeepseek.readBoxed(images.notes(), deepseek);
            if (read.used()) {
                List<String> warnings = new ArrayList<>(extracted.warnings);
                warnings.addAll(read.warnings());
                List<DrawingConvertView.MaterialRow> merged =
                        ExtractMergeSupport.mergeMaterials(extracted.materials, read.rows(), warnings);
                Extracted next = new Extracted(
                        extracted.notes, merged, warnings, extracted.overlayText, extracted.notesNorm);
                return new BoxedMaterials(next, read.crops());
            }
        }
        return new BoxedMaterials(
                DrawingConvertDeepseek.overlay(extracted, ocrText, fallbackPng, deepseek),
                List.of());
    }

    /** 裁图已并行完成时：只做 vision 读框或 overlay。 */
    private static BoxedMaterials finishBoxedNotes(
            Extracted extracted,
            String ocrText,
            String fallbackPng,
            DrawingConvertDeepseek.Config deepseek,
            NoteCropRenderer.Images images) {
        DrawingConvertDeepseek.BoxedRead read = null;
        if (deepseek != null && deepseek.enabled() && images != null && images.notes() != null && !images.notes().isEmpty()) {
            read = DrawingConvertDeepseek.readBoxed(images.notes(), deepseek);
        }
        return finishBoxedNotes(extracted, ocrText, fallbackPng, deepseek, images, read);
    }

    /**
     * 合并 legend 后的 NOTE 与方框材料。read 已算好则不再调 readBoxed（与 legend 并行路径）。
     * 材料按号融合，非整表覆盖。
     */
    private static BoxedMaterials finishBoxedNotes(
            Extracted extracted,
            String ocrText,
            String fallbackPng,
            DrawingConvertDeepseek.Config deepseek,
            NoteCropRenderer.Images images,
            DrawingConvertDeepseek.BoxedRead precomputed) {
        DrawingConvertDeepseek.BoxedRead read = precomputed;
        if (read == null
                && deepseek != null
                && deepseek.enabled()
                && images != null
                && images.notes() != null
                && !images.notes().isEmpty()) {
            read = DrawingConvertDeepseek.readBoxed(images.notes(), deepseek);
        }
        if (read != null && read.used()) {
            List<String> warnings = new ArrayList<>(extracted.warnings);
            warnings.addAll(read.warnings());
            List<DrawingConvertView.MaterialRow> merged =
                    ExtractMergeSupport.mergeMaterials(extracted.materials, read.rows(), warnings);
            Extracted next = new Extracted(
                    extracted.notes, merged, warnings, extracted.overlayText, extracted.notesNorm);
            return new BoxedMaterials(next, read.crops());
        }
        return new BoxedMaterials(
                DrawingConvertDeepseek.overlay(extracted, ocrText, fallbackPng, deepseek),
                List.of());
    }

    private static int countLayouts(DxfDetailView details) {
        if (details == null) {
            return 0;
        }
        if (details.layoutNames() != null && !details.layoutNames().isEmpty()) {
            return details.layoutNames().size();
        }
        if (details.texts() == null || details.texts().isEmpty()) {
            return 0;
        }
        Set<String> names = new LinkedHashSet<>();
        for (DxfDetailView.TextRow row : details.texts()) {
            if (row == null) {
                continue;
            }
            String layout = row.layout() == null ? "" : row.layout().trim();
            names.add(layout.isEmpty() ? "Model" : layout);
        }
        return names.size();
    }

    private static List<String> dedupeWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String w : warnings) {
            if (w != null && !w.isBlank()) {
                set.add(w.trim());
            }
        }
        return new ArrayList<>(set);
    }

    /** 从矢量字 + OCR 抽出编号 NOTE 与材料表，供核对。OCR 只补文字，不改几何。 */
    static Extracted extract(String vectorText, String ocrText, DxfDetailView details) {
        List<String> warnings = new ArrayList<>();
        String primary = vectorText == null ? "" : vectorText;
        OcrRead ocrRead = readOcr(ocrText);
        // 左侧 NOTES 大块：按 OCR 坐标框取字（正常图纸说明在左栏）
        String spatialNotes = normalizeOcrNotes(notesBlockFromOcrTsv(ocrText));
        String fallbackPlain = normalizeOcrNotes(ocrRead.plain);
        String ocrNotesCorpus = !spatialNotes.isBlank() ? spatialNotes : fallbackPlain;
        List<String> vectorNotes = mergeNoteLines(primary, "");
        List<String> rawNotes = mergeNoteLines(primary, ocrNotesCorpus);
        boolean usedSpatial = !spatialNotes.isBlank();
        if (rawNotes.isEmpty() && !fallbackPlain.isBlank() && usedSpatial) {
            // 空间框过窄时再试全文 OCR
            rawNotes = mergeNoteLines(primary, fallbackPlain);
        }
        if (rawNotes.isEmpty()) {
            warnings.add("未认出编号 NOTES");
        } else if (ExtractMergeSupport.isVectorPoor(primary)) {
            warnings.add("图纸矢量文字很少，已主要按 OCR 抽取 NOTE，粘连项将交说明区视觉纠正");
        } else if (!usedSpatial && extractNotesRegion(ocrNotesCorpus).isBlank()
                && extractNotesRegion(primary).isBlank()) {
            warnings.add("未定位到 NOTES 标题区，已按全文过滤尺寸公差后抽取");
        } else if (!usedSpatial && vectorNotes.size() < rawNotes.size()
                && !textHasMaterial(primary)
                && textHasMaterial(String.join("\n", rawNotes))) {
            warnings.add("图面说明不是文字对象，已按认字结果填写。认不清的不写入");
        }
        Map<String, DxfDetailView.TextRow> at = indexText(details);
        List<DrawingConvertView.NoteLine> notes = new ArrayList<>();
        for (String line : rawNotes) {
            Matcher m = NOTE_HEAD.matcher(line.trim());
            String no = noteNo(m);
            NoteGlossary.Translated tr = NoteGlossary.defer(line.trim());
            DxfDetailView.TextRow hit = at.get(no);
            notes.add(new DrawingConvertView.NoteLine(
                    no,
                    tr.original(),
                    tr.translated(),
                    tr.status(),
                    hit == null ? null : hit.x(),
                    hit == null ? null : hit.y()));
        }
        // 材料仍用全文 OCR+矢量，避免只吃 NOTES 左栏漏掉方框材料
        List<DrawingConvertView.MaterialRow> materials = materialRows(
                rawNotes, normalizeOcrNotes(primary + "\n" + fallbackPlain), warnings);
        Extracted built = new Extracted(notes, materials, warnings, "", Map.of());
        // OCR 易糊成一条：强制按序号再拆
        return resplitExtractedNotes(built);
    }

    /**
     * Deepseek 读 NOTES 区裁图按序号逐条；与规则 NOTE 按号融合。
     * preferLegend：无矢量/OCR 粘连时以视觉为准。
     */
    private static Extracted enrichNotesByLegend(
            Extracted extracted,
            String legendPngBase64,
            DrawingConvertDeepseek.Config deepseek) {
        return enrichNotesByLegend(extracted, legendPngBase64, deepseek, false);
    }

    private static Extracted enrichNotesByLegend(
            Extracted extracted,
            String legendPngBase64,
            DrawingConvertDeepseek.Config deepseek,
            boolean preferLegend) {
        if (extracted == null) {
            return extracted;
        }
        Extracted base = resplitExtractedNotes(extracted);
        if (deepseek != null && deepseek.enabled()
                && legendPngBase64 != null && !legendPngBase64.isBlank()) {
            DrawingConvertDeepseek.NotesLegendRead read =
                    DrawingConvertDeepseek.readNotesLegend(legendPngBase64, deepseek);
            if (read.used() && read.notes() != null && read.notes().size() >= 2) {
                List<String> warnings = new ArrayList<>(base.warnings);
                warnings.addAll(read.warnings());
                boolean useLegendFirst = preferLegend || ExtractMergeSupport.notesLookGlued(base.notes);
                List<DrawingConvertView.NoteLine> merged =
                        ExtractMergeSupport.mergeNotes(base.notes, read.notes(), warnings, useLegendFirst);
                return new Extracted(
                        merged,
                        base.materials,
                        warnings,
                        base.overlayText,
                        base.notesNorm);
            }
        }
        return base;
    }

    /** 把糊成一条/少条的 NOTE 按 1. 2. [11] 再拆；粘连条强制拆开。 */
    static Extracted resplitExtractedNotes(Extracted extracted) {
        if (extracted == null || extracted.notes == null || extracted.notes.isEmpty()) {
            return extracted;
        }
        List<DrawingConvertView.NoteLine> expanded = new ArrayList<>();
        boolean changed = false;
        for (DrawingConvertView.NoteLine n : extracted.notes) {
            if (n == null) {
                continue;
            }
            String orig = n.original() == null ? "" : n.original().trim();
            if (!ExtractMergeSupport.looksGlued(orig) && extracted.notes.size() >= 8) {
                expanded.add(n);
                continue;
            }
            if (!ExtractMergeSupport.looksGlued(orig)) {
                expanded.add(n);
                continue;
            }
            List<String> split = splitByNoteNumbers(orig);
            if (split.size() <= 1) {
                expanded.add(n);
                continue;
            }
            changed = true;
            for (String line : split) {
                if (looksLikeDimensionNote(line) || isSpecHeaderLine(line)) {
                    continue;
                }
                Matcher m = NOTE_HEAD.matcher(line.trim());
                String no = noteNo(m);
                if (no.isEmpty()) {
                    continue;
                }
                NoteGlossary.Translated tr = NoteGlossary.defer(line.trim());
                expanded.add(new DrawingConvertView.NoteLine(
                        no, tr.original(), tr.translated(), tr.status(), null, null));
            }
        }
        if (!changed && extracted.notes.size() >= 8) {
            return extracted;
        }
        if (!changed) {
            // 条数少：整表再拆一次
            StringBuilder blob = new StringBuilder();
            for (DrawingConvertView.NoteLine n : extracted.notes) {
                if (n == null) {
                    continue;
                }
                if (blob.length() > 0) {
                    blob.append('\n');
                }
                String orig = n.original() == null ? "" : n.original().trim();
                if (!orig.isEmpty()) {
                    blob.append(orig);
                }
            }
            List<String> split = splitByNoteNumbers(blob.toString());
            if (split.size() <= extracted.notes.size()) {
                return extracted;
            }
            expanded.clear();
            for (String line : split) {
                if (looksLikeDimensionNote(line) || isSpecHeaderLine(line)) {
                    continue;
                }
                Matcher m = NOTE_HEAD.matcher(line.trim());
                String no = noteNo(m);
                if (no.isEmpty()) {
                    continue;
                }
                NoteGlossary.Translated tr = NoteGlossary.defer(line.trim());
                expanded.add(new DrawingConvertView.NoteLine(
                        no, tr.original(), tr.translated(), tr.status(), null, null));
            }
            if (expanded.size() <= extracted.notes.size()) {
                return extracted;
            }
            changed = true;
        }
        if (!changed || expanded.isEmpty()) {
            return extracted;
        }
        // 同号去重：保留较干净（非粘连）者
        Map<String, DrawingConvertView.NoteLine> byNo = new LinkedHashMap<>();
        for (DrawingConvertView.NoteLine n : expanded) {
            String no = n.noteNo() == null ? "" : n.noteNo().replaceAll("\\D", "");
            if (no.isEmpty()) {
                continue;
            }
            DrawingConvertView.NoteLine prior = byNo.get(no);
            if (prior == null) {
                byNo.put(no, n);
                continue;
            }
            boolean pg = ExtractMergeSupport.looksGlued(prior.original());
            boolean ng = ExtractMergeSupport.looksGlued(n.original());
            if (pg && !ng) {
                byNo.put(no, n);
            } else if (!pg && ng) {
                // keep prior
            } else if (safeLen(n.original()) < safeLen(prior.original())) {
                byNo.put(no, n);
            }
        }
        List<DrawingConvertView.NoteLine> notes = new ArrayList<>(byNo.values());
        notes.sort(Comparator.comparingInt(n -> {
            try {
                return Integer.parseInt(n.noteNo().replaceAll("\\D", ""));
            } catch (Exception ex) {
                return 999;
            }
        }));
        return new Extracted(
                notes, extracted.materials, extracted.warnings, extracted.overlayText, extracted.notesNorm);
    }

    private static int safeLen(String s) {
        return s == null ? 0 : s.trim().length();
    }

    /**
     * 在序号前强制换行后按条解析：支持 1. / 2) / [11]；行内粘连也会切开。
     */
    static List<String> splitByNoteNumbers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace('\r', '\n');
        // 无空格粘连：…SHOWN.2.PART / …HANGING.14.THE
        normalized = normalized.replaceAll(
                "(?<=[A-Za-z0-9.])(?=[1-9]\\d{0,2}\\s*[.)、．](?!\\d))", "\n");
        // 方框序号（行首或行中）
        normalized = normalized.replaceAll("(?<!\\n)\\s*(?=\\[\\s*[1-9]\\d{0,2}\\s*\\])", "\n");
        // 圆点序号：行中「…SHOWN. 14. PART」
        normalized = normalized.replaceAll(
                "(?<![\\d.A-Za-z])(?<!\\n)\\s*(?=[1-9]\\d{0,2}\\s*[.)、．](?!\\d)\\s)", "\n");
        normalized = normalized.replaceAll(
                "(?<=[A-Za-z0-9.])\\s+(?=[1-9]\\d{0,2}\\s*[.)、．](?!\\d)\\s*[A-Za-z\\[])", "\n");
        // 无句点序号：…SHOWN 2 GEOMETRIC / 行首 2 GEOMETRIC
        normalized = normalized.replaceAll(
                "(?<=[A-Za-z.,;:])\\s+(?=[1-9]\\d{0,2}\\s+[A-Za-z\\[])", "\n");
        normalized = collapseOcrEcho(normalized);
        List<String> lines = customerNoteLines(normalized);
        if (lines.size() >= 2) {
            return lines;
        }
        return DrawingStackMaterial.parseNumberedNotes(normalized);
    }

    /** 压掉 OCR 常见重复短句（如 TO USE THE IN ORDER TO USE THE） */
    static String collapseOcrEcho(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = text;
        for (int i = 0; i < 4; i++) {
            String next = out.replaceAll("(?i)\\b(.{8,60}?)(?:\\s+\\1){1,}", "$1");
            if (next.equals(out)) {
                break;
            }
            out = next;
        }
        // 相邻完全相同句子
        out = out.replaceAll("(?i)([.!?]\\s*)\\1+", "$1");
        return out.replaceAll("[ \\t]{2,}", " ").trim();
    }

    /** 规格/材料表头行，不进编号 NOTE 正文 */
    static boolean isSpecHeaderLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String t = line.trim();
        return t.matches("(?i)^(THICKNESS|HARDNESS|STIFFENER\\s+MATERIAL|ALL\\s+MATERIALS?|"
                + "RECYCL(?:ED|ABLE)|PER\\s+ISO|ISO\\s+\\d|FOLLOWING\\s+MATERIAL|"
                + "MATERIAL\\s*[:：]|COLOR\\s*[:：]).*");
    }

    /**
     * 认字噪声：MATER1AL、半角括号、序号粘在 FOAM 上。只修材料行，不编造牌号。
     */
    static String normalizeOcrNotes(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = text.replace('\r', '\n')
                .replace('」', ']')
                .replace('「', '[')
                .replace('【', '[')
                .replace('】', ']');
        out = out.replaceAll("(?i)MATER[I1L]AL", "MATERIAL");
        // 13FOAM / 15LINER → 13 FOAM / 15 LINER
        out = out.replaceAll("(?i)(?<!\\d)([1-9]\\d{0,2})\\s*(FOAM|ADHESIVE|LINER|PSA|SHIM|TAPE)\\b", "$1 $2");
        out = out.replaceAll("(?i)\\[\\s*([1-9]\\d{0,2})\\s*[\\]）」]?\\s*", "[$1] ");
        // 同一行粘了多条材料时拆开，避免 13 并进 15
        out = MATERIAL_BREAK.matcher(out).replaceAll("\n");
        return out;
    }

    static List<DrawingConvertView.MaterialRow> materialRows(
            List<String> noteLines, String text, List<String> warnings) {
        List<DrawingConvertView.MaterialRow> square = filterValidMaterials(squareMaterials(text));
        if (!square.isEmpty()) {
            return square;
        }
        List<DrawingConvertView.MaterialRow> fromNotes = filterValidMaterials(materialsFromNotes(noteLines));
        if (!fromNotes.isEmpty()) {
            return fromNotes;
        }
        // 12. 这种圆点编号、以及引线上的 [14] PSA，都不是方框材料说明
        List<DrawingConvertView.MaterialRow> rows = new ArrayList<>();
        if (!textHasMaterial(text) && !notesHaveMaterial(noteLines)) {
            warnings.add("没有 MATERIAL 行，材料表为未标注");
        } else {
            warnings.add("没有方框序号的材料说明，材料表为未标注");
        }
        rows.add(materialRow(
                1, "", MaterialLineSupport.UNLABELED, "", MaterialLineSupport.UNLABELED, "unlabeled", "", ""));
        return rows;
    }

    /** 去掉合规 NOTE 误入、仅序号空壳等无效材料行。 */
    static List<DrawingConvertView.MaterialRow> filterValidMaterials(List<DrawingConvertView.MaterialRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DrawingConvertView.MaterialRow> out = new ArrayList<>();
        int seq = 1;
        for (DrawingConvertView.MaterialRow row : rows) {
            if (row == null) {
                continue;
            }
            String no = row.noteNo() == null ? "" : row.noteNo().replaceAll("\\D", "");
            String en = row.materialEn() == null ? "" : row.materialEn();
            String zh = row.materialZh() == null ? "" : row.materialZh();
            if (isShellMaterialName(no, en) || isShellMaterialName(no, zh)) {
                continue;
            }
            if (isNotesComplianceText(en) || isNotesComplianceText(zh)) {
                continue;
            }
            if (!looksLikeMaterialSpecLine(en) && !looksLikeMaterialSpecLine(zh)
                    && !filled(row.thickness()) && !filled(row.color())) {
                // 无牌号形态且无厚/色 → 丢弃
                continue;
            }
            out.add(materialRow(
                    seq++,
                    row.noteNo(),
                    row.materialEn(),
                    row.materialZh(),
                    row.thickness(),
                    row.source(),
                    row.role(),
                    row.color()));
        }
        return out;
    }

    static final class Extracted {
        final List<DrawingConvertView.NoteLine> notes;
        final List<DrawingConvertView.MaterialRow> materials;
        final List<String> warnings;
        final String overlayText;
        final Map<String, Double> notesNorm;

        Extracted(
                List<DrawingConvertView.NoteLine> notes,
                List<DrawingConvertView.MaterialRow> materials,
                List<String> warnings) {
            this(notes, materials, warnings, "", Map.of());
        }

        Extracted(
                List<DrawingConvertView.NoteLine> notes,
                List<DrawingConvertView.MaterialRow> materials,
                List<String> warnings,
                String overlayText,
                Map<String, Double> notesNorm) {
            this.notes = notes;
            this.materials = materials;
            this.warnings = warnings;
            this.overlayText = overlayText == null ? "" : overlayText;
            this.notesNorm = notesNorm == null ? Map.of() : notesNorm;
        }
    }

    /** 只取认字结果里的文字列。坐标行、纯数字行不当说明。 */
    static OcrRead readOcr(String tsv) {
        List<SheetFactSupport.Line> lines = SheetFactSupport.parse(tsv);
        if (lines.isEmpty()) {
            // 非 TSV 的纯文本（单测或上游已拼好）仍可当说明用
            if (tsv != null && textHasMaterial(tsv)) {
                return new OcrRead(tsv, "", Map.of());
            }
            return new OcrRead("", "", Map.of());
        }
        List<SheetFactSupport.Line> used = new ArrayList<>();
        for (SheetFactSupport.Line line : lines) {
            if (line == null || line.text() == null) {
                continue;
            }
            String text = line.text().trim();
            boolean loneNo = text.matches("[1-9]\\d{0,2}");
            if ((text.length() < 2 && !loneNo) || MOSTLY_NUMBER.matcher(text).matches()) {
                continue;
            }
            if (line.confidence() > 0 && line.confidence() < 0.45) {
                continue;
            }
            used.add(line);
        }
        used.sort(Comparator.comparingDouble(SheetFactSupport.Line::y).thenComparingDouble(SheetFactSupport.Line::x));
        StringBuilder plain = new StringBuilder();
        StringBuilder overlay = new StringBuilder();
        double minX = 1;
        double minY = 1;
        double maxX = 0;
        double maxY = 0;
        int words = 0;
        for (SheetFactSupport.Line line : used) {
            String text = line.text().trim();
            if (plain.length() > 0) {
                plain.append('\n');
            }
            plain.append(text);
            if (text.chars().anyMatch(Character::isLetter)) {
                words++;
            }
            // 抽取不译：overlay 保留原文，中文留给④
            if (overlay.length() > 0) {
                overlay.append('\n');
            }
            overlay.append(text);
            minX = Math.min(minX, line.x());
            minY = Math.min(minY, line.y());
            maxX = Math.max(maxX, line.x() + Math.max(line.w(), 0.02));
            maxY = Math.max(maxY, line.y() + Math.max(line.h(), 0.012));
        }
        if (words < 4) {
            return new OcrRead(plain.toString(), "", Map.of());
        }
        Map<String, Double> norm = new LinkedHashMap<>();
        norm.put("x", minX);
        norm.put("y", minY);
        norm.put("w", Math.max(0.05, maxX - minX));
        norm.put("h", Math.max(0.05, maxY - minY));
        return new OcrRead(plain.toString(), overlay.toString(), norm);
    }

    private record OcrRead(String plain, String overlay, Map<String, Double> norm) {
    }

    private static List<String> mergeNoteLines(String primary, String extra) {
        Map<String, String> byNo = new LinkedHashMap<>();
        // 1) 优先：OCR/矢量中的 NOTES 标题整块（真说明段落）
        ingestNotesRegion(byNo, extra);
        ingestNotesRegion(byNo, primary);
        boolean fromRegion = !byNo.isEmpty();
        // 2) 全文兜底（已过滤公差）；OCR 始终参与，便于补全缺号
        addNotes(byNo, PdfFactSupport.read(nullToEmpty(primary)).notes());
        addNotes(byNo, customerNoteLines(primary));
        addNotes(byNo, DrawingStackMaterial.parseNumberedNotes(primary));
        if (extra != null && !extra.isBlank()) {
            addNotes(byNo, PdfFactSupport.read(extra).notes());
            addNotes(byNo, customerNoteLines(extra));
            addNotes(byNo, DrawingStackMaterial.parseNumberedNotes(extra));
        }
        // 若已从 NOTES 区抽到足够条目，丢掉仍像尺寸的残留
        if (fromRegion && byNo.size() >= 3) {
            byNo.entrySet().removeIf(e -> looksLikeDimensionNote(e.getValue()));
        }
        List<Map.Entry<String, String>> rows = new ArrayList<>(byNo.entrySet());
        rows.sort(Comparator.comparingInt(e -> parseInt(e.getKey(), 999)));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> row : rows) {
            out.add(row.getValue());
        }
        return out;
    }

    /**
     * 从 OCR TSV 按页坐标截取 NOTES 大块（锚点驱动，非死板左半屏）。
     * 以 NOTES 标题为种子，按种子列宽向右扩展；收入同列编号正文。
     */
    static String notesBlockFromOcrTsv(String tsv) {
        List<SheetFactSupport.Line> lines = SheetFactSupport.parse(tsv);
        if (lines.isEmpty()) {
            return "";
        }
        SheetFactSupport.Line seed = null;
        for (SheetFactSupport.Line line : lines) {
            if (line == null || line.text() == null) {
                continue;
            }
            String t = line.text().trim();
            if (NOTES_SEED.matcher(t).matches() || NOTES_REGION_HEADER.matcher(t).matches()) {
                if (seed == null || line.x() < seed.x()) {
                    seed = line;
                }
            }
        }
        double top;
        double left;
        double maxX;
        if (seed != null) {
            top = Math.max(0, seed.y() - 0.01);
            left = Math.max(0, seed.x() - 0.03);
            // 以标题宽度为基准向右扩一列，不再写死 0.52
            maxX = Math.min(0.62, Math.max(seed.x() + Math.max(seed.w(), 0.18) + 0.16, seed.x() + 0.34));
        } else {
            // 无标题：左上栏兜底
            top = 0.02;
            left = 0.0;
            maxX = 0.42;
        }
        List<SheetFactSupport.Line> band = new ArrayList<>();
        for (SheetFactSupport.Line line : lines) {
            if (line == null || line.text() == null) {
                continue;
            }
            String t = line.text().trim();
            if (t.isEmpty()) {
                continue;
            }
            double midY = line.y() + line.h() / 2.0;
            double midX = line.x() + line.w() / 2.0;
            if (midY < top || midY > TITLE_BAND_Y) {
                continue;
            }
            if (midX < left - 0.02) {
                continue;
            }
            if (midX > maxX && line.x() > maxX) {
                continue;
            }
            if (NOTES_REGION_STOP.matcher(t).matches() && midX > maxX - 0.05) {
                continue;
            }
            // 右侧材料 MATERIAL 行不进 NOTES 块
            if (midX > 0.45 && t.toUpperCase(Locale.ROOT).contains("MATERIAL")) {
                continue;
            }
            if (isSpecHeaderLine(t)) {
                continue;
            }
            if (line.confidence() > 0 && line.confidence() < 0.40) {
                continue;
            }
            band.add(line);
        }
        if (band.size() < 3) {
            return "";
        }
        band.sort(Comparator
                .comparingDouble(SheetFactSupport.Line::y)
                .thenComparingDouble(SheetFactSupport.Line::x));
        StringBuilder sb = new StringBuilder();
        boolean hasHeader = false;
        for (SheetFactSupport.Line line : band) {
            if (NOTES_SEED.matcher(line.text().trim()).matches()) {
                hasHeader = true;
                break;
            }
        }
        if (!hasHeader) {
            sb.append("NOTES:").append('\n');
        }
        for (SheetFactSupport.Line line : band) {
            String t = line.text().trim();
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString().trim();
    }

    /** 从 NOTES 标题截到标题栏/STOP，供专区解析 */
    static String extractNotesRegion(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.replace('\r', '\n').split("\\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (NOTES_REGION_HEADER.matcher(line).matches() || NOTES_SEED.matcher(line).matches()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (i > start && NOTES_REGION_STOP.matcher(line).matches()) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private static void ingestNotesRegion(Map<String, String> byNo, String text) {
        String region = extractNotesRegion(text);
        if (region.isBlank()) {
            // 已是空间框拼好的整块（可能自带 NOTES:），整段当区解析
            if (text != null && text.toUpperCase(Locale.ROOT).contains("NOTES")) {
                region = text;
            } else {
                return;
            }
        }
        addNotes(byNo, PdfFactSupport.read(region).notes());
        addNotes(byNo, customerNoteLines(region));
        addNotes(byNo, DrawingStackMaterial.parseNumberedNotes(region));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 把整段说明拆成编号条目。下一编号之前的续行并进上一条。 */
    static List<String> customerNoteLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String normalized = text.replace("\\P", "\n").replace('\r', '\n');
        normalized = NOTE_BREAK.matcher(normalized).replaceAll("\n");
        String currentNo = "";
        StringBuilder current = null;
        String pendingNo = "";
        for (String raw : normalized.split("\\n")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher lone = LONE_NOTE_NO.matcher(line);
            if (lone.matches()) {
                pendingNo = lone.group(1);
                continue;
            }
            if (isSpecHeaderLine(line)) {
                // 规格块不挂到编号 NOTE
                if (current != null && !currentNo.isEmpty() && ExtractMergeSupport.looksGlued(current.toString())) {
                    // 规格插入粘连正文时截断当前条
                    out.add(current.toString().trim());
                    current = null;
                    currentNo = "";
                }
                pendingNo = "";
                continue;
            }
            // OCR 常把「1.」与正文拆行：单独序号后的字母行并成一条
            if (!pendingNo.isEmpty() && (ROLE_MATERIAL_LINE.matcher(line).matches() || noteBodyCandidate(line))) {
                if (current != null && !currentNo.isEmpty()) {
                    out.add(current.toString().trim());
                }
                currentNo = pendingNo;
                current = new StringBuilder(pendingNo).append(". ").append(line);
                pendingNo = "";
                continue;
            }
            pendingNo = "";
            Matcher head = NOTE_HEAD.matcher(line);
            String no = noteNo(head);
            if (!no.isEmpty()) {
                if (current != null && !currentNo.isEmpty()) {
                    out.add(current.toString().trim());
                }
                currentNo = no;
                current = new StringBuilder(line);
                continue;
            }
            if (current != null) {
                current.append(' ').append(line);
            }
        }
        if (current != null && !currentNo.isEmpty()) {
            out.add(current.toString().trim());
        }
        return out;
    }

    /** 可作 NOTE 正文的行：有足够字母，且不是尺寸公差 */
    private static boolean noteBodyCandidate(String line) {
        if (line == null || line.isBlank() || looksLikeDimensionNote(line)) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < line.length(); i++) {
            if (Character.isLetter(line.charAt(i))) {
                letters++;
            }
        }
        return letters >= 4;
    }

    private static String noteNo(Matcher head) {
        if (head == null || !head.matches()) {
            return "";
        }
        if (head.group(1) != null) {
            return head.group(1);
        }
        if (head.group(2) != null) {
            return head.group(2);
        }
        return head.group(3) == null ? "" : head.group(3);
    }

    private static boolean textHasMaterial(String text) {
        return text != null && text.toUpperCase(Locale.ROOT).contains("MATERIAL");
    }

    private static boolean mapHasMaterial(Map<String, String> byNo) {
        for (String line : byNo.values()) {
            if (textHasMaterial(line)) {
                return true;
            }
        }
        return false;
    }

    /** 只从带 MATERIAL: 的编号说明取材料。厚度优先 THICKNESS 行，其次牌号末尾的毫米。 */
    static List<DrawingConvertView.MaterialRow> materialsFromNotes(List<String> noteLines) {
        if (noteLines == null || noteLines.isEmpty()) {
            return List.of();
        }
        List<DrawingConvertView.MaterialRow> rows = new ArrayList<>();
        int seq = 1;
        for (String note : noteLines) {
            if (note == null || note.isBlank()) {
                continue;
            }
            Matcher head = NOTE_HEAD.matcher(note.trim());
            String no = noteNo(head);
            // 圆点编号不是方框，不进材料表
            if (no.isEmpty() || head.group(2) != null) {
                continue;
            }
            Matcher mat = MATERIAL_BODY.matcher(note);
            if (!mat.find()) {
                continue;
            }
            if (isNotesComplianceText(note) || !looksLikeMaterialSpecLine(note)) {
                continue;
            }
            String bodyForSpec = note.replaceFirst("(?i)^\\s*(?:\\[[^\\]]+\\]|[1-9]\\d{0,2})\\s*", "");
            Spec spec = splitSpec(bodyForSpec);
            String role = materialRole(note);
            String name = materialName(role, spec.part);
            if (name.isBlank() || isShellMaterialName(no, name)) {
                continue;
            }
            // 中文留给④翻译步，抽取不预译
            rows.add(materialRow(seq++, no, name, "", spec.thick, "customer_note", role, spec.color));
        }
        return rows;
    }

    /** 方形编号的 MATERIAL 行：名称、料号、颜色、厚度。引线如 [14] PSA 没有 MATERIAL，不进表。 */
    static List<DrawingConvertView.MaterialRow> squareMaterials(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String flat = normalizeOcrNotes(text);
        Map<String, DrawingConvertView.MaterialRow> byNo = new LinkedHashMap<>();
        int seq = scanBoxedLines(flat, byNo, 1);
        Matcher matcher = SQUARE_MATERIAL.matcher(flat);
        while (matcher.find()) {
            String no = firstNo(matcher.group(1), matcher.group(2), "");
            String role = matcher.group(3).replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
            String rawBody = matcher.group(4) == null ? "" : matcher.group(4);
            seq = putMaterial(byNo, seq, no, role, sanitizeMaterialBody(rawBody));
            // 正文里粘着的 13FOAM MATERIAL 再拆成独立行
            Matcher emb = EMBEDDED_MATERIAL.matcher(rawBody);
            while (emb.find()) {
                if (emb.start() <= 1) {
                    continue;
                }
                String embNo = emb.group(1);
                String embRole = emb.group(2).replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
                String embBody = rawBody.substring(emb.end());
                Matcher next = EMBEDDED_MATERIAL.matcher(embBody);
                if (next.find() && next.start() > 1) {
                    embBody = embBody.substring(0, next.start());
                }
                seq = putMaterial(byNo, seq, embNo, embRole, sanitizeMaterialBody(embBody));
            }
        }
        List<DrawingConvertView.MaterialRow> out = new ArrayList<>(byNo.values());
        out.sort(Comparator.comparingInt(row -> parseInt(row.noteNo(), 999)));
        List<DrawingConvertView.MaterialRow> numbered = new ArrayList<>();
        int n = 1;
        for (DrawingConvertView.MaterialRow row : out) {
            numbered.add(materialRow(
                    n++,
                    row.noteNo(),
                    row.materialEn(),
                    row.materialZh(),
                    row.thickness(),
                    row.source(),
                    row.role(),
                    row.color()));
        }
        return numbered;
    }

    /** 方框序号后的材料说明。PET TAPE、ADHESIVE 1 MATERIAL、LOW TACK LINER 都收。引线只有序号或角色词的不收。 */
    private static int scanBoxedLines(
            String text,
            Map<String, DrawingConvertView.MaterialRow> byNo,
            int seq) {
        String pendingNo = "";
        StringBuilder pending = null;
        for (String raw : text.split("\\n")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher lone = LONE_NOTE_NO.matcher(line);
            if (lone.matches()) {
                seq = flushBoxed(byNo, seq, pendingNo, pending);
                pendingNo = lone.group(1);
                pending = new StringBuilder();
                continue;
            }
            Matcher head = BOXED_HEAD.matcher(line);
            if (head.matches()) {
                seq = flushBoxed(byNo, seq, pendingNo, pending);
                pendingNo = firstNo(head.group(1), head.group(2), "");
                pending = new StringBuilder(head.group(3).trim());
                continue;
            }
            if (pending != null && !pendingNo.isEmpty()) {
                boolean dotted = line.matches("(?i)^[1-9]\\d{0,2}\\s*[.)、．].*");
                boolean append = !dotted && (pending.length() == 0
                        ? looksLikeMaterial(line)
                        : continuesMaterial(line));
                if (append) {
                    if (pending.length() > 0) {
                        pending.append(' ');
                    }
                    pending.append(line);
                    continue;
                }
            }
            seq = flushBoxed(byNo, seq, pendingNo, pending);
            pendingNo = "";
            pending = null;
        }
        return flushBoxed(byNo, seq, pendingNo, pending);
    }

    private static int flushBoxed(
            Map<String, DrawingConvertView.MaterialRow> byNo,
            int seq,
            String no,
            StringBuilder pending) {
        if (no == null || no.isBlank() || pending == null || pending.length() == 0) {
            return seq;
        }
        return acceptBoxed(byNo, seq, no, pending.toString());
    }

    private static int acceptBoxed(
            Map<String, DrawingConvertView.MaterialRow> byNo,
            int seq,
            String no,
            String text) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty() || calloutOnly(body) || isNotesComplianceText(body) || !looksLikeMaterial(body)) {
            return seq;
        }
        // 合规 NOTE（如 10. ALL … MATERIALS MUST COMPLY）不是「角色 MATERIAL: 牌号」
        if (!looksLikeMaterialSpecLine(body)) {
            return seq;
        }
        Matcher label = LABEL_SPLIT.matcher(body);
        String role = "";
        String specBody = body;
        boolean materialWord = false;
        if (label.matches()) {
            String head = label.group(1).replaceAll("\\s+", " ").trim();
            specBody = label.group(2) == null ? "" : label.group(2).trim();
            if (head.toUpperCase(Locale.ROOT).matches(".*\\bMATERIAL\\s*$")) {
                role = head.replaceAll("(?i)\\s*MATERIAL\\s*$", "").trim();
                materialWord = true;
            } else {
                role = head;
            }
        }
        return putMaterial(byNo, seq, no, role, specBody, materialWord);
    }

    /** 有牌号、厚度或颜色的才是材料说明。 */
    private static boolean looksLikeMaterial(String text) {
        if (text == null || text.isBlank() || isNotesComplianceText(text)) {
            return false;
        }
        return text.matches("(?is).*(MATERIAL|TAPE|LINER|THICK|THK|COLOR|\\d\\s*mm).*");
    }

    /**
     * 真材料说明形态：ROLE MATERIAL: / MATERIAL: / 带厚度颜色的料号行。
     * 排除 NOTE 里「… MATERIALS MUST COMPLY …」这类合规句。
     */
    static boolean looksLikeMaterialSpecLine(String text) {
        if (text == null || text.isBlank() || isNotesComplianceText(text)) {
            return false;
        }
        String t = text.trim();
        if (t.matches("(?i)^(?:" + ROLE_ALT + ")\\s+MATERIAL\\b.*")) {
            return true;
        }
        if (t.matches("(?i).*\\bMATERIAL\\s*[:：].*")) {
            return true;
        }
        // 有厚度或颜色，且不是纯合规说明
        return t.matches("(?is).*(?:THICK(?:NESS)?|THK|COLOR)\\s*[:：].*")
                || t.matches("(?is).*\\d+(?:\\.\\d+)?\\s*mm.*");
    }

    /** NOTES 合规/环境条款（常含 MATERIALS 字样，勿进材料表）。 */
    static boolean isNotesComplianceText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String u = text.toUpperCase(Locale.ROOT);
        return u.contains("MUST COMPLY")
                || u.contains("ENVIRONMENTAL")
                || u.contains("HOMOGENEOUS")
                || u.contains("REGULATED SUBSTANCE")
                || u.contains("ROHS")
                || (u.contains("SPECIFICATION") && u.contains("MATERIAL") && !u.matches("(?s).*\\bMATERIAL\\s*:.*"));
    }

    /** 材料名无效：空、等于序号、纯数字、过短无料义。 */
    static boolean isShellMaterialName(String noteNo, String name) {
        if (name == null || name.isBlank() || MaterialLineSupport.UNLABELED.equals(name.trim())) {
            return true;
        }
        String n = name.replaceAll("\\s+", " ").trim();
        String no = noteNo == null ? "" : noteNo.replaceAll("\\D", "");
        if (!no.isEmpty() && n.replaceAll("\\D", "").equals(no) && n.replaceAll("[\\d.\\s\\[\\]]", "").isEmpty()) {
            return true;
        }
        if (n.matches("^[\\d.\\[\\]\\s]+$")) {
            return true;
        }
        // 去掉序号后几乎无字母
        String body = n.replaceFirst("^\\s*(?:\\[\\s*)?\\d{1,3}(?:\\s*\\])?\\s*[.)、．]?\\s*", "");
        int letters = 0;
        for (int i = 0; i < body.length(); i++) {
            if (Character.isLetter(body.charAt(i))) {
                letters++;
            }
        }
        return letters < 3;
    }

    /** 引线上的 [12]、[13] FOAM 没有牌号。 */
    private static boolean calloutOnly(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.matches("(?i)[A-Z][A-Z ]{0,40}") && !trimmed.contains(":") && !trimmed.matches(".*\\d.*");
    }

    /** 颜色、厚度被认到下一行时并回上一条。 */
    private static boolean continuesMaterial(String line) {
        return line != null && line.matches("(?i).*(COLOR|THICK|THK|\\d\\s*mm|TRANSLUCENT).*");
    }

    private static int putMaterial(
            Map<String, DrawingConvertView.MaterialRow> byNo,
            int seq,
            String no,
            String role,
            String body) {
        if (no == null || no.isBlank()) {
            return seq;
        }
        return putMaterial(byNo, seq, no, role, body, true);
    }

    private static int putMaterial(
            Map<String, DrawingConvertView.MaterialRow> byNo,
            int seq,
            String no,
            String role,
            String body,
            boolean materialWord) {
        if (no == null || no.isBlank()) {
            return seq;
        }
        Spec spec = splitSpec(body);
        if (spec.part.isBlank() && role != null && !role.isBlank() && !materialWord) {
            spec = new Spec(role, spec.color, spec.thick);
        }
        if (spec.part.isBlank()) {
            return seq;
        }
        // 合规 NOTE 正文误当材料
        if (isNotesComplianceText(spec.part) || isNotesComplianceText(role) || isNotesComplianceText(body)) {
            return seq;
        }
        String name = materialName(role, spec.part, materialWord);
        if (isShellMaterialName(no, name)) {
            return seq;
        }
        // 中文留给④翻译步，抽取不预译
        DrawingConvertView.MaterialRow row = materialRow(
                seq, no, name, "", spec.thick, "customer_note", role, spec.color);
        DrawingConvertView.MaterialRow existing = byNo.get(no);
        // 同一编号认到多条时，留颜色和厚度更全、料号更干净的那条
        if (existing != null && !preferMaterial(row, existing)) {
            return seq;
        }
        byNo.put(no, row);
        return existing == null ? seq + 1 : seq;
    }

    private static boolean preferMaterial(
            DrawingConvertView.MaterialRow candidate,
            DrawingConvertView.MaterialRow current) {
        int scoreGap = materialScore(candidate) - materialScore(current);
        if (scoreGap != 0) {
            return scoreGap > 0;
        }
        return cleanMaterialName(candidate.materialEn()) && !cleanMaterialName(current.materialEn());
    }

    private static int materialScore(DrawingConvertView.MaterialRow row) {
        int score = 0;
        if (row != null && filled(row.color())) {
            score += 2;
        }
        if (row != null && filled(row.thickness())) {
            score += 2;
        }
        return score;
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank() && !MaterialLineSupport.UNLABELED.equals(value);
    }

    private static boolean cleanMaterialName(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return !upper.contains("COLOR") && !upper.contains("THICK") && !upper.matches("(?s).*\\d\\s*MM.*");
    }

    /** 截掉正文里粘上的下一条材料，以及别的 NOTE 乱码。 */
    static String sanitizeMaterialBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String body = raw;
        Matcher embedded = EMBEDDED_MATERIAL.matcher(body);
        if (embedded.find() && embedded.start() > 1) {
            body = body.substring(0, embedded.start());
        }
        Matcher garbage = SPEC_GARBAGE.matcher(body);
        if (garbage.find() && garbage.start() > 1) {
            body = body.substring(0, garbage.start());
        }
        return body.trim();
    }

    private static String firstNo(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third == null ? "" : third;
    }

    /** 材料正文后面的 [14] PSA 一类引线不进料号。 */
    private static String cutCalloutTail(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher matcher = CALLOUT_TAIL.matcher(raw);
        if (!matcher.find()) {
            return raw;
        }
        return raw.substring(0, matcher.start());
    }

    private static Spec splitSpec(String raw) {
        String body = closeSpacedDots(sanitizeMaterialBody(cutCalloutTail(raw == null ? "" : raw))
                .replaceAll("\\s+", " ")
                .trim());
        String color = "";
        Matcher colorMatcher = COLOR_PHRASE.matcher(body);
        if (colorMatcher.find()) {
            color = colorMatcher.group(1).replaceAll("\\s+", " ").trim();
            body = COLOR_PHRASE.matcher(body).replaceAll(" ");
        }
        // 第一条厚度之后的单字、孤立数字不属于这条材料
        Matcher thickEnd = THICK_END.matcher(body);
        if (thickEnd.find()) {
            body = body.substring(0, thickEnd.end());
        }
        String thick = thicknessOf(body);
        if (!MaterialLineSupport.UNLABELED.equals(thick)) {
            body = THICKNESS.matcher(body).replaceAll(" ");
        }
        Matcher thickWord = THICK_WORD.matcher(body);
        if (thickWord.find()) {
            if (MaterialLineSupport.UNLABELED.equals(thick)) {
                String number = fixOcrDigits(thickWord.group(1)).replaceAll("\\s+", " ").trim();
                thick = thickWord.group(2) == null ? number : number + " mm";
            }
            body = THICK_WORD.matcher(body).replaceAll(" ");
        }
        Matcher thickMatcher = THICK_PHRASE.matcher(body);
        if (thickMatcher.find()) {
            if (MaterialLineSupport.UNLABELED.equals(thick)) {
                thick = fixOcrDigits(thickMatcher.group(1)).replaceAll("\\s+", " ").trim()
                        + thickMatcher.group(2);
            }
            body = THICK_PHRASE.matcher(body).replaceAll(" ");
        }
        if (MaterialLineSupport.UNLABELED.equals(thick)) {
            Matcher range = BARE_RANGE.matcher(body);
            if (range.find()) {
                thick = fixOcrDigits(range.group(1)).replaceAll("\\s+", " ").trim();
                body = (body.substring(0, range.start()) + " " + body.substring(range.end()))
                        .replaceAll("\\s+", " ")
                        .trim();
            }
        }
        if (color.isBlank()) {
            Matcher bareColor = BARE_COLOR.matcher(body);
            if (bareColor.find()) {
                color = bareColor.group(1).replaceAll("\\s+", " ").trim();
                body = BARE_COLOR.matcher(body).replaceAll(" ");
            }
        }
        if (!MaterialLineSupport.UNLABELED.equals(thick)) {
            thick = fixOcrDigits(thick);
        }
        body = body.replaceAll("(?i)\\bTHICK(?:NESS)?\\b", "")
                .replaceAll("(?i)\\bTHK\\b", "")
                .replaceAll("(?i)\\bCOLOR\\b", "")
                .replaceAll("(?i)\\bMATERIAL\\b", "")
                .replaceAll("[.。;；]\\s*", " ")
                .replaceAll("[,，]+\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        body = dropJunkTail(body);
        if (body.length() > 80) {
            body = body.substring(0, 80).replaceAll("\\s+\\S*$", "").trim();
        }
        return new Spec(body, color, thick);
    }

    /** 把 0 . 76、0. 76 收成 0.76，避免厚度丢小数点。 */
    private static String closeSpacedDots(String text) {
        String body = text == null ? "" : text;
        String previous;
        do {
            previous = body;
            body = SPACED_DOT.matcher(body).replaceAll("$1.$2");
        } while (!body.equals(previous));
        return body;
    }

    /** 末尾连续的单字母、孤立数字丢掉。牌号后的一个字母（如 P）保留。 */
    private static String dropJunkTail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String[] tokens = body.split("\\s+");
        int end = tokens.length;
        while (end > 0 && junkToken(tokens[end - 1])) {
            end--;
        }
        if (end < tokens.length && end > 0 && tokens[end].matches("(?i)[A-Z]") && tokens[end - 1].matches(".*\\d.*")) {
            end++;
        }
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (kept.length() > 0) {
                kept.append(' ');
            }
            kept.append(tokens[i]);
        }
        return kept.toString().trim();
    }

    private static boolean junkToken(String token) {
        return token != null && token.matches("(?i)[A-Z]|\\d");
    }

    /** 厚度里的 O/0 字形混淆只改数字段，不改牌号原文。 */
    private static String fixOcrDigits(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replace('O', '0').replace('o', '0');
    }

    /** 材料信息 = 角色 + 牌号等，不含颜色和厚度。带 MATERIAL 的才写成 MATERIAL:。 */
    private static String materialName(String role, String part) {
        return materialName(role, part, true);
    }

    private static String materialName(String role, String part, boolean materialWord) {
        String roleText = role == null ? "" : role.replaceAll("\\s+", " ").trim();
        String body = part == null ? "" : part.replaceAll("\\s+", " ").trim();
        if (!roleText.isEmpty()) {
            body = body.replaceFirst("(?i)^" + java.util.regex.Pattern.quote(roleText) + "\\s*[:：]?\\s*", "");
        }
        body = body.replaceFirst("(?i)^MATERIAL\\s*[:：]?\\s*", "").trim();
        if (roleText.isEmpty()) {
            return body;
        }
        if (body.isEmpty()) {
            return materialWord ? roleText + " MATERIAL" : roleText;
        }
        return materialWord ? roleText + " MATERIAL: " + body : roleText + ": " + body;
    }

    private static boolean notesHaveMaterial(List<String> noteLines) {
        if (noteLines == null) {
            return false;
        }
        for (String line : noteLines) {
            if (textHasMaterial(line)) {
                return true;
            }
        }
        return false;
    }

    private record Spec(String part, String color, String thick) {
    }

    private static DrawingConvertView.MaterialRow materialRow(
            int seq,
            String noteNo,
            String part,
            String nameZh,
            String thick,
            String source,
            String role,
            String color) {
        return new DrawingConvertView.MaterialRow(
                seq,
                noteNo == null ? "" : noteNo,
                part == null ? "" : part,
                nameZh == null ? "" : nameZh,
                thick == null || thick.isBlank() ? MaterialLineSupport.UNLABELED : thick,
                source,
                role == null ? "" : role,
                color == null || color.isBlank() ? MaterialLineSupport.UNLABELED : color);
    }

    private static void addNotes(Map<String, String> byNo, List<String> lines) {
        if (lines == null) {
            return;
        }
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String line = raw.trim();
            if (looksLikeDimensionNote(line)) {
                continue;
            }
            Matcher m = NOTE_HEAD.matcher(line);
            String no = noteNo(m);
            if (no.isEmpty()) {
                continue;
            }
            String existing = byNo.get(no);
            // 真说明覆盖公差；带 MATERIAL 覆盖无 MATERIAL；字母更多者优先
            if (existing == null || shouldReplaceNote(existing, line)) {
                byNo.put(no, line);
            }
        }
    }

    /** 尺寸公差冒充的 NOTE（如 2.00 ±0.08） */
    static boolean looksLikeDimensionNote(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        String t = line.trim();
        if (DIMENSION_NOTE_LINE.matcher(t).matches()) {
            return true;
        }
        Matcher m = NOTE_HEAD.matcher(t);
        String body = "";
        if (m.matches()) {
            body = m.group(m.groupCount()) == null ? "" : m.group(m.groupCount()).trim();
        } else {
            body = t;
        }
        return DrawingStackMaterial.isDimensionNoteBody(body);
    }

    private static boolean shouldReplaceNote(String existing, String candidate) {
        boolean eg = ExtractMergeSupport.looksGlued(existing);
        boolean cg = ExtractMergeSupport.looksGlued(candidate);
        if (eg && !cg) {
            return true;
        }
        if (!eg && cg) {
            return false;
        }
        if (looksLikeDimensionNote(existing) && !looksLikeDimensionNote(candidate)) {
            return true;
        }
        if (!textHasMaterial(existing) && textHasMaterial(candidate) && !cg) {
            return true;
        }
        if (textHasMaterial(existing) && !textHasMaterial(candidate) && !looksLikeDimensionNote(existing)) {
            return false;
        }
        return noteTextQuality(candidate) > noteTextQuality(existing);
    }

    /** 字母多且不粘连 → 更像真 NOTES；粘连重罚 */
    private static int noteTextQuality(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        if (ExtractMergeSupport.looksGlued(s)) {
            return Math.min(s.length(), 80);
        }
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                letters++;
            }
        }
        return letters * 3 + Math.min(s.length(), 220);
    }

    private static Map<String, DxfDetailView.TextRow> indexText(DxfDetailView details) {
        Map<String, DxfDetailView.TextRow> map = new LinkedHashMap<>();
        if (details == null || details.texts() == null) {
            return map;
        }
        for (DxfDetailView.TextRow row : details.texts()) {
            if (row == null || row.content() == null) {
                continue;
            }
            Matcher m = NOTE_HEAD.matcher(row.content().trim());
            String no = noteNo(m);
            if (!no.isEmpty()) {
                map.putIfAbsent(no, row);
            }
        }
        return map;
    }

    static String joinTexts(DxfDetailView details) {
        if (details == null || details.texts() == null || details.texts().isEmpty()) {
            return "";
        }
        List<DxfDetailView.TextRow> rows = new ArrayList<>(details.texts());
        rows.sort(Comparator
                .comparing((DxfDetailView.TextRow t) -> t.layout() == null ? "" : t.layout())
                .thenComparingDouble((DxfDetailView.TextRow t) -> t.y() == null ? 0.0 : -t.y())
                .thenComparingDouble((DxfDetailView.TextRow t) -> t.x() == null ? 0.0 : t.x()));
        StringBuilder sb = new StringBuilder();
        for (DxfDetailView.TextRow row : rows) {
            if (row.content() == null || row.content().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(row.content().trim());
        }
        return sb.toString();
    }

    private static String materialRole(String note) {
        Matcher role = MATERIAL_ROLE.matcher(note == null ? "" : note);
        if (!role.find()) {
            return "";
        }
        // 角色保持英文原文，供 materialEn 拼装；中文留给④
        return role.group(1).replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private static String thicknessOf(String line) {
        if (line == null) {
            return MaterialLineSupport.UNLABELED;
        }
        Matcher m = THICKNESS.matcher(line);
        if (!m.find()) {
            return MaterialLineSupport.UNLABELED;
        }
        String value = m.group(1).replaceAll("\\s+", " ").trim();
        return value.isBlank() ? MaterialLineSupport.UNLABELED : value;
    }

    private static Map<String, Object> jobOf(
            List<DrawingConvertView.NoteLine> notes,
            List<DrawingConvertView.MaterialRow> materials,
            DxfDetailView details,
            String overlayText,
            Map<String, Double> notesNorm) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("notes", notes == null ? List.of() : notes);
        job.put("materials", materials == null ? List.of() : materials);
        job.put("notesBox", notesBox(notes, details));
        job.put("overlayText", overlayText == null ? "" : overlayText);
        job.put("notesNorm", notesNorm == null ? Map.of() : notesNorm);
        return job;
    }

    private static Map<String, Double> notesBox(List<DrawingConvertView.NoteLine> notes, DxfDetailView details) {
        double minx = Double.POSITIVE_INFINITY;
        double miny = Double.POSITIVE_INFINITY;
        double maxx = Double.NEGATIVE_INFINITY;
        double maxy = Double.NEGATIVE_INFINITY;
        if (notes != null) {
            for (DrawingConvertView.NoteLine note : notes) {
                if (note == null || note.x() == null || note.y() == null) {
                    continue;
                }
                minx = Math.min(minx, note.x());
                maxx = Math.max(maxx, note.x());
                miny = Math.min(miny, note.y());
                maxy = Math.max(maxy, note.y());
            }
        }
        if (!Double.isFinite(minx) && details != null && details.texts() != null) {
            for (DxfDetailView.TextRow row : details.texts()) {
                if (row == null || row.content() == null || row.x() == null || row.y() == null) {
                    continue;
                }
                String u = row.content().toUpperCase(Locale.ROOT);
                if (!(u.contains("NOTE") || !noteNo(NOTE_HEAD.matcher(row.content().trim())).isEmpty())) {
                    continue;
                }
                minx = Math.min(minx, row.x());
                maxx = Math.max(maxx, row.x());
                miny = Math.min(miny, row.y());
                maxy = Math.max(maxy, row.y());
            }
        }
        if (!Double.isFinite(minx)) {
            return Map.of();
        }
        Map<String, Double> box = new LinkedHashMap<>();
        box.put("minx", minx);
        box.put("miny", miny);
        box.put("maxx", maxx + 80);
        box.put("maxy", maxy + 8);
        return box;
    }

    private static ConvertReport parseReport(String json) {
        if (json == null || json.isBlank()) {
            return new ConvertReport(List.of("转换脚本没有返回说明"), null);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            Object raw = map.get("warnings");
            List<String> warnings = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !item.toString().isBlank()) {
                        warnings.add(item.toString());
                    }
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> slot = map.get("explosionSlot") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : null;
            return new ConvertReport(warnings, slot);
        } catch (IOException ex) {
            return new ConvertReport(List.of(), null);
        }
    }

    private static Rendered renderConverted(Path dxf, Path dir, String python) {
        try {
            Path pages = dir.resolve("pages");
            Files.createDirectories(pages);
            Path png = dir.resolve("preview.png");
            Path script = dir.resolve("render_dxf.py");
            if (!copyResource(RENDER_SCRIPT, script)) {
                return new Rendered("", "");
            }
            if (!run(python, TIMEOUT_SECONDS, script.toString(), dxf.toString(), pages.toString(), png.toString(), "400")) {
                return new Rendered("", "");
            }
            String pngB64 = "";
            if (Files.isRegularFile(png)) {
                // 终稿预览：更高边长，放大查看仍清晰
                String packed = DwgOriginalPreview.shrinkPngBytes(Files.readAllBytes(png), 4800, 8_000_000);
                pngB64 = packed == null ? "" : packed;
            }
            String svg = "";
            try (var walk = Files.list(pages)) {
                Path first = walk
                        .filter(p -> p.getFileName().toString().startsWith("page-")
                                && p.getFileName().toString().endsWith(".svg"))
                        .sorted()
                        .findFirst()
                        .orElse(null);
                if (first != null) {
                    String markup = Files.readString(first, StandardCharsets.UTF_8);
                    if (markup.contains("<svg") && markup.length() <= 1_200_000) {
                        svg = markup;
                    }
                }
            }
            return new Rendered(pngB64, svg);
        } catch (IOException ex) {
            return new Rendered("", "");
        }
    }

    private static boolean isStackInsertFailure(String warn) {
        if (warn == null || warn.isBlank()) {
            return false;
        }
        return warn.contains("层次 DXF 无法打开")
                || warn.contains("层次 DXF 无图元")
                || warn.contains("层次 DXF 量界失败")
                || warn.contains("层次 DXF 导入失败")
                || warn.contains("层次图（无法插入）");
    }

    private static String previewMessage(Extracted extracted) {
        int notes = extracted.notes.size();
        long untranslated = extracted.notes.stream()
                .filter(n -> NoteGlossary.UNTRANSLATED.equals(n.status()))
                .count();
        int mats = extracted.materials.size();
        String en0 = mats == 1 ? extracted.materials.get(0).materialEn() : "";
        boolean unlabeled = mats == 1 && (en0 == null || en0.isBlank()
                || MaterialLineSupport.UNLABELED.equals(en0.trim()));
        StringBuilder sb = new StringBuilder();
        sb.append("已读出说明 ").append(notes).append(" 条");
        if (untranslated > 0) {
            sb.append("，中文请在翻译步补全（").append(untranslated).append(" 条待译）");
        }
        sb.append("。材料表 ").append(unlabeled ? "未标注" : (mats + " 行"))
                .append("。请在本步核对 NOTE/材料原文，中文在翻译步处理。");
        return sb.toString();
    }

    private static String applyMessage(
            List<DrawingConvertView.NoteLine> notes,
            List<DrawingConvertView.MaterialRow> materials,
            ConvertReport report) {
        StringBuilder sb = new StringBuilder("已生成转换 DXF：视图实体未重画。");
        int noteCount = notes == null ? 0 : notes.size();
        int mats = 0;
        if (materials != null) {
            for (DrawingConvertView.MaterialRow row : materials) {
                if (row != null && "customer_note".equals(row.source())) {
                    mats++;
                }
            }
        }
        sb.append("说明 ").append(noteCount).append(" 条。");
        if (mats == 0) {
            sb.append("预览上的说明是原图线条，这次没有抽出带 MATERIAL 的材料行，左下角只保留空表头。");
        } else {
            sb.append("材料表 ").append(mats).append(" 行，在新图左下角，表头贴边往上排。客户原图放在一侧，未改 NOTE。");
        }
        return sb.toString();
    }

    private static void requireDwg(String fileName) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".dwg")) {
            throw new IllegalArgumentException("图纸转换只接受客户 DWG");
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean copyResource(String resource, Path target) {
        try (InputStream in = DrawingConvertSupport.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static String capture(String command, int timeoutSeconds, String... args) {
        String[] all = new String[args.length + 1];
        all[0] = command;
        System.arraycopy(args, 0, all, 1, args.length);
        ProcessBuilder builder = new ProcessBuilder(all);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(sink);
                } catch (IOException ignored) {
                    // 读不到当失败
                }
            });
            reader.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            reader.join(2000);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            return sink.toString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
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

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private record ConvertReport(List<String> warnings, Map<String, Object> explosionSlot) {
    }

    private record Rendered(String png, String svg) {
    }
}
