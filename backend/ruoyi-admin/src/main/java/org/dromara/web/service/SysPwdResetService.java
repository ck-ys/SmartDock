package org.dromara.web.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.CacheConstants;
import org.dromara.common.core.constant.Constants;
import org.dromara.common.core.constant.GlobalConstants;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.log.event.LogininforEvent;
import org.dromara.common.mail.utils.MailUtils;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.system.domain.SysTenant;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.vo.SysTenantVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysTenantMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.ISysUserService;
import org.dromara.system.service.SysTenantLoginChecker;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

/**
 * 忘记密码·邮箱验证码重置（批4 2026-09-26 拍板 8A）
 *
 * <p>流程：账号识别（复用批2 listByLoginId，前端完成）→ 向该账号<b>绑定邮箱</b>发
 * 6 位数字验证码（Redis key 独立前缀 5 分钟过期，与登录图形验证码隔离防挪用；
 * 同账号 60 秒限发一次）→ 输码+新密码提交 → resetUserPwd（BCrypt+调用方负责）。</p>
 *
 * <p>SMTP 凭证不入仓库：mail.* 真值经 --spring.config.additional-location
 * 挂载（scripts/start-backend.ps1 探测到 .codex/mail-secret-qq.yml 才追加），
 * 仓库 yml 永远 enabled=false 占位；未配置态发码直接报"邮箱服务未配置"。</p>
 *
 * @author smartdock
 */
@RequiredArgsConstructor
@Service
public class SysPwdResetService {

    /** 验证码 Redis key：global:captcha_codes:PWD_RESET:{tenantId}:{loginId}（计划钦定前缀） */
    private static final String CODE_KEY = GlobalConstants.CAPTCHA_CODE_KEY + "PWD_RESET:";

    /** 发码限频 key：同账号 60 秒一次（独立于验证码 key，不随验码消耗） */
    private static final String RATE_KEY = GlobalConstants.CAPTCHA_CODE_KEY + "PWD_RESET:RATE:";

    /** 验证码错误计数 key：同一码错 5 次即作废（P1-1 防爆破，glm53 终审） */
    private static final String FAIL_KEY = GlobalConstants.CAPTCHA_CODE_KEY + "PWD_RESET:FAIL:";

    private static final int MAX_CODE_FAILS = 5;

    /**
     * 中性拒绝文案（grill Q3-B 拍板）：账号不存在/未绑定邮箱/已停用/单位不可登录四态合一，
     * 探测者无法凭报错区分账号存在性与绑定状态；正常未绑定用户仍有行动指引。
     */
    private static final String NEUTRAL_MSG = "该账号不存在或未绑定邮箱。若是本人账号，请登录后在「个人信息」中绑定邮箱后重试";

    private final SysTenantMapper tenantMapper;

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RATE_TTL = Duration.ofSeconds(60);

    /** 与注册页前端同款密码规则（register.vue） */
    private static final String PASSWORD_PATTERN = "^[^<>\"'|\\\\]{5,20}$";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysUserMapper userMapper;
    private final ISysUserService userService;

