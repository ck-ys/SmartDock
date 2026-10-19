package org.dromara.assistant.service.support;

import cn.hutool.core.util.StrUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.system.domain.vo.SysOssVo;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 附件正文提取器(拍板 Q5-A⁺/Q8 护栏, 2026-09-29, 实验性; 2026-09-30 大小上限参数化+异步全量提取;
 * 同日 Excel/PPT 补支持拍板)。
 *
 * <p>支持 docx(POI 段落+表格)/xlsx(按显示值逐行)/pptx(按页逐文本框)/pdf(PDFBox)/txt;
 * 其余格式与超上限文件明确拒给人话文案(上限由 {@link DcsAttachmentComparePolicyResolver}
 * 读 sys_config, 5~50MB, 超管可调)。口径: 只比文字内容不比排版——Excel 取单元格"显示值"
 * (公式算出结果), PPT 每页前加 [第N页] 定位标记, 版式/颜色/图片变化不在对比范围。
 * 提取结果按 ossId 进程内缓存(截 8000 行, 防 5MB xlsx 解压出百兆文本撑爆堆), 新上传=新 ossId 天然版本化;
 * 容量 100 条满即清空——实验期单实例够用, 不引入外部缓存。
 * 大文件(读满 10MB 未完)不走本地缓存, 由 MQ 串行提取后截行落 Redis。</p>
 */
@Service
public class OssTextExtractor {

    /** 内容对比支持的后缀白名单 */
    private static final List<String> SUPPORTED_SUFFIXES = List.of("docx", "xlsx", "pptx", "pdf", "txt");
    /** 历史默认护栏(无参 readCapped 兼容保留, 现行路径都应显式传上限) */
    private static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    private static final int CACHE_CAPACITY = 100;
    /** 异步提取结果截行上限(批3 微核心化: 旧 dcs DcsMqConstants 常量随模块退役, 本地化同值 8000) */
    static final int RESULT_MAX_LINES = 8000;

    private final Map<Long, String> cache = new ConcurrentHashMap<>();

    /**
     * 限长尝试提取(同步对比路径用): 文件读完 ≤ capBytes 返回文本(带缓存);
     * 读满 capBytes 还没完 → 返回 empty(调用方分流转异步), 不抛错——已读字节丢弃,
     * 异步侧重新读全量(sys_oss 无 size 字段, 只能读时分流, 内网重读 ~1s 可接受)。
     *
     * <p><b>截行护栏</b>: 返回与缓存都截到 8000 行(与异步结果口径一致)。xlsx 是压缩格式,
     * 5MB 文件可解出十万行文本, 不截会把堆缓存和 HTTP 响应同时撑爆。sameText 语义随之变为
     * "前 8000 行相同"——前端 diff 本就只取前 4000 行(TRUNCATED_ROW 提示), 产品口径一致。</p>
     */
    public java.util.Optional<String> tryExtract(SysOssVo oss, long capBytes) {
        if (oss == null || oss.getOssId() == null) {
            throw new ServiceException("文件不存在");
        }
        String cached = cache.get(oss.getOssId());
        if (cached != null) {
            return java.util.Optional.of(cached);
        }
        byte[] bytes;
        boolean oversize;
        try (InputStream in = OssFactory.instance(oss.getService()).getObjectContent(oss.getFileName())) {
            ReadOutcome outcome = readUpTo(in, capBytes);
            bytes = outcome.bytes();
            oversize = outcome.oversize();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文件读取失败，请稍后重试");
        }
        if (oversize) {
            return java.util.Optional.empty();
        }
        String text = capLines(extractFromBytes(normalizeSuffix(oss.getFileSuffix()), bytes),
            RESULT_MAX_LINES).text();
        if (cache.size() >= CACHE_CAPACITY) {
            cache.clear();
        }
        cache.put(oss.getOssId(), text);
        return java.util.Optional.of(text);
    }

