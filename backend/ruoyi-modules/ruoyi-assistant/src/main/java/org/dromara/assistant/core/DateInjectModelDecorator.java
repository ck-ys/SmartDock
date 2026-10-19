package org.dromara.assistant.core;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前日期注入模型装饰器（Q8，2026-10-15 拍板A）。
 *
 * <p>每次模型调用把「今天是 YYYY-MM-DD 周X」追加为末位 system 消息——模型此前拿不到
 * 「今天」锚点，答「今天截止的/这周谁没交」只能从工具时间戳倒猜，跨天/跨年易错位。
 * 位置与 {@link MemoryInjectModelDecorator} 同款（消息尾部=动态区）：日期每天变，
 * <b>绝不进 system 前缀</b>（PromptAssets 静态红线，Q4）——尾部追加不影响缓存前缀命中。
 * ZCode 同构实证：current-date.ts 段 injectionTarget=meta_user + cacheHint=dynamic。</p>
 *
 * <p>装饰链装配为 Memory(Date(Skill(…)))，装饰器一律尾部追加、外层先追加，故实际消息序
 * 为 [原文…, 记忆段, 日期段, 技能段]（选中技能的轮次技能段才是末位）；本段位于消息列表
 * 末区、每轮按全价计费（本轮内不可能被前缀缓存命中，约 30 token/轮）。
 * 日期取 {@code LocalDate.now()}（JVM 默认时区）——依赖启动参数
 * {@code -Duser.timezone=GMT+8}，漏参时北京 0~12 点窗口「今天」会差一天（历史两轮事故
 * 同因，见根 AGENTS.md）。</p>
 */
public class DateInjectModelDecorator implements Model {

    /** 周几中文（DAY_OF_WEEK 数值直接映射，不随 JVM 语言环境漂移） */
    private static final String[] WEEK_CN = { "一", "二", "三", "四", "五", "六", "日" };

    private final Model delegate;

    public DateInjectModelDecorator(Model delegate) {
        this.delegate = delegate;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        LocalDate today = LocalDate.now();
        String dateMsg = "今天是 " + today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            + " 星期" + WEEK_CN[today.getDayOfWeek().getValue() - 1]
            + "（用户问「今天/本周/还剩几天」时以此为基准；涉及业务数据仍以工具返回的 asOf 为准）";
        List<Msg> withDate = new ArrayList<>(messages);
        withDate.add(Msg.builder().role(MsgRole.SYSTEM).textContent(dateMsg).build());
        return delegate.stream(withDate, tools, options);
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
