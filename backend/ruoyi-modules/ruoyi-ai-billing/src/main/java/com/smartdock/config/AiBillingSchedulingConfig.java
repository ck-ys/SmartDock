package com.smartdock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ai-billing 模块调度自持(模块化拆分 2026-09-24): WalletAlertScheduler 的 @Scheduled(每天 09:00 扫低余额)
 * 依赖 @EnableScheduling 开启。dcs 的 DcsScheduleConfig 也带此注解, 但模块不应隐性依赖宿主模块的开关
 * ——本模块自带一份(重复标注幂等无害), 保证 ruoyi-ai-billing 可独立运行。
 *
 * @author smartdock
 */
@Configuration
@EnableScheduling
public class AiBillingSchedulingConfig {
}
