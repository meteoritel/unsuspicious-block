<#
独立 UI kit 消费边界检查。
扫描宿主 Java 源集的直接与全限定引用；业务布局与第三方数据出口由源码审查确认。
用法：pwsh -NoProfile -File scripts/check-ui-kit-boundaries.ps1 [-Root <仓库根>]
退出码：0 通过；1 违规；2 源集缺失。
#>
param([string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path)

$violations = [System.Collections.Generic.List[string]]::new()
$sourceRoots = @("common", "fabric", "neoforge") | ForEach-Object { Join-Path $Root ($_ + "/src/main/java") }
foreach ($sourceRoot in $sourceRoots) {
    if (-not (Test-Path -LiteralPath $sourceRoot)) {
        Write-Error "找不到宿主源集：$sourceRoot"
        exit 2
    }
}
$legacyDir = Join-Path $Root "common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/kit"
if (Test-Path -LiteralPath $legacyDir) { $violations.Add("[legacy-directory] $legacyDir") }

foreach ($sourceRoot in $sourceRoots) {
    $sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter *.java)
    if ($sourceFiles.Count -eq 0) {
        Write-Error "宿主源集为空：$sourceRoot"
        exit 2
    }
    foreach ($file in $sourceFiles) {
        $relative = [IO.Path]::GetRelativePath($Root, $file.FullName).Replace('\', '/')
        $isClient = $relative -match '/com/meteorite/unsuspiciousblock/client/' -or
            $relative -match '^fabric/src/main/java/com/meteorite/unsuspiciousblock/modmenu/' -or
            $relative -match '/plugin/jei/(JournalJeiGuiHandler|PotteryWheelJeiCategory)\.java$'
        $lineNumber = 0
        foreach ($line in Get-Content -LiteralPath $file.FullName -Encoding utf8) {
            $lineNumber++
            $rules = [ordered]@{
                'legacy-kit' = 'com\.meteorite\.unsuspiciousblock\.client\.ui\.kit\b'
                'kit-internal-consumer' = 'com\.meteorite\.meteosuikit\.(?!api\.)'
                'native-owned-widget' = 'net\.minecraft\.client\.gui\.components\.(AbstractButton|AbstractWidget|Button|EditBox|MultiLineEditBox|MultiLineTextWidget|Checkbox|CycleButton)\b|net\.minecraft\.client\.gui\.screens\.(ConfirmScreen|ConfirmLinkScreen)\b'
            }
            if (-not $isClient) { $rules['non-client-consumes-kit'] = 'com\.meteorite\.meteosuikit\.api\.' }
            if ($relative.StartsWith('common/')) {
                $rules['common-imports-loader'] = '^\s*import\s+(static\s+)?(net\.neoforged|net\.fabricmc|net\.minecraftforge|cpw\.mods)\.'
            }
            foreach ($rule in $rules.GetEnumerator()) {
                if ($line -match $rule.Value) { $violations.Add("[$($rule.Key)] ${relative}:${lineNumber} -> $($line.Trim())") }
            }
        }
    }
}
if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Host $_ }
    exit 1
}
Write-Host "UI kit 消费检查通过：公开 API、客户端归属、common 平台隔离和旧实现退出。"
exit 0
