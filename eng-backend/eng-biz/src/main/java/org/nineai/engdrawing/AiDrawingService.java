package org.nineai.engdrawing;

import lombok.extern.slf4j.Slf4j;
import org.nineai.common.core.exception.ServiceException;
import org.nineai.common.core.support.RequestIds;
import org.nineai.entity.EngTask;
import org.nineai.service.EngTaskService;
import org.nineai.support.AuthTokenStore;
import org.nineai.support.EngTaskStorage;
import org.nineai.support.LoginContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 工程图主链路：STP 解析、DWG 转换、层次资产、合成。图上没有的栏写未标注。
 */
@Slf4j
@Service
public class AiDrawingService {

    static final long MAX_STEP_BYTES = 80L * 1024 * 1024;
    static final long MAX_PDF_BYTES = 20L * 1024 * 1024;
    private static final Set<String> STEP_EXT = Set.of("stp", "step");

    @Value("${ai-drawing.oda-converter:}")
    private String odaConverter;

    @Value("${ai-drawing.python:python3}")
    private String pythonCommand;

    @Value("${ai-drawing.ocr-base-url:http://127.0.0.1:8868}")
    private String ocrBaseUrl;

    @Value("${ai-drawing.ocr-timeout-seconds:120}")
    private int ocrTimeoutSeconds;

    @Value("${ai-drawing.deepseek-base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${ai-drawing.deepseek-api-key:}")
    private String deepseekApiKey;

    @Value("${ai-drawing.deepseek-model:deepseek-flash}")
    private String deepseekModel;

    @Value("${ai-drawing.storage-dir:data/eng-drawing}")
    private String storageDir;

    private final org.nineai.service.EngLlmConfigService engLlmConfigService;
    private final EngTaskService engTaskService;
    private final EngTaskStorage engTaskStorage;
    private final HeavyJobSlots heavyJobSlots;

    /** 同任务串行合成，避免叠跑 */
    private final ConcurrentHashMap<Long, Object> composeLocks = new ConcurrentHashMap<>();
    /** 同任务串行抽取 */
    private final ConcurrentHashMap<Long, Object> extractLocks = new ConcurrentHashMap<>();
    /** 抽取 Future，供停止时 interrupt */
    private final ConcurrentHashMap<Long, Future<?>> extractFutures = new ConcurrentHashMap<>();

    public AiDrawingService(
            org.nineai.service.EngLlmConfigService engLlmConfigService,
            EngTaskService engTaskService,
            EngTaskStorage engTaskStorage,
            HeavyJobSlots heavyJobSlots) {
        this.engLlmConfigService = engLlmConfigService;
        this.engTaskService = engTaskService;
        this.engTaskStorage = engTaskStorage;
        this.heavyJobSlots = heavyJobSlots;
    }

    private AiDrawingPaddleOcr.Config ocrConfig() {
        return AiDrawingPaddleOcr.Config.of(ocrBaseUrl, ocrTimeoutSeconds);
    }

    /** 优先库内启用模型，否则回退 yml。 */
    private DrawingConvertDeepseek.Config deepseekConfig() {
        if (engLlmConfigService != null) {
            DrawingConvertDeepseek.Config cfg = engLlmConfigService.resolveEnabledConfig();
            if (cfg != null) {
                return cfg;
            }
        }
        return DrawingConvertDeepseek.Config.of(deepseekBaseUrl, deepseekApiKey, deepseekModel);
    }

    public StepScanView scanStep(MultipartFile file) throws IOException {
        String fileName = requireName(file);
        String ext = extension(fileName);
        if (file.getSize() > MAX_STEP_BYTES) {
            throw new IllegalArgumentException("三维图纸超过 80MB：" + fileName);
        }
        String drawingNo = MaterialLineSupport.drawingNo(fileName);
        if (!STEP_EXT.contains(ext)) {
            throw new IllegalArgumentException("三维图纸仅接受 stp / step：" + fileName);
        }
        byte[] bytes = file.getBytes();
        StepTextScan.Result scanned = StepTextScan.scan(bytes);
        if (!scanned.stepFile()) {
            throw new IllegalArgumentException("无法识别为三维图纸：" + fileName);
        }
        StepWireframe.Model wire = StepWireframe.extract(new String(bytes, StandardCharsets.UTF_8));
        boolean assembly = scanned.assemblyUseCount() > 0;
        boolean hasSolid = scanned.solidCount() > 0 || !wire.segments().isEmpty();
        String headerPartNo = headerPartNoOf(scanned.headerFileName());
        return new StepScanView(
                fileName,
                drawingNo,
                scanned.headerFileName(),
                headerPartNo,
                scanned.originatingSystem(),
                scanned.timestamp(),
                scanned.author(),
                scanned.organization(),
                scanned.preprocessorVersion(),
                scanned.authorization(),
                scanned.fileDescription(),
                scanned.implementationLevel(),
                scanned.schema(),
                scanned.protocol(),
                scanned.lengthUnit(),
                scanned.productNames(),
                scanned.productListTruncated(),
                scanned.assemblyNames(),
                scanned.revisions(),
                scanned.layers(),
                scanned.solidCount(),
                scanned.solidEntityIds(),
                scanned.assemblyUseCount(),
                assembly,
                hasSolid,
                false,
                scanned.colors(),
                scanned.properties(),
                scanned.measures(),
                scanned.pmi(),
                StepWireframe.envelope(wire.segments(), scanned.lengthUnit()),
                wire.segments(),
                wire.truncated(),
                stepMessage(drawingNo, headerPartNo, scanned.assemblyUseCount(), hasSolid, wire));
    }

