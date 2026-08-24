cmake_minimum_required(VERSION 3.22.1)
project(PhyToyAndroid LANGUAGES CXX)

get_filename_component(PHYTOY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)

set(PHYTOY_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(PHYTOY_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(PHYTOY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(PHYTOY_SHADER_HOST_TAG "darwin-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(PHYTOY_SHADER_HOST_TAG "linux-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(PHYTOY_SHADER_HOST_TAG "windows-x86_64")
else()
    message(FATAL_ERROR "Unsupported shader compiler host: ${CMAKE_HOST_SYSTEM_NAME}")
endif()

set(PHYTOY_GLSLC
    "${ANDROID_NDK}/shader-tools/${PHYTOY_SHADER_HOST_TAG}/glslc"
    CACHE FILEPATH "" FORCE)
add_subdirectory("${PHYTOY_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/phytoy-engine")

add_library(phytoy_android SHARED phytoy_jni.cpp)
target_compile_features(phytoy_android PRIVATE cxx_std_20)
target_include_directories(phytoy_android PRIVATE "${PHYTOY_ROOT}/core/include")
target_link_libraries(phytoy_android PRIVATE PhyToy::Core mediandk android log)
