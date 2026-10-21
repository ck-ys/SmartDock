/**
 * 批0（2026-10-21 拍板 Q3-A/ADR）：类型检查接线——先报警不阻断。
 * vue-tsc 此前只装了依赖、从未接进任何 npm script（同款"假绿灯"，DCS 仓实证教训）。
 * 本脚本在 build:prod 前真实跑一遍 vue-tsc：有错打 [type-check] WARN 横幅并放行构建
 * （存量笔误并行清理，清理完再收紧为阻断——见 .workshop/plans/20261021-ability2-master-plan.md ADR-10/坑位）。
 */
import { spawnSync } from "node:child_process";

const bin = process.platform === "win32" ? "npx.cmd" : "npx";
const r = spawnSync(bin, ["vue-tsc", "--noEmit"], {
  stdio: ["ignore", "pipe", "pipe"],
  encoding: "utf8",
  cwd: process.cwd(),
});

if (r.error) {
  console.warn(`[type-check] 无法启动 vue-tsc（${r.error.message}）——按放行处理，请排查依赖`);
  process.exit(0);
}
const out = `${r.stdout || ""}${r.stderr || ""}`;
const bad = (out.match(/error TS/g) || []).length;
if (r.status === 0 && bad === 0) {
  console.log("[type-check] PASS：0 个类型错误");
  process.exit(0);
}
console.warn("=".repeat(64));
console.warn(`[type-check] WARN：类型检查发现 ${bad} 处错误（存量笔误并行清理中）——本次仅报警，构建继续`);
console.warn("[type-check] 首批错误预览（完整清单：npm run type-check）：");
for (const line of out.split(/\r?\n/).filter((l) => l.includes("error TS")).slice(0, 8)) {
  console.warn(`  ${line}`);
}
console.warn("=".repeat(64));
process.exit(0);
