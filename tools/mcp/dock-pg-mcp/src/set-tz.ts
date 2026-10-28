// 时区钉死(U12 修复, 2026-09-23): pg 驱动把 naive timestamp 按进程本地时区转 UTC 输出,
// 宿主机 Windows 若是美东(UTC-4)会导致 MCP 查出的时间 = 库内值+4h 的假象。
// 库内 naive 时间语义是北京时间——本模块必须保持为 index.ts 的【第一个】import,
// 保证赋值先于 pg/SDK 模块链里任何 new Date() 触发 Node 对 TZ 的缓存。
process.env.TZ = 'Asia/Shanghai';

export const MCP_TZ = 'Asia/Shanghai';
