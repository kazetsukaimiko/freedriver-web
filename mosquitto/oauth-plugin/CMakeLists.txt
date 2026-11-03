cmake_minimum_required(VERSION 3.22)
project(mosquitto-oauth-plugin C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT MOSQUITTO_INCLUDE_DIR)
    message(FATAL_ERROR "MOSQUITTO_INCLUDE_DIR must point at Mosquitto 2.1.2 headers")
endif()

find_package(PkgConfig REQUIRED)
find_package(OpenSSL REQUIRED)
pkg_check_modules(LIBCURL REQUIRED libcurl)
pkg_check_modules(JANSSON REQUIRED jansson)
find_path(JWT_INCLUDE_DIR jwt.h REQUIRED)
find_library(JWT_LIBRARY jwt REQUIRED)

file(GLOB SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.c")

add_library(mosquitto-oauth-plugin SHARED ${SOURCES})
target_include_directories(mosquitto-oauth-plugin PRIVATE
    ${MOSQUITTO_INCLUDE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${LIBCURL_INCLUDE_DIRS}
    ${JWT_INCLUDE_DIR}
    ${JANSSON_INCLUDE_DIRS}
)
# Mosquitto 2.1 mosquitto_broker.h no longer declares mosquitto_malloc;
# mosquitto.h does, via libcommon_memory.h.
target_compile_options(mosquitto-oauth-plugin PRIVATE -include mosquitto.h)
target_link_libraries(mosquitto-oauth-plugin PRIVATE
    ${LIBCURL_LIBRARIES}
    ${JWT_LIBRARY}
    ${JANSSON_LIBRARIES}
    OpenSSL::Crypto
)
target_link_options(mosquitto-oauth-plugin PRIVATE
    "LINKER:--allow-shlib-undefined"
)
set_target_properties(mosquitto-oauth-plugin PROPERTIES
    PREFIX ""
    SUFFIX ".so"
    OUTPUT_NAME "mosquitto-oauth-plugin"
)

install(TARGETS mosquitto-oauth-plugin LIBRARY DESTINATION lib)
