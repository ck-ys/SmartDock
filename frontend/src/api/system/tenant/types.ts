export interface TenantVO extends BaseEntity {
  id: number | string;
  tenantId: number | string;
  username: string;
  contactUserName: string;
  contactPhone: string;
  companyName: string;
  licenseNumber: string;
  address: string;
  domain: string;
  intro: string;
  remark: string;
  packageId: string | number;
  expireTime: string;
  accountCount: number;
  status: string;
  /** 是否启用责任审核（'0'=关闭用转发审核 '1'=开启）；NULL/默认回退 application.yml */
  responsibilityEnabled?: string;
}

export interface TenantQuery extends PageQuery {
  tenantId: string | number;

  contactUserName: string;

  contactPhone: string;

  companyName: string;
}

export interface TenantForm {
  id: number | string | undefined;
  tenantId: number | string | undefined;
  username: string;
  password: string;
  contactUserName: string;
  contactPhone: string;
  companyName: string;
  licenseNumber: string;
  domain: string;
  address: string;
  intro: string;
  remark: string;
  packageId: string | number;
  expireTime: string;
  accountCount: number;
  status: string;
  /** 是否启用责任审核（'0'=关闭用转发审核 '1'=开启）；NULL/默认回退 application.yml */
  responsibilityEnabled?: string;
}
