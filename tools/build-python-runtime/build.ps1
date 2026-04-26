# 构建 Windows 平台的知微 Python runtime tarball
# 用法: build.ps1 -Version <python-version> -Platform windows -Arch x86_64
param(
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$Platform,
    [Parameter(Mandatory=$true)][string]$Arch
)

$ErrorActionPreference = "Stop"

$OutDir = "dist"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# python-build-standalone 发布 tag，可通过环境变量 PBS_TAG 覆盖
$PbsTag = if ($env:PBS_TAG) { $env:PBS_TAG } else { "20260414" }

# Windows 当前命名约定: x86_64-pc-windows-msvc（旧版本曾有 -shared 后缀，新版本已统一）
$PbsTriple = switch ("$Platform-$Arch") {
    "windows-x86_64" { "x86_64-pc-windows-msvc" }
    "windows-arm64"  { "aarch64-pc-windows-msvc" }
    default { throw "不支持的平台组合: $Platform-$Arch" }
}

$PbsUrl = "https://github.com/astral-sh/python-build-standalone/releases/download/$PbsTag/cpython-$Version+$PbsTag-$PbsTriple-install_only.tar.gz"

Write-Host "下载 python-build-standalone: $PbsUrl"
Invoke-WebRequest -Uri $PbsUrl -OutFile "pbs.tar.gz"

New-Item -ItemType Directory -Force -Path "extract" | Out-Null

# Windows 10 1803+ 自带 BSD tar
tar -xzf pbs.tar.gz -C extract
$PythonDir = "extract\python"

if (-not (Test-Path "$PythonDir\python.exe")) {
    throw "解压后未找到 $PythonDir\python.exe"
}

Write-Host "安装预装库 (requirements.txt)"
& "$PythonDir\python.exe" -m pip install --no-cache-dir --upgrade pip
& "$PythonDir\python.exe" -m pip install --no-cache-dir -r "tools\build-python-runtime\requirements.txt"
if ($LASTEXITCODE -ne 0) { throw "pip install 失败 (exit=$LASTEXITCODE)" }

# 写 VERSION 文件
"$Version" | Out-File -Encoding ASCII -NoNewline "$PythonDir\VERSION"

# 打 tar.zst（依赖 zstd.exe，CI 通过 choco 安装）
$Tarball = "$OutDir\zhiwei-python-runtime-$Version-$Platform-$Arch.tar.zst"
Write-Host "压缩为 $Tarball"
tar -cf - -C extract python | zstd -19 -o $Tarball
if ($LASTEXITCODE -ne 0) { throw "tar | zstd 失败 (exit=$LASTEXITCODE)" }

# 算 SHA-256
$Hash = (Get-FileHash -Algorithm SHA256 $Tarball).Hash.ToLower()
$Hash | Out-File -Encoding ASCII -NoNewline "$Tarball.sha256"

Write-Host "已生成 $Tarball"
Get-Item $Tarball, "$Tarball.sha256" | Format-List Length, FullName
