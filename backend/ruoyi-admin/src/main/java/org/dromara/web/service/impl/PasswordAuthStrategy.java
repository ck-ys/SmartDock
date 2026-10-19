package org.dromara.web.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.Constants;
import org.dromara.common.core.constant.GlobalConstants;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.domain.model.PasswordLoginBody;
import org.dromara.common.core.enums.LoginType;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.exception.user.CaptchaException;
import org.dromara.common.core.exception.user.CaptchaExpireException;
import org.dromara.common.core.exception.user.UserException;
import org.dromara.common.core.utils.MessageUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.ValidatorUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.web.config.properties.CaptchaProperties;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.vo.SysClientVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.web.domain.vo.LoginVo;
import org.dromara.web.service.IAuthStrategy;
import org.dromara.web.service.SysLoginService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 密码认证策略
 *
 * @author Michelle.Chung
 */
@Slf4j
@Service("password" + IAuthStrategy.BASE_NAME)
@RequiredArgsConstructor
public class PasswordAuthStrategy implements IAuthStrategy {

    private final CaptchaProperties captchaProperties;
    private final SysLoginService loginService;
    private final SysUserMapper userMapper;

    @Override
    public LoginVo login(String body, SysClientVo client) {
        PasswordLoginBody loginBody = JsonUtils.parseObject(body, PasswordLoginBody.class);
        ValidatorUtils.validate(loginBody);
        String tenantId = loginBody.getTenantId();
        String username = loginBody.getUsername();
        String password = loginBody.getPassword();
        String code = loginBody.getCode();
        String uuid = loginBody.getUuid();

        boolean captchaEnabled = captchaProperties.getEnable();
        // 验证码开关
        if (captchaEnabled) {
            validateCaptcha(tenantId, username, code, uuid);
        }
        final String[] verifiedHash = new String[1];
        LoginUser loginUser = TenantHelper.dynamic(tenantId, () -> {
            SysUserVo user = loadUserByUsername(username);
            // 记录"校验时"的密码哈希——签名后复核用（深挖审核 Q3，dspro P0-1）
            verifiedHash[0] = user.getPassword();
            // 锁定计数/登录日志按【命中用户的 user_name】记（审查 P1-1，2026-09-26）：输入值可能是手机号，
            // 若按输入值记 key，同一账号将拥有"账号名+手机号"两把独立试错钥匙，暴力锁定强度减半，
            // 且登录成功只清当前 key，另一通道残留计数不重置。loadUser 已在此前抛 user.not.exists，无 NPE 面。
            loginService.checkLogin(LoginType.PASSWORD, tenantId, user.getUserName(), () -> !BCrypt.checkpw(password, user.getPassword()));
            // 此处可根据登录用户的数据不同 自行创建 loginUser
            return loginService.buildLoginUser(user);
        });
        loginUser.setClientKey(client.getClientKey());
        loginUser.setDeviceType(client.getDeviceType());
        SaLoginParameter model = new SaLoginParameter();
        model.setDeviceType(client.getDeviceType());
        // 自定义分配 不同用户体系 不同 token 授权时间 不设置默认走全局 yml 配置
        // 例如: 后台用户30分钟过期 app用户1天过期
        model.setTimeout(client.getTimeout());
        model.setActiveTimeout(client.getActiveTimeout());
        model.setExtra(LoginHelper.CLIENT_KEY, client.getClientId());
        // 生成token
        LoginHelper.login(loginUser, model);
        // 签名后复核（深挖审核 Q3，2026-09-27 拍板）："验密码→建用户→签token"跨多次 DB 往返（约几十毫秒），
        // 忘记密码重置恰落在窗口内时（UPDATE 新密码+踢旧会话都发生在本 token 签发之前），
        // 攻击者用旧密码换到的这张 token 漏网存活最长 30 天——kickout 只删"签发时刻已存在"的会话。
        // 签名后重读一次哈希，不一致说明"校验期间密码被改"，当场作废本 token（kickoutByTokenValue 只踢自己，
        // 不波及真用户其它在线会话；不用 disable 封禁——已核实 sa-token login 不查封禁状态）
        String nowHash = TenantHelper.dynamic(tenantId, () -> {
            SysUser fresh = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getPassword).eq(SysUser::getUserId, loginUser.getUserId()));
            return fresh == null ? null : fresh.getPassword();
        });
        if (!ObjectUtil.equals(verifiedHash[0], nowHash)) {
            StpUtil.kickoutByTokenValue(StpUtil.getTokenValue());
            loginService.recordLogininfor(tenantId, username, Constants.LOGIN_FAIL, "登录期间密码已被重置，拒绝放行");
            throw new ServiceException("密码已变更，请用新密码登录");
        }

        LoginVo loginVo = new LoginVo();
        loginVo.setAccessToken(StpUtil.getTokenValue());
        loginVo.setExpireIn(StpUtil.getTokenTimeout());
        loginVo.setClientId(client.getClientId());
        return loginVo;
    }

    /**
     * 校验验证码
     *
     * @param username 用户名
     * @param code     验证码
     * @param uuid     唯一标识
     */
    private void validateCaptcha(String tenantId, String username, String code, String uuid) {
        String verifyKey = GlobalConstants.CAPTCHA_CODE_KEY + StringUtils.blankToDefault(uuid, "");
        String captcha = RedisUtils.getCacheObject(verifyKey);
        RedisUtils.deleteObject(verifyKey);
        if (captcha == null) {
            loginService.recordLogininfor(tenantId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"));
            throw new CaptchaExpireException();
        }
        if (!StringUtils.equalsIgnoreCase(code, captcha)) {
            loginService.recordLogininfor(tenantId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error"));
            throw new CaptchaException();
        }
    }

    private SysUserVo loadUserByUsername(String username) {
        SysUserVo user = userMapper.selectVoOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUserName, username));
        if (ObjectUtil.isNull(user)) {
            // 手机号兜底通道（登录改造 2026-09-25 拍板 4A）：账号名未命中再按手机号找，
            // 应对将来微信小程序端只有手机号的场景；同租户内多账号同手机号无法唯一定位，明确报错
            List<SysUserVo> byPhone = userMapper.selectVoList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhonenumber, username));
            if (byPhone.size() == 1) {
                user = byPhone.get(0);
            } else if (byPhone.size() > 1) {
                log.info("登录手机号：{} 在本单位下命中多个账号.", username);
                throw new UserException("user.phone.duplicate", username);
            }
        }
        if (ObjectUtil.isNull(user)) {
            log.info("登录用户：{} 不存在.", username);
            throw new UserException("user.not.exists", username);
        } else if (SystemConstants.DISABLE.equals(user.getStatus())) {
            log.info("登录用户：{} 已被停用.", username);
            throw new UserException("user.blocked", username);
        }
        return user;
    }

}
