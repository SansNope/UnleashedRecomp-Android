@echo off
REM Incremental Android build (libmain.so) in the local out/build/android-arm64-verify tree.
REM Toolchain: VS18 Community CMake/Ninja + NDK 29 from %LOCALAPPDATA%\Android\Sdk.
set "PATH=C:\Windows\system32;C:\Windows;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Program Files\Git\cmd;C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja"
cd /d "C:\Users\ITSeniy\source\UnleashedRecompAndroid"
"C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" --build out/build/android-arm64-verify --target UnleashedRecomp
