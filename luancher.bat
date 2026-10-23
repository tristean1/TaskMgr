@echo off
chcp 65001 >nul
REM 历史遗留的启动器文件名（luancher = launcher 拼写错误）。
REM 统一转给 start_qqbot.bat，避免重复维护 classpath；那里用 %~dp0 解析路径，cmd 可直接运行。
call "%~dp0start_qqbot.bat"
