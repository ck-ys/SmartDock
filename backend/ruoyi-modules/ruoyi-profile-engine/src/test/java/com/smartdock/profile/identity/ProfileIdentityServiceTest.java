package com.smartdock.profile.identity;

import com.smartdock.profile.identity.ProfileIdentityMerge;
import com.smartdock.profile.mapper.ProfileIdentityLinkMapper;
import com.smartdock.profile.mapper.ProfileIdentityMergeMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 身份归一的安全边界: 绝不把真实用户的身份链接搬给别的主体。
 * 用 Mockito 模拟两个 Mapper, 只验证编排逻辑, 不连库。
 */
@Tag("local")
class ProfileIdentityServiceTest {

    private final ProfileIdentityLinkMapper linkMapper = Mockito.mock(ProfileIdentityLinkMapper.class);
    private final ProfileIdentityMergeMapper mergeMapper = Mockito.mock(ProfileIdentityMergeMapper.class);
    // 批10 Q6 起依赖四个协作件(拆分事务/回放/重算), 归一测试用不到但构造必须给
    private final org.springframework.transaction.support.TransactionTemplate txTemplate =
        Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);
    private final com.smartdock.analytics.engine.AnalyticsService analyticsService =
        Mockito.mock(com.smartdock.analytics.engine.AnalyticsService.class);
    private final com.smartdock.profile.engine.ProfileScoreEngine scoreEngine =
        Mockito.mock(com.smartdock.profile.engine.ProfileScoreEngine.class);
    private final com.smartdock.profile.engine.ProfileScoreModelService modelService =
        Mockito.mock(com.smartdock.profile.engine.ProfileScoreModelService.class);
    private final ProfileIdentityService service = new ProfileIdentityService(
        linkMapper, mergeMapper, txTemplate, analyticsService, scoreEngine, modelService);

    static {
        // 批11 Q4: resolveAndLink 已包进事务模板+advisory lock——mock 模板直通执行回调
    }

    ProfileIdentityServiceTest() {
        Mockito.doAnswer(inv ->
                ((org.springframework.transaction.support.TransactionCallback<?>) inv.getArgument(0))
                    .doInTransaction(null))
            .when(txTemplate).execute(any());
    }

    private ProfileIdentityLink link(String subjectId) {
        ProfileIdentityLink link = new ProfileIdentityLink();
        link.setSubjectId(subjectId);
        link.setSubjectType("user");
        return link;
    }

    @Test
    void anonymousRevisitAfterLoginKeepsUserAttribution() {
        // 匿名链接 A→U(U 持有 user_id 强标识); 登录后的下一次匿名事件必须归因到 U,
        // 且绝不能把 U 并入匿名主体(旧实现反向 merge 会把用户链接搬走)
        when(linkMapper.selectLinkShared("000000", "web", "anonymous_id", "anon-A"))
            .thenReturn(link("user-1"));
        when(linkMapper.countStrongLinks("000000", "web", "user", "user-1")).thenReturn(1);

        String canonical = service.resolveAndLink("000000", "web", "visitor", "anon-A",
            Map.of("anonymous_id", "anon-A"));

        assertThat(canonical).isEqualTo("user-1");
        verify(mergeMapper, never()).insert(any(ProfileIdentityMerge.class));
        verify(linkMapper, never()).moveSubject(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sharedBrowserSecondLoginDoesNotStealFirstUserLinks() {
        // 浏览器匿名 A 已挂到 user-1; user-2 在同一浏览器登录: 只改挂匿名链接, 不搬 user-1 的身份
        when(linkMapper.selectLinkStrong("000000", "web", "user", "user_id", "user-2")).thenReturn(null);
        when(linkMapper.selectLinkShared("000000", "web", "anonymous_id", "anon-A"))
            .thenReturn(link("user-1"));
        when(linkMapper.countStrongLinks("000000", "web", "user", "user-1")).thenReturn(1);

        String canonical = service.resolveAndLink("000000", "web", "user", "user-2",
            Map.of("user_id", "user-2", "anonymous_id", "anon-A"));

        assertThat(canonical).isEqualTo("user-2");
        verify(mergeMapper, never()).insert(any(ProfileIdentityMerge.class));
        verify(linkMapper, never()).moveSubject(anyString(), anyString(), anyString(), anyString(), anyString());
        // 匿名链接改挂到 user-2(共享设备当前使用者), 而不是搬走 user-1 的链接
        verify(linkMapper).upsertLink(Mockito.argThat(l ->
            "user-2".equals(l.getSubjectId()) && "anonymous_id".equals(l.getIdentifierType())));
    }

    @Test
    void firstLoginUpgradesPureAnonymousSubject() {
        // 匿名链接 A→A(纯匿名主体, 无强标识): 首次登录正常升级, A 并入规范主体
        when(linkMapper.selectLinkStrong("000000", "web", "user", "user_id", "user-1")).thenReturn(null);
        when(linkMapper.selectLinkShared("000000", "web", "anonymous_id", "anon-A"))
            .thenReturn(link("anon-A"));
        when(linkMapper.countStrongLinks("000000", "web", "user", "anon-A")).thenReturn(0);

        String canonical = service.resolveAndLink("000000", "web", "user", "user-1",
            Map.of("user_id", "user-1", "anonymous_id", "anon-A"));

        assertThat(canonical).isEqualTo("user-1");
        verify(linkMapper).moveSubject(eq("000000"), eq("web"), eq("user"),
            eq("anon-A"), eq("user-1"));
        verify(mergeMapper).insert(any(ProfileIdentityMerge.class));
    }

    @Test
    void memberSameNumberDoesNotStealUserLink() {
        // 员工(user)命名空间已用 user_id=123; 商城顾客(member)事件带同号标识: 各命名空间互不串门
        when(linkMapper.selectLinkStrong("000000", "mall", "member", "user_id", "123"))
            .thenReturn(null);

        String canonical = service.resolveAndLink("000000", "mall", "member", "member-1",
            Map.of("user_id", "123"));

        assertThat(canonical).isEqualTo("member-1");
        verify(mergeMapper, never()).insert(any(ProfileIdentityMerge.class));
        verify(linkMapper, never()).moveSubject(anyString(), anyString(), anyString(), anyString(), anyString());
        // 强标识登记在 member 命名空间, 不会改挂到 user 名下
        verify(linkMapper).upsertLink(Mockito.argThat(l ->
            "member".equals(l.getSubjectType()) && "member-1".equals(l.getSubjectId())
                && "user_id".equals(l.getIdentifierType())));
    }

    @Test
    void strongUserIdConflictKeepsExistingSubject() {
        // user_id 已挂 user-1, 来了一个 subjectId=user-2 的事件: 沿用 user-1, 不改挂强标识
        when(linkMapper.selectLinkStrong("000000", "web", "user", "user_id", "u-100"))
            .thenReturn(link("user-1"));

        String canonical = service.resolveAndLink("000000", "web", "user", "user-2",
            Map.of("user_id", "u-100"));

        assertThat(canonical).isEqualTo("user-1");
        verify(mergeMapper, never()).insert(any(ProfileIdentityMerge.class));
        // 强标识只能刷新 lastSeen, 主体仍须是 user-1, 绝不能改挂到 user-2
        verify(linkMapper).upsertLink(Mockito.argThat(l ->
            "user-1".equals(l.getSubjectId()) && "user_id".equals(l.getIdentifierType())));
    }
}
