@echo off
REM Full reconfigure of the LOCAL Android build tree (out/build/android-arm64-verify).
REM vcpkg manifest install is skipped (VCPKG_MANIFEST_MODE=OFF): the prebuilt
REM arm64-android packages are reused from out\build\android-arm64\vcpkg_installed.
REM Do NOT delete out\build\android-arm64 - that E:-era tree can no longer build,
REM but it serves as the vcpkg package store for this configuration.
set "PATH=C:\Windows\system32;C:\Windows;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Program Files\Git\cmd;C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja"
set "ANDROID_NDK_HOME=C:\Users\ITSeniy\AppData\Local\Android\Sdk\ndk\29.0.14206865"
set "ANDROID_NDK_ROOT=C:\Users\ITSeniy\AppData\Local\Android\Sdk\ndk\29.0.14206865"
set "VCPKG_ROOT=C:\Users\ITSeniy\source\UnleashedRecompAndroid\thirdparty\vcpkg"
cd /d "C:\Users\ITSeniy\source\UnleashedRecompAndroid"
rmdir /s /q out\build\android-arm64-verify 2>nul
"C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" ^
  -S . -B out/build/android-arm64-verify -G Ninja ^
  -DCMAKE_TOOLCHAIN_FILE=thirdparty/vcpkg/scripts/buildsystems/vcpkg.cmake ^
  -DVCPKG_TARGET_TRIPLET=arm64-android ^
  -DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=C:/Users/ITSeniy/AppData/Local/Android/Sdk/ndk/29.0.14206865/build/cmake/android.toolchain.cmake ^
  -DVCPKG_MANIFEST_MODE=OFF ^
  -DVCPKG_INSTALLED_DIR=C:/Users/ITSeniy/source/UnleashedRecompAndroid/out/build/android-arm64/vcpkg_installed ^
  -DANDROID_ABI=arm64-v8a ^
  -DANDROID_PLATFORM=android-29 ^
  -DCMAKE_SYSTEM_VERSION=29 ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DUNLEASHED_RECOMP_ANDROID=ON ^
  -DUNLEASHED_RECOMP_D3D12=OFF ^
  -DUNLEASHED_RECOMP_HOST_XENON_RECOMP=C:/Users/ITSeniy/source/UnleashedRecompAndroid/out/build/x64-Clang-HostTools/tools/XenonRecomp/XenonRecomp/XenonRecomp.exe ^
  -DUNLEASHED_RECOMP_HOST_XENOS_RECOMP=C:/Users/ITSeniy/source/UnleashedRecompAndroid/out/build/x64-Clang-HostTools/tools/XenosRecomp/XenosRecomp/XenosRecomp.exe ^
  -DUNLEASHED_RECOMP_HOST_FILE_TO_C=C:/Users/ITSeniy/source/UnleashedRecompAndroid/out/build/x64-Clang-HostTools/tools/file_to_c/file_to_c.exe ^
  -DUNLEASHED_RECOMP_HOST_X_DECOMPRESS=C:/Users/ITSeniy/source/UnleashedRecompAndroid/out/build/x64-Clang-HostTools/tools/x_decompress/x_decompress.exe
