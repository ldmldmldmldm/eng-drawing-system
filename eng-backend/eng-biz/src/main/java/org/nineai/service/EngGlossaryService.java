package org.nineai.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.nineai.common.core.exception.ServiceException;
import org.nineai.engdrawing.NoteGlossary;
import org.nineai.entity.EngGlossary;
import org.nineai.mapper.EngGlossaryMapper;
import org.nineai.support.LoginContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NOTE 语料库：全员可查可改；变更后刷新 NoteGlossary 运行时词典（TERM/PHRASE）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EngGlossaryService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final Set<String> KINDS = Set.of(
            NoteGlossary.KIND_TERM, NoteGlossary.KIND_PHRASE);
    private static final String SHEET_DATA = "语料导入";
    private static final String SHEET_EXPORT = "语料库";
    private static final String SHEET_HELP = "填写说明";
    private static final String COL_EN = "英文短语";
    private static final String COL_ZH = "中文译文";
    private static final String COL_KIND = "层级";
    private static final String COL_STATUS = "状态";
    private static final String[] DATA_HEADERS = {COL_EN, COL_ZH, COL_KIND, COL_STATUS};

    private final EngGlossaryMapper engGlossaryMapper;
    private final OperationLogService operationLogService;

    @PostConstruct
    public void init() {
        try {
            reloadIntoGlossary();
        } catch (Exception e) {
            log.warn("[任务] 语料库加载跳过（表可能未建）: {}", e.getMessage());
        }
    }

    public void reloadIntoGlossary() {
        List<EngGlossary> rows = engGlossaryMapper.selectList(
                new LambdaQueryWrapper<EngGlossary>()
                        .eq(EngGlossary::getStatus, STATUS_ACTIVE)
                        .in(EngGlossary::getEntryKind, NoteGlossary.KIND_TERM, NoteGlossary.KIND_PHRASE)
                        .orderByDesc(EngGlossary::getId));
        List<String[]> pairs = new ArrayList<>();
        for (EngGlossary row : rows) {
            if (row == null) {
                continue;
            }
            String kind = StrUtil.blankToDefault(row.getEntryKind(), NoteGlossary.KIND_PHRASE);
            pairs.add(new String[]{row.getEnPhrase(), row.getZhPhrase(), kind});
        }
        NoteGlossary.replaceExtraPhrases(pairs);
        log.info("[任务] 语料库已加载 matchCount={} (TERM/PHRASE)", pairs.size());
    }

    public Map<String, Object> page(String keyword, String status, String entryKind, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        LambdaQueryWrapper<EngGlossary> q = buildQuery(keyword, status, entryKind);
        q.orderByDesc(EngGlossary::getId);
        Page<EngGlossary> result = engGlossaryMapper.selectPage(new Page<>(p, s), q);
        List<Map<String, Object>> items = new ArrayList<>();
        for (EngGlossary row : result.getRecords()) {
            items.add(toItem(row));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("total", result.getTotal());
        out.put("page", p);
        out.put("size", s);
        out.put("items", items);
        return out;
    }

    /** 兼容旧接口：全量列表。 */
    public List<Map<String, Object>> listAll() {
        List<EngGlossary> rows = engGlossaryMapper.selectList(
                new LambdaQueryWrapper<EngGlossary>().orderByDesc(EngGlossary::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (EngGlossary row : rows) {
            out.add(toItem(row));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> create(String enPhrase, String zhPhrase, String status, String entryKind) {
        String en = requirePhrase(enPhrase, "英文");
        String zh = requirePhrase(zhPhrase, "中文");
        String st = normalizeStatus(status);
        String kind = normalizeKind(entryKind, en);
        EngGlossary dup = engGlossaryMapper.selectOne(
                new LambdaQueryWrapper<EngGlossary>().eq(EngGlossary::getEnPhrase, en).last("LIMIT 1"));
        if (dup != null) {
            throw new ServiceException(400, "英文短语已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        EngGlossary row = new EngGlossary();
        row.setEnPhrase(en);
        row.setZhPhrase(zh);
        row.setEntryKind(kind);
        row.setStatus(st);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        engGlossaryMapper.insert(row);
        reloadIntoGlossary();
        log.info("[审计] 新增语料 id={} kind={} en={}", row.getId(), kind, truncate(en, 64));
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_CREATE,
                String.valueOf(row.getId()), "新增语料 " + kind + " " + truncate(en, 40));
        return toItem(row);
    }

    @Transactional
    public Map<String, Object> update(Long id, String enPhrase, String zhPhrase, String status, String entryKind) {
        EngGlossary row = requireRow(id);
        if (enPhrase != null) {
            String en = requirePhrase(enPhrase, "英文");
            EngGlossary dup = engGlossaryMapper.selectOne(
                    new LambdaQueryWrapper<EngGlossary>()
                            .eq(EngGlossary::getEnPhrase, en)
                            .ne(EngGlossary::getId, id)
                            .last("LIMIT 1"));
            if (dup != null) {
                throw new ServiceException(400, "英文短语已存在");
            }
            row.setEnPhrase(en);
        }
        if (zhPhrase != null) {
            row.setZhPhrase(requirePhrase(zhPhrase, "中文"));
        }
        if (status != null) {
            row.setStatus(normalizeStatus(status));
        }
        if (entryKind != null) {
            row.setEntryKind(normalizeKind(entryKind, row.getEnPhrase()));
        } else if (enPhrase != null && StrUtil.isBlank(row.getEntryKind())) {
            row.setEntryKind(NoteGlossary.classifyKind(row.getEnPhrase()));
        }
        row.setUpdateTime(LocalDateTime.now());
        engGlossaryMapper.updateById(row);
        reloadIntoGlossary();
        log.info("[审计] 更新语料 id={} kind={}", id, row.getEntryKind());
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_UPDATE,
                String.valueOf(id), "更新语料");
        return toItem(row);
    }

    /** 兼容旧 upsert。 */
    @Transactional
    public Map<String, Object> upsert(String enPhrase, String zhPhrase, String status, String entryKind) {
        String en = requirePhrase(enPhrase, "英文");
        EngGlossary existing = engGlossaryMapper.selectOne(
                new LambdaQueryWrapper<EngGlossary>().eq(EngGlossary::getEnPhrase, en).last("LIMIT 1"));
        if (existing == null) {
            return create(en, zhPhrase, status, entryKind);
        }
        return update(existing.getId(), en, zhPhrase, status, entryKind);
    }

    @Transactional
    public void delete(Long id) {
        EngGlossary row = requireRow(id);
        engGlossaryMapper.deleteById(id);
        reloadIntoGlossary();
        log.info("[审计] 删除语料 id={} en={}", id, truncate(row.getEnPhrase(), 64));
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_DELETE,
                String.valueOf(id), "删除语料");
    }

    /** 按规则重算全部 entry_kind（洗库）。 */
    @Transactional
    public Map<String, Object> reclassifyAll() {
        List<EngGlossary> rows = engGlossaryMapper.selectList(null);
        int term = 0;
        int phrase = 0;
        LocalDateTime now = LocalDateTime.now();
        for (EngGlossary row : rows) {
            if (row == null) {
                continue;
            }
            String kind = NoteGlossary.classifyKind(row.getEnPhrase());
            row.setEntryKind(kind);
            row.setUpdateTime(now);
            engGlossaryMapper.updateById(row);
            if (NoteGlossary.KIND_TERM.equals(kind)) {
                term++;
            } else {
                phrase++;
            }
        }
        reloadIntoGlossary();
        log.info("[审计] 语料洗库完成 term={} phrase={} by={}",
                term, phrase, LoginContext.currentUsername());
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_UPDATE,
                "glossary", "洗库 term=" + term + " phrase=" + phrase);
        Map<String, Object> out = new HashMap<>();
        out.put("termCount", term);
        out.put("phraseCount", phrase);
        out.put("exampleCount", 0);
        out.put("total", rows.size());
        return out;
    }

    public byte[] exportExcel(String keyword, String status, String entryKind) {
        LambdaQueryWrapper<EngGlossary> q = buildQuery(keyword, status, entryKind);
        q.orderByAsc(EngGlossary::getId);
        List<EngGlossary> rows = engGlossaryMapper.selectList(q);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = ExcelUtil.getWriter(true)) {
            Workbook wb = writer.getWorkbook();
            writer.renameSheet(SHEET_EXPORT);
            Sheet sheet = writer.getSheet();
            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle dataStyle = createDataStyle(wb);

            writeTitleRow(sheet, "工程图纸 · NOTE 语料库导出", titleStyle);
            writeHeaderRow(sheet, headerStyle);
            applyColumnWidths(sheet);
            sheet.createFreezePane(0, 2);

            int rowIdx = 2;
            for (EngGlossary row : rows) {
                if (row == null) {
                    continue;
                }
                Row excelRow = sheet.createRow(rowIdx++);
                writeStyledCell(excelRow, 0, nullToEmpty(row.getEnPhrase()), dataStyle);
                writeStyledCell(excelRow, 1, nullToEmpty(row.getZhPhrase()), dataStyle);
                writeStyledCell(excelRow, 2,
                        StrUtil.blankToDefault(row.getEntryKind(), NoteGlossary.KIND_PHRASE), dataStyle);
                writeStyledCell(excelRow, 3, nullToEmpty(row.getStatus()), dataStyle);
            }
            writer.flush(out, true);
        } catch (Exception e) {
            log.error("导出语料库失败", e);
            throw new ServiceException(500, "导出失败，请稍后重试");
        }
        return out.toByteArray();
    }

    public byte[] templateExcel() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = ExcelUtil.getWriter(true)) {
            Workbook wb = writer.getWorkbook();
            writer.renameSheet(SHEET_DATA);
            Sheet sheet = writer.getSheet();
            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle exampleStyle = createExampleStyle(wb);

            writeTitleRow(sheet, "工程图纸 · NOTE 语料导入模板", titleStyle);
            writeHeaderRow(sheet, headerStyle);

            // 示例行（导入前请删除或改成真实语料）
            Row ex1 = sheet.createRow(2);
            writeStyledCell(ex1, 0, "PRESSURE SENSITIVE ADHESIVE", exampleStyle);
            writeStyledCell(ex1, 1, "压敏胶", exampleStyle);
            writeStyledCell(ex1, 2, NoteGlossary.KIND_PHRASE, exampleStyle);
            writeStyledCell(ex1, 3, STATUS_ACTIVE, exampleStyle);
            Row ex2 = sheet.createRow(3);
            writeStyledCell(ex2, 0, "LINER", exampleStyle);
            writeStyledCell(ex2, 1, "离型膜", exampleStyle);
            writeStyledCell(ex2, 2, NoteGlossary.KIND_TERM, exampleStyle);
            writeStyledCell(ex2, 3, STATUS_ACTIVE, exampleStyle);

            applyColumnWidths(sheet);
            sheet.createFreezePane(0, 2);

            // 填写说明 Sheet
            writer.setSheet(SHEET_HELP);
            Sheet help = writer.getSheet();
            CellStyle helpTitle = createHeaderStyle(wb);
            CellStyle helpBody = wb.createCellStyle();
            helpBody.setWrapText(true);
            helpBody.setVerticalAlignment(VerticalAlignment.CENTER);
            String[] tips = {
                    "1. 请在「语料导入」Sheet 填写数据；「填写说明」仅供阅读，勿在此填数据。",
                    "2. 表头固定为：英文短语、中文译文、层级、状态；不可改名、不可调换列顺序。",
                    "3. 「英文短语」「中文译文」必填，最长 256 字；英文已存在则覆盖更新。",
                    "4. 「层级」填 TERM（词）或 PHRASE（短语）；留空则按英文自动归类（短词→TERM，其余→PHRASE）。",
                    "5. TERM / PHRASE 均参与 NOTE 英译中替换；短词带词边界，减少误伤。",
                    "6. 「状态」填 ACTIVE（启用）或 DISABLED（停用）；留空默认 ACTIVE。",
                    "7. 兼容旧三列模板（英/中/状态，无层级列）；层级会按规则自动补全。",
                    "8. 模板中的斜体示例行请删除或改成真实语料后再导入。",
                    "9. 仅支持 .xlsx / .xls；请勿在「填写说明」Sheet 填数据。"
            };
            Row helpHead = help.createRow(0);
            helpHead.setHeightInPoints(24);
            writeStyledCell(helpHead, 0, "填写说明", helpTitle);
            help.setColumnWidth(0, 78 * 256);
            for (int i = 0; i < tips.length; i++) {
                Row r = help.createRow(i + 1);
                r.setHeightInPoints(22);
                Cell c = r.createCell(0);
                c.setCellValue(tips[i]);
                c.setCellStyle(helpBody);
            }

            writer.flush(out, true);
        } catch (Exception e) {
            log.error("导出语料导入模板失败", e);
            throw new ServiceException(500, "导出失败，请稍后重试");
        }
        return out.toByteArray();
    }

    @Transactional
    public Map<String, Object> importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(400, "请上传 Excel 文件");
        }
        int success = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();
        try {
            ExcelReader reader = ExcelUtil.getReader(file.getInputStream());
            if (reader.getWorkbook().getSheet(SHEET_DATA) != null) {
                reader.setSheet(SHEET_DATA);
            } else if (reader.getWorkbook().getSheet(SHEET_EXPORT) != null) {
                reader.setSheet(SHEET_EXPORT);
            }
            List<List<Object>> rows = reader.read();
            int headerRow = detectHeaderRow(rows);
            // 跳过标题/表头；兼容旧三列（英/中/状态）与新四列（英/中/层级/状态）
            for (int i = headerRow + 1; i < rows.size(); i++) {
                List<Object> row = rows.get(i);
                if (row == null || row.isEmpty()) {
                    continue;
                }
                String en = cell(row, 0);
                String zh = cell(row, 1);
                String col2 = cell(row, 2);
                String col3 = cell(row, 3);
                String kind;
                String st;
                if (isStatusToken(col2) && StrUtil.isBlank(col3)) {
                    kind = null;
                    st = col2;
                } else {
                    kind = col2;
                    st = StrUtil.blankToDefault(col3, STATUS_ACTIVE);
                }
                if (StrUtil.isBlank(en) && StrUtil.isBlank(zh)) {
                    continue;
                }
                // 跳过误写入的表头重复行
                if (COL_EN.equals(en)) {
                    continue;
                }
                try {
                    upsert(en, zh, StrUtil.blankToDefault(st, STATUS_ACTIVE), kind);
                    success++;
                } catch (Exception e) {
                    fail++;
                    if (errors.size() < 20) {
                        errors.add("第" + (i + 1) + "行：" + e.getMessage());
                    }
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "Excel 解析失败：" + e.getMessage());
        }
        log.info("[审计] 导入语料 success={} fail={} by={}", success, fail, LoginContext.currentUsername());
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_IMPORT,
                "glossary", "导入语料 success=" + success + " fail=" + fail);
        Map<String, Object> out = new HashMap<>();
        out.put("successCount", success);
        out.put("failCount", fail);
        out.put("errors", errors);
        return out;
    }

    /** 有标题行则表头在第 1 行，否则第 0 行（兼容旧模板）。 */
    private int detectHeaderRow(List<List<Object>> rows) {
        int limit = Math.min(rows.size(), 5);
        for (int i = 0; i < limit; i++) {
            if (rowContainsHeader(rows.get(i))) {
                return i;
            }
        }
        throw new ServiceException(400, "未找到表头「英文短语」，请使用下载的模板");
    }

    private static boolean rowContainsHeader(List<Object> row) {
        if (row == null) {
            return false;
        }
        for (Object cell : row) {
            if (cell != null && COL_EN.equals(String.valueOf(cell).trim())) {
                return true;
            }
        }
        return false;
    }

    private static void writeTitleRow(Sheet sheet, String title, CellStyle titleStyle) {
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, DATA_HEADERS.length - 1));
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(32);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);
        for (int i = 1; i < DATA_HEADERS.length; i++) {
            titleRow.createCell(i).setCellStyle(titleStyle);
        }
    }

    private static void writeHeaderRow(Sheet sheet, CellStyle headerStyle) {
        Row headerRow = sheet.createRow(1);
        headerRow.setHeightInPoints(22);
        for (int i = 0; i < DATA_HEADERS.length; i++) {
            writeStyledCell(headerRow, i, DATA_HEADERS[i], headerStyle);
        }
    }

    private static void applyColumnWidths(Sheet sheet) {
        sheet.setColumnWidth(0, 36 * 256);
        sheet.setColumnWidth(1, 28 * 256);
        sheet.setColumnWidth(2, 14 * 256);
        sheet.setColumnWidth(3, 12 * 256);
    }

    private static void writeStyledCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static CellStyle createTitleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createExampleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createDataStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private LambdaQueryWrapper<EngGlossary> buildQuery(String keyword, String status, String entryKind) {
        LambdaQueryWrapper<EngGlossary> q = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            String kw = keyword.trim();
            q.and(w -> w.like(EngGlossary::getEnPhrase, kw).or().like(EngGlossary::getZhPhrase, kw));
        }
        if (StrUtil.isNotBlank(status)) {
            q.eq(EngGlossary::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(entryKind)) {
            q.eq(EngGlossary::getEntryKind, entryKind.trim().toUpperCase(Locale.ROOT));
        }
        return q;
    }

    private EngGlossary requireRow(Long id) {
        if (id == null) {
            throw new ServiceException(400, "语料 ID 无效");
        }
        EngGlossary row = engGlossaryMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(404, "语料不存在");
        }
        return row;
    }

    private static String requirePhrase(String raw, String label) {
        String v = StrUtil.trim(raw);
        if (StrUtil.isBlank(v)) {
            throw new ServiceException(400, label + "短语不能为空");
        }
        if (v.length() > 256) {
            throw new ServiceException(400, label + "短语最长 256 字");
        }
        return v;
    }

    private static String normalizeStatus(String status) {
        String st = StrUtil.blankToDefault(StrUtil.trim(status), STATUS_ACTIVE).toUpperCase(Locale.ROOT);
        if (!STATUS_ACTIVE.equals(st) && !STATUS_DISABLED.equals(st)) {
            throw new ServiceException(400, "状态仅支持 ACTIVE/DISABLED");
        }
        return st;
    }

    private static String normalizeKind(String entryKind, String en) {
        if (StrUtil.isBlank(entryKind)) {
            return NoteGlossary.classifyKind(en);
        }
        String kind = entryKind.trim().toUpperCase(Locale.ROOT);
        // 旧导入若仍写 EXAMPLE：归入短语，避免误导为「不生效类型」
        if (NoteGlossary.KIND_EXAMPLE.equals(kind)) {
            return NoteGlossary.KIND_PHRASE;
        }
        if (!KINDS.contains(kind)) {
            throw new ServiceException(400, "层级仅支持 TERM/PHRASE");
        }
        return kind;
    }

    private static boolean isStatusToken(String v) {
        if (StrUtil.isBlank(v)) {
            return false;
        }
        String u = v.trim().toUpperCase(Locale.ROOT);
        return STATUS_ACTIVE.equals(u) || STATUS_DISABLED.equals(u);
    }

    private static String cell(List<Object> row, int idx) {
        if (idx >= row.size() || row.get(idx) == null) {
            return "";
        }
        return String.valueOf(row.get(idx)).trim();
    }

    private static Map<String, Object> toItem(EngGlossary row) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", row.getId());
        m.put("enPhrase", row.getEnPhrase());
        m.put("zhPhrase", row.getZhPhrase());
        m.put("entryKind", StrUtil.blankToDefault(row.getEntryKind(), NoteGlossary.KIND_PHRASE));
        m.put("enabled", STATUS_ACTIVE.equalsIgnoreCase(row.getStatus()));
        m.put("status", row.getStatus());
        m.put("createTime", row.getCreateTime());
        m.put("updateTime", row.getUpdateTime());
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
