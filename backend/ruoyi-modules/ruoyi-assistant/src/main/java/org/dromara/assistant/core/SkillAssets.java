package org.dromara.assistant.core;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 技能资产（五需求批 R1 / Q1-A+Q2-A，2026-10-13 拍板）：Claude Code 式渐进披露技能库。
 *
 * <p><b>省 token 核心机制</b>：系统提示词只注入「技能目录」（每技能一行=id+名称+一句话描述，
 * 预算 {@link #CATALOG_BUDGET} 字符，超预算告警并截断）；技能正文（1~4KB）只在两种时机进上下文——
 * ①用户在输入框选中技能（前端 forwardedProps.skillId 上行，{@link SkillInjectModelDecorator}
 * 当轮注入，不进会话记忆）；②模型判断需要时调 view_skill 工具自取。目录与正文永不同时常驻。</p>
 *
 * <p><b>技能定义</b>：{@code prompts/skills/<id>.md}，frontmatter 四行
 * （id/name/description/minRole: member|publisher|admin）+ 正文。加载三级兜底与
 * {@link PromptAssets} 同构：外部 {@code config/prompts/skills/}（部署期热覆写，可增删
 * 整个技能文件）→ classpath → 内嵌常量（{@code SKILLS} 与 md 同源生成，零漂移护栏单测对照）。
 * minRole 按角色裁目录：成员只见 member 技能。</p>
 */
@Slf4j
public final class SkillAssets {

    /** 目录字符预算（约 10 个技能；超出=新增技能没做收敛，告警并截到预算内） */
    static final int CATALOG_BUDGET = 600;
    /** 单技能正文上限（防误放整个说明书进技能；超出截断并告警） */
    static final int BODY_CAP = 6000;

    /** 技能元数据 + 三级兜底内嵌正文（与 classpath md 同源；新增/修改技能两处同步改）。
     *  triggers=能力① Q7（2026-10-20）触发词：用户输入含任一词时前端自动挂载该技能
     *  （可撤销徽章）；目录端点透出。保守配词——误挂比漏挂伤体验。 */
    public record Skill(String id, String name, String description, int minRole,
                        java.util.List<String> triggers, String embeddedBody) {
    }

    /** R1 首批三技能（2026-10-13 拍板 Q3=A+C；create-template 随 R4a act_create_template 同批上） */
    static final List<Skill> SKILLS = List.of(
        new Skill("create-mission", "创建任务", "把「帮我建任务」引导成完整草稿（类型/时间/说明问全再建）", 1,
        java.util.List.of("帮我建任务", "建个任务", "新建任务", "创建任务", "发起一个任务", "发个任务让大家"), """
            你正在使用「创建任务」技能。目标：把用户模糊的建任务意愿引导成一张可直接发布的任务草稿。

            步骤：
            1. 先问清三件事（一次问全，别拆成多轮）：任务名称（给个建议名让用户改）、任务类型（填表类=每人交一份表；追加类=过程中不断追加记录）、截止时间。
            2. 用户答不全时给默认建议（如「名称先用『XX信息采集』，截止一周后」），让用户说「行」即可。
            3. 调用创建任务草稿工具，产出草稿后明确告诉用户：草稿已建好，去「任务管理」配置明细字段和责任人后才能发布；附站内链接 /dcs/mission。
            4. 用户想「从模板建」时：先去模板中心（/dcs/template）看有没有现成的，别从零建。

            禁止：不问就建；把任务类型说反；承诺草稿=已发布。
            """),
        new Skill("create-template", "创建模板", "把「做个采集模板」引导成字段清单齐全的模板再建", 1,
        java.util.List.of("做个模板", "建个模板", "创建模板", "新建模板", "做个表单", "建个采集模板"), """
            你正在使用「创建模板」技能。目标：把用户「做个模板/建个表单」的意愿引导成一份字段齐全的采集模板。

            步骤：
            1. 先推荐复用：模板中心有 30+ 系统预设（办公用品领用、活动报名、信息登记、突发事件上报、每日打卡等），用户要采的东西和预设基本对口时，先推荐直接用预设（或基于预设改字段）；用户明确要新建、或确实没有对口预设，才走下面。
            2. 新建先问清两件事（一次问全）：模板名称（用来采集什么，给个建议名）、要收集哪些信息（字段清单，让用户口述即可）。
            3. 用户只给字段名时你补齐合理类型：数量/金额/年龄类→NUMBER；日期类→DATE；固定几个选项（类别/是否/部门）→SELECT 并代拟选项让用户确认；要交证明材料→ATTACHMENT；选人→USER；其余→STRING。
            4. 关键字段（姓名/工号/联系方式类）建议必填，其余默认选填；按这个建议定，用户没意见不再追问。
            5. 调用创建模板工具（fields_json 传字段数组），出表单确认卡让用户过目；确认后告诉用户：模板可在「模板中心」查看，用它能快速建同款任务。

            禁止：不问就建；SELECT 字段不给选项；把字段类型编成 STRING/NUMBER/DATE/BOOLEAN/SELECT/USER/ATTACHMENT/ORG_CASCADE 之外的值。
            """),
        new Skill("data-reading", "数据解读", "把查到的数字讲成人话结论（先结论后细节，口径说清）", 0,
        java.util.List.of("数据解读", "解读数据", "帮我解读", "分析这些数据", "这数据怎么看"), """
            你正在使用「数据解读」技能。目标：把查到的业务数字讲成领导能直接用的结论。

            规则：
            1. 先一句话结论（如「全部 60 人还差 43 人未交，集中在技术部」），再给支撑明细。
            2. 每个数字说清口径：统计的是人次还是人数、按部门还是按类别、截止什么时间。
            3. 对比着讲：和总量比（43/60=72% 未交）、和时间比（比昨天多/少了几个）、和部门间比。
            4. 有 3 个以上可比项时给一张统计图（按统计图协议）；数字全部来自工具真数。
            5. 最后给一条可执行建议（如「优先催 XX 部门」），并说明依据。
            """),
        new Skill("urge-wording", "催办话术", "写不伤人的催办提醒（有依据、给出路、不施压过度）", 1,
        java.util.List.of("催办话术", "写个催办", "帮我写催办", "怎么催比较好", "提醒话术"), """
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

    /** 加载后的技能（body=最终生效正文，按三级兜底定；triggers 恒取内嵌表=权限语义同源） */
    public record LoadedSkill(String id, String name, String description, int minRole,
                              java.util.List<String> triggers, String body) {
    }

    private static volatile List<LoadedSkill> cache;

    /** 全量技能（进程内缓存、进程生命周期内不过期——invalidate 仅测试通道；glm53 终审勘误：外部目录变更需重启生效，目录每次 run 都拼，读盘可控） */
    public static List<LoadedSkill> skills() {
        List<LoadedSkill> snap = cache;
        if (snap != null) {
            return snap;
        }
        synchronized (SkillAssets.class) {
            if (cache == null) {
                cache = loadAll();
            }
            return cache;
        }
    }

    /** 立即失效缓存（测试/热更用） */
    static void invalidate() {
        cache = null;
    }

    /** 技能目录段（追加进系统提示词；只含 id+名称+描述，正文永不进目录） */
    public static String catalog(int roleLevel) {
        StringBuilder sb = new StringBuilder("\n\n【技能】（用户可在输入框＋号里选技能，选中后其完整指引当轮自动生效；"
            + "你判断需要时也可调 view_skill 按 skill_id 取全文——只取与当前任务相关的）\n");
        int used = sb.length();
        for (LoadedSkill s : skills()) {
            if (s.minRole() > roleLevel) {
                continue;
            }
            String line = "- " + s.id() + "：" + s.name() + "——" + s.description() + "\n";
            if (used + line.length() > CATALOG_BUDGET) {
                log.warn("[skill] 技能目录超出 {} 字符预算，截断（技能数={}, 剩余未入目录={}）",
                    CATALOG_BUDGET, skills().size(), s.id());
                break;
            }
            sb.append(line);
            used += line.length();
        }
        return sb.toString();
    }

    /** 技能正文（注入/自取共用）；id 不存在返回 null（调用方自处理） */
    public static String body(String skillId, int roleLevel) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        for (LoadedSkill s : skills()) {
            if (s.id().equals(skillId.trim())) {
                if (s.minRole() > roleLevel) {
                    return null;
                }
                return capBody(s);
            }
        }
        return null;
    }

    /** 技能是否存在（角色无关） */
    public static boolean exists(String skillId) {
        return skills().stream().anyMatch(s -> s.id().equals(skillId));
    }

    private static String capBody(LoadedSkill s) {
        if (s.body().length() <= BODY_CAP) {
            return s.body();
        }
        log.warn("[skill] 技能 {} 正文超 {} 字符上限，截断（原长 {}）", s.id(), BODY_CAP, s.body().length());
        return s.body().substring(0, BODY_CAP);
    }

    private static List<LoadedSkill> loadAll() {
        List<LoadedSkill> out = new ArrayList<>(SKILLS.size());
        for (Skill s : SKILLS) {
            String body = loadExternal(s.id());
            String source = "external";
            if (body == null) {
                body = loadClasspath(s.id());
                source = "classpath";
            }
            if (body == null) {
                body = s.embeddedBody();
                source = "embedded";
            }
            body = normalize(body);
            if (body == null) {
                log.warn("[skill] 技能 {} 三级加载正文为空，跳过该技能", s.id());
                continue;
            }
            log.debug("[skill] {} 生效来源={} bytes={}", s.id(), source, body.length());
            out.add(new LoadedSkill(s.id(), s.name(), s.description(), s.minRole(), s.triggers(), body));
        }
        return List.copyOf(out);
    }

    private static String loadExternal(String id) {
        Path dir = Path.of("config", "prompts", "skills");
        Path p = dir.resolve(id + ".md");
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            String ext = Files.readString(p, StandardCharsets.UTF_8);
            return ext.isBlank() ? null : stripFrontmatter(ext);
        } catch (IOException e) {
            log.warn("[skill] 外部技能读取失败，回退 classpath: {}", p.toAbsolutePath(), e);
            return null;
        }
    }

    private static String loadClasspath(String id) {
        try (InputStream in = SkillAssets.class.getResourceAsStream("/prompts/skills/" + id + ".md")) {
            if (in == null) {
                return null;
            }
            return stripFrontmatter(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** 外部技能文件允许带 frontmatter（id/name/description/minRole 会被忽略——元数据以内嵌表为准，
     *  外部文件只覆写正文；这样管理员改文案不用碰权限语义） */
    private static String stripFrontmatter(String raw) {
        String s = raw.startsWith("﻿") ? raw.substring(1) : raw;
        if (s.startsWith("---")) {
            int end = s.indexOf("\n---", 3);
            if (end > 0) {
                int bodyStart = s.indexOf('\n', end + 4);
                s = bodyStart > 0 ? s.substring(bodyStart + 1) : "";
            }
        }
        return s;
    }

    private static String normalize(String raw) {
        String content = raw.replace("\r\n", "\n").strip();
        return content.isBlank() ? null : content;
    }
}
