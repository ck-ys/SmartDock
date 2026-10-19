package org.dromara.assistant.service;

import org.dromara.assistant.domain.DcsAiModelCapability;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务批3-①（Q2A 2026-10-12）单元测试：覆盖弹窗「支持输入」三勾选框的合成语义。
 *
 * <p>文本恒支持不设字段（ZCode 高级设置同款口径：可选输入能力只有图片/视频/音频）；
 * mergeSupports 规则：请求非 null=租户显式覆盖，null=从 SEED 目录行继承，SEED 缺=保守 false。</p>
 */
@Tag("dev")
class CapabilityOverrideSupportsUnitTest {

    private static DcsAiModelCapability seed(boolean img, boolean video, boolean audio) {
        DcsAiModelCapability r = new DcsAiModelCapability();
        r.setSupportsImage(img);
        r.setSupportsVideo(video);
        r.setSupportsAudio(audio);
        return r;
    }

    @Test
    void 全部未提交_从种子行继承真实值() {
        CapabilityService.OverrideBo bo = new CapabilityService.OverrideBo();
        DcsAiModelCapability merged = CapabilityService.mergeSupports(seed(true, true, false), bo);
        assertThat(merged.getSupportsImage()).isTrue();
        assertThat(merged.getSupportsVideo()).isTrue();
        assertThat(merged.getSupportsAudio()).isFalse();
    }

    @Test
    void 全部未提交_无种子行_保守落false() {
        CapabilityService.OverrideBo bo = new CapabilityService.OverrideBo();
        DcsAiModelCapability merged = CapabilityService.mergeSupports(null, bo);
        assertThat(merged.getSupportsImage()).isFalse();
        assertThat(merged.getSupportsVideo()).isFalse();
        assertThat(merged.getSupportsAudio()).isFalse();
    }

    @Test
    void 部分覆盖_覆盖列用请求值_其余列继承种子() {
        CapabilityService.OverrideBo bo = new CapabilityService.OverrideBo();
        bo.setSupportsImage(Boolean.TRUE);
        DcsAiModelCapability merged = CapabilityService.mergeSupports(seed(false, true, false), bo);
        // 图片=租户勾选覆盖目录的 false；视频=未勾选继承目录 true；音频=两边皆无=false
        assertThat(merged.getSupportsImage()).isTrue();
        assertThat(merged.getSupportsVideo()).isTrue();
        assertThat(merged.getSupportsAudio()).isFalse();
    }

    @Test
    void 显式取消_压掉种子行的true() {
        CapabilityService.OverrideBo bo = new CapabilityService.OverrideBo();
        bo.setSupportsImage(Boolean.FALSE);
        DcsAiModelCapability merged = CapabilityService.mergeSupports(seed(true, false, false), bo);
        // 网关实际不支持图片时，租户可用勾选框纠正目录（显式 false 优先于种子 true）
        assertThat(merged.getSupportsImage()).isFalse();
    }
}
