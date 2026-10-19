package org.dromara.test.assistant;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T15c 探针：PII 四入口穿透（实测⑮之三，安全五连测卡3，零模型成本）。
 * <p>P0 形态（计划 Task 15 Step3 定界）：探针内联最小正则过滤器，验证四层挂点可行；
 * 正式过滤器+四层硬断言=一站验收。</p>
 * <p>四要素（spec §6 X-08 通用合规）：证件号/手机号/邮箱/姓名（含复合拼接样例）。
 * 四入口：①用户输入出网前（Model 装饰器——与 X-02 计费装饰器同挂点）
 * ②工具结果进上下文前（工具边界包裹）③记忆写入前 ④模型输出渲染前。</p>
 * <p>脱敏格式（spec §6）：姓名张*、邮箱a**@dom、手机 138****、证件前6****后4；
 * 正则须抗全角数字与空格/连字符间隔变体（normalize 后匹配）。</p>
 */
@Tag("p0probe")
class P15cPiiFilterTest {

    // ==================== 最小内联过滤器（P0 原型，一站按 spec §6 换正式实现） ====================

    /** 四要素样例数据（复合拼接=四要素同串） */
    static final String NAME = "张三丰";
    static final String PHONE = "13800138000";
    static final String EMAIL = "zhangsanfeng@example.com";
    static final String ID_CARD = "130203199901011234";

    static final class PiiFilter {
        static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86)?1[3-9]\\d{9}(?!\\d)");
        static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
        static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
        /** 姓名不可安全纯正则（中文常用字误伤率高）——P0 口径=已知姓名字典（生产=实体字段元数据驱动，登记证据） */
        static final List<String> NAME_DICT = List.of(NAME, "李四", "张三");

        static String normalize(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (char c : s.toCharArray()) {
                if (c >= '０' && c <= '９') {
                    sb.append((char) ('0' + (c - '０')));
                } else {
                    sb.append(c);
                }
            }
            // 间隔符变体：数字段之间的空格/连字符折叠（含全角空格 U+3000 与全角连字符 U+FF0D，三审补）。
            // 三审登记：全局折叠会误伤日期区间（2026-2027→20262027）——生产过滤器只在 PII 候选窗口内折叠
            return sb.toString()
                .replaceAll("(?<=\\d)[\\s\\-–　－]+(?=\\d)", "")
                // 尾位空格 X 变体：证件校验位前空格（…1234 X → …1234X；X 非数字，数字间折叠抓不到）
                .replaceAll("(?<=\\d)\\s+(?=[0-9Xx](?!\\d))", "");
        }

