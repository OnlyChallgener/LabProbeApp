param([Parameter(Mandatory=$true)][string]$Source)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$repo = Split-Path -Parent $root
$dest = Join-Path $PSScriptRoot 'assets/hub-brandicons'
$archive = Join-Path $PSScriptRoot 'asset-sources/device-icons-20261113'
New-Item -ItemType Directory -Path $archive -Force | Out-Null
$sourceImage = [System.Drawing.Bitmap]::FromFile($Source)
try {
    Copy-Item -LiteralPath $Source -Destination (Join-Path $archive 'generated-transparent-pair.png') -Force
    foreach ($spec in @(@('device_lock',0,430),@('device_bathheater',430,($sourceImage.Width-430)))) {
        $name = $spec[0]; $left = [int]$spec[1]; $width = [int]$spec[2]
        $x0=$sourceImage.Width; $y0=$sourceImage.Height; $x1=0; $y1=0
        for ($y=0; $y -lt $sourceImage.Height; $y++) {
            for ($x=$left; $x -lt ($left+$width); $x++) {
                if ($sourceImage.GetPixel($x,$y).A -ge 32) {
                    $x0=[Math]::Min($x0,$x); $x1=[Math]::Max($x1,$x)
                    $y0=[Math]::Min($y0,$y); $y1=[Math]::Max($y1,$y)
                }
            }
        }
        if ($x1 -le $x0 -or $y1 -le $y0) { throw "Empty transparent product: $name" }
        $output = New-Object System.Drawing.Bitmap 256,256
        $graphics = [System.Drawing.Graphics]::FromImage($output)
        try {
            $graphics.Clear([System.Drawing.Color]::Transparent)
            $graphics.InterpolationMode=[System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.CompositingQuality=[System.Drawing.Drawing2D.CompositingQuality]::HighQuality
            $w=$x1-$x0+1; $h=$y1-$y0+1; $scale=228.0/[Math]::Max($w,$h)
            $dw=[int]($w*$scale); $dh=[int]($h*$scale)
            $targetRect=New-Object System.Drawing.Rectangle ([int]((256-$dw)/2)),([int]((256-$dh)/2)),$dw,$dh
            $graphics.DrawImage($sourceImage,$targetRect,$x0,$y0,$w,$h,[System.Drawing.GraphicsUnit]::Pixel)
            $path=Join-Path $dest ($name+'.png')
            $originalPath=Join-Path $archive ($name+'-original.png')
            if ((Test-Path -LiteralPath $path) -and !(Test-Path -LiteralPath $originalPath)) { Copy-Item -LiteralPath $path -Destination $originalPath }
            $output.Save($path,[System.Drawing.Imaging.ImageFormat]::Png)
            Write-Output "Exported transparent 256px asset: $name"
        } finally { $graphics.Dispose(); $output.Dispose() }
    }
} finally { $sourceImage.Dispose() }
