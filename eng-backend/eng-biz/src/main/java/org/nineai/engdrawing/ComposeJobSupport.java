package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.nineai.support.EngTaskStorage;
import org.nineai.support.LargeJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ⑤ 合成异步任务：按任务目录落盘状态/日志/产物，支持离页再进续跟。
 */
@Slf4j
public final class ComposeJobSupport {

    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    /** RUNNING 超过此时长视为僵死（进程重启或线程挂死） */
    public static final long STALE_RUNNING_MS = 25L * 60 * 1000;
    private static final int MAX_LOGS = 80;

    private static final ObjectMapper MAPPER = LargeJson.mapper();

    private ComposeJobSupport() {
    }

    public static String newJobId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 读任务合成状态；无文件返回 IDLE；RUNNING 超时则标 FAILED。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readJob(EngTaskStorage storage, Long userId, Long taskId) {
        Map<String, Object> idle = idleView(taskId);
        if (storage == null || userId == null || taskId == null) {
            return idle;
        }
        try {
            byte[] bytes = storage.readTaskFile(userId, taskId, EngTaskStorage.COMPOSE_JOB_JSON);
            if (bytes == null || bytes.length == 0) {
                return idle;
            }
            Map<String, Object> job = MAPPER.readValue(bytes, Map.class);
            if (job == null || job.isEmpty()) {
                return idle;
            }
            String status = str(job.get("status"));
            if ((STATUS_RUNNING.equals(status) || STATUS_QUEUED.equals(status)) && isStale(job)) {
                long now = System.currentTimeMillis();
                long started = longOf(job.get("startedAt"), 0L);
                long elapsed = started > 0 ? Math.max(0L, now - started) : 0L;
                appendLog(job, "[已用 " + formatElapsed(elapsed) + "] 生成超时未完成，请重新生成");
                job.put("status", STATUS_FAILED);
                job.put("stage", "失败");
                job.put("percent", 100);
                job.put("finishedAt", now);
                job.put("elapsedMs", elapsed);
                job.put("message", "生成超时未完成，请重新生成");
                writeJob(storage, userId, taskId, job);
                log.warn("[任务] 合成任务僵死已标记失败 taskId={} jobId={}", taskId, job.get("jobId"));
            }
            return sanitizeForApi(job, storage, userId, taskId);
        } catch (Exception e) {
            log.warn("[任务] 读合成任务状态失败 taskId={}: {}", taskId, e.getMessage());
            return idle;
        }
    }

