-- 单源重构 M1 E 片三席回修(2026-10-28): 接入协议清单补两个自监控事件。
-- 背景(dspro P2): user.login 与 data.audited 是采用度指标的取数口径, 但协议文档(v1.0.0)的
-- events 清单没有它们——管理员在「协议与 Schema」页看不到, 配维度/规则时无从下手。
-- 协议表每源只允许一个 PUBLISHED 版本(uk_profile_protocol_published 部分唯一):
-- 先把 v1.0.0 转SUPERSEDED 再发布 v1.1.0(保留旧版本可追溯)。幂等可重跑:
-- 二次执行时 UPDATE 命中 0 行、INSERT 撞 (tenant,source,version) 唯一键跳过。

UPDATE public.profile_protocol
SET status = 'SUPERSEDED', update_time = now()
WHERE tenant_id = '000000' AND source = 'dcs' AND version = '1.0.0'
  AND status = 'PUBLISHED' AND del_flag = '0';

WITH manifest AS (
    SELECT $manifest$
    {
      "apiVersion": "profile.protocol/v1",
      "kind": "ProfileManifest",
      "metadata": {
        "namespace": "dcs",
        "version": "1.1.0",
        "title": "任务填报画像",
        "description": "内置演示业务源：登录、任务发布、提交、审核（通过/退回记提交人，完成审核记审核人）、任务关闭。"
      },
      "subjectTypes": [
        {"name": "user", "displayName": "用户", "idField": "userId"}
      ],
      "events": [
        {"name": "user.login", "displayName": "登录成功", "subjectTypes": ["user"]},
        {"name": "task.published", "displayName": "任务发布", "subjectTypes": ["user"]},
        {"name": "task.closed", "displayName": "任务关闭", "subjectTypes": ["user"]},
        {"name": "data.submitted", "displayName": "数据提交", "subjectTypes": ["user"]},
        {"name": "data.audit_passed", "displayName": "审核通过(记提交人)", "subjectTypes": ["user"]},
        {"name": "data.audit_rejected", "displayName": "审核退回(记提交人)", "subjectTypes": ["user"]},
        {"name": "data.audited", "displayName": "完成审核(记审核人)", "subjectTypes": ["user"]}
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
SELECT 9609, '000000', 'dcs', '1.1.0', 'PUBLISHED', doc,
       encode(sha256(convert_to(doc::text, 'UTF8')), 'hex'),
       '2026-10-28 00:00:00', 1, 103, 1, '2026-10-28 00:00:00'
FROM manifest
ON CONFLICT (tenant_id, source, version) DO NOTHING;
