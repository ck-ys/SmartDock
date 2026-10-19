package com.smartdock.fileguard;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.bo.DcsFileReviewConfigBo;
import com.smartdock.mapper.DcsFileReviewConfigMapper;
import com.smartdock.mapper.DcsFileReviewMapper;
import com.smartdock.service.impl.DcsFileReviewConfigServiceImpl;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置服务 file-guard 三字段归一/校验单测（批三，Q5=B 界面化管理的服务端兜底）。
 *
 * @author smartdock
 */
@Tag("dev")
class DcsFileReviewConfigGuardUnitTest {

    private final DcsFileReviewConfigMapper configMapper = mock(DcsFileReviewConfigMapper.class);
    private final DcsFileReviewMapper reviewMapper = mock(DcsFileReviewMapper.class);
    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);

    private DcsFileReviewConfigServiceImpl service() {
        when(configMapper.upsertConfig(anyLong(), anyString(), anyString(), anyString(), any(),
            any(), any(), any())).thenReturn(1);
        return new DcsFileReviewConfigServiceImpl(configMapper, reviewMapper, roleMapper, userRoleMapper);
    }

    private DcsFileReviewConfigBo bo() {
        DcsFileReviewConfigBo bo = new DcsFileReviewConfigBo();
        bo.setEnabled(false);
        bo.setCategories(List.of("IMAGE"));
        return bo;
    }

    private void save(DcsFileReviewConfigBo bo) {
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            service().saveConfig(bo);
        }
    }

    @Test
    void defaultsAppliedWhenGuardFieldsAbsent() {
        // 旧调用方只传开关+类别：guard 缺省 OBSERVE/空白名单/50MB，不炸
        DcsFileReviewConfigBo bo = bo();
        save(bo);
        ArgumentCaptor<String> mode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> whitelist = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> maxMb = ArgumentCaptor.forClass(Integer.class);
        verify(configMapper).upsertConfig(anyLong(), anyString(), anyString(), anyString(), any(),
            mode.capture(), whitelist.capture(), maxMb.capture());
        assertThat(mode.getValue()).isEqualTo("OBSERVE");
        assertThat(whitelist.getValue()).isEmpty();
        assertThat(maxMb.getValue()).isEqualTo(50);
    }

    @Test
    void whitelistNormalized() {
        DcsFileReviewConfigBo bo = bo();
        bo.setGuardMode("enforce");
        bo.setGuardWhitelist(" DOC , .Docx ,pdf,,PDF ");
        save(bo);
        ArgumentCaptor<String> whitelist = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> mode = ArgumentCaptor.forClass(String.class);
        verify(configMapper).upsertConfig(anyLong(), anyString(), anyString(), anyString(), any(),
            mode.capture(), whitelist.capture(), any());
        // trim/去前导点/小写/去空/去重
        assertThat(whitelist.getValue()).isEqualTo("doc,docx,pdf");
        assertThat(mode.getValue()).isEqualTo("ENFORCE");
    }

    @Test
    void invalidModeRejected() {
        DcsFileReviewConfigBo bo = bo();
        bo.setGuardMode("STRICT");
        assertThatThrownBy(() -> save(bo))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("未知检测模式");
    }

    @Test
    void invalidWhitelistTokenRejected() {
        DcsFileReviewConfigBo bo = bo();
        bo.setGuardWhitelist("exe;rm");
        assertThatThrownBy(() -> save(bo))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("格式不合法");
    }

    @Test
    void deepScanMaxBoundsChecked() {
        DcsFileReviewConfigBo tooSmall = bo();
        tooSmall.setGuardDeepScanMaxMb(0);
        assertThatThrownBy(() -> save(tooSmall)).isInstanceOf(ServiceException.class);

        DcsFileReviewConfigBo tooBig = bo();
        tooBig.setGuardDeepScanMaxMb(501);
        assertThatThrownBy(() -> save(tooBig)).isInstanceOf(ServiceException.class);
    }
}
