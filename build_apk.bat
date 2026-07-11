@echo off
REM Builds the debug APK. Copies the freshly built libmain.so from the local CMake
REM tree into jniLibs first (the gradle project deliberately has no dependency on
REM the CMake build; see android-apk/app/build.gradle).
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
set "REPO=C:\Users\ITSeniy\source\UnleashedRecompAndroid"
if exist "%REPO%\out\build\android-arm64-verify\UnleashedRecomp\libmain.so" (
    copy /Y "%REPO%\out\build\android-arm64-verify\UnleashedRecomp\libmain.so" "%REPO%\android-apk\app\src\main\jniLibs\arm64-v8a\libmain.so"
) else (
    echo WARNING: no freshly built libmain.so in out\build\android-arm64-verify, packaging the existing jniLibs copy.
)
cd /d "%REPO%\android-apk"
call "%REPO%\android-apk\gradlew.bat" assembleDebug
