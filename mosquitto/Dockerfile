# Broker image for #317: eclipse-mosquitto:2.1.2-alpine plus iotinga OAuth plugin.
# Alpine 3.23 ships libjwt 3.x; the plugin speaks libjwt 1.x, so 1.17.2 is built
# from source. Mosquitto headers come from the 2.1.2 release tarball.

FROM eclipse-mosquitto:2.1.2-alpine AS builder

ARG MOSQUITTO_VERSION=2.1.2
ARG MOSQUITTO_SHA256=fd905380691ac65ea5a93779e8214941829e3d6e038d5edff9eac5fd74cbed02
ARG OAUTH_PLUGIN_VERSION=1.1.1
ARG OAUTH_PLUGIN_SHA256=30e889d82730596b220c27645f783c8fab701f5510c78e64d83e1d6dbac6ae91
ARG LIBJWT_VERSION=1.17.2
ARG LIBJWT_SHA256=3dc1d0d69c3aa95cf6932304728d71dccfb1fec5196c6b274da96572619aaf26

USER root
WORKDIR /build

RUN apk add --no-cache \
    build-base \
    cmake \
    curl \
    curl-dev \
    jansson-dev \
    linux-headers \
    openssl-dev \
    patch \
    pkgconfig

RUN curl -fsSL -o mosquitto.tar.gz "https://mosquitto.org/files/source/mosquitto-${MOSQUITTO_VERSION}.tar.gz" \
    && echo "${MOSQUITTO_SHA256}  mosquitto.tar.gz" | sha256sum -c - \
    && tar -xzf mosquitto.tar.gz \
    && curl -fsSL -o libjwt.tar.gz "https://github.com/benmcollins/libjwt/archive/refs/tags/v${LIBJWT_VERSION}.tar.gz" \
    && echo "${LIBJWT_SHA256}  libjwt.tar.gz" | sha256sum -c - \
    && tar -xzf libjwt.tar.gz \
    && curl -fsSL -o plugin.tar.gz "https://github.com/iotinga/mosquitto-oauth-plugin/archive/refs/tags/v${OAUTH_PLUGIN_VERSION}.tar.gz" \
    && echo "${OAUTH_PLUGIN_SHA256}  plugin.tar.gz" | sha256sum -c -

WORKDIR /build/libjwt-${LIBJWT_VERSION}
RUN cmake -S . -B build \
        -DCMAKE_INSTALL_PREFIX=/usr \
        -DBUILD_SHARED_LIBS=ON \
        -DBUILD_EXAMPLES=OFF \
        -DUSE_INSTALLED_JANSSON=ON \
    && cmake --build build \
    && cmake --install build

COPY mosquitto/oauth-plugin/defer-password-file.patch /build/defer-password-file.patch
COPY mosquitto/oauth-plugin/CMakeLists.txt /build/plugin-CMakeLists.txt

WORKDIR /build/mosquitto-oauth-plugin-${OAUTH_PLUGIN_VERSION}
RUN patch -p1 < /build/defer-password-file.patch \
    && cp /build/plugin-CMakeLists.txt CMakeLists.txt \
    && cmake -S . -B build \
        -DCMAKE_INSTALL_PREFIX=/usr \
        -DMOSQUITTO_INCLUDE_DIR=/build/mosquitto-${MOSQUITTO_VERSION}/include \
    && cmake --build build \
    && cmake --install build

FROM eclipse-mosquitto:2.1.2-alpine

USER root
RUN apk add --no-cache \
    ca-certificates \
    curl \
    jansson
COPY --from=builder /usr/lib/libjwt.so* /usr/lib/
COPY --from=builder /usr/lib/mosquitto-oauth-plugin.so /usr/lib/mosquitto-oauth-plugin.so
COPY mosquitto/docker-entrypoint.sh /freedriver-mosquitto-entrypoint.sh
RUN chmod 0755 /freedriver-mosquitto-entrypoint.sh \
    && chmod 0755 /usr/lib/mosquitto-oauth-plugin.so

ENTRYPOINT ["/freedriver-mosquitto-entrypoint.sh"]
CMD ["/usr/sbin/mosquitto", "-c", "/mosquitto/config/mosquitto.conf"]
