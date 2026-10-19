/**
 * DCS 工作助手模块（ruoyi-assistant）— AgentScope Java 驱动的嵌入式 AI 助手。
 *
 * <p>P0 阶段（2026-10-03 起）：探针模块骨架，只含依赖与探针，不写业务功能。
 * 一站规划结构与职责见 document/DCS_ASSISTANT_SPEC_20260923.md §2（config/agent/tools/guard/billing/persist/hitl/job/web 分包）。
 *
 * <p>纪律：探针测试一律 @Tag("p0probe")（禁 dev 标签，防混入存量回归真实外呼）；
 * 模块依赖版本由根 pom 的 io.agentscope:agentscope-bom 统一管理。
 */
package org.dromara.assistant;
