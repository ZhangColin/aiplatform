# 前后端一键拉起（本地 Windows 开发；scripts/dev.sh 的 PowerShell 对应物，.sh 原版留给 Linux 服务器）：
# 本仓后端（8888）与前端（3333）拉起到健康——没在跑就直接起（停旧段空跑），
# 已在跑则先停再起（换新代码）。附健康探测。
#
# 进程锚定只认本仓端口与路径（8888/3333 监听者 + 命令行含本仓路径的 java/node），
# 绝不宽匹配——同机还跑着别的 next dev，按进程名停会误伤。
# identity（10002）本脚本不管，仅在结尾探测展示（SSO 依赖它，顺手看一眼）。
#
# 用法：powershell -ExecutionPolicy Bypass -File scripts/dev.ps1（仓库任意位置）
#   后端启动复用 aiplatform-server/scripts/dev-backend.ps1（.env.local 导出逻辑在那边）
# 日志：%TEMP%\aiplatform-backend.log、aiplatform-web.log（每次执行覆盖）
$ErrorActionPreference = 'Continue'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$BackendPort = 8888
$FrontendPort = 3333
$IdentityPort = 10002
$BackendLog = Join-Path $env:TEMP 'aiplatform-backend.log'
$WebLog = Join-Path $env:TEMP 'aiplatform-web.log'

function Get-PortPid([int]$Port) {
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
}

function Test-Http([string]$Url) {
    $ProgressPreference = 'SilentlyContinue'   # 5.1 的 Invoke-WebRequest 不关进度条会拖慢探测
    try {
        return [int](Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2).StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

Write-Host "== 停旧（本仓端口/路径锚定） =="
# 候选 = 端口监听者 + 命令行含本仓路径的 java（target\classes）/ node（next dev）
$targetPids = New-Object System.Collections.Generic.List[int]
foreach ($port in @($BackendPort, $FrontendPort)) {
    foreach ($listenPid in @(Get-PortPid $port)) {
        if ($listenPid) { $targetPids.Add([int]$listenPid) }
    }
}
foreach ($proc in @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) {
    $cmd = $proc.CommandLine
    if (-not $cmd) { continue }
    if ($cmd -match [regex]::Escape(($RepoRoot -replace '/', '\')) -and
        ($cmd -match 'target\\classes' -or $cmd -match 'next dev')) {
        $targetPids.Add([int]$proc.ProcessId)
    }
}
foreach ($procId in $targetPids | Sort-Object -Unique) {
    try {
        # java 的父进程若是 maven 启动器（classworlds），一并停，不留孤儿等待
        $parent = (Get-CimInstance Win32_Process -Filter "ProcessId=$procId").ParentProcessId
        if ($parent) {
            $parentCmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$parent").CommandLine
            if ($parentCmd -and $parentCmd -match 'classworlds|maven') {
                Stop-Process -Id $parent -Force -ErrorAction SilentlyContinue
            }
        }
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    } catch { }
}

Write-Host "== 起新 =="
Start-Process -WindowStyle Hidden powershell.exe -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    "& '$RepoRoot\aiplatform-server\scripts\dev-backend.ps1' *> '$BackendLog'"
)
Start-Process -WindowStyle Hidden powershell.exe -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    "Set-Location '$RepoRoot\aiplatform-web'; npm run dev *> '$WebLog'"
)

Write-Host "== 健康探测（后端最长 120s / 前端 60s） ="
$backend = 0; $frontend = 0
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
    $backend = Test-Http "http://localhost:${BackendPort}/actuator/health"
    $frontend = Test-Http "http://localhost:${FrontendPort}/"
    if ($backend -eq 200 -and $frontend -ne 0) { break }
    Start-Sleep -Seconds 2
}

$backendOk = if ($backend -eq 200) { 'OK' } else { '未就绪' }
$frontendOk = if ($frontend -ne 0) { 'OK' } else { '未就绪' }
Write-Host "后端   :${BackendPort}  ${backend}  ${backendOk}"
Write-Host "前端   :${FrontendPort}  ${frontend}  ${frontendOk}"
$identity = Test-Http "http://localhost:${IdentityPort}/"
Write-Host "identity:${IdentityPort}  ${identity}（信息项，本脚本不管它）"
Write-Host "日志：$BackendLog / $WebLog"

if (-not ($backend -eq 200 -and $frontend -ne 0)) { exit 1 }
