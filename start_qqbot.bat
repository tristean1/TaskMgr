@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

REM ============================================================
REM  start_qqbot.bat —— 一键启动 QqBot（WebSocket 模式）
REM  作用：校验环境 -> 编译 -> 启动 QqBot 主类
REM  用法：双击运行，或在命令行执行 start_qqbot.bat
REM ============================================================

set "PROJ=D:\Java\TaskMgr"
set "M2=%USERPROFILE%\.m2\repository"
set "OUT=%PROJ%\target\classes"

set "GSON=%M2%\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar"
set "SQLITE=%M2%\org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar"
set "SLF4J=%M2%\org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar"

echo ============================================================
echo   QqBot 启动器
echo ============================================================
echo.

REM ---------- 1. 工程目录 ----------
if not exist "%PROJ%" (
    echo [错误] 找不到工程目录: %PROJ%
    goto :fail
)
cd /d "%PROJ%"
echo [1/5] 工程目录 OK: %PROJ%

REM ---------- 2. 依赖 jar ----------
for %%J in ("%GSON%" "%SQLITE%" "%SLF4J%") do (
    if not exist "%%~J" (
        echo [错误] 缺少依赖 jar: %%~J
        echo        请先在该工程下执行一次: mvn dependency:resolve
        goto :fail
    )
)
echo [2/5] 依赖 jar 全部就位

REM ---------- 3. 配置文件 ----------
set "CONF=%PROJ%\src\main\resources\qqbot.properties"
if not exist "%CONF%" (
    echo [错误] 缺少配置文件: %CONF%
    goto :fail
)
findstr /C:"appsecret=" "%CONF%" | findstr /R /C:"appsecret=." >nul
if errorlevel 1 (
    echo [警告] qqbot.properties 里 appsecret 为空，将无法连接 QQ。
    echo        请填入 AppSecret 后重试。按任意键继续，或 Ctrl+C 退出。
    pause >nul
)
echo [3/5] 配置文件 OK

REM ---------- 4. 编译 ----------
echo [4/5] 正在编译...
if not exist "%OUT%" mkdir "%OUT%" >nul 2>&1

copy /Y "%CONF%" "%OUT%\qqbot.properties" >nul

javac -encoding UTF-8 --add-modules jdk.httpserver ^
  -cp "%GSON%;%SQLITE%;%SLF4J%" ^
  -d "%OUT%" ^
  "%PROJ%\src\main\java\*.java"

if errorlevel 1 (
    echo [错误] 编译失败，请看上面的 javac 报错。
    goto :fail
)
echo      编译成功

REM ---------- 5. 启动 ----------
echo [5/5] 启动 QqBot（WebSocket 模式）...
echo ------------------------------------------------------------
echo  连接成功后，在 QQ 里私聊你的机器人发命令测试。
echo  按 Ctrl+C 停止。
echo ------------------------------------------------------------
echo.

java "-Dfile.encoding=UTF-8" -cp "%OUT%;%GSON%;%SQLITE%;%SLF4J%" QqBot

echo.
echo [QqBot 已退出] 退出码=%ERRORLEVEL%
pause
exit /b 0

:fail
echo.
echo 启动失败。
pause
exit /b 1
