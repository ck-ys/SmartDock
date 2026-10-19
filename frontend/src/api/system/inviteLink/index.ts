import request from '@/utils/request';

/**
 * 邀请注册链接（批3 2026-09-26）
 * 管理端接口：生成/列表/作废/角色下拉；注册页匿名读信息走 api/login.ts 的 getInviteInfo
 */
export interface InviteLinkVO {
  linkId: string | number;
  tenantId: string;
  companyName?: string;
  roleId: string | number;
  roleName?: string;
  inviteCode: string;
  expireTime?: string | null;
  status: string;
  useCount: number;
  createTime?: string;
}

export interface InviteRoleOption {
  roleId: string | number;
  roleName: string;
}

/** 生成邀请链接（超管传 tenantId 任选租户；单位管理员不传由后端锁本租户） */
export function generateInviteLink(data: { tenantId?: string; roleId: string | number; expireTime?: string | null }) {
  return request<InviteLinkVO>({ url: '/dcs/inviteLink/generate', method: 'post', data });
}

/** 链接分页列表 */
export function listInviteLink(params?: any) {
  return request<{ rows: InviteLinkVO[]; total: number }>({ url: '/dcs/inviteLink/list', method: 'get', params });
}

/** 作废链接 */
export function revokeInviteLink(linkId: string | number) {
  return request({ url: `/dcs/inviteLink/revoke/${linkId}`, method: 'post' });
}

/** 可选默认角色下拉（超管传 tenantId 联动拉对应租户角色） */
export function listInviteRoles(tenantId?: string) {
  return request<InviteRoleOption[]>({ url: '/dcs/inviteLink/roles', method: 'get', params: tenantId ? { tenantId } : {} });
}
