cmake_minimum_required(VERSION 3.20)
project(fsr_vulkan_smoke LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_MSVC_RUNTIME_LIBRARY MultiThreaded)
get_filename_component(REPO_ROOT "../.." ABSOLUTE)
set(PLUME_ROOT "${REPO_ROOT}/thirdparty/plume")
add_executable(fsr_vulkan_smoke smoke.cpp "${PLUME_ROOT}/plume_vulkan.cpp")
target_compile_definitions(fsr_vulkan_smoke PRIVATE NOMINMAX)
target_include_directories(fsr_vulkan_smoke PRIVATE
    "${PLUME_ROOT}" "${PLUME_ROOT}/contrib/volk"
    "${PLUME_ROOT}/contrib/Vulkan-Headers/include"
    "${PLUME_ROOT}/contrib/VulkanMemoryAllocator/include")
if(WIN32)
    target_link_libraries(fsr_vulkan_smoke PRIVATE user32)
elseif(UNIX)
    target_link_libraries(fsr_vulkan_smoke PRIVATE dl X11)
endif()
