package org.dromara.web.service;

import cn.hutool.core.lang.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.Constants;
import org.dromara.common.core.constant.GlobalConstants;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.log.event.LogininforEvent;
import org.dromara.common.mail.utils.MailUtils;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.service.ISysUserService;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 个人信息·邮箱自助绑定（批B 2026-09-26 拍板 Q2-B）
 *
 * <p>流程（登录态）：输入新邮箱 → 发 6 位验证码到该邮箱（所有权验证，60 秒限发）
 * → 输码提交 → 码单次消费 + 唯一性复验 → 回写 sys_user.email。</p>
 *
 * <p>设计约束：
 * ① 邮箱是「忘记密码」的找回通道（批4），变更必须经过收件验证——基本资料保存
 * 路径已同步锁死 email 字段（SysProfileController.updateProfile 置 null），杜绝旁路；
 * ② Redis key 用 global:captcha_codes:EMAIL_BIND:{tenantId}:{userId} 前缀——
 * 含 "global:" 段可豁免多租户 NameMapper 前缀（批A 地雷认知），登录态读写恒同宇宙；
 * ③ 手机号绑定不做（拍板 Q2-B，等短信通道）。</p>
 *
 * @author smartdock
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SysEmailBindService {

    /** 验证码 Redis key：global:captcha_codes:EMAIL_BIND:{tenantId}:{userId}（按用户记账，换目标邮箱也共用限频） */
    private static final String CODE_KEY = GlobalConstants.CAPTCHA_CODE_KEY + "EMAIL_BIND:";

    /** 发码限频 key：同一用户 60 秒一次（限频锚定 userId 而非目标邮箱，防换邮箱刷发） */
    private static final String RATE_KEY = GlobalConstants.CAPTCHA_CODE_KEY + "EMAIL_BIND:RATE:";

    /** 验证码错误计数 key：同码错 5 次即作废（对齐批4 忘记密码防爆破口径） */
    private static final String FAIL_KEY = GlobalConstants.CAPTCHA_CODE_KEY + "EMAIL_BIND:FAIL:";

    private static final int MAX_CODE_FAILS = 5;

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RATE_TTL = Duration.ofSeconds(60);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ISysUserService userService;

    /**
     * 发送绑定验证码到新邮箱
     *
     * @return 打码后的目标邮箱（前端 toast 提示用）
     */
    public String sendCode(String email) {
        // SMTP 自检前置（批A P1-3 纪律）：与输入无关的配置性检查放最前，未配置环境一视同仁
        if (!"true".equalsIgnoreCase(SpringUtils.getProperty("mail.enabled"))) {
            throw new ServiceException("邮箱服务未配置，请联系管理员");
        }
        if (!Validator.isEmail(email)) {
            throw new ServiceException("邮箱格式不正确");
        }
        Long userId = LoginHelper.getUserId();
        // 租户段锚定账号归属租户（深挖审核 Q8⑤）：原取 TenantHelper.getTenantId()（含超管
        // "切换租户"态），超管切到别家单位发码后切回，key 宇宙错位读不到码白报"验证码错误"；
        // 锚定归属租户后切换态不再影响发码/绑定的 key 一致性
        String tenantId = LoginHelper.getLoginUser().getTenantId();
        String rateKey = RATE_KEY + tenantId + ":" + userId;
        // 原子占坑（glm53 P1-2）：get-then-set 并发可绕——@RepeatSubmit 指纹含 email 参数，
        // 携 N 个不同邮箱的并发请求互不拦限频；setIfAbsent 抢坑失败即拒。
        // 占坑值用 nonce（深挖审核 Q2）：回滚时 CAS 只回收自己那一份，不误删并发后来者的坑。
        // ⚠️ 占坑位置在"同邮箱/唯一性"检查【之前】（深挖审核 Q9，k3 P2-3）：原顺序下"已被其他
        // 账号使用"分支不消耗坑位，登录态用户可无限速枚举租户内已注册邮箱（钓鱼名单探测仪）；
        // 前移后探测也被 60 秒/次限速——正常用户的代价仅是"输了已占用邮箱后等 60 秒再试"
        String rateNonce = java.util.UUID.randomUUID().toString();
        if (!RedisUtils.setObjectIfAbsent(rateKey, rateNonce, RATE_TTL)) {
            throw new ServiceException("验证码发送太频繁，请 1 分钟后再试");
        }
        SysUserVo user = userService.selectUserById(userId);
        if (user == null) {
            throw new ServiceException("用户信息不存在，请重新登录后再试");
        }
        if (email.equalsIgnoreCase(user.getEmail())) {
            throw new ServiceException("该邮箱已是当前绑定邮箱");
        }
        SysUserBo uniqueBo = new SysUserBo();
        uniqueBo.setUserId(userId);
        uniqueBo.setEmail(email);
        if (!userService.checkEmailUnique(uniqueBo)) {
            throw new ServiceException("该邮箱已被其他账号使用");
        }
        // 码值锚定目标邮箱（glm53 P1-1）：形如 email|code。bind 时邮箱+码双重比对——
        // 否则攻击者给自己邮箱发码、拿同一码绑他人邮箱，受害者邮箱被占后永远绑不上
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String codeValue = email.toLowerCase() + "|" + code;
        RedisUtils.setCacheObject(CODE_KEY + tenantId + ":" + userId, codeValue, CODE_TTL);
        try {
            MailUtils.sendText(email, "DCS 系统邮箱绑定验证码",
                "您正在为 DCS 系统账号「" + LoginHelper.getUsername() + "」绑定登录邮箱，验证码：" + code + "（5 分钟内有效）。\n"
                    + "若非本人操作请忽略本邮件，您的账号信息不会被修改。");
        } catch (Exception e) {
            // 发信失败不留验证码（防占坑：码在但用户收不到，占满 5 分钟窗口）；
            // CAS 回滚只删自己写入的那份值（码值含随机 code，天然 nonce 化）
            RedisUtils.compareAndDelete(CODE_KEY + tenantId + ":" + userId, codeValue);
            RedisUtils.compareAndDelete(rateKey, rateNonce);
            throw new ServiceException("验证码邮件发送失败，请稍后重试或联系管理员");
        }
        recordLogininfor(tenantId, LoginHelper.getUsername(), Constants.SUCCESS, "个人信息-邮箱绑定验证码已发送");
        return maskEmail(email);
    }

    /**
     * 校验验证码并绑定/更换邮箱（成功后验证码立即失效防重放）
     *
     * @return 打码后的新邮箱
     */
    public String bind(String email, String code) {
        if (!Validator.isEmail(email)) {
            throw new ServiceException("邮箱格式不正确");
        }
        if (StringUtils.isBlank(code)) {
            throw new ServiceException("请输入邮箱验证码");
        }
        // 大小写归一（深挖审核 Q5，2026-09-27 拍板）：邮箱地址本无大小写语义，原样落库会让
        // A@x.com 与 a@x.com 各占一坑——两账号绑"同一邮箱"，忘记密码投递归属混乱。
        // 落库/比对统一小写，配合 lower(email) 唯一索引（migration_2026_09_27）彻底堵变体占坑
        final String bindEmail = email.trim().toLowerCase();
        Long userId = LoginHelper.getUserId();
        // 租户段锚定账号归属租户（Q8⑤）：与 sendCode 同源，保证发码/绑定 key 恒同宇宙
        String tenantId = LoginHelper.getLoginUser().getTenantId();
        String codeKey = CODE_KEY + tenantId + ":" + userId;
        // 原子消费（深挖审核 Q2，dspro P1-2）：原"GET→双重比对→DEL"三段式可并发双花。
        // CAS 期望值 = 小写邮箱+"|"+码——与写入形态（email.toLowerCase()+"|"+code）逐字符一致；
        // email 入口已统一 toLowerCase（Q5），此处不再重复降 Case
        long cas = RedisUtils.compareAndDelete(codeKey, bindEmail + "|" + code.trim());
        if (cas == 0) {
            throw new ServiceException("验证码错误或已过期");
        }
        if (cas == 2) {
            // 码还活着（值不匹配）：错 5 次即作废，对齐批4 忘记密码防爆破口径；cas=2 时码未删，容错继续累计
            String failKey = FAIL_KEY + tenantId + ":" + userId;
            long fails = RedisUtils.incrAtomicValue(failKey);
            RedisUtils.expire(failKey, CODE_TTL);
            if (fails >= MAX_CODE_FAILS) {
                RedisUtils.deleteObject(codeKey);
                RedisUtils.deleteObject(failKey);
                throw new ServiceException("验证码错误次数过多，请重新获取验证码");
            }
            throw new ServiceException("验证码错误或已过期");
        }
        // cas==1：消费成功，清容错计数
        RedisUtils.deleteObject(FAIL_KEY + tenantId + ":" + userId);
        // 读当前用户（换绑通知旧邮箱 + 0 行更新兜底都需要）
        SysUserVo user = userService.selectUserById(userId);
        if (user == null) {
            throw new ServiceException("绑定失败，账号状态异常，请重新登录后再试");
        }
        // 唯一性复验（发码与提交之间可能被他人占用，TOCTOU 兜底）
        SysUserBo bindBo = new SysUserBo();
        bindBo.setUserId(userId);
        bindBo.setEmail(bindEmail);
        if (!userService.checkEmailUnique(bindBo)) {
            throw new ServiceException("该邮箱已被其他账号使用");
        }
        // 定向更新（updateUserEmail 只动 email 一列）：updateUserProfile 的 wrapper 对
        // phonenumber/sex 无条件 set，走它会把无关字段连带清库（E2E 实证）
        boolean updated = DataPermissionHelper.ignore(() -> userService.updateUserEmail(userId, bindEmail));
        if (!updated) {
            // 0 行更新=提交瞬间用户被软删（@TableLogic 过滤），不装成功（glm53 P2-4）
            throw new ServiceException("绑定失败，账号状态异常，请重新登录后再试");
        }
        // 换绑通知旧邮箱（glm53 P2-2）：旧邮箱非空时尽力发一封"已被更换"——公共电脑被盗
        // 登录态的静默换绑是免旧密码持久接管的铺垫，旧邮箱主人需要知情。发信失败不阻断主流程
        String oldEmail = user.getEmail();
        if (StringUtils.isNotBlank(oldEmail) && !oldEmail.equalsIgnoreCase(bindEmail)) {
            try {
                MailUtils.sendText(oldEmail, "DCS 系统绑定邮箱已更换通知",
                    "您的 DCS 系统账号「" + LoginHelper.getUsername() + "」绑定邮箱已由 " + maskEmail(oldEmail)
                        + " 更换为 " + maskEmail(bindEmail) + "。若非本人操作，请立即联系管理员处理。");
            } catch (Exception mailEx) {
                log.warn("换绑旧邮箱通知发送失败（不阻断绑定）: {}", mailEx.getMessage());
            }
        }
        recordLogininfor(tenantId, LoginHelper.getUsername(), Constants.SUCCESS, "个人信息-绑定邮箱成功");
        return maskEmail(bindEmail);
    }

    /** 邮箱打码：a***@qq.com（对齐批4 SysPwdResetService 同款口径） */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        if (at <= 2) {
            return "***" + email.substring(at);
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private void recordLogininfor(String tenantId, String username, String status, String message) {
        LogininforEvent event = new LogininforEvent();
        event.setTenantId(tenantId);
        event.setUsername(username);
        event.setStatus(status);
        event.setMessage(message);
        event.setRequest(ServletUtils.getRequest());
        SpringUtils.context().publishEvent(event);
    }
}
