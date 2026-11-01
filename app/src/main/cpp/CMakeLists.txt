cmake_minimum_required(VERSION 3.22.1)
project(moonlight-native C)

set(CMAKE_C_STANDARD 99)

# Build opus as static library
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Build opus programs" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "Build opus tests" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "Build custom modes" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)
set(OPUS_FORTIFY_SOURCE OFF CACHE BOOL "Opus fortify source" FORCE)
add_subdirectory(opus EXCLUDE_FROM_ALL)

# Build mbedtls (for mbedcrypto) as static library
set(ENABLE_TESTING OFF CACHE BOOL "Build mbedtls tests" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "Build mbedtls programs" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "MbedTLS fatal warnings" FORCE)
add_subdirectory(mbedtls EXCLUDE_FROM_ALL)

# moonlight-common-c static library
set(COMMON_C_DIR ${CMAKE_CURRENT_SOURCE_DIR}/moonlight-common-c)

set(COMMON_C_SOURCES
    ${COMMON_C_DIR}/src/AudioStream.c
    ${COMMON_C_DIR}/src/ByteBuffer.c
    ${COMMON_C_DIR}/src/Connection.c
    ${COMMON_C_DIR}/src/ConnectionTester.c
    ${COMMON_C_DIR}/src/ControlStream.c
    ${COMMON_C_DIR}/src/FakeCallbacks.c
    ${COMMON_C_DIR}/src/InputStream.c
    ${COMMON_C_DIR}/src/LinkedBlockingQueue.c
    ${COMMON_C_DIR}/src/Misc.c
    ${COMMON_C_DIR}/src/Platform.c
    ${COMMON_C_DIR}/src/PlatformCrypto.c
    ${COMMON_C_DIR}/src/PlatformSockets.c
    ${COMMON_C_DIR}/src/RtpAudioQueue.c
    ${COMMON_C_DIR}/src/RtpVideoQueue.c
    ${COMMON_C_DIR}/src/RtspConnection.c
    ${COMMON_C_DIR}/src/RtspParser.c
    ${COMMON_C_DIR}/src/SdpGenerator.c
    ${COMMON_C_DIR}/src/SimpleStun.c
    ${COMMON_C_DIR}/src/VideoDepacketizer.c
    ${COMMON_C_DIR}/src/VideoStream.c
    ${COMMON_C_DIR}/nanors/deps/obl/oblas_common.c
    ${COMMON_C_DIR}/nanors/deps/obl/oblas_lite.c
    ${COMMON_C_DIR}/nanors/rs.c
    ${COMMON_C_DIR}/enet/callbacks.c
    ${COMMON_C_DIR}/enet/compress.c
    ${COMMON_C_DIR}/enet/host.c
    ${COMMON_C_DIR}/enet/list.c
    ${COMMON_C_DIR}/enet/packet.c
    ${COMMON_C_DIR}/enet/peer.c
    ${COMMON_C_DIR}/enet/protocol.c
    ${COMMON_C_DIR}/enet/unix.c
)

add_library(moonlight-common-c STATIC ${COMMON_C_SOURCES})

target_include_directories(moonlight-common-c PUBLIC
    ${COMMON_C_DIR}/include
    ${COMMON_C_DIR}/src
    ${COMMON_C_DIR}/enet/include
    ${COMMON_C_DIR}/nanors
    ${COMMON_C_DIR}/nanors/deps/obl
    ${CMAKE_CURRENT_SOURCE_DIR}/opus/include
    ${CMAKE_CURRENT_SOURCE_DIR}/mbedtls/include
)

target_compile_definitions(moonlight-common-c PUBLIC
    USE_MBEDTLS=1
    HAS_FCNTL=1
    HAS_POLL=1
    HAS_GETADDRINFO=1
    HAS_GETNAMEINFO=1
    HAS_INET_PTON=1
    HAS_INET_NTOP=1
    HAS_MSGHDR_FLAGS=1
    HAS_SOCKLEN_T=1
    LC_ANDROID=1
    HAVE_CLOCK_GETTIME=1
)

target_link_libraries(moonlight-common-c PUBLIC
    mbedcrypto
    opus
)

# moonlight-bridge shared library (JNI)
add_library(moonlight-bridge SHARED
    bridge/moonbridge.c
    bridge/minisdl.c
)

target_include_directories(moonlight-bridge PRIVATE
    bridge
    ${COMMON_C_DIR}/include
    ${COMMON_C_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/opus/include
)

target_link_libraries(moonlight-bridge PRIVATE
    moonlight-common-c
    opus
    mbedcrypto
    log
)
