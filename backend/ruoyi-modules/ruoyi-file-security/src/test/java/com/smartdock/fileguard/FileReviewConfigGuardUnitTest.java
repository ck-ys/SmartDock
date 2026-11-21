package com.smartdock.fileguard;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.bo.FileReviewConfigBo;
import com.smartdock.mapper.FileReviewConfigMapper;
import com.smartdock.mapper.FileReviewMapper;
import com.smartdock.service.impl.FileReviewConfigServiceImpl;
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
class FileReviewConfigGuardUnitTest {

    private final FileReviewConfigMapper configMapper = mock(FileReviewConfigMapper.class);
    private final FileReviewMapper reviewMapper = mock(FileReviewMapper.class);
    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);

    private FileReviewConfigServiceImpl service() {
        when(configMapper.upsertConfig(anyLong(), anyString(), anyString(), anyString(), any(),
            any(), any(), any())).thenReturn(1);
        return new FileReviewConfigServiceImpl(configMapper, reviewMapper, roleMapper, userRoleMapper);
    }

    private FileReviewConfigBo bo() {
        FileReviewConfigBo bo = new FileReviewConfigBo();
        bo.setEnabled(false);
        bo.setCategories(List.of("IMAGE"));
        return bo;
    }

    private void save(FileReviewConfigBo bo) {
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            service().saveConfig(bo);
        }
    }

    @Test
    void defaultsAppliedWhenGuardFieldsAbsent() {
        // 旧调用方只传开关+类别：guard 缺省 OBSERVE/空白名单/50MB，不炸
        FileReviewConfigBo bo = bo();
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
        FileReviewConfigBo bo = bo();
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
        FileReviewConfigBo bo = bo();
        bo.setGuardMode("STRICT");
        assertThatThrownBy(() -> save(bo))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("未知检测模式");
    }

    @Test
    void invalidWhitelistTokenRejected() {
        FileReviewConfigBo bo = bo();
        bo.setGuardWhitelist("exe;rm");
        assertThatThrownBy(() -> save(bo))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("格式不合法");
    }

    @Test
    void deepScanMaxBoundsChecked() {
        FileReviewConfigBo tooSmall = bo();
        tooSmall.setGuardDeepScanMaxMb(0);
        assertThatThrownBy(() -> save(tooSmall)).isInstanceOf(ServiceException.class);

        FileReviewConfigBo tooBig = bo();
        tooBig.setGuardDeepScanMaxMb(501);
        assertThatThrownBy(() -> save(tooBig)).isInstanceOf(ServiceException.class);
    }
}
