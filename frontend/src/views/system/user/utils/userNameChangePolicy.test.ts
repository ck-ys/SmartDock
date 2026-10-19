import { describe, expect, it } from 'vitest';
import { buildLoginNameChangeConfirmMessage, shouldConfirmLoginNameChange } from './userNameChangePolicy';

describe('userNameChangePolicy', () => {
  it('only confirms when an existing user login name changes', () => {
    expect(shouldConfirmLoginNameChange(undefined, '', 'new-user')).toBe(false);
    expect(shouldConfirmLoginNameChange('100', 'old-user', 'old-user')).toBe(false);
    expect(shouldConfirmLoginNameChange('100', 'old-user', 'new-user')).toBe(true);
  });

  it('explains identity history and active-session compatibility', () => {
    expect(buildLoginNameChangeConfirmMessage('old-user', 'new-user')).toBe(
      '确认将登录账号从“old-user”修改为“new-user”吗？修改后该用户下次需使用新账号登录；用户ID、任务与审计历史不变；当前已登录会话不会立即退出。'
    );
  });
});
