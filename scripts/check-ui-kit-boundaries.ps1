<#
沿用既有命令入口，平台和 UI 边界由 Python 校验共享实现。
#>
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$Python = "python"
)

try {
    & $Python (Join-Path $PSScriptRoot "../tools/check-all.py") --root $Root --only isolation --only ui-kit
    exit $LASTEXITCODE
} catch {
    Write-Error $_
    exit 2
}
