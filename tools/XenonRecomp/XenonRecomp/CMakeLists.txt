cmake_minimum_required (VERSION 3.8)

project("XenonRecomp")

add_executable(XenonRecomp 
    "main.cpp" 
    "recompiler.cpp"
    "test_recompiler.cpp" 
    "recompiler_config.cpp")

target_precompile_headers(XenonRecomp PUBLIC "pch.h")

target_link_libraries(XenonRecomp PRIVATE
    LibXenonAnalyse 
    XenonUtils 
    fmt::fmt
    tomlplusplus::tomlplusplus 
    xxHash::xxhash)

if (CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
    target_compile_options(XenonRecomp PRIVATE -Wno-switch -Wno-unused-variable -Wno-null-arithmetic)

    # alias attribute not supported on Apple.
    if (NOT APPLE)
        target_compile_definitions(XenonRecomp PRIVATE XENON_RECOMP_USE_ALIAS)
    endif()
endif()

target_compile_definitions(XenonRecomp PRIVATE _CRT_SECURE_NO_WARNINGS)

# Optional: bake the TOML config path and the PPC context header path into the
# binary at compile time, instead of requiring them as argv[1]/argv[2] at
# runtime (see the #ifdef in main.cpp). Off by default — only enabled if both
# variables are explicitly passed to the CMake configure step with -D.
# NOTE: this does not change what gets generated; it only changes how the
# tool learns the same two paths (compiled-in vs. passed on the command line).
if (DEFINED XENON_RECOMP_CONFIG_FILE_PATH AND DEFINED XENON_RECOMP_HEADER_FILE_PATH)
    target_compile_definitions(XenonRecomp PRIVATE
        XENON_RECOMP_CONFIG_FILE_PATH="${XENON_RECOMP_CONFIG_FILE_PATH}"
        XENON_RECOMP_HEADER_FILE_PATH="${XENON_RECOMP_HEADER_FILE_PATH}")
endif()
