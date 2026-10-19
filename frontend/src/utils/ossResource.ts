type PreviewUrlBuilder = (ossId: string | number) => string;

/**
 * 优先通过应用后端读取私有资源，避免客户端必须直连对象存储。
 * fallbackUrl 仅用于兼容尚未返回 OSS ID 的旧接口。
 */
export const resolveOssResourceUrl = (ossId?: string | number | null, fallbackUrl?: string | null, previewUrlBuilder?: PreviewUrlBuilder): string => {
  if (ossId !== undefined && ossId !== null && String(ossId).trim() && previewUrlBuilder) return previewUrlBuilder(ossId);
  return fallbackUrl || '';
};
