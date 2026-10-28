package com.smartdock.profile.event;

/**
 * 事件源常量(单源口径)。
 *
 * <p>单源重构 M1: 产品只内置一个事件源, 一切写入(业务喂数 / CSV 补录 / 管理端灌数)
 * 落库一律归入 {@link #UNIFIED}——发布入口统一覆盖, 调用方传入的 source 兼容保留但
 * 不再参与任何分表分套, 防止接入方各自造源把打分配置与数据拆散。</p>
 *
 * <p>取值沿用存量数据与配置所在的 "dcs", 不做数据迁移; 引擎各查询链路按本常量取数,
 * 禁止在新代码里裸写字符串。</p>
 *
 * @author smartdock
 */
public final class ProfileSources {

    /** 唯一内置事件源(存量事件/主体/身份链接/打分配置均在该值下)。 */
    public static final String UNIFIED = "dcs";

    private ProfileSources() {
    }
}
