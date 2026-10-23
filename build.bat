@echo off
cd /d "D:\Java\TaskMgr"
set CP=%USERPROFILE%\.m2\repository\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar;%USERPROFILE%\.m2\repository\org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar
if not exist target\classes mkdir target\classes
copy /Y src\main\resources\qqbot.properties target\classes\ >nul
javac --add-modules jdk.httpserver -encoding UTF-8 -cp "%CP%" -d target\classes src\main\java\Main.java src\main\java\TaskEngine.java src\main\java\QqBot.java src\main\java\QqWsClient.java src\main\java\ReminderService.java src\main\java\Autostart.java src\main\java\ReminderMain.java src\main\java\IcsExporter.java src\main\java\ProviderConfig.java src\main\java\CloudNlParser.java src\main\java\NlParser.java src\main\java\NlParseException.java src\main\java\ParsedTask.java src\main\java\ParsedTaskValidator.java src\main\java\ValidationException.java src\main\java\OpLogKit.java src\main\java\SingleInstanceLock.java src\main\java\WsSessionStore.java
echo EXIT=%ERRORLEVEL%