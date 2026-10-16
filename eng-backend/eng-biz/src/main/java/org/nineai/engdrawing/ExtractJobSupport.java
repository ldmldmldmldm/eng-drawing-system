package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.nineai.support.EngTaskStorage;
import org.nineai.support.LargeJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ① 抽取异步任务：排队/进度/预览产物落盘，离页可续跟。
 */
@Slf4j
public final class ExtractJobSupport {

    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** progress / 回调检测到用户停止时抛出，文案固定便于识别 */
    public static final String CANCEL_SIGNAL = "EXTRACT_CANCELLED";

    public static final long STALE_RUNNING_MS = 25L * 60 * 1000;
    private static final int MAX_LOGS = 80;

    private static final ObjectMapper MAPPER = LargeJson.mapper();

    private ExtractJobSupport() {
    }

    public static String newJobId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readJob(EngTaskStorage storage, Long userId, Long taskId) {
        Map<String, Object> idle = idleView(taskId);
        if (storage == null || userId == null || taskId == null) {
            return idle;
        }
        try {
            byte[] bytes = storage.readTaskFile(userId, taskId, EngTaskStorage.EXTRACT_JOB_JSON);
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
                appendLog(job, "[已用 " + formatElapsed(elapsed) + "] 处理超时未完成，请重新解析");
                job.put("status", STATUS_FAILED);
                job.put("stage", "失败");
                job.put("percent", 100);
                job.put("finishedAt", now);
                job.put("elapsedMs", elapsed);
                job.put("message", "处理超时未完成，请重新解析");
                writeJob(storage, userId, taskId, job);
                log.warn("[任务] 抽取任务僵死已标记失败 taskId={} jobId={}", taskId, job.get("jobId"));
            }
            return sanitizeForApi(job, storage, userId, taskId);
        } catch (Exception e) {
            log.warn("[任务] 读抽取任务状态失败 taskId={}: {}", taskId, e.getMessage());
            return idle;
        }
    }

    public static Map<String, Object> idleView(Long taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId);
        m.put("jobId", "");
        m.put("status", STATUS_IDLE);
        m.put("mode", "");
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

    /**
     * @param mode previewOnly（仅高清预览）或 extract（全量抽取）；其它按 extract
     */
    public static Map<String, Object> beginJob(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            String jobId,
            String fileName,
            String mode) throws IOException {
        String normalized = normalizeMode(mode);
        long now = System.currentTimeMillis();
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("taskId", taskId);
        job.put("jobId", jobId);
        job.put("fileName", fileName == null ? "" : fileName);
        job.put("mode", normalized);
        job.put("status", STATUS_QUEUED);
        job.put("stage", "多人共用处理通道，排队等待中…");
        job.put("percent", 4);
        job.put("startedAt", now);
        job.put("lastProgressAt", now);
        job.put("finishedAt", null);
        job.put("elapsedMs", 0L);
        boolean sheetOnly = MODE_PREVIEW_ONLY.equals(normalized);
        job.put("message", sheetOnly ? "图纸解析已提交，等待执行" : "抽取已提交，等待执行");
        List<String> logs = new ArrayList<>();
        logs.add(ts() + " [已用 00:00] "
                + (sheetOnly ? "已提交图纸预览，等待处理" : "已提交抽取，等待处理"));
        job.put("logs", logs);
        writeJob(storage, userId, taskId, job);
        storage.deleteTaskFile(userId, taskId, EngTaskStorage.EXTRACT_RESULT_JSON);
        return job;
    }

    public static final String MODE_PREVIEW_ONLY = "previewOnly";
    public static final String MODE_EXTRACT = "extract";

    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_EXTRACT;
        }
        String m = mode.trim();
        if (MODE_PREVIEW_ONLY.equalsIgnoreCase(m) || "sheet".equalsIgnoreCase(m) || "preview".equalsIgnoreCase(m)) {
            return MODE_PREVIEW_ONLY;
        }
        return MODE_EXTRACT;
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
            if (STATUS_CANCELLED.equals(status)) {
                return;
            }
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
            log.warn("[任务] 抽取标记 RUNNING 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 用户停止：QUEUED/RUNNING → CANCELLED。返回是否成功标记。
     */
    public static synchronized boolean requestCancel(
            EngTaskStorage storage, Long userId, Long taskId, String jobId) {
        try {
            Map<String, Object> job = loadRaw(storage, userId, taskId);
            if (job == null) {
                return false;
            }
            if (jobId != null && !jobId.isBlank() && !jobId.equals(str(job.get("jobId")))) {
                return false;
            }
            String status = str(job.get("status"));
            if (!STATUS_QUEUED.equals(status) && !STATUS_RUNNING.equals(status)) {
                return STATUS_CANCELLED.equals(status);
            }
            long now = System.currentTimeMillis();
            long started = longOf(job.get("startedAt"), 0L);
            long elapsed = started > 0 ? Math.max(0L, now - started) : 0L;
            job.put("status", STATUS_CANCELLED);
            job.put("stage", "已停止");
            job.put("percent", intOf(job.get("percent"), 0));
            job.put("finishedAt", now);
            job.put("elapsedMs", elapsed);
            job.put("message", "已停止抽取");
            appendLog(job, "[已用 " + formatElapsed(elapsed) + "] 已停止抽取");
            writeJob(storage, userId, taskId, job);
            log.info("[任务] 抽取已标记取消 taskId={} jobId={}", taskId, job.get("jobId"));
            return true;
        } catch (Exception e) {
            log.warn("[任务] 标记抽取取消失败 taskId={}: {}", taskId, e.getMessage());
            return false;
        }
    }

    public static boolean isCancelled(EngTaskStorage storage, Long userId, Long taskId, String jobId) {
        try {
            Map<String, Object> job = loadRaw(storage, userId, taskId);
            if (job == null) {
                return false;
            }
            if (jobId != null && !jobId.isBlank() && !jobId.equals(str(job.get("jobId")))) {
                return false;
            }
            return STATUS_CANCELLED.equals(str(job.get("status")));
        } catch (Exception e) {
            return false;
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
            String status = str(job.get("status"));
            if (STATUS_CANCELLED.equals(status)) {
                throw new IllegalStateException(CANCEL_SIGNAL);
            }
            if (!STATUS_RUNNING.equals(status)) {
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
            log.warn("[任务] 写抽取进度失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    public static synchronized void succeed(
            EngTaskStorage storage,
            Long userId,
            Long taskId,
            String jobId,
            DrawingConvertView.Preview preview) throws IOException {
        Map<String, Object> job = loadRaw(storage, userId, taskId);
        if (job == null) {
            job = new LinkedHashMap<>();
            job.put("taskId", taskId);
            job.put("jobId", jobId);
        }
        if (jobId != null && !jobId.isBlank() && !jobId.equals(str(job.get("jobId")))) {
            return;
        }
        if (STATUS_CANCELLED.equals(str(job.get("status")))) {
            return;
        }
        storage.writeBytes(userId, taskId, EngTaskStorage.EXTRACT_RESULT_JSON, MAPPER.writeValueAsBytes(preview));
        long now = System.currentTimeMillis();
        long started = longOf(job.get("startedAt"), 0L);
        long elapsed = started > 0 ? Math.max(0L, now - started) : 0L;
        job.put("status", STATUS_SUCCESS);
        job.put("stage", "完成");
        job.put("percent", 100);
        job.put("finishedAt", now);
        job.put("elapsedMs", elapsed);
        job.put("message", preview == null || preview.message() == null ? "图纸抽取完成" : preview.message());
        boolean sheetOnly = preview != null && Boolean.TRUE.equals(preview.sheetReady());
        appendLog(job, "[已用 " + formatElapsed(elapsed) + "] " + (sheetOnly ? "图纸预览完成" : "抽取完成"));
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
            if (STATUS_CANCELLED.equals(str(job.get("status")))) {
                return;
            }
            long now = System.currentTimeMillis();
            long started = longOf(job.get("startedAt"), 0L);
            long elapsed = started > 0 ? Math.max(0L, now - started) : 0L;
            String msg = errorMessage == null || errorMessage.isBlank() ? "图纸抽取失败" : errorMessage;
            job.put("status", STATUS_FAILED);
            job.put("stage", "失败");
            job.put("percent", 100);
            job.put("finishedAt", now);
            job.put("elapsedMs", elapsed);
            job.put("message", msg);
            appendLog(job, "[已用 " + formatElapsed(elapsed) + "] 失败：" + msg);
            writeJob(storage, userId, taskId, job);
        } catch (Exception e) {
            log.warn("[任务] 写抽取失败状态失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

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
        byte[] bytes = storage.readTaskFile(userId, taskId, EngTaskStorage.EXTRACT_JOB_JSON);
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
                EngTaskStorage.EXTRACT_JOB_JSON,
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
                byte[] resultBytes = storage.readTaskFile(userId, taskId, EngTaskStorage.EXTRACT_RESULT_JSON);
                if (resultBytes != null && resultBytes.length > 0) {
                    out.put("result", MAPPER.readValue(resultBytes, DrawingConvertView.Preview.class));
                }
            } catch (Exception e) {
                log.warn("[任务] 读抽取产物失败 taskId={}: {}", taskId, e.getMessage());
            }
        }
        return out;
    }

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
