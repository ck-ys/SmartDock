# SmartDock Frontend

Vue 3 + TypeScript + Element Plus + Vite 单页应用，与后端微核心契约一一对应
（契约层在 [src/api/dcs/core/](src/api/dcs/core/)）。

## 开发

```bash
npm i
npm run dev        # http://localhost:5188，代理到 localhost:8090
npm test           # vitest（200 用例）
npm run build:prod # 产物 dist/
```

登录账号见仓库根 [README.md](../README.md)。本前端基于
[RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus)（MIT）前端裁剪重整，
许可与署名见根目录 [NOTICE](../NOTICE) 与本目录 `LICENSE.txt`。
