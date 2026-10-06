cmake_minimum_required(VERSION 3.22.1)
project(p2thermal LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(WORKBENCH "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(THIRD_PARTY "${WORKBENCH}/third_party")
set(USB "${THIRD_PARTY}/libusb")
set(UVC "${THIRD_PARTY}/libuvc")

# libusb remains a separate shared library so its source/build and replacement
# rights can be supplied independently under LGPL-2.1-or-later.
add_library(usb SHARED
    ${USB}/libusb/core.c ${USB}/libusb/descriptor.c ${USB}/libusb/hotplug.c
    ${USB}/libusb/io.c ${USB}/libusb/sync.c ${USB}/libusb/strerror.c
    ${USB}/libusb/os/linux_usbfs.c ${USB}/libusb/os/events_posix.c
    ${USB}/libusb/os/threads_posix.c ${USB}/libusb/os/linux_netlink.c)
target_include_directories(usb PUBLIC ${USB}/libusb PRIVATE ${USB}/android)
target_link_libraries(usb PRIVATE log)

set(libuvc_VERSION_MAJOR 0)
set(libuvc_VERSION_MINOR 0)
set(libuvc_VERSION_PATCH 7)
set(libuvc_VERSION "0.0.7")
configure_file(${UVC}/include/libuvc/libuvc_config.h.in
    ${CMAKE_CURRENT_BINARY_DIR}/include/libuvc/libuvc_config.h @ONLY)
add_library(uvc STATIC
    ${UVC}/src/ctrl.c ${UVC}/src/ctrl-gen.c ${UVC}/src/device.c
    ${UVC}/src/diag.c ${UVC}/src/frame.c ${UVC}/src/init.c
    ${UVC}/src/stream.c ${UVC}/src/misc.c)
target_include_directories(uvc PUBLIC ${UVC}/include ${CMAKE_CURRENT_BINARY_DIR}/include)
target_link_libraries(uvc PUBLIC usb PRIVATE log)

add_library(thermal_core SHARED
    engine.cpp jni.cpp
    ${WORKBENCH}/native/p2pro_protocol.cpp ${WORKBENCH}/native/p2pro_camera.cpp)
target_include_directories(thermal_core PRIVATE ${WORKBENCH}/native)
target_link_libraries(thermal_core PRIVATE uvc usb android log EGL GLESv3)
target_link_options(thermal_core PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(usb PRIVATE "-Wl,-z,max-page-size=16384")