    /**
     * 全量提取(MQ 异步路径专用): 读到 maxBytes 上限为止, 超限抛人话护栏;
     * 结果不进本地缓存(大文本不吃 JVM 堆, 由调用方截行后落 Redis)。
     */
    public String extractUncapped(SysOssVo oss, long maxBytes) {
        return extractUncapped(oss, maxBytes, null);
    }

    /**
     * 同上，超限话术分场景（ISSUE-F14-09 修，2026-10-14）：默认文案"仅支持文件级对比"
     * 是附件对比功能的话术，助手附件解析等其它场景传入自己的话术防串台；
     * overLimitMessage=null 沿用对比功能默认文案（MQ 消费端行为不变）。
     */
    public String extractUncapped(SysOssVo oss, long maxBytes, String overLimitMessage) {
        if (oss == null || oss.getOssId() == null) {
            throw new ServiceException("文件不存在");
        }
        byte[] bytes;
        try (InputStream in = OssFactory.instance(oss.getService()).getObjectContent(oss.getFileName())) {
            bytes = readCapped(in, maxBytes, overLimitMessage);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文件读取失败，请稍后重试");
        }
        return extractFromBytes(normalizeSuffix(oss.getFileSuffix()), bytes);
    }

    private static String normalizeSuffix(String suffix) {
        String value = StrUtil.blankToDefault(suffix, "").trim().toLowerCase();
        return value.startsWith(".") ? value.substring(1) : value;
    }

    /** 单次读取结果: bytes=已读内容, oversize=是否读满上限还没完 */
    private record ReadOutcome(byte[] bytes, boolean oversize) {}

    /** 限长读: 超过 5MB(默认)立即拒(防大文件拖垮解析); 兼容旧调用与单测 */
    public static byte[] readCapped(InputStream in) {
        return readCapped(in, DEFAULT_MAX_BYTES);
    }

    /** 限长读: 超过 limitBytes 立即拒, 文案带实际 MB 数(管理员改参数后文案如实跟随) */
    public static byte[] readCapped(InputStream in, long limitBytes) {
        return readCapped(in, limitBytes, null);
    }

    /** 同上，超限话术可定制（F14-09 分场景；null=对比功能默认文案） */
    public static byte[] readCapped(InputStream in, long limitBytes, String overLimitMessage) {
        ReadOutcome outcome = readUpTo(in, limitBytes);
        if (outcome.oversize()) {
            throw new ServiceException(overLimitMessage != null ? overLimitMessage
                : "文件较大（超过" + toMb(limitBytes) + "MB），仅支持文件级对比");
        }
        return outcome.bytes();
    }

