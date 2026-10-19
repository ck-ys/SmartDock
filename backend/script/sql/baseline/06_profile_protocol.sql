-- 画像接入协议与事件 Schema 种子（03_identity 之后执行）
-- 目的：给内置 DCS 业务源一个可查看、可导出、可版本化的接入契约示例。
-- 修复/重跑安全：ON CONFLICT 不覆盖已发布版本。

-- 1. DCS 业务源接入协议 v1.0.0
WITH manifest AS (
    SELECT $manifest$
    {
      "apiVersion": "profile.protocol/v1",
      "kind": "ProfileManifest",
      "metadata": {
        "namespace": "dcs",
        "version": "1.0.0",
        "title": "任务填报画像",
        "description": "内置演示业务源：任务发布、提交、审核通过/退回四类行为事件。"
      },
      "subjectTypes": [
        {"name": "user", "displayName": "用户", "idField": "userId"}
      ],
      "events": [
        {"name": "task.published", "displayName": "任务发布", "subjectTypes": ["user"]},
        {"name": "task.closed", "displayName": "任务关闭", "subjectTypes": ["user"]},
        {"name": "data.submitted", "displayName": "数据提交", "subjectTypes": ["user"]},
        {"name": "data.audit_passed", "displayName": "审核通过", "subjectTypes": ["user"]},
        {"name": "data.audit_rejected", "displayName": "审核退回", "subjectTypes": ["user"]}
      ],
      "dimensions": [
        {"name": "completion", "displayName": "完成率", "operator": "RATE", "scoreMax": 100},
        {"name": "response", "displayName": "响应时效", "operator": "AVG", "scoreMax": 100},
        {"name": "quality", "displayName": "填报质量", "operator": "RATE", "scoreMax": 100},
        {"name": "negative", "displayName": "负面指标", "operator": "COUNT", "scoreMax": 100}
      ],
      "metrics": [
        {"name": "approval_rate", "displayName": "通过率", "operator": "RATE"},
        {"name": "avg_response_hours", "displayName": "平均响应小时", "operator": "AVG"}
      ],
      "rules": [
        {
          "name": "v1-builtin",
          "displayName": "内置权重",
          "weights": {"completion": 0.35, "response": 0.25, "quality": 0.25, "negative": 0.15}
        }
      ],
      "scoreModels": [],
      "segments": [],
      "identity": {
        "strong": ["user_id"],
        "weak": ["anonymous_id", "device_id", "email_sha256", "phone_sha256"]
      }
    }
    $manifest$::jsonb AS doc
)
INSERT INTO public.profile_protocol
    (protocol_id, tenant_id, source, version, status, manifest, checksum,
     published_at, published_by, create_dept, create_by, create_time)
SELECT 9600, '000000', 'dcs', '1.0.0', 'PUBLISHED', doc,
       encode(sha256(convert_to(doc::text, 'UTF8')), 'hex'),
       '2026-10-19 00:00:00', 1, 103, 1, '2026-10-19 00:00:00'
FROM manifest
ON CONFLICT DO NOTHING;

-- 2. 内置事件 Schema（可选强约束；未注册事件仍按宽松模式放行）
INSERT INTO public.profile_schema_registry
    (schema_id, tenant_id, source, event_name, schema_version, schema_json, status, create_by, create_time)
VALUES
    (9601, '000000', 'dcs', 'task.published', '1-0-0', $schema$
    {
      "type": "object",
      "required": ["taskId"],
      "properties": {
        "taskId": {"type": "integer"},
        "title": {"type": "string"},
        "deadline": {"type": "string"},
        "assigneeCount": {"type": "integer"}
      }
    }
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00'),
    (9602, '000000', 'dcs', 'task.closed', '1-0-0', $schema$
    {
      "type": "object",
      "required": ["taskId"],
      "properties": {"taskId": {"type": "integer"}}
    }
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00'),
    (9603, '000000', 'dcs', 'data.submitted', '1-0-0', $schema$
    {
      "type": "object",
      "required": ["taskId", "submissionId", "version"],
      "properties": {
        "taskId": {"type": "integer"},
        "submissionId": {"type": "integer"},
        "version": {"type": "integer"},
        "responseHours": {"type": "number"}
      }
    }
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00'),
    (9604, '000000', 'dcs', 'data.audit_passed', '1-0-0', $schema$
    {
      "type": "object",
      "required": ["taskId", "submissionId", "version"],
      "properties": {
        "taskId": {"type": "integer"},
        "submissionId": {"type": "integer"},
        "version": {"type": "integer"}
      }
    }
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00'),
    (9605, '000000', 'dcs', 'data.audit_rejected', '1-0-0', $schema$
    {
      "type": "object",
      "required": ["taskId", "submissionId", "version"],
      "properties": {
        "taskId": {"type": "integer"},
        "submissionId": {"type": "integer"},
        "version": {"type": "integer"}
      }
    }
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00')
ON CONFLICT (tenant_id, source, event_name, schema_version) DO NOTHING;
