cmake_minimum_required(VERSION 3.20)

project(PhyToyEngine VERSION 0.3.0 LANGUAGES CXX)

option(PHYTOY_BUILD_CLI "Build the native command-line renderer" ON)
option(PHYTOY_BUILD_BENCHMARK "Build the runtime acceptance benchmark" ON)
option(PHYTOY_BUILD_TESTS "Build native tests" ON)
option(PHYTOY_BUILD_VULKAN_SHADERS "Compile and embed Android Vulkan compute shaders" ON)
set(PHYTOY_GLSLC "" CACHE FILEPATH "Path to the Vulkan glslc compiler")

add_library(phytoy_core SHARED
    core/src/engine.cpp
    core/src/json.cpp
    core/src/profile.cpp
    core/src/sha256.cpp
    backends/cpu/cpu_backend.cpp
    backends/vulkan/vulkan_backend.cpp
)
add_library(PhyToy::Core ALIAS phytoy_core)

target_compile_features(phytoy_core PUBLIC cxx_std_20)
target_include_directories(phytoy_core
    PUBLIC core/include
    PRIVATE core/src backends/cpu backends/vulkan
)
target_compile_definitions(phytoy_core PRIVATE PHYTOY_CORE_BUILD=1)
set_target_properties(phytoy_core PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

if(MSVC)
    target_compile_options(phytoy_core PRIVATE /W4 /permissive-)
else()
    target_compile_options(phytoy_core PRIVATE -Wall -Wextra -Wpedantic -Wconversion)
endif()

if(PHYTOY_BUILD_VULKAN_SHADERS)
    if(NOT PHYTOY_GLSLC)
        find_program(PHYTOY_GLSLC NAMES glslc)
    endif()
    if(PHYTOY_GLSLC)
        find_package(Python3 REQUIRED COMPONENTS Interpreter)
        set(PHYTOY_SHADER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/backends/vulkan/shaders")
        set(PHYTOY_SPIRV_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated/spirv")
        set(PHYTOY_SPIRV_FILES)
        foreach(SHADER_SOURCE normalize_ahb.comp optics.comp sensor.comp isp.comp present.vert present.frag)
            get_filename_component(SHADER_NAME "${SHADER_SOURCE}" NAME_WE)
            get_filename_component(SHADER_STAGE "${SHADER_SOURCE}" LAST_EXT)
            string(SUBSTRING "${SHADER_STAGE}" 1 -1 SHADER_STAGE)
            if(SHADER_STAGE STREQUAL "comp")
                set(SHADER_ID "${SHADER_NAME}")
            else()
                set(SHADER_ID "${SHADER_NAME}_${SHADER_STAGE}")
            endif()
            set(SPIRV_FILE "${PHYTOY_SPIRV_DIR}/${SHADER_ID}.spv")
            add_custom_command(
                OUTPUT "${SPIRV_FILE}"
                COMMAND "${CMAKE_COMMAND}" -E make_directory "${PHYTOY_SPIRV_DIR}"
                COMMAND "${PHYTOY_GLSLC}" --target-env=vulkan1.1 -O
                    "${PHYTOY_SHADER_DIR}/${SHADER_SOURCE}" -o "${SPIRV_FILE}"
                DEPENDS "${PHYTOY_SHADER_DIR}/${SHADER_SOURCE}"
                VERBATIM
            )
            list(APPEND PHYTOY_SPIRV_FILES "${SHADER_ID}=${SPIRV_FILE}")
            list(APPEND PHYTOY_SPIRV_OUTPUTS "${SPIRV_FILE}")
        endforeach()
        set(PHYTOY_SPIRV_HEADER "${CMAKE_CURRENT_BINARY_DIR}/generated/phytoy_spirv.hpp")
        add_custom_command(
            OUTPUT "${PHYTOY_SPIRV_HEADER}"
            COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tools/embed_spirv.py"
                --output "${PHYTOY_SPIRV_HEADER}" ${PHYTOY_SPIRV_FILES}
            DEPENDS ${PHYTOY_SPIRV_OUTPUTS} tools/embed_spirv.py
            VERBATIM
        )
        add_custom_target(phytoy_vulkan_shaders DEPENDS "${PHYTOY_SPIRV_HEADER}")
        add_dependencies(phytoy_core phytoy_vulkan_shaders)
        target_include_directories(phytoy_core PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/generated")
        target_compile_definitions(phytoy_core PRIVATE PHYTOY_HAS_EMBEDDED_SPIRV=1)
    elseif(ANDROID)
        message(FATAL_ERROR "Android Vulkan build requires PHYTOY_GLSLC")
    else()
        message(STATUS "glslc not found; Vulkan shader build skipped")
    endif()
endif()

if(ANDROID)
    target_link_libraries(phytoy_core PRIVATE vulkan android log dl)
endif()

if(PHYTOY_BUILD_CLI)
    add_executable(phytoy_cli cli/phytoy_cli.cpp)
    target_compile_features(phytoy_cli PRIVATE cxx_std_20)
    target_link_libraries(phytoy_cli PRIVATE PhyToy::Core)
endif()

if(PHYTOY_BUILD_BENCHMARK)
    add_executable(phytoy_benchmark tools/runtime_benchmark.cpp)
    target_compile_features(phytoy_benchmark PRIVATE cxx_std_20)
    target_link_libraries(phytoy_benchmark PRIVATE PhyToy::Core)
    if(ANDROID)
        target_link_libraries(phytoy_benchmark PRIVATE android dl)
    endif()
endif()

include(CTest)
if(BUILD_TESTING AND PHYTOY_BUILD_TESTS)
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    set(PHYTOY_GENERATED_PROFILE_DIR "${CMAKE_CURRENT_BINARY_DIR}/profiles")
    file(MAKE_DIRECTORY "${PHYTOY_GENERATED_PROFILE_DIR}")

    add_custom_command(
        OUTPUT
            "${PHYTOY_GENERATED_PROFILE_DIR}/host_generic_srgb.ptp"
            "${PHYTOY_GENERATED_PROFILE_DIR}/host_generic_yuv420.ptp"
            "${PHYTOY_GENERATED_PROFILE_DIR}/host_reference_raw.ptp"
            "${PHYTOY_GENERATED_PROFILE_DIR}/host_samsung_sm_s9210_camera0_raw.ptp"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_fixed_focus_alpha.ptp"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_fixed_focus_conformance.ptp"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1.ptp"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1_1.ptp"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1_2.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/host_generic_yuv420.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/host_generic_yuv420.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/host_generic_srgb.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/host_generic_srgb.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/host_reference_raw.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/host_reference_raw.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/host_samsung_sm_s9210_camera0_raw.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/host_samsung_sm_s9210_camera0_raw.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/toy_fixed_focus_alpha.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_fixed_focus_alpha.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/toy_fixed_focus_conformance.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_fixed_focus_conformance.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/toy_phytoy_digital_01_v1.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/toy_phytoy_digital_01_v1_1.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1_1.ptp"
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "${Python3_EXECUTABLE}" -m phytoy_ref.profile_package
            "${CMAKE_CURRENT_SOURCE_DIR}/profiles/authoring/toy_phytoy_digital_01_v1_2.json"
            "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1_2.ptp"
        DEPENDS
            profiles/authoring/host_generic_srgb.json
            profiles/authoring/host_generic_yuv420.json
            profiles/authoring/host_reference_raw.json
            profiles/authoring/host_samsung_sm_s9210_camera0_raw.json
            profiles/authoring/toy_fixed_focus_alpha.json
            profiles/authoring/toy_fixed_focus_conformance.json
            profiles/authoring/toy_phytoy_digital_01_v1.json
            profiles/authoring/toy_phytoy_digital_01_v1_1.json
            profiles/authoring/toy_phytoy_digital_01_v1_2.json
            reference/phytoy_ref/profile_package.py
            reference/phytoy_ref/profiles.py
        VERBATIM
    )
    add_custom_target(phytoy_test_profiles DEPENDS
        "${PHYTOY_GENERATED_PROFILE_DIR}/host_generic_srgb.ptp"
        "${PHYTOY_GENERATED_PROFILE_DIR}/host_generic_yuv420.ptp"
        "${PHYTOY_GENERATED_PROFILE_DIR}/host_reference_raw.ptp"
        "${PHYTOY_GENERATED_PROFILE_DIR}/host_samsung_sm_s9210_camera0_raw.ptp"
        "${PHYTOY_GENERATED_PROFILE_DIR}/toy_fixed_focus_alpha.ptp"
        "${PHYTOY_GENERATED_PROFILE_DIR}/toy_fixed_focus_conformance.ptp"
        "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1.ptp"
        "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1_1.ptp"
        "${PHYTOY_GENERATED_PROFILE_DIR}/toy_phytoy_digital_01_v1_2.ptp"
    )

    add_executable(phytoy_core_tests core/tests/test_core.cpp)
    target_compile_features(phytoy_core_tests PRIVATE cxx_std_20)
    target_link_libraries(phytoy_core_tests PRIVATE PhyToy::Core)
    target_compile_definitions(phytoy_core_tests PRIVATE
        PHYTOY_TEST_PROFILE_DIR="${PHYTOY_GENERATED_PROFILE_DIR}"
    )
    add_dependencies(phytoy_core_tests phytoy_test_profiles)
    add_test(NAME phytoy_core_tests COMMAND phytoy_core_tests)

    add_test(
        NAME phytoy_python_reference_tests
        COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${CMAKE_CURRENT_SOURCE_DIR}/reference"
            "PHYTOY_CPP_LIBRARY=$<TARGET_FILE:phytoy_core>"
            "PHYTOY_CPP_PROFILE_DIR=${PHYTOY_GENERATED_PROFILE_DIR}"
            "${Python3_EXECUTABLE}" -m pytest -q "${CMAKE_CURRENT_SOURCE_DIR}/reference/tests"
    )
endif()
