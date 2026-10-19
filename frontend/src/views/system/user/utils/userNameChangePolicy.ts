export const shouldConfirmLoginNameChange = (
  userId: number | string | null | undefined,
  originalUserName: string,
  currentUserName: string
): boolean => userId != null && originalUserName !== currentUserName;

export const buildLoginNameChangeConfirmMessage = (originalUserName: string, currentUserName: string): string =>
  `确认将登录账号从“${originalUserName}”修改为“${currentUserName}”吗？` +
  '修改后该用户下次需使用新账号登录；用户ID、任务与审计历史不变；当前已登录会话不会立即退出。';
