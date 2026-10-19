package com.smartdock.risk;

import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.risk.config.DcsRolloutProperties;
import com.smartdock.risk.domain.bo.RiskEventCommand;
import com.smartdock.risk.service.ContentModerationResult;
import com.smartdock.risk.service.ContentModerationService;
import com.smartdock.risk.service.DcsSensitiveWordFilterService;
import com.smartdock.risk.service.RiskEventCollector;
import com.smartdock.risk.support.SensitiveWordMatcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容机审服务单测(风控2.0 批1, 2026-09-22)。
 *
 * <p>验证口径(全部占位词, 严禁真实敏感词):
 * <ul>
 *   <li>总开关关: 完全放行, 不匹配不记事件</li>
 *   <li>FORM 场景 BLOCK 命中: 记 TASK_CONTENT_RISK 事件(source/hitWord/幂等键格式), observe 模式不拒绝</li>
 *   <li>幂等键: 同内容两次调用键一致(重放去重); 内容变化键不同(新事实)</li>
 *   <li>REVIEW 命中: reviewHit=true 但 blockHit=false, 拦截开关开也不拒绝</li>
 *   <li>enforce 模式: 总开关+拦截开关+BLOCK 命中三条件齐才 shouldReject; 拒绝文案含字段名不含命中词</li>
 *   <li>嵌套字段路径: Map/List 展平后 fieldKey 带 . 与 [下标]</li>
 * </ul></p>
 */
@Tag("dev")
class ContentModerationServiceUnitTest {

    /** 占位测试词(严禁真实敏感词) */
    private static final String PLACEHOLDER_BLOCK_WORD = "机审测试词A";
    private static final String PLACEHOLDER_REVIEW_WORD = "机审示例词B";

    @Test
    void moderationDisabledSkipsMatchingAndRecording() {
        DcsSensitiveWordFilterService wordFilter = mock(DcsSensitiveWordFilterService.class);
        RiskEventCollector collector = mock(RiskEventCollector.class);
        DcsRolloutProperties rollout = new DcsRolloutProperties();
        rollout.setContentModerationEnabled(false);
        ContentModerationService service = new ContentModerationService(wordFilter, collector, rollout);

        ContentModerationResult result = service.moderateDetail(9L, ContentModerationService.SOURCE_FORM,
            Map.of("remark", "任意文本" + PLACEHOLDER_BLOCK_WORD));

        assertThat(result.blockHit()).isFalse();
        assertThat(result.hits()).isEmpty();
        verify(wordFilter, never()).inspect(anyString(), anyString());
        verify(collector, never()).record(any());
    }

    @Test
    void formBlockHitRecordsEventAndObservationModeNeverRejects() {
        DcsSensitiveWordFilterService wordFilter = mock(DcsSensitiveWordFilterService.class);
        RiskEventCollector collector = mock(RiskEventCollector.class);
        DcsRolloutProperties rollout = new DcsRolloutProperties();
        rollout.setContentModerationEnabled(true);
        rollout.setContentBlockEnabled(false);
        ContentModerationService service = new ContentModerationService(wordFilter, collector, rollout);
        stubInspect(wordFilter, PLACEHOLDER_BLOCK_WORD, SensitiveWordMatcher.ACTION_BLOCK);

        ContentModerationResult result = runWithLoginContext(() -> service.moderateDetail(9L,
            ContentModerationService.SOURCE_FORM, Map.of("remark", "内容" + PLACEHOLDER_BLOCK_WORD)));

        assertThat(result.blockHit()).isTrue();
        assertThat(result.hitWord()).isEqualTo(PLACEHOLDER_BLOCK_WORD);
        // observe 模式(拦截开关关): 记事件但绝不拒绝
        assertThat(service.shouldReject(result)).isFalse();

        ArgumentCaptor<RiskEventCommand> captor = ArgumentCaptor.forClass(RiskEventCommand.class);
        verify(collector).record(captor.capture());
        RiskEventCommand command = captor.getValue();
        assertThat(command.eventType()).isEqualTo(ContentModerationService.EVENT_TYPE);
        assertThat(command.source()).isEqualTo(ContentModerationService.SOURCE_FORM);
        assertThat(command.hitWord()).isEqualTo(PLACEHOLDER_BLOCK_WORD);
        assertThat(command.idempotencyKey()).startsWith("mod:FORM:9:7:");
        assertThat(command.detail()).contains("BLOCK").contains("[remark]");
    }

