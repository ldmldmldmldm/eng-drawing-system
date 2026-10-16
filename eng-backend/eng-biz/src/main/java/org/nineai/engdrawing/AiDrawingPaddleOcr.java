package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * 新入口专用：把预览 PNG 交给与原读图共用的 PaddleOCR（默认 8868）。
 * 输出与旧 Vision TSV 同形，供 {@link SheetFactSupport#classify}；失败返回空串，不编造。
 */
public final class AiDrawingPaddleOcr {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final int MAX_TSV_CHARS = 500_000;

    private AiDrawingPaddleOcr() {
    }

    /** OCR 地址与超时；baseUrl 空则认字直接空。 */
    public record Config(String baseUrl, int timeoutSeconds) {
        public static Config of(String baseUrl, int timeoutSeconds) {
            String base = baseUrl == null ? "" : baseUrl.trim();
            int timeout = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
            return new Config(base, timeout);
        }

        public boolean enabled() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    /** 失败或未配置时返回空串。 */
    public static String recognizeToTsv(byte[] pngBytes, Config config) {
        if (pngBytes == null || pngBytes.length < 32 || config == null || !config.enabled()) {
            return "";
        }
        ImageSize size = imageSize(pngBytes);
        if (size.width <= 0 || size.height <= 0) {
            return "";
        }
        try {
            String body = postTable(pngBytes, config);
            if (body == null || body.isBlank()) {
                return "";
            }
            String tsv = toTsv(MAPPER.readTree(body), size.width, size.height);
            return tsv.length() > MAX_TSV_CHARS ? tsv.substring(0, MAX_TSV_CHARS) : tsv;
        } catch (Exception ex) {
            return "";
        }
    }

    /** 把 Paddle /ocr/table JSON 转成 Vision 同形 TSV（归一化坐标）。 */
    static String toTsv(JsonNode root, int width, int height) {
        if (root == null || width <= 0 || height <= 0) {
            return "";
        }
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) {
            items = root.path("data");
        }
        if (!items.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode it : items) {
            String text = cleanText(it.path("text").asText(""));
            if (text.isEmpty()) {
                continue;
            }
            float[] box = readBox(it);
            double x = clamp01(box[0] / width);
            double y = clamp01(box[1] / height);
            double w = clamp01(Math.max(1f, box[2] - box[0]) / width);
            double h = clamp01(Math.max(1f, box[3] - box[1]) / height);
            double confidence = readConfidence(it);
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(String.format(Locale.US, "%.3f\t%.4f\t%.4f\t%.4f\t%.4f\t%s",
                    confidence, x, y, w, h, text));
        }
        return out.toString();
    }

    private static String postTable(byte[] pngBytes, Config config) throws IOException, InterruptedException {
        String base = config.baseUrl().replaceAll("/+$", "");
        String boundary = "----AiDrawingOcr" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipart = buildMultipart(boundary, pngBytes);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/ocr/table"))
                .timeout(Duration.ofSeconds(Math.max(10, config.timeoutSeconds())))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return "";
        }
        return response.body();
    }

    private static byte[] buildMultipart(String boundary, byte[] pngBytes) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"preview.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[headBytes.length + pngBytes.length + tailBytes.length];
        System.arraycopy(headBytes, 0, all, 0, headBytes.length);
        System.arraycopy(pngBytes, 0, all, headBytes.length, pngBytes.length);
        System.arraycopy(tailBytes, 0, all, headBytes.length + pngBytes.length, tailBytes.length);
        return all;
    }

    private static ImageSize imageSize(byte[] pngBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (image == null) {
                return new ImageSize(0, 0);
            }
            return new ImageSize(image.getWidth(), image.getHeight());
        } catch (IOException ex) {
            return new ImageSize(0, 0);
        }
    }

    /** 返回 [x1,y1,x2,y2] 像素。 */
    private static float[] readBox(JsonNode it) {
        JsonNode box = it.get("box");
        if (box == null || box.isNull()) {
            box = it.get("bbox");
        }
        if (box != null && box.isArray() && box.size() >= 4) {
            if (box.get(0).isArray()) {
                float minX = Float.MAX_VALUE;
                float minY = Float.MAX_VALUE;
                float maxX = 0;
                float maxY = 0;
                for (JsonNode p : box) {
                    float px = (float) p.get(0).asDouble();
                    float py = (float) p.get(1).asDouble();
                    minX = Math.min(minX, px);
                    minY = Math.min(minY, py);
                    maxX = Math.max(maxX, px);
                    maxY = Math.max(maxY, py);
                }
                return new float[]{minX, minY, maxX, maxY};
            }
            return new float[]{
                    (float) box.get(0).asDouble(),
                    (float) box.get(1).asDouble(),
                    (float) box.get(2).asDouble(),
                    (float) box.get(3).asDouble()
            };
        }
        float x = (float) it.path("x").asDouble(0);
        float y = (float) it.path("y").asDouble(0);
        float w = (float) it.path("w").asDouble(it.path("width").asDouble(12));
        float h = (float) it.path("h").asDouble(it.path("height").asDouble(12));
        return new float[]{x, y, x + w, y + h};
    }

    private static double readConfidence(JsonNode it) {
        if (it.has("confidence")) {
            return clamp01(it.path("confidence").asDouble(0.9));
        }
        if (it.has("score")) {
            return clamp01(it.path("score").asDouble(0.9));
        }
        return 0.9;
    }

    private static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static double clamp01(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    private record ImageSize(int width, int height) {
    }
}
