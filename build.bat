@echo off
cd /d "D:\Java\TaskMgr"
set CP=%USERPROFILE%\.m2\repository\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar;%USERPROFILE%\.m2\repository\org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar
if not exist target\classes mkdir target\classes
copy /Y src\main\resources\qqbot.properties target\classes\ >nul
javac --add-modules jdk.httpserver -encoding UTF-8 -cp "%CP%" -d target\classes src\main\java\Main.java src\main\java\TaskEngine.java src\main\java\QqBot.java src\main\java\QqWsClient.java src\main\java\ReminderService.java src\main\java\Autostart.java src\main\java\ReminderMain.java src\main\java\IcsExporter.java
echo EXIT=%ERRORLEVEL%