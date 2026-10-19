package com.smartdock.fileguard;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * SVG 内容级 XSS 扫描器（file-guard L3）。
 *
 * <p>iemp 两代实现的教训在此一并修掉：逐行正则版会误报（person=/connection= 命中 on\w+=）
 * 且跨行漏报（&lt;scr+ipt&gt; 分行写就查不到）。本版用 JDK 自带 StAX 做结构化解析
 * （零新依赖——jsoup 不在本项目 classpath），判定依据是真实解析出的标签与属性：
 * ① 黑名单标签 script / foreignObject / iframe；② 任意 on* 事件属性；
 * ③ href / xlink:href 为 javascript: 协议。</p>
 *
 * <p>安全设置：解析器显式禁用 DTD 与外部实体（XXE 防护）。
 * 解析失败 fail-open——畸形 XML 浏览器也无法执行其中脚本，无攻击面。</p>
 *
 * @author smartdock
 */
public final class SvgXssScanner {

    /** 明确可执行/可嵌 HTML 的标签（小写比较） */
    private static final Set<String> BANNED_TAGS = Set.of("script", "foreignobject", "iframe");

    private SvgXssScanner() {
    }

    /**
     * 扫描 SVG 文本内容（UTF-8）。
     *
     * @param content SVG 全文字节（由调用方按深扫上限截断供应）
     * @return 命中项；干净或无法解析返回 empty
     */
    public static Optional<RiskFinding> scan(byte[] content) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // XXE 防护：禁 DTD 与外部实体（SVG 无合法 DTD 需求）
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        try {
            // ByteArrayInputStream 的 close() 是空操作，无需 try-with-resources（其声明抛 IOException 反而污染 catch 域）
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(content));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String tag = localName(reader).toLowerCase(Locale.ROOT);
                if (BANNED_TAGS.contains(tag)) {
                    return Optional.of(new RiskFinding("SVG_XSS", "SVG 含可执行标签 <" + tag + ">"));
                }
                // 事件属性（onload/onclick/onerror…）与 javascript: 协议
                // （xlink:href 的本地名同样是 href，一并覆盖）
                for (int i = 0; i < reader.getAttributeCount(); i++) {
                    String attrName = reader.getAttributeLocalName(i).toLowerCase(Locale.ROOT);
                    if (attrName.startsWith("on")) {
                        return Optional.of(new RiskFinding("SVG_XSS", "SVG 含事件属性 " + attrName));
                    }
                    if (attrName.equals("href")
                        && reader.getAttributeValue(i).trim().toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                        return Optional.of(new RiskFinding("SVG_XSS", "SVG 含 javascript: 链接"));
                    }
                }
            }
            return Optional.empty();
        } catch (XMLStreamException | RuntimeException ex) {
            // 畸形内容无法解析 → fail-open（浏览器同样无法执行）
            return Optional.empty();
        }
    }

    /** 取元素本地名（命名空间感知下 localName 可能为空，退回 QName） */
    private static String localName(XMLStreamReader reader) {
        String name = reader.getLocalName();
        return name == null || name.isEmpty() ? reader.getName().getLocalPart() : name;
    }

    /** 便捷重载：字符串入参（单测用） */
    public static Optional<RiskFinding> scan(String svg) {
        return scan(svg.getBytes(StandardCharsets.UTF_8));
    }
}
