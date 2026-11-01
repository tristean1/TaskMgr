@chcp 65001 >nul
@echo off
setlocal
cd /d "%~dp0"
set "CP=%USERPROFILE%\.m2\repository\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar;%USERPROFILE%\.m2\repository\org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar;%~dp0lib\jline-3.27.1.jar"
if not exist target\classes mkdir target\classes
javac --add-modules jdk.httpserver -encoding UTF-8 -cp "%CP%" -d target\classes src\main\java\*.java
if errorlevel 1 exit /b 1
if exist src\main\resources xcopy /Y /I /Q src\main\resources\* target\classes\ >nul
echo EXIT=0
exit /b 0