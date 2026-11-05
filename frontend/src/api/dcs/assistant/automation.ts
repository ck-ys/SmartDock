/**
 * AI 助手自动化 REST 封装（2026-11-05 自动化批·题7 乙）。
 * 全部走 utils/request.ts（鉴权/错误 toast 统一）；请求返回 R<T>，调用方取 res.data。
 */
import request from '@/utils/request';

export interface AutomationRow {
  id: number | string;
  title: string;
  prompt: string;
  scheduleType: 'DAILY' | 'WEEKLY' | 'BUILTIN';
  scheduleTime: string | null;
  weekdays: string | null;
  /** 人话日程：每天 09:00 / 每周一、三 09:00 */
  scheduleText: string;
  /** '1'=开 '0'=停 */
  enabled: string;
  /** '1'=系统内置（只读卡片） */
  builtin: string;
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastStatus: string | null;
  consecutiveFailures: number;
  runCount: number;
  successCount: number;
  canEdit: boolean;
  canToggle: boolean;
}

export interface AutomationRunRow {
  id: number | string;
  automationId: number | string;
  automationTitle: string | null;
  /** RUNNING / SUCCESS / FAILED / MISSED */
  status: string;
  /** SCHEDULE / MANUAL */
  triggerType: string;
  scheduledFor: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  conversationId: number | string | null;
  /** 结果会话 threadId（/agent?thread= 深链） */
  threadId: string | null;
  summary: string | null;
  error: string | null;
}

export interface AutomationPayload {
  title: string;
  prompt: string;
  scheduleType: 'DAILY' | 'WEEKLY';
  scheduleTime: string;
  weekdays?: string;
}

const BASE = '/dcs/assistant/automations';

export function listAutomations(filter?: string) {
  return request({ url: BASE, method: 'get', params: filter && filter !== 'ALL' ? { filter } : {} });
}

export function getAutomation(id: number | string) {
  return request({ url: `${BASE}/${id}`, method: 'get' });
}

export function previewSchedule(params: { scheduleType: string; scheduleTime: string; weekdays?: string }) {
  return request({ url: `${BASE}/preview`, method: 'get', params });
}

export function createAutomation(data: AutomationPayload) {
  return request({ url: BASE, method: 'post', data });
}

export function updateAutomation(id: number | string, data: AutomationPayload) {
  return request({ url: `${BASE}/${id}`, method: 'post', data });
}

export function toggleAutomation(id: number | string, enabled: boolean) {
  return request({ url: `${BASE}/${id}/toggle`, method: 'post', data: { enabled } });
}

export function deleteAutomation(id: number | string) {
  return request({ url: `${BASE}/${id}/delete`, method: 'post' });
}

export function runAutomation(id: number | string) {
  return request({ url: `${BASE}/${id}/run`, method: 'post' });
}

export function listAutomationRuns(id: number | string, limit = 10) {
  return request({ url: `${BASE}/${id}/runs`, method: 'get', params: { limit } });
}

export function listRecentAutomationRuns(limit = 5) {
  return request({ url: `${BASE}/runs/recent`, method: 'get', params: { limit } });
}

export function toggleBuiltinAutomation(enabled: boolean) {
  return request({ url: `${BASE}/builtin/toggle`, method: 'post', data: { enabled } });
}