    @Test
    void idempotencyKeyStableForSameContentAndChangesForNewContent() {
        DcsSensitiveWordFilterService wordFilter = mock(DcsSensitiveWordFilterService.class);
        RiskEventCollector collector = mock(RiskEventCollector.class);
        DcsRolloutProperties rollout = new DcsRolloutProperties();
        rollout.setContentModerationEnabled(true);
        ContentModerationService service = new ContentModerationService(wordFilter, collector, rollout);
        stubInspect(wordFilter, PLACEHOLDER_BLOCK_WORD, SensitiveWordMatcher.ACTION_BLOCK);

        String first = runWithLoginContext(() -> {
            service.moderateDetail(9L, ContentModerationService.SOURCE_FORM,
                Map.of("remark", "同内容" + PLACEHOLDER_BLOCK_WORD));
            return captureKey(collector);
        });
        String second = runWithLoginContext(() -> {
            service.moderateDetail(9L, ContentModerationService.SOURCE_FORM,
                Map.of("remark", "同内容" + PLACEHOLDER_BLOCK_WORD));
            return captureKey(collector);
        });
        String changed = runWithLoginContext(() -> {
            service.moderateDetail(9L, ContentModerationService.SOURCE_FORM,
                Map.of("remark", "同内容改" + PLACEHOLDER_BLOCK_WORD));
            return captureKey(collector);
        });

        assertThat(first).isEqualTo(second);
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    void reviewOnlyHitNeverRejectsEvenWithBlockSwitchOn() {
        DcsSensitiveWordFilterService wordFilter = mock(DcsSensitiveWordFilterService.class);
        RiskEventCollector collector = mock(RiskEventCollector.class);
        DcsRolloutProperties rollout = new DcsRolloutProperties();
        rollout.setContentModerationEnabled(true);
        rollout.setContentBlockEnabled(true);
        ContentModerationService service = new ContentModerationService(wordFilter, collector, rollout);
        stubInspect(wordFilter, PLACEHOLDER_REVIEW_WORD, SensitiveWordMatcher.ACTION_REVIEW);

        ContentModerationResult result = runWithLoginContext(() -> service.moderateDetail(9L,
            ContentModerationService.SOURCE_FORM, Map.of("desc", "内容" + PLACEHOLDER_REVIEW_WORD)));

        assertThat(result.reviewHit()).isTrue();
        assertThat(result.blockHit()).isFalse();
        assertThat(result.hitWord()).isEqualTo(PLACEHOLDER_REVIEW_WORD);
        assertThat(service.shouldReject(result)).isFalse();
    }

    @Test
    void enforceModeRejectsBlockHitAndMessageNamesFieldWithoutWord() {
        DcsSensitiveWordFilterService wordFilter = mock(DcsSensitiveWordFilterService.class);
        RiskEventCollector collector = mock(RiskEventCollector.class);
        DcsRolloutProperties rollout = new DcsRolloutProperties();
        rollout.setContentModerationEnabled(true);
        rollout.setContentBlockEnabled(true);
        ContentModerationService service = new ContentModerationService(wordFilter, collector, rollout);
        stubInspect(wordFilter, PLACEHOLDER_BLOCK_WORD, SensitiveWordMatcher.ACTION_BLOCK);

        ContentModerationResult result = runWithLoginContext(() -> service.moderateDetail(9L,
            ContentModerationService.SOURCE_FORM, Map.of("remark", "内容" + PLACEHOLDER_BLOCK_WORD)));

        assertThat(service.shouldReject(result)).isTrue();
        String message = service.rejectMessage(result);
        assertThat(message).contains("[remark]");
        // 拒绝文案不回显命中词(与聊天拦截口径一致)
        assertThat(message).doesNotContain(PLACEHOLDER_BLOCK_WORD);
    }

    @Test
    void nestedFieldsFlattenWithPathInHits() {
        DcsSensitiveWordFilterService wordFilter = mock(DcsSensitiveWordFilterService.class);
        RiskEventCollector collector = mock(RiskEventCollector.class);
        DcsRolloutProperties rollout = new DcsRolloutProperties();
        rollout.setContentModerationEnabled(true);
        ContentModerationService service = new ContentModerationService(wordFilter, collector, rollout);
        stubInspect(wordFilter, PLACEHOLDER_REVIEW_WORD, SensitiveWordMatcher.ACTION_REVIEW);

        Map<String, Object> nested = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("note", "嵌套" + PLACEHOLDER_REVIEW_WORD);
        nested.put("ext", inner);
        nested.put("tags", List.of("干净值", "列表" + PLACEHOLDER_REVIEW_WORD));

        ContentModerationResult result = runWithLoginContext(() ->
            service.moderateDetail(9L, ContentModerationService.SOURCE_FORM, nested));

        assertThat(result.hits()).extracting(ContentModerationResult.FieldHit::fieldKey)
            .containsExactlyInAnyOrder("ext.note", "tags[1]");
    }

    @Test
    void machineMarkerMapsHitsToStatusAndCleanToNull() {
        DcsSensitiveWordFilterService wordFilter = mock(DcsSensitiveWordFilterService.class);
        RiskEventCollector collector = mock(RiskEventCollector.class);
        DcsRolloutProperties rollout = new DcsRolloutProperties();
        rollout.setContentModerationEnabled(true);
        ContentModerationService service = new ContentModerationService(wordFilter, collector, rollout);
        stubInspectTwoWords(wordFilter, PLACEHOLDER_BLOCK_WORD, PLACEHOLDER_REVIEW_WORD);

        // BLOCK 命中 → BLOCK 标记, 说明含字段路径+级别且不含词面
        ContentModerationResult blockHit = runWithLoginContext(() -> service.moderateDetail(9L,
            ContentModerationService.SOURCE_FORM, Map.of("remark", "内容" + PLACEHOLDER_BLOCK_WORD)));
        ContentModerationService.MachineMarker blockMarker = service.machineMarker(blockHit);
        assertThat(blockMarker.status()).isEqualTo("BLOCK");
        assertThat(blockMarker.note()).contains("[remark]").contains("BLOCK")
            .doesNotContain(PLACEHOLDER_BLOCK_WORD);

        // REVIEW 命中 → REVIEW 标记
        ContentModerationResult reviewHit = runWithLoginContext(() -> service.moderateDetail(9L,
            ContentModerationService.SOURCE_FORM, Map.of("desc", "内容" + PLACEHOLDER_REVIEW_WORD)));
        assertThat(service.machineMarker(reviewHit).status()).isEqualTo("REVIEW");

        // 干净内容 → null(调用方显式清空旧标记); null 入参 → null(防御)
        ContentModerationResult clean = runWithLoginContext(() -> service.moderateDetail(9L,
            ContentModerationService.SOURCE_FORM, Map.of("remark", "完全干净的内容")));
        assertThat(service.machineMarker(clean).status()).isNull();
        assertThat(service.machineMarker(clean).note()).isNull();
        assertThat(service.machineMarker(null).status()).isNull();

        // 标记开关与机审总开关同档
        assertThat(service.isMarkerEnabled()).isTrue();
        rollout.setContentModerationEnabled(false);
        assertThat(service.isMarkerEnabled()).isFalse();
    }

    @Test
    void machineMarkerTruncatesLongFieldListAndSplitsMixedLevels() {
        DcsSensitiveWordFilterService wordFilter = mock(DcsSensitiveWordFilterService.class);
        RiskEventCollector collector = mock(RiskEventCollector.class);
        DcsRolloutProperties rollout = new DcsRolloutProperties();
        rollout.setContentModerationEnabled(true);
        ContentModerationService service = new ContentModerationService(wordFilter, collector, rollout);

        // 终审P1: 字段路径超 10 个折叠"等N个字段", 且 note 硬截 500(machine_check_note 列宽)——
        // 防多字段命中拼接超长把观察期提交炸失败(机审红线"只记不拦")
        List<ContentModerationResult.FieldHit> manyFields = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            manyFields.add(new ContentModerationResult.FieldHit(
                "field" + i, "w" + i, SensitiveWordMatcher.ACTION_REVIEW));
        }
        ContentModerationService.MachineMarker folded = service.machineMarker(
            new ContentModerationResult(false, true, "w0", manyFields));
        assertThat(folded.note()).contains("field9").contains("等12个字段")
            .doesNotContain("field10").doesNotContain("field11");
        assertThat(folded.note().length()).isLessThanOrEqualTo(500);

        // 终审P2-1: 混级命中按级别分组标注, 防审核人把复核级字段误当阻断级
        List<ContentModerationResult.FieldHit> mixed = List.of(
            new ContentModerationResult.FieldHit("danger", "wb", SensitiveWordMatcher.ACTION_BLOCK),
            new ContentModerationResult.FieldHit("hint", "wr", SensitiveWordMatcher.ACTION_REVIEW));
        ContentModerationService.MachineMarker mixedMarker = service.machineMarker(
            new ContentModerationResult(true, true, "wb", mixed));
        assertThat(mixedMarker.status()).isEqualTo("BLOCK");
        assertThat(mixedMarker.note()).contains("[danger](阻断)").contains("[hint](复核)");
    }