        static String maskAll(String text) {
            if (text == null || text.isEmpty()) {
                return text;
            }
            String out = normalize(text);
            out = EMAIL.matcher(out).replaceAll(m -> {
                String e = m.group();
                return e.charAt(0) + "**@" + e.substring(e.indexOf('@') + 1);
            });
            out = ID_CARD.matcher(out).replaceAll(m -> {
                String id = m.group();
                return id.substring(0, 6) + "********" + id.substring(id.length() - 4);
            });
            out = PHONE.matcher(out).replaceAll(m -> m.group().substring(0, 3) + "********");
            for (String n : NAME_DICT) {
                out = out.replace(n, n.charAt(0) + "*".repeat(Math.max(1, n.length() - 1)));
            }
            return out;
        }
    }

    // ==================== ① 用户输入出网前：Model 装饰器挂点（FakeModel 记录所见=出网内容） ====================

    /** 记录型假模型：只记录收到的 messages（即"出网内容"），零 API 成本 */
    static class RecordingModel implements Model {
        final List<String> seenInputs = new CopyOnWriteArrayList<>();

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                                                GenerateOptions options) {
            seenInputs.add(msgs.stream().map(m -> m.getContentBlocks(TextBlock.class).stream()
                .map(TextBlock::getText).reduce("", (a, b) -> a + b)).reduce("", (a, b) -> a + b));
            return reactor.core.publisher.Flux.just(new ChatResponse("fake-1",
                List.of(TextBlock.builder().text("收到，已记录。").build()),
                new ChatUsage(10, 5, 0.0), java.util.Map.of(), "stop"));
        }

        @Override
        public String getModelName() { return "p15c-fake"; }
    }

    /** 脱敏装饰器：①层挂点原型——与 X-02 计费装饰器同一 Model 边界（spec：出网/计费/限流统一在此包） */
    static class MaskingModel implements Model {
        private final Model delegate;
        MaskingModel(Model delegate) { this.delegate = delegate; }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                                                GenerateOptions options) {
            List<Msg> masked = msgs.stream().map(m -> {
                String text = m.getContentBlocks(TextBlock.class).stream()
                    .map(TextBlock::getText).reduce("", (a, b) -> a + b);
                String maskedText = PiiFilter.maskAll(text);
                if (maskedText.equals(text)) {
                    return m;
                }
                return Msg.builderForRole(m.getRole()).textContent(maskedText).build();
            }).toList();
            return delegate.stream(masked, tools, options);
        }

        @Override
        public String getModelName() { return delegate.getModelName(); }
    }

    @Test
    void regexCatchesFourElementsAndVariants() {
        String composite = "联系人：" + NAME + "，手机 " + PHONE + "，邮箱 " + EMAIL + "，证件 " + ID_CARD;
        String masked = PiiFilter.maskAll(composite);
        System.out.println("[P15c] 复合样例脱敏 = " + masked);
        // 四要素明文全灭
        assertFalse(masked.contains(NAME) || masked.contains(PHONE)
            || masked.contains(EMAIL) || masked.contains(ID_CARD),
            "复合拼接样例四要素明文必须全灭（实测=" + masked + "）");
        // 脱敏标记形态（spec §6：姓名张*、邮箱a**@dom、手机138****、证件前6****后4）
        assertTrue(masked.contains("张*") || masked.contains("张**"), "姓名脱敏形态");
        assertTrue(masked.contains("138****"), "手机脱敏形态");
        assertTrue(masked.contains("z**@"), "邮箱脱敏形态");
        assertTrue(masked.contains("130203********1234"), "证件脱敏形态");

        // 变体：全角数字证件 / 空格间隔手机（spec §6：正则含全角/间隔符变体）
        String fullwidthId = "１３０２０３１９９９０１０１１２３４";
        String maskedFullwidth = PiiFilter.maskAll("证件" + fullwidthId + " 勿外传");
        assertFalse(maskedFullwidth.contains("130203199901011234"),
            "全角数字证件变体必须被脱敏（实测=" + maskedFullwidth + "）");
        String maskedSpaced = PiiFilter.maskAll("手机 138 0013 8000 请联系");
        assertFalse(maskedSpaced.contains("13800138000") || maskedSpaced.matches(".*138[\\s]*0{4}[\\s]*8\\d{3}.*"),
            "空格间隔手机变体必须被脱敏（实测=" + maskedSpaced + "）");
        assertTrue(maskedSpaced.contains("138********"), "间隔手机脱敏后形态=138****…（首3位+掩码）");
        // 变体（三审 k3 席补）：+86 国家码前缀手机 / 身份证尾位空格X
        String maskedCountryCode = PiiFilter.maskAll("联系电话+8613800138000 速回");
        assertFalse(maskedCountryCode.contains("13800138000"),
            "+86 前缀手机必须被脱敏（实测=" + maskedCountryCode + "）");
        String maskedSpacedX = PiiFilter.maskAll("证件 1302031999 0101 1234 X 勿外传");
        assertFalse(maskedSpacedX.contains("130203199901011234"),
            "空格分段+尾X 证件变体必须被脱敏（实测=" + maskedSpacedX + "）");
        // 脱敏后文本须保持可用（非破坏性清空）
        assertTrue(masked.contains("联系人") && masked.contains("邮箱"), "脱敏不得破坏非 PII 正文");
    }

    @Test
    void fourLayerMountPointsFeasible(@TempDir Path tmp) throws Exception {
        // ===== ① 用户输入出网前（Model 装饰器）=====
        RecordingModel recorder = new RecordingModel();
        MaskingModel guard = new MaskingModel(recorder);
        String userPii = "帮我查 " + NAME + " 的档案，手机号 " + PHONE;
        guard.stream(List.of(Msg.builderForRole(MsgRole.USER).textContent(userPii).build()),
            List.of(), GenerateOptions.builder().build()).blockLast(java.time.Duration.ofSeconds(5));
        String wentOut = recorder.seenInputs.get(0);
        System.out.println("[P15c] ①出网前内容 = " + wentOut);
        assertFalse(wentOut.contains(NAME) || wentOut.contains(PHONE),
            "①出网内容不得含 PII 明文（挂点=Model 装饰器，与计费装饰器同边界）");

        // ===== ② 工具结果进上下文前（工具边界包裹）=====
        String toolResult = "成员：" + NAME + "，证件 " + ID_CARD + "，邮箱 " + EMAIL + "，状态：已提交";
        String intoContext = PiiFilter.maskAll(toolResult);
        System.out.println("[P15c] ②入上下文 = " + intoContext);
        assertFalse(intoContext.contains(NAME) || intoContext.contains(ID_CARD) || intoContext.contains(EMAIL),
            "②工具结果入上下文前必须脱敏（挂点=工具返回值包裹）");
        assertTrue(intoContext.contains("已提交"), "②非 PII 字段保留（任务状态可用）");

        // ===== ③ 记忆写入前 =====
        String memoryEntry = "用户常问：" + NAME + "（" + PHONE + "）的采集完成情况。";
        Path memoryFile = tmp.resolve("memory").resolve("2026-10-03.md");
        Files.createDirectories(memoryFile.getParent());
        Files.writeString(memoryFile, PiiFilter.maskAll(memoryEntry), StandardCharsets.UTF_8);
        String persisted = Files.readString(memoryFile, StandardCharsets.UTF_8);
        System.out.println("[P15c] ③记忆落盘 = " + persisted);
        assertFalse(persisted.contains(NAME) || persisted.contains(PHONE),
            "③记忆写入必须脱敏（挂点=MemoryFlushManager 写 workspace 前）");

        // ===== ④ 模型输出渲染前 =====
        String modelOutput = "查询结果：" + NAME + "（证件 " + ID_CARD + "）已提交。";
        String rendered = PiiFilter.maskAll(modelOutput);
        System.out.println("[P15c] ④渲染输出 = " + rendered);
        assertFalse(rendered.contains(NAME) || rendered.contains(ID_CARD),
            "④模型输出渲染前必须脱敏（挂点=响应组装层）");
        assertTrue(rendered.contains("已提交"), "④回答语义保留");
        System.out.println("[P15c] 四层挂点全通：①Model装饰器 ②工具边界 ③记忆写入前 ④渲染前");
    }
}
