package org.dromara.assistant.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 一键汇报导出（五需求批 R4b / Q7-A，2026-10-13）：把一条问答渲染成
 * Word（结论+表格正文）或 Excel（表格明细）供直接转发/归档。
 *
 * <p>输入=前端随请求带上 question/content（回答 markdown 原文——bufText 对直播/回放
 * 两种形态都完整），不在后端反查消息表：导出的是「用户看到的那条回答」，与库内行
 * 可能存在的分段/中断差异无关。markdown 解析走轻量行级解析（表格/标题/列表/代码块），
 * 不引 CommonMark 依赖。</p>
 *
 * <p>防护：content ≤200KB、表格 ≤64 列 ×5000 行、单元格文本 ≤2000 字截断——poi 全量
 * 在内存，防超大回答拖垮线程。</p>
 */
@Slf4j
@Service
public class AssistantReportService {

    static final int MAX_CONTENT = 200_000;
    static final int MAX_COLS = 64;
    static final int MAX_ROWS = 5_000;
    static final int MAX_CELL = 2_000;
    /** 三审回修（m3 P0）：MAX_ROWS 是单表上限，200KB 内容可拆出 N 张表叠加（万行×64 列=POI 数 GB 堆），
     *  全文档行数总量再设一道闸 */
    static final int MAX_TOTAL_ROWS = 20_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public record ReportBody(String format, String question, String content) {
    }

    public byte[] export(ReportBody body) {
        String q = body.question() == null ? "" : body.question().trim();
        String c = body.content() == null ? "" : body.content();
        if (c.length() > MAX_CONTENT) {
            c = c.substring(0, MAX_CONTENT);
        }
        boolean docx = !"xlsx".equalsIgnoreCase(body.format());
        return docx ? buildDocx(q, c) : buildXlsx(q, c);
    }

    public String fileName(ReportBody body, String userId) {
        boolean docx = !"xlsx".equalsIgnoreCase(body.format());
        return "AI助手汇报_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
            + "_" + userId + (docx ? ".docx" : ".xlsx");
    }

    /* ================= markdown 行级解析（表格/标题/列表/代码块/段落） ================= */

    record Block(String kind, String text, List<String[]> table) {
        static Block para(String t) {
            return new Block("para", t, null);
        }

        static Block table(List<String[]> rows) {
            return new Block("table", null, rows);
        }
    }

