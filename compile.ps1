$ErrorActionPreference = 'Continue'
$cp = "$env:USERPROFILE\.m2\repository\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar;$env:USERPROFILE\.m2\repository\org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar"
Remove-Item -Recurse -Force 'target\classes' -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force 'target\classes' | Out-Null
Copy-Item 'src\main\resources\qqbot.properties' 'target\classes\' -Force
javac -encoding UTF-8 -cp $cp -d 'target\classes' `
  'src\main\java\Main.java' `
  'src\main\java\TaskEngine.java' `
  'src\main\java\QqBot.java' `
  'src\main\java\QqWsClient.java' `
  'src\main\java\ReminderService.java' `
  'src\main\java\Autostart.java' `
  'src\main\java\ReminderMain.java' `
  'src\main\java\IcsExporter.java' `
  'src\main\java\ParsedTask.java' `
  'src\main\java\NlParser.java' `
  'src\main\java\NlParseException.java' `
  'src\main\java\ParsedTaskValidator.java' `
  'src\main\java\ValidationException.java'
Write-Output ("EXITCODE=" + $LASTEXITCODE)