package com.smartdock.risk.service;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.risk.config.DcsRolloutProperties;
import com.smartdock.risk.domain.bo.RiskEventCommand;
import com.smartdock.risk.support.SensitiveWordMatcher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 任务填写内容机审服务(风控2.0 批1, 2026-09-22)。
 *
 * <p><b>职责</b>: 任务填写主数据流(表单提交/追加/在线批量/Excel导入)的统一内容机审入口——
 * 对明细数据的文本字段做 FORM 场景词库匹配(复用 {@link DcsSensitiveWordFilterService}),
 * 命中即异步记 TASK_CONTENT_RISK 风险事件(只记分留痕不阻塞), 拒绝与否由调用方按灰度开关判定。</p>
 *
 * <p><b>灰度双开关</b>(dcs.rollout, 详见 {@link DcsRolloutProperties}):
 * contentModerationEnabled(默认 false)=机审总开关, 关=完全放行不记事件;
 * contentBlockEnabled(默认 false)=BLOCK 拦截开关, 关=观察模式只记事件。
 * 上线节奏: 先开总开关观察误伤率, 业务确认后再开拦截——红线: 不影响正常用户。</p>
 *
 * <p><b>幂等口径</b>: {@code mod:{source}:{missionId}:{userId}:{sha256(内容指纹)前16}} ——
 * 同人同任务同内容只记一次事件(重复导入/重放去重), 内容变化(修改重提)视为新事实重新记录。</p>
 *
 * <p><b>红线口径</b>: 本服务不抛业务异常拦截提交(聊天链路的 filter 抛异常模式不适用于填报主流程);
 * 拒绝判定集中在 {@link #shouldReject}, 调用方显式调用——保证观察模式下调用行为与未接入时完全一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentModerationService {

    /** 词库生效场景: 表单(含追加/在线批量/Excel 导入, 均为明细填报文本) */
    public static final String SCENE_FORM = "FORM";
    /** 事件来源: 表单提交/修改重提(save-detail)与追加(append-detail) */
    public static final String SOURCE_FORM = "FORM";
    /** 事件来源: 在线批量填写(save-online-data) */
    public static final String SOURCE_ONLINE = "ONLINE";
    /** 事件来源: Excel 导入(upload)与程序化装载(load) */
    public static final String SOURCE_EXCEL = "EXCEL";
    /** 事件类型: 任务填写内容命中词库 */
    public static final String EVENT_TYPE = "TASK_CONTENT_RISK";
    /** 机检标记值: 审核人判误报(命中说明保留, 误报计数聚合本值) */
    public static final String MACHINE_CHECK_FP = "FP";

    private final DcsSensitiveWordFilterService wordFilterService;
    private final RiskEventCollector riskEventCollector;
    private final DcsRolloutProperties rollout;

    /**
     * 单行明细机审: 展平 itemMap 的全部文本叶子值逐字段匹配, 命中记事件并返回结论。
     *
     * @param missionId 任务 ID(事件 refId)
     * @param source    入口来源 SOURCE_FORM / SOURCE_ONLINE / SOURCE_EXCEL
     * @param itemMap   明细字段值(动态表单 Map, 支持嵌套 Map/List)
     * @return 机审结论(总开关关或无命中时返回 clean)
     */
    public ContentModerationResult moderateDetail(Long missionId, String source, Map<String, Object> itemMap) {
        if (!rollout.isContentModerationEnabled() || itemMap == null || itemMap.isEmpty()) {
            return ContentModerationResult.clean();
        }
        List<ContentModerationResult.FieldHit> hits = new ArrayList<>();
        walkFields("", itemMap, hits);
        if (hits.isEmpty()) {
            return ContentModerationResult.clean();
        }
        boolean blockHit = hits.stream().anyMatch(h -> SensitiveWordMatcher.ACTION_BLOCK.equalsIgnoreCase(h.action()));
        boolean reviewHit = hits.stream().anyMatch(h -> SensitiveWordMatcher.ACTION_REVIEW.equalsIgnoreCase(h.action()));
        // 代表词: BLOCK 级优先, 无则首个 REVIEW 级(与大盘 TOP 命中词聚合口径一致)
        String hitWord = hits.stream()
            .filter(h -> SensitiveWordMatcher.ACTION_BLOCK.equalsIgnoreCase(h.action()))
            .map(ContentModerationResult.FieldHit::word)
            .findFirst()
            .orElseGet(() -> hits.get(0).word());

        String fieldKeys = hits.stream()
            .map(ContentModerationResult.FieldHit::fieldKey)
            .distinct()
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        String detail = "内容机检:命中" + (blockHit ? "BLOCK" : "REVIEW") + "词[" + hitWord + "] 字段[" + fieldKeys + "]";
        riskEventCollector.record(new RiskEventCommand(
            StrUtil.blankToDefault(LoginHelper.getTenantId(), "000000"),
            EVENT_TYPE,
            source,
            LoginHelper.getUserId(),
            clientIpOrNull(),
            userAgentOrNull(),
            missionId,
            detail,
            idempotencyKey(missionId, source, itemMap),
            new Date(),
            hitWord
        ));
        return new ContentModerationResult(blockHit, reviewHit, hitWord, List.copyOf(hits));
    }

    /**
     * 批量行机审: 逐行调用 {@link #moderateDetail}, 返回与入参行序一致的结果列表。
     * 行为 null 或非 Map 结构时对应位置返回 clean(调用方自行决定是否容忍)。
     */
    public List<ContentModerationResult> moderateRows(Long missionId, String source, List<Map<String, Object>> rows) {
        if (!rollout.isContentModerationEnabled() || rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<ContentModerationResult> results = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            results.add(moderateDetail(missionId, source, row));
        }
        return results;
    }

    /**
     * 拦截判定: 机审总开关 + BLOCK 拦截开关 + BLOCK 命中 三者同时成立才拒绝。
     * 观察模式(拦截开关关)恒 false——调用方据此保证"只记事件不拦提交"。
     */
    public boolean shouldReject(ContentModerationResult result) {
        return rollout.isContentModerationEnabled() && rollout.isContentBlockEnabled()
            && result != null && result.blockHit();
    }

    /** 拒绝提示文案(不回显命中词, 与聊天链路口径一致): 调用方在 shouldReject 成立时抛 ServiceException 用 */
    public String rejectMessage(ContentModerationResult result) {
        String fieldKeys = result.hits().stream()
            .map(ContentModerationResult.FieldHit::fieldKey)
            .distinct()
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        return "提交内容包含不允许发布的内容（字段[" + fieldKeys + "]），请修改后重新提交";
    }

    /**
     * 机检标记是否启用(批2): 与机审总开关同档——开关关时调用方不得触碰明细标记列
     * (已落库的历史标记冻结保留, 不清不覆盖)。
     */
    public boolean isMarkerEnabled() {
        return rollout.isContentModerationEnabled();
    }

    /**
     * 机检标记随行落库(风控2.0批2): BLOCK/REVIEW 命中映射为行级标记供审核人核对,
     * 干净内容映射为 null(调用方显式清空旧标记, 防内容改干净后陈旧提示误导审核人)。
     * 观察期 BLOCK 行同样标记——拦截开关未开, 阻断级命中恰恰是审核人最该人工核对的行。
     */
    public MachineMarker machineMarker(ContentModerationResult result) {
        if (result == null || (!result.blockHit() && !result.reviewHit())) {
            return new MachineMarker(null, null);
        }
        boolean block = result.blockHit();
        // 终审P2-1: 混级命中按级别分组标注, 避免审核人把复核级字段误当阻断级
        java.util.List<String> blockFields = result.hits().stream()
            .filter(h -> "BLOCK".equals(h.action()))
            .map(ContentModerationResult.FieldHit::fieldKey)
            .distinct()
            .toList();
        java.util.List<String> reviewFields = result.hits().stream()
            .filter(h -> !"BLOCK".equals(h.action()))
            .map(ContentModerationResult.FieldHit::fieldKey)
            .distinct()
            .toList();
        String note;
        if (!blockFields.isEmpty() && !reviewFields.isEmpty()) {
            note = "字段[" + foldFields(blockFields) + "](阻断) 字段[" + foldFields(reviewFields) + "](复核)";
        } else {
            java.util.List<String> fields = blockFields.isEmpty() ? reviewFields : blockFields;
            note = "字段[" + foldFields(fields) + "] 级别[" + (block ? "BLOCK" : "REVIEW") + "]";
        }
        // 终审P1: 说明硬截 500(machine_check_note 列宽), 防多字段命中拼接超长炸提交——
        // 观察期机审红线是"只记不拦", 标记写失败不得反噬提交
        if (note.length() > 500) {
            note = note.substring(0, 497) + "...";
        }
        // 说明只含字段路径+级别, 不含词面(列表/悬停均打码口径, 词面仅事件表 hit_word 有)
        return new MachineMarker(block ? "BLOCK" : "REVIEW", note);
    }

    /** 字段路径列表折叠(终审P1): 超过 10 个折叠为"等N个字段", 防说明超长 */
    private static String foldFields(java.util.List<String> fields) {
        if (fields.size() <= 10) {
            return String.join(",", fields);
        }
        return String.join(",", fields.subList(0, 10)) + " 等" + fields.size() + "个字段";
    }

    /**
     * 行级机检标记: status ∈ BLOCK/REVIEW/null(干净); note 为悬停说明(不含词面)。
     * 误报判定(FP)由审核端点写回, 不在本映射内。
     */
    public record MachineMarker(String status, String note) {
    }

    /**
     * 递归展平字段树, 对每个 String 叶子值做 FORM 场景词库匹配(无副作用 inspect, 不改写不抛异常)。
     * 嵌套 Map 以.连接字段路径; List 以[下标]连接——错误提示可精确定位到嵌套字段。
     */
    private void walkFields(String prefix, Map<String, Object> node, List<ContentModerationResult.FieldHit> hits) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) map;
                walkFields(key, nested, hits);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof String text) {
                        inspectField(key + "[" + i + "]", text, hits);
                    }
                }
            } else if (value instanceof String text && StrUtil.isNotBlank(text)) {
                inspectField(key, text, hits);
            }
        }
    }

    /** 单字段匹配: 命中明细逐条收集(词/级别来自 inspection, 不会重复报同一词) */
    private void inspectField(String fieldKey, String text, List<ContentModerationResult.FieldHit> hits) {
        SensitiveWordMatcher.InspectionResult inspection = wordFilterService.inspect(text, SCENE_FORM);
        if (inspection == null || inspection.hits() == null) {
            return;
        }
        for (SensitiveWordMatcher.Hit hit : inspection.hits()) {
            hits.add(new ContentModerationResult.FieldHit(fieldKey, hit.word(), hit.action()));
        }
    }

    /** 幂等键: mod:source:missionId:userId:内容指纹前16 —— 同内容重放去重, 改内容重提=新事实 */
    private String idempotencyKey(Long missionId, String source, Map<String, Object> itemMap) {
        List<String> leaves = new ArrayList<>();
        collectLeafValues("", itemMap, leaves);
        Collections.sort(leaves);
        return "mod:" + StrUtil.blankToDefault(source, "FORM") + ":" + missionId + ":"
            + LoginHelper.getUserId() + ":" + sha256Hex(String.join(";", leaves), 16);
    }

    /** 收集全部叶子值的字符串形式(含非文本叶子, 供内容指纹使用) */
    private void collectLeafValues(String prefix, Map<String, Object> node, List<String> leaves) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) map;
                collectLeafValues(key, nested, leaves);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    leaves.add(key + "[" + i + "]=" + (item == null ? "" : item.toString()));
                }
            } else {
                leaves.add(key + "=" + (value == null ? "" : value.toString()));
            }
        }
    }

    /** SHA-256 十六进制前 limit 位(内容指纹; 算法失败回退整串 hashCode 十六进制, 保证幂等键非空) */
    private String sha256Hex(String input, int limit) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, limit);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private String clientIpOrNull() {
        try {
            return ServletUtils.getClientIP();
        } catch (Exception e) {
            return null;
        }
    }

    private String userAgentOrNull() {
        try {
            HttpServletRequest request = ServletUtils.getRequest();
            return request == null ? null : request.getHeader("User-Agent");
        } catch (Exception e) {
            return null;
        }
    }
}
