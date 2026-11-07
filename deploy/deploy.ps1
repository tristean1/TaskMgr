<#
.SYNOPSIS
    把项目部署到云服务器（编译 + 可选重启服务）。

.DESCRIPTION
    做的事只有四步，全部可重跑：
      1. 凑齐 jar（lib/ 下没有的从 ~/.m2 复制过来）；
      2. scp 源码、jar、构建脚本到服务器；
      3. 在服务器上跑 build.sh（javac --release 17）；
      4. 需要时安装/刷新 systemd 单元并重启。

    为什么不用「服务器上 git pull + post-receive 钩子」：那要多维护一个裸仓库和一段钩子脚本，
    而这里只有一台开发机推给一台服务器，scp + 远端编译是同样的效果、更少的活动部件。
    真要回滚，git 在本地库里，服务器上重跑一次本脚本即可。

.EXAMPLE
    .\deploy\deploy.ps1                     # 只部署+编译，不动正在跑的服务
    .\deploy\deploy.ps1 -Restart            # 部署完重启 systemd 服务
    .\deploy\deploy.ps1 -Target aliyun -RemoteDir /opt/taskmgr
#>
param(
    [string]$Target = "aliyun",
    [string]$RemoteDir = "/opt/taskmgr",
    [switch]$Restart
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # ---- 1. 凑 jar ---------------------------------------------------------
    $m2 = Join-Path $env:USERPROFILE ".m2\repository"
    $need = @(
        (Join-Path $m2 "com\google\code\gson\gson\2.11.0\gson-2.11.0.jar"),
        (Join-Path $m2 "org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar"),
        (Join-Path $m2 "org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar")
    )
    foreach ($jar in $need) {
        $dest = Join-Path "lib" (Split-Path -Leaf $jar)
        if (-not (Test-Path $dest)) {
            if (-not (Test-Path $jar)) { throw "找不到依赖 jar：$jar（先跑一次 build.bat）" }
            Copy-Item $jar $dest
            Write-Host "[deploy] 补齐 $dest"
        }
    }

    # ---- 2. 传文件 ---------------------------------------------------------
    Write-Host "[deploy] 传到 $Target`:$RemoteDir …"
    & ssh $Target "mkdir -p '$RemoteDir'"
    if ($LASTEXITCODE -ne 0) { throw "ssh 连不上 $Target" }
    & scp -r -q src lib deploy/build.sh deploy/task "$Target`:$RemoteDir/"
    if ($LASTEXITCODE -ne 0) { throw "scp 失败" }

    # ---- 3. 远端编译 -------------------------------------------------------
    Write-Host "[deploy] 远端编译 …"
    & ssh $Target "cd '$RemoteDir' && chmod +x build.sh task && bash build.sh"
    if ($LASTEXITCODE -ne 0) { throw "远端编译失败" }

    # ---- 4. 服务 -----------------------------------------------------------
    if ($Restart) {
        Write-Host "[deploy] 安装/刷新 systemd 单元并重启 …"
        & scp -q deploy/taskmgr.service "$Target`:/etc/systemd/system/taskmgr.service"
        if ($LASTEXITCODE -ne 0) { throw "上传 unit 失败" }
        & ssh $Target "systemctl daemon-reload && systemctl enable --now taskmgr && systemctl restart taskmgr && sleep 2 && systemctl is-active taskmgr"
        if ($LASTEXITCODE -ne 0) { throw "重启服务失败" }
    } else {
        Write-Host "[deploy] 未加 -Restart：服务没动，新代码等下次重启生效。"
    }

    Write-Host "[deploy] 完成。"
} finally {
    Pop-Location
}
