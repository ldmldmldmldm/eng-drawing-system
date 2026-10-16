package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 层次爆炸 DXF：走 ezdxf R2018（与图纸转换相同），避免手写精简 DXF 被 CAD 拒绝。
 */
public final class StackExplodeSupport {

    private static final String SCRIPT = "/aidrawing/stack_explode_dxf.py";
    private static final String RENDER_SCRIPT = "/aidrawing/render_dxf.py";
    private static final int TIMEOUT_SECONDS = 60;
    /** 层次预览重渲；失败不阻断 DXF 保存。 */
    private static final int RENDER_TIMEOUT_SECONDS = 90;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StackExplodeSupport() {
    }

    /** 导出结果：DXF Base64 + 文件名提示。 */
    public record Export(String dxfBase64, String fileName, String message, List<String> warnings) {
    }

    /** jobJson：plates/callouts（前端所见即所得坐标）。 */
    public static Export export(String jobJson, String drawingNo, String pythonCommand) {
        if (jobJson == null || jobJson.isBlank()) {
            throw new IllegalArgumentException("层次爆炸数据为空");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ai-stack-explode-");
            Path job = dir.resolve("job.json");
            Files.writeString(job, jobJson, StandardCharsets.UTF_8);
            Path script = dir.resolve("stack_explode_dxf.py");
            if (!copyResource(SCRIPT, script)) {
                throw new IllegalArgumentException("层次爆炸脚本不可用");
            }
            Path outDxf = dir.resolve("out.dxf");
            String python = pythonCommand == null || pythonCommand.isBlank() ? "python3" : pythonCommand;
            String json = capture(
                    python,
                    TIMEOUT_SECONDS,
                    script.toAbsolutePath().toString(),
                    job.toAbsolutePath().toString(),
                    outDxf.toAbsolutePath().toString());
            if (!Files.isRegularFile(outDxf) || Files.size(outDxf) == 0) {
                String detail = blank(json) ? "请确认本机已安装 ezdxf（python3 -m pip install ezdxf）" : json;
                throw new IllegalArgumentException("层次爆炸 DXF 生成失败。" + detail);
            }
            Map<?, ?> report = parseReport(json);
            @SuppressWarnings("unchecked")
            List<String> warnings = report.get("warnings") instanceof List<?> list
                    ? (List<String>) list
                    : List.of();
            byte[] bytes = Files.readAllBytes(outDxf);
            String stem = blank(drawingNo) ? "stack-explode" : drawingNo.trim();
            return new Export(
                    Base64.getEncoder().encodeToString(bytes),
                    stem + "-层次爆炸.dxf",
                    "已用 ezdxf R2018 导出，格式与图纸转换一致",
                    warnings);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalArgumentException("层次爆炸 DXF 无法写入");
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 按层次 DXF 重渲预览 PNG（白底 CAD 渲染，与 job/DXF 旁注一致）。
     * 失败返回空串，不抛异常。
     */
    public static String renderPreviewFromDxf(byte[] dxfBytes, String pythonCommand) {
        if (dxfBytes == null || dxfBytes.length < 32) {
            return "";
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ai-stack-preview-");
            Path dxf = dir.resolve("stack.dxf");
            Files.write(dxf, dxfBytes);
            Path pages = dir.resolve("pages");
            Files.createDirectories(pages);
            Path png = dir.resolve("preview.png");
            Path script = dir.resolve("render_dxf.py");
            if (!copyResource(RENDER_SCRIPT, script)) {
                return "";
            }
            String python = pythonCommand == null || pythonCommand.isBlank() ? "python3" : pythonCommand;
            if (!run(
                    python,
                    RENDER_TIMEOUT_SECONDS,
                    script.toAbsolutePath().toString(),
                    dxf.toAbsolutePath().toString(),
                    pages.toAbsolutePath().toString(),
                    png.toAbsolutePath().toString(),
                    // 与终稿同档，产物总览放大仍可读旁注
                    "280")) {
                return "";
            }
            if (!Files.isRegularFile(png) || Files.size(png) < 32) {
                return "";
            }
            String packed = DwgOriginalPreview.shrinkPngBytes(Files.readAllBytes(png), 4800, 8_000_000);
            return packed == null ? "" : packed;
        } catch (IOException ex) {
            return "";
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 供测试：把前端结构整理成脚本 job（也可前端直接传）。 */
    public static String normalizeJobJson(Map<String, Object> body) {
        try {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("plates", body == null ? List.of() : body.getOrDefault("plates", List.of()));
            job.put("callouts", body == null ? List.of() : body.getOrDefault("callouts", List.of()));
            job.put("drawingNo", body == null ? "" : String.valueOf(body.getOrDefault("drawingNo", "")));
            return MAPPER.writeValueAsString(job);
        } catch (IOException ex) {
            throw new IllegalArgumentException("层次爆炸数据无法序列化");
        }
    }

    private static Map<?, ?> parseReport(String json) {
        if (blank(json)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (IOException ex) {
            return Map.of();
        }
    }

    private static boolean copyResource(String resource, Path target) {
        try (InputStream in = StackExplodeSupport.class.getResourceAsStream(resource)) {
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

    private static String capture(String command, int timeoutSeconds, String... args) {
        String[] all = new String[args.length + 1];
        all[0] = command;
        System.arraycopy(args, 0, all, 1, args.length);
        ProcessBuilder builder = new ProcessBuilder(all);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        try {
            Process process = builder.start();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread outReader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(sink);
                } catch (IOException ignored) {
                    // 读不到当失败
                }
            });
            Thread errReader = new Thread(() -> {
                try {
                    process.getErrorStream().transferTo(err);
                } catch (IOException ignored) {
                    // 同上
                }
            });
            outReader.start();
            errReader.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            outReader.join(2000);
            errReader.join(2000);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            String text = sink.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 && text.isEmpty()) {
                String errText = err.toString(StandardCharsets.UTF_8).trim();
                return errText.isEmpty() ? "" : errText;
            }
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
