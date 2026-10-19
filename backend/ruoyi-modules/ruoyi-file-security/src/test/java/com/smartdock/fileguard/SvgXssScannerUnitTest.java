package com.smartdock.fileguard;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SVG XSS 扫描单测（file-guard 批一，合同#4 的验收依据）。
 * 重点回归：iemp 逐行正则版的误报（person= 命中 on\w+=）与跨行漏报必须不复现。
 *
 * @author smartdock
 */
@Tag("dev")
class SvgXssScannerUnitTest {

    @Test
    void cleanSvgPasses() {
        String clean = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"5\" cy=\"5\" r=\"4\"/></svg>";
        assertThat(SvgXssScanner.scan(clean)).isEmpty();
    }

    @Test
    void scriptTagHit() {
        String evil = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";
        assertThat(SvgXssScanner.scan(evil)).hasValueSatisfying(f -> {
            assertThat(f.rule()).isEqualTo("SVG_XSS");
            assertThat(f.message()).contains("script");
        });
    }

    @Test
    void eventAttributeHit() {
        String evil = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><rect/></svg>";
        assertThat(SvgXssScanner.scan(evil)).hasValueSatisfying(f ->
            assertThat(f.message()).contains("onload"));
    }

    @Test
    void javascriptHrefHit() {
        String evil = "<svg xmlns=\"http://www.w3.org/2000/svg\"><a href=\"javascript:alert(1)\"><text>x</text></a></svg>";
        assertThat(SvgXssScanner.scan(evil)).hasValueSatisfying(f ->
            assertThat(f.message()).contains("javascript:"));
    }

    @Test
    void xlinkJavascriptHrefHit() {
        String evil = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            + "<a xlink:href=\"javascript:alert(1)\"><text>x</text></a></svg>";
        assertThat(SvgXssScanner.scan(evil)).isPresent();
    }

    @Test
    void foreignObjectAndIframeHit() {
        assertThat(SvgXssScanner.scan("<svg xmlns=\"http://www.w3.org/2000/svg\"><foreignObject><body>x</body></foreignObject></svg>")).isPresent();
        assertThat(SvgXssScanner.scan("<svg xmlns=\"http://www.w3.org/2000/svg\"><iframe src=\"x\"/></svg>")).isPresent();
    }

    @Test
    void iempFalsePositiveFixed() {
        // iemp 的 on\w+= 正则会误报 person=/connection=；结构化解析只认真实事件属性
        String benign = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>person=5, connection=stable, once=2</text></svg>";
        assertThat(SvgXssScanner.scan(benign)).isEmpty();
    }

    @Test
    void malformedXmlFailsOpen() {
        // 畸形 XML 浏览器无法执行脚本 → fail-open
        assertThat(SvgXssScanner.scan("<svg><unclosed")).isEmpty();
        assertThat(SvgXssScanner.scan("not xml at all")).isEmpty();
        assertThat(SvgXssScanner.scan((byte[]) null)).isEmpty();
        assertThat(SvgXssScanner.scan(new byte[0])).isEmpty();
    }
}
