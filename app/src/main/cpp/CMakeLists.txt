cmake_minimum_required(VERSION 3.22.1)
project("phoneassistant_llm")

# Source files
add_library(${CMAKE_PROJECT_NAME} SHARED
    llm_jni.cpp
    llm_session.cpp
    processor.cpp
    video/video_processor.cpp
    video/video_decoder.cpp
    video/byte_buffer_decoder.cpp
    video/image_utils.cpp
)

# Header search paths
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_SOURCE_DIR}/include
    ${CMAKE_SOURCE_DIR}/third_party
    ${CMAKE_SOURCE_DIR}
)

# Prebuilt MNN libraries (from jniLibs)
set(JNILIBS_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN.so")

add_library(llm SHARED IMPORTED)
set_target_properties(llm PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libllm.so")

add_library(mnn_Express SHARED IMPORTED)
set_target_properties(mnn_Express PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN_Express.so")

# Link libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    mediandk
    MNN
    llm
    mnn_Express
)

# C++17
set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES CXX_STANDARD 17)

# 16KB page size alignment (Android 15+)
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
