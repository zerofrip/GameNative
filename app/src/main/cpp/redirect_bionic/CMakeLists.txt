cmake_minimum_required(VERSION 3.22.1)

project(redirect_bionic C)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fPIC -Wall -Wextra")

add_library(redirect-bionic SHARED redirect_bionic.c)

target_link_libraries(redirect-bionic dl log)
