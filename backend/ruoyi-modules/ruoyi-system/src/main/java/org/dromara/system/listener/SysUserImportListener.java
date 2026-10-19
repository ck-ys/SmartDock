package org.dromara.system.listener;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.http.HtmlUtil;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StreamUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.ValidatorUtils;
import org.dromara.common.excel.core.ExcelListener;
import org.dromara.common.excel.core.ExcelResult;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysUserImportVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.service.ISysConfigService;
import org.dromara.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 系统用户自定义导入
 *
 * @author Lion Li
 */
@Slf4j
public class SysUserImportListener extends AnalysisEventListener<SysUserImportVo> implements ExcelListener<SysUserImportVo> {

    private final ISysUserService userService;

    private final String password;

    private final Boolean isUpdateSupport;

    private final Long operUserId;

    private int successNum = 0;
    private int failureNum = 0;
    private final StringBuilder successMsg = new StringBuilder();
    private final StringBuilder failureMsg = new StringBuilder();

    public SysUserImportListener(Boolean isUpdateSupport) {
        String initPassword = SpringUtils.getBean(ISysConfigService.class).selectConfigByKey("sys.user.initPassword");
        this.userService = SpringUtils.getBean(ISysUserService.class);
        this.password = BCrypt.hashpw(initPassword);
        this.isUpdateSupport = isUpdateSupport;
        this.operUserId = LoginHelper.getUserId();
    }

    @Override
    public void invoke(SysUserImportVo userVo, AnalysisContext context) {
        SysUserVo sysUser = this.userService.selectUserByUserName(userVo.getUserName());
        // 查重 Bo（手机号+邮箱共用）：更新分支须带上库内 userId 排除自身（glm53 P1-2）——
        // Excel"用户序号"列通常不填、Bo.userId 恒 null，不排除则"没改的老用户刷一遍数据"
        // 会被 exists 命中自己误报已存在
        SysUserBo checkBo = BeanUtil.toBean(userVo, SysUserBo.class);
        if (ObjectUtil.isNotNull(sysUser)) {
            checkBo.setUserId(sysUser.getUserId());
        }
        // 手机号查重：导入路径原是唯一没保安的门，重复手机号会打残该批用户的
        // 手机号登录兜底通道（PasswordAuthStrategy 多命中直接报 user.phone.duplicate）
        boolean phoneTaken = StringUtils.isNotBlank(userVo.getPhonenumber())
            && !userService.checkPhoneUnique(checkBo);
        if (phoneTaken) {
            failureNum++;
            failureMsg.append("<br/>").append(failureNum).append("、账号 ").append(HtmlUtil.cleanHtmlTag(userVo.getUserName()))
                .append(" 导入失败：手机号 ").append(HtmlUtil.cleanHtmlTag(userVo.getPhonenumber())).append(" 已存在");
            return;
        }
        // 邮箱查重（深挖审核 Q6，2026-09-27 拍板）：原路径只查手机号，撞邮箱走
        // uk_sys_user_tenant_email_ci 索引炸 DuplicateKeyException 天书报错——
        // 索引兜底数据不坏，但管理员看不懂；补预检给同款人话文案
        boolean emailTaken = StringUtils.isNotBlank(userVo.getEmail())
            && !userService.checkEmailUnique(checkBo);
        if (emailTaken) {
            failureNum++;
            failureMsg.append("<br/>").append(failureNum).append("、账号 ").append(HtmlUtil.cleanHtmlTag(userVo.getUserName()))
                .append(" 导入失败：邮箱 ").append(HtmlUtil.cleanHtmlTag(userVo.getEmail())).append(" 已被其他账号使用");
            return;
        }
        try {
            // 验证是否存在这个用户
            if (ObjectUtil.isNull(sysUser)) {
                SysUserBo user = BeanUtil.toBean(userVo, SysUserBo.class);
                ValidatorUtils.validate(user);
                user.setPassword(password);
                user.setCreateBy(operUserId);
                userService.insertUser(user);
                successNum++;
                successMsg.append("<br/>").append(successNum).append("、账号 ").append(user.getUserName()).append(" 导入成功");
            } else if (isUpdateSupport) {
                Long userId = sysUser.getUserId();
                SysUserBo user = BeanUtil.toBean(userVo, SysUserBo.class);
                user.setUserId(userId);
                ValidatorUtils.validate(user);
                userService.checkUserAllowed(user.getUserId());
                userService.checkUserDataScope(user.getUserId());
                user.setUpdateBy(operUserId);
                userService.updateUser(user);
                successNum++;
                successMsg.append("<br/>").append(successNum).append("、账号 ").append(user.getUserName()).append(" 更新成功");
            } else {
                failureNum++;
                failureMsg.append("<br/>").append(failureNum).append("、账号 ").append(sysUser.getUserName()).append(" 已存在");
            }
        } catch (Exception e) {
            failureNum++;
            String msg = "<br/>" + failureNum + "、账号 " + HtmlUtil.cleanHtmlTag(userVo.getUserName()) + " 导入失败：";
            String message = e.getMessage();
            if (e instanceof ConstraintViolationException cvException) {
                message = StreamUtils.join(cvException.getConstraintViolations(), ConstraintViolation::getMessage, ", ");
            }
            failureMsg.append(msg).append(message);
            log.error(msg, e);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {

    }

    @Override
    public ExcelResult<SysUserImportVo> getExcelResult() {
        return new ExcelResult<>() {

            @Override
            public String getAnalysis() {
                if (failureNum > 0) {
                    failureMsg.insert(0, "很抱歉，导入失败！共 " + failureNum + " 条数据格式不正确，错误如下：");
                    throw new ServiceException(failureMsg.toString());
                } else {
                    successMsg.insert(0, "恭喜您，数据已全部导入成功！共 " + successNum + " 条，数据如下：");
                }
                return successMsg.toString();
            }

            @Override
            public List<SysUserImportVo> getList() {
                return null;
            }

            @Override
            public List<String> getErrorList() {
                return null;
            }
        };
    }
}
