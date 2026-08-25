@echo off
rem ============================================
rem HFcatDurabilityAlert 一键构建脚本
rem Gradle 8.12 必须用 JDK 21 启动（不支持 JDK 25）
rem 编译由 toolchain 自动调用 JDK 25（通过 -D 传入路径，与 gradle.properties 一致）
rem ============================================
setlocal
set "JAVA_HOME=F:\env\jdk\azul-21.0.11"
cd /d "%~dp0"
call gradlew.bat shadowJar -Dorg.gradle.java.installations.paths=F:/env/jdk/azul-25.0.3,F:/env/jdk/azul-21.0.11 %*
if errorlevel 1 (
    echo.
    echo [构建失败] 请检查 JDK 安装路径（build.bat 中的 JAVA_HOME 与 -D 参数）
    exit /b 1
)
echo.
echo [构建成功] build\libs\HFcatDurabilityAlert-1.0.0.jar
endlocal
