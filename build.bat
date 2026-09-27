@echo off
rem ============================================
rem HFcatDurabilityAlert 一键构建脚本（Windows）
rem 需要 JDK 21 或更高版本（Gradle 9 与插件编译都支持 21~25）
rem 产物: build\libs\HFcatDurabilityAlert-<version>.jar
rem ============================================
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
    echo [提示] 未设置 JAVA_HOME，将使用 PATH 中的 java。若构建失败请设置 JAVA_HOME 指向 JDK 21+。
)

call gradlew.bat clean build %*
if errorlevel 1 (
    echo.
    echo [构建失败] 请确认已安装 JDK 21 或更高版本（JAVA_HOME 指向 JDK 安装目录）。
    exit /b 1
)

echo.
echo [构建成功] 产物位于 build\libs\
dir /b build\libs\*.jar
endlocal
