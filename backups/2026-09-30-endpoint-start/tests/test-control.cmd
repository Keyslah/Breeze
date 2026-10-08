@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul
if errorlevel 1 exit /b 1
cl /nologo /EHsc /std:c++17 /Fe:tests\control_test.exe /Fo:tests\control_test.obj tests\control_test.cpp
if errorlevel 1 exit /b 1
tests\control_test.exe
