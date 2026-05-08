@echo off
REM
REM 下载 Memory Eval Harness 使用的开源基准数据集（Windows 版）。
REM
REM 支持的数据集：
REM   - LoCoMo (snap-research/locomo, Apache-2.0)
REM   - LongMemEval (xiaowu0162/LongMemEval, MIT, ICLR 2025)
REM
REM 用法：
REM   scripts\download-eval-datasets.cmd          REM 首次下载
REM   scripts\download-eval-datasets.cmd --force  REM 强制重下
REM
REM @author zsg
REM @since 2026-05-09

setlocal enabledelayedexpansion

set "CACHE_DIR=%LIFEPILOT_EVAL_CACHE%"
if "%CACHE_DIR%"=="" set "CACHE_DIR=%USERPROFILE%\.zhiwei\eval-cache"

set "FORCE=0"
if "%~1"=="--force" set "FORCE=1"
if "%~1"=="-h" goto :help
if "%~1"=="--help" goto :help

where git >nul 2>&1
if errorlevel 1 (
    echo 错误: 需要 git 命令。请先安装 git。 >&2
    exit /b 1
)

if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
echo 缓存目录: %CACHE_DIR%

call :download_git_repo "locomo" "https://github.com/snap-research/locomo.git" "data\locomo10.json"
if errorlevel 1 exit /b %ERRORLEVEL%

call :download_git_repo "longmemeval" "https://github.com/xiaowu0162/LongMemEval.git" "data\longmemeval_s.json"
if errorlevel 1 exit /b %ERRORLEVEL%

REM 兼容 Loader 默认路径：如果只有 data/longmemeval_s.json，复制一份到根
if exist "%CACHE_DIR%\longmemeval\data\longmemeval_s.json" (
    if not exist "%CACHE_DIR%\longmemeval\longmemeval_s.json" (
        copy /Y "%CACHE_DIR%\longmemeval\data\longmemeval_s.json" "%CACHE_DIR%\longmemeval\longmemeval_s.json" >nul
    )
)

echo.
echo 所有数据集下载完成。运行 'mvn test -Pmemory-eval-quick' 开始首次评估。
exit /b 0

:download_git_repo
set "NAME=%~1"
set "REPO=%~2"
set "CHECK=%~3"
set "DEST=%CACHE_DIR%\%NAME%"

if exist "%DEST%" if "%FORCE%"=="0" (
    echo [%NAME%] 已存在，跳过。使用 --force 强制重新下载。
    exit /b 0
)
if exist "%DEST%" if "%FORCE%"=="1" (
    echo [%NAME%] 清理已有目录...
    rmdir /S /Q "%DEST%"
)

echo [%NAME%] 克隆 %REPO% ...
git clone --depth 1 %REPO% "%DEST%"
if errorlevel 1 (
    echo [%NAME%] 克隆失败。网络不通？ >&2
    exit /b 3
)
if not "%CHECK%"=="" (
    if not exist "%DEST%\%CHECK%" (
        echo [%NAME%] 警告：关键文件 %CHECK% 未找到。 >&2
    ) else (
        echo [%NAME%] 校验通过：%CHECK% 存在
    )
)
exit /b 0

:help
echo 用法: %0 [--force]
echo.
echo 选项：
echo   --force        强制重新下载
echo.
echo 环境变量：
echo   LIFEPILOT_EVAL_CACHE   自定义缓存目录
exit /b 0
