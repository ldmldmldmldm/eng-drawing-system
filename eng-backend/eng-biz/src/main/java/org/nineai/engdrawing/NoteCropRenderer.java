package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 把方框窗口从 DXF 放大成 PNG。失败时返回空列表，不打断转换。 */
final class NoteCropRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCRIPT = "/aidrawing/note_crops.py";
    private static final int TIMEOUT_SECONDS = 180;

    record Images(List<String> notes, List<String> callouts) {
        static Images empty() {
            return new Images(List.of(), List.of());
        }
    }

    private NoteCropRenderer() {
    }

    static Images render(String dxfText, NoteBoxFinder.Found found, String pythonCommand) {
        if (dxfText == null || dxfText.isBlank() || found == null || found.notes().isEmpty()) {
            return Images.empty();
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ai-note-crop-");
            Path dxf = dir.resolve("in.dxf");
            Path script = dir.resolve("note_crops.py");
            Path spec = dir.resolve("windows.json");
            Path out = Files.createDirectory(dir.resolve("out"));
            Files.writeString(dxf, dxfText, StandardCharsets.ISO_8859_1);
            if (!copyScript(script)) {
                return Images.empty();
            }
            Files.writeString(spec, windowsJson(found), StandardCharsets.UTF_8);
            String python = pythonCommand == null || pythonCommand.isBlank() ? "python3" : pythonCommand;
            if (!run(python, script, dxf, spec, out)) {
                return Images.empty();
            }
            Path index = out.resolve("index.json");
            if (!Files.isRegularFile(index)) {
                return Images.empty();
            }
            return readImages(Files.readString(index, StandardCharsets.UTF_8), found);
        } catch (Exception ex) {
            return Images.empty();
        } finally {
            deleteDir(dir);
        }
    }

    private static String windowsJson(NoteBoxFinder.Found found) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("notes", boxes(found.notes()));
        root.set("callouts", boxes(found.callouts()));
        return MAPPER.writeValueAsString(root);
    }

    private static ArrayNode boxes(List<NoteBoxFinder.Window> windows) {
        ArrayNode array = MAPPER.createArrayNode();
        int limit = Math.min(windows.size(), 8);
        for (int i = 0; i < limit; i++) {
            NoteBoxFinder.Window window = windows.get(i);
            ObjectNode box = array.addObject();
            box.put("x0", window.x0());
            box.put("y0", window.y0());
            box.put("x1", window.x1());
            box.put("y1", window.y1());
        }
        return array;
    }

    private static Images readImages(String json, NoteBoxFinder.Found found) throws Exception {
        JsonNode rows = MAPPER.readTree(json);
        List<String> notes = blank(found.notes().size());
        List<String> callouts = blank(Math.min(found.callouts().size(), 8));
        if (!rows.isArray()) {
            return Images.empty();
        }
        for (JsonNode row : rows) {
            String kind = row.path("kind").asText("");
            int index = row.path("index").asInt(-1);
            String file = row.path("file").asText("");
            if (index < 0 || file.isBlank()) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(Path.of(file));
            if (bytes.length < 32) {
                continue;
            }
            String packed = Base64.getEncoder().encodeToString(bytes);
            if ("notes".equals(kind) && index < notes.size()) {
                notes.set(index, packed);
            } else if ("callouts".equals(kind) && index < callouts.size()) {
                callouts.set(index, packed);
            }
        }
        boolean anyNote = notes.stream().anyMatch(text -> text != null && !text.isBlank());
        return anyNote ? new Images(notes, callouts) : Images.empty();
    }

    private static List<String> blank(int size) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            rows.add("");
        }
        return rows;
    }

    private static boolean copyScript(Path target) {
        try (InputStream in = NoteCropRenderer.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean run(String python, Path script, Path dxf, Path spec, Path out) {
        ProcessBuilder builder = new ProcessBuilder(
                python,
                script.toString(),
                dxf.toString(),
                spec.toString(),
                out.toString());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 临时目录清不掉不影响结果
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
