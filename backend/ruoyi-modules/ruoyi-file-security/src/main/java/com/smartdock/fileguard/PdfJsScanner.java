package com.smartdock.fileguard;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDDocumentCatalogAdditionalActions;

import java.util.Locale;
import java.util.Optional;

/**
 * PDF 内嵌 JavaScript 深度扫描器（file-guard L3，iemp PDFUtil 四层扫描的内存流式移植版）。
 *
 * <p>四层检查：① OpenAction 动作；② 文档级 AdditionalActions（WC/WP/WS/DS/DP）；
 * ③ 页面级 /AA 动作；④ 全 COS 对象递归查找 "javascript" 字符串标记。</p>
 *
 * <p>对 iemp 修掉三处：不再临时落盘（入参 byte[]，深扫上限天然兜住内存——500MB 上传放开后
 * 深扫上限是必选闸）；解析异常从 fail-closed 拒传改为灰度进审（PARSE_ANOMALY，与 Q2=C
 * 分层策略一致——文件头是 %PDF 却解析不动，值得人工看一眼而非直接误杀）；
 * 第④层补上 COSObject 解引用（iemp 直接把 COSObject 传进 instanceof 链，
 * 四种类型都不匹配，第④层实为死代码——本版 while 解引用到真实 COSBase 再递归）。</p>
 *
 * @author smartdock
 */
public final class PdfJsScanner {

    private PdfJsScanner() {
    }

    /**
     * 扫描 PDF 字节内容。
     *
     * @param pdfBytes PDF 全量字节（由调用方按深扫上限截断供应，本方法不落盘）
     * @return 命中项；干净返回 empty
     */
    public static Optional<RiskFinding> scan(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDDocumentCatalog catalog = document.getDocumentCatalog();

            // ① OpenAction
            if (catalog.getOpenAction() != null
                && containsJavaScriptInActionBase(catalog.getOpenAction().getCOSObject())) {
                return Optional.of(new RiskFinding("PDF_JAVASCRIPT", "PDF 内嵌 JavaScript（OpenAction）"));
            }

            // ② 文档级 AdditionalActions
            PDDocumentCatalogAdditionalActions docActions = catalog.getActions();
            if (docActions != null
                && containsJavaScriptInActions(docActions.getWC(), docActions.getWP(),
                docActions.getWS(), docActions.getDS(), docActions.getDP())) {
                return Optional.of(new RiskFinding("PDF_JAVASCRIPT", "PDF 内嵌 JavaScript（文档级动作）"));
            }

            // ③ 页面级 /AA 动作
            if (containsPageLevelJavaScript(catalog.getPages())) {
                return Optional.of(new RiskFinding("PDF_JAVASCRIPT", "PDF 内嵌 JavaScript（页面级动作）"));
            }

            // ④ 全 COS 对象深度扫描（带环防护：Pages/Kids/Parent 回边会让无防护递归爆栈）
            COSDocument cosDoc = document.getDocument();
            for (COSObjectKey key : cosDoc.getXrefTable().keySet()) {
                if (containsJSRecursive(cosDoc.getObjectFromPool(key), java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))) {
                    return Optional.of(new RiskFinding("PDF_JAVASCRIPT", "PDF 底层对象含 javascript 标记"));
                }
            }
            return Optional.empty();
        } catch (Exception ex) {
            // %PDF 开头却解析失败：灰度进审人工过目，不直接拒传
            return Optional.of(new RiskFinding("PARSE_ANOMALY", "PDF 解析异常，无法完成安全检查"));
        }
    }

    /** 动作对象是否为 JavaScript（支持字典与动作数组两种形态） */
    private static boolean containsJavaScriptInActionBase(COSBase actionBase) {
        try {
            // 解引用到真实对象（COSObject/懒加载包装）
            while (actionBase instanceof COSObject cosObject) {
                actionBase = cosObject.getObject();
            }
            if (actionBase instanceof COSDictionary dict) {
                PDAction action = PDActionFactory.createAction(dict);
                return action instanceof PDActionJavaScript;
            }
            if (actionBase instanceof COSArray array) {
                for (COSBase item : array) {
                    COSBase deref = item instanceof COSObject cosObject ? cosObject.getObject() : item;
                    if (deref instanceof COSDictionary dict
                        && PDActionFactory.createAction(dict) instanceof PDActionJavaScript) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 页面 /AA 字典内所有动作的 JavaScript 检查 */
    private static boolean containsPageLevelJavaScript(PDPageTree pages) {
        for (PDPage page : pages) {
            COSDictionary pageDict = page.getCOSObject();
            COSBase aaBase = pageDict.getDictionaryObject(COSName.AA);
            if (aaBase instanceof COSDictionary aaDict) {
                for (COSName key : aaDict.keySet()) {
                    if (containsJavaScriptInActionBase(aaDict.getDictionaryObject(key))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 多个动作任一为 JavaScript 即命中 */
    private static boolean containsJavaScriptInActions(PDAction... actions) {
        for (PDAction action : actions) {
            if (action instanceof PDActionJavaScript) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归扫描 COS 结构，查找字符串值中的 "javascript" 标记（/S /JavaScript 动作的伴生特征）。
     * seen 为身份语义访问集，破掉 Pages→Kids→Parent 一类的对象环（iemp 无防护版遇正常 PDF 即爆栈）。
     */
    private static boolean containsJSRecursive(COSBase base, java.util.Set<COSBase> seen) {
        while (base instanceof COSObject cosObject) {
            base = cosObject.getObject();
        }
        if (base == null || !seen.add(base)) {
            return false;
        }
        if (base instanceof COSDictionary dict) {
            for (COSName key : dict.keySet()) {
                if (containsJSRecursive(dict.getDictionaryObject(key), seen)) {
                    return true;
                }
            }
        } else if (base instanceof COSArray array) {
            for (COSBase item : array) {
                if (containsJSRecursive(item, seen)) {
                    return true;
                }
            }
        } else if (base instanceof COSString str) {
            String text = str.getString();
            return text != null && text.toLowerCase(Locale.ROOT).contains("javascript");
        }
        return false;
    }
}
