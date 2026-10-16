package org.nineai.service;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nineai.common.core.exception.ServiceException;
import org.nineai.dto.RuntimeLogDtos;
import org.nineai.support.AuthTokenStore;
import org.nineai.support.LoginContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行日志只读查看：读 logging.file.path 白名单文件；仅 ADMIN。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeLogService {

    public enum LogSource {
        ALL("eng-backend.log"),
        ERROR("eng-backend-error.log"),
        BIZ("eng-backend-biz.log");

        private final String fileName;

        LogSource(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }

        static LogSource from(String raw) {
            String key = StrUtil.blankToDefault(raw, "all").trim().toLowerCase(Locale.ROOT);
            return switch (key) {
                case "error" -> ERROR;
                case "biz" -> BIZ;
                case "all", "full" -> ALL;
                default -> throw new ServiceException(400, "日志源仅支持 all / error / biz");
            };
        }
    }

    private static final String ROLE_ADMIN = "ADMIN";
    private static final int DEFAULT_LINES = 200;
    private static final int MAX_LINES = 2000;
    private static final int MAX_STREAM_CONCURRENT = 3;
    private static final long HEARTBEAT_MS = 15_000L;
    private static final long POLL_MS = 800L;
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    /** logback %-5level：前后为空格或边界 */
    private static final Pattern LEVEL_PATTERN = Pattern.compile(
            "(?:^|\\s)(ERROR|WARN|INFO|DEBUG|TRACE)\\s");
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|appsecret|authorization)\\s*[=:]\\s*[^\\s,;]+");

    private static final Map<LogSource, String> SOURCE_LABEL = new EnumMap<>(LogSource.class);

    static {
        SOURCE_LABEL.put(LogSource.ALL, "全量");
        SOURCE_LABEL.put(LogSource.ERROR, "ERROR");
        SOURCE_LABEL.put(LogSource.BIZ, "业务摘要");
    }

    @Value("${logging.file.path:./logs}")
    private String logPath;

    private final OperationLogService operationLogService;
    private final Semaphore streamSlots = new Semaphore(MAX_STREAM_CONCURRENT, true);
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "runtime-log-sse");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdownNow();
    }

    public RuntimeLogDtos.TailResult tail(String sourceRaw, Integer lines, String levelRaw, String keyword) {
        requireAdmin();
        LogSource source = LogSource.from(sourceRaw);
        int want = normalizeLines(lines);
        String level = normalizeLevel(levelRaw);
        String kw = StrUtil.trimToEmpty(keyword);

        Path file = resolveLogFile(source);
        RuntimeLogDtos.TailResult result = new RuntimeLogDtos.TailResult();
        result.setSource(source.name().toLowerCase(Locale.ROOT));
        result.setFileName(source.fileName());

        if (!Files.isRegularFile(file)) {
            result.setExists(false);
            result.setFileSize(0);
            result.setLineCount(0);
            result.setMessage("日志文件不存在（当前环境可能未落盘，生产 prod 才有文件日志）。路径："
                    + file.toAbsolutePath().normalize());
            return result;
        }

        try {
            long size = Files.size(file);
            result.setExists(true);
            result.setFileSize(size);
            List<String> raw = readLastLines(file, Math.min(want * 8, MAX_LINES * 4));
            List<String> filtered = filterLines(raw, level, kw, want);
            result.setLines(filtered);
            result.setLineCount(filtered.size());
            if (filtered.isEmpty() && !raw.isEmpty()) {
                result.setMessage("最近日志中无匹配行，可放宽级别或关键词后重试");
            }
            return result;
        } catch (IOException ex) {
            log.warn("[系统] 读取运行日志失败 file={}: {}", file, ex.getMessage());
            throw new ServiceException(500, "读取日志失败，请稍后重试");
        }
    }

    public SseEmitter stream(String sourceRaw, Integer lines, String levelRaw, String keyword) {
        AuthTokenStore.Session session = requireAdmin();
        LogSource source = LogSource.from(sourceRaw);
        int want = normalizeLines(lines);
        String level = normalizeLevel(levelRaw);
        String kw = StrUtil.trimToEmpty(keyword);
        Path file = resolveLogFile(source);

        if (!streamSlots.tryAcquire()) {
            throw new ServiceException(400, "实时日志连接已满（最多 " + MAX_STREAM_CONCURRENT + " 路），请稍后重试");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean running = new AtomicBoolean(true);
        Runnable release = () -> {
            if (running.compareAndSet(true, false)) {
                streamSlots.release();
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(ex -> release.run());

        log.info("[审计] 打开运行日志实时流 operator={} source={} level={} keyword={}",
                session.username(), source.name().toLowerCase(Locale.ROOT), level,
                StrUtil.blankToDefault(kw, "-"));
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_VIEW,
                "runtime-log:" + source.name().toLowerCase(Locale.ROOT),
                "实时跟随 level=" + level + " lines=" + want
                        + (StrUtil.isNotBlank(kw) ? " kw=" + truncate(kw, 40) : ""));

        streamExecutor.execute(() -> {
            try {
                sendMeta(emitter, source, file);
                long position = 0L;
                if (Files.isRegularFile(file)) {
                    List<String> history = readLastLines(file, Math.min(want * 8, MAX_LINES * 4));
                    List<String> filtered = filterLines(history, level, kw, want);
                    for (String line : filtered) {
                        sendLine(emitter, sanitize(line));
                    }
                    position = Files.size(file);
                } else {
                    sendEvent(emitter, "status", "日志文件尚不存在，等待落盘…");
                }

                long lastHeartbeat = System.currentTimeMillis();
                while (running.get()) {
                    if (!Files.isRegularFile(file)) {
                        TimeUnit.MILLISECONDS.sleep(POLL_MS);
                        continue;
                    }
                    long size = Files.size(file);
                    if (size < position) {
                        // 滚动截断，从头跟
                        position = 0L;
                        sendEvent(emitter, "status", "检测到日志滚动，已重置跟随位置");
                    }
                    if (size > position) {
                        ChunkRead chunk = readFrom(file, position, size);
                        position = chunk.nextPosition();
                        for (String line : chunk.lines()) {
                            if (!matchLevel(line, level) || !matchKeyword(line, kw)) {
                                continue;
                            }
                            sendLine(emitter, sanitize(line));
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat >= HEARTBEAT_MS) {
                        sendEvent(emitter, "ping", String.valueOf(now));
                        lastHeartbeat = now;
                    }
                    TimeUnit.MILLISECONDS.sleep(POLL_MS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                if (running.get()) {
                    log.warn("[系统] 运行日志 SSE 异常 operator={}: {}", session.username(), ex.getMessage());
                    try {
                        emitter.completeWithError(ex);
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            } finally {
                release.run();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // ignore
                }
                log.info("[审计] 关闭运行日志实时流 operator={} source={}",
                        session.username(), source.name().toLowerCase(Locale.ROOT));
            }
        });

        return emitter;
    }

    private void sendMeta(SseEmitter emitter, LogSource source, Path file) throws IOException {
        String pathHint = file.toAbsolutePath().normalize().toString();
        String exists = Files.isRegularFile(file) ? "yes" : "no";
        sendEvent(emitter, "meta",
                "source=" + source.name().toLowerCase(Locale.ROOT)
                        + " label=" + SOURCE_LABEL.get(source)
                        + " file=" + source.fileName()
                        + " exists=" + exists
                        + " path=" + pathHint);
    }

    private static void sendLine(SseEmitter emitter, String line) throws IOException {
        sendEvent(emitter, "log", line);
    }

    private static void sendEvent(SseEmitter emitter, String name, String data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data == null ? "" : data));
    }

    private Path resolveLogFile(LogSource source) {
        Path dir = Paths.get(logPath).toAbsolutePath().normalize();
        Path file = dir.resolve(source.fileName()).normalize();
        if (!file.startsWith(dir)) {
            throw new ServiceException(400, "非法日志路径");
        }
        return file;
    }

    private static int normalizeLines(Integer lines) {
        if (lines == null || lines <= 0) {
            return DEFAULT_LINES;
        }
        return Math.min(lines, MAX_LINES);
    }

    private static String normalizeLevel(String levelRaw) {
        String level = StrUtil.blankToDefault(levelRaw, "ALL").trim().toUpperCase(Locale.ROOT);
        return switch (level) {
            case "ALL", "" -> "ALL";
            case "ERROR", "WARN", "INFO", "DEBUG", "TRACE" -> level;
            default -> throw new ServiceException(400, "级别仅支持 ALL / ERROR / WARN / INFO / DEBUG / TRACE");
        };
    }

    private AuthTokenStore.Session requireAdmin() {
        AuthTokenStore.Session session = LoginContext.currentSession();
        if (session == null) {
            throw new ServiceException(401, "未登录或登录已失效");
        }
        if (!ROLE_ADMIN.equalsIgnoreCase(StrUtil.blankToDefault(session.role(), ""))) {
            log.warn("[鉴权] 非管理员访问运行日志 account={} role={}", session.username(), session.role());
            throw new ServiceException(403, "仅系统管理员可查看运行日志");
        }
        return session;
    }

    private static List<String> filterLines(List<String> raw, String level, String keyword, int limit) {
        List<String> matched = new ArrayList<>();
        for (String line : raw) {
            if (!matchLevel(line, level) || !matchKeyword(line, keyword)) {
                continue;
            }
            matched.add(sanitize(line));
        }
        if (matched.size() <= limit) {
            return matched;
        }
        return new ArrayList<>(matched.subList(matched.size() - limit, matched.size()));
    }

    private static boolean matchLevel(String line, String level) {
        if ("ALL".equals(level) || StrUtil.isBlank(line)) {
            return true;
        }
        Matcher m = LEVEL_PATTERN.matcher(line);
        if (!m.find()) {
            // 无级别标记的续行（堆栈等）放行，避免截断异常上下文
            return true;
        }
        return level.equals(m.group(1));
    }

    private static boolean matchKeyword(String line, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        return line != null && line.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    static String sanitize(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        return SENSITIVE_PATTERN.matcher(line).replaceAll("$1=***");
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    /** 从文件尾倒读，返回时间正序的行（最多 maxLines）。按字节找换行，整行再按 UTF-8 解码。 */
    private static List<String> readLastLines(Path file, int maxLines) throws IOException {
        if (maxLines <= 0) {
            return List.of();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            if (length == 0) {
                return List.of();
            }
            List<byte[]> lineBytes = new ArrayList<>(Math.min(maxLines, 256));
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(256);
            long pos = length - 1;
            raf.seek(pos);
            int b = raf.read();
            if (b == '\n' || b == '\r') {
                pos--;
            }
            while (pos >= 0 && lineBytes.size() < maxLines) {
                raf.seek(pos);
                b = raf.read();
                if (b == '\n') {
                    lineBytes.add(reverseBytes(buf.toByteArray()));
                    buf.reset();
                } else if (b != '\r') {
                    buf.write(b);
                }
                pos--;
            }
            if (buf.size() > 0 && lineBytes.size() < maxLines) {
                lineBytes.add(reverseBytes(buf.toByteArray()));
            }
            List<String> chronological = new ArrayList<>(lineBytes.size());
            for (int i = lineBytes.size() - 1; i >= 0; i--) {
                chronological.add(new String(lineBytes.get(i), StandardCharsets.UTF_8));
            }
            return chronological;
        }
    }

    private static byte[] reverseBytes(byte[] input) {
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = input[input.length - 1 - i];
        }
        return out;
    }

    private record ChunkRead(List<String> lines, long nextPosition) {
    }

    /** 读取 [from, to) 内完整行；未完成行不推进 position。 */
    private static ChunkRead readFrom(Path file, long from, long to) throws IOException {
        if (to <= from) {
            return new ChunkRead(List.of(), from);
        }
        if (to - from > 2 * 1024 * 1024) {
            // 单次增量过大则只取末尾 2MB，避免突发打爆 SSE
            from = to - 2 * 1024 * 1024;
        }
        byte[] buf = new byte[(int) (to - from)];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(from);
            raf.readFully(buf);
        }
        int consume = buf.length;
        // 若不以换行结束，回退到最后一个换行之后，保留半行给下次
        if (buf[buf.length - 1] != '\n' && buf[buf.length - 1] != '\r') {
            int lastNl = -1;
            for (int i = buf.length - 1; i >= 0; i--) {
                if (buf[i] == '\n' || buf[i] == '\r') {
                    lastNl = i;
                    break;
                }
            }
            if (lastNl < 0) {
                return new ChunkRead(List.of(), from);
            }
            consume = lastNl + 1;
        }
        String text = new String(buf, 0, consume, StandardCharsets.UTF_8);
        String[] parts = text.split("\\R", -1);
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return new ChunkRead(out, from + consume);
    }
}
