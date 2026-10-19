import { describe, expect, it, vi } from 'vitest';
import { resolveOssResourceUrl } from './ossResource';

describe('resolveOssResourceUrl', () => {
  it('有 OSS ID 时优先使用后端受控预览地址', () => {
    const builder = vi.fn((id: string | number) => `/resource/oss/preview/${id}`);

    expect(resolveOssResourceUrl('2080185813045399554', 'http://object-storage/file.xlsx', builder)).toBe(
      '/resource/oss/preview/2080185813045399554'
    );
    expect(builder).toHaveBeenCalledWith('2080185813045399554');
  });

  it('没有 OSS ID 时兼容旧 URL，没有任何资源时返回空字符串', () => {
    const builder = vi.fn();

    expect(resolveOssResourceUrl(undefined, 'https://legacy.example/avatar.png', builder)).toBe('https://legacy.example/avatar.png');
    expect(resolveOssResourceUrl(undefined, undefined, builder)).toBe('');
    expect(builder).not.toHaveBeenCalled();
  });
});
