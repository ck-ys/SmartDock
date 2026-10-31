import { describe, expect, it } from 'vitest';
import { tenantLabel } from './tenantLabel';

describe('tenantLabel', () => {
  it('有公司名就用公司名', () => {
    expect(tenantLabel({ tenantId: '978311', companyName: '演示单位', contactUserName: '张三' })).toBe('978311 / 演示单位');
  });

  it('公司名为空退联系人', () => {
    expect(tenantLabel({ tenantId: '978311', companyName: '', contactUserName: '张三' })).toBe('978311 / 张三');
  });

  it('两个都空退横杠，不显示 null/undefined', () => {
    expect(tenantLabel({ tenantId: '978311', companyName: null, contactUserName: undefined })).toBe('978311 / -');
  });
});
