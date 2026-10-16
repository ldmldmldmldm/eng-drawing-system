package org.nineai.engdrawing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只抄 STEP 文本里写明的身份、产品、单位、颜色和属性，不猜尺寸和材料。
 */
public final class StepTextScan {

    private static final int HEAD_PROBE = 4096;
    private static final int MAX_PRODUCTS = 500;
    private static final int MAX_COLORS = 16;
    private static final int MAX_PROPS = 20;
    private static final int MAX_LIST = 40;
    private static final Pattern PRODUCT_NAME = Pattern.compile(
            "\\bPRODUCT\\s*\\(\\s*'((?:''|[^'])*)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_SCHEMA = Pattern.compile(
            "FILE_SCHEMA\\s*\\(\\s*\\(\\s*'([^']*)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSEMBLY_USE = Pattern.compile(
            "NEXT_ASSEMBLY_USAGE_OCCURRENCE",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ORIGINATING = Pattern.compile(
            "originating_system\\s*\\*/\\s*'((?:''|[^'])*)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOUR_RGB = Pattern.compile(
            "\\bCOLOUR_RGB\\s*\\(\\s*(?:'((?:''|[^'])*)'\\s*,\\s*)?"
                    + "([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[Ee][+-]?\\d+)?)\\s*,\\s*"
                    + "([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[Ee][+-]?\\d+)?)\\s*,\\s*"
                    + "([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[Ee][+-]?\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCRIPTIVE = Pattern.compile(
            "\\bDESCRIPTIVE_REPRESENTATION_ITEM\\s*\\(\\s*'((?:''|[^'])*)'\\s*,\\s*'((?:''|[^'])*)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MATERIAL_NAME = Pattern.compile(
            "\\bMATERIAL_DESIGNATION\\s*\\(\\s*'((?:''|[^'])*)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LAYER_ASSIGN = Pattern.compile(
            "\\bPRESENTATION_LAYER_ASSIGNMENT\\s*\\(\\s*'((?:''|[^'])*)'\\s*,\\s*'((?:''|[^'])*)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSEMBLY_OCCURRENCE = Pattern.compile(
            "\\bNEXT_ASSEMBLY_USAGE_OCCURRENCE\\s*\\(\\s*'((?:''|[^'])*)'\\s*,\\s*'((?:''|[^'])*)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MEASURE_ITEM = Pattern.compile(
            "\\bMEASURE_REPRESENTATION_ITEM\\s*\\(\\s*'((?:''|[^'])*)'\\s*,\\s*([A-Za-z0-9_]+)\\s*\\(\\s*"
                    + "([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[Ee][+-]?\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMATION = Pattern.compile(
            "\\bPRODUCT_DEFINITION_FORMATION\\s*\\(\\s*'((?:''|[^'])*)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SI_METRE = Pattern.compile(
            "SI_UNIT\\s*\\(\\s*(?:\\$\\s*,\\s*)?(?:\\.([A-Z]+)\\.\\s*,\\s*)?\\.METRE\\.",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONVERSION_UNIT = Pattern.compile(
            "CONVERSION_BASED_UNIT\\s*\\(\\s*'((?:''|[^'])*)'",
            Pattern.CASE_INSENSITIVE);
    private static final String SOLID_TOKEN = "MANIFOLD_SOLID_BREP";
    private static final Pattern SOLID_ENTITY = Pattern.compile(
            "#(\\d+)\\s*=\\s*MANIFOLD_SOLID_BREP\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String[] PMI_TOKENS = {
            "GEOMETRIC_TOLERANCE",
            "DIMENSIONAL_SIZE",
            "DIMENSIONAL_LOCATION",
            "DATUM_FEATURE",
            "DRAUGHTING_CALLOUT",
            "TESSELLATED_ANNOTATION_OCCURRENCE"
    };

    private StepTextScan() {
    }

    public record Result(
            boolean stepFile,
            String headerFileName,
            String timestamp,
            String author,
            String organization,
            String preprocessorVersion,
            String originatingSystem,
            String authorization,
            String fileDescription,
            String implementationLevel,
            String schema,
            String protocol,
            String lengthUnit,
            int solidCount,
            List<String> solidEntityIds,
            List<String> productNames,
            int assemblyUseCount,
            boolean productListTruncated,
            List<String> assemblyNames,
            List<String> revisions,
            List<String> layers,
            List<String> colors,
            List<String> properties,
            List<String> measures,
            String pmi) {
    }

    public static Result scan(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || looksBinary(bytes)) {
            return empty();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String head = text.length() > HEAD_PROBE ? text.substring(0, HEAD_PROBE) : text;
        if (!head.contains("ISO-10303-21")) {
            return empty();
        }
        String schema = first(FILE_SCHEMA, text);
        FileNameFields fileName = readFileName(text);
        String originating = labeled(fileName.originating);
        if (MaterialLineSupport.UNLABELED.equals(originating)) {
            originating = labeled(decodeOrNull(first(ORIGINATING, text)));
        }
        List<String> names = productNames(text);
        DescriptionFields description = readDescription(text);
        List<String> solidIds = solidEntityIds(text);
        return new Result(
                true,
                labeled(fileName.name),
                labeled(fileName.timestamp),
                labeled(fileName.author),
                labeled(fileName.organization),
                labeled(fileName.preprocessor),
                originating,
                labeled(fileName.authorization),
                labeled(description.text),
                labeled(description.implementationLevel),
                labeled(schema),
                protocolOf(schema),
                lengthUnit(text),
                solidIds.isEmpty() ? countToken(text, SOLID_TOKEN) : solidIds.size(),
                solidIds,
                names,
                countMatches(ASSEMBLY_USE, text),
                names.size() >= MAX_PRODUCTS && moreProducts(text),
                collect(ASSEMBLY_OCCURRENCE, text, true),
                collect(FORMATION, text, false),
                layers(text),
                colors(text),
                properties(text),
                measures(text),
                pmiOf(text));
    }

    /** STEP 实体号，如 #73；按文件出现顺序，最多 500。 */
    private static List<String> solidEntityIds(String text) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = SOLID_ENTITY.matcher(text);
        while (matcher.find()) {
            ids.add("#" + matcher.group(1));
            if (ids.size() >= MAX_PRODUCTS) {
                break;
            }
        }
        return ids;
    }

    private static List<String> productNames(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PRODUCT_NAME.matcher(text);
        while (matcher.find()) {
            String decoded = decodeStepString(matcher.group(1));
            if (decoded.isEmpty()) {
                continue;
            }
            if (names.size() >= MAX_PRODUCTS) {
                break;
            }
            names.add(decoded);
        }
        return new ArrayList<>(names);
    }

    private static boolean moreProducts(String text) {
        int seen = 0;
        Matcher matcher = PRODUCT_NAME.matcher(text);
        while (matcher.find()) {
            if (!decodeStepString(matcher.group(1)).isEmpty()) {
                seen++;
                if (seen > MAX_PRODUCTS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String lengthUnit(String text) {
        Matcher si = SI_METRE.matcher(text);
        String prefix = null;
        while (si.find()) {
            prefix = si.group(1);
            if (prefix != null && "MILLI".equalsIgnoreCase(prefix)) {
                return "毫米";
            }
        }
        if (prefix != null) {
            if ("CENTI".equalsIgnoreCase(prefix)) {
                return "厘米";
            }
            if ("DECI".equalsIgnoreCase(prefix)) {
                return "分米";
            }
            return "米";
        }
        Matcher conversion = CONVERSION_UNIT.matcher(text);
        while (conversion.find()) {
            String name = decodeStepString(conversion.group(1)).toUpperCase(Locale.ROOT);
            if (name.contains("INCH") || name.contains("英寸")) {
                return "英寸";
            }
        }
        return MaterialLineSupport.UNLABELED;
    }

    private static List<String> colors(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = COLOUR_RGB.matcher(text);
        while (matcher.find() && found.size() < MAX_COLORS) {
            found.add(toHex(parseNumber(matcher.group(2)), parseNumber(matcher.group(3)), parseNumber(matcher.group(4))));
        }
        return new ArrayList<>(found);
    }

    private static List<String> properties(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher material = MATERIAL_NAME.matcher(text);
        while (material.find() && found.size() < MAX_PROPS) {
            String name = decodeStepString(material.group(1));
            if (!name.isEmpty()) {
                found.add("材料：" + name);
            }
        }
        Matcher item = DESCRIPTIVE.matcher(text);
        while (item.find() && found.size() < MAX_PROPS) {
            String name = decodeStepString(item.group(1));
            String value = decodeStepString(item.group(2));
            if (name.isEmpty() || value.isEmpty()) {
                continue;
            }
            found.add(name + "：" + value);
        }
        return new ArrayList<>(found);
    }

    private static List<String> layers(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = LAYER_ASSIGN.matcher(text);
        while (matcher.find() && found.size() < MAX_LIST) {
            String name = decodeStepString(matcher.group(1));
            String description = decodeStepString(matcher.group(2));
            String layer = !name.isEmpty() ? name : description;
            if (!layer.isEmpty()) {
                found.add(layer);
            }
        }
        return new ArrayList<>(found);
    }

    private static List<String> measures(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = MEASURE_ITEM.matcher(text);
        while (matcher.find() && found.size() < MAX_PROPS) {
            String name = decodeStepString(matcher.group(1));
            String kind = matcher.group(2);
            String value = matcher.group(3);
            if (value == null || value.isBlank()) {
                continue;
            }
            String label = name.isEmpty() ? kind : name;
            found.add(label + "：" + value);
        }
        return new ArrayList<>(found);
    }

    private static List<String> collect(Pattern pattern, String text, boolean preferSecond) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && found.size() < MAX_LIST) {
            String first = decodeStepString(matcher.group(1));
            String second = matcher.groupCount() >= 2 ? decodeStepString(matcher.group(2)) : "";
            String value = preferSecond && !second.isEmpty() ? second : first;
            if (value.isEmpty() && !second.isEmpty()) {
                value = second;
            }
            if (!value.isEmpty()) {
                found.add(value);
            }
        }
        return new ArrayList<>(found);
    }

    private static String pmiOf(String text) {
        for (String token : PMI_TOKENS) {
            if (countToken(text, token) > 0) {
                return "文件含三维标注，未解析具体数值";
            }
        }
        return "本文件未包含 PMI";
    }

    private static String protocolOf(String schema) {
        if (schema == null || schema.isBlank()) {
            return MaterialLineSupport.UNLABELED;
        }
        String upper = schema.toUpperCase(Locale.ROOT);
        if (upper.contains("242") || upper.contains("MANAGED_MODEL_BASED")) {
            return "AP242";
        }
        if (upper.contains("214") || upper.contains("AUTOMOTIVE_DESIGN")) {
            return "AP214";
        }
        if (upper.contains("203") || upper.contains("CONFIG_CONTROL")) {
            return "AP203";
        }
        return labeled(schema);
    }

    private static FileNameFields readFileName(String text) {
        int start = indexOfIgnoreCase(text, "FILE_NAME", 0);
        if (start < 0) {
            return FileNameFields.empty();
        }
        int open = text.indexOf('(', start);
        if (open < 0) {
            return FileNameFields.empty();
        }
        int close = scanParen(text, open);
        if (close < 0) {
            return FileNameFields.empty();
        }
        List<String> args = splitArgs(stripComments(text.substring(open + 1, close)));
        return new FileNameFields(
                decodeOrNull(unquote(argAt(args, 0))),
                decodeOrNull(unquote(argAt(args, 1))),
                decodeOrNull(listText(argAt(args, 2))),
                decodeOrNull(listText(argAt(args, 3))),
                decodeOrNull(unquote(argAt(args, 4))),
                decodeOrNull(unquote(argAt(args, 5))),
                decodeOrNull(unquote(argAt(args, 6))));
    }

    private static DescriptionFields readDescription(String text) {
        int start = indexOfIgnoreCase(text, "FILE_DESCRIPTION", 0);
        if (start < 0) {
            return DescriptionFields.empty();
        }
        int open = text.indexOf('(', start);
        if (open < 0) {
            return DescriptionFields.empty();
        }
        int close = scanParen(text, open);
        if (close < 0) {
            return DescriptionFields.empty();
        }
        List<String> args = splitArgs(stripComments(text.substring(open + 1, close)));
        return new DescriptionFields(
                decodeOrNull(listText(argAt(args, 0))),
                decodeOrNull(unquote(argAt(args, 1))));
    }

    private static String argAt(List<String> args, int index) {
        return index < args.size() ? args.get(index) : "";
    }

    private static String stripComments(String text) {
        return text.replaceAll("/\\*[^*]*\\*/", " ");
    }

    private static List<String> splitArgs(String body) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (inString) {
                current.append(ch);
                if (ch == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                    current.append(body.charAt(i + 1));
                    i++;
                } else if (ch == '\'') {
                    inString = false;
                }
                continue;
            }
            if (ch == '\'') {
                inString = true;
                current.append(ch);
                continue;
            }
            if (ch == '(') {
                depth++;
                current.append(ch);
                continue;
            }
            if (ch == ')') {
                depth--;
                current.append(ch);
                continue;
            }
            if (ch == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (!current.toString().isBlank()) {
            args.add(current.toString().trim());
        }
        return args;
    }

    private static String unquote(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String listText(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("(") && value.endsWith(")")) {
            value = value.substring(1, value.length() - 1);
        }
        List<String> parts = splitArgs(value);
        List<String> names = new ArrayList<>();
        for (String part : parts) {
            String decoded = decodeStepString(unquote(part));
            if (!decoded.isEmpty() && !"$".equals(decoded)) {
                names.add(decoded);
            }
        }
        return String.join("、", names);
    }

    private static int scanParen(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (ch == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                if (ch == '\'') {
                    inString = false;
                }
                continue;
            }
            if (ch == '\'') {
                inString = true;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countToken(String text, String token) {
        int count = 0;
        int from = 0;
        while (from < text.length()) {
            int found = text.indexOf(token, from);
            if (found < 0) {
                return count;
            }
            count++;
            from = found + token.length();
        }
        return count;
    }

    private static String labeled(String value) {
        if (value == null || value.isBlank() || "$".equals(value.trim())) {
            return MaterialLineSupport.UNLABELED;
        }
        return value.trim();
    }

    private static String decodeOrNull(String raw) {
        String decoded = decodeStepString(raw);
        return decoded.isEmpty() ? null : decoded;
    }

    private static String toHex(double red, double green, double blue) {
        return String.format("#%02X%02X%02X", toByte(red), toByte(green), toByte(blue));
    }

    private static int toByte(double value) {
        double scaled = value > 1.0 ? value : value * 255.0;
        int rounded = (int) Math.round(scaled);
        if (rounded < 0) {
            return 0;
        }
        return Math.min(255, rounded);
    }

    private static double parseNumber(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** STEP 的 \\X2\\ 十六进制是原文里的汉字，不是翻译。 */
    static String decodeStepString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            if (i + 1 < raw.length() && raw.charAt(i) == '\'' && raw.charAt(i + 1) == '\'') {
                out.append('\'');
                i++;
                continue;
            }
            if (raw.regionMatches(true, i, "\\X2\\", 0, 4)) {
                int end = indexOfIgnoreCase(raw, "\\X0\\", i + 4);
                if (end < 0) {
                    out.append(raw.charAt(i));
                    continue;
                }
                appendHex4(out, raw.substring(i + 4, end));
                i = end + 3;
                continue;
            }
            out.append(raw.charAt(i));
        }
        return out.toString().trim();
    }

    private static void appendHex4(StringBuilder out, String hexWithSpace) {
        String hex = hexWithSpace.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        for (int h = 0; h + 4 <= hex.length(); h += 4) {
            try {
                out.append((char) Integer.parseInt(hex.substring(h, h + 4), 16));
            } catch (NumberFormatException ignored) {
                return;
            }
        }
    }

    private static int indexOfIgnoreCase(String text, String token, int from) {
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.indexOf(token.toUpperCase(Locale.ROOT), from);
    }

    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, HEAD_PROBE);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static Result empty() {
        String none = MaterialLineSupport.UNLABELED;
        return new Result(
                false,
                none,
                none,
                none,
                none,
                none,
                none,
                none,
                none,
                none,
                none,
                none,
                none,
                0,
                List.of(),
                List.of(),
                0,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                none);
    }

    private record FileNameFields(
            String name,
            String timestamp,
            String author,
            String organization,
            String preprocessor,
            String originating,
            String authorization) {
        static FileNameFields empty() {
            return new FileNameFields(null, null, null, null, null, null, null);
        }
    }

    private record DescriptionFields(String text, String implementationLevel) {
        static DescriptionFields empty() {
            return new DescriptionFields(null, null);
        }
    }
}