    /** 图纸转换预览：ODA DXF 抽 NOTES，词典预填，材料只认 MATERIAL 行。 */
    public DrawingConvertView.Preview previewConvert(MultipartFile file) throws IOException {
        return previewConvert(file, null);
    }

    /**
     * @param taskId 非空时把源 DXF 落到任务目录，供⑤合成复用（跳过二次 ODA）
     */
    public DrawingConvertView.Preview previewConvert(MultipartFile file, Long taskId) throws IOException {
        String fileName = requireName(file);
        if (!"dwg".equals(extension(fileName))) {
            throw new IllegalArgumentException("图纸转换只接受客户 DWG");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("二维图纸超过 20MB：" + fileName);
        }
        byte[] dwgBytes = file.getBytes();
        long t0 = System.currentTimeMillis();
        log.info("[任务] 抽取开始 taskId={} file={} bytes={}", taskId, fileName, dwgBytes.length);
        DrawingConvertSupport.PreviewResult pr = DrawingConvertSupport.previewResult(
                dwgBytes, fileName, odaConverter, pythonCommand, ocrConfig(), deepseekConfig());
        if (taskId != null && pr.sourceDxfBytes() != null && pr.sourceDxfBytes().length > 0) {
            engTaskService.persistCustomerSourceDxf(taskId, dwgBytes, pr.sourceDxfBytes());
        }
        if (taskId != null && pr.sheetHdPngBytes() != null && pr.sheetHdPngBytes().length >= 32) {
            engTaskService.persistSheetHdPng(taskId, pr.sheetHdPngBytes());
        }
        DrawingConvertView.Preview preview = pr.preview();
        log.info(
                "[任务] 抽取结束 taskId={} file={} totalMs={} notes={} materials={}",
                taskId,
                fileName,
                System.currentTimeMillis() - t0,
                preview == null || preview.notes() == null ? 0 : preview.notes().size(),
                preview == null || preview.materials() == null ? 0 : preview.materials().size());
        return preview;
    }

    /**
     * ① 异步：立即返回 job；后台跑图纸预览或全量抽取。同任务 QUEUED/RUNNING 复用。
     *
     * @param mode previewOnly=仅 ODA+高清图；extract=全量 NOTE/材料抽取（默认）
     */
    public Map<String, Object> startPreviewAsync(MultipartFile file, Long taskId, String mode) throws IOException {
        if (taskId == null) {
            throw new ServiceException(400, "异步抽取须绑定任务（taskId）");
        }
        EngTask task = engTaskService.requireAccessibleTask(taskId);
        String fileName = requireName(file);
        if (!"dwg".equals(extension(fileName))) {
            throw new IllegalArgumentException("图纸转换只接受客户 DWG");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("二维图纸超过 20MB：" + fileName);
        }
        String normalizedMode = ExtractJobSupport.normalizeMode(mode);
        Long userId = task.getUserId();
        Object lock = extractLocks.computeIfAbsent(taskId, id -> new Object());
        synchronized (lock) {
            Map<String, Object> existing = ExtractJobSupport.readJob(engTaskStorage, userId, taskId);
            if (ExtractJobSupport.isActive(existing)) {
                log.info("[任务] 抽取已在进行，复用 job taskId={} jobId={} mode={}",
                        taskId, existing.get("jobId"), existing.get("mode"));
                return existing;
            }
            if (!heavyJobSlots.tryAdmitExtract()) {
                throw new ServiceException(429,
                        "当前处理人数较多，抽取排队已满。请稍等 1～2 分钟后再试（不影响您已保存的任务数据）");
            }
            String jobId = null;
            try {
                byte[] dwgBytes = file.getBytes();
                jobId = ExtractJobSupport.newJobId();
                ExtractJobSupport.beginJob(engTaskStorage, userId, taskId, jobId, fileName, normalizedMode);
                AuthTokenStore.Session session = LoginContext.currentSession();
                String requestId = MDC.get(RequestIds.MDC_REQUEST_ID);
                String username = session == null ? null : session.username();
                int waiting = heavyJobSlots.extractWaitingCount();
                final String extractJobId = jobId;
                final Long extractTaskId = taskId;
                Future<?>[] box = new Future<?>[1];
                box[0] = heavyJobSlots.submit(() -> {
                    try {
                        runExtractJob(
                                session,
                                requestId,
                                username,
                                userId,
                                extractTaskId,
                                extractJobId,
                                dwgBytes,
                                fileName,
                                waiting,
                                normalizedMode);
                    } finally {
                        extractFutures.remove(extractTaskId, box[0]);
                    }
                });
                extractFutures.put(taskId, box[0]);
                log.info(
                        "[任务] 抽取异步已提交 taskId={} jobId={} mode={} waiting≈{}",
                        taskId,
                        jobId,
                        normalizedMode,
                        waiting);
                return ExtractJobSupport.readJob(engTaskStorage, userId, taskId);
            } catch (Exception e) {
                if (jobId != null) {
                    ExtractJobSupport.fail(engTaskStorage, userId, taskId, jobId, "抽取任务提交失败，请重试");
                }
                heavyJobSlots.cancelAdmitExtract();
                throw e;
            }
        }
    }

