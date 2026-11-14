@echo off
rem Compiles the backend and starts SpendWise (Windows). Needs JDK 17+ and lib\mysql-connector-j-*.jar
cd /d "%~dp0"
if not exist out mkdir out
dir /s /b backend\src\main\java\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
if errorlevel 1 exit /b 1
java -cp "out;lib\*" spendwise.Server
