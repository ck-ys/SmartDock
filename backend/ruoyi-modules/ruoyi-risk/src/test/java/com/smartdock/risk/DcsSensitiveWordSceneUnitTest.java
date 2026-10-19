package com.smartdock.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.risk.domain.DcsSensitiveWord;
import com.smartdock.risk.domain.bo.DcsSensitiveWordBo;
import com.smartdock.risk.mapper.DcsSensitiveWordMapper;
import com.smartdock.risk.service.DcsSensitiveWordFilterService;
import com.smartdock.risk.service.SensitiveWordInputPolicy;
import com.smartdock.risk.support.SensitiveWordMatcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 词库场景化装载单测(风控2.0 批1, 2026-09-22)。
 *
 * <p>验证口径(不使用任何真实敏感词, 一律占位词):
 * <ul>
 *   <li>FORM 场景: 装载结果只含 DB 词——2 条系统兜底词被场景剔除(钉死 CHAT, 防表单误伤红线)</li>
 *   <li>全场景(null): 装载结果 = DB 词 + 2 条系统兜底词(词库页命中自测口径)</li>
 *   <li>FORM 场景 DB 词命中走 inspect 无副作用通道</li>
 * </ul>
 * 注: DB 行按 scene 列的 SQL 过滤(LIKE)属 SQL 层行为, 由真实库验证;
 * 单测用 mock mapper 只验证装载结果的构成(baseline 剔除/保留)。</p>
 */
@Tag("dev")
class DcsSensitiveWordSceneUnitTest {

    /** 占位测试词(严禁使用真实敏感词) */
    private static final String PLACEHOLDER_WORD = "表单场景测试词A";

    @Test
    @SuppressWarnings("unchecked")
    void formSceneExcludesBaselineWordsButKeepsDbWords() throws Exception {
        DcsSensitiveWordMapper wordMapper = mock(DcsSensitiveWordMapper.class);
        when(wordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(wordRow(PLACEHOLDER_WORD, "REVIEW", "FORM")));
        DcsSensitiveWordFilterService service = new DcsSensitiveWordFilterService(wordMapper);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
             MockedStatic<TenantHelper> tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            tenant.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<List<DcsSensitiveWord>>) inv.getArgument(0)).get());

            Method loadRules = DcsSensitiveWordFilterService.class.getDeclaredMethod("loadRules", String.class);
            loadRules.setAccessible(true);
            List<?> formRules = (List<?>) loadRules.invoke(service, "FORM");
            List<?> allRules = (List<?>) loadRules.invoke(service, (Object) null);

            // FORM 场景: 仅 1 条 DB 词(2 条系统兜底词被剔除)
            assertThat(formRules).hasSize(1);
            // 全场景: 1 条 DB 词 + 2 条系统兜底词
            assertThat(allRules).hasSize(3);

            // FORM 场景词可正常命中(无副作用 inspect 通道)
            SensitiveWordMatcher.InspectionResult hit =
                service.inspect("前缀" + PLACEHOLDER_WORD + "后缀", "FORM");
            assertThat(hit.hits()).extracting(SensitiveWordMatcher.Hit::word).containsExactly(PLACEHOLDER_WORD);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void formSceneWordDoesNotHitWhenDbReturnsNoFormWords() throws Exception {
        DcsSensitiveWordMapper wordMapper = mock(DcsSensitiveWordMapper.class);
        // 模拟 DB 场景过滤后无 FORM 词(真实场景由 SQL LIKE 过滤, mock 直接返回空)
        when(wordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        DcsSensitiveWordFilterService service = new DcsSensitiveWordFilterService(wordMapper);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
             MockedStatic<TenantHelper> tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            tenant.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<List<DcsSensitiveWord>>) inv.getArgument(0)).get());

            SensitiveWordMatcher.InspectionResult hit = service.inspect("包含占位词" + PLACEHOLDER_WORD + "的文本", "FORM");
            assertThat(hit.hits()).isEmpty();
            assertThat(hit.blocked()).isFalse();
        }
    }

    @Test
    void sceneNormalizationDefaultsToChatAndJoinsCsv() {
        SensitiveWordInputPolicy policy = new SensitiveWordInputPolicy();
        // 空=CHAT(存量口径)
        assertThat(boWithScene(null, policy).getScene()).isEqualTo("CHAT");
        // 逗号组合: 去空白/大写/去重保序
        assertThat(boWithScene(" form,CHAT,,form ", policy).getScene()).isEqualTo("FORM,CHAT");
        // ALL 是装载层内部标记, 不允许入库(SQL CSV LIKE 匹配不到)
        assertThatThrownBy(() -> boWithScene("ALL", policy))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("生效场景仅支持");
    }

    private DcsSensitiveWordBo boWithScene(String scene, SensitiveWordInputPolicy policy) {
        DcsSensitiveWordBo bo = new DcsSensitiveWordBo();
        bo.setWordText(PLACEHOLDER_WORD);
        bo.setAction("MASK");
        bo.setScene(scene);
        policy.prepareCreate(bo, "000000");
        return bo;
    }

    private DcsSensitiveWord wordRow(String text, String action, String scene) {
        DcsSensitiveWord row = new DcsSensitiveWord();
        row.setWordId(1L);
        row.setTenantId("000000");
        row.setWordText(text);
        row.setAction(action);
        row.setEnabled("1");
        row.setBuiltin("0");
        row.setDelFlag("0");
        row.setScene(scene);
        return row;
    }
}
