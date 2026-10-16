package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * NOTE 补译：语料未命中后，用当前启用的 LLM（OpenAI 兼容 chat/completions）批量翻译。
 * 料号/标准号/数字尽量原样保留；失败则保持 untranslated。
 */
public final class NoteTranslateSupport {

    public static final String LLM = "llm";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final int TIMEOUT_SECONDS = 90;
    private static final int MAX_BATCH = 40;

    private NoteTranslateSupport() {
    }

    /** 仅处理 status=untranslated 的 NOTE；返回新 Extracted。 */
    public static DrawingConvertSupport.Extracted enrichUntranslated(
            DrawingConvertSupport.Extracted extracted,
            DrawingConvertDeepseek.Config config) {
        if (extracted == null || extracted.notes == null || extracted.notes.isEmpty()) {
            return extracted;
        }
        if (config == null || !config.enabled()) {
            return extracted;
        }
        List<DrawingConvertView.NoteLine> notes = new ArrayList<>(extracted.notes);
        List<Integer> pendingIdx = new ArrayList<>();
        List<String> pendingFull = new ArrayList<>();
        List<String> pendingBody = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            DrawingConvertView.NoteLine n = notes.get(i);
            if (n == null || !NoteGlossary.UNTRANSLATED.equals(n.status())) {
                continue;
            }
            String orig = n.original() == null ? "" : n.original().trim();
            if (orig.isEmpty() || !NoteGlossary.looksEnglish(orig)) {
                continue;
            }
            String body = NoteGlossary.stripLeadingNumber(orig);
            if (body.isEmpty() || !NoteGlossary.looksEnglish(body)) {
                continue;
            }
            pendingIdx.add(i);
            pendingFull.add(orig);
            pendingBody.add(body);
            if (pendingBody.size() >= MAX_BATCH) {
                break;
            }
        }
        if (pendingBody.isEmpty()) {
            return extracted;
        }
        List<String> warnings = new ArrayList<>(extracted.warnings == null ? List.of() : extracted.warnings);
        try {
            // 只译正文，行首序号译后由 ensureLeadingNumber 拼回，避免「2. 2.」
            List<String> translated = ask(pendingBody, config, false);
            int ok = 0;
            for (int j = 0; j < pendingIdx.size(); j++) {
                String zh = j < translated.size() ? translated.get(j) : "";
                if (zh == null || zh.isBlank() || zh.equals(pendingBody.get(j)) || zh.equals(pendingFull.get(j))) {
                    continue;
                }
                zh = NoteGlossary.ensureLeadingNumber(pendingFull.get(j), zh);
                int idx = pendingIdx.get(j);
                DrawingConvertView.NoteLine old = notes.get(idx);
                notes.set(idx, new DrawingConvertView.NoteLine(
                        old.noteNo(),
                        old.original(),
                        zh.trim(),
                        LLM,
                        old.x(),
                        old.y()));
                ok++;
            }
            if (ok > 0) {
                warnings.add("有 " + ok + " 条 NOTE 由大模型补译，请人工审校");
            } else {
                warnings.add("大模型未返回可用译文，未覆盖条目仍待人工处理");
            }
        } catch (Exception e) {
            warnings.add("NOTE 大模型补译失败，仍用语料/原文");
        }
        return new DrawingConvertSupport.Extracted(
                List.copyOf(notes),
                extracted.materials,
                List.copyOf(warnings),
                extracted.overlayText,
                extracted.notesNorm);
    }

    /**
     * 单条强制重译：语料优先，启用模型时再补译覆盖。
     * 失败时返回语料结果（可能仍为 untranslated）。
     */
    public static NoteGlossary.Translated forceTranslate(
            String original, boolean material, DrawingConvertDeepseek.Config config) {
        String src = original == null ? "" : original.trim();
        if (src.isEmpty()) {
            return new NoteGlossary.Translated("", "", NoteGlossary.UNTRANSLATED);
        }
        NoteGlossary.Translated dict = NoteGlossary.translate(src);
        if (config == null || !config.enabled() || !NoteGlossary.looksEnglish(src)) {
            return dict;
        }
        try {
            // NOTE：剥行首序号再送模型，译后只拼一次，避免双号
            String askText = material ? src : NoteGlossary.stripLeadingNumber(src);
            if (askText.isEmpty() || (!material && !NoteGlossary.looksEnglish(askText))) {
                return dict;
            }
            List<String> zhList = ask(List.of(askText), config, material);
            String zh = zhList.isEmpty() ? "" : zhList.get(0);
            // 材料：模型偶发原样返回英文 → 再强制译一次
            if (material
                    && NoteGlossary.looksEnglish(src)
                    && !NoteGlossary.looksKeepAsOriginal(src)
                    && !NoteGlossary.containsCjk(src)
                    && (zh == null || zh.isBlank() || zh.equalsIgnoreCase(askText) || zh.equalsIgnoreCase(src))) {
                zhList = ask(List.of(askText), config, material, true);
                zh = zhList.isEmpty() ? "" : zhList.get(0);
            }
            if (zh != null && !zh.isBlank() && !zh.equalsIgnoreCase(askText) && !zh.equalsIgnoreCase(src)) {
                String fixed = material ? zh.trim() : NoteGlossary.ensureLeadingNumber(src, zh);
                return new NoteGlossary.Translated(src, fixed, LLM);
            }
        } catch (Exception ignored) {
            // 回退语料
        }
        return dict;
    }

    private static List<String> ask(
            List<String> originals, DrawingConvertDeepseek.Config config, boolean material) throws Exception {
        return ask(originals, config, material, false);
    }

    private static List<String> ask(
            List<String> originals,
            DrawingConvertDeepseek.Config config,
            boolean material,
            boolean forceChinese) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.model());
        root.put("temperature", 0);
        root.putObject("response_format").put("type", "json_object");
        DrawingConvertDeepseek.disableThinking(root);
        ArrayNode messages = root.putArray("messages");
        String sys;
        if (material) {
            sys = forceChinese
                    ? "你是工程图纸材料名称英译中助手。必须把英文词语/短语译成中文，禁止原样返回英文。"
                            + "仅纯数字、料号、牌号、标准号、单位符号可保留原文。"
                            + "输出 JSON：{\"items\":[{\"i\":0,\"zh\":\"中文\"}]}，i 为输入序号。"
                    : "你是工程图纸材料名称英译中助手。把材料品名及英文词语译成中文；"
                            + "仅料号、牌号、标准号、纯数字、单位符号可保留原文，普通英文短语必须译成中文，禁止原样返回。"
                            + "不要编造图上没有的内容。输出 JSON：{\"items\":[{\"i\":0,\"zh\":\"中文\"}]}，i 为输入序号。";
        } else {
            sys = "你是工程图纸 NOTE 英译中助手。"
                    + "只翻译工艺说明类英文；料号、标准号（ISO/ASME/GB/DIN 等）、数字、公差、单位符号保持原文不译。"
                    + "不要在译文开头自行添加序号（如 1. / 2) / [3]），行首序号由程序处理。"
                    + "不要编造图上没有的内容。输出 JSON：{\"items\":[{\"i\":0,\"zh\":\"中文\"}]}，i 为输入序号。";
        }
        messages.addObject().put("role", "system").put("content", sys);
        StringBuilder user = new StringBuilder(
                forceChinese
                        ? "请务必译成中文（不要返回英文原文）：\n"
                        : (material ? "请翻译以下材料名称：\n" : "请翻译以下 NOTE：\n"));
        for (int i = 0; i < originals.size(); i++) {
            user.append(i).append(". ").append(originals.get(i)).append('\n');
        }
        messages.addObject().put("role", "user").put("content", user.toString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        String content = DrawingConvertDeepseek.assistantContent(MAPPER.readTree(response.body()));
        return parseItems(content, originals.size());
    }

    private static List<String> parseItems(String content, int size) {
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add("");
        }
        if (content == null || content.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(stripFence(content));
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                items = root.path("translations");
            }
            if (!items.isArray()) {
                return out;
            }
            for (JsonNode item : items) {
                int i = item.path("i").asInt(-1);
                if (i < 0) {
                    i = item.path("index").asInt(-1);
                }
                String zh = item.path("zh").asText("");
                if (zh.isBlank()) {
                    zh = item.path("translated").asText("");
                }
                if (i >= 0 && i < size && zh != null && !zh.isBlank()) {
                    out.set(i, preserveTokens(zh));
                }
            }
        } catch (Exception ignored) {
            // keep empty
        }
        return out;
    }

    private static String stripFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return s.substring(start, end + 1);
            }
        }
        return s;
    }

    /** 若模型误改了标准号片段，尽量不动（轻量保护）。 */
    private static String preserveTokens(String zh) {
        if (zh == null) {
            return "";
        }
        return zh.trim();
    }
}