    /** 兼容旧调用：默认全量抽取。 */
    public Map<String, Object> startPreviewAsync(MultipartFile file, Long taskId) throws IOException {
        return startPreviewAsync(file, taskId, ExtractJobSupport.MODE_EXTRACT);
    }

    public Map<String, Object> previewStatus(Long taskId) {
        if (taskId == null) {
            throw new ServiceException(400, "任务 ID 不能为空");
        }
        EngTask task = engTaskService.requireAccessibleTask(taskId);
        return ExtractJobSupport.readJob(engTaskStorage, task.getUserId(), taskId);
    }

    /** ① 用户停止解析/抽取（QUEUED/RUNNING → CANCELLED，并尝试中断执行线程）。 */
    public Map<String, Object> cancelPreview(Long taskId) {
        if (taskId == null) {
            throw new ServiceException(400, "任务 ID 不能为空");
        }
        EngTask task = engTaskService.requireAccessibleTask(taskId);
        Long userId = task.getUserId();
        Object lock = extractLocks.computeIfAbsent(taskId, id -> new Object());
        synchronized (lock) {
            Map<String, Object> job = ExtractJobSupport.readJob(engTaskStorage, userId, taskId);
            if (!ExtractJobSupport.isActive(job)) {
                String st = job == null ? "" : String.valueOf(job.get("status"));
                if (ExtractJobSupport.STATUS_CANCELLED.equals(st)) {
                    return job;
                }
                throw new ServiceException(400, "当前没有进行中的抽取任务");
            }
            String jobId = job.get("jobId") == null ? "" : String.valueOf(job.get("jobId"));
            ExtractJobSupport.requestCancel(engTaskStorage, userId, taskId, jobId);
            Future<?> future = extractFutures.get(taskId);
            if (future != null) {
                future.cancel(true);
            }
            log.info("[审计] 停止抽取 taskId={} jobId={}", taskId, jobId);
            return ExtractJobSupport.readJob(engTaskStorage, userId, taskId);
        }
    }

