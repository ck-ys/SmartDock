package com.smartdock.assistant.domain.model;

import java.util.List;

/**
 * 长期记忆注入摘要的值对象（批 6 题 2 案 1，2026-11-20 从记忆服务内部 record 外移）。
 *
 * <p>为什么外移：摘要的产出方（新根存储服务）与消费方（状态面板、注入装饰器）不再同包，
 * 值对象必须能被两侧共同引用；留在某个服务内部会让另一方只能反向依赖服务实现。</p>
 *
 * @param text  拼好的摘要正文（「内容」以「」包裹、以；分隔）
 * @param count 实际收录条数
 * @param chars 正文字数（与预算口径一致）
 * @param ids   命中行 id（注入路径据此回写 last_used_at）
 */
public record DigestResult(String text, int count, int chars, List<Long> ids) {
}
