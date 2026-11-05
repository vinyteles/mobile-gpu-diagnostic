cmake_minimum_required(VERSION 3.22)
project(lfm_diagnostic LANGUAGES C CXX)
add_compile_options(-O2) # Keep real-model instrumentation practical in debug APKs too.

# 1. Compile the pinned CPU and Vulkan backends into the JNI library.
set(DEPS "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # The physical target is the S25 FE. Guard these features in JNI before loading the CPU backend.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif (ANDROID_ABI STREQUAL "x86_64")
    # This test emulator exposes AVX and F16C, but not AVX2/FMA.
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
endif()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(Vulkan_INCLUDE_DIR "${DEPS}/Vulkan-Headers/include" CACHE PATH "" FORCE)
set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc" CACHE FILEPATH "" FORCE)
set(SPIRV-Headers_DIR "${DEPS}/spirv-install/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
include_directories("${DEPS}/SPIRV-Headers/include")
add_subdirectory("${DEPS}/llama.cpp" llama)

# 2. Expose bounded, single-thread-owned inference calls to the disposable worker process.
add_library(lfm_diagnostic SHARED lfm_jni.cpp)
target_compile_features(lfm_diagnostic PRIVATE cxx_std_17)
target_include_directories(lfm_diagnostic PRIVATE "${DEPS}/llama.cpp/vendor")
target_link_libraries(lfm_diagnostic PRIVATE llama android log)
target_link_options(lfm_diagnostic PRIVATE "-Wl,-z,max-page-size=16384")