    // ---------------------------------------------------------------
    // 测试脚手架
    // ---------------------------------------------------------------

    /** stub: 仅当文本包含占位词时返回命中(干净文本返回空, 与真实词库行为一致) */
    private void stubInspect(DcsSensitiveWordFilterService wordFilter, String word, String action) {
        SensitiveWordMatcher.Hit hit = new SensitiveWordMatcher.Hit(word, action, 0, Math.max(1, word.length()));
        SensitiveWordMatcher.InspectionResult inspection = new SensitiveWordMatcher.InspectionResult(
            List.of(hit), SensitiveWordMatcher.ACTION_BLOCK.equalsIgnoreCase(action), false);
        SensitiveWordMatcher.InspectionResult clean = new SensitiveWordMatcher.InspectionResult(List.of(), false, false);
        when(wordFilter.inspect(anyString(), eq(ContentModerationService.SCENE_FORM)))
            .thenAnswer(inv -> inv.getArgument(0).toString().contains(word) ? inspection : clean);
    }

    /** stub(批2): 双占位词并存, 文本含哪个词记哪个命中——供 BLOCK/REVIEW 标记映射用例 */
    private void stubInspectTwoWords(DcsSensitiveWordFilterService wordFilter, String blockWord, String reviewWord) {
        SensitiveWordMatcher.Hit blockHit = new SensitiveWordMatcher.Hit(
            blockWord, SensitiveWordMatcher.ACTION_BLOCK, 0, Math.max(1, blockWord.length()));
        SensitiveWordMatcher.Hit reviewHit = new SensitiveWordMatcher.Hit(
            reviewWord, SensitiveWordMatcher.ACTION_REVIEW, 0, Math.max(1, reviewWord.length()));
        when(wordFilter.inspect(anyString(), eq(ContentModerationService.SCENE_FORM)))
            .thenAnswer(inv -> {
                String text = inv.getArgument(0).toString();
                List<SensitiveWordMatcher.Hit> hits = new java.util.ArrayList<>();
                if (text.contains(blockWord)) {
                    hits.add(blockHit);
                }
                if (text.contains(reviewWord)) {
                    hits.add(reviewHit);
                }
                boolean block = hits.stream().anyMatch(h ->
                    SensitiveWordMatcher.ACTION_BLOCK.equalsIgnoreCase(h.action()));
                return new SensitiveWordMatcher.InspectionResult(List.copyOf(hits), block, false);
            });
    }

    /** 模拟登录上下文(租户/用户/IP); 生产环境由 Sa-Token 会话提供 */
    private <T> T runWithLoginContext(java.util.function.Supplier<T> body) {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
             MockedStatic<ServletUtils> servlet = mockStatic(ServletUtils.class)) {
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::getUserId).thenReturn(7L);
            servlet.when(ServletUtils::getClientIP).thenReturn("127.0.0.1");
            servlet.when(ServletUtils::getRequest).thenReturn(null);
            return body.get();
        }
    }

    /** 取 collector 最近一次 record 的幂等键(每次新 mock 局部使用) */
    private String captureKey(RiskEventCollector collector) {
        ArgumentCaptor<RiskEventCommand> captor = ArgumentCaptor.forClass(RiskEventCommand.class);
        verify(collector, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getValue().idempotencyKey();
    }
}
