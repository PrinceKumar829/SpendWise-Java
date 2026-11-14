@echo off
rem Runs all tests (no MySQL needed). Windows.
cd /d "%~dp0"
if exist out rmdir /s /q out
if exist testout rmdir /s /q testout
mkdir out
mkdir testout
dir /s /b backend\src\main\java\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
if errorlevel 1 exit /b 1
dir /s /b backend\src\test\java\*.java > test-sources.txt
javac -encoding UTF-8 -cp out -d testout @test-sources.txt
if errorlevel 1 exit /b 1
for %%t in (SplitServiceTest InsightServiceTest JsonTest FlowTest HttpSmokeTest) do java -cp out;testout spendwise.%%t