    private void runExtractJob(
            AuthTokenStore.Session session,
            String requestId,
            String username,
            Long userId,
            Long taskId,
            String jobId,
            byte[] dwgBytes,
            String fileName,
            int queuedHint,
            String mode) {
        if (session != null) {
            LoginContext.setSession(session);
        }
        if (requestId != null) {
            MDC.put(RequestIds.MDC_REQUEST_ID, requestId);
        }
        if (username != null) {
            MDC.put(RequestIds.MDC_USERNAME, username);
        }
        MDC.put(RequestIds.MDC_JOB_ID, jobId);
        boolean acquired = false;
        boolean sheetOnly = ExtractJobSupport.MODE_PREVIEW_ONLY.equals(mode);
        try {
            if (ExtractJobSupport.isCancelled(engTaskStorage, userId, taskId, jobId)) {
                log.info("[任务] 抽取在排队阶段已取消 taskId={} jobId={}", taskId, jobId);
                return;
            }
            heavyJobSlots.acquireExtract();
            acquired = true;
            if (ExtractJobSupport.isCancelled(engTaskStorage, userId, taskId, jobId)) {
                log.info("[任务] 抽取获槽后已取消 taskId={} jobId={}", taskId, jobId);
                return;
            }
            ExtractJobSupport.markRunning(
                    engTaskStorage,
                    userId,
                    taskId,
                    jobId,
                    "解析图纸结构…",
                    queuedHint > 1
                            ? "前方约有 " + (queuedHint - 1) + " 个任务，即将开始"
                                    + (sheetOnly ? "图纸预览" : "抽取")
                            : "已开始处理，正在" + (sheetOnly ? "生成图纸预览" : "抽取说明与材料"));
            if (ExtractJobSupport.isCancelled(engTaskStorage, userId, taskId, jobId)) {
                return;
            }
            DrawingConvertSupport.ExtractProgress progress = (stage, percent, logLine) ->
                    ExtractJobSupport.progress(engTaskStorage, userId, taskId, jobId, stage, percent, logLine);
            DrawingConvertSupport.PreviewResult pr;
            if (sheetOnly) {
                pr = DrawingConvertSupport.sheetPreviewResult(
                        dwgBytes, fileName, odaConverter, pythonCommand, progress);
            } else {
                pr = DrawingConvertSupport.previewResult(
                        dwgBytes,
                        fileName,
                        odaConverter,
                        pythonCommand,
                        ocrConfig(),
                        deepseekConfig(),
                        progress);
            }
            if (ExtractJobSupport.isCancelled(engTaskStorage, userId, taskId, jobId)) {
                return;
            }
            if (pr.sourceDxfBytes() != null && pr.sourceDxfBytes().length > 0) {
                engTaskService.persistCustomerSourceDxf(taskId, dwgBytes, pr.sourceDxfBytes());
            }
            if (pr.sheetHdPngBytes() != null && pr.sheetHdPngBytes().length >= 32) {
                engTaskService.persistSheetHdPng(taskId, pr.sheetHdPngBytes());
            }
            ExtractJobSupport.succeed(engTaskStorage, userId, taskId, jobId, pr.preview());
            log.info("[任务] 抽取异步完成 taskId={} jobId={} mode={}", taskId, jobId, mode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!ExtractJobSupport.isCancelled(engTaskStorage, userId, taskId, jobId)) {
                ExtractJobSupport.requestCancel(engTaskStorage, userId, taskId, jobId);
            }
            log.info("[任务] 抽取被中断/停止 taskId={} jobId={}", taskId, jobId);
        } catch (IllegalStateException e) {
            if (ExtractJobSupport.CANCEL_SIGNAL.equals(e.getMessage())
                    || ExtractJobSupport.isCancelled(engTaskStorage, userId, taskId, jobId)) {
                log.info("[任务] 抽取已停止 taskId={} jobId={}", taskId, jobId);
            } else {
                ExtractJobSupport.fail(engTaskStorage, userId, taskId, jobId, e.getMessage());
                log.warn("[任务] 抽取异步状态异常 taskId={} jobId={}: {}", taskId, jobId, e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            ExtractJobSupport.fail(engTaskStorage, userId, taskId, jobId, e.getMessage());
            log.warn("[任务] 抽取异步业务失败 taskId={} jobId={} mode={}: {}", taskId, jobId, mode, e.getMessage());
        } catch (Exception e) {
            if (ExtractJobSupport.isCancelled(engTaskStorage, userId, taskId, jobId)) {
                log.info("[任务] 抽取停止过程中结束 taskId={} jobId={}", taskId, jobId);
            } else {
                ExtractJobSupport.fail(
                        engTaskStorage,
                        userId,
                        taskId,
                        jobId,
                        sheetOnly ? "图纸预览失败" : "图纸抽取失败");
                log.error("[任务] 抽取异步异常 taskId={} jobId={} mode={}", taskId, jobId, mode, e);
            }
        } finally {
            if (acquired) {
                heavyJobSlots.releaseExtract();
            } else {
                heavyJobSlots.cancelAdmitExtract();
            }
            LoginContext.clearSession();
            MDC.remove(RequestIds.MDC_JOB_ID);
            MDC.remove(RequestIds.MDC_REQUEST_ID);
            MDC.remove(RequestIds.MDC_USERNAME);
        }
    }

    /** ④ 单条强制重译：语料 + 启用中的 LLM。 */
    public DrawingConvertView.TranslateResult translateLine(DrawingConvertView.TranslateBody body) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new ServiceException(400, "原文为空，无法翻译");
        }
        String kind = body.kind() == null ? "note" : body.kind().trim().toLowerCase(Locale.ROOT);
        boolean material = "material".equals(kind);
        if (!material && !"note".equals(kind) && !kind.isEmpty()) {
            throw new ServiceException(400, "kind 仅支持 note 或 material");
        }
        acquireLightOrBusy("当前翻译人数较多，请稍后再试（可先手工填写译文）");
        try {
            String src = body.text().trim();
            NoteGlossary.Translated tr = NoteTranslateSupport.forceTranslate(src, material, deepseekConfig());
            String zh = tr.translated() == null ? "" : tr.translated().trim();
            if (zh.isEmpty() || NoteGlossary.UNTRANSLATED.equals(tr.status())) {
                throw new ServiceException(400, "未能译出可用中文，请手工填写或检查模型配置");
            }
            // 英文原文却译成一模一样：不算成功（避免短词漏检后原样「译」过）
            if (zh.equalsIgnoreCase(src)
                    && NoteGlossary.looksEnglish(src)
                    && !NoteGlossary.containsCjk(src)
                    && !NoteGlossary.looksKeepAsOriginal(src)) {
                throw new ServiceException(400, "译文与原文相同，未能译出中文，请手工填写或稍后重试");
            }
            String preview = src.length() > 40 ? src.substring(0, 40) + "…" : src;
            log.info("[审计] 单条重译 kind={} status={} preview={}", material ? "material" : "note", tr.status(), preview);
            return new DrawingConvertView.TranslateResult(src, zh, tr.status());
        } finally {
            heavyJobSlots.releaseLight();
        }
    }

