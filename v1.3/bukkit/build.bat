@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem ============================================================
rem  MCPanelRestart 构建脚本（JDK 17，不需要 Maven）
rem  PAPER_API_JAR 环境变量指向 paper-api jar，或放到 ..\paper-api.jar
rem ============================================================
set SRC=src\main\java
set OUT=build\classes
set RES=src\main\resources
set VERSION=1.1.0
set JAR=build\MCPanelRestart-%VERSION%.jar
set LIB=D:\666\bedwar\libraries

set API_JAR=%PAPER_API_JAR%
if not defined API_JAR if exist "..\paper-api.jar" set API_JAR=..\paper-api.jar
if not defined API_JAR set API_JAR=%LIB%\io\papermc\paper\paper-api\1.20.1-R0.1-SNAPSHOT\paper-api-1.20.1-R0.1-SNAPSHOT.jar

set CP="%API_JAR%"
for %%J in (
  "%LIB%\net\kyori\adventure-api\4.14.0\adventure-api-4.14.0.jar"
  "%LIB%\net\kyori\adventure-key\4.14.0\adventure-key-4.14.0.jar"
  "%LIB%\net\kyori\adventure-text-serializer-legacy\4.14.0\adventure-text-serializer-legacy-4.14.0.jar"
  "%LIB%\net\kyori\examination-api\1.3.0\examination-api-1.3.0.jar"
  "%LIB%\net\kyori\examination-string\1.3.0\examination-string-1.3.0.jar"
  "%LIB%\net\md-5\bungeecord-chat\1.20-R0.1-deprecated+build.14\bungeecord-chat-1.20-R0.1-deprecated+build.14.jar"
) do set CP=!CP!;%%~J

if not exist "%API_JAR%" (
  echo [错误] 找不到 paper-api：%API_JAR%
  pause
  exit /b 1
)

if exist build rmdir /s /q build
mkdir "%OUT%"

echo 正在编译...
for /r "%SRC%" %%f in (*.java) do echo %%f>>"%TEMP%\mcpanel_restart_sources.txt"
javac -encoding UTF-8 --release 17 -cp %CP% -d "%OUT%" "@%TEMP%\mcpanel_restart_sources.txt"
del "%TEMP%\mcpanel_restart_sources.txt" >nul 2>&1
if errorlevel 1 ( echo [错误] 编译失败 & pause & exit /b 1 )

xcopy /e /i /y /q "%RES%" "%OUT%" >nul
(echo off & setlocal enabledelayedexpansion & for /f "delims=" %%l in ('type "%OUT%\plugin.yml"') do (set "line=%%l" & set "line=!line:${project.version}=%VERSION%!" & echo !line!)) > "%OUT%\plugin.yml.tmp"
move /y "%OUT%\plugin.yml.tmp" "%OUT%\plugin.yml" >nul
if not exist build mkdir build
echo 打包 jar...
jar --create --file "%JAR%" -C "%OUT%" .
echo 完成：%JAR%
pause
