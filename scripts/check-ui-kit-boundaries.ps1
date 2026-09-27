<#
UI kit 依赖方向检查（阶段 E）。

用途：在拆包/发布之前，用可执行的方式守住四条边界，避免它们靠人工记忆退化：

  1) kit 内部不得 import 任何项目包（kit 只依赖 Minecraft 客户端类型、Java 标准库与 JOML/annotations），
     这样 kit 才可能被抽成独立库；
  2) kit 内部不得出现两端 loader / 平台 API（neoforged、fabricmc、minecraftforge、mods）；
  3) kit 内部不得出现 Constants.MOD_ID、模组命名空间字面量这类项目资源常量；
  4) client/ 以外的代码不得 import kit（服务端加载路径不得链接客户端 kit 类）。

用法：pwsh -File scripts/check-ui-kit-boundaries.ps1 [-Root <仓库根>]
退出码：0 全部通过；1 有违规（逐条打印 文件:行）；2 环境异常（找不到 kit 目录）。

已知局限（有意保留，请按需加强）：
  · 规则 1/2 只匹配 import 语句，不解析同包引用；import static 已覆盖；
  · 规则 3 会剔除整行注释与行内注释，但不会剔除块注释中间的行；
  · 规则 4 扫描 common / fabric / neoforge 的 src/main/java，不扫描 test 源集与构建产物。
#>
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$clientDir = Join-Path $Root "common/src/main/java/com/meteorite/unsuspiciousblock/client"
$kitDir = Join-Path $clientDir "ui/kit"

$violations = New-Object System.Collections.Generic.List[string]

function Add-Hit($title, $file, $lineNumber, $line) {
    $rel = Resolve-Path -Relative $file
    $script:violations.Add("[" + $title + "] " + $rel + ":" + $lineNumber + " -> " + $line.Trim())
}

function Test-LinePattern($title, $pattern, $files) {
    foreach ($file in $files) {
        $lines = Get-Content -LiteralPath $file
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match $pattern) { Add-Hit $title $file ($i + 1) $lines[$i] }
        }
    }
}

# 资源常量单独处理：先跳过整行注释、再剔除行内注释，避免 javadoc / 注释说明被误报。
function Test-ModConstants($files) {
    foreach ($file in $files) {
        $lines = Get-Content -LiteralPath $file
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $code = $lines[$i]
            if ($code -match "^\s*(\*|/\*|//)") { continue }
            $code = ($code -replace "//.*$", "")
            if ($code -match "Constants\.MOD_ID" -or $code -match "unsuspiciousblock:" -or
                $code -match 'fromNamespaceAndPath\(\s*"unsuspiciousblock"') {
                Add-Hit "kit-uses-mod-constants" $file ($i + 1) $lines[$i]
            }
        }
    }
}

if (-not (Test-Path $kitDir)) { Write-Error ("找不到 kit 目录：" + $kitDir); exit 2 }

$kitFiles = Get-ChildItem -Path $kitDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
Test-LinePattern "kit-imports-project" '^\s*import\s+(static\s+)?com\.meteorite\.unsuspiciousblock\.' $kitFiles
Test-LinePattern "kit-imports-platform" '^\s*import\s+(static\s+)?(net\.neoforged|net\.fabricmc|net\.minecraftforge|cpw\.mods)\.' $kitFiles
Test-ModConstants $kitFiles

$sourceRoots = @("common", "fabric", "neoforge") |
    ForEach-Object { Join-Path $Root ($_ + "/src/main/java") } |
    Where-Object { Test-Path $_ }
$otherFiles = foreach ($root in $sourceRoots) {
    Get-ChildItem -Path $root -Recurse -Filter *.java |
        Where-Object { -not $_.FullName.StartsWith($clientDir, [System.StringComparison]::OrdinalIgnoreCase) } |
        Select-Object -ExpandProperty FullName
}
Test-LinePattern "non-client-imports-kit" '^\s*import\s+(static\s+)?com\.meteorite\.unsuspiciousblock\.client\.ui\.kit\.' $otherFiles

if ($violations.Count -eq 0) {
    Write-Host "UI kit 边界检查通过：kit 无项目依赖 / 无平台 API / 无资源常量；client 以外未引用 kit。"
    Write-Host ("扫描范围：" + $kitFiles.Count + " 个 kit 文件 + " + ($otherFiles | Measure-Object).Count + " 个非 client 文件。")
    exit 0
}
Write-Host ("UI kit 边界检查失败，共 " + $violations.Count + " 条：")
$violations | ForEach-Object { Write-Host ("  " + $_) }
exit 1