    /** ① 框选区域识别：OCR + Vision，kind=note|material。 */
    public DrawingConvertView.RegionExtractResult extractRegion(MultipartFile file, String kind, Long taskId)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(400, "请先框选截图");
        }
        if (file.getSize() > 8L * 1024 * 1024) {
            throw new ServiceException(400, "截图超过 8MB，请缩小选区后重试");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ct.contains("png") && !name.endsWith(".png") && !ct.contains("octet-stream")) {
            throw new ServiceException(400, "截图仅支持 PNG");
        }
        String normalized = normalizeRegionKind(kind);
        if (taskId != null) {
            engTaskService.requireAccessibleTask(taskId);
        }
        byte[] png = file.getBytes();
        byte[] boosted = DwgOriginalPreview.boostCadPreviewPngBytes(png);
        if (boosted != null && boosted.length >= 32) {
            png = boosted;
        }
        acquireLightOrBusy("当前识别人数较多，请稍等几秒再点「识别」（不影响已保存内容）");
        try {
            return finishRegionExtract(png, normalized, taskId, "blob");
        } finally {
            heavyJobSlots.releaseLight();
        }
    }

    /**
     * ① 框选只交归一化坐标，服务端从高清底图裁切再 OCR/Vision。
     * rect：相对整图 0~1（与预览同构图）。
     */
    public DrawingConvertView.RegionExtractResult extractRegionByRect(
            Long taskId, String kind, double x, double y, double w, double h) {
        if (taskId == null) {
            throw new ServiceException(400, "按坐标截图须绑定任务（taskId）");
        }
        engTaskService.requireAccessibleTask(taskId);
        String normalized = normalizeRegionKind(kind);
        byte[] hd = engTaskService.loadSheetHdPng(taskId);
        if (hd == null || hd.length < 32) {
            throw new ServiceException(400, "高清底图未就绪，请先「重新渲图」后再截图抽取");
        }
        byte[] png = DwgOriginalPreview.cropNormalizedRegion(hd, x, y, w, h);
        // 与 blob 上传路径对齐：裁切后再拉对比度，减轻深底灰字漏号
        byte[] boosted = DwgOriginalPreview.boostCadPreviewPngBytes(png);
        if (boosted != null && boosted.length >= 32) {
            png = boosted;
        }
        acquireLightOrBusy("当前识别人数较多，请稍等几秒再点「识别」（不影响已保存内容）");
        try {
            return finishRegionExtract(png, normalized, taskId, "hd-rect");
        } finally {
            heavyJobSlots.releaseLight();
        }
    }

    private static String normalizeRegionKind(String kind) {
        String normalized = kind == null ? "note" : kind.trim().toLowerCase(Locale.ROOT);
        if (!"note".equals(normalized) && !"material".equals(normalized)) {
            throw new ServiceException(400, "kind 仅支持 note 或 material");
        }
        return normalized;
    }

    private DrawingConvertView.RegionExtractResult finishRegionExtract(
            byte[] png, String normalized, Long taskId, String source) {
        DrawingConvertView.RegionExtractResult result =
                DrawingConvertDeepseek.readRegion(png, normalized, deepseekConfig(), ocrConfig());
        int n = "material".equals(result.kind())
                ? (result.materials() == null ? 0 : result.materials().size())
                : (result.notes() == null ? 0 : result.notes().size());
        log.info(
                "[审计] 区域识别 kind={} taskId={} source={} usedVision={} rows={} ocrChars={}",
                result.kind(),
                taskId,
                source,
                result.usedVision(),
                n,
                result.ocrText() == null ? 0 : result.ocrText().length());
        return result;
    }

    /** 核对后出 DXF：不重画视图，不编造材料。 */
    public DrawingConvertView.Apply applyConvert(MultipartFile file, DrawingConvertView.ApplyBody body)
            throws IOException {
        String fileName = requireName(file);
        if (!"dwg".equals(extension(fileName))) {
            throw new IllegalArgumentException("图纸转换只接受客户 DWG");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("二维图纸超过 20MB：" + fileName);
        }
        return DrawingConvertSupport.apply(
                file.getBytes(), fileName, body, odaConverter, pythonCommand, ocrConfig(), deepseekConfig());
    }

    /** 层次爆炸 DXF：ezdxf R2018，格式与图纸转换一致。 */
    public StackExplodeSupport.Export exportStackExplode(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("层次爆炸数据为空");
        }
        acquireLightOrBusy("当前处理人数较多，请稍等几秒再生成层次预览（不影响已编辑内容）");
        try {
            String drawingNo = body.get("drawingNo") == null ? "" : String.valueOf(body.get("drawingNo"));
            String jobJson = StackExplodeSupport.normalizeJobJson(body);
            return StackExplodeSupport.export(jobJson, drawingNo, pythonCommand);
        } finally {
            heavyJobSlots.releaseLight();
        }
    }

    /** 工程图：保存三维层次资产（合成前必须；向导可先抽 2D 再标层次）。 */
    public EngDrawingModels.StackAssetSummary saveEngStack(EngDrawingModels.StackSaveBody body) throws IOException {
        AuthTokenStore.Session session = LoginContext.currentSession();
        Long ownerId = session == null ? null : session.userId();
        acquireLightOrBusy("当前保存层次的人数较多，请稍等几秒再保存（不影响已编辑内容）");
        try {
            return EngDrawingSupport.saveStack(storeRoot(), body, pythonCommand, ownerId);
        } finally {
            heavyJobSlots.releaseLight();
        }
    }

    public List<EngDrawingModels.StackAssetSummary> listEngStacks() throws IOException {
        AuthTokenStore.Session session = LoginContext.currentSession();
        boolean admin = session != null
                && "ADMIN".equalsIgnoreCase(session.role() == null ? "" : session.role());
        Long uid = session == null ? null : session.userId();
        Set<String> bound = engTaskService == null ? Set.of() : engTaskService.accessibleStackAssetIds();
        return EngDrawingSupport.listStacks(storeRoot(), uid, admin, bound);
    }

    public EngDrawingModels.StackAsset loadEngStack(String id) throws IOException {
        assertStackAccess(id);
        return EngDrawingSupport.loadStack(storeRoot(), id);
    }

    public byte[] loadEngStackDxf(String id) throws IOException {
        assertStackAccess(id);
        return EngDrawingSupport.loadStackDxf(storeRoot(), id);
    }

    /** 层次预览 PNG（可选；未保存预览时返回 null）。 */
    public byte[] loadEngStackPreviewPng(String id) throws IOException {
        assertStackAccess(id);
        return EngDrawingSupport.loadStackPreviewPng(storeRoot(), id);
    }

    /** 工程图合成：已保存层次 + 客户 DWG → 覆盖爆炸槽并整页放大。 */
    public EngDrawingModels.ComposeResult composeEngDrawing(
            MultipartFile dwg, String stackAssetId, DrawingConvertView.ApplyBody annot) throws IOException {
        return composeEngDrawing(dwg, stackAssetId, annot, null);
    }

    /**
     * @param taskId 非空时优先读①落盘的源 DXF（哈希匹配），跳过二次 ODA；有审校则跳过 OCR
     */
    public EngDrawingModels.ComposeResult composeEngDrawing(
            MultipartFile dwg,
            String stackAssetId,
            DrawingConvertView.ApplyBody annot,
            Long taskId) throws IOException {
        assertStackAccess(stackAssetId);
        boolean noDwg = dwg == null || dwg.isEmpty();
        boolean skipStack = stackAssetId == null || stackAssetId.isBlank();
        if (noDwg && skipStack) {
            throw new IllegalArgumentException("请上传客户 DWG 或完成三维层次（二维与三维至少保留一个）");
        }
        byte[] dwgBytes = noDwg ? new byte[0] : dwg.getBytes();
        String fileName = noDwg
                ? "stack-only.dwg"
                : (dwg.getOriginalFilename() == null ? "customer.dwg" : dwg.getOriginalFilename());
        return composeEngDrawingBytes(dwgBytes, fileName, stackAssetId, annot, taskId, null, null, null);
    }

    /**
     * ⑤ 异步合成：立即返回 job 状态；后台跑合成。同任务已 RUNNING 则复用（幂等）。
     * taskId 必填（状态按任务落盘，供离页恢复）。
     */
    public Map<String, Object> startComposeAsync(
            MultipartFile dwg,
            String stackAssetId,
            DrawingConvertView.ApplyBody annot,
            Long taskId) throws IOException {
        if (taskId == null) {
            throw new ServiceException(400, "异步合成须绑定任务（taskId）");
        }
        EngTask task = engTaskService.requireAccessibleTask(taskId);
        assertStackAccess(stackAssetId);
        if (annot == null || annot.notes() == null || annot.materials() == null) {
            throw new IllegalArgumentException("请先完成材料核对与翻译审校后再合成工程图");
        }
        boolean noDwg = dwg == null || dwg.isEmpty();
        boolean skipStack = stackAssetId == null || stackAssetId.isBlank();
        if (noDwg && skipStack) {
            throw new IllegalArgumentException("请上传客户 DWG 或完成三维层次（二维与三维至少保留一个）");
        }

        Long userId = task.getUserId();
        Object lock = composeLocks.computeIfAbsent(taskId, id -> new Object());
        synchronized (lock) {
            Map<String, Object> existing = ComposeJobSupport.readJob(engTaskStorage, userId, taskId);
            if (ComposeJobSupport.isActive(existing)) {
                log.info("[任务] 合成已在进行，复用 job taskId={} jobId={}", taskId, existing.get("jobId"));
                return existing;
            }
            if (!heavyJobSlots.tryAdmitCompose()) {
                throw new ServiceException(429,
                        "当前处理人数较多，工程图生成排队已满。请稍等 1～2 分钟后再试（不影响您已保存的任务数据）");
            }
            String jobId = null;
            try {
                byte[] dwgBytes = noDwg ? new byte[0] : dwg.getBytes();
                String fileName = noDwg
                        ? "stack-only.dwg"
                        : (dwg.getOriginalFilename() == null ? "customer.dwg" : dwg.getOriginalFilename());
                jobId = ComposeJobSupport.newJobId();
                ComposeJobSupport.beginJob(engTaskStorage, userId, taskId, jobId, stackAssetId);
                AuthTokenStore.Session session = LoginContext.currentSession();
                String requestId = MDC.get(RequestIds.MDC_REQUEST_ID);
                String username = session == null ? null : session.username();
                int waiting = heavyJobSlots.composeWaitingCount();
                final String composeJobId = jobId;

                heavyJobSlots.execute(() -> runComposeJob(
                        session,
                        requestId,
                        username,
                        userId,
                        taskId,
                        composeJobId,
                        dwgBytes,
                        fileName,
                        stackAssetId,
                        annot));
                log.info("[任务] 合成异步已提交 taskId={} jobId={} stackAssetId={} waiting≈{}",
                        taskId, jobId, stackAssetId, waiting);
                return ComposeJobSupport.readJob(engTaskStorage, userId, taskId);
            } catch (Exception e) {
                if (jobId != null) {
                    ComposeJobSupport.fail(engTaskStorage, userId, taskId, jobId, "工程图生成提交失败，请重试");
                }
                heavyJobSlots.cancelAdmitCompose();
                throw e;
            }
        }
    }

    /** ⑤ 查询合成状态（含日志；SUCCESS 时带 result）。 */
    public Map<String, Object> composeStatus(Long taskId) {
        if (taskId == null) {
            throw new ServiceException(400, "任务 ID 不能为空");
        }
        EngTask task = engTaskService.requireAccessibleTask(taskId);
        return ComposeJobSupport.readJob(engTaskStorage, task.getUserId(), taskId);
    }

    private void runComposeJob(
            AuthTokenStore.Session session,
            String requestId,
            String username,
            Long userId,
            Long taskId,
            String jobId,
            byte[] dwgBytes,
            String fileName,
            String stackAssetId,
            DrawingConvertView.ApplyBody annot) {
        if (session != null) {
            LoginContext.setSession(session);
        }
        if (requestId != null) {
            MDC.put(RequestIds.MDC_REQUEST_ID, requestId);
        }
        if (username != null) {
            MDC.put(RequestIds.MDC_USERNAME, username);
        }
        MDC.put(RequestIds.MDC_JOB_ID, jobId);
        boolean acquired = false;
        try {
            heavyJobSlots.acquireCompose();
            acquired = true;
            ComposeJobSupport.markRunning(
                    engTaskStorage, userId, taskId, jobId, "准备生成…", "已开始生成工程图");
            ComposeJobSupport.progress(
                    engTaskStorage, userId, taskId, jobId, "准备生成…", 12, "正在准备生成参数");
            EngDrawingModels.ComposeResult result = composeEngDrawingBytes(
                    dwgBytes,
                    fileName,
                    stackAssetId,
                    annot,
                    taskId,
                    engTaskStorage,
                    userId,
                    jobId);
            ComposeJobSupport.succeed(engTaskStorage, userId, taskId, jobId, result);
            log.info("[任务] 合成异步完成 taskId={} jobId={}", taskId, jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ComposeJobSupport.fail(engTaskStorage, userId, taskId, jobId, "生成排队被中断，请重试");
        } catch (IllegalArgumentException e) {
            ComposeJobSupport.fail(engTaskStorage, userId, taskId, jobId, e.getMessage());
            log.warn("[任务] 合成异步业务失败 taskId={} jobId={}: {}", taskId, jobId, e.getMessage());
        } catch (Exception e) {
            ComposeJobSupport.fail(engTaskStorage, userId, taskId, jobId, "工程图生成失败");
            log.error("[任务] 合成异步异常 taskId={} jobId={}", taskId, jobId, e);
        } finally {
            if (acquired) {
                heavyJobSlots.releaseCompose();
            } else {
                heavyJobSlots.cancelAdmitCompose();
            }
            LoginContext.clearSession();
            MDC.remove(RequestIds.MDC_JOB_ID);
            MDC.remove(RequestIds.MDC_REQUEST_ID);
            MDC.remove(RequestIds.MDC_USERNAME);
        }
    }

    /**
     * 同步/异步共用：读源 DXF 缓存 → 合成。progressStorage 非空时写阶段进度。
     */
    private EngDrawingModels.ComposeResult composeEngDrawingBytes(
            byte[] dwgBytes,
            String fileName,
            String stackAssetId,
            DrawingConvertView.ApplyBody annot,
            Long taskId,
            EngTaskStorage progressStorage,
            Long progressUserId,
            String jobId) throws IOException {
        assertStackAccess(stackAssetId);
        byte[] sourceDxf = null;
        boolean noDwg = dwgBytes == null || dwgBytes.length == 0;
        if (taskId != null && !noDwg) {
            progress(
                    progressStorage,
                    progressUserId,
                    taskId,
                    jobId,
                    "读取图纸数据…",
                    22,
                    "正在检查已转换的图纸缓存");
            sourceDxf = engTaskService.loadCustomerSourceDxfIfMatch(taskId, dwgBytes);
            if (sourceDxf == null) {
                progress(
                        progressStorage,
                        progressUserId,
                        taskId,
                        jobId,
                        "转换图纸…",
                        30,
                        "未找到缓存，正在转换图纸格式");
                DwgOriginalPreview.DxfBundle bundle =
                        DwgOriginalPreview.toDxf(dwgBytes, odaConverter, pythonCommand);
                if (bundle != null && bundle.dxfText() != null && !bundle.dxfText().isBlank()) {
                    sourceDxf = bundle.dxfText().getBytes(StandardCharsets.ISO_8859_1);
                    engTaskService.persistCustomerSourceDxf(taskId, dwgBytes, sourceDxf);
                    log.info("[任务] 合成前补落源 DXF taskId={} bytes={}", taskId, sourceDxf.length);
                    progress(
                            progressStorage,
                            progressUserId,
                            taskId,
                            jobId,
                            "图纸已转换",
                            38,
                            "图纸格式转换完成");
                }
            } else {
                log.info("[任务] 合成复用源 DXF 缓存 taskId={} bytes={}", taskId, sourceDxf.length);
                progress(
                        progressStorage,
                        progressUserId,
                        taskId,
                        jobId,
                        "复用已有图纸数据",
                        35,
                        "已复用上次转换的图纸数据");
            }
        } else if (noDwg) {
            progress(
                    progressStorage,
                    progressUserId,
                    taskId,
                    jobId,
                    "准备三维层次合成…",
                    28,
                    "无客户二维，将仅生成图框与层次");
        }
        progress(
                progressStorage,
                progressUserId,
                taskId,
                jobId,
                "叠加层次与生成工程图…",
                48,
                "正在叠加三维层次并生成工程图");
        long t0 = System.currentTimeMillis();
        EngDrawingModels.ComposeResult result = EngDrawingSupport.compose(
                storeRoot(),
                dwgBytes,
                fileName,
                stackAssetId,
                annot,
                odaConverter,
                pythonCommand,
                ocrConfig(),
                deepseekConfig(),
                sourceDxf);
        long ms = System.currentTimeMillis() - t0;
        log.info("[任务] 合成完成 taskId={} stackAssetId={} ms={} cachedDxf={}",
                taskId, stackAssetId, ms, sourceDxf != null);
        progress(
                progressStorage,
                progressUserId,
                taskId,
                jobId,
                "保存预览结果…",
                90,
                "工程图已生成，正在保存预览");
        return result;
    }

    private static void progress(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            String jobId,
            String stage,
            int percent,
            String logLine) {
        if (storage == null || userId == null || taskId == null || jobId == null) {
            return;
        }
        ComposeJobSupport.progress(storage, userId, taskId, jobId, stage, percent, logLine);
    }

    private void acquireLightOrBusy(String tip) {
        if (!heavyJobSlots.tryAcquireLight()) {
            throw new ServiceException(429, tip);
        }
    }

    private void assertStackAccess(String stackAssetId) throws IOException {
        if (stackAssetId == null || stackAssetId.isBlank()) {
            return;
        }
        if (engTaskService == null) {
            return;
        }
        Long owner = EngDrawingSupport.readOwnerUserId(storeRoot(), stackAssetId);
        engTaskService.assertStackAssetAccessible(stackAssetId, owner);
    }

    private Path storeRoot() {
        return Path.of(storageDir).toAbsolutePath().normalize();
    }

    private static String headerPartNoOf(String headerFileName) {
        if (headerFileName == null || MaterialLineSupport.UNLABELED.equals(headerFileName)) {
            return MaterialLineSupport.UNLABELED;
        }
        String partNo = MaterialLineSupport.drawingNo(headerFileName);
        return partNo == null || partNo.isBlank() ? MaterialLineSupport.UNLABELED : partNo;
    }

    private static String stepMessage(
            String drawingNo,
            String headerPartNo,
            int assemblyUseCount,
            boolean hasSolid,
            StepWireframe.Model wire) {
        StringBuilder message = new StringBuilder("图号按上传文件名「").append(drawingNo).append("」。");
        if (headerPartNo != null
                && !MaterialLineSupport.UNLABELED.equals(headerPartNo)
                && !headerPartNo.equals(drawingNo)) {
            message.append("文件头另有料号「").append(headerPartNo).append("」，不作为图号。");
        }
        if (hasSolid && !wire.segments().isEmpty()) {
            message.append("已读出实体，预览为文件几何。请用 Z 展开拉开层次后保存。");
        } else if (hasSolid) {
            message.append("读到实体记录，但没有生成线框。");
        } else {
            message.append("没有读出实体。");
        }
        if (wire.truncated()) {
            message.append("线框只显示一部分。");
        }
        // 无总装坐标时不画假装配爆炸；层次 Z 展开仍可用，勿写成失败语气
        if (assemblyUseCount > 0) {
            message.append("含装配引用 ").append(assemblyUseCount).append(" 条（无零件坐标时不做装配爆炸）。");
        }
        return message.toString();
    }

    private static String requireName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请先选择图纸");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("图纸缺少文件名");
        }
        return name;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
