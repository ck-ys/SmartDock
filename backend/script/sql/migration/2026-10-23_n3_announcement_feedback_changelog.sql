-- 2026-10-23 N3：公告专业化 + 反馈中心 + 更新日志数据库驱动
-- 可重复执行：列 ADD IF NOT EXISTS / 表 IF NOT EXISTS / 菜单 ON CONFLICT DO UPDATE / 授权 DO NOTHING

-- ===== 1. 公告：置顶 / 发布时间 / 过期时间 =====
ALTER TABLE public.sys_notice ADD COLUMN IF NOT EXISTS is_top char(1) DEFAULT '0'::bpchar;
ALTER TABLE public.sys_notice ADD COLUMN IF NOT EXISTS publish_time timestamp(6) without time zone;
ALTER TABLE public.sys_notice ADD COLUMN IF NOT EXISTS expire_time timestamp(6) without time zone;

COMMENT ON COLUMN public.sys_notice.is_top IS '置顶 0=否 1=是';
COMMENT ON COLUMN public.sys_notice.publish_time IS '发布时间；为空视为立即发布';
COMMENT ON COLUMN public.sys_notice.expire_time IS '过期时间；为空=长期有效';

CREATE INDEX IF NOT EXISTS idx_sys_notice_portal
    ON public.sys_notice (tenant_id, status, is_top DESC, publish_time DESC);

-- ===== 2. 公告已读（按人一行） =====
CREATE TABLE IF NOT EXISTS public.sys_notice_read (
    notice_id bigint NOT NULL,
    user_id   bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    read_time timestamp(6) without time zone DEFAULT now(),
    CONSTRAINT pk_sys_notice_read PRIMARY KEY (notice_id, user_id)
);

COMMENT ON TABLE public.sys_notice_read IS '公告已读记录（公告+用户唯一）';

CREATE INDEX IF NOT EXISTS idx_sys_notice_read_user
    ON public.sys_notice_read (tenant_id, user_id);

-- ===== 3. 站内通知支持跳转链接 =====
ALTER TABLE public.dcs_notice ADD COLUMN IF NOT EXISTS ref_url character varying(500);
COMMENT ON COLUMN public.dcs_notice.ref_url IS '点击通知跳转的前端路由（可空）';

