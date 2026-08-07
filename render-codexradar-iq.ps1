[CmdletBinding()]
param(
    # Defaults to an image beside this script. Relative custom paths resolve from the caller's current file-system location.
    [Parameter(Position = 0)]
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

function Resolve-OutputPath {
    param(
        [AllowNull()]
        [string]$Path
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'codexradar-iq-seven-by-three.png'))
    }

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }

    $currentLocation = (Get-Location -PSProvider FileSystem).ProviderPath
    return [System.IO.Path]::GetFullPath((Join-Path $currentLocation $Path))
}

$outputPath = Resolve-OutputPath -Path $OutputPath
$outputDirectory = Split-Path -Parent $outputPath
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}

function Get-LiveModels {
    $endpoint = 'https://codexradar.com/api/intelligence-efficiency?refresh=1'
    $response = Invoke-WebRequest -Uri $endpoint -UseBasicParsing -TimeoutSec 20 -Headers @{
        Accept = 'application/json'
        'Cache-Control' = 'no-cache'
    }
    if ($response.StatusCode -ne 200) {
        throw "CodexRadar returned HTTP $($response.StatusCode)."
    }
    if ([string]$response.Headers['Content-Type'] -notmatch '^application/json(?:;|$)') {
        throw 'CodexRadar did not return JSON.'
    }
    if ($response.RawContentLength -gt 8MB) {
        throw 'CodexRadar response exceeds the 8 MiB limit.'
    }

    $payload = $response.Content | ConvertFrom-Json
    if ($payload.schema -ne 1) {
        throw 'CodexRadar response schema is unsupported.'
    }

    $expected = @(
        [pscustomobject]@{ Model = 'gpt-5.6-sol'; Strength = 'ultra'; Name = 'GPT5.6 Sol'; Family = 'Sol' }
        [pscustomobject]@{ Model = 'gpt-5.6-sol'; Strength = 'max'; Name = 'GPT5.6 Sol'; Family = 'Sol' }
        [pscustomobject]@{ Model = 'gpt-5.6-sol'; Strength = 'xhigh'; Name = 'GPT5.6 Sol'; Family = 'Sol' }
        [pscustomobject]@{ Model = 'gpt-5.6-sol'; Strength = 'high'; Name = 'GPT5.6 Sol'; Family = 'Sol' }
        [pscustomobject]@{ Model = 'gpt-5.6-sol'; Strength = 'medium'; Name = 'GPT5.6 Sol'; Family = 'Sol' }
        [pscustomobject]@{ Model = 'gpt-5.6-sol'; Strength = 'low'; Name = 'GPT5.6 Sol'; Family = 'Sol' }
        [pscustomobject]@{ Model = 'gpt-5.6-terra'; Strength = 'ultra'; Name = 'GPT5.6 Terra'; Family = 'Terra' }
        [pscustomobject]@{ Model = 'gpt-5.6-terra'; Strength = 'max'; Name = 'GPT5.6 Terra'; Family = 'Terra' }
        [pscustomobject]@{ Model = 'gpt-5.6-terra'; Strength = 'xhigh'; Name = 'GPT5.6 Terra'; Family = 'Terra' }
        [pscustomobject]@{ Model = 'gpt-5.6-terra'; Strength = 'high'; Name = 'GPT5.6 Terra'; Family = 'Terra' }
        [pscustomobject]@{ Model = 'gpt-5.6-terra'; Strength = 'medium'; Name = 'GPT5.6 Terra'; Family = 'Terra' }
        [pscustomobject]@{ Model = 'gpt-5.6-terra'; Strength = 'low'; Name = 'GPT5.6 Terra'; Family = 'Terra' }
        [pscustomobject]@{ Model = 'gpt-5.6-luna'; Strength = 'max'; Name = 'GPT5.6 Luna'; Family = 'Luna' }
        [pscustomobject]@{ Model = 'gpt-5.6-luna'; Strength = 'xhigh'; Name = 'GPT5.6 Luna'; Family = 'Luna' }
        [pscustomobject]@{ Model = 'gpt-5.6-luna'; Strength = 'high'; Name = 'GPT5.6 Luna'; Family = 'Luna' }
        [pscustomobject]@{ Model = 'gpt-5.6-luna'; Strength = 'medium'; Name = 'GPT5.6 Luna'; Family = 'Luna' }
        [pscustomobject]@{ Model = 'gpt-5.6-luna'; Strength = 'low'; Name = 'GPT5.6 Luna'; Family = 'Luna' }
        [pscustomobject]@{ Model = 'gpt-5.5'; Strength = 'xhigh'; Name = 'GPT5.5'; Family = 'GPT55' }
        [pscustomobject]@{ Model = 'gpt-5.5'; Strength = 'high'; Name = 'GPT5.5'; Family = 'GPT55' }
        [pscustomobject]@{ Model = 'deepseek-v4-flash'; Strength = 'max'; Name = 'DeepSeek V4 Flash'; Family = 'DeepSeek' }
        [pscustomobject]@{ Model = 'deepseek-v4-flash'; Strength = 'high'; Name = 'DeepSeek V4 Flash'; Family = 'DeepSeek' }
    )
    $expectedByKey = @{}
    foreach ($definition in $expected) {
        $expectedByKey["$($definition.Model)|$($definition.Strength)"] = $definition
    }

    $combos = @($payload.combos)
    if ($combos.Count -ne $expected.Count) {
        throw 'CodexRadar did not return exactly 21 model combinations.'
    }
    $seenCombos = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($combo in $combos) {
        $key = "$([string]$combo.model)|$([string]$combo.effort)"
        if (-not $expectedByKey.ContainsKey($key) -or -not $seenCombos.Add($key)) {
            throw 'CodexRadar returned an unknown or duplicate model combination.'
        }
    }
    if ($seenCombos.Count -ne $expected.Count) {
        throw 'CodexRadar model combinations are incomplete.'
    }

    $taskIds = [System.Collections.Generic.List[string]]::new()
    $seenTasks = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($task in @($payload.tasks)) {
        $taskId = [string]$task.id
        if ([string]::IsNullOrWhiteSpace($taskId) -or $taskId.Contains('|') -or -not $seenTasks.Add($taskId)) {
            throw 'CodexRadar task IDs are invalid.'
        }
        $taskIds.Add($taskId)
    }
    if ($taskIds.Count -eq 0 -or $null -eq $payload.cells) {
        throw 'CodexRadar table has no tasks or cells.'
    }

    $models = [System.Collections.Generic.List[object]]::new()
    foreach ($definition in $expected) {
        $passedTasks = 0
        $validTasks = 0
        foreach ($taskId in $taskIds) {
            $cellKey = "$taskId|$($definition.Model)|$($definition.Strength)"
            $cellProperty = $payload.cells.PSObject.Properties[$cellKey]
            if ($null -eq $cellProperty) {
                continue
            }
            $runners = @($cellProperty.Value.ran_by)
            if ($runners.Count -eq 0) {
                continue
            }
            $passedProperty = $runners[0].PSObject.Properties['passed']
            if ($null -eq $passedProperty -or $passedProperty.Value -isnot [bool]) {
                continue
            }
            $validTasks++
            if ($passedProperty.Value) {
                $passedTasks++
            }
        }
        if ($validTasks -eq 0) {
            throw "CodexRadar returned no valid samples for $($definition.Name) $($definition.Strength)."
        }
        $iq = $passedTasks / [double]$validTasks * 150.0
        if ([double]::IsNaN($iq) -or [double]::IsInfinity($iq) -or $iq -lt 0.0 -or $iq -gt 120.0) {
            throw "CodexRadar returned an unsupported IQ for $($definition.Name) $($definition.Strength)."
        }
        $models.Add([pscustomobject]@{
            Name = $definition.Name
            IQ = $iq
            Family = $definition.Family
            Strength = $definition.Strength
        })
    }
    return $models.ToArray()
}

