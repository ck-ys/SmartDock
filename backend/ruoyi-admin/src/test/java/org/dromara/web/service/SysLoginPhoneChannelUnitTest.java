package org.dromara.web.service;

import cn.hutool.core.exceptions.InvocationTargetRuntimeException;
import cn.hutool.core.util.ReflectUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.user.UserException;
import org.dromara.common.web.config.properties.CaptchaProperties;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.bo.SysTenantBo;
import org.dromara.system.domain.vo.SysTenantVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.ISysTenantService;
import org.dromara.web.domain.vo.TenantListVo;
import org.dromara.web.service.impl.PasswordAuthStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录改造（2026-09-25 拍板 3A+/4A）核心语义单测：纯 Mockito 不连库。
 *
 * <p>覆盖两条后端腿：① {@code listTenantByLoginId} 先识别后登录（0/1/N 三态 +
 * 停用/过期单位过滤 + 停用用户排除 + user_name OR phonenumber 查询面）；
 * ② {@code PasswordAuthStrategy} 账号名未命中时手机号兜底（唯一命中放行 /
 * 多命中明确报错 / 停用用户照拦）。TenantHelper.ignore 纯 ThreadLocal 可在裸测试跑。</p>
 *
 * @author smartdock
 */
@Tag("dev")
class SysLoginPhoneChannelUnitTest {

