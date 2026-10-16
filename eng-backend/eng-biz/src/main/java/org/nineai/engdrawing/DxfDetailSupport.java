package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * 调用 ezdxf 脚本抽出 DXF 明细。失败返回 empty，不抛给页面。
 */
public final class DxfDetailSupport {

    private static final int TIMEOUT_SECONDS = 90;
    private static final String SCRIPT = "/aidrawing/extract_dxf.py";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DxfDetailSupport() {
    }

    /** 已有 DXF 文件时直接抽。 */
    public static DxfDetailView fromFile(Path dxf, String pythonCommand) {
        if (dxf == null || !Files.isRegularFile(dxf)) {
            return DxfDetailView.empty();
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ai-dxf-detail-");
            Path script = dir.resolve("extract_dxf.py");
            if (!copyResource(SCRIPT, script)) {
                return DxfDetailView.empty();
            }
            String python = pythonCommand == null || pythonCommand.isBlank() ? "python3" : pythonCommand;
            String json = capture(python, TIMEOUT_SECONDS, script.toString(), dxf.toAbsolutePath().toString());
            return parse(json);
        } catch (IOException ex) {
            return DxfDetailView.empty();
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 内存中的 DXF 文本：落盘后再抽。 */
    public static DxfDetailView fromText(String dxfText, String pythonCommand) {
        if (dxfText == null || dxfText.isBlank()) {
            return DxfDetailView.empty();
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ai-dxf-text-");
            Path dxf = dir.resolve("in.dxf");
            Files.writeString(dxf, dxfText, StandardCharsets.ISO_8859_1);
            return fromFile(dxf, pythonCommand);
        } catch (IOException ex) {
            return DxfDetailView.empty();
        } finally {
            deleteQuietly(dir);
        }
    }

    static DxfDetailView parse(String json) {
        if (json == null || json.isBlank()) {
            return DxfDetailView.empty();
        }
        try {
            DxfDetailView view = MAPPER.readValue(json, DxfDetailView.class);
            return view == null ? DxfDetailView.empty() : normalize(view);
        } catch (IOException ex) {
            return DxfDetailView.empty();
        }
    }

    private static DxfDetailView normalize(DxfDetailView view) {
        return new DxfDetailView(
                view.layers() == null ? java.util.List.of() : view.layers(),
                view.texts() == null ? java.util.List.of() : view.texts(),
                view.dimensions() == null ? java.util.List.of() : view.dimensions(),
                view.leaders() == null ? java.util.List.of() : view.leaders(),
                view.blockNames() == null ? java.util.List.of() : view.blockNames(),
                view.layoutNames() == null ? java.util.List.of() : view.layoutNames(),
                view.typeCounts() == null ? java.util.List.of() : view.typeCounts(),
                view.truncated(),
                view.layersTruncated(),
                view.textsTruncated(),
                view.dimensionsTruncated(),
                view.source() == null ? "" : view.source(),
                view.message() == null ? "" : view.message());
    }

    private static boolean copyResource(String resource, Path target) {
        try (InputStream in = DxfDetailSupport.class.getResourceAsStream(resource)) {
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
            String text = sink.toString(StandardCharsets.UTF_8);
            return text.length() > 8_000_000 ? text.substring(0, 8_000_000) : text;
        } catch (IOException ex) {
            return "";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
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
}