    /**
     * 发送验证码到账号绑定邮箱
     *
     * @return 打码后的邮箱（如 a***@qq.com），前端 toast 提示用
     */
    public String sendCode(String tenantId, String loginId) {
        // SMTP 配置自检（未挂载 secret 时 mail.enabled=false 占位）——必须在任何账号态判断【之前】
        // （glm53 P1-3）：与输入无关的配置性检查放最后会让"账号存在且已绑邮箱"走非中性报错、
        // 不存在账号走中性文案，未配 SMTP 的部署形态下两句可直接区分账号存在性
        if (!"true".equalsIgnoreCase(SpringUtils.getProperty("mail.enabled"))) {
            throw new ServiceException("邮箱服务未配置，请联系管理员");
        }
        // 单位可登录闸：消灭"过期单位用户能重置密码但不能登录"的怪态，
        // 拦截走中性文案（单位停用/过期本身不单独暴露）
        SysTenantVo tenant = TenantHelper.ignore(() -> tenantMapper.selectVoOne(
            new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getTenantId, tenantId)));
        if (!SysTenantLoginChecker.isLoginable(tenant, new java.util.Date())) {
            throwNeutral(tenantId, loginId);
        }
        SysUserVo user = locateUser(tenantId, loginId);
        if (StringUtils.isBlank(user.getEmail())) {
            // 中性化（grill Q3-B）：原"该账号未绑定邮箱"可被探测者用来区分账号存在性
            throwNeutral(tenantId, loginId);
        }
        String rateKey = RATE_KEY + tenantId + ":" + loginId;
        // 原子占坑（glm53 批B P1-2 连坐）：get-then-set 并发可绕限频，setIfAbsent 抢坑失败即拒。
        // 占坑值用 nonce（深挖审核 Q2）：发信失败回滚时 compareAndDelete 只回收"自己那一份"——
        // 值为常量时 SMTP 慢（>60s）场景下会误删并发窗口里后来者刚占的新坑（dspro P2-5）
        String rateNonce = java.util.UUID.randomUUID().toString();
        if (!RedisUtils.setObjectIfAbsent(rateKey, rateNonce, RATE_TTL)) {
            throw new ServiceException("验证码发送太频繁，请 1 分钟后再试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        RedisUtils.setCacheObject(CODE_KEY + tenantId + ":" + loginId, code, CODE_TTL);
        try {
            MailUtils.sendText(user.getEmail(), "DCS 系统密码重置验证码",
                "您正在重置 DCS 系统登录密码，验证码：" + code + "（5 分钟内有效）。\n"
                    + "若非本人操作请忽略本邮件，您的密码不会被修改。");
        } catch (Exception e) {
            // 发信失败不留验证码（防占坑：码在但用户收不到，占满 5 分钟窗口）；
            // CAS 回滚只删自己写入的那份值，误删不了并发后来者的码/坑（深挖审核 Q2）
            RedisUtils.compareAndDelete(CODE_KEY + tenantId + ":" + loginId, code);
            RedisUtils.compareAndDelete(rateKey, rateNonce);
            throw new ServiceException("验证码邮件发送失败，请稍后重试或联系管理员");
        }
        recordLogininfor(tenantId, loginId, Constants.SUCCESS, "忘记密码-验证码已发送至绑定邮箱");
        return maskEmail(user.getEmail());
    }

    /**
     * 校验验证码并重置密码（成功后验证码立即失效防重放）
     */
    public void resetPassword(String tenantId, String loginId, String code, String password) {
        if (StringUtils.isBlank(code)) {
            throw new ServiceException("请输入邮箱验证码");
        }
        if (StringUtils.isBlank(password) || !password.matches(PASSWORD_PATTERN)) {
            throw new ServiceException("新密码须为 5-20 位，且不能包含 < > \" ' \\ | 字符");
        }
        String codeKey = CODE_KEY + tenantId + ":" + loginId;
        // 原子消费（深挖审核 Q2，dspro P1-2）：原"GET→equals→DEL"三段式，并发双持同码可双消费，
        // 两次提交设两个密码末次写赢、留两条成功审计——compareAndDelete 一锤子"值相等才删"
        long cas = RedisUtils.compareAndDelete(codeKey, code.trim());
        if (cas == 0) {
            throw new ServiceException("验证码错误或已过期");
        }
        if (cas == 2) {
            // P1-1(glm53 终审): 验证码错 5 次即作废，压缩 6 位码爆破空间；计数与码同生命周期。
            // cas=2 时码未被删（还活着），容错计数继续累计
            String failKey = FAIL_KEY + tenantId + ":" + loginId;
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
        RedisUtils.deleteObject(FAIL_KEY + tenantId + ":" + loginId);
        SysUserVo user = locateUser(tenantId, loginId);
        // resetUserPwd 裸 set，调用方负责 BCrypt（SysUserController.resetPwd 同款）。
        // 匿名上下文两件套（E2E C4 实证缺一即炸）：
        // ① dynamic——UPDATE 需要租户条件，无登录态时靠 ThreadLocal 提供租户；
        // ② ignore——数据权限拦截器 beforePrepare 会拦 UPDATE 拼 getSqlSegment，
        //    内部 LoginHelper.getLoginUser()→StpUtil.getTokenSession() 匿名抛
        //    NotLoginException("token 已被冻结")，被包成"认证失败，无法访问系统资源"
        TenantHelper.dynamic(tenantId, () ->
            DataPermissionHelper.ignore(() ->
                userService.resetUserPwd(user.getUserId(), BCrypt.hashpw(password))));
        // P1-2(glm53 终审): 重置成功踢掉该账号全部旧会话——改密码是用户失窃后的自救动作，
        // 旧 token 不踢则攻击者凭旧 token 最长仍有效 30 天（sa-token 默认 timeout）。
        // ⚠️ loginId 是复合键 userType:userId（LoginUser.getLoginId 同构），裸 userId 踢不中
        StpUtil.kickout(user.getUserType() + ":" + user.getUserId());
        // P2-4+深审C-1: 完成邮箱验证=证明本人，清登录错误锁定计数；锁定 key 已带租户前缀(6.2)，
        // 且登录侧按命中 user_name 记账——双清 user_name 与输入 loginId(可能是手机号)两把钥匙，杜绝清不中。
        // ⚠️ 多租户 Redis NameMapper(TenantKeyPrefixHandler)：checkLogin 在登录流程(租户上下文活跃)写 key，
        // 实际落库形态是 {tenantId}:pwd_err_cnt:...；本方法匿名上下文裸 key 永远删不中真实锁——
        // 必须 TenantHelper.dynamic 包住让 NameMapper 按目标租户补前缀（glm53 P1-1 E2E 假 PASS 揭穿）。
        // 深挖审核 Q7：登录计数已换 V2 段（INCR 原子化），V1/旧格式段一并双清做过渡
        TenantHelper.dynamic(tenantId, () -> {
            RedisUtils.deleteObject(CacheConstants.PWD_ERR_CNT_V2_KEY + tenantId + ":" + user.getUserName());
            RedisUtils.deleteObject(CacheConstants.PWD_ERR_CNT_V2_KEY + tenantId + ":" + loginId);
            RedisUtils.deleteObject(CacheConstants.PWD_ERR_CNT_KEY + tenantId + ":" + user.getUserName());
            RedisUtils.deleteObject(CacheConstants.PWD_ERR_CNT_KEY + tenantId + ":" + loginId);
        });
        recordLogininfor(tenantId, loginId, Constants.SUCCESS, "忘记密码-邮箱验证码重置成功");
    }

    /**
     * 单租户内按 user_name/手机号 定位唯一用户（与登录识别同查询面）；
     * 不存在/多命中（数据异常）都不放行重置
     */
    private SysUserVo locateUser(String tenantId, String loginId) {
        List<SysUserVo> users = TenantHelper.dynamic(tenantId, () ->
            userMapper.selectVoList(new LambdaQueryWrapper<SysUser>()
                .and(w -> w.eq(SysUser::getUserName, loginId).or().eq(SysUser::getPhonenumber, loginId))));
        // 三态中性化（grill Q3-B）：不存在/多命中/停用不可区分，探测者拿不到账号状态
        if (users == null || users.isEmpty()) {
            throwNeutral(tenantId, loginId);
        }
        if (users.size() > 1) {
            throwNeutral(tenantId, loginId);
        }
        SysUserVo user = users.get(0);
        if (SystemConstants.DISABLE.equals(user.getStatus())) {
            throwNeutral(tenantId, loginId);
        }
        return user;
    }

    /**
     * 中性拒绝统一出口（grill Q3-B + 6.3）：留痕一条 LOGIN_FAIL 供事后审计探测行为
     * （仅此一类失败记日志——限发/SMTP 未配置不记，防接口反过来被刷成日志轰炸面），再抛中性文案
     */
    private void throwNeutral(String tenantId, String loginId) {
        recordLogininfor(tenantId, loginId, Constants.LOGIN_FAIL, "忘记密码-账号不存在或未绑定邮箱");
        throw new ServiceException(NEUTRAL_MSG);
    }

    /** 邮箱打码：a***@qq.com（提示已发送目的地，不泄露全文）；本地名≤2位时整段打码防全泄露（P2-5） */
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