    /** 纯 Mockito 无 MyBatis 环境，Lambda 列名解析需手动注册实体元数据（现有测试同配方） */
    @BeforeAll
    static void initTableMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
            new MybatisConfiguration(), "login-phone-channel");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    private static SysUserVo user(String tenantId, String status) {
        SysUserVo u = new SysUserVo();
        u.setTenantId(tenantId);
        u.setStatus(status);
        u.setUserName("u-" + tenantId);
        return u;
    }

    private static SysTenantVo tenant(String tenantId, String status, Date expireTime) {
        SysTenantVo t = new SysTenantVo();
        t.setTenantId(tenantId);
        t.setCompanyName("单位" + tenantId);
        t.setStatus(status);
        t.setExpireTime(expireTime);
        return t;
    }

    private SysLoginService serviceWith(SysUserMapper userMapper, List<SysTenantVo> tenants) {
        ISysTenantService tenantService = mock(ISysTenantService.class);
        when(tenantService.queryList(any(SysTenantBo.class))).thenReturn(tenants);
        return new SysLoginService(tenantService,
            mock(org.dromara.system.service.ISysPermissionService.class),
            mock(org.dromara.system.service.ISysSocialService.class),
            mock(org.dromara.system.service.ISysRoleService.class),
            mock(org.dromara.system.service.ISysDeptService.class),
            mock(org.dromara.system.service.ISysPostService.class),
            userMapper);
    }

    // ==================== ① 识别接口（先识别后登录） ====================

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listByLoginIdQueriesNameOrPhoneColumns() {
        // 查询面必须是 user_name OR phonenumber——漏任一列即双通道断裂
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoList(any())).thenReturn(List.of());
        SysLoginService service = serviceWith(userMapper, List.of());

        service.listTenantByLoginId("18300005678");

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectVoList(captor.capture());
        LambdaQueryWrapper<?> w = captor.getValue();
        // 查询面必须是 user_name OR phonenumber——漏任一列即双通道断裂；
        // 且 loginId 参数值须真绑定（防只拼列名不传参的假阳性，审查 P2-6）
        assertThat(w.getSqlSegment()).contains("user_name").contains("phonenumber").contains("OR");
        assertThat(w.getParamNameValuePairs().values()).contains("18300005678");
    }

    @Test
    void listByLoginIdReturnsEmptyWhenNoMatch() {
        // 0 态：账号/手机号都不存在 → 空（前端提示"账号不存在"，不暴露细节）
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoList(any())).thenReturn(List.of());
        SysLoginService service = serviceWith(userMapper, List.of(tenant("000000", "0", null)));

        assertThat(service.listTenantByLoginId("不存在的账号")).isEmpty();
    }

    @Test
    void listByLoginIdSingleTenantDirectLogin() {
        // 1 态：仅一家可登录单位 → 单元素（前端直接进，不弹窗）
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoList(any())).thenReturn(List.of(user("000000", "0")));
        SysLoginService service = serviceWith(userMapper, List.of(
            tenant("000000", "0", null), tenant("900002", "0", null)));

        List<TenantListVo> result = service.listTenantByLoginId("张三");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("000000");
        assertThat(result.get(0).getCompanyName()).isEqualTo("单位000000");
    }

    @Test
    void listByLoginIdMultiTenantsSortedForStablePicker() {
        // N 态：两家命中 → 弹窗数据；按 tenantId 排序保证展示顺序稳定
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoList(any())).thenReturn(
            List.of(user("900002", "0"), user("000000", "0")));
        SysLoginService service = serviceWith(userMapper, List.of(
            tenant("000000", "0", null), tenant("900002", "0", null)));

        List<TenantListVo> result = service.listTenantByLoginId("18300005678");
        assertThat(result).extracting(TenantListVo::getTenantId)
            .containsExactly("000000", "900002");
    }

    @Test
    void listByLoginIdFiltersExpiredAndDisabledTenant() {
        // 过期租户与停用租户都不出现在识别结果——
        // 用户归其名下也不给登录入口（与 checkTenant 同口径）
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoList(any())).thenReturn(List.of(user("900001", "0")));
        SysLoginService service = serviceWith(userMapper, List.of(
            tenant("000000", "0", null), tenant("900001", "0", new Date(System.currentTimeMillis() - 86400_000L))));

        assertThat(service.listTenantByLoginId("过期单位的用户")).isEmpty();
    }

    @Test
    void listByLoginIdIgnoresDisabledUser() {
        // 停用用户不算数（识别阶段就排除，避免引导到必然失败的登录）
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoList(any())).thenReturn(List.of(user("000000", "1")));
        SysLoginService service = serviceWith(userMapper, List.of(tenant("000000", "0", null)));

        assertThat(service.listTenantByLoginId("被停用的账号")).isEmpty();
    }

    @Test
    void loginableAlwaysKeepsDefaultTenant() {
        // 000000 管理租户豁免：即使停用/过期也保持可登录（框架口径，checkTenant 同款豁免）
        assertThat(SysLoginService.loginable(tenant("000000", "1", new Date(0)), new Date())).isTrue();
        assertThat(SysLoginService.loginable(tenant("900001", "0", new Date(0)), new Date())).isFalse();
        assertThat(SysLoginService.loginable(tenant("900002", "1", null), new Date())).isFalse();
        assertThat(SysLoginService.loginable(tenant("900002", "0", null), new Date())).isTrue();
    }

    // ==================== ② 密码策略手机号兜底 ====================

    private PasswordAuthStrategy strategyWith(SysUserMapper userMapper) {
        return new PasswordAuthStrategy(new CaptchaProperties(),
            mock(SysLoginService.class), userMapper);
    }

    @Test
    void phoneFallbackLoadsUniquePhoneUser() {
        // 账号名未命中 + 手机号唯一命中 → 兜底成功
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoOne(any())).thenReturn(null);
        SysUserVo phoneUser = user("000000", "0");
        when(userMapper.selectVoList(any())).thenReturn(List.of(phoneUser));

        Object result = ReflectUtil.invoke(strategyWith(userMapper), "loadUserByUsername", "18300005678");
        assertThat(result).isSameAs(phoneUser);
    }

    @Test
    void phoneFallbackRejectsDuplicatePhoneInTenant() {
        // 同租户多账号同手机号 → 无法唯一定位，明确报错引导改用账号名
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoOne(any())).thenReturn(null);
        when(userMapper.selectVoList(any())).thenReturn(
            List.of(user("000000", "0"), user("000000", "0")));

        PasswordAuthStrategy strategy = strategyWith(userMapper);
        // ReflectUtil 会把反射目标异常包成 InvocationTargetRuntimeException, 断言按根因判定
        // （根因 message 须含"多个账号"——UserException 同类三态互相顶替也能过 hasRootCauseInstanceOf，审查 P2-6）
        try {
            ReflectUtil.invoke(strategy, "loadUserByUsername", "18300005678");
            throw new AssertionError("expected exception");
        } catch (InvocationTargetRuntimeException wrapper) {
            Throwable root = wrapper;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            // 断言精确到 code 而非仅类型（审查 P2-6）：UserException 同类三态互相顶替也能过
            // hasRootCauseInstanceOf；裸 JVM 下 getMessage 是惰性 i18n 必炸，须用 getCode 判定
            assertThat(root).isInstanceOf(UserException.class);
            assertThat(((org.dromara.common.core.exception.base.BaseException) root).getCode())
                .isEqualTo("user.phone.duplicate");
        }
    }

    @Test
    void phoneFallbackStillBlocksDisabledUser() {
        // 兜底找到的用户同样过停用检查——手机号通道不是停用账号的后门
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectVoOne(any())).thenReturn(null);
        when(userMapper.selectVoList(any())).thenReturn(List.of(user("000000", "1")));

        PasswordAuthStrategy strategy = strategyWith(userMapper);
        try {
            ReflectUtil.invoke(strategy, "loadUserByUsername", "18300005678");
            throw new AssertionError("expected exception");
        } catch (InvocationTargetRuntimeException wrapper) {
            Throwable root = wrapper;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            // code 精确断言（审查 P2-6）：停用用户必须报 blocked 而非 not.exists
            assertThat(root).isInstanceOf(UserException.class);
            assertThat(((org.dromara.common.core.exception.base.BaseException) root).getCode())
                .isEqualTo("user.blocked");
        }
    }

    @Test
    void nameHitSkipsPhoneFallbackQuery() {
        // 账号名直命中 → 不再查手机号（优先级：账号名 > 手机号）
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUserVo nameUser = user("000000", "0");
        when(userMapper.selectVoOne(any())).thenReturn(nameUser);

        Object result = ReflectUtil.invoke(strategyWith(userMapper), "loadUserByUsername", "admin");
        assertThat(result).isSameAs(nameUser);
        verify(userMapper, times(0)).selectVoList(any());
    }
}