    /** 读到 limitBytes 为止不抛错, 由调用方决定超限语义(tryExtract 分流 / readCapped 拒绝) */
    private static ReadOutcome readUpTo(InputStream in, long limitBytes) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > limitBytes) {
                    return new ReadOutcome(buf.toByteArray(), true);
                }
                buf.write(chunk, 0, n);
            }
            return new ReadOutcome(buf.toByteArray(), false);
        } catch (Exception e) {
            throw new ServiceException("文件读取失败，请稍后重试");
        }
    }

    private static long toMb(long bytes) {
        return bytes / 1024 / 1024;
    }

    /** 截行结果: text=截后文本, truncated=是否发生了截断 */
    public record CappedText(String text, boolean truncated) {}

    /** 提取文本截到前 maxLines 行(逐字符数行, 避免 50MB 文本 split 出巨型数组); 行数不超过时原样返回 */
    public static CappedText capLines(String text, int maxLines) {
        int lineCount = 0;
        int cutIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++lineCount >= maxLines) {
                cutIndex = i;
                break;
            }
        }
        if (cutIndex < 0) {
            return new CappedText(text, false);
        }
        return new CappedText(text.substring(0, cutIndex), true);
    }


    /** 按后缀提取正文(公开静态供单测直接喂字节); 提不出字(扫描件/纯图片/加密)统一在此拒绝 */
    public static String extractFromBytes(String suffix, byte[] bytes) {
        String text;
        try {
            text = switch (suffix) {
                case "docx" -> extractDocx(bytes);
                case "xlsx" -> extractXlsx(bytes);
                case "pptx" -> extractPptx(bytes);
                case "pdf" -> {
                    try (PDDocument doc = Loader.loadPDF(bytes)) {
                        yield new PDFTextStripper().getText(doc);
                    }
                }
                case "txt" -> new String(bytes, StandardCharsets.UTF_8);
                default -> throw new ServiceException("该格式（." + suffix + "）暂不支持内容对比，可下载后在本地比较");
            };
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文件解析失败，可能已损坏或格式不符");
        }
        if (StrUtil.isBlank(text)) {
            throw new ServiceException("未能从该文件提取出文字（可能是扫描件、纯图片版或加密文档）");
        }
        return text;
    }

    /**
     * Excel 逐行提取(2026-09-30 拍板补支持): 每个工作表前加 [工作表:名] 定位行,
     * 数据行单元格按"显示值"制表符拼接(公式算结果/日期按格式/数字不带科学计数),
     * 整行空白的行跳过(减折叠噪音)。口径: 只比内容, 不比格式/颜色/合并单元格。
     */
    private static String extractXlsx(byte[] bytes) throws Exception {
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                XSSFSheet sheet = wb.getSheetAt(s);
                if (s > 0) {
                    sb.append('\n');
                }
                sb.append("[工作表:").append(sheet.getSheetName()).append("]\n");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    short last = row.getLastCellNum();
                    for (int c = 0; c < last; c++) {
                        if (line.length() > 0) {
                            line.append('\t');
                        }
                        line.append(displayText(row.getCell(c), formatter, evaluator));
                    }
                    if (StrUtil.isBlank(line.toString())) {
                        continue;
                    }
                    sb.append(line).append('\n');
                }
            }
            return sb.toString().trim();
        }
    }

    /** 单元格显示值: 公式先算(算不动回落缓存值/公式文本), 其余按 Excel 显示格式 */
    private static String displayText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            try {
                return formatter.formatCellValue(cell, evaluator).trim();
            } catch (Exception e) {
                return StrUtil.nullToEmpty(cell.getCellFormula()).trim();
            }
        }
        return formatter.formatCellValue(cell).trim();
    }

    /**
     * PPT 按页提取(2026-09-30 拍板补支持): 每页前加 [第N页] 定位行, 页内文本框逐个成段
     * (组合形状递归进内层, 表格按行制表符拼接)。口径: 只比文字, 不比版式/图片/动画。
     */
    private static String extractPptx(byte[] bytes) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                sb.append("[第").append(i + 1).append("页]\n");
                collectShapeText(slides.get(i).getShapes(), sb);
            }
            return sb.toString().trim();
        }
    }

    /** 形状树递归取文字: 文本框取整段, 组合形状下钻, 表格按行拼 */
    private static void collectShapeText(List<? extends XSLFShape> shapes, StringBuilder sb) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTextShape textShape) {
                String text = textShape.getText();
                if (StrUtil.isNotBlank(text)) {
                    sb.append(text.trim()).append('\n');
                }
            } else if (shape instanceof XSLFGroupShape group) {
                collectShapeText(group.getShapes(), sb);
            } else if (shape instanceof XSLFTable table) {
                for (XSLFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XSLFTableCell cell : row.getCells()) {
                        if (line.length() > 0) {
                            line.append('\t');
                        }
                        line.append(StrUtil.nullToEmpty(cell.getText()).trim());
                    }
                    if (StrUtil.isNotBlank(line.toString())) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
    }

    private static String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(StrUtil.nullToEmpty(p.getText())).append('\n'));
            for (var table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (line.length() > 0) {
                            line.append('\t');
                        }
                        line.append(StrUtil.nullToEmpty(cell.getText()));
                    }
                    sb.append(line).append('\n');
                }
            }
            return sb.toString().trim();
        }
    }

    /** 供归属校验复用: 当前支持内容对比的后缀 */
    public static boolean isSupportedSuffix(String suffix) {
        return SUPPORTED_SUFFIXES.contains(normalizeSuffix(suffix));
    }

    static List<String> supportedSuffixes() {
        return SUPPORTED_SUFFIXES;
    }
}
