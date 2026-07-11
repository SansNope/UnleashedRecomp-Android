@echo off
REM Incremental Android build that also survives a CMake reconfigure.
REM Unlike build_android_target.bat, this sets ANDROID_NDK_HOME/VCPKG_ROOT so that
REM when CMakeLists.txt changes (forcing vcpkg to re-detect the arm64-android
REM compiler) the NDK is still found. Otherwise vcpkg falls back to a default
REM ndk-bundle path and the reconfigure fails.
set "PATH=C:\Windows\system32;C:\Windows;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Program Files\Git\cmd;C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja"
set "ANDROID_NDK_HOME=C:\Users\ITSeniy\AppData\Local\Android\Sdk\ndk\29.0.14206865"
set "ANDROID_NDK_ROOT=C:\Users\ITSeniy\AppData\Local\Android\Sdk\ndk\29.0.14206865"
set "VCPKG_ROOT=C:\Users\ITSeniy\source\UnleashedRecompAndroid\thirdparty\vcpkg"
cd /d "C:\Users\ITSeniy\source\UnleashedRecompAndroid"
"C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" --build out/build/android-arm64-verify --target UnleashedRecomp
