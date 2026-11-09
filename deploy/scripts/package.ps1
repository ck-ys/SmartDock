# Windows 下打交付包（等价于 scripts/package.sh）
# 用法：powershell -ExecutionPolicy Bypass -File deploy\scripts\package.ps1 -Tag 20261107
param(
  [string]$Tag = (Get-Date -Format 'yyyyMMdd')
)
$ErrorActionPreference = 'Stop'
$deploy = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$root = Split-Path -Parent $deploy
$out = Join-Path $deploy "release\smartdock-$Tag"

Write-Host '→ 1/3 构建后端（需要 JDK21 + Maven；本机可用 .workshop\tmp\package-backend.ps1）'
if (Test-Path (Join-Path $root '.workshop\tmp\package-backend.ps1')) {
  & powershell -ExecutionPolicy Bypass -File (Join-Path $root '.workshop\tmp\package-backend.ps1')
  if ($LASTEXITCODE -ne 0) { throw "后端构建失败（exit $LASTEXITCODE）" }
} else {
  Push-Location (Join-Path $root 'backend')
  try { & mvn -q -P dev -DskipTests package -pl ruoyi-admin -am } finally { Pop-Location }
}

Write-Host '→ 2/3 构建前端'
Push-Location (Join-Path $root 'frontend')
try {
  npm install --no-audit --no-fund
  npm run build:prod
} finally { Pop-Location }

Write-Host "→ 3/3 组装 $out"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out -Force | Out-Null

# 配方（排除运行时目录与 .env）
$exclude = @('data', 'logs', 'backups', 'release', '.env')
Get-ChildItem $deploy -Force | Where-Object { $exclude -notcontains $_.Name } | ForEach-Object {
  Copy-Item $_.FullName -Destination (Join-Path $out 'deploy') -Recurse -Force
}
New-Item -ItemType Directory -Path (Join-Path $out 'backend\ruoyi-admin\target') -Force | Out-Null
Copy-Item (Join-Path $root 'backend\ruoyi-admin\target\ruoyi-admin.jar') (Join-Path $out 'backend\ruoyi-admin\target\') -Force
New-Item -ItemType Directory -Path (Join-Path $out 'frontend') -Force | Out-Null
Copy-Item (Join-Path $root 'frontend\dist') (Join-Path $out 'frontend\dist') -Recurse -Force
Copy-Item (Join-Path $root 'backend\script\sql') (Join-Path $out 'backend\script\sql') -Recurse -Force

$tar = Join-Path $deploy "release\smartdock-$Tag.tar.gz"
Push-Location (Join-Path $deploy 'release')
try { tar -czf $tar "smartdock-$Tag" } finally { Pop-Location }

Write-Host "✓ 交付包：$tar"
Write-Host "  服务器上：tar -xzf $(Split-Path -Leaf $tar); cd smartdock-$Tag/deploy; cp .env.example .env; vi .env; ./scripts/deploy.sh"
