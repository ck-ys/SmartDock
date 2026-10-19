package com.smartdock.profile.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 模块自有调度开关(glm53 批1 审查 P2-1 回修): @Scheduled 清理任务不隐性依赖
 * 宿主模块点亮 @EnableScheduling——模块独立部署时定时清理照常工作。
 * Spring 对重复 @EnableScheduling 幂等, mono-admin 部署无冲突
 * (仓库先例 AiBillingSchedulingConfig 同款)。
 *
 * @author smartdock
 */
@Configuration
@EnableScheduling
public class ProfileSchedulingConfig {
}
