project(ncnn-detector)

cmake_minimum_required(VERSION 3.4.1)
# import ncnn library

# change this folder path to yours
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20220420-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(
        ncnn_detector SHARED
        NCNN_detector_public_jni.cpp
        yolov5ncnn_jni.cpp
        # add your cpp here
)

target_link_libraries(ncnn_detector
    ncnn
    jnigraphics
#    glslang
)