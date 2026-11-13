-- 2026-11-13 题 6（P1-15 越权）：把「成员 member」误授的管理类权限收回来。
--
-- 现象：sys_role_menu 给 role_key='member' 挂了「系统管理」整族 76 项绑定（含 system:user:*），
--       普通用户 user001 打开 /system/user 时工具栏「新增/修改/删除/更多」全部渲染，
--       列表接口返回 200（total:0），只有详情接口被后端拦住。
--
-- 治法：纯数据修复——删掉 member 在「系统管理」菜单子树下的全部绑定。
--       日常权限（任务中心、系统支持、AI 助手）不动；超管 / 租户管理员 / 文件审核员不动。
--       代码零改动：前端 v-hasPermi 门牌机制本身是好的，收回门牌按钮自然消失。
--
-- 可重复执行：按当前菜单树与角色键重新计算，重复跑只会删除「仍存在」的多余绑定，不报错。
WITH RECURSIVE manage_tree AS (
  SELECT menu_id FROM sys_menu WHERE menu_id = 1 AND parent_id = 0
  UNION ALL
  SELECT m.menu_id FROM sys_menu m JOIN manage_tree t ON m.parent_id = t.menu_id
)
DELETE FROM sys_role_menu
WHERE role_id IN (SELECT role_id FROM sys_role WHERE role_key = 'member' AND del_flag = '0')
  AND menu_id IN (SELECT menu_id FROM manage_tree);
