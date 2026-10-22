/**
 * 批0（2026-10-21 拍板 Q3-A/ADR）：类型检查接线——先报警不阻断。
 * G5（2026-10-22 拍板 Q5-A）收紧为硬阻断：存量 183 处已清零，自此有错即 fail
 * （build:prod 退出非零），假绿灯通道彻底关闭；依赖缺失/工具崩溃同样 fail-closed。
 * 三审回修（glm53/dsflash/dspro 一致 P1）：Windows Node≥20.12 禁止无 shell spawn
 * .cmd（CVE-2024-27980），spawnSync('npx.cmd') 恒 EINVAL→假放行；改 require.resolve
 * 直定位 vue-tsc 入口 + process.execPath 启动，绕开 npx 且无 .cmd。
 */
import { spawnSync } from "node:child_process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
let vueTscBin;
try {
  vueTscBin = require.resolve("vue-tsc/bin/vue-tsc.js");
} catch {
  console.error("[type-check] 未找到 vue-tsc 依赖（npm i 后重试）——fail-closed 中断构建");
  process.exit(1);
}
const r = spawnSync(process.execPath, [vueTscBin, "--noEmit"], {
  stdio: ["ignore", "pipe", "pipe"],
  encoding: "utf8",
  cwd: process.cwd(),
  timeout: 10 * 60_000,
  maxBuffer: 16 * 1024 * 1024,
});
if (r.error?.code === "ETIMEDOUT") {
  console.error("[type-check] vue-tsc 超时（10 分钟）——fail-closed 中断构建");
  process.exit(1);
}

if (r.error) {
  console.error(`[type-check] 无法启动 vue-tsc（${r.error.message}）——fail-closed 中断构建，请排查依赖`);
  process.exit(1);
}
const out = `${r.stdout || ""}${r.stderr || ""}`;
const bad = (out.match(/error TS/g) || []).length;
if (r.status === 0 && bad === 0) {
  console.log("[type-check] PASS：0 个类型错误");
  process.exit(0);
}
console.error("=".repeat(64));
console.error(`[type-check] FAIL：${bad > 0 ? `发现 ${bad} 处类型错误——构建中断` : `vue-tsc 异常退出（status=${r.status}，0 行可数错误）——fail-closed 中断构建`}`);
console.error("[type-check] 首批错误预览（完整清单：npm run type-check）：");
for (const line of out.split(/\r?\n/).filter((l) => l.includes("error TS")).slice(0, 8)) {
  console.error(`  ${line}`);
}
console.error("=".repeat(64));
process.exit(1);
