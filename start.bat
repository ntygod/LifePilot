@echo off
chcp 65001 >nul 2>&1

REM 1. 检测 java 命令
where java >nul 2>&1
if errorlevel 1 (
    echo 错误: 未找到 java 命令
    echo 请安装 Java 22 或更高版本: https://adoptium.net/
    exit /b 1
)

REM 2. 检测 Java 版本 >= 22
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
for /f "tokens=1 delims=." %%m in ("%JAVA_VER%") do set "JAVA_MAJOR=%%m"
if "%JAVA_MAJOR%"=="" (
    echo 错误: 无法解析 Java 版本
    exit /b 1
)
if %JAVA_MAJOR% LSS 22 (
    echo 错误: 当前 Java 版本为 %JAVA_MAJOR%，要求 22 或更高
    exit /b 1
)

REM 3. 创建数据目录
if not exist "%USERPROFILE%\.zhiwei" mkdir "%USERPROFILE%\.zhiwei"

REM 4. 计算 JVM 内存参数（系统内存 50%，上限 2048MB）
set "XMX=512"
for /f "tokens=2 delims==" %%m in ('wmic OS get TotalVisibleMemorySize /value ^| findstr "="') do set "TOTAL_MEM_KB=%%m"
if not "%TOTAL_MEM_KB%"=="" (
    set /a TOTAL_MEM_MB=%TOTAL_MEM_KB% / 1024
    set /a XMX=%TOTAL_MEM_MB% / 2
    if %XMX% GTR 2048 set XMX=2048
)

echo 使用 JVM 最大内存: %XMX%m

REM 5. 启动应用
java -Xmx%XMX%m -jar "%~dp0zhiwei.jar"

