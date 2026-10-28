package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.domain.ProfileReviewQueue;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.mapper.ProfileEngineConfigMapper;
import com.smartdock.profile.mapper.ProfileReviewQueueMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复核第二入口(scan 手动扫描/未来其它调用方)不得绕过「占位 50 分不入队」护栏(夜审 P1):
 * 守卫在 evaluateAfterRecalc 本体, 引擎之外的路进来同样生效;
 * 且占位分时期误入队的存量 PENDING 自动销案。
 *
 * <p>B 片三审回修补钉: ①销案改为 supersedeIfPending 条件更新(与人工确认并发不覆盖);
 * ②scan() 载入快照的 SQL 必须带 dimension_detail——漏投影会让守卫在扫描路径恒空转
 * (三审实锤的机制级防法, 用注解反射钉住列名, 改 SQL 的人跑测试就炸)。</p>
 *
 * @author smartdock
 */
@Tag("local")
class ProfileReviewUnconfiguredGuardTest {

    @BeforeAll
    static void initMybatisTableCache() {
        // LambdaQueryWrapper 的 lambda 列解析依赖 MyBatis 表缓存, 纯单测须手工登记
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProfileReviewQueue.class);
        TableInfoHelper.initTableInfo(assistant, ProfileSubject.class);
    }

    private final ProfileReviewQueueMapper reviewMapper = Mockito.mock(ProfileReviewQueueMapper.class);
    private final ProfileEngineConfigMapper configMapper = Mockito.mock(ProfileEngineConfigMapper.class);
    private final ProfileEngineConfigService configService = Mockito.mock(ProfileEngineConfigService.class);
    private final ProfileSubjectMapper subjectMapper = Mockito.mock(ProfileSubjectMapper.class);
    private final ProfileReviewService service = new ProfileReviewService(
        reviewMapper, configMapper, configService, subjectMapper);

    private static ProfileScoreSnapshot snapshot(String detailJson, String total) {
        ProfileScoreSnapshot s = new ProfileScoreSnapshot();
        s.setTenantId("000000");
        s.setSource("dcs");
        s.setSubjectType("user");
        s.setSubjectId("u-1");
        s.setTotalScore(new BigDecimal(total));
        s.setDimensionDetail(detailJson);
        return s;
    }

    private void reviewEnabled(double threshold) {
        ProfileEngineConfig config = new ProfileEngineConfig();
        config.setReviewEnabled("1");
        config.setReviewThreshold(BigDecimal.valueOf(threshold));
        Mockito.when(configService.getOrCreate("000000", "dcs")).thenReturn(config);
    }

    @Test
    void unconfiguredSnapshotNeverEnqueuesAndSupersedesStalePending() {
        // 占位 50 分(带 _noDimensions 标记)即使低于阈值也不入队;
        // 存量误入队的 PENDING 走条件更新销案(不再是整对象回写)
        reviewEnabled(60);
        ProfileReviewQueue stale = new ProfileReviewQueue();
        stale.setReviewId(1L);
        stale.setStatus("PENDING");
        Mockito.when(reviewMapper.selectList(Mockito.any())).thenReturn(List.of(stale));
        Mockito.when(reviewMapper.supersedeIfPending(Mockito.eq(1L), Mockito.eq("000000"),
            Mockito.anyString(), Mockito.any())).thenReturn(1);

        boolean created = service.evaluateAfterRecalc(snapshot("{\"_noDimensions\":true}", "50"));

        assertFalse(created);
        Mockito.verify(reviewMapper).supersedeIfPending(Mockito.eq(1L), Mockito.eq("000000"),
            Mockito.anyString(), Mockito.any());
        Mockito.verify(reviewMapper, Mockito.never()).insert(Mockito.any(ProfileReviewQueue.class));
    }

    @Test
    void supersedeLosesToConcurrentHumanFinishSilently() {
        // 条件更新 0 行=人工刚确认/驳回过: 放弃销案且不覆盖人工结论, 也不报错(扫描是后台动作)
        reviewEnabled(60);
        ProfileReviewQueue stale = new ProfileReviewQueue();
        stale.setReviewId(1L);
        stale.setStatus("PENDING");
        Mockito.when(reviewMapper.selectList(Mockito.any())).thenReturn(List.of(stale));
        Mockito.when(reviewMapper.supersedeIfPending(Mockito.any(), Mockito.any(),
            Mockito.anyString(), Mockito.any())).thenReturn(0);

        boolean created = service.evaluateAfterRecalc(snapshot("{\"_noDimensions\":true}", "50"));

        assertFalse(created);
        Mockito.verify(reviewMapper, Mockito.never()).insert(Mockito.any(ProfileReviewQueue.class));
    }

    @Test
    void configuredLowScoreStillEnqueues() {
        // 正常路径回归: 真分数低于阈值照常入队, 守卫不能误伤
        reviewEnabled(60);
        Mockito.when(reviewMapper.selectList(Mockito.any())).thenReturn(Collections.emptyList());

        boolean created = service.evaluateAfterRecalc(snapshot("{\"act_freq\":{\"score\":42}}", "42"));

        assertTrue(created);
        Mockito.verify(reviewMapper).insert(Mockito.any(ProfileReviewQueue.class));
    }

    @Test
    void scanSnapshotProjectionMustCarryDimensionDetail() throws Exception {
        // 三审实锤的机制级防法: selectLatestSnapshots 漏 dimension_detail 列时,
        // isPlaceholderScore 在 scan 路径恒 false, 守卫形同虚设——钉住列名
        Method m = ProfileSubjectMapper.class.getMethod("selectLatestSnapshots", String.class, String.class);
        Select select = m.getAnnotation(Select.class);
        assertTrue(select != null && String.join(" ", select.value()).contains("dimension_detail"),
            "selectLatestSnapshots 的 SQL 必须投影 dimension_detail(占位守卫依赖), 改列前先看本测试");
    }
}