$models = @(Get-LiveModels)
$fetchedAt = [DateTimeOffset]::Now
$width = 1400
$height = 1750
$bitmap = [System.Drawing.Bitmap]::new($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

$background = [System.Drawing.ColorTranslator]::FromHtml('#0D1117')
$surface = [System.Drawing.ColorTranslator]::FromHtml('#161B22')
$border = [System.Drawing.ColorTranslator]::FromHtml('#30363D')
$text = [System.Drawing.ColorTranslator]::FromHtml('#F0F6FC')
$muted = [System.Drawing.ColorTranslator]::FromHtml('#8B949E')
$track = [System.Drawing.ColorTranslator]::FromHtml('#282E36')

$familyColors = @{
    Sol = [System.Drawing.ColorTranslator]::FromHtml('#58A6FF')
    Terra = [System.Drawing.ColorTranslator]::FromHtml('#F2A65A')
    Luna = [System.Drawing.ColorTranslator]::FromHtml('#6BCB8B')
    GPT55 = [System.Drawing.ColorTranslator]::FromHtml('#E68AC3')
    DeepSeek = [System.Drawing.ColorTranslator]::FromHtml('#A98AF7')
}

function New-RoundedRectanglePath {
    param(
        [float]$X,
        [float]$Y,
        [float]$Width,
        [float]$Height,
        [float]$Radius
    )

    $diameter = $Radius * 2
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $path.AddArc($X, $Y, $diameter, $diameter, 180, 90)
    $path.AddArc($X + $Width - $diameter, $Y, $diameter, $diameter, 270, 90)
    $path.AddArc($X + $Width - $diameter, $Y + $Height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($X, $Y + $Height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

$backgroundBrush = [System.Drawing.SolidBrush]::new($background)
$surfaceBrush = [System.Drawing.SolidBrush]::new($surface)
$textBrush = [System.Drawing.SolidBrush]::new($text)
$mutedBrush = [System.Drawing.SolidBrush]::new($muted)
$trackBrush = [System.Drawing.SolidBrush]::new($track)
$borderPen = [System.Drawing.Pen]::new($border, 1)
$titleFont = [System.Drawing.Font]::new('Microsoft YaHei UI', 28, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$eyebrowFont = [System.Drawing.Font]::new('Segoe UI', 12, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$metaFont = [System.Drawing.Font]::new('Microsoft YaHei UI', 14, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$nameFont = [System.Drawing.Font]::new('Segoe UI', 15, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$strengthFont = [System.Drawing.Font]::new('Segoe UI', 10, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$rankTickFont = [System.Drawing.Font]::new('Segoe UI', 14, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$valueFont = [System.Drawing.Font]::new('Segoe UI', 42, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$unitFont = [System.Drawing.Font]::new('Segoe UI', 13, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)

try {
    $graphics.FillRectangle($backgroundBrush, 0, 0, $width, $height)

    $graphics.DrawString('CODEXRADAR / INTELLIGENCE EFFICIENCY', $eyebrowFont, $mutedBrush, 32, 24)
    $graphics.DrawString('21 个模型档位 IQ', $titleFont, $textBrush, 30, 47)
    $graphics.DrawString('7 x 3  GRID', $eyebrowFont, $mutedBrush, 1280, 29)
    $graphics.DrawString(('实时抓取 ' + $fetchedAt.ToString('MM/dd HH:mm')), $metaFont, $mutedBrush, 1170, 55)

    $marginX = 32
    $gridY = 110
    $columnGap = 8
    $rowGap = 10
    $cardWidth = 184
    $cardHeight = 160
    $cardPadding = 14

    for ($index = 0; $index -lt $models.Count; $index++) {
        $model = $models[$index]
        $column = $index % 7
        $row = [Math]::Floor($index / 7)
        $x = $marginX + ($column * ($cardWidth + $columnGap))
        $y = $gridY + ($row * ($cardHeight + $rowGap))
        $accentColor = $familyColors[$model.Family]
        $accentBrush = [System.Drawing.SolidBrush]::new($accentColor)
        $cardPath = New-RoundedRectanglePath -X $x -Y $y -Width $cardWidth -Height $cardHeight -Radius 7
        $strengthSize = $graphics.MeasureString($model.Strength, $strengthFont)
        $strengthBadgeHeight = [float]22
        $strengthBadgeWidth = [float][Math]::Max(38, [Math]::Ceiling($strengthSize.Width + 12))
        $strengthBadgeX = $x + $cardWidth - $cardPadding - $strengthBadgeWidth
        $strengthBadgeY = $y + 10
        $strengthBadgePath = New-RoundedRectanglePath -X $strengthBadgeX -Y $strengthBadgeY -Width $strengthBadgeWidth -Height $strengthBadgeHeight -Radius ($strengthBadgeHeight / 2)
        $strengthPen = [System.Drawing.Pen]::new($accentColor, 1)

        try {
            $graphics.FillPath($surfaceBrush, $cardPath)
            $graphics.DrawPath($borderPen, $cardPath)
            $graphics.FillRectangle($accentBrush, $x, $y + 16, 4, 44)
            $graphics.FillPath($trackBrush, $strengthBadgePath)
            $graphics.DrawPath($strengthPen, $strengthBadgePath)
            $strengthTextX = $strengthBadgeX + (($strengthBadgeWidth - $strengthSize.Width) / 2)
            $strengthTextY = $strengthBadgeY + (($strengthBadgeHeight - $strengthSize.Height) / 2) - 1
            $graphics.DrawString($model.Strength, $strengthFont, $accentBrush, $strengthTextX, $strengthTextY)

            $nameX = $x + $cardPadding
            $nameRight = $strengthBadgeX - 8
            $nameWidth = [Math]::Max(32, $nameRight - $nameX)
            $nameRect = [System.Drawing.RectangleF]::new($nameX, $y + 14, $nameWidth, 40)
            $graphics.DrawString($model.Name, $nameFont, $textBrush, $nameRect)

            $score = $model.IQ.ToString('0.0', [Globalization.CultureInfo]::InvariantCulture)
            $graphics.DrawString($score, $valueFont, $textBrush, $x + $cardPadding - 2, $y + 54)
            $scoreSize = $graphics.MeasureString($score, $valueFont)
            $graphics.DrawString('IQ', $unitFont, $mutedBrush, $x + $cardPadding + $scoreSize.Width, $y + 84)

            $barX = $x + $cardPadding
            $barY = $y + 134
            $barWidth = $cardWidth - ($cardPadding * 2)
            $graphics.FillRectangle($trackBrush, $barX, $barY, $barWidth, 5)
            $fillWidth = [Math]::Max(3, [Math]::Round($barWidth * ($model.IQ / 120.0)))
            $graphics.FillRectangle($accentBrush, $barX, $barY, $fillWidth, 5)
        }
        finally {
            $strengthPen.Dispose()
            $strengthBadgePath.Dispose()
            $cardPath.Dispose()
            $accentBrush.Dispose()
        }
    }

    # The ranking section uses a stable, IQ-descending sort; original index breaks ties.
    $rankedModels = @($models | ForEach-Object -Begin { $sourceIndex = 0 } -Process {
        [pscustomobject]@{ Model = $_; SourceIndex = $sourceIndex }
        $sourceIndex++
    } | Sort-Object -Property @{ Expression = { $_.Model.IQ }; Descending = $true }, @{ Expression = { $_.SourceIndex }; Descending = $false } | ForEach-Object { $_.Model })

    $rankingTitleY = 650
    $graphics.DrawString('模型排行', $titleFont, $textBrush, 30, $rankingTitleY)
    $graphics.DrawString('按 IQ 从高到低 · 同分保持原始顺序', $metaFont, $mutedBrush, 245, $rankingTitleY + 9)

    $rankingStartY = 715
    $rankRowHeight = 43
    $rankNameX = 76
    $rankBarX = 300
    $rankBarWidth = 865
    $rankValueX = 1204
    $rankNameFormat = [System.Drawing.StringFormat]::new()
    $rankNameFormat.FormatFlags = [System.Drawing.StringFormatFlags]::NoWrap
    $rankNameFormat.Trimming = [System.Drawing.StringTrimming]::EllipsisCharacter
    $axisPen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(150, [System.Drawing.ColorTranslator]::FromHtml('#6E7681')), 1)
    $axisTicks = @(0, 20, 40, 60, 80, 100, 120)
    $axisTopY = $rankingStartY - 4
    $axisBottomY = $rankingStartY + (($rankedModels.Count - 1) * $rankRowHeight) + 33
    foreach ($tick in $axisTicks) {
        $tickX = $rankBarX + (($rankBarWidth * $tick) / 120.0)
        $graphics.DrawLine($axisPen, [float]$tickX, $axisTopY, [float]$tickX, $axisBottomY)
        $tickLabel = [string]$tick
        $tickSize = $graphics.MeasureString($tickLabel, $rankTickFont)
        $graphics.DrawString($tickLabel, $rankTickFont, $mutedBrush, [float]($tickX - ($tickSize.Width / 2)), $rankingStartY - 31)
    }

    $rankScoreFont = [System.Drawing.Font]::new('Segoe UI', 30, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    for ($rankIndex = 0; $rankIndex -lt $rankedModels.Count; $rankIndex++) {
        $ranked = $rankedModels[$rankIndex]
        $rankY = $rankingStartY + ($rankIndex * $rankRowHeight)
        $rankNumber = $rankIndex + 1
        $accentColor = $familyColors[$ranked.Family]
        $accentBrush = [System.Drawing.SolidBrush]::new($accentColor)
        $rankBadgePen = [System.Drawing.Pen]::new($accentColor, 1)
        $rankBadgePath = New-RoundedRectanglePath -X 30 -Y ($rankY + 7) -Width 32 -Height 27 -Radius 13.5
        try {
            $graphics.FillPath($trackBrush, $rankBadgePath)
            $graphics.DrawPath($rankBadgePen, $rankBadgePath)
            $rankTextSize = $graphics.MeasureString([string]$rankNumber, $unitFont)
            $graphics.DrawString([string]$rankNumber, $unitFont, $accentBrush, 30 + ((32 - $rankTextSize.Width) / 2), $rankY + 11)
            $rankName = '{0} {1}' -f $ranked.Name, $ranked.Strength
            $rankNameRect = [System.Drawing.RectangleF]::new($rankNameX, $rankY + 8, $rankBarX - $rankNameX - 14, 30)
            $graphics.DrawString($rankName, $nameFont, $textBrush, $rankNameRect, $rankNameFormat)
            $graphics.FillRectangle($trackBrush, $rankBarX, $rankY + 18, $rankBarWidth, 7)
            $rankFillWidth = [Math]::Max(3, [Math]::Round($rankBarWidth * ($ranked.IQ / 120.0)))
            $graphics.FillRectangle($accentBrush, $rankBarX, $rankY + 18, $rankFillWidth, 7)
            $rankScore = $ranked.IQ.ToString('0.0', [Globalization.CultureInfo]::InvariantCulture)
            $graphics.DrawString($rankScore, $rankScoreFont, $textBrush, $rankValueX, $rankY + 2)
        }
        finally {
            $rankBadgePath.Dispose()
            $rankBadgePen.Dispose()
            $accentBrush.Dispose()
        }
    }
    $rankScoreFont.Dispose()
    $axisPen.Dispose()
    $rankNameFormat.Dispose()

    $footerY = 1640
    $graphics.DrawLine($borderPen, 32, $footerY, 1368, $footerY)
    $graphics.DrawString('IQ 参考尺度 0-120', $metaFont, $mutedBrush, 32, $footerY + 20)
    $graphics.DrawString('来源 codexradar.com  |  实时抓取', $metaFont, $mutedBrush, 1012, $footerY + 20)

    $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
}
finally {
    $titleFont.Dispose()
    $eyebrowFont.Dispose()
    $metaFont.Dispose()
    $nameFont.Dispose()
    $strengthFont.Dispose()
    $rankTickFont.Dispose()
    $valueFont.Dispose()
    $unitFont.Dispose()
    $borderPen.Dispose()
    $backgroundBrush.Dispose()
    $surfaceBrush.Dispose()
    $textBrush.Dispose()
    $mutedBrush.Dispose()
    $trackBrush.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}

Write-Output $outputPath
