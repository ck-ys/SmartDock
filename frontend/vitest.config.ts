import { defineConfig } from 'vitest/config';
import path from 'path';
import createPlugins from './vite/plugins';

/**
 * Vitest 独立配置。
 *
 * 让 `npm test`(`vitest run`)成为标准测试入口, 同时不影响 vite.config.ts 的构建/开发配置。
 * 现有测试均为纯函数/工具测试, 默认 node 环境即可; 未来若加 Vue 组件测试,
 * 需 `npm i -D jsdom` 并把 environment 改为 'jsdom'。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src')
    },
    extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
  },
  plugins: createPlugins({} as any, false),
  test: {
    // 纯函数测试无需 DOM; 加组件测试时改 'jsdom' 并装 jsdom 依赖
    environment: 'node',
    // 允许 describe/it/expect 等全局可用(与现有 .test.ts 写法一致)
    globals: true,
    // 扫描 src 下所有 .test.ts(含 __tests__/ 子目录)
    include: ['src/**/*.test.ts']
  }
});
