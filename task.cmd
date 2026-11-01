@echo off
rem task.cmd - launcher for the TaskMgr client (REQ-019 phase 1).
rem
rem IMPORTANT: the command text is passed through the environment variable
rem TASKCMDLINE, never through argv. On this machine (JDK 17.0.2, ACP=65001)
rem the launcher converts the command line to UTF-8 while the JVM decodes it
rem with sun.jnu.encoding=GBK, so Chinese arguments get corrupted. The Windows
rem environment block is UTF-16 and Java reads it with wide-char APIs, so
rem Chinese survives. See AGENTS.md rule 4 and REQUIREMENTS.md REQ-019.
setlocal
chcp 65001 >nul
set "TASKCMDLINE=%*"
set "ROOT=%~dp0"
set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "HOMEOPT="
if defined TASKMGR_HOME set "HOMEOPT=-Dtaskmgr.home=%TASKMGR_HOME%"
set "CP=%ROOT%target\classes;%USERPROFILE%\.m2\repository\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar;%USERPROFILE%\.m2\repository\org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar;%ROOT%lib\jline-3.27.1.jar"
"%JAVA_EXE%" -Dfile.encoding=UTF-8 %HOMEOPT% -cp "%CP%" TaskClient
exit /b %ERRORLEVEL%