-- ===== 4. 反馈中心 =====
CREATE TABLE IF NOT EXISTS public.dcs_feedback (
    feedback_id   bigint NOT NULL,
    tenant_id     character varying(20) NOT NULL DEFAULT '000000'::character varying,
    title         character varying(200) NOT NULL,
    content       character varying(2000) NOT NULL,
    feedback_type character varying(20) NOT NULL,
    oss_ids       character varying(500),
    contact       character varying(100),
    context_json  text,
    status        character varying(16) NOT NULL DEFAULT 'PENDING'::character varying,
    handler_id    bigint,
    handler_name  character varying(64),
    handle_note   character varying(1000),
    handle_time   timestamp(6) without time zone,
    create_dept   bigint,
    create_by     bigint,
    create_time   timestamp(6) without time zone,
    update_by     bigint,
    update_time   timestamp(6) without time zone,
    del_flag      char(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_dcs_feedback PRIMARY KEY (feedback_id),
    CONSTRAINT ck_dcs_feedback_type CHECK (feedback_type::text = ANY (ARRAY['BUG','EXPERIENCE','SUGGEST','OTHER']::text[])),
    CONSTRAINT ck_dcs_feedback_status CHECK (status::text = ANY (ARRAY['PENDING','PROCESSING','RESOLVED','CLOSED']::text[]))
);

COMMENT ON TABLE public.dcs_feedback IS '产品反馈（单表；最近一次处理意见覆盖式存储）';
COMMENT ON COLUMN public.dcs_feedback.feedback_type IS 'BUG=缺陷 EXPERIENCE=体验 SUGGEST=建议 OTHER=其它';
COMMENT ON COLUMN public.dcs_feedback.context_json IS '提交时自动采集的页面/浏览器/版本上下文（JSON）';

CREATE INDEX IF NOT EXISTS idx_dcs_feedback_status ON public.dcs_feedback (status);
CREATE INDEX IF NOT EXISTS idx_dcs_feedback_create_by ON public.dcs_feedback (create_by);
CREATE INDEX IF NOT EXISTS idx_dcs_feedback_tenant_status
    ON public.dcs_feedback (tenant_id, status, create_time DESC);

-- ===== 5. 更新日志（全局表，已在 tenant.excludes） =====
CREATE TABLE IF NOT EXISTS public.dcs_changelog (
    log_id         bigint NOT NULL,
    version        character varying(32) NOT NULL,
    title          character varying(200) NOT NULL,
    items          text NOT NULL DEFAULT '[]'::text,
    target_tenants text NOT NULL DEFAULT '[]'::text,
    status         char(1) NOT NULL DEFAULT '0'::bpchar,
    publish_time   timestamp(6) without time zone,
    create_dept    bigint,
    create_by      bigint,
    create_time    timestamp(6) without time zone,
    update_by      bigint,
    update_time    timestamp(6) without time zone,
    del_flag       char(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_dcs_changelog PRIMARY KEY (log_id),
    CONSTRAINT ck_dcs_changelog_status CHECK (status::text = ANY (ARRAY['0','1']::text[]))
);

COMMENT ON TABLE public.dcs_changelog IS '更新日志（全局；visible 由 target_tenants 名单控制）';
COMMENT ON COLUMN public.dcs_changelog.items IS '条目 JSON：[{"type":"new|optimize|fix","text":"..."}]';
COMMENT ON COLUMN public.dcs_changelog.target_tenants IS '推送租户名单 JSON：["000000",...]';
COMMENT ON COLUMN public.dcs_changelog.status IS '0=草稿 1=已发布';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_changelog_version
    ON public.dcs_changelog (version) WHERE (del_flag = '0'::bpchar);
CREATE INDEX IF NOT EXISTS idx_dcs_changelog_status_publish
    ON public.dcs_changelog (status, publish_time DESC);

-- ===== 6. 菜单与权限 =====
-- 6.1 公告（系统支持目录下，全体登录角色可见）
INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9150, '公告', 9180, 0, 'announcement', 'dcs/announcement/index', '',
     '1', '0', 'C', '0', '0', 'dcs:announcement:list', 'message',
     103, 1, now(), NULL, NULL, '公告卡片流与文章页（全体登录角色）')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id, path = EXCLUDED.path, component = EXCLUDED.component,
    menu_type = EXCLUDED.menu_type, visible = EXCLUDED.visible, status = EXCLUDED.status,
    perms = EXCLUDED.perms, remark = EXCLUDED.remark, update_time = now();

-- 6.2 更新日志：补列表权限 + 四个按钮
UPDATE public.sys_menu SET perms = 'dcs:changelog:list', remark = '系统版本迭代记录（数据库驱动）', update_time = now()
 WHERE menu_id = 9160;

INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9161, '日志查询', 9160, 1, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:changelog:query', '#', 103, 1, now(), NULL, NULL, ''),
    (9162, '版本新增', 9160, 2, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:changelog:add', '#', 103, 1, now(), NULL, NULL, ''),
    (9163, '版本编辑', 9160, 3, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:changelog:edit', '#', 103, 1, now(), NULL, NULL, ''),
    (9164, '版本删除', 9160, 4, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:changelog:remove', '#', 103, 1, now(), NULL, NULL, '')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id, perms = EXCLUDED.perms, visible = EXCLUDED.visible,
    status = EXCLUDED.status, update_time = now();

-- 6.3 反馈中心
INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9170, '反馈中心', 9180, 2, 'feedback', 'dcs/feedback/index', '',
     '1', '0', 'C', '0', '0', 'dcs:feedback:list', 'email',
     103, 1, now(), NULL, NULL, '产品反馈管理（租户管理员与超管）'),
    (9171, '反馈查询', 9170, 1, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:feedback:query', '#', 103, 1, now(), NULL, NULL, ''),
    (9172, '反馈处理', 9170, 2, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:feedback:handle', '#', 103, 1, now(), NULL, NULL, '')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id, path = EXCLUDED.path, component = EXCLUDED.component,
    menu_type = EXCLUDED.menu_type, visible = EXCLUDED.visible, status = EXCLUDED.status,
    perms = EXCLUDED.perms, remark = EXCLUDED.remark, update_time = now();

-- 6.4 授权
-- 公告 + 更新日志只读：所有有效角色
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM public.sys_role r
 CROSS JOIN (VALUES (9150), (9160), (9161)) AS m(menu_id)
 WHERE r.del_flag = '0' AND r.status = '0'
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 更新日志写面：仅超管（role_key='superadmin'）
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM public.sys_role r
 CROSS JOIN (VALUES (9162), (9163), (9164)) AS m(menu_id)
 WHERE r.del_flag = '0' AND r.status = '0' AND r.role_key = 'superadmin'
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 反馈中心：超管 + 租户管理员
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM public.sys_role r
 CROSS JOIN (VALUES (9170), (9171), (9172)) AS m(menu_id)
 WHERE r.del_flag = '0' AND r.status = '0' AND r.role_key IN ('superadmin', 'tenant_admin')
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ===== 7. 历史更新日志迁移（从原前端静态 changelog.ts 一次性导入） =====
-- 已发布、推送给当前全部有效单位；不触发通知（历史记录不打扰）。重复执行自动跳过。

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000000, 'v1.10.0', '钥匙与模型：三种接口格式、20 家厂商模板、价格来源看得见',
  '[{"type":"new","text":"新增模型时可以从 20 家厂商模板一键带出接口地址、格式和推荐模型名；接口格式支持 Chat Completions / Anthropic Messages / Responses 三种，表单下方直接显示最终会请求的地址，非对话格式会标明「仅供连通测试」。"},{"type":"new","text":"价格目录新增「从 models.dev 同步」和「JSON 导入」，每行标出价格来源（手工 / 同步 / 导入 / 已锁定），同步不会覆盖手工价；「价格来源」列移到模型旁边，窄屏也不用横向滚动才能看到。"},{"type":"new","text":"余额与额度查询扩到 7 家厂商（Kimi / MiniMax 的订阅额度按「剩余百分比」显示）；手动「测试」与每日巡检写进同一张表，测完徽标立刻刷新；默认模型不可用时会自动沿用上次成功使用的模型。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-23 12:00:00', '0', '2026-10-23 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000001, 'v1.9.7', '细节修缮：卡片样式与说明文案对齐内部口径',
  '[{"type":"fix","text":"修复「数据分析」页卡片样式：移除外部接入时误留的一条旧样式规则曾让所有卡片顶部多出一条虚线和多余间距，已清除。"},{"type":"fix","text":"首页「数据脉搏」的来源说明不再提已移除的「采集站点」；文档中的页签清单补齐到实际的十个页签，数据库升级说明改为按文件名顺序执行全部增量脚本。"},{"type":"fix","text":"演示数据脚本加固：指定天数很小（1~6 天）时不再卡死；登录失败会提示检查验证码设置；文档补上「同一天内可重复执行」的准确说明。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-23 12:00:00', '0', '2026-10-23 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000002, 'v1.9.6', '升级脚本可安全重复执行',
  '[{"type":"fix","text":"修复一个升级脚本的问题：在全新安装的库上按顺序重放全部升级脚本时，会在事件归档表这一步因主键已存在而中断；现在重复执行会自动跳过，不再报错。"},{"type":"new","text":"经真实数据库全链路实测：全新安装（基线 7 个脚本）和「全新安装 + 重放全部 21 个升级脚本」两条路径均可一次跑通。"},{"type":"fix","text":"同步清理外部接入残留：架构与画像引擎文档、数据库安装说明（升级步骤与实际脚本对齐）、开发代理配置、开发规范文档等十余处不再提及已移除的接入方式。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-23 12:00:00', '0', '2026-10-23 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000003, 'v1.9.5', '移除外部网站接入：系统专注内部数据源',
  '[{"type":"optimize","text":"「数据接入」页签改为「数据导入」：只留 CSV 离线补录和全域总览；建站、接入代码、检测、模拟访客这些外部网站相关功能全部下线，「数据分析」默认页签回到「流量概览」。"},{"type":"optimize","text":"公开采集接口、网页采集脚本（SDK）、站点钥匙整套外部通道移除——数据进系统只有两条路：内部业务系统走事件协议，离线数据走 CSV 导入；部署模板里的采集转发规则一并删除。"},{"type":"new","text":"演示数据播种脚本改为走管理员的 CSV 导入通道（事件与口径不变，仍可重复执行）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-23 12:00:00', '0', '2026-10-23 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000004, 'v1.9.4', '网站数据接入变简单：一页三块，5 分钟看到自己的数据',
  '[{"type":"optimize","text":"「数据分析」第一个页签改为「数据接入」：先看看（模拟几个访客即时验证）/ 接我的网站（起个名字就建站，复制整段代码贴进网页）/ 导入历史数据，三块搞定；站点钥匙等术语折进「高级」；已有站点刷新后直接亮出接入代码。"},{"type":"fix","text":"修复接入代码贴到自己网站后数据发不出去的问题（地址此前按相对路径生成，会发到用户自己域名）；「清掉模拟数据」现在连画像主体、访客首见一起清，不留幽灵记录。"},{"type":"new","text":"新增「检测接入」：贴完代码点一下，钥匙有效+测试事件收到两个绿灯亮起即链路打通；Docker 部署模板补上了采集转发规则（仅放行 collect 一个公开端点），README 增加 5 分钟接入指引。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000005, 'v1.9.3', '长对话自动打包前文，手动「压缩」按钮下线',
  '[{"type":"fix","text":"对话太长时系统会自动把前文打包成一段摘要腾出空间，不用再找按钮：输入框的「打包前文」按钮和 /压缩 命令已删除。"},{"type":"optimize","text":"会话状态环改成「水位表」：能看到已用多少、窗口多大、到哪条线会自动打包；离打包线越近环越橙越红。"},{"type":"optimize","text":"翻看超长历史会话现在显示最近的消息（此前只显示最早 100 条，看着像丢消息）；打包暂停时界面会留一句人话提示。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000006, 'v1.9.2', '「系统支持」不再白屏，更新日志能正常打开',
  '[{"type":"new","text":"「系统支持 → 更新日志」页面回来了：此前点开是空白页（迁移时只搬了外框、漏了里面的页面菜单），现在能正常看到所有版本更新记录。"},{"type":"fix","text":"直接访问目录地址（如 /support）会自动打开它下面的第一个页面，不再是空白页；其他一级目录同样受益。"},{"type":"new","text":"更新日志对所有登录用户可见——首页「全部日志」入口本来就人人可见，现在点进去也不会再被权限挡住。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000007, 'v1.9.1', '前端类型检查清零并接入构建硬闸，顺手修好三处小毛病',
  '[{"type":"optimize","text":"前端类型检查从 183 处报错清到 0，并且接进生产构建：以后有类型错误构建直接失败，不达标上不了线。"},{"type":"optimize","text":"暗色模式下侧栏 Logo 现在会正确切换暗色版（此前判断失效恒走亮色）；AI 助手首屏问候语优先显示昵称（此前该字段名写错一直显示备用名）。"},{"type":"fix","text":"手动点击「刷新会话」「保存草稿」按钮不再被误判为静默模式：加载动画和成功提示恢复正常。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000008, 'v1.9.0', '外部工具配钥匙、进开关矩阵，安全再上三道锁',
  '[{"type":"new","text":"外部 HTTP 工具可以配鉴权头了（比如 Authorization）：值加密存进数据库（拿到数据库也看不到原文），管理页只显示键名不显示值，操作日志也不记录。"},{"type":"new","text":"「工具开关矩阵」新增外部服务行（mcp: 开头）：可以把某个外部工具对本租户整体关闭或设为仅管理员可用，下一轮新会话生效。"},{"type":"optimize","text":"外部工具的调用记录保留 90 天自动清理，查询更快；写类外部工具会先弹确认卡再执行（与平台工具同一道安全闸）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000009, 'v1.8.9', '反问卡加固：不再替你选、不再卡死提交',
  '[{"type":"fix","text":"反问卡的「提交回答」按钮修好了必填没填时的判定（此前即使填完也按不下去）；选择题不再自动替你选第一项，必须自己选。"},{"type":"optimize","text":"反问等待改用服务器给的剩余时间倒计时，两台电脑时间不准也不会一打开就显示超时；对话结束或出错时，已失效的反问卡会自动收走。"},{"type":"new","text":"管理后台审计里新增了反问相关的中文说明（反问用户 / 已作答 / 被拒绝 / 被关闭 / 超时关闭）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000010, 'v1.8.8', '外部工具会反问了，答完接着跑',
  '[{"type":"new","text":"接了外部工具（MCP）的对话里，工具缺信息时会弹出一张「反问卡」直接问你（比如查天气缺城市时让你选城市）；提交回答后工具继续执行，不用重新问一遍。也可以拒绝回答或直接关闭。"},{"type":"optimize","text":"反问等待有倒计时（默认 5 分钟），超时自动关闭不会一直挂着；每次反问和作答都有审计记录。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000011, 'v1.8.7', '不挑模型也能知道在跟谁说话了',
  '[{"type":"new","text":"管理员没开「允许用户自选模型」时，助手输入框上会显示一个只读的小标签，标明当前生效的默认模型；想换模型找管理员即可（窄屏放不下时收成图标，悬停可看全名）。"},{"type":"new","text":"顺手修活了一个一直没生效的提醒：管理员设置的默认模型失效时，选择器会如实标出「默认模型不可用」（之前这个提醒从未亮过）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000012, 'v1.8.6', '停止按钮真的停了，危险操作必过确认',
  '[{"type":"fix","text":"对话里的「停止」现在会真正叫停后台生成（之前只是不再显示，后台还在跑），停下来的对话会明确标记为「用户已停止」。"},{"type":"fix","text":"修复一个隐藏问题：助手的写类操作（如建任务草稿）此前可能不经确认直接执行——现在一律先弹确认卡，点了取消的操作会留审计记录。"},{"type":"optimize","text":"管理员调整工具开关后，正在聊的对话下一句就按新配置生效，不用重开会话。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000013, 'v1.8.5', '外部工具试点加固（三审回修）',
  '[{"type":"fix","text":"修掉两处会导致外部工具挂不上/悄悄被关的问题：重名预检现在先握手再列工具；闲置回收认对了会话，不再误关正在用的连接。"},{"type":"fix","text":"只读的外部工具（如天气查询）现在声明只读身份，对话里直接用，不再每次弹确认框。"},{"type":"optimize","text":"管理页外部工具列表补上「编辑」入口，开关失败自动弹回原样，增删改后审计列表立刻刷新。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000014, 'v1.8.4', '助手能连外部工具了（MCP 试点）',
  '[{"type":"new","text":"AI 助手管理新增「外部工具」页签：管理员可以把只读的 MCP 服务挂进助手（对话里就能查天气这类外部数据），每次挂载和调用都有审计记录。"},{"type":"optimize","text":"安全闸全开：只允许免密钥只读服务，脚本/地址都要在白名单内才保存得了；单个服务挂了不影响正常对话。"},{"type":"optimize","text":"每次调用有次数上限（每分钟 30 次），超限会明确提示，防止外部服务被刷。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000015, 'v1.8.3', '助手的工具，管理员现在能自己开关了',
  '[{"type":"new","text":"AI 助手管理新增「工具开关」页签：每个工具可以调成全员可用 / 仅管理员 / 关闭，也可以勾选一批工具批量设置，不用再等发版。"},{"type":"optimize","text":"改动只对本单位生效；正在聊的对话下一句起就按新开关生效（此前的旧版说的是\"要重开会话\"，后来已改进为即时生效）。"},{"type":"optimize","text":"「能力全景」页同步多了一列「本租户生效档」：哪个工具被关闭/收紧，一眼能看到（关闭的标红）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000016, 'v1.8.2', '配钥匙不再问地址，长对话不再被误判',
  '[{"type":"fix","text":"钥匙管理选了厂商就不用再填接口地址：系统按厂商官方地址自动接入（页面也不再显示这个输入框）；要走中转地址才需要选「自定义」厂商手填，自定义留空会直接提示。"},{"type":"new","text":"修了一个误判：排队高峰时刚说完的回合可能被后台兜底线程抢先标成「已断开」并退回额度，现在真终态写入期间兜底线程会让行。"},{"type":"fix","text":"订阅（积分）租户的回合退款口径与扣费口径对齐：没扣过当日回合的订阅轮不再误退计数。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000017, 'v1.8.1', '引擎升级到 AgentScope 2.0.4，两处暗雷排掉',
  '[{"type":"optimize","text":"AI 引擎底座升级到 AgentScope 2.0.4（对话/思考/工具/心跳/确认卡全链路实测正常），为后续技能与外部工具扩展铺路。"},{"type":"fix","text":"修掉一个暗改道问题：模型接口地址留空的配置行会让对话悄悄发往 OpenAI 官方地址然后超时，现在直接提示「请补全接口地址」，不再干等。"},{"type":"optimize","text":"删除用户数据时框架会话表的清理补上了表名前缀，此前这步清理一直在静默失败重试。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000018, 'v1.8.0', '合规三件套：能打码、能删人、能追责',
  '[{"type":"new","text":"新增「合规中心」页：哪些字段算敏感（手机/邮箱/搜索词…）改成一张清单自己配，配好全站查询自动打码；没有对应权限的账号看不到分数列和逐事件轨迹。"},{"type":"optimize","text":"删得干净也删得放心：输入主体 ID 先预览会删掉多少事件/会话/快照，回显 ID 确认后一次性清干净（含匿名期与冷库归档），删完自动重算统计，每笔删除留审计行。"},{"type":"new","text":"分群导出 CSV 每次留痕（行数/是否明文/文件指纹），明文导出会站内提醒管理员；文件做了防公式注入处理，Excel 打开不会执行公式；采集上报支持站点级签名，伪造和重放会被整批拒收。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000019, 'v1.7.1', '说出需求自动带出技能，助手运行状况看得见',
  '[{"type":"fix","text":"在输入框说出「帮我建任务」「做个模板」这类话，会自动挂上对应技能（胶囊标「自动」），点 × 移除后不再自动加回来。"},{"type":"new","text":"管理页新增「运行健康」卡：回答是正常收尾还是中断、追问有没有生成、为什么没生成，一眼看清（回答太短/超预算不发属正常节流）。"},{"type":"optimize","text":"技能列表改为后台下发：以后加新技能、改触发词不用更新前端。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000020, 'v1.7.0', '数据算错能重跑了，引擎健康看得见了',
  '[{"type":"new","text":"新增「数据运维」页：改错口径或补了历史数据，可以按时间窗回放重算（会话/日报/模型分数可选），支持断点续跑，完成后给出前后对账数字。"},{"type":"new","text":"新增「引擎健康」页：事件延迟、重建耗时、任务失败率、队列积压四项指标一屏看清，越线自动给管理员发站内提醒。"},{"type":"new","text":"数据可以按业务源设保留天数（过期先进冷库再删）；身份合错了能拆开，误并的匿名事件归属会改回去并重算。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000021, 'v1.6.2', '回答更稳了：等再久也不会自动重发，追问等得更久、回来就能看',
  '[{"type":"fix","text":"等回答时后台每 20 秒报一次平安；真等不到时先弹窗问你「继续等还是停止」，不再自动重发（重发会白扣一次额度）。"},{"type":"optimize","text":"追问建议的等待窗口从 30 秒放宽到 90 秒（排队高峰也能等到）；切走再切回页面，建议立刻刷出来。"},{"type":"optimize","text":"追问问题放宽到 24 字以内，表达更完整；后台挑轻快模型时自动避开「思考关不掉」的型号，追问更少悄悄失败。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000022, 'v1.6.1', '助手名字可以自己起，MiniMax 换国内入口',
  '[{"type":"new","text":"管理员可以在「AI 助手管理」里给助手改名，欢迎语、使用说明弹窗会跟着变；清空就恢复默认「SmartDock 小助手」。"},{"type":"optimize","text":"MiniMax 预置换成国内站接口，默认模型升到 M3.1-Flash；选它时页面会提醒先去开放平台申请钥匙再填进来。"},{"type":"optimize","text":"聊天里的「缓存命中」加了说明：解释它为什么更快更省、几成算正常，以及什么时候会暂时变低。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000023, 'v1.6.0', '评分模型有了发布纪律，分群能发通知了',
  '[{"type":"fix","text":"改评分规则不再直接生效：先存草稿、试算看新旧分数对比（含稳定性提示）再发布，出问题可一键回滚到历史版本。"},{"type":"new","text":"分群可以给成员发站内信（发送名单当时冻结、每人只收一次、失败可重试），也能导出成员名单（默认打码）。"},{"type":"new","text":"低分复核支持按天设处理时限，超时的标红置顶；用户列表新增风险筛选和「依据」查看，风险阈值改由后端统一下发。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000024, 'v1.5.3', '登录页品牌与产品窗口再升级，左右两栏重新找平',
  '[{"type":"new","text":"左侧新增 SmartDock 标记；产品窗口补了顶部导航、侧栏图标、评分 +4、事件类型点和趋势 Y 轴，更像真实产品截图。"},{"type":"new","text":"产品窗口在品牌与页脚之间垂直居中，右侧登录卡新增分组线和渐变描边，1440/1366/1024/1920 观感一致。"},{"type":"fix","text":"手机端两个勾选行的点按高度从 20px 提到 32px；390×560 横屏不再多出滚动。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000025, 'v1.5.2', '登录页再细抠一轮：小字更清楚，助手浮层不压字',
  '[{"type":"optimize","text":"左侧产品窗口里的姓名、事件、图表标题放大了一档，顶部能力行和底部说明也提亮了。"},{"type":"fix","text":"「指定单位登录」、版权行和输入框提示文字的对比度提高，1366/1024 下卡片底部不再贴边。"},{"type":"fix","text":"1024 宽度下助手浮层向下让出最后一条事件，不再盖住事件文字。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000026, 'v1.5.1', '登录页换成画像工作台实景，右栏只留登录',
  '[{"type":"fix","text":"左侧改成品牌字标加画像工作台、AI 助手浮层示意，不再用抽象球体和轨道动画。"},{"type":"optimize","text":"右侧删掉口号和「画像 / 助手」两枚标签，只保留登录表单；验证码、记住我、协议勾选、指定单位登录一个不少。"},{"type":"optimize","text":"手机端顶部保留 150px 品牌条；修了「指定单位登录」展开后校验提示压住「记住我」的问题。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000027, 'v1.5.0', '分析中心新增路径、活跃、实时三张视图',
  '[{"type":"new","text":"新标签「路径分析」：看用户从哪页进来、下一步去哪页；「活跃分析」：日活/周活/月活和粘性趋势；「实时看板」：最近 5 分钟的流量脉冲，5 秒自动刷新、可一键暂停。"},{"type":"new","text":"漏斗页新增「流失去向」（流失的访客接下来去了哪）和渠道归因表（不同来源各带来多少转化）；采集页新增「全域总览」，网站、订单、供应链等业务源一屏对账。"},{"type":"fix","text":"修复三处老问题：共享浏览器下访客时间线串台、路径图因回访成环不显示、跨时区电脑上「最近写入」时间显示错误。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000028, 'v1.4.4', '首页几个数字的说法改准了',
  '[{"type":"optimize","text":"画像卡第三个数改为「低分主体」（此前误把低风险人数当成低分人数）。"},{"type":"fix","text":"首页「已提交待审」现在跳到我的待办，不再误跳审核队列；没有分析权限时不再显示「数据分析」按钮。"},{"type":"optimize","text":"手机上历史会话抽屉关闭后，键盘 Tab 不会再钻进去；按 Esc 或点「新会话」会自动收起。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000029, 'v1.4.3', '小屏上的搜索、分页和助手会话又顺了一截',
  '[{"type":"fix","text":"手机上打开顶部搜索，弹窗不再超出屏幕，输入框和结果都能完整操作。"},{"type":"fix","text":"AI 助手会话页在手机上重新占满可用高度，底部不再留一大片空白。"},{"type":"fix","text":"窄屏分页自动收起页码跳转与每页条数；富文本输入在手机上聚焦时不再被整页放大。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000030, 'v1.4.2', '手机上的任务列表和 AI 助手更好用了',
  '[{"type":"optimize","text":"任务管理、我的待办、我的消息、审核队列在手机上改用卡片显示，不用再左右拖动表格。"},{"type":"optimize","text":"AI 助手的确认操作改为在输入框位置弹出，确认前不会误发新消息；取消后还能展开查看当时确认的内容。"},{"type":"fix","text":"AI 助手会话页在手机上新增「历史会话」抽屉，对话区不再被侧栏挤成一条缝；悬浮球的主动提醒改到底部卡片，避免遮挡内容。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000031, 'v1.4.1', '首页换成双引擎驾驶舱，菜单重新分了组',
  '[{"type":"optimize","text":"首页现在能直接一句话问 AI 助手，画像健康、数据脉搏和最近更新一屏看完。"},{"type":"optimize","text":"画像相关页面独立成「画像与分析」菜单，任务中心只留任务相关入口。"},{"type":"optimize","text":"登录页视觉升级，表单更好看也更好点了。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000032, 'v1.4.0', '报表数字更可信了：机器人与外来灌数不再混进统计',
  '[{"type":"new","text":"「数据采集」页新增采集质量卡片：今天拦了多少机器人、拒绝了哪些外来网站、重复上报多少，一目了然。"},{"type":"optimize","text":"网站埋点脚本升级：断网或刷新期间的访问记录先存在浏览器里、网络恢复自动补传，关页面也不丢最后的停留时长。"},{"type":"new","text":"访客可以一键撤回数据采集授权（setConsent(false)），撤回后立即停止采集并清空本地暂存。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000033, 'v1.3.2', '一处小细节修正',
  '[{"type":"fix","text":"带刘海的手机横屏上，小助手面板里的内容不再贴进刘海区域。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-19 12:00:00', '0', '2026-10-19 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000034, 'v1.3.1', '修掉手机和平板上的几个小毛病',
  '[{"type":"optimize","text":"平板竖屏、手机横屏时点输入框，页面不会再突然放大。"},{"type":"fix","text":"小助手的确认按钮遇到网络卡顿失灵的问题已修复，现在可以正常再点。"},{"type":"optimize","text":"手机上从详情返回列表，之前填的筛选条件不会再丢。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-19 12:00:00', '0', '2026-10-19 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000035, 'v1.3', '手机上更好找、更好点、更好读',
  '[{"type":"optimize","text":"手机顶栏保留搜索和消息提醒，不用再为了看未读切回电脑。"},{"type":"optimize","text":"手机底部给手势条留出空间，页面最后一行的按钮不会再被挡住。"},{"type":"fix","text":"点击输入框不再把整页放大，填表时视野保持稳定。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-19 12:00:00', '0', '2026-10-19 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000036, 'v1.2', '画像工作室上线，圈人和看数据更直观',
  '[{"type":"new","text":"新增「画像工作室」：按人群特征自由圈人，名单可随时回看。"},{"type":"new","text":"画像规则和评分可以自己调整，改完马上能看到影响范围。"},{"type":"optimize","text":"画像详情补充了来源与变化记录，判断依据更清楚。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-18 12:00:00', '0', '2026-10-18 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000037, 'v1.1', '小助手能帮你动真数据了',
  '[{"type":"optimize","text":"对着小助手说一句话就能新建任务，真正执行前会先请你确认。"},{"type":"optimize","text":"确认卡、任务表单和模板清单都能在对话里直接填写和修改。"},{"type":"new","text":"回答里的表格和数据可以直接下载成文档，汇报不用再手工整理。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-08 12:00:00', '0', '2026-10-08 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000038, 'v1.0', '消息、反馈和审核记录都更好找',
  '[{"type":"optimize","text":"顶部消息提醒一眼看到未读数，点开就能处理。"},{"type":"new","text":"新增问题反馈入口，提交后能看到处理进度。"},{"type":"fix","text":"每一次提交、通过和退回都有记录，退回原因不再丢。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-09-24 12:00:00', '0', '2026-09-24 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;
