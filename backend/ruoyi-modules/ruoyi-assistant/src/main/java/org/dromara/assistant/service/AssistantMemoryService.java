package org.dromara.assistant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.domain.DcsAssistantMemory;
import org.dromara.assistant.domain.DcsAssistantMemoryPref;
import org.dromara.assistant.mapper.AssistantConversationMapper;
import org.dromara.assistant.mapper.AssistantMemoryMapper;
import org.dromara.assistant.mapper.AssistantMemoryPrefMapper;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 助手长期记忆服务（二站④ AU-53，2026-10-08；ckys3 解锁板题7~题10 案1，2026-11-12 扩）。
 *
 * <p>链路：save_memory 工具 → {@link #save}（PII 脱敏+去重+容量闸+同主题覆盖）→ 落库；
 * {@link org.dromara.assistant.core.MemoryInjectModelDecorator} 每次模型调用 →
 * {@link #digest}（按当前问题相关性取数 + 字数额度，30s 进程内缓存）注入「用户长期记忆」
 * 系统消息；记忆页 → 清单/搜索/编辑/导出/清空，本人可查可改可删。</p>
 *
 * <p>四题口径（拍板页 v8.2 案1）：
 * ① 题7：{@code memory_type} 按关键词推断（user/project/team/reference），{@code conversation_id}
 * 由 threadId 反查作「出处」，行内编辑走 {@link #edit}（属主谓词+脱敏+长度闸）。
 * ② 题8：{@link #topicKeyOf} 只认「称呼/语言/催办默认/时区」这类参数型偏好；同键新条落库前
 * 把旧 ACTIVE 条置 SUPERSEDED 并记 superseded_by（留痕可回滚），其余内容仍只做精确去重。
 * ③ 题9：{@link #digest} 按问题关键词打分取 TopK 再按 {@link #MAX_DIGEST_CHARS} 截断；
 * 无命中返回 null（不注入）。
 * ④ 题10：个人开关（缺行=开）+ 90 天未用归档（只认 last_used_at，非创建时间）+ 导出/清空。</p>
 *
 * <p>缓存口径：进程内 30s TTL（写后立即 evict 本进程；多节点部署下其它节点最迟 30s 过期——
 * 记忆注入对时效不敏感，可接受，注释钉死防误当 bug 排查）。缓存键含问题指纹，换问题不复用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantMemoryService {

    /** 单条上限（与表列宽一致） */
    static final int MAX_CONTENT = 200;
    /** 每人容量上限（防模型滥存刷库；满=提示清理后再存） */
    public static final int MAX_PER_USER = 50;
    /** 每轮注入字数上限（题9 案1：条数上限≠注入量；示例值，可按经验调整） */
    static final int MAX_DIGEST_CHARS = 800;
    /** 每轮最多注入条数（打分后 TopK） */
    static final int MAX_DIGEST_ITEMS = 8;
    /** 归档阈值：90 天没被注入过 */
    static final int ARCHIVE_DAYS = 90;
    private static final long DIGEST_TTL_MS = 30_000;

    /** 状态机（与 DDL 注释一致） */
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_SUPERSEDED = "SUPERSEDED";
    public static final String ST_ARCHIVED = "ARCHIVED";

    /** 参数型偏好主题：命中才做同主题覆盖（保守口径——事实类内容绝不自动覆盖） */
    private static final List<Map.Entry<String, Pattern>> TOPIC_RULES = List.of(
        // 「称呼」单独成词就够：模型常把「以后叫我老王」改写成「称呼用户为老王」，
        // 只认「叫我/称呼我」会漏判 → 同一个人并存两条互相矛盾的称呼（2026-11-12 活体抓到）
        Map.entry("call:name", Pattern.compile("叫我|称呼|喊我|我的名字|名字叫")),
        Map.entry("reply:lang", Pattern.compile("用中文|用英文|说中文|说英文|语言|英文回答|中文回答")),
        Map.entry("remind:default", Pattern.compile("催办|默认提醒|默认时间|提醒我")),
        Map.entry("time:zone", Pattern.compile("时区|工作日|上班时间|下班时间|工作时间|作息")));

    /** 类型推断规则（顺序即优先级：team > project > user；都不中→reference） */
    private static final Pattern TYPE_TEAM = Pattern.compile(
        "王工|张工|李工|赵工|刘工|同事|同学|主管|领导|负责人|负责人是|对接人|经理");
    private static final Pattern TYPE_PROJECT = Pattern.compile(
        "项目|出差|行程|周会|例会|会议|任务|截止|上线|部署|版本|迭代|需求|工单|排期");
    private static final Pattern TYPE_USER = Pattern.compile(
        "叫我|称呼|以后都|默认|偏好|喜欢|不喜欢|不要|别用|习惯|回复|回答|语气|风格");

    /** 打分：中文按二元组 + 英文/数字 token 重叠；停用字避免「的/了/是」刷分 */
    private static final Set<Character> STOP_CHARS = Set.of(
        '的', '了', '是', '在', '我', '你', '他', '她', '和', '与', '就', '都', '也', '吗', '呢',
        '把', '被', '给', '让', '这', '那', '有', '没', '不', '要', '会', '能', '个', '一');

    private final AssistantMemoryMapper memoryMapper;
    private final AssistantMemoryPrefMapper prefMapper;
    private final AssistantConversationMapper conversationMapper;
    private final AssistantPiiFilter piiFilter;
    /** 回合记忆账本（题8/题9 会话内可见；Enricher 在 RUN_FINISHED 取走发帧） */
    private final org.dromara.assistant.core.AssistantMemoryTurnLedger turnLedger;

    /** digest 缓存：key=tenant:user:questionHash → [0]=过期时刻, [1]=DigestResult（null=查过无命中） */
    private final Map<String, Object[]> digestCache = new ConcurrentHashMap<>();

    /**
     * 落库结果（三批③，2026-11-01；题8 扩）：message=人话结果；memoryId=落库行 id；
     * overwritten=本次被同主题覆盖的旧条内容（无覆盖为空列表，供会话内「记忆已更新」卡）。
     */
    public record SaveOutcome(String message, Long memoryId, List<String> overwritten) {
        public SaveOutcome(String message, Long memoryId) {
            this(message, memoryId, List.of());
        }
    }

    /** 注入结果（题9）：text=拼好的摘要；count/条数；chars=字数；ids=命中行（回写 last_used_at） */
    public record DigestResult(String text, int count, int chars, List<Long> ids) {
    }

    /** save_memory 工具入口（旧签名，兼容存量调用）：只取人话结果 */
    public String save(String tenantId, Long userId, String raw) {
        return saveReturningId(tenantId, userId, raw, null).message();
    }

    /** save_memory 工具入口（题7 出处版）：threadId 可空，能查到会话就记 conversation_id */
    public SaveOutcome saveReturningId(String tenantId, Long userId, String raw, String threadId) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            return new SaveOutcome("内容是空的，没有保存", null);
        }
        if (!isUserEnabled(tenantId, userId)) {
            return new SaveOutcome("你已关闭长期记忆，这次没有保存（想开启请到「AI 助手记忆」页打开）", null);
        }
        if (content.length() > MAX_CONTENT) {
            content = content.substring(0, MAX_CONTENT);
        }
        // PII 脱敏（手机号/身份证不进长期记忆——记忆页用户可见，更要守住）
        content = piiFilter.mask(content, tenantId);
        if (memoryMapper.countByContent(tenantId, userId, content) > 0) {
            return new SaveOutcome("这条已经记过了，不重复保存", null);
        }
        if (memoryMapper.countByUser(tenantId, userId) >= MAX_PER_USER) {
            return new SaveOutcome("记忆最多存 " + MAX_PER_USER + " 条：请在「AI 助手记忆」页删掉不再需要的，再让我记新的", null);
        }

        String topicKey = topicKeyOf(content);
        List<DcsAssistantMemory> sameTopic = topicKey == null
            ? List.of() : memoryMapper.listActiveByTopic(tenantId, userId, topicKey);

        DcsAssistantMemory row = new DcsAssistantMemory();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setContent(content);
        row.setSource("AI");
        row.setMemoryType(detectType(content));
        row.setTopicKey(topicKey);
        row.setStatus(ST_ACTIVE);
        row.setConversationId(resolveConversationId(tenantId, threadId));
        row.setCreateBy(userId);
        row.setCreateTime(new Date());
        memoryMapper.insert(row);

        // 题8：同主题旧条留痕（不删、可回滚）
        List<String> overwritten = new ArrayList<>();
        Date now = new Date();
        for (DcsAssistantMemory old : sameTopic) {
            if (memoryMapper.supersedeOwn(tenantId, userId, old.getId(), row.getId(), now) > 0) {
                overwritten.add(old.getContent());
                // 会话内「记忆已更新」小卡（题8）：旧→新，随本回合 memory_turn 帧下发
                // 键用 threadId 而非 ThreadLocal 里的 runId——工具跑在 loom 线程，读不到 run 上下文
                turnLedger.recordSuperseded(threadId, old.getContent(), content);
            }
        }
        evict(tenantId, userId);
        log.info("[AssistantMemory] 已保存 tenant={} user={} contentLen={} memoryId={} type={} topic={} conv={} superseded={}",
            tenantId, userId, content.length(), row.getId(), row.getMemoryType(), topicKey,
            row.getConversationId(), overwritten.size());
        return new SaveOutcome("已记住：" + content, row.getId(), overwritten);
    }

    /** 生效中清单（新→旧；题7 主列表） */
    public List<DcsAssistantMemory> listForUser(String tenantId, Long userId) {
        return memoryMapper.listByUserStatus(tenantId, userId, ST_ACTIVE);
    }

    /** 历史清单（题7/题8：被替代 + 已归档，合并按 id 倒序） */
    public List<DcsAssistantMemory> listHistory(String tenantId, Long userId) {
        List<DcsAssistantMemory> out = new ArrayList<>();
        out.addAll(memoryMapper.listByUserStatus(tenantId, userId, ST_SUPERSEDED));
        out.addAll(memoryMapper.listByUserStatus(tenantId, userId, ST_ARCHIVED));
        out.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        return out;
    }

    /** 容量/条数用量（题7「3 / 50 条」只算生效中） */
    public int countActive(String tenantId, Long userId) {
        return memoryMapper.countByUser(tenantId, userId);
    }

    /** 记忆页删除（属主谓词在 SQL；删别人=影响 0 行） */
    public boolean remove(String tenantId, Long userId, Long id) {
        boolean ok = memoryMapper.deleteOwn(tenantId, userId, id) > 0;
        if (ok) {
            evict(tenantId, userId);
        }
        return ok;
    }

    /**
     * 行内编辑（题7）：与保存口同一套闸——长度截断 + PII 脱敏 + 空内容拒绝 + 精确去重。
     * 属主谓词在 SQL（改他人=0 行）。类型按新内容重推，主题键同步重算（否则覆盖判定会错）。
     */
    public boolean edit(String tenantId, Long userId, Long id, String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            return false;
        }
        if (content.length() > MAX_CONTENT) {
            content = content.substring(0, MAX_CONTENT);
        }
        content = piiFilter.mask(content, tenantId);
        DcsAssistantMemory row = memoryMapper.selectById(id);
        if (row == null || !tenantId.equals(row.getTenantId()) || !userId.equals(row.getUserId())) {
            return false;
        }
        if (content.equals(row.getContent())) {
            return true;
        }
        if (memoryMapper.countByContent(tenantId, userId, content) > 0) {
            return false;
        }
        boolean ok = memoryMapper.updateOwnContent(tenantId, userId, id, content, new Date()) > 0;
        if (ok) {
            // 类型/主题随内容重算（updateOwnContent 只动 content，这里补一次整行更新）
            DcsAssistantMemory patch = new DcsAssistantMemory();
            patch.setId(id);
            patch.setMemoryType(detectType(content));
            patch.setTopicKey(topicKeyOf(content));
            memoryMapper.updateById(patch);
            evict(tenantId, userId);
        }
        return ok;
    }

    /**
     * 注入用摘要（题9 案1）：按当前问题给每条打分，取相关 TopK，再按字数预算截断。
     *
     * <p>无相关问题（全部 0 分）→ 返回 null，不注入。命中行回写 last_used_at（题10 归档判据）。</p>
     */
    public DigestResult digest(String tenantId, Long userId, String question) {
        String key = tenantId + ":" + userId + ":" + (question == null ? "" : question.hashCode());
        Object[] cached = digestCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (Long) cached[0] > now) {
            return (DigestResult) cached[1];
        }
        DigestResult result = buildDigest(tenantId, userId, question);
        digestCache.put(key, new Object[]{now + DIGEST_TTL_MS, result});
        return result;
    }

    /** 兼容旧签名（无问题上下文=全量按新→旧取，行为同升级前，仅用于无法拿到问题的路径） */
    public String digest(String tenantId, Long userId) {
        DigestResult r = digest(tenantId, userId, null);
        return r == null ? null : r.text();
    }

    private DigestResult buildDigest(String tenantId, Long userId, String question) {
        if (!isUserEnabled(tenantId, userId)) {
            return null;
        }
        List<DcsAssistantMemory> rows = memoryMapper.listByUserStatus(tenantId, userId, ST_ACTIVE);
        if (rows.isEmpty()) {
            return null;
        }
        List<DcsAssistantMemory> ranked;
        if (question == null || question.isBlank()) {
            ranked = rows; // 无问题上下文：保持旧口径（新→旧）
        } else {
            List<Scored> scored = new ArrayList<>();
            for (DcsAssistantMemory r : rows) {
                int s = scoreOf(r.getContent(), question);
                if (s > 0) {
                    scored.add(new Scored(r, s));
                }
            }
            if (scored.isEmpty()) {
                return null; // 题9：没有相关的不注入
            }
            scored.sort((a, b) -> b.score != a.score ? Integer.compare(b.score, a.score)
                : Long.compare(b.row.getId(), a.row.getId()));
            ranked = new ArrayList<>();
            for (Scored s : scored) {
                if (ranked.size() >= MAX_DIGEST_ITEMS) {
                    break;
                }
                ranked.add(s.row);
            }
        }

        List<String> contents = new ArrayList<>();
        List<Long> rankedIds = new ArrayList<>();
        for (DcsAssistantMemory r : ranked) {
            contents.add(r.getContent());
            rankedIds.add(r.getId());
        }
        DigestResult built = joinWithBudget(contents, rankedIds, MAX_DIGEST_CHARS);
        if (built == null) {
            return null;
        }
        try {
            memoryMapper.touchUsed(tenantId, userId, built.ids(), new Date());
        } catch (RuntimeException e) {
            // 回写失败不影响注入（归档判据退化为「一直未使用」，不会误删）
            log.warn("[AssistantMemory] last_used_at 回写失败: {}", e.getMessage());
        }
        return built;
    }

    /**
     * 摘要拼装（题9 预算法，纯函数可单测）：按给定顺序取条，累加字数不超预算；
     * 第一条无论多长都收（至少一条），空列表返回 null。
     */
    public static DigestResult joinWithBudget(List<String> contents, List<Long> rowIds, int maxChars) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<Long> ids = new ArrayList<>();
        int chars = 0;
        for (int i = 0; i < contents.size(); i++) {
            String c = contents.get(i);
            if (c == null || c.isEmpty()) {
                continue;
            }
            int add = c.length() + 3;
            if (chars > 0 && chars + add > maxChars) {
                break;
            }
            if (chars > 0) {
                sb.append('；');
            }
            sb.append('「').append(c).append('」');
            chars += add;
            if (rowIds != null && i < rowIds.size()) {
                ids.add(rowIds.get(i));
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        return new DigestResult(sb.toString(), ids.size(), sb.length(), ids);
    }

    /** 归档截止时刻（题10 纯函数可单测）：now - days 天 */
    public static Date archiveBefore(Date now, int days) {
        return new Date(now.getTime() - days * 24L * 3600 * 1000);
    }

    /**
     * 上报本轮注入统计（题9 可见行）：装饰器注入成功后调。
     * threadId 由装配期显式传入（模型调用在 loom 线程，读不到 run ThreadLocal）。
     * count&lt;=0 = 本轮没命中，显式清空上一轮残留。
     */
    public void recordInjectedForTurn(String threadId, int count, int chars) {
        try {
            turnLedger.recordInjected(threadId, count, chars);
        } catch (RuntimeException e) {
            log.debug("[AssistantMemory] 注入统计上报失败(忽略): {}", e.getMessage());
        }
    }

    /** 本人开关（题10）：缺行=默认开启；租户总闸在装配期判，不在这里 */    public boolean isUserEnabled(String tenantId, Long userId) {
        DcsAssistantMemoryPref pref = prefMapper.selectOwn(tenantId, userId);
        return pref == null || !"0".equals(pref.getEnabled());
    }

    /** 设本人开关（题10）；返回是否开启 */
    public boolean setUserEnabled(String tenantId, Long userId, boolean enabled) {
        prefMapper.upsertOwn(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
            tenantId, userId, enabled ? "1" : "0", new Date());
        evict(tenantId, userId);
        return enabled;
    }

    /** 归档扫描（题10）：90 天没被注入过的 ACTIVE 条 → ARCHIVED；返回条数 */
    public int archiveStale() {
        Date before = archiveBefore(new Date(), ARCHIVE_DAYS);
        int n = memoryMapper.archiveStale(before, new Date());
        if (n > 0) {
            log.info("[AssistantMemory] 归档 {} 条 90 天未使用的记忆", n);
        }
        return n;
    }

    /** 清空本人全部（题10；含历史），返回删除行数 */
    public int clearAll(String tenantId, Long userId) {
        int n = memoryMapper.clearAllOwn(tenantId, userId);
        evict(tenantId, userId);
        return n;
    }

    /** threadId → conversation_id（题7 出处；查不到返回 null，前端退化为「对话中沉淀」） */
    private Long resolveConversationId(String tenantId, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        try {
            DcsAssistantConversation conv = conversationMapper.selectByThread(tenantId, threadId);
            return conv == null ? null : conv.getId();
        } catch (RuntimeException e) {
            log.debug("[AssistantMemory] 出处会话反查失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    /** 写后立即失效本进程缓存（该用户全部问题指纹） */
    private void evict(String tenantId, Long userId) {
        String prefix = tenantId + ":" + userId + ":";
        digestCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ==================== 纯函数（单测锚点） ====================

    /**
     * 类型推断（题7）：team > project > user 顺序命中；都不中→reference。
     * 顺序有意为之——「周会由王工负责」应落 team（谁的事比什么事更具体）。
     */
    public static String detectType(String content) {
        if (content == null || content.isBlank()) {
            return "reference";
        }
        if (TYPE_TEAM.matcher(content).find()) {
            return "team";
        }
        if (TYPE_PROJECT.matcher(content).find()) {
            return "project";
        }
        if (TYPE_USER.matcher(content).find()) {
            return "user";
        }
        return "reference";
    }

    /**
     * 同主题键（题8）：只认参数型偏好（称呼/语言/催办默认/时区）。
     * 未命中返回 null=不做覆盖（事实类内容只做精确去重，绝不自动替换）。
     */
    public static String topicKeyOf(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        for (Map.Entry<String, Pattern> e : TOPIC_RULES) {
            if (e.getValue().matcher(content).find()) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 相关性打分（题9）：中文二元组重叠 + 英文/数字词重合。
     *
     * <p>可解释、无外部依赖：命中越多分越高；停用字剔除防「的/了/我」刷分。
     * 返回 0 = 与当前问题无关，不注入。</p>
     */
    public static int scoreOf(String content, String question) {
        if (content == null || content.isBlank() || question == null || question.isBlank()) {
            return 0;
        }
        Set<String> qGrams = grams(question);
        Set<String> cGrams = grams(content);
        int hit = 0;
        for (String g : qGrams) {
            if (cGrams.contains(g)) {
                hit++;
            }
        }
        // 英文/数字词额外加权（版本号、人名缩写这类专有词命中价值更高）
        int wordHit = 0;
        for (String w : words(question)) {
            if (content.toLowerCase().contains(w)) {
                wordHit += 2;
            }
        }
        return hit + wordHit;
    }

    /** 中文二元组（剔停用字与标点）；不足 2 字的片段按原样保留为 1 个 gram */
    static Set<String> grams(String text) {
        Set<String> out = new LinkedHashSet<>();
        StringBuilder buf = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c) && !STOP_CHARS.contains(c)) {
                buf.append(Character.toLowerCase(c));
            } else {
                flushGrams(buf, out);
            }
        }
        flushGrams(buf, out);
        return out;
    }

    private static void flushGrams(StringBuilder buf, Set<String> out) {
        if (buf.length() == 0) {
            return;
        }
        String s = buf.toString();
        if (s.length() == 1) {
            out.add(s);
        } else {
            for (int i = 0; i + 1 < s.length(); i++) {
                out.add(s.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }

    /** 英文/数字词（长度≥2） */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[A-Za-z0-9_]{2,}").matcher(text);
        while (m.find()) {
            out.add(m.group().toLowerCase());
        }
        return out;
    }

    private record Scored(DcsAssistantMemory row, int score) {
    }

    /** 仅供单测/工具：暴露当前缓存大小（排查「改了不生效」时确认缓存行为） */
    public int cacheSize() {
        return digestCache.size();
    }

    /** 测试辅助：清空缓存 */
    public void clearCacheForTest() {
        digestCache.clear();
    }

    /** 导出用（题10）：全部条目按 id 倒序（含状态） */
    public List<DcsAssistantMemory> exportRows(String tenantId, Long userId) {
        List<DcsAssistantMemory> rows = new ArrayList<>(memoryMapper.listByUser(tenantId, userId));
        rows.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        return rows.isEmpty() ? Collections.emptyList() : rows;
    }
}