    List<Block> parse(String md) {
        List<Block> blocks = new ArrayList<>();
        int totalRows = 0;
        boolean totalTruncated = false;
        String[] lines = md.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String t = line.trim();
            if (t.isEmpty()) {
                i++;
                continue;
            }
            if (t.startsWith("```")) {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    sb.append(lines[i]).append('\n');
                    i++;
                }
                i++;
                blocks.add(new Block("code", sb.toString(), null));
                continue;
            }
            if (t.startsWith("|") && i + 1 < lines.length && isTableSep(lines[i + 1].trim())) {
                List<String[]> rows = new ArrayList<>();
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    String row = lines[i].trim();
                    if (!isTableSep(row)) {
                        rows.add(splitRow(row));
                    }
                    i++;
                    if (rows.size() >= MAX_ROWS) {
                        break;
                    }
                }
                int room = MAX_TOTAL_ROWS - totalRows;
                if (room <= 0) {
                    totalTruncated = true;
                    log.warn("[AssistantReport] 全文档表格行数已达上限 {}，其后表格整体跳过", MAX_TOTAL_ROWS);
                    i++;
                    continue;
                }
                if (rows.size() > room) {
                    rows = new ArrayList<>(rows.subList(0, room));
                    totalTruncated = true;
                    log.warn("[AssistantReport] 表格行数超全文档上限截断: total={} cap={}", totalRows + rows.size(), MAX_TOTAL_ROWS);
                }
                totalRows += rows.size();
                blocks.add(Block.table(rows));
                continue;
            }
            if (t.startsWith("#")) {
                blocks.add(new Block("head", t.replaceFirst("^#+\\s*", ""), null));
                i++;
                continue;
            }
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("•") || t.matches("^\\d+[.、)].*")) {
                blocks.add(new Block("list", t.replaceFirst("^([-*•]|\\d+[.、)])\\s*", ""), null));
                i++;
                continue;
            }
            blocks.add(Block.para(t));
            i++;
        }
        if (totalTruncated) {
            blocks.add(Block.para("（汇报内容过长：表格数据已按上限 " + MAX_TOTAL_ROWS + " 行截断）"));
        }
        return blocks;
    }

    private boolean isTableSep(String s) {
        return s.startsWith("|") && s.replaceAll("[|:\\-\\s]", "").isEmpty() && s.contains("-");
    }

    private String[] splitRow(String row) {
        String body = row.substring(1, row.endsWith("|") && row.length() > 1 ? row.length() - 1 : row.length());
        String[] cells = body.split("\\|", -1);
        int n = Math.min(cells.length, MAX_COLS);
        String[] out = new String[n];
        for (int k = 0; k < n; k++) {
            String v = cells[k].trim();
            out[k] = v.length() > MAX_CELL ? v.substring(0, MAX_CELL) + "…" : v;
        }
        return out;
    }

    /* ================= Word ================= */

    private byte[] buildDocx(String question, String content) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            run(title, "AI 助手问答汇报", 18, true);

            XWPFParagraph meta = doc.createParagraph();
            meta.setAlignment(ParagraphAlignment.CENTER);
            run(meta, "生成时间：" + LocalDateTime.now().format(TS), 9, false);

            XWPFParagraph qP = doc.createParagraph();
            run(qP, "提问", 13, true);
            XWPFParagraph qv = doc.createParagraph();
            run(qv, question.isEmpty() ? "（无）" : clip(question, MAX_CELL), 11, false);

            XWPFParagraph aP = doc.createParagraph();
            run(aP, "回答", 13, true);

            for (Block b : parse(content)) {
                switch (b.kind()) {
                    case "head" -> {
                        XWPFParagraph p = doc.createParagraph();
                        run(p, b.text(), 12, true);
                    }
                    case "para" -> {
                        XWPFParagraph p = doc.createParagraph();
                        run(p, b.text(), 11, false);
                    }
                    case "list" -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setIndentationLeft(360);
                        run(p, "· " + b.text(), 11, false);
                    }
                    case "code" -> {
                        XWPFParagraph p = doc.createParagraph();
                        XWPFRun r = p.createRun();
                        r.setFontFamily("Consolas");
                        r.setFontSize(9);
                        r.setText(clip(b.text(), MAX_CELL * 4));
                    }
                    case "table" -> writeDocxTable(doc, b.table());
                    default -> {
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Word 汇报失败: " + e.getMessage(), e);
        }
    }

    private void writeDocxTable(XWPFDocument doc, List<String[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int cols = rows.stream().mapToInt(r -> r.length).max().orElse(1);
        XWPFTable table = doc.createTable(rows.size(), cols);
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow tr = table.getRow(i);
            String[] cells = rows.get(i);
            for (int k = 0; k < cols; k++) {
                XWPFTableCell cell = tr.getCell(k);
                String v = k < cells.length ? cells[k] : "";
                cell.setText(v);
                if (i == 0) {
                    XWPFRun r = firstRun(cell);
                    if (r != null) {
                        r.setBold(true);
                    }
                }
            }
        }
    }

    private XWPFRun firstRun(XWPFTableCell cell) {
        return cell.getParagraphs().isEmpty() ? null
            : cell.getParagraphs().get(0).getRuns().isEmpty() ? null
            : cell.getParagraphs().get(0).getRuns().get(0);
    }

    private void run(XWPFParagraph p, String text, int size, boolean bold) {
        XWPFRun r = p.createRun();
        r.setFontSize(size);
        r.setBold(bold);
        r.setText(clip(text, MAX_CELL * 8));
    }

    private String clip(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /* ================= Excel ================= */

    private byte[] buildXlsx(String question, String content) {
        List<Block> blocks = parse(content);
        List<List<String[]>> tables = blocks.stream().filter(b -> "table".equals(b.kind()))
            .map(Block::table).toList();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headStyle.setFont(bold);
            if (tables.isEmpty()) {
                Sheet sh = wb.createSheet("汇报");
                Row q = sh.createRow(0);
                cell(q, 0, "提问", headStyle);
                cell(q, 1, clip(question, 30_000), null);
                Row a = sh.createRow(1);
                cell(a, 0, "回答", headStyle);
                cell(a, 1, clip(blocks.stream().map(Block::text).filter(t -> t != null)
                    .reduce((x, y) -> x + "\n" + y).orElse("（空）"), 30_000), null);
                sh.setColumnWidth(0, 12 * 256);
                sh.setColumnWidth(1, 90 * 256);
            } else {
                int idx = 1;
                for (List<String[]> rows : tables) {
                    Sheet sh = wb.createSheet("表格" + idx++);
                    for (int i = 0; i < rows.size(); i++) {
                        Row row = sh.createRow(i);
                        String[] cells = rows.get(i);
                        for (int k = 0; k < cells.length; k++) {
                            cell(row, k, cells[k], i == 0 ? headStyle : null);
                        }
                    }
                    for (int k = 0; k < rows.stream().mapToInt(r -> r.length).max().orElse(1); k++) {
                        sh.setColumnWidth(k, 22 * 256);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Excel 明细失败: " + e.getMessage(), e);
        }
    }

    private void cell(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        if (style != null) {
            c.setCellStyle(style);
        }
    }
}
