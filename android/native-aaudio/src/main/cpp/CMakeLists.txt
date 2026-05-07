cmake_minimum_required(VERSION 3.22.1)
project(aaudio_engine LANGUAGES CXX)

add_library(aaudio_engine SHARED
    aaudio_engine.cpp
    jni_bridge.cpp
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)

target_link_libraries(aaudio_engine
    ${log-lib}
    ${aaudio-lib}
)

target_compile_options(aaudio_engine PRIVATE -Wall -Wextra -Wno-unused-parameter)
