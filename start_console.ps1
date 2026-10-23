param(
    [Parameter(Mandatory = $true)][string]$ClassPath,
    [string]$MainClass = 'Main',
    [string[]]$ApplicationArgs = @()
)

$ErrorActionPreference = 'Stop'
$previousInputEncoding = [Console]::InputEncoding
$previousOutputEncoding = [Console]::OutputEncoding
$consoleExitCode = 1
try {
    # JDK 17.0.2 + Windows: CP65001 console byte reads lose Chinese characters.
    # Console.reader() uses CP936. JDK 17 also derives stdout/stderr from
    # that input code page, so file.encoding alone does NOT keep output UTF-8.
    # Redirected input remains UTF-8 and must not be converted.
    if (-not [Console]::IsInputRedirected) {
        [Console]::InputEncoding = [Text.Encoding]::GetEncoding(936)
    }
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    & java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $ClassPath $MainClass @ApplicationArgs
    $consoleExitCode = $LASTEXITCODE
} finally {
    [Console]::InputEncoding = $previousInputEncoding
    [Console]::OutputEncoding = $previousOutputEncoding
}
exit $consoleExitCode
