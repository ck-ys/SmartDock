package com.smartdock.assistant.domain.model;

/**
 * 一条任务清单项（ZCode TodoItem 同构）。
 *
 * <p>字段与 JSON 形态与原先的内部类 {@code AssistantTodoSignal.TodoItem} 完全一致
 * （{@code content} / {@code status} / {@code priority}，序列化键名不变），批 7C-2 只是把
 * record 从 legacy 的内部类提升为可共享的领域类型——legacy 侧改为 import 本类（legacy 可以
 * 依赖新根，反向禁止，见 PackageHygieneTest 规则 8）。</p>
 *
 * <p>status=pending/in_progress/completed，priority=high/medium/low。</p>
 */
public record AssistantTodoItem(String content, String status, String priority) {
}
