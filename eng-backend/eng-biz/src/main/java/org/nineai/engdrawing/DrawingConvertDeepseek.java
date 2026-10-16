package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 材料试抽：说明区高清图交给模型读牌号和厚度。认字原文只作参照，不再用它丢掉图上读到的数字。
 * 视图方框序号仍用来确认这条是材料。未配置密钥或调用失败时保留规则结果。
 */
public final class DrawingConvertDeepseek {

    private static final Logger log = LoggerFactory.getLogger(DrawingConvertDeepseek.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final int TIMEOUT_SECONDS = 90;
    private static final int MAX_OCR_CHARS = 12_000;
    /** 说明区原图的 base64 上限，超过则只发文字。 */
    private static final int MAX_IMAGE_CHARS = 12_000_000;
    private static final int VISION_PARALLEL = 4;
    private static final ExecutorService VISION_POOL = Executors.newFixedThreadPool(VISION_PARALLEL, r -> {
        Thread t = new Thread(r, "eng-vision");
        t.setDaemon(true);
        return t;
    });
    private static final Pattern CALLOUT_NO = Pattern.compile("^\\[?\\s*([1-9]\\d{0,2})\\s*\\]?$");
    /**
     * OCR 常把序号单独成行（{@code 6} / {@code 6.} / {@code 6。} / {@code [11]}），
     * 必须保留进原文，否则拆条无号可依。
     */
    private static final Pattern REGION_LONE_NOTE_NO = Pattern.compile(
            "^\\s*(?:\\[\\s*)?([1-9]\\d{0,2})(?:\\s*\\])?\\s*[.)、．。]?\\s*$");
    /** 区域 OCR 兜底拆条：1. / 2) / [11] / 无句点「1 GEOMETRIC」 */
    private static final Pattern REGION_NOTE_HEAD = Pattern.compile(
            "^\\s*(?:\\[([1-9]\\d{0,2})\\]|([1-9]\\d{0,2})(?:\\s*[.)、．]|(?=\\s+[A-Za-z\\[])))\\s*(.+)$",
            Pattern.DOTALL);
    /** 正文内嵌序号（粘连切开；含无句点「2 GEOMETRIC」） */
    private static final Pattern REGION_EMBEDDED_NOTE_NO = Pattern.compile(
            "(?i)(?:\\[\\s*([1-9]\\d{0,2})\\s*\\]|([1-9]\\d{0,2})\\s*[.)、．](?!\\d)"
                    + "|(?<![\\d.A-Za-z])([1-9]\\d{0,2})(?=\\s+[A-Za-z\\[]))");

    private DrawingConvertDeepseek() {
    }

    public record Config(String baseUrl, String apiKey, String model) {
        public static Config disabled() {
            return new Config("", "", "");
        }

        public static Config of(String baseUrl, String apiKey, String model) {
            String base = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.trim();
            String key = apiKey == null ? "" : apiKey.trim();
            String name = model == null || model.isBlank() ? "deepseek-flash" : model.trim();
            return new Config(base, key, name);
        }

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * 关闭思考链（Qwen3 / vLLM OpenAI 兼容）。
     * 思考型模型默认先写 reasoning，探测或 JSON 任务易把 token 占满导致 content 为空。
     */
    public static void disableThinking(ObjectNode root) {
        if (root != null) {
            root.putObject("chat_template_kwargs").put("enable_thinking", false);
        }
    }

    /** 取助手正文；空白视为无内容。 */
    public static String assistantContent(JsonNode responseRoot) {
        if (responseRoot == null) {
            return "";
        }
        return responseRoot.path("choices").path(0).path("message").path("content").asText("").trim();
    }

    /** 密钥为空时原样返回。模型无结果或失败时仍用规则抽出的材料。 */
    public static DrawingConvertSupport.Extracted overlay(
            DrawingConvertSupport.Extracted extracted,
            String ocrTsv,
            String pngBase64,
            Config config) {
        if (extracted == null || config == null || !config.enabled()) {
            return extracted;
        }
        List<String> warnings = new ArrayList<>(extracted.warnings);
        try {
            List<DrawingConvertView.MaterialRow> rows = ask(ocrTsv, pngBase64, config);
            if (rows.isEmpty()) {
                warnings.add("模型没有抽出可核对的材料，仍用原有结果");
                return copy(extracted, extracted.materials, warnings);
            }
            warnings.add("材料表为模型试抽，请对照图纸核对");
            return copy(extracted, rows, warnings);
        } catch (Exception ex) {
            warnings.add("模型试抽失败，仍用原有结果");
            return copy(extracted, extracted.materials, warnings);
        }
    }

    static List<DrawingConvertView.MaterialRow> keepGrounded(String modelJson, String ocrTsv) {
        Set<String> callouts = calloutNumbers(ocrTsv);
        if (callouts.isEmpty() || modelJson == null || modelJson.isBlank()) {
            return List.of();
        }
        JsonNode root = readJson(modelJson);
        if (root == null) {
            return List.of();
        }
        JsonNode materials = root.path("materials");
        if (!materials.isArray()) {
            return List.of();
        }
        List<DrawingConvertView.MaterialRow> rows = new ArrayList<>();
        int seq = 1;
        for (JsonNode item : materials) {
            String no = item.path("noteNo").asText("").replaceAll("\\D", "");
            // 视图方框序号确认这是材料；牌号和厚度以说明区图片为准
            if (!callouts.contains(no)) {
                continue;
            }
            String name = item.path("materialEn").asText("").replaceAll("\\s+", " ").trim();
            if (name.isEmpty()) {
                continue;
            }
            String thick = blankToUnlabeled(item.path("thickness").asText(""));
            String color = blankToUnlabeled(item.path("color").asText(""));
            rows.add(new DrawingConvertView.MaterialRow(
                    seq++, no, name, "", thick, "customer_note", "", color));
        }
        return rows;
    }

    private static List<DrawingConvertView.MaterialRow> ask(String ocrTsv, String pngBase64, Config config)
            throws Exception {
        String corpus = corpusOf(ocrTsv);
        Set<String> callouts = calloutNumbers(ocrTsv);
        if (corpus.isBlank() || callouts.isEmpty()) {
            return List.of();
        }
        String body = requestBody(config, corpus, callouts, pngBase64);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return List.of();
        }
        String content = assistantContent(MAPPER.readTree(response.body()));
        return keepGrounded(content, ocrTsv);
    }

    private static String requestBody(Config config, String corpus, Set<String> callouts, String pngBase64)
            throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.model());
        root.put("temperature", 0);
        root.putObject("response_format").put("type", "json_object");
        disableThinking(root);
        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", "材料表以附带的说明区图片为准，输出 JSON。"
                        + "认字原文可能丢了小数点或把序号认错，厚度和牌号按图片抄写，不要按认错的原文改数字。"
                        + "只保留视图序号里也有的材料。材料名去掉颜色和厚度。"
                        + "图片上看不清的字段写未标注，不要编造图片上没有的内容。");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        String prompt = "视图序号：" + String.join(",", callouts) + "\n"
                + "认字原文（仅供参照，可能有错）：\n" + clip(corpus) + "\n"
                + "按说明区图片抄材料。序号必须出现在视图序号里。"
                + "返回 {\"materials\":[{\"noteNo\":\"\",\"materialEn\":\"\",\"thickness\":\"\",\"color\":\"\"}]}";
        String image = imageUrl(pngBase64);
        if (image.isEmpty()) {
            user.put("content", prompt);
        } else {
            ArrayNode parts = user.putArray("content");
            parts.addObject().put("type", "text").put("text", prompt);
            parts.addObject().put("type", "image_url").putObject("image_url").put("url", image).put("detail", "high");
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String imageUrl(String pngBase64) {
        if (pngBase64 == null || pngBase64.isBlank() || pngBase64.length() > MAX_IMAGE_CHARS) {
            return "";
        }
        String raw = pngBase64.startsWith("data:") ? pngBase64 : "data:image/png;base64," + pngBase64;
        return raw.length() > MAX_IMAGE_CHARS ? "" : raw;
    }

    private static DrawingConvertSupport.Extracted copy(
            DrawingConvertSupport.Extracted extracted,
            List<DrawingConvertView.MaterialRow> materials,
            List<String> warnings) {
        return new DrawingConvertSupport.Extracted(
                extracted.notes, materials, warnings, extracted.overlayText, extracted.notesNorm);
    }

    private static String blankToUnlabeled(String text) {
        String value = text == null ? "" : text.trim();
        return value.isEmpty() ? MaterialLineSupport.UNLABELED : value;
    }

    private static String corpusOf(String ocrTsv) {
        StringBuilder notes = new StringBuilder();
        for (SheetFactSupport.Line line : SheetFactSupport.parse(ocrTsv)) {
            String text = line.text() == null ? "" : line.text().trim();
            if (!keepRegionOcrLine(text)) {
                continue;
            }
            if (notes.length() > 0) {
                notes.append('\n');
            }
            notes.append(text);
        }
        if (notes.length() == 0 && ocrTsv != null && ocrTsv.chars().anyMatch(Character::isLetter)) {
            return clip(ocrTsv);
        }
        return clip(notes.toString());
    }

    /** 有字母的正文，或孤号行（供后续与正文合并拆条）。 */
    static boolean keepRegionOcrLine(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.chars().anyMatch(Character::isLetter)) {
            return true;
        }
        return REGION_LONE_NOTE_NO.matcher(text.trim()).matches();
    }

    static Set<String> calloutNumbers(String ocrTsv) {
        Set<String> numbers = new LinkedHashSet<>();
        for (SheetFactSupport.Line line : SheetFactSupport.parse(ocrTsv)) {
            Matcher matcher = CALLOUT_NO.matcher(line.text() == null ? "" : line.text().trim());
            if (matcher.matches()) {
                numbers.add(matcher.group(1));
            }
        }
        return numbers;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_OCR_CHARS ? text : text.substring(0, MAX_OCR_CHARS);
    }

    private static JsonNode readJson(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return MAPPER.readTree(text.substring(start, end + 1));
        } catch (Exception ex) {
            return null;
        }
    }

    public record BoxedRead(
            List<DrawingConvertView.MaterialRow> rows,
            List<String> crops,
            List<String> warnings,
            boolean used) {
        static BoxedRead missed() {
            return new BoxedRead(List.of(), List.of(), List.of(), false);
        }
    }

    /** 每张放大图只读一条。某一条失败不影响已经读出的行。 */
    public static BoxedRead readBoxed(List<String> notePngs, Config config) {
        if (config == null || !config.enabled() || notePngs == null || notePngs.isEmpty()) {
            return BoxedRead.missed();
        }
        List<DrawingConvertView.MaterialRow> rows = new ArrayList<>();
        List<String> crops = new ArrayList<>();
        int missed = 0;
        List<Future<DrawingConvertView.MaterialRow>> futures = new ArrayList<>();
        List<String> submittedPngs = new ArrayList<>();
        for (String png : notePngs) {
            if (png == null || png.isBlank()) {
                missed++;
                continue;
            }
            submittedPngs.add(png);
            futures.add(VISION_POOL.submit(() -> {
                try {
                    return askNote(png, config);
                } catch (Exception ex) {
                    return null;
                }
            }));
        }
        for (int i = 0; i < futures.size(); i++) {
            DrawingConvertView.MaterialRow row = null;
            try {
                row = futures.get(i).get(90, TimeUnit.SECONDS);
            } catch (Exception ex) {
                row = null;
            }
            if (row == null) {
                missed++;
                continue;
            }
            rows.add(row);
            crops.add(submittedPngs.get(i));
        }
        if (rows.isEmpty()) {
            return BoxedRead.missed();
        }
        // 说明区每条方框已经单独读过。视图序号读不全时不能把 13、14 丢掉。
        List<DrawingConvertView.MaterialRow> pending = rows;
        List<String> pendingCrops = crops;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            order.add(i);
        }
        order.sort(java.util.Comparator.comparingInt(index -> {
            try {
                return Integer.parseInt(pending.get(index).noteNo());
            } catch (NumberFormatException ex) {
                return Integer.MAX_VALUE;
            }
        }));
        List<DrawingConvertView.MaterialRow> numbered = new ArrayList<>();
        List<String> orderedCrops = new ArrayList<>();
        for (int seq = 0; seq < order.size(); seq++) {
            DrawingConvertView.MaterialRow row = pending.get(order.get(seq));
            numbered.add(new DrawingConvertView.MaterialRow(
                    seq + 1, row.noteNo(), row.materialEn(), row.materialZh(),
                    row.thickness(), row.source(), row.role(), row.color()));
            orderedCrops.add(pendingCrops.get(order.get(seq)));
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("材料表按方框说明逐条读取，请对照每条放大图核对");
        if (missed > 0) {
            warnings.add("有 " + missed + " 条方框说明没有读出");
        }
        return new BoxedRead(numbered, orderedCrops, warnings, true);
    }

    static DrawingConvertView.MaterialRow rowFromCropJson(String json) {
        JsonNode root = readJson(json);
        if (root == null) {
            return null;
        }
        JsonNode materials = root.path("materials");
        if (!materials.isArray() || materials.isEmpty()) {
            return null;
        }
        JsonNode item = materials.get(0);
        String no = item.path("noteNo").asText("").replaceAll("\\D", "");
        if (no.isEmpty() || no.length() > 3) {
            return null;
        }
        String name = item.path("materialEn").asText("").replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            return null;
        }
        if (DrawingConvertSupport.isNotesComplianceText(name)
                || DrawingConvertSupport.isShellMaterialName(no, name)
                || !DrawingConvertSupport.looksLikeMaterialSpecLine(name)) {
            return null;
        }
        return new DrawingConvertView.MaterialRow(
                1,
                no,
                name,
                "",
                cleanThick(item.path("thickness").asText("")),
                "customer_note",
                "",
                blankToUnlabeled(item.path("color").asText("")));
    }

    private static DrawingConvertView.MaterialRow askNote(String pngBase64, Config config) throws Exception {
        String prompt = "这张图只有一条方框序号和它右侧的说明，只抄这一条。"
                + "仅当右侧是材料说明（如 RELEASE LINER MATERIAL: / FOAM MATERIAL: / 带厚度颜色）才输出；"
                + "若是 NOTES 合规条款（如 MUST COMPLY / ENVIRONMENTAL / HOMOGENEOUS MATERIALS）则 materials 为空。"
                + "材料名保留序号右侧除颜色和厚度以外的全部原文，角色也要留下。"
                + "颜色只取 COLOR: 后面的词，不要把材料名里的 CLEAR 当成颜色删掉。"
                + "厚度只留数字和单位，不要 THICK。"
                + "看不清的字段写未标注。材料名不要只填序号数字。"
                + "返回 {\"materials\":[{\"noteNo\":\"\",\"materialEn\":\"\",\"thickness\":\"\",\"color\":\"\"}]}";
        String content = askImages(config, prompt, List.of(pngBase64));
        return rowFromCropJson(content);
    }

    public record NotesLegendRead(
            List<DrawingConvertView.NoteLine> notes,
            List<String> warnings,
            boolean used) {
        static NotesLegendRead missed() {
            return new NotesLegendRead(List.of(), List.of(), false);
        }
    }

    /**
     * 读左侧 NOTES 说明大块：按图上序号逐条抄写（1. / [11] 等），禁止糊成一条。
     */
    public static NotesLegendRead readNotesLegend(String pngBase64, Config config) {
        if (config == null || !config.enabled() || pngBase64 == null || pngBase64.isBlank()) {
            return NotesLegendRead.missed();
        }
        try {
            String prompt = "这是工程图左侧 NOTES 说明区截图（可能是笔画字/扫描风，认不清可省略该词）。"
                    + "必须按图上序号拆成多条，禁止把多条 NOTE 粘成一条："
                    + "圆点编号如 1. 2. 14. 与方框编号如 [11] [12] [13] 各成一条；"
                    + "同一序号的多行正文合并到该条 original；"
                    + "下一条序号一旦出现必须新开对象；"
                    + "不要把 THICKNESS/HARDNESS/材料规格表写进 NOTE；"
                    + "不要编造图上没有的字，不要重复抄写同一句。"
                    + "返回 JSON：{\"notes\":[{\"noteNo\":\"1\",\"original\":\"...\"},...]}；"
                    + "noteNo 只留数字，original 为该条完整英文/原文（单条建议不超过 400 字符）。";
            String content = askImages(config, prompt, List.of(pngBase64));
            List<DrawingConvertView.NoteLine> notes = notesFromLegendJson(content);
            if (notes.size() < 2) {
                return NotesLegendRead.missed();
            }
            return new NotesLegendRead(notes, List.of(), true);
        } catch (Exception ex) {
            return NotesLegendRead.missed();
        }
    }

    static List<DrawingConvertView.NoteLine> notesFromLegendJson(String json) {
        JsonNode root = readJson(json);
        if (root == null) {
            return List.of();
        }
        JsonNode arr = root.path("notes");
        if (!arr.isArray() || arr.isEmpty()) {
            return List.of();
        }
        List<DrawingConvertView.NoteLine> out = new ArrayList<>();
        for (JsonNode item : arr) {
            String no = item.path("noteNo").asText("").replaceAll("\\D", "");
            String original = item.path("original").asText("").replaceAll("\\s+", " ").trim();
            if (no.isEmpty() || no.length() > 3 || original.length() < 8) {
                continue;
            }
            // 视觉偶发粘连：再按序号切开
            if (ExtractMergeSupport.looksGlued(original) || ExtractMergeSupport.countEmbeddedNoteNos(no + ". " + original) >= 2) {
                List<String> parts = DrawingConvertSupport.splitByNoteNumbers(no + ". " + original);
                if (parts.size() >= 2) {
                    for (String part : parts) {
                        java.util.regex.Matcher hm = java.util.regex.Pattern.compile(
                                "^\\s*(?:\\[([1-9]\\d{0,2})\\]|([1-9]\\d{0,2})\\s*[.)、．])\\s*(.+)$",
                                java.util.regex.Pattern.DOTALL).matcher(part.trim());
                        if (!hm.matches()) {
                            continue;
                        }
                        String pno = hm.group(1) != null ? hm.group(1) : hm.group(2);
                        String body = hm.group(3) == null ? "" : hm.group(3).replaceAll("\\s+", " ").trim();
                        if (pno == null || body.length() < 8) {
                            continue;
                        }
                        NoteGlossary.Translated ptr = NoteGlossary.defer(pno + ". " + body);
                        out.add(new DrawingConvertView.NoteLine(
                                pno, ptr.original(), ptr.translated(), ptr.status(), null, null));
                    }
                    continue;
                }
            }
            // 跳过尺寸公差假条目
            if (original.matches("(?i).*\\d+(?:\\.\\d+)?\\s*[±÷].*") && original.replaceAll("[^A-Za-z]", "").length() < 4) {
                continue;
            }
            NoteGlossary.Translated tr = NoteGlossary.defer(no + ". " + original);
            out.add(new DrawingConvertView.NoteLine(no, tr.original(), tr.translated(), tr.status(), null, null));
        }
        out.sort(java.util.Comparator.comparingInt(n -> {
            try {
                return Integer.parseInt(n.noteNo());
            } catch (Exception ex) {
                return 999;
            }
        }));
        // 同号去重
        java.util.Map<String, DrawingConvertView.NoteLine> uniq = new java.util.LinkedHashMap<>();
        for (DrawingConvertView.NoteLine n : out) {
            String key = n.noteNo() == null ? "" : n.noteNo().replaceAll("\\D", "");
            if (key.isEmpty()) {
                continue;
            }
            DrawingConvertView.NoteLine prior = uniq.get(key);
            if (prior == null || (ExtractMergeSupport.looksGlued(prior.original())
                    && !ExtractMergeSupport.looksGlued(n.original()))) {
                uniq.put(key, n);
            }
        }
        return new ArrayList<>(uniq.values());
    }

    private static String askImages(Config config, String prompt, List<String> pngs) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.model());
        root.put("temperature", 0);
        root.putObject("response_format").put("type", "json_object");
        disableThinking(root);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", "按图片抄写，输出 JSON。不要编造图上没有的文字。");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode parts = user.putArray("content");
        parts.addObject().put("type", "text").put("text", prompt);
        for (String png : pngs) {
            String image = imageUrl(png);
            if (!image.isEmpty()) {
                parts.addObject().put("type", "image_url").putObject("image_url").put("url", image).put("detail", "high");
            }
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("模型返回 " + response.statusCode());
        }
        return assistantContent(MAPPER.readTree(response.body()));
    }

    private static String cleanThick(String thick) {
        String value = thick == null ? "" : thick.trim();
        value = value.replaceAll("(?i)\\s*THICKNESS\\b", "");
        value = value.replaceAll("(?i)\\s*THK\\b", "");
        value = value.replaceAll("(?i)\\s*THICK\\b", "");
        value = value.replaceAll("\\s+", " ").trim();
        return value.isEmpty() ? MaterialLineSupport.UNLABELED : value;
    }

    /**
     * ① 用户框选截图：OCR + Vision，按 kind 抽 NOTE 或多行材料。
     * 看不清写未标注；禁止编造。Vision 失败时仍回 OCR 原文。
     */
    public static DrawingConvertView.RegionExtractResult readRegion(
            byte[] pngBytes,
            String kind,
            Config config,
            AiDrawingPaddleOcr.Config ocrConfig) {
        String normalized = kind == null ? "note" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        boolean material = "material".equals(normalized);
        if (!material) {
            normalized = "note";
        }
        List<String> warnings = new ArrayList<>();
        String ocrTsv = AiDrawingPaddleOcr.recognizeToTsv(pngBytes, ocrConfig);
        String ocrText = softNormalizeRegionOcr(corpusOf(ocrTsv));
        String pngBase64 = java.util.Base64.getEncoder().encodeToString(pngBytes == null ? new byte[0] : pngBytes);
        if (config == null || !config.enabled()) {
            if (material) {
                List<DrawingConvertView.MaterialRow> ocrMats = materialsFromRegionOcrFallback(ocrText);
                if (!ocrMats.isEmpty()) {
                    warnings.add("模型未配置，已按 OCR 预填材料，请核对后写入");
                    return new DrawingConvertView.RegionExtractResult(
                            normalized, ocrText, false, List.of(), ocrMats, warnings);
                }
                warnings.add("模型未配置，仅返回 OCR 原文，请手工落表");
                return new DrawingConvertView.RegionExtractResult(
                        normalized, ocrText, false, List.of(), List.of(), warnings);
            }
            List<DrawingConvertView.NoteLine> ocrNotes = notesFromRegionOcrFallback(ocrText);
            if (!ocrNotes.isEmpty()) {
                warnings.add("模型未配置，已按 OCR 拆条/预填，请核对后写入");
                return new DrawingConvertView.RegionExtractResult(
                        normalized, ocrText, false, ocrNotes, List.of(), warnings);
            }
            warnings.add("模型未配置，仅返回 OCR 原文，请手工落表");
            return new DrawingConvertView.RegionExtractResult(
                    normalized, ocrText, false, List.of(), List.of(), warnings);
        }
        if (pngBase64.isBlank() || imageUrl(pngBase64).isEmpty()) {
            if (material) {
                List<DrawingConvertView.MaterialRow> ocrMats = materialsFromRegionOcrFallback(ocrText);
                if (!ocrMats.isEmpty()) {
                    warnings.add("截图过大无法送模型，已按 OCR 预填材料，请核对");
                    return new DrawingConvertView.RegionExtractResult(
                            normalized, ocrText, false, List.of(), ocrMats, warnings);
                }
                warnings.add("截图过大或无效，无法送模型");
                return new DrawingConvertView.RegionExtractResult(
                        normalized, ocrText, false, List.of(), List.of(), warnings);
            }
            List<DrawingConvertView.NoteLine> ocrNotes = notesFromRegionOcrFallback(ocrText);
            if (!ocrNotes.isEmpty()) {
                warnings.add("截图过大无法送模型，已按 OCR 拆条/预填，请核对");
                return new DrawingConvertView.RegionExtractResult(
                        normalized, ocrText, false, ocrNotes, List.of(), warnings);
            }
            warnings.add("截图过大或无效，无法送模型");
            return new DrawingConvertView.RegionExtractResult(
                    normalized, ocrText, false, List.of(), List.of(), warnings);
        }
        try {
            if (material) {
                String prompt = "这是工程图材料说明区用户框选截图。"
                        + "按图上序号/方框逐条抄材料（如 [11] RELEASE LINER… / FOAM MATERIAL:）。"
                        + "材料名保留序号右侧除颜色和厚度以外的全部原文。"
                        + "颜色只取 COLOR: 后的词；厚度只留数字和单位。"
                        + "看不清的字段写未标注；不要编造图上没有的字。"
                        + "认字原文仅供参照（可能有错）：\n" + clip(ocrText) + "\n"
                        + "返回 {\"materials\":[{\"noteNo\":\"\",\"materialEn\":\"\",\"thickness\":\"\",\"color\":\"\"}]}；"
                        + "无材料则 materials 为空数组。";
                String content = askImages(config, prompt, List.of(pngBase64));
                List<DrawingConvertView.MaterialRow> rows = materialsFromRegionJson(content);
                if (rows.isEmpty()) {
                    List<DrawingConvertView.MaterialRow> ocrMats = materialsFromRegionOcrFallback(ocrText);
                    if (!ocrMats.isEmpty()) {
                        warnings.add("模型未抽出材料行，已按 OCR 预填，请逐条核对（OCR 可能有误）");
                        return new DrawingConvertView.RegionExtractResult(
                                normalized, ocrText, true, List.of(), ocrMats, warnings);
                    }
                    warnings.add("模型未抽出材料行，请对照 OCR 原文手工填写");
                }
                return new DrawingConvertView.RegionExtractResult(
                        normalized, ocrText, true, List.of(), rows, warnings);
            }
            String prompt = "这是工程图 NOTES 说明区用户框选截图（可能扫描糊字）。"
                    + "必须以图上可见序号为准拆成多条（如 1. / 2. / 7. / [11]），禁止把多条粘成一条；"
                    + "选区内有几条编号 NOTE 就返回几条，缺号不要补造。"
                    + "OCR 常漏号、粘词（OFTHE），只作参照，不要被 OCR 带着走。"
                    + "同一序号多行合并到该条 original；下一条序号必须新开对象。"
                    + "不要把材料规格/THICKNESS 表写进 NOTE；不要编造；认不清可省略该词。"
                    + "认字原文仅供参照：\n" + clip(ocrText) + "\n"
                    + "返回 {\"notes\":[{\"noteNo\":\"1\",\"original\":\"...\"},{\"noteNo\":\"2\",\"original\":\"...\"},...]}；"
                    + "noteNo 只留数字，original 为该条完整原文（勿含序号前缀亦可）。";
            String content = askImages(config, prompt, List.of(pngBase64));
            List<DrawingConvertView.NoteLine> notes = regionNotesFromJson(content);
            List<DrawingConvertView.NoteLine> ocrNotes = notesFromRegionOcrFallback(ocrText);
            notes = preferRegionNotes(notes, ocrNotes, warnings);
            if (notes.isEmpty()) {
                warnings.add("模型未抽出 NOTE，请对照 OCR 原文手工填写");
            }
            return new DrawingConvertView.RegionExtractResult(
                    normalized, ocrText, true, notes, List.of(), warnings);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (msg.length() > 180) {
                msg = msg.substring(0, 180);
            }
            log.warn("[任务] 区域 Vision 失败 kind={} msg={}", normalized, msg);
            boolean timedOut = msg.toLowerCase(java.util.Locale.ROOT).contains("timed out")
                    || msg.contains("超时");
            String failHint = timedOut ? "模型超时" : "模型识别失败";
            if (material) {
                List<DrawingConvertView.MaterialRow> ocrMats = materialsFromRegionOcrFallback(ocrText);
                if (!ocrMats.isEmpty()) {
                    warnings.add(failHint + "，已按 OCR 预填材料，请逐条核对（OCR 可能有误）");
                    return new DrawingConvertView.RegionExtractResult(
                            normalized, ocrText, false, List.of(), ocrMats, warnings);
                }
                warnings.add(failHint + "，仅返回 OCR 原文");
                return new DrawingConvertView.RegionExtractResult(
                        normalized, ocrText, false, List.of(), List.of(), warnings);
            }
            List<DrawingConvertView.NoteLine> ocrNotes = notesFromRegionOcrFallback(ocrText);
            if (!ocrNotes.isEmpty()) {
                warnings.add(failHint + "，已按 OCR 拆条/预填，请核对");
                return new DrawingConvertView.RegionExtractResult(
                        normalized, ocrText, false, ocrNotes, List.of(), warnings);
            }
            warnings.add(failHint + "，仅返回 OCR 原文");
            return new DrawingConvertView.RegionExtractResult(
                    normalized, ocrText, false, List.of(), List.of(), warnings);
        }
    }

    /**
     * Vision 空/失败时：按 OCR 孤号+材料正文规则预填（复用方框材料解析）。
     */
    static List<DrawingConvertView.MaterialRow> materialsFromRegionOcrFallback(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return List.of();
        }
        List<DrawingConvertView.MaterialRow> raw = DrawingConvertSupport.filterValidMaterials(
                DrawingConvertSupport.squareMaterials(ocrText));
        if (raw.isEmpty()) {
            return List.of();
        }
        List<DrawingConvertView.MaterialRow> out = new ArrayList<>();
        int seq = 1;
        for (DrawingConvertView.MaterialRow row : raw) {
            if (row == null) {
                continue;
            }
            out.add(new DrawingConvertView.MaterialRow(
                    seq++,
                    row.noteNo() == null ? "" : row.noteNo(),
                    row.materialEn() == null ? "" : row.materialEn(),
                    row.materialZh() == null ? "" : row.materialZh(),
                    row.thickness() == null ? "" : row.thickness(),
                    "region_ocr",
                    row.role() == null ? "" : row.role(),
                    row.color() == null ? "" : row.color()));
        }
        return out;
    }

    /**
     * Vision 与 OCR 拆条择优：模型粘连/少条时切开或改用 OCR 多条。
     */
    static List<DrawingConvertView.NoteLine> preferRegionNotes(
            List<DrawingConvertView.NoteLine> vision,
            List<DrawingConvertView.NoteLine> ocr,
            List<String> warnings) {
        List<DrawingConvertView.NoteLine> v = vision == null ? List.of() : vision;
        List<DrawingConvertView.NoteLine> o = ocr == null ? List.of() : ocr;
        if (!v.isEmpty()) {
            List<DrawingConvertView.NoteLine> unglued = unglueRegionNotes(v);
            if (unglued.size() > v.size()) {
                warnings.add("模型结果疑似粘连，已按序号拆成 " + unglued.size() + " 条，请核对");
                v = unglued;
            }
        }
        if (v.isEmpty()) {
            if (!o.isEmpty()) {
                warnings.add(
                        o.size() > 1
                                ? "模型未抽出 NOTE，已按 OCR 拆条，请逐条核对序号与原文"
                                : "模型未抽出 NOTE，已用 OCR 预填，请改序号并核对原文后再写入");
            }
            return o;
        }
        boolean glued = v.stream().anyMatch(n -> ExtractMergeSupport.looksGlued(n.original()));
        if ((v.size() <= 1 && o.size() > v.size()) || (glued && o.size() > v.size())) {
            warnings.add("模型结果疑似粘连/少条，已按 OCR 序号拆成 " + o.size() + " 条，请核对");
            return o;
        }
        return v;
    }

    /** 单条/多条里嵌套序号时强制切开。 */
    static List<DrawingConvertView.NoteLine> unglueRegionNotes(List<DrawingConvertView.NoteLine> notes) {
        if (notes == null || notes.isEmpty()) {
            return List.of();
        }
        List<DrawingConvertView.NoteLine> out = new ArrayList<>();
        for (DrawingConvertView.NoteLine n : notes) {
            if (n == null) {
                continue;
            }
            String raw = n.original() == null ? "" : n.original().trim();
            String no = n.noteNo() == null ? "" : n.noteNo().replaceAll("\\D", "");
            String blob = raw;
            if (!no.isEmpty() && !REGION_NOTE_HEAD.matcher(raw).matches()) {
                blob = no + ". " + raw;
            }
            if (!ExtractMergeSupport.looksGlued(blob)
                    && ExtractMergeSupport.countEmbeddedNoteNos(blob) < 2) {
                out.add(n);
                continue;
            }
            List<DrawingConvertView.NoteLine> carved = carveNotesByEmbeddedNos(blob);
            if (carved.size() <= 1) {
                carved = parseRegionOcrParts(forceBreakEmbeddedNoteNos(blob));
            }
            if (carved.size() > 1) {
                out.addAll(carved);
            } else {
                out.add(n);
            }
        }
        return dedupeRegionNotes(out);
    }

    /** 区域 OCR：修常见粘连与中英标点，便于拆条。 */
    static String softNormalizeRegionOcr(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = text.replace('\r', '\n')
                .replace('。', '.')
                .replace('，', ',')
                .replace('：', ':')
                .replace('；', ';')
                .replace('（', '(')
                .replace('）', ')');
        out = DrawingConvertSupport.collapseOcrEcho(out);
        // 孤号行去掉尾标点，便于与下一行正文合并（OCR 常出「6。」/「7.」）
        out = out.replaceAll("(?m)^\\s*([1-9]\\d{0,2})\\s*[.)、．。]\\s*$", "$1");
        out = out.replaceAll("(?m)^\\s*\\[\\s*([1-9]\\d{0,2})\\s*\\]\\s*[.)、．。]?\\s*$", "[$1]");
        // 常见 OCR 粘词（只修确定模式，不编造语义）
        out = out.replaceAll("(?i)\\bOFTHE\\b", "OF THE");
        out = out.replaceAll("(?i)\\bARETHE\\b", "ARE THE");
        out = out.replaceAll("(?i)\\bMUSTBE\\b", "MUST BE");
        out = out.replaceAll("(?i)\\bTHATARE\\b", "THAT ARE");
        out = out.replaceAll("(?i)\\bUSEDTO\\b", "USED TO");
        out = out.replaceAll("(?i)\\bPROPERTYOF\\b", "PROPERTY OF");
        // 粘连切开：…SHOWN.2.PART
        out = out.replaceAll("(?<=[A-Za-z0-9.])(?=[1-9]\\d{0,2}\\s*[.)、．](?!\\d))", "\n");
        // 无句点：…SHOWN 2 GEOMETRIC
        out = out.replaceAll("(?<=[A-Za-z.,;:])\\s+(?=[1-9]\\d{0,2}\\s+[A-Za-z\\[])", "\n");
        return out.replaceAll("[ \\t]{2,}", " ").trim();
    }

    /**
     * Vision 空结果时：按 OCR 序号拆条；粘连长文禁止整段糊成 1 条。
     */
    static List<DrawingConvertView.NoteLine> notesFromRegionOcrFallback(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return List.of();
        }
        String cleaned = softNormalizeRegionOcr(ocrText);
        List<DrawingConvertView.NoteLine> out = parseRegionOcrParts(cleaned);
        if (out.size() > 1) {
            return out;
        }
        // 二次：按嵌套序号强制切开后再解析
        String forced = forceBreakEmbeddedNoteNos(cleaned);
        if (!forced.equals(cleaned)) {
            out = parseRegionOcrParts(forced);
            if (!out.isEmpty()) {
                return out;
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        String blob = cleaned.replaceAll("\\s+", " ").trim();
        int embedded = Math.max(
                ExtractMergeSupport.countEmbeddedNoteNos(blob),
                countRegionEmbeddedNos(blob));
        // 粘连长文 / 正文里已有多序号：强制切开，禁止整段 1 条
        if (blob.length() >= 200 || embedded >= 2) {
            List<DrawingConvertView.NoteLine> carved = carveNotesByEmbeddedNos(blob);
            if (carved.size() <= 1) {
                carved = parseRegionOcrParts(forceBreakEmbeddedNoteNos(blob));
            }
            if (carved.size() > 1) {
                return carved;
            }
            // 仍切不开：返回空，避免前端/调用方再糊成一大坨
            return List.of();
        }
        if (blob.length() < 12) {
            return List.of();
        }
        NoteGlossary.Translated tr = NoteGlossary.defer("1. " + blob);
        return List.of(new DrawingConvertView.NoteLine("1", tr.original(), tr.translated(), tr.status(), null, null));
    }

    private static int countRegionEmbeddedNos(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int n = 0;
        Matcher m = REGION_EMBEDDED_NOTE_NO.matcher(text);
        while (m.find()) {
            n++;
            if (n >= 12) {
                return n;
            }
        }
        return n;
    }

    private static List<DrawingConvertView.NoteLine> parseRegionOcrParts(String cleaned) {
        List<String> parts = DrawingConvertSupport.splitByNoteNumbers(cleaned);
        List<DrawingConvertView.NoteLine> out = new ArrayList<>();
        for (String line : parts) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (DrawingConvertSupport.looksLikeDimensionNote(line)
                    || DrawingConvertSupport.isSpecHeaderLine(line)) {
                continue;
            }
            Matcher m = REGION_NOTE_HEAD.matcher(line.trim());
            String no;
            String body;
            if (m.matches()) {
                no = m.group(1) != null ? m.group(1) : m.group(2);
                body = m.group(3) == null ? "" : m.group(3).replaceAll("\\s+", " ").trim();
            } else {
                continue;
            }
            if (no == null || no.isBlank() || body.length() < 4) {
                continue;
            }
            NoteGlossary.Translated tr = NoteGlossary.defer(no + ". " + body);
            out.add(new DrawingConvertView.NoteLine(no, tr.original(), tr.translated(), tr.status(), null, null));
        }
        return dedupeRegionNotes(out);
    }

    static String forceBreakEmbeddedNoteNos(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = text.replace('\r', '\n');
        out = out.replaceAll("(?<=[A-Za-z0-9.])(?=[1-9]\\d{0,2}\\s*[.)、．](?!\\d))", "\n");
        out = out.replaceAll("(?<=[A-Za-z0-9.])\\s+(?=[1-9]\\d{0,2}\\s*[.)、．](?!\\d)\\s*[A-Za-z\\[])", "\n");
        out = out.replaceAll("(?<=[A-Za-z.,;:])\\s+(?=[1-9]\\d{0,2}\\s+[A-Za-z\\[])", "\n");
        out = out.replaceAll("(?<!\\n)\\s*(?=\\[\\s*[1-9]\\d{0,2}\\s*\\])", "\n");
        return out;
    }

    /** 在整段粘连文中按嵌套序号硬切成多条。 */
    static List<DrawingConvertView.NoteLine> carveNotesByEmbeddedNos(String blob) {
        if (blob == null || blob.isBlank()) {
            return List.of();
        }
        String text = forceBreakEmbeddedNoteNos(blob).replace('\n', ' ').replaceAll("\\s+", " ").trim();
        Matcher m = REGION_EMBEDDED_NOTE_NO.matcher(text);
        List<int[]> hits = new ArrayList<>();
        List<String> nos = new ArrayList<>();
        while (m.find()) {
            String no = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
            if (no == null || no.isBlank()) {
                continue;
            }
            hits.add(new int[] {m.start(), m.end()});
            nos.add(no);
        }
        if (hits.size() < 2) {
            return List.of();
        }
        List<DrawingConvertView.NoteLine> out = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            int start = hits.get(i)[0];
            int end = i + 1 < hits.size() ? hits.get(i + 1)[0] : text.length();
            String chunk = text.substring(start, end).trim();
            if (chunk.length() < 6) {
                continue;
            }
            Matcher hm = REGION_NOTE_HEAD.matcher(chunk);
            String no;
            String body;
            if (hm.matches()) {
                no = hm.group(1) != null ? hm.group(1) : hm.group(2);
                body = hm.group(3) == null ? "" : hm.group(3).replaceAll("\\s+", " ").trim();
            } else {
                no = nos.get(i);
                body = chunk.replaceFirst("^(?:\\[\\s*\\d+\\s*\\]|\\d+\\s*[.)、．]?)\\s*", "").trim();
            }
            if (no == null || no.isBlank() || body.length() < 4) {
                continue;
            }
            if (DrawingConvertSupport.looksLikeDimensionNote(no + ". " + body)
                    || DrawingConvertSupport.isSpecHeaderLine(chunk)) {
                continue;
            }
            NoteGlossary.Translated tr = NoteGlossary.defer(no + ". " + body);
            out.add(new DrawingConvertView.NoteLine(no, tr.original(), tr.translated(), tr.status(), null, null));
        }
        return dedupeRegionNotes(out);
    }

    private static List<DrawingConvertView.NoteLine> dedupeRegionNotes(List<DrawingConvertView.NoteLine> notes) {
        java.util.Map<String, DrawingConvertView.NoteLine> uniq = new java.util.LinkedHashMap<>();
        for (DrawingConvertView.NoteLine n : notes) {
            String key = n.noteNo() == null ? "" : n.noteNo().replaceAll("\\D", "");
            if (key.isEmpty()) {
                continue;
            }
            DrawingConvertView.NoteLine prior = uniq.get(key);
            if (prior == null || ((prior.original() == null ? 0 : prior.original().length())
                    < (n.original() == null ? 0 : n.original().length()))) {
                uniq.put(key, n);
            }
        }
        return new ArrayList<>(uniq.values());
    }

    /** 区域材料：不要求视图序号接地，用户已框选；仅跳过明显合规条款。 */
    static List<DrawingConvertView.MaterialRow> materialsFromRegionJson(String json) {
        JsonNode root = readJson(json);
        if (root == null) {
            return List.of();
        }
        JsonNode materials = root.path("materials");
        if (!materials.isArray() || materials.isEmpty()) {
            return List.of();
        }
        List<DrawingConvertView.MaterialRow> out = new ArrayList<>();
        int auto = 0;
        for (JsonNode item : materials) {
            String no = item.path("noteNo").asText("").replaceAll("\\D", "");
            String name = item.path("materialEn").asText("").replaceAll("\\s+", " ").trim();
            if (name.isEmpty() || DrawingConvertSupport.isNotesComplianceText(name)) {
                continue;
            }
            if (no.isEmpty() || no.length() > 3) {
                no = String.valueOf(++auto);
            }
            out.add(new DrawingConvertView.MaterialRow(
                    out.size() + 1,
                    no,
                    name,
                    "",
                    cleanThick(item.path("thickness").asText("")),
                    "region_capture",
                    "",
                    blankToUnlabeled(item.path("color").asText(""))));
        }
        return out;
    }

    /** 区域 NOTE：允许单条；粘连时按序号切开。 */
    static List<DrawingConvertView.NoteLine> regionNotesFromJson(String json) {
        JsonNode root = readJson(json);
        if (root == null) {
            return List.of();
        }
        JsonNode arr = root.path("notes");
        if (!arr.isArray() || arr.isEmpty()) {
            return List.of();
        }
        List<DrawingConvertView.NoteLine> out = new ArrayList<>();
        for (JsonNode item : arr) {
            String no = item.path("noteNo").asText("").replaceAll("\\D", "");
            String original = item.path("original").asText("").replaceAll("\\s+", " ").trim();
            if (no.isEmpty() || no.length() > 3 || original.length() < 4) {
                continue;
            }
            // 与整图 legend 一致：一条里嵌多个序号则切开
            if (ExtractMergeSupport.looksGlued(original)
                    || ExtractMergeSupport.countEmbeddedNoteNos(no + ". " + original) >= 2) {
                List<String> parts = DrawingConvertSupport.splitByNoteNumbers(no + ". " + original);
                if (parts.size() >= 2) {
                    for (String part : parts) {
                        Matcher hm = REGION_NOTE_HEAD.matcher(part.trim());
                        if (!hm.matches()) {
                            continue;
                        }
                        String pno = hm.group(1) != null ? hm.group(1) : hm.group(2);
                        String body = hm.group(3) == null ? "" : hm.group(3).replaceAll("\\s+", " ").trim();
                        if (pno == null || pno.isBlank() || body.length() < 4) {
                            continue;
                        }
                        NoteGlossary.Translated ptr = NoteGlossary.defer(pno + ". " + body);
                        out.add(new DrawingConvertView.NoteLine(
                                pno, ptr.original(), ptr.translated(), ptr.status(), null, null));
                    }
                    continue;
                }
            }
            if (original.matches("(?i).*\\d+(?:\\.\\d+)?\\s*[±÷].*")
                    && original.replaceAll("[^A-Za-z]", "").length() < 4) {
                continue;
            }
            NoteGlossary.Translated tr = NoteGlossary.defer(no + ". " + original);
            out.add(new DrawingConvertView.NoteLine(no, tr.original(), tr.translated(), tr.status(), null, null));
        }
        return dedupeRegionNotes(out);
    }
}
