package com.smartdock.fileReview;

import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsFileReview;
import com.smartdock.mapper.DcsFileReviewMapper;
import com.smartdock.risk.domain.bo.RiskEventCommand;
import com.smartdock.risk.service.RiskEventCollector;
import com.smartdock.service.impl.DcsFileReviewServiceImpl;
import com.smartdock.service.support.FileReviewNoticeEvent;
import org.dromara.system.service.ISysUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件审核决策服务单测（12b）：CAS 冲突口径 + 头像回写只在通过那一刻（合同#4/#9）。
 *
 * @author smartdock
 */
@ExtendWith(MockitoExtension.class)
class DcsFileReviewDecisionUnitTest {

    @Mock
    private DcsFileReviewMapper reviewMapper;
    /** 事件发布器桩(驳回通知走 FileReviewNoticeEvent 域事件, dcs 侧翻译, 模块化 2026-09-24) */
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ISysUserService sysUserService;
    @Mock
    private RiskEventCollector riskEventCollector;

    private DcsFileReviewServiceImpl service;

    private static final Long REVIEW_ID = 8801L;
    private static final Long OSS_ID = 9901L;
    private static final Long UPLOADER_ID = 100L;
    private static final Long REVIEWER_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new DcsFileReviewServiceImpl(reviewMapper, eventPublisher, sysUserService, riskEventCollector);
    }

    /** 造一条 PENDING 单（bizSource 可变） */
    private DcsFileReview review(String bizSource) {
        DcsFileReview r = new DcsFileReview();
        r.setReviewId(REVIEW_ID);
        r.setOssId(OSS_ID);
        r.setBizSource(bizSource);
        r.setUploaderId(UPLOADER_ID);
        r.setStatus("PENDING");
        r.setFileName("头像.png");
        return r;
    }

    /** 登录态桩：审核员甲 */
    private static LoginUser reviewerLogin() {
        LoginUser user = new LoginUser();
        user.setUserId(REVIEWER_ID);
        user.setNickname("审核员甲");
        return user;
    }

    @Test
    @Tag("dev")
    void approveCasConflictThrowsAndSkipsAvatarWrite() {
        // CAS 0 行（他窗审核员先处理）→ 全冲突抛"均已被处理"，头像不回写
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(REVIEWER_ID);
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::getLoginUser).thenReturn(reviewerLogin());
            // 服务层租户取值已对齐拦截器口径走 TenantHelper（审查修复），单测须一并桩掉
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review("AVATAR"));
            when(reviewMapper.markApproved(anyString(), anyLong(), anyLong(), anyString())).thenReturn(0);

            assertThatThrownBy(() -> service.approve(java.util.List.of(REVIEW_ID)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("均已被处理");
            verify(sysUserService, never()).updateUserAvatar(anyLong(), anyLong());
        }
    }

    @Test
    @Tag("dev")
    void approveAvatarWritesUserAvatarOnSuccess() {
        // 合同#4：头像单通过那一刻回写 sys_user.avatar（uploaderId+ossId）
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(REVIEWER_ID);
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::getLoginUser).thenReturn(reviewerLogin());
            // 服务层租户取值已对齐拦截器口径走 TenantHelper（审查修复），单测须一并桩掉
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review("AVATAR"));
            when(reviewMapper.markApproved(anyString(), anyLong(), anyLong(), anyString())).thenReturn(1);

            service.approve(java.util.List.of(REVIEW_ID));
            verify(sysUserService, times(1)).updateUserAvatar(UPLOADER_ID, OSS_ID);
        }
    }

    @Test
    @Tag("dev")
    void approveNonAvatarSkipsAvatarWrite() {
        // 非头像单通过：不碰 sys_user.avatar
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(REVIEWER_ID);
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::getLoginUser).thenReturn(reviewerLogin());
            // 服务层租户取值已对齐拦截器口径走 TenantHelper（审查修复），单测须一并桩掉
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review("MISSION_ATTACHMENT"));
            when(reviewMapper.markApproved(anyString(), anyLong(), anyLong(), anyString())).thenReturn(1);

            service.approve(java.util.List.of(REVIEW_ID));
            verify(sysUserService, never()).updateUserAvatar(anyLong(), anyLong());
        }
    }

    @Test
    @Tag("dev")
    void rejectCasConflictThrowsWithoutNotice() {
        // CAS 0 行 → "该单已被处理"；驳回通知不发布（回滚不留通知的等价口径）
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(REVIEWER_ID);
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::getLoginUser).thenReturn(reviewerLogin());
            // 服务层租户取值已对齐拦截器口径走 TenantHelper（审查修复），单测须一并桩掉
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review("MISSION_ATTACHMENT"));
            when(reviewMapper.markRejected(anyString(), anyLong(), anyLong(), anyString(), anyString())).thenReturn(0);

            assertThatThrownBy(() -> service.reject(REVIEW_ID, "内容不清晰"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("该单已被处理");
            verify(eventPublisher, never()).publishEvent(any(FileReviewNoticeEvent.class));
            // 驳回未生效：不记 FILE_REJECTED 风险事件（防幻影）
            verify(riskEventCollector, never()).record(any(RiskEventCommand.class));
        }
    }

    @Test
    @Tag("dev")
    void rejectSuccessPublishesNotice() {
        // CAS 1 行 → 同事务发布驳回通知（上传人收"未通过+理由"）
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(REVIEWER_ID);
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::getLoginUser).thenReturn(reviewerLogin());
            // 服务层租户取值已对齐拦截器口径走 TenantHelper（审查修复），单测须一并桩掉
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            DcsFileReview r = review("MISSION_ATTACHMENT");
            when(reviewMapper.selectById(REVIEW_ID)).thenReturn(r);
            when(reviewMapper.markRejected(anyString(), anyLong(), anyLong(), anyString(), anyString())).thenReturn(1);

            service.reject(REVIEW_ID, "内容不清晰，无法辨认");
            // 域事件内容逐字校验: 驳回单+理由原样进 FileReviewNoticeEvent
            ArgumentCaptor<FileReviewNoticeEvent> noticeCaptor = ArgumentCaptor.forClass(FileReviewNoticeEvent.class);
            verify(eventPublisher, times(1)).publishEvent(noticeCaptor.capture());
            assertThat(noticeCaptor.getValue().getReview()).isEqualTo(r);
            assertThat(noticeCaptor.getValue().getReason()).isEqualTo("内容不清晰，无法辨认");
        }
    }

    @Test
    @Tag("dev")
    void rejectSuccessRecordsFileRejectedEvent() {
        // 风控2.0批2 文件联动：驳回成功记 FILE_REJECTED——归责上传人/refId=审核单/幂等键钉单/无命中词
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(REVIEWER_ID);
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::getLoginUser).thenReturn(reviewerLogin());
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review("MISSION_ATTACHMENT"));
            when(reviewMapper.markRejected(anyString(), anyLong(), anyLong(), anyString(), anyString())).thenReturn(1);

            service.reject(REVIEW_ID, "内容不清晰，无法辨认");
            ArgumentCaptor<RiskEventCommand> captor = ArgumentCaptor.forClass(RiskEventCommand.class);
            // 单测无事务上下文 → isSynchronizationActive()=false 走直发分支
            verify(riskEventCollector, times(1)).record(captor.capture());
            RiskEventCommand cmd = captor.getValue();
            assertThat(cmd.eventType()).isEqualTo("FILE_REJECTED");
            assertThat(cmd.source()).isEqualTo("FILE");
            assertThat(cmd.userId()).isEqualTo(UPLOADER_ID);
            assertThat(cmd.refId()).isEqualTo(REVIEW_ID);
            assertThat(cmd.idempotencyKey()).isEqualTo("file:review:" + REVIEW_ID);
            assertThat(cmd.hitWord()).isNull();
            assertThat(cmd.detail()).contains("头像.png");
        }
    }

    @Test
    @Tag("dev")
    void rejectAvatarNeverTouchesOldAvatar() {
        // 合同#4：头像驳回不动旧头像（sys_user.avatar 不写）
        try (var login = mockStatic(LoginHelper.class); var tenant = mockStatic(TenantHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(REVIEWER_ID);
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::getLoginUser).thenReturn(reviewerLogin());
            // 服务层租户取值已对齐拦截器口径走 TenantHelper（审查修复），单测须一并桩掉
            tenant.when(TenantHelper::getTenantId).thenReturn("000000");
            when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review("AVATAR"));
            when(reviewMapper.markRejected(anyString(), anyLong(), anyLong(), anyString(), anyString())).thenReturn(1);

            service.reject(REVIEW_ID, "包含敏感信息");
            verify(sysUserService, never()).updateUserAvatar(anyLong(), anyLong());
            verify(eventPublisher, times(1)).publishEvent(any(FileReviewNoticeEvent.class));
        }
    }
}
