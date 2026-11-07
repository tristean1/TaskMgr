$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $timingOut = Join-Path $PSScriptRoot 'target/timing-check'
    $timingResults = Join-Path $PSScriptRoot 'target/timing-test-results'
    New-Item -ItemType Directory -Force $timingOut, $timingResults | Out-Null
    $timingCp = "$timingOut;$env:USERPROFILE\.m2\repository\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar;$env:USERPROFILE\.m2\repository\org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar;$env:USERPROFILE\.m2\repository\org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar;$(Join-Path $PSScriptRoot 'lib/jline-3.27.1.jar')"
    $timingSources = Get-ChildItem src/main/java/*.java, src/test/java/*.java | ForEach-Object FullName
    & javac '-J-Dfile.encoding=UTF-8' -encoding UTF-8 --add-modules jdk.httpserver -cp $timingCp -d $timingOut $timingSources
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & java '-Dfile.encoding=UTF-8' -cp $timingCp TimingTest $timingResults
    $timingExit = $LASTEXITCODE
    Get-Content -Encoding UTF8 (Join-Path $timingResults 'results.txt')
    if ($timingExit -ne 0) { throw 'Timing tests failed' }

    # 2026-10-21 bug 修复回归（BUG-008/009/010/011/012/013/014/015/016/017/018/022/023/025/026/027）
    $bugfixResults = Join-Path $timingResults 'bugfix-results.txt'
    # 注意：不要写 2>&1。SLF4J 会把告警打到 stderr，合并进管道后会被 PowerShell 当成
    # ErrorRecord，而本脚本 $ErrorActionPreference='Stop' 会因此中止（历史现象：本行后
    # 整个脚本 EXIT=1 且 bugfix-results.txt 为 0 字节）。stderr 直接放行到宿主即可。
    & java '-Dfile.encoding=UTF-8' -cp $timingCp BugfixTest | Out-File -FilePath $bugfixResults -Encoding utf8
    $bugfixExit = $LASTEXITCODE
    Get-Content -Encoding UTF8 $bugfixResults
    if ($bugfixExit -ne 0) { throw 'Bugfix tests failed' }
    # 2026-10-23 REQ-008（add 回执只回一条）+ REQ-004（next / 无参 done 的 C 口径）
    $nextResults = Join-Path $timingResults 'next-results.txt'
    & java '-Dfile.encoding=UTF-8' -cp $timingCp NextTest $timingResults
    $nextExit = $LASTEXITCODE
    Get-Content -Encoding UTF8 $nextResults
    if ($nextExit -ne 0) { throw 'Next / REQ-008 tests failed' }
    # 2026-10-26 REQ-016（push 平移任务时间）
    $pushResults = Join-Path $timingResults 'push-results.txt'
    & java '-Dfile.encoding=UTF-8' -cp $timingCp PushTest $timingResults
    $pushExit = $LASTEXITCODE
    Get-Content -Encoding UTF8 $pushResults
    if ($pushExit -ne 0) { throw 'Push / REQ-016 tests failed' }
    # 2026-10-28 REQ-017（set 修改已有任务的属性）
    $setResults = Join-Path $timingResults 'set-results.txt'
    & java '-Dfile.encoding=UTF-8' -cp $timingCp SetTest $timingResults
    $setExit = $LASTEXITCODE
    Get-Content -Encoding UTF8 $setResults
    if ($setExit -ne 0) { throw 'Set / REQ-017 tests failed' }
    # 2026-10-29 REQ-018（循环任务：周期推进 / add 解析 / 状态机 / 一次性回归）
    $recResults = Join-Path $timingResults 'recurrence-results.txt'
    & java '-Dfile.encoding=UTF-8' -cp $timingCp RecurrenceTest $timingResults
    $recExit = $LASTEXITCODE
    Get-Content -Encoding UTF8 $recResults
    if ($recExit -ne 0) { throw 'Recurrence / REQ-018 tests failed' }
    # 2026-11-01 REQ-019 第 2 期（QQ 托管进守护进程）
    # 注意：必须给 -Duser.home，否则单实例锁会写进真正的 ~/.taskmgr（污染用户目录）。
    $qqHostHome = Join-Path $env:TEMP 'tm-qqbot-host-home'
    New-Item -ItemType Directory -Force $qqHostHome | Out-Null
    $qqHostResults = Join-Path $timingResults 'qqbothost-results.txt'
    & java '-Dfile.encoding=UTF-8' "-Duser.home=$qqHostHome" -cp $timingCp QqBotHostTest $timingResults
    $qqHostExit = $LASTEXITCODE
    Get-Content -Encoding UTF8 $qqHostResults
    if ($qqHostExit -ne 0) { throw 'QqBotHost / REQ-019 tests failed' }
    $consoleResults = Join-Path $timingResults 'console-redirected.txt'
    & java '-Dfile.encoding=UTF-8' -cp $timingCp ConsoleInputTest $consoleResults
    if ($LASTEXITCODE -ne 0) { throw 'Console input tests failed' }
    Get-Content -Encoding UTF8 $consoleResults
    # 2026-11-06 REQ-020（task sync：快照 / 安装 / 拒绝条件）
    # 注意：SyncTest 自己用 -Dtaskmgr.home 指到临时目录，不碰真库。
    $syncResults = Join-Path $timingResults 'sync-results.txt'
    & java '-Dfile.encoding=UTF-8' -cp $timingCp SyncTest $timingResults
    $syncExit = $LASTEXITCODE
    Get-Content -Encoding UTF8 $syncResults
    if ($syncExit -ne 0) { throw 'Sync / REQ-020 tests failed' }
} finally {
    Pop-Location
}
