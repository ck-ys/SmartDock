package org.dromara.system.controller.system;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.file.MimeTypeUtils;
import org.dromara.common.encrypt.annotation.ApiEncrypt;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.bo.SysUserPasswordBo;
import org.dromara.system.domain.bo.SysUserProfileBo;
import org.dromara.system.domain.vo.ProfileUserVo;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.domain.vo.SysOssVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysOssService;
import org.dromara.system.service.ISysUserService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;

/**
 * 个人信息 业务处理
 *
 * @author Lion Li
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/user/profile")
public class SysProfileController extends BaseController {

    private final ISysUserService userService;
    private final ISysOssService ossService;
    private final ISysDeptService deptService;

    /**
     * 个人信息
     */
    @GetMapping
    public R<ProfileVo> profile() {
        SysUserVo user = userService.selectUserById(LoginHelper.getUserId());
        String roleGroup = userService.selectUserRoleGroup(user.getUserId());
        String postGroup = userService.selectUserPostGroup(user.getUserId());
        // 单独做一个vo专门给个人中心用 避免数据被脱敏
        ProfileUserVo profileUser = BeanUtil.toBean(user, ProfileUserVo.class);
        profileUser.setDeptFullName(buildDeptFullName(user.getDeptId()));
        ProfileVo profileVo = new ProfileVo(profileUser, roleGroup, postGroup);
        return R.ok(profileVo);
    }

    /**
     * 个人中心"所属部门"展示父级/本级两级路径(业务要求, 如 "信息工程部门 / 教学组"),
     * 只取直接上级、不追溯更上层; 用户直挂根部门或父部门查不到时退化为仅本级名。
     */
    private String buildDeptFullName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        SysDeptVo dept = deptService.selectDeptById(deptId);
        if (dept == null) {
            return null;
        }
        if (dept.getParentId() == null || dept.getParentId() == 0L) {
            return dept.getDeptName();
        }
        SysDeptVo parent = deptService.selectDeptById(dept.getParentId());
        if (parent == null || StringUtils.isBlank(parent.getDeptName())) {
            return dept.getDeptName();
        }
        return parent.getDeptName() + " / " + dept.getDeptName();
    }

    /**
     * 修改用户信息
     */
    @RepeatSubmit
    @Log(title = "个人信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> updateProfile(@Validated @RequestBody SysUserProfileBo profile) {
        SysUserBo user = BeanUtil.toBean(profile, SysUserBo.class);
        user.setUserId(LoginHelper.getUserId());
        // 邮箱是「忘记密码」的找回通道（批B Q2-B），变更必须走收件验证的
        // /system/user/profile/email/bind——updateUserProfile 的 UPDATE 已不再 set email 列
        // （深挖审核 Q1：邮箱单一写者=绑定通道；原"透传库内值再写回"两跳间无锁，
        // 与并发绑定时新邮箱会被旧值回滚，库/界面/换绑通知三方矛盾）
        String username = LoginHelper.getUsername();
        if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user)) {
            return R.fail("修改用户'" + username + "'失败，手机号码已存在");
        }
        int rows = DataPermissionHelper.ignore(() -> userService.updateUserProfile(user));
        if (rows > 0) {
            return R.ok();
        }
        return R.fail("修改个人信息异常，请联系管理员");
    }

    /**
     * 重置密码
     *
     * @param bo 新旧密码
     */
    @RepeatSubmit
    @ApiEncrypt
    @Log(title = "个人信息", businessType = BusinessType.UPDATE)
    @PutMapping("/updatePwd")
    public R<Void> updatePwd(@Validated @RequestBody SysUserPasswordBo bo) {
        SysUserVo user = userService.selectUserById(LoginHelper.getUserId());
        String password = user.getPassword();
        if (!BCrypt.checkpw(bo.getOldPassword(), password)) {
            return R.fail("修改密码失败，旧密码错误");
        }
        if (BCrypt.checkpw(bo.getNewPassword(), password)) {
            return R.fail("新密码不能与旧密码相同");
        }
        int rows = DataPermissionHelper.ignore(() -> userService.resetUserPwd(user.getUserId(), BCrypt.hashpw(bo.getNewPassword())));
        if (rows > 0) {
            // 深挖审核 Q8②：登录态改密成功踢掉该账号全部会话（含当前）——与匿名重置的 kickout
            // 同口径（SysPwdResetService P1-2）。改密码是"失窃自救"动作，不踢会话则攻击者凭
            // 旧 token 最长仍有效 30 天；前端下一次请求 401 自动跳回登录页
            StpUtil.kickout(LoginHelper.getLoginUser().getLoginId());
            return R.ok("密码修改成功，请重新登录");
        }
        return R.fail("修改密码异常，请联系管理员");
    }

    /**
     * 头像上传
     *
     * @param avatarfile 用户头像
     */
    @RepeatSubmit
    @Log(title = "用户头像", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<AvatarVo> avatar(@RequestPart("avatarfile") MultipartFile avatarfile) {
        if (ObjectUtil.isNotNull(avatarfile) && !avatarfile.isEmpty()) {
            String extension = FileUtil.extName(avatarfile.getOriginalFilename());
            if (!StringUtils.equalsAnyIgnoreCase(extension, MimeTypeUtils.IMAGE_EXTENSION)) {
                return R.fail("文件格式不正确，请上传" + Arrays.toString(MimeTypeUtils.IMAGE_EXTENSION) + "格式");
            }
            SysOssVo oss = ossService.upload(avatarfile, "AVATAR", null);
            // 12b 先审后生效：待审期间不写 sys_user.avatar（旧头像保持），
            // 审核通过那一刻由 dcs 审核服务回写；驳回通知重传，旧头像不动
            if ("PENDING".equals(oss.getReviewStatus())) {
                return R.ok(new AvatarVo(null, oss.getOssId(), "PENDING"));
            }
            String avatar = oss.getUrl();
            boolean updateSuccess = DataPermissionHelper.ignore(() -> userService.updateUserAvatar(LoginHelper.getUserId(), oss.getOssId()));
            if (updateSuccess) {
                return R.ok(new AvatarVo(avatar, oss.getOssId(), oss.getReviewStatus()));
            }
        }
        return R.fail("上传图片异常，请联系管理员");
    }

    /**
     * 用户头像信息
     *
     * @param imgUrl       头像地址（兼容旧前端；待审时为 null——审核通过才生效）
     * @param ossId        头像 OSS ID（供受控预览接口使用）
     * @param reviewStatus 审核状态（12b）：PENDING=待审未生效，SKIPPED/null=已生效
     */
    public record AvatarVo(String imgUrl, Long ossId, String reviewStatus) {}

    /**
     * 用户个人信息
     *
     * @param user      用户信息
     * @param roleGroup 用户所属角色组
     * @param postGroup 用户所属岗位组
     */
    public record ProfileVo(ProfileUserVo user, String roleGroup, String postGroup) {}

}
