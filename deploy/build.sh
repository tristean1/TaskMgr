#!/usr/bin/env bash
# 在 Linux 上编译本项目（云服务器用）。
# Windows 上仍走 build.bat；两边编译的是同一份源码，class 产物不通用，各自编译各自的。
#
# 依赖：lib/ 下必须有 4 个 jar（gson / sqlite-jdbc / slf4j-api / jline）。
# 没有的话从本机 scp 上来，别指望服务器能从 Maven Central 下（实测可达但慢）。
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "$(ls -A lib/*.jar 2>/dev/null || true)" ]; then
  echo "[错误] lib/ 里没有 jar。先把本机 lib 目录整个传上来。" >&2
  exit 1
fi

CP=$(printf '%s:' lib/*.jar)
CP=${CP%:}

rm -rf classes
mkdir -p classes
# --release 17：服务器是 JDK 21，但产物要和本机 JDK 17 保持同一字节码版本，
# 免得出现「服务器编完考回本机跑不起来」这种隐性分裂。
javac --release 17 -encoding UTF-8 -cp "$CP" -d classes src/main/java/*.java

if [ -d src/main/resources ]; then
  cp -r src/main/resources/. classes/ 2>/dev/null || true
fi

echo "BUILD_OK 生成 $(find classes -name '*.class' | wc -l) 个 class"
