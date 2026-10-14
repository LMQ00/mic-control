#!/bin/sh
#
# Gradle 启动脚本（精简版，够本项目用）。
# 项目只在 GitHub Actions 上构建，本地不编译，因此这里不追求覆盖所有边界情况。

set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=$(command -v java) || { echo "找不到 java，请先安装 JDK 17" >&2; exit 1; }
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# 按 Gradle 的约定取默认 JVM 参数，未设置时给一个保守值
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"
JAVA_OPTS="${JAVA_OPTS:-$DEFAULT_JVM_OPTS}"

exec "$JAVACMD" $JAVA_OPTS \
    -Dorg.gradle.appname=gradlew \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
