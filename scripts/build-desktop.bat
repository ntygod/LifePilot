@echo off
REM =============================================================================
REM ZhiWei 桌面端构建脚本（Windows）
REM
REM 用法:
REM   scripts\build-desktop.bat              完整构建
REM   scripts\build-desktop.bat --skip-jar   跳过 JAR 构建
REM   scripts\build-desktop.bat --skip-jre   跳过 JRE 构建
REM   scripts\build-desktop.bat --dev        仅准备开发环境
REM =============================================================================
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."
set "TAURI_DIR=%PROJECT_ROOT%\zhiwei-web\src-tauri"
set "RESOURCES_DIR=%TAURI_DIR%\resources"
set "FRONTEND_DIR=%PROJECT_ROOT%\zhiwei-web"
set "MODULES_FILE=%SCRIPT_DIR%jlink-modules.txt"

set "SKIP_JAR=false"
set "SKIP_JRE=false"
set "DEV_ONLY=false"

:parse_args
if "%~1"=="" goto start
if /i "%~1"=="--skip-jar" set "SKIP_JAR=true"
if /i "%~1"=="--skip-jre" set "SKIP_JRE=true"
if /i "%~1"=="--dev" set "DEV_ONLY=true"
shift
goto parse_args

:start
echo ======================================
echo   ZhiWei 桌面端构建
echo ======================================

REM ------- 1. 环境检查 -------
echo.
echo [1/5] 环境检查...

where java >nul 2>&1 || ( echo 错误: 未找到 java & exit /b 1 )
where mvn >nul 2>&1 || ( echo 错误: 未找到 mvn & exit /b 1 )
where node >nul 2>&1 || ( echo 错误: 未找到 node & exit /b 1 )
where cargo >nul 2>&1 || ( echo 错误: 未找到 cargo & exit /b 1 )

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "JAVA_VER_RAW=%%~v"
)
for /f "tokens=1 delims=." %%m in ("%JAVA_VER_RAW%") do set "JAVA_MAJOR=%%m"
if %JAVA_MAJOR% LSS 22 (
    echo 错误: Java 版本 %JAVA_MAJOR%，需要 22+
    exit /b 1
)

if not defined JAVA_HOME (
    for /f "delims=" %%p in ('where java') do set "JAVA_HOME=%%~dpp.."
)

echo   Java %JAVA_MAJOR% (%JAVA_HOME%)

REM ------- 2. 构建后端 JAR -------
if "%SKIP_JAR%"=="true" (
    echo.
    echo [2/5] 跳过 JAR 构建
) else (
    echo.
    echo [2/5] 构建后端 JAR...
    cd /d "%PROJECT_ROOT%"
    call mvn clean package -DskipTests -q
    if errorlevel 1 ( echo JAR 构建失败 & exit /b 1 )
    echo   输出: target\zhiwei.jar
)

if not exist "%RESOURCES_DIR%" mkdir "%RESOURCES_DIR%"
if exist "%PROJECT_ROOT%\target\zhiwei.jar" (
    copy /y "%PROJECT_ROOT%\target\zhiwei.jar" "%RESOURCES_DIR%\zhiwei.jar" >nul
    echo   已复制到 %RESOURCES_DIR%\zhiwei.jar
) else if not exist "%RESOURCES_DIR%\zhiwei.jar" (
    echo 错误: target\zhiwei.jar 不存在且 resources 中无缓存，请先构建 JAR
    exit /b 1
)

REM ------- 3. 构建精简 JRE -------
if "%SKIP_JRE%"=="true" (
    echo.
    echo [3/5] 跳过 JRE 构建
) else (
    echo.
    echo [3/5] 构建精简 JRE ^(jlink^)...

    REM 解析模块列表
    set "MODULES="
    for /f "usebackq eol=# tokens=*" %%m in ("%MODULES_FILE%") do (
        if not "%%m"=="" (
            if "!MODULES!"=="" (
                set "MODULES=%%m"
            ) else (
                set "MODULES=!MODULES!,%%m"
            )
        )
    )

    set "JRE_OUTPUT=%RESOURCES_DIR%\jre"
    if exist "!JRE_OUTPUT!" rmdir /s /q "!JRE_OUTPUT!"

    "%JAVA_HOME%\bin\jlink.exe" ^
        --module-path "%JAVA_HOME%\jmods" ^
        --add-modules "!MODULES!" ^
        --no-header-files ^
        --no-man-pages ^
        --strip-debug ^
        --compress=zip-6 ^
        --output "!JRE_OUTPUT!"

    if errorlevel 1 ( echo JRE 构建失败 & exit /b 1 )
    echo   JRE 已生成: !JRE_OUTPUT!
    "!JRE_OUTPUT!\bin\java.exe" --version 2>&1 | findstr /i "version"
)

REM 仅开发模式
if "%DEV_ONLY%"=="true" (
    echo.
    echo 开发环境准备完成！
    echo 运行 'cd zhiwei-web ^&^& cargo tauri dev' 启动开发模式
    exit /b 0
)

REM ------- 4. 构建前端 -------
echo.
echo [4/5] 构建前端...
cd /d "%FRONTEND_DIR%"
call npm install --silent
call npm run build
if errorlevel 1 ( echo 前端构建失败 & exit /b 1 )
echo   输出: dist\

REM ------- 5. 构建 Tauri 安装包 -------
echo.
echo [5/5] 构建 Tauri 安装包...
cd /d "%FRONTEND_DIR%"
call npx tauri build
if errorlevel 1 ( echo Tauri 构建失败 & exit /b 1 )

echo.
echo ======================================
echo   构建完成！
echo ======================================
echo.
echo 安装包位置: %TAURI_DIR%\target\release\bundle\
dir /b "%TAURI_DIR%\target\release\bundle\nsis\*.exe" 2>nul

endlocal
