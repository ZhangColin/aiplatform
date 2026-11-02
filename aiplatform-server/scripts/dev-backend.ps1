# 后端一键启动（本地 Windows 开发；dev-backend.sh 的 PowerShell 对应物，.sh 原版留给 Linux 服务器）：
# .env.local 是裸 KEY=VALUE，必须导出成进程环境变量 Spring 才读得到（症状：sso.client-id 空）。
# 用法：powershell -ExecutionPolicy Bypass -File aiplatform-server/scripts/dev-backend.ps1（任意目录均可——脚本自 cd）
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if (-not (Test-Path .env.local)) {
    Write-Error "缺 aiplatform-server/.env.local（SSO 七项 + VOLCENGINE_API_KEY + BOCHA_API_KEY + OPENAPI_APP_*，DEEPSEEK_API_KEY 可选备胎，gitignored）"
    exit 1
}

# 逐行解析裸 KEY=VALUE：忽略空行/注释，剥掉成对引号，注入进程环境变量。
# 必须 -Encoding UTF8：文件是 UTF-8 无 BOM，Windows PowerShell 5.1 缺省会按系统
# GBK 码页误读——注释行尾全角标点（。）的字节对会吞掉换行，把下一个 KEY 行并进
# 注释整行丢弃（实证：13 个 KEY 行静默丢 5 个，含 SSO_CLIENT_ID／RESEND_API_KEY）。
Get-Content -Encoding UTF8 .env.local | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#')) { return }
    $eq = $line.IndexOf('=')
    if ($eq -lt 1) { return }
    $key = $line.Substring(0, $eq).Trim()
    $val = $line.Substring($eq + 1).Trim()
    if ($val.Length -ge 2 -and (($val[0] -eq '"' -and $val[-1] -eq '"') -or ($val[0] -eq "'" -and $val[-1] -eq "'"))) {
        $val = $val.Substring(1, $val.Length - 2)
    }
    Set-Item -Path "Env:$key" -Value $val
}

mvn spring-boot:run
