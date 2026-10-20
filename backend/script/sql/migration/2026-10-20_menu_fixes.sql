-- 二期批7前置快赢: 菜单结构修正
-- 1) 11987(数据分析编辑) 原挂在 9070(用户画像) 下, 只勾数据分析菜单的角色拿不到编辑权限 → 挂到 11986(数据分析)
-- 2) 9070 remark 提到不存在的 "AI诊断" 能力 → 移除
UPDATE public.sys_menu SET parent_id = 11986 WHERE menu_id = 11987 AND parent_id = 9070;
UPDATE public.sys_menu SET remark = '用户画像查询(综合分/4维/排行)' WHERE menu_id = 9070 AND remark LIKE '%AI诊断%';
