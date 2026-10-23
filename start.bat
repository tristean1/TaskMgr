@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
title TaskMgr 一键启动

REM ======================================================================
REM  start.bat  TaskMgr 一键启动
REM
REM  用法
REM    双击                弹出菜单，15 秒无操作默认选 1
REM    start.bat 1         控制台模式   Main          桌面提醒 + 命令输入
REM    start.bat 2         QQ 机器人    QqBot         桌面提醒 + QQ 推送 + 手机发命令
REM    start.bat 3         静默守护     ReminderMain  只弹提醒、无输入（开机自启用）
REM    start.bat build     只重新编译后退出（排查用）
REM
REM  提醒：同一个提醒不要在两个窗口里同时跑，会重复弹窗。
REM ======================================================================

set "PROJ=%~dp0"
if "%PROJ:~-1%"=="\" set "PROJ=%PROJ:~0,-1%"
set "SRC=%PROJ%\src\main\java"
set "RES=%PROJ%\src\main\resources"
set "OUT=%PROJ%\target\classes"
set "M2=%USERPROFILE%\.m2\repository"
set "GSON=%M2%\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar"
set "SQLITE=%M2%\org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar"
set "SLF4J=%M2%\org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar"
set "CP=%OUT%;%GSON%;%SQLITE%;%SLF4J%"
set "ARGS=%TEMP%\taskmgr_sources.txt"
set "DB=%USERPROFILE%\.taskmgr\tasks.db"

echo ============================================================
echo   TaskMgr 一键启动
echo ============================================================
echo.

REM ---------- 0. 路径不能含空格（-cp / javac @argfile 都按空格切分） ----------
if not "%PROJ%"=="%PROJ: =%" (
    echo [错误] 工程路径含空格，本脚本不支持：%PROJ%
    echo        请把工程放到不含空格的路径下，例如 D:\Java\TaskMgr
    goto :fail
)

REM ---------- 1. java / javac ----------
where java >nul 2>&1
if errorlevel 1 (
    echo [错误] 找不到 java。请安装 JDK 17，并把 bin 目录加入 PATH。
    goto :fail
)
where javac >nul 2>&1
if errorlevel 1 (
    echo [错误] 找不到 javac。需要 JDK，不能只用 JRE。
    goto :fail
)
echo [1/4] Java 环境 OK

REM ---------- 2. 依赖 jar ----------
for %%J in ("%GSON%" "%SQLITE%" "%SLF4J%") do (
    if not exist "%%~J" (
        echo [错误] 缺少依赖 jar：%%~J
        echo        请先在工程目录执行一次：mvn dependency:resolve
        goto :fail
    )
)
echo [2/4] 依赖 jar 就位

if /i "%~1"=="build"   goto :buildonly
if /i "%~1"=="rebuild" goto :buildonly

REM ---------- 3. 选择模式 ----------
set "MODE="
if "%~1"=="1" set "MODE=1"
if "%~1"=="2" set "MODE=2"
if "%~1"=="3" set "MODE=3"
if not defined MODE (
    echo [3/4] 选择启动模式
    echo       1 = 控制台模式   桌面提醒 + 命令输入（默认）
    echo       2 = QQ 机器人    桌面提醒 + QQ 推送 + 手机发命令
    echo       3 = 静默守护     只弹提醒、无输入（给开机自启用）
    echo.
    choice /c 123 /n /t 15 /d 1 /m "       请选择 1 / 2 / 3，15 秒无操作默认 1： "
    set "MODE=!errorlevel!"
)
REM choice 在输入被重定向时会失败并返回 255，这里兜底成控制台模式
if not "!MODE!"=="1" if not "!MODE!"=="2" if not "!MODE!"=="3" (
    echo       菜单不可用（输入可能被重定向），按默认模式 1 继续。
    set "MODE=1"
)
echo [3/4] 模式 = !MODE!

REM ---------- 4. 编译 ----------
call :compile
if errorlevel 1 goto :fail

if "!MODE!"=="1" set "MAINCLASS=Main"
if "!MODE!"=="2" set "MAINCLASS=QqBot"
if "!MODE!"=="3" set "MAINCLASS=ReminderMain"

echo.
echo ------------------------------------------------------------
echo  数据库：%DB%
if "!MODE!"=="1" (
    echo  控制台模式：桌面提醒已随程序启动。
    echo  需要 QQ 推送时，在提示符里输入： qqbot up
    echo  退出程序：输入 exit
)
if "!MODE!"=="2" (
    echo  QQ 机器人模式：连接成功后，用手机 QQ 私聊机器人即可发命令。
    echo  按 Ctrl+C 停止。
)
if "!MODE!"=="3" (
    echo  静默守护模式：只做桌面提醒，这个窗口关掉就停。
)
echo ------------------------------------------------------------
echo.

cd /d "%PROJ%"
if "!MAINCLASS!"=="Main" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJ%\start_console.ps1" -ClassPath "!CP!"
) else (
    java "-Dfile.encoding=UTF-8" -cp "!CP!" !MAINCLASS!
)
set "RC=!errorlevel!"

echo.
echo [已退出] 退出码 = !RC!
if "!MODE!"=="2" if "!RC!"=="0" echo  提示：退出码 0 且没有报错，通常是另一个 QqBot 实例正占着单实例锁。
pause
exit /b 0

REM ======================================================================
REM  只编译
REM ======================================================================
:buildonly
call :compile
if errorlevel 1 goto :fail
echo.
echo [完成] 编译结果在 %OUT%
pause
exit /b 0

REM ======================================================================
REM  编译子过程
REM ======================================================================
:compile
echo [4/4] 编译中（约 3 秒）...
REM 唯一编译入口：build.bat（编译全部源文件并复制 resources，避免各脚本各自维护文件列表）
call "%PROJ%\build.bat"
if errorlevel 1 (
    echo [错误] 编译失败，请看上面的 javac 报错。
    exit /b 1
)
echo       编译成功
exit /b 0

REM ======================================================================
REM  失败出口
REM ======================================================================
:fail
echo.
echo 启动失败。
pause
exit /b 1