    public static Map<String, Object> idleView(Long taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId);
        m.put("jobId", "");
        m.put("status", STATUS_IDLE);
        m.put("stage", "");
        m.put("percent", 0);
        m.put("startedAt", 0L);
        m.put("finishedAt", null);
        m.put("elapsedMs", 0L);
        m.put("message", "");
        m.put("logs", List.of());
        m.put("result", null);
        return m;
    }

    public static Map<String, Object> beginJob(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            String jobId,
            String stackAssetId) throws IOException {
        long now = System.currentTimeMillis();
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("taskId", taskId);
        job.put("jobId", jobId);
        job.put("stackAssetId", stackAssetId == null ? "" : stackAssetId);
        job.put("status", STATUS_QUEUED);
        job.put("stage", "多人共用处理通道，排队等待中…");
        job.put("percent", 4);
        job.put("startedAt", now);
        job.put("lastProgressAt", now);
        job.put("finishedAt", null);
        job.put("elapsedMs", 0L);
        job.put("message", "工程图生成已提交，等待执行");
        List<String> logs = new ArrayList<>();
        logs.add(ts() + " [已用 00:00] 已提交工程图生成，等待处理");
        job.put("logs", logs);
        writeJob(storage, userId, taskId, job);
        // 新开跑时清旧产物，避免误读
        storage.deleteTaskFile(userId, taskId, EngTaskStorage.COMPOSE_RESULT_JSON);
        storage.deleteTaskFile(userId, taskId, EngTaskStorage.COMPOSE_RESULT_DXF);
        return job;
    }

    public static synchronized void markRunning(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            String jobId,
            String stage,
            String logLine) {
        try {
            Map<String, Object> job = loadRaw(storage, userId, taskId);
            if (job == null) {
                return;
            }
            if (jobId != null && !jobId.equals(str(job.get("jobId")))) {
                return;
            }
            String status = str(job.get("status"));
            if (!STATUS_QUEUED.equals(status) && !STATUS_RUNNING.equals(status)) {
                return;
            }
            job.put("status", STATUS_RUNNING);
            if (stage != null && !stage.isBlank()) {
                job.put("stage", stage);
            }
            job.put("percent", Math.max(intOf(job.get("percent"), 0), 12));
            if (logLine != null && !logLine.isBlank()) {
                long started = longOf(job.get("startedAt"), 0L);
                long elapsed = started > 0 ? Math.max(0L, System.currentTimeMillis() - started) : 0L;
                job.put("elapsedMs", elapsed);
                appendLog(job, "[已用 " + formatElapsed(elapsed) + "] " + logLine);
            }
            writeJob(storage, userId, taskId, job);
        } catch (Exception e) {
            log.warn("[任务] 合成标记 RUNNING 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    public static synchronized void progress(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            String jobId,
            String stage,
            int percent,
            String logLine) {
        try {
            Map<String, Object> job = loadRaw(storage, userId, taskId);
            if (job == null) {
                return;
            }
            if (jobId != null && !jobId.equals(str(job.get("jobId")))) {
                return;
            }
            if (!STATUS_RUNNING.equals(str(job.get("status")))) {
                return;
            }
            long now = System.currentTimeMillis();
            long started = longOf(job.get("startedAt"), 0L);
            if (started <= 0) {
                started = now;
                job.put("startedAt", started);
            }
            long lastAt = longOf(job.get("lastProgressAt"), started);
            long elapsed = Math.max(0L, now - started);
            long segment = Math.max(0L, now - lastAt);
            job.put("lastProgressAt", now);
            job.put("elapsedMs", elapsed);
            if (stage != null && !stage.isBlank()) {
                job.put("stage", stage);
            }
            if (percent >= 0) {
                int cur = intOf(job.get("percent"), 0);
                job.put("percent", Math.max(cur, Math.min(99, percent)));
            }
            if (logLine != null && !logLine.isBlank()) {
                appendLog(job, "[已用 " + formatElapsed(elapsed) + " · 本段 " + formatElapsed(segment) + "] " + logLine);
            }
            writeJob(storage, userId, taskId, job);
        } catch (Exception e) {
            log.warn("[任务] 写合成进度失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    public static synchronized void succeed(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            String jobId,
            EngDrawingModels.ComposeResult result) throws IOException {
        Map<String, Object> job = loadRaw(storage, userId, taskId);
        if (job == null) {
            job = new LinkedHashMap<>();
            job.put("taskId", taskId);
            job.put("jobId", jobId);
        }
        if (jobId != null && !jobId.isBlank() && !jobId.equals(str(job.get("jobId")))) {
            return;
        }
        persistComposeProduct(storage, userId, taskId, result);
        long now = System.currentTimeMillis();
        long started = longOf(job.get("startedAt"), 0L);
        long elapsed = started > 0 ? Math.max(0L, now - started) : 0L;
        job.put("status", STATUS_SUCCESS);
        job.put("stage", "完成");
        job.put("percent", 100);
        job.put("finishedAt", now);
        job.put("elapsedMs", elapsed);
        job.put("message", result == null || result.message() == null ? "工程图已生成" : result.message());
        appendLog(job, "[已用 " + formatElapsed(elapsed) + "] 工程图生成完成");
        writeJob(storage, userId, taskId, job);
    }

    public static synchronized void fail(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            String jobId,
            String errorMessage) {
        try {
            Map<String, Object> job = loadRaw(storage, userId, taskId);
            if (job == null) {
                job = new LinkedHashMap<>();
                job.put("taskId", taskId);
                job.put("jobId", jobId == null ? "" : jobId);
                job.put("startedAt", System.currentTimeMillis());
                job.put("logs", new ArrayList<String>());
            }
            if (jobId != null && !jobId.isBlank() && !jobId.equals(str(job.get("jobId")))) {
                return;
            }
            long now = System.currentTimeMillis();
            long started = longOf(job.get("startedAt"), 0L);
            long elapsed = started > 0 ? Math.max(0L, now - started) : 0L;
            String msg = errorMessage == null || errorMessage.isBlank() ? "工程图生成失败" : errorMessage;
            job.put("status", STATUS_FAILED);
            job.put("stage", "失败");
            job.put("percent", 100);
            job.put("finishedAt", now);
            job.put("elapsedMs", elapsed);
            job.put("message", msg);
            appendLog(job, "[已用 " + formatElapsed(elapsed) + "] 失败：" + msg);
            writeJob(storage, userId, taskId, job);
        } catch (Exception e) {
            log.warn("[任务] 写合成失败状态失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    public static boolean isRunning(Map<String, Object> job) {
        return job != null && STATUS_RUNNING.equals(str(job.get("status")));
    }

    /** 排队或执行中，同任务提交应复用。 */
    public static boolean isActive(Map<String, Object> job) {
        if (job == null) {
            return false;
        }
        String status = str(job.get("status"));
        return STATUS_RUNNING.equals(status) || STATUS_QUEUED.equals(status);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadRaw(EngTaskStorage storage, Long userId, Long taskId)
            throws IOException {
        byte[] bytes = storage.readTaskFile(userId, taskId, EngTaskStorage.COMPOSE_JOB_JSON);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return MAPPER.readValue(bytes, Map.class);
    }

    private static void writeJob(EngTaskStorage storage, Long userId, Long taskId, Map<String, Object> job)
            throws IOException {
        storage.writeBytes(
                userId,
                taskId,
                EngTaskStorage.COMPOSE_JOB_JSON,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(job));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeForApi(
            Map<String, Object> job, EngTaskStorage storage, Long userId, Long taskId) {
        Map<String, Object> out = new LinkedHashMap<>(job);
        Object logs = out.get("logs");
        if (!(logs instanceof List)) {
            out.put("logs", List.of());
        }
        out.put("elapsedMs", computeElapsedMs(out));
        out.put("result", null);
        if (STATUS_SUCCESS.equals(str(out.get("status")))) {
            try {
                Map<String, Object> resultView = loadResultForApi(storage, userId, taskId);
                if (resultView != null) {
                    out.put("result", resultView);
                }
            } catch (Exception e) {
                log.warn("[任务] 读合成产物失败 taskId={}: {}", taskId, e.getMessage());
            }
        }
        return out;
    }

    /**
     * DXF 落独立文件，JSON 只留元数据与预览。旧 JSON 内嵌 Base64 时拆出并瘦身。
     */
    static void persistComposeProduct(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            EngDrawingModels.ComposeResult result) throws IOException {
        if (result == null) {
            return;
        }
        writeComposeDxfIfPresent(storage, userId, taskId, result.dxfBase64());
        storage.writeBytes(
                userId,
                taskId,
                EngTaskStorage.COMPOSE_RESULT_JSON,
                MAPPER.writeValueAsBytes(stripDxfBase64(result)));
    }

    private static Map<String, Object> loadResultForApi(EngTaskStorage storage, Long userId, Long taskId)
            throws IOException {
        byte[] resultBytes = storage.readTaskFile(userId, taskId, EngTaskStorage.COMPOSE_RESULT_JSON);
        if (resultBytes == null || resultBytes.length == 0) {
            return null;
        }
        EngDrawingModels.ComposeResult result =
                MAPPER.readValue(resultBytes, EngDrawingModels.ComposeResult.class);
        boolean wroteSidecar = migrateEmbeddedDxf(storage, userId, taskId, result);
        if (wroteSidecar) {
            result = stripDxfBase64(result);
            storage.writeBytes(
                    userId,
                    taskId,
                    EngTaskStorage.COMPOSE_RESULT_JSON,
                    MAPPER.writeValueAsBytes(result));
        }
        boolean hasDxf = storage.existsTaskFile(userId, taskId, EngTaskStorage.COMPOSE_RESULT_DXF)
                || (result.dxfBase64() != null && !result.dxfBase64().isBlank());
        return toApiResult(result, hasDxf);
    }

    /** 完成任务/下载前确保 sidecar 存在（兼容旧 compose-result.json）。 */
    public static boolean ensureComposeDxf(EngTaskStorage storage, Long userId, Long taskId) {
        if (storage == null || userId == null || taskId == null) {
            return false;
        }
        if (storage.existsTaskFile(userId, taskId, EngTaskStorage.COMPOSE_RESULT_DXF)) {
            return true;
        }
        try {
            loadResultForApi(storage, userId, taskId);
        } catch (Exception e) {
            log.warn("[任务] 拆出合成 DXF 失败 taskId={}: {}", taskId, e.getMessage());
        }
        return storage.existsTaskFile(userId, taskId, EngTaskStorage.COMPOSE_RESULT_DXF);
    }

    private static boolean migrateEmbeddedDxf(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            EngDrawingModels.ComposeResult result) throws IOException {
        if (storage.existsTaskFile(userId, taskId, EngTaskStorage.COMPOSE_RESULT_DXF)) {
            return result.dxfBase64() != null && !result.dxfBase64().isBlank();
        }
        return writeComposeDxfIfPresent(storage, userId, taskId, result.dxfBase64());
    }

    private static boolean writeComposeDxfIfPresent(
            EngTaskStorage storage, Long userId, Long taskId, String dxfBase64) throws IOException {
        if (dxfBase64 == null || dxfBase64.isBlank()) {
            return false;
        }
        storage.writeBytes(userId, taskId, EngTaskStorage.COMPOSE_RESULT_DXF, decodeBase64Payload(dxfBase64));
        return true;
    }

    private static EngDrawingModels.ComposeResult stripDxfBase64(EngDrawingModels.ComposeResult result) {
        if (result == null || result.dxfBase64() == null || result.dxfBase64().isBlank()) {
            return result;
        }
        return new EngDrawingModels.ComposeResult(
                result.fileName(),
                result.drawingNo(),
                result.stackAssetId(),
                result.message(),
                result.warnings(),
                result.previewPngBase64(),
                result.previewSvg(),
                result.zipBase64(),
                result.dxfFileName(),
                "",
                result.explosionSlot());
    }

    private static Map<String, Object> toApiResult(EngDrawingModels.ComposeResult result, boolean hasDxf) {
        @SuppressWarnings("unchecked")
        Map<String, Object> view = MAPPER.convertValue(stripDxfBase64(result), Map.class);
        view.put("dxfBase64", "");
        view.put("hasDxf", hasDxf);
        return view;
    }

    private static byte[] decodeBase64Payload(String raw) {
        String payload = raw.trim();
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        return Base64.getDecoder().decode(payload);
    }

    /** RUNNING 用当前时刻；终态用 finishedAt；供前端计时器，避免客户端时钟偏差卡在 00:00。 */
    private static long computeElapsedMs(Map<String, Object> job) {
        long started = longOf(job.get("startedAt"), 0L);
        if (started <= 0) {
            return Math.max(0L, longOf(job.get("elapsedMs"), 0L));
        }
        String status = str(job.get("status"));
        if (STATUS_RUNNING.equals(status) || STATUS_QUEUED.equals(status)) {
            return Math.max(0L, System.currentTimeMillis() - started);
        }
        long finished = longOf(job.get("finishedAt"), 0L);
        if (finished > started) {
            return finished - started;
        }
        return Math.max(0L, longOf(job.get("elapsedMs"), 0L));
    }

    /** 日志用 mm:ss（超过 1 小时则 h:mm:ss）。 */
    static String formatElapsed(long ms) {
        long sec = Math.max(0L, ms / 1000L);
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    @SuppressWarnings("unchecked")
    private static void appendLog(Map<String, Object> job, String line) {
        List<String> logs;
        Object raw = job.get("logs");
        if (raw instanceof List<?> list) {
            logs = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    logs.add(String.valueOf(o));
                }
            }
        } else {
            logs = new ArrayList<>();
        }
        logs.add(ts() + " " + line);
        while (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
        job.put("logs", logs);
    }

    private static boolean isStale(Map<String, Object> job) {
        long started = longOf(job.get("startedAt"), 0L);
        return started > 0 && System.currentTimeMillis() - started > STALE_RUNNING_MS;
    }

    private static String ts() {
        java.time.LocalTime t = java.time.LocalTime.now().withNano(0);
        return t.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longOf(Object o, long def) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? def : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
