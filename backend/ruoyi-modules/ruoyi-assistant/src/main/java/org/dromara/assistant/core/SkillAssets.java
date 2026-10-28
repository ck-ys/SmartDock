package org.dromara.assistant.core;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能资产兼容门面（五需求批 R1 起家；B 批装载替换 2026-10-27 改读官方仓）。
 *
 * <p><b>现状</b>：装载已迁 {@link AssistantSkillSource}（官方 AgentSkillRepository 分层栈：
 * classpath /skills → config/skills，官方 YAML frontmatter 格式）。本类保留原公共 API
 * （{@link #skills()}/{@link #body}/{@link #exists}）做兼容门面，
 * 目录端点/注入装饰器/view_skill/Holder 零签名改动。内嵌常量表 {@link #SKILLS} 退役为
 * 零漂移护栏单测（SkillOfficialFormatZeroDiffUnitTest）的对照源，不再参与运行时装载。</p>
 *
 * <p><b>省 token 核心机制不变</b>：系统提示词只含「技能目录」（每技能一行；B 批起由官方
 * DynamicSkillMiddleware 每次调用重组，见 {@link AssistantSkillGateMiddleware}）；技能正文
 * 只在两种时机进上下文——①用户在输入框选中技能（{@link SkillInjectModelDecorator} 当轮注入，
 * 不进会话记忆）；②模型判断需要时调工具自取（官方 load_skill_through_path / 旧 view_skill
 * 同源读 {@link #body}）。目录与正文永不同时常驻。</p>
 *
 * @deprecated 装载真相在 {@link AssistantSkillSource}；新消费方请直用其
 *     {@code repositories()}/{@code all()}，本类仅存量门面（后续批次随消费方迁移收敛）。
 */
@Slf4j
@Deprecated
public final class SkillAssets {

    /** 单技能正文上限（防误放整个说明书进技能；超出截断并告警）。
     *  5b 三审回修（dsflash P1-2）：与管理端保存上限 BODY_MAX=2 万对齐——官方 load 路径
     *  读全文不截断，自研注入/view_skill 截 6000 会造成同一技能两条路径内容不一致 */
    static final int BODY_CAP = 20_000;

    /** 技能元数据 + 兜底内嵌正文（与 classpath md 同源；新增/修改技能两处同步改）。
     *  5b 批题7 甲（2026-10-28 拍板）拆除触发词：什么时候用技能由 description（一句话
     *  说明）喂模型判断——原触发词已并进各技能 description。 */
    public record Skill(String id, String name, String description, int minRole,
                        String embeddedBody) {
    }

    /** R1 首批三技能（2026-10-13 拍板 Q3=A+C；create-template 随 R4a act_create_template 同批上） */
    public static final List<Skill> SKILLS = List.of(
        new Skill("create-mission", "创建任务", "用户说「帮我建任务/建个任务/新建任务」时，把模糊意愿引导成完整草稿（类型/时间/说明问全再建）", 1, """
            你正在使用「创建任务」技能。目标：把用户模糊的建任务意愿引导成一张可直接发布的任务草稿。

            步骤：
            1. 先问清三件事（一次问全，别拆成多轮）：任务名称（给个建议名让用户改）、任务类型（填表类=每人交一份表；追加类=过程中不断追加记录）、截止时间。
            2. 用户答不全时给默认建议（如「名称先用『XX信息采集』，截止一周后」），让用户说「行」即可。
            3. 调用创建任务草稿工具，产出草稿后明确告诉用户：草稿已建好，去「任务管理」配置明细字段和责任人后才能发布；附站内链接 /dcs/mission。
            4. 用户想「从模板建」时：先去模板中心（/dcs/template）看有没有现成的，别从零建。

            禁止：不问就建；把任务类型说反；承诺草稿=已发布。
            """),
        new Skill("create-template", "创建模板", "用户说「做个模板/建个表单/建个采集模板」时，引导成字段清单齐全的模板再建（先推荐复用预设）", 1, """
            你正在使用「创建模板」技能。目标：把用户「做个模板/建个表单」的意愿引导成一份字段齐全的采集模板。

            步骤：
            1. 先推荐复用：模板中心有 30+ 系统预设（办公用品领用、活动报名、信息登记、突发事件上报、每日打卡等），用户要采的东西和预设基本对口时，先推荐直接用预设（或基于预设改字段）；用户明确要新建、或确实没有对口预设，才走下面。
            2. 新建先问清两件事（一次问全）：模板名称（用来采集什么，给个建议名）、要收集哪些信息（字段清单，让用户口述即可）。
            3. 用户只给字段名时你补齐合理类型：数量/金额/年龄类→NUMBER；日期类→DATE；固定几个选项（类别/是否/部门）→SELECT 并代拟选项让用户确认；要交证明材料→ATTACHMENT；选人→USER；其余→STRING。
            4. 关键字段（姓名/工号/联系方式类）建议必填，其余默认选填；按这个建议定，用户没意见不再追问。
            5. 调用创建模板工具（fields_json 传字段数组），出表单确认卡让用户过目；确认后告诉用户：模板可在「模板中心」查看，用它能快速建同款任务。

            禁止：不问就建；SELECT 字段不给选项；把字段类型编成 STRING/NUMBER/DATE/BOOLEAN/SELECT/USER/ATTACHMENT/ORG_CASCADE 之外的值。
            """),
        new Skill("data-reading", "数据解读", "用户说「解读数据/这数据怎么看/帮我解读」时，把查到的数字讲成人话结论（先结论后细节，口径说清）", 0, """
            你正在使用「数据解读」技能。目标：把查到的业务数字讲成领导能直接用的结论。

            规则：
            1. 先一句话结论（如「全部 60 人还差 43 人未交，集中在技术部」），再给支撑明细。
            2. 每个数字说清口径：统计的是人次还是人数、按部门还是按类别、截止什么时间。
            3. 对比着讲：和总量比（43/60=72% 未交）、和时间比（比昨天多/少了几个）、和部门间比。
            4. 有 3 个以上可比项时给一张统计图（按统计图协议）；数字全部来自工具真数。
            5. 最后给一条可执行建议（如「优先催 XX 部门」），并说明依据。
            """),
        new Skill("urge-wording", "催办话术", "用户说「帮我写催办/怎么催比较好/写个催办」时，写不伤人的催办提醒（有依据、给出路、不施压过度）", 1, """
            你正在使用「催办话术」技能。目标：帮用户起草一条得体的催办留言。

            写法（催办留言默认 100 字内）：
            1. 说事不说人：「办公区安全检查还有 3 天截止」而不是「你怎么还没交」。
            2. 给依据给出路：附截止时间、入口（任务详情页）、卡住可联系谁。
            3. 语气分级：第一次催=提醒式（「别忘了」）；第二次=说明影响（「逾期会影响部门进度」）；逾期后=协助式（「遇到困难可以联系我」）。
            4. 先问用户催第几次、催谁（全员/某部门/某人），再成稿；成稿后问「直接发还是改改」。
            发送仍走催办确认卡，你只负责写话术。
            """)
    );

    private SkillAssets() {
    }

    /** 加载后的技能（body=最终生效正文，按三层叠加定；5b 起 description=模型触发唯一依据） */
    public record LoadedSkill(String id, String name, String description, int minRole, String body) {
    }

    /**
     * 全量技能（5b 批起按租户视图；缓存与失效统一在 {@link AssistantSkillSource}——
     * TTL 30 秒兜底＋保存/广播即时失效，本门面不再自持进程级缓存）。
     * 无参版给请求线程目录端点用（LoginHelper 解析当前租户，异常兜底全局租户）。
     */
    public static List<LoadedSkill> skills() {
        return skills(currentTenant());
    }

    /** 租户版全量技能（工具线程/装饰器显式传租户用——LoginHelper 在 agent 线程不可用） */
    public static List<LoadedSkill> skills(String tenantId) {
        List<AssistantSkillSource.SkillView> views = AssistantSkillSource.all(tenantId);
        List<LoadedSkill> out = new ArrayList<>(views.size());
        for (AssistantSkillSource.SkillView v : views) {
            if (v.body() == null || v.body().isBlank()) {
                log.warn("[skill] 技能 {} 正文为空，跳过该技能", v.id());
                continue;
            }
            out.add(new LoadedSkill(v.id(), v.displayName(), v.description(), v.minRole(), v.body()));
        }
        return List.copyOf(out);
    }

    /** 请求线程当前租户（LoginHelper 不可用=agent 线程/纯单测→全局租户兜底） */
    static String currentTenant() {
        try {
            String tenantId = org.dromara.common.satoken.utils.LoginHelper.getTenantId();
            return tenantId == null || tenantId.isBlank()
                ? DatabaseSkillRepository.GLOBAL_TENANT : tenantId;
        } catch (Exception e) {
            return DatabaseSkillRepository.GLOBAL_TENANT;
        }
    }

    /** 立即失效缓存（测试通道；5b 起全量失效走 AssistantSkillSource.invalidateAll） */
    static void invalidate() {
        AssistantSkillSource.invalidateAll();
    }

    /** 技能正文（注入/自取共用，按租户视图）；id 不存在返回 null（调用方自处理）。
     *  B 批起系统提示词的技能目录段由官方中间件组装（AssistantSkillGateMiddleware），
     *  本门面不再提供 catalog 拼装（旧自研目录段已随装载替换退役）。 */
    public static String body(String skillId, int roleLevel) {
        return body(skillId, roleLevel, currentTenant());
    }

    /** 租户版正文（工具线程显式传租户） */
    public static String body(String skillId, int roleLevel, String tenantId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        for (LoadedSkill s : skills(tenantId)) {
            if (s.id().equals(skillId.trim())) {
                if (s.minRole() > roleLevel) {
                    return null;
                }
                return capBody(s);
            }
        }
        return null;
    }

    /** 技能是否存在（角色无关，请求线程版） */
    public static boolean exists(String skillId) {
        return exists(skillId, currentTenant());
    }

    /** 租户版存在性判定 */
    public static boolean exists(String skillId, String tenantId) {
        return skills(tenantId).stream().anyMatch(s -> s.id().equals(skillId));
    }

    private static String capBody(LoadedSkill s) {
        if (s.body().length() <= BODY_CAP) {
            return s.body();
        }
        log.warn("[skill] 技能 {} 正文超 {} 字符上限，截断（原长 {}）", s.id(), BODY_CAP, s.body().length());
        return s.body().substring(0, BODY_CAP);
    }
}
