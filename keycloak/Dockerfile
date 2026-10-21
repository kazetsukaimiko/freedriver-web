# Keycloak 26.3 plus the Freedriver SMS OTP provider at
# /opt/keycloak/providers/freedriver-sms-otp.jar and the freedriver login theme
# at /opt/keycloak/themes/freedriver.
#
# The builder compiles sms-model, sms-api, and sms-client from this checkout
# with ./mvnw -C -pl sms-client -am package on JDK 23. Those three jars are
# shaded into the provider jar. Jackson and jakarta.validation-api are not.
# The shared secret stays in the runtime environment. It is not a build arg
# and it is not copied into a layer.

FROM maven:3.9-eclipse-temurin-23 AS spi
WORKDIR /src
# mvnw -C checks the Maven zip. The wrapper needs unzip for that zip.
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

COPY mvnw mvnw
COPY .mvn .mvn
COPY pom.xml pom.xml
COPY mqtt-contract/pom.xml mqtt-contract/pom.xml
COPY mqtt/pom.xml mqtt/pom.xml
COPY mqtt-paho/pom.xml mqtt-paho/pom.xml
COPY java/pom.xml java/pom.xml
COPY java/bom/pom.xml java/bom/pom.xml
COPY java/dashboard-model/pom.xml java/dashboard-model/pom.xml
COPY java/dashboard-api/pom.xml java/dashboard-api/pom.xml
COPY java/dashboard/pom.xml java/dashboard/pom.xml
COPY java/agent-dispatcher-model/pom.xml java/agent-dispatcher-model/pom.xml
COPY java/agent-dispatcher-api/pom.xml java/agent-dispatcher-api/pom.xml
COPY java/agent-dispatcher/pom.xml java/agent-dispatcher/pom.xml
COPY java/device-daemon/pom.xml java/device-daemon/pom.xml
COPY sms-model/pom.xml sms-model/pom.xml
COPY sms-api/pom.xml sms-api/pom.xml
COPY sms-client/pom.xml sms-client/pom.xml
COPY sms/pom.xml sms/pom.xml
COPY sms-model/src/main sms-model/src/main
COPY sms-api/src/main sms-api/src/main
COPY sms-client/src/main sms-client/src/main
COPY keycloak/sms-otp-spi/pom.xml keycloak/sms-otp-spi/pom.xml
COPY keycloak/sms-otp-spi/src/main keycloak/sms-otp-spi/src/main
COPY scripts/check-sms-otp-provider-jar.sh scripts/check-sms-otp-provider-jar.sh

# Git commit of the checkout being deployed. Not a secret.
ARG SOURCE_COMMIT=unknown
RUN chmod +x mvnw scripts/check-sms-otp-provider-jar.sh \
 && ./mvnw -C -pl sms-client -am package -DskipTests \
 && ./mvnw -C -pl sms-client -am install -DskipTests \
 && ./mvnw -C -f keycloak/sms-otp-spi/pom.xml -DskipTests -Dsource.commit="${SOURCE_COMMIT}" package \
 && scripts/check-sms-otp-provider-jar.sh \
      keycloak/sms-otp-spi/target/freedriver-sms-otp-spi-1.0.0-SNAPSHOT.jar \
      "${SOURCE_COMMIT}" \
 && cp keycloak/sms-otp-spi/target/freedriver-sms-otp-spi-1.0.0-SNAPSHOT.jar /freedriver-sms-otp.jar

FROM quay.io/keycloak/keycloak:26.3
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_HTTP_ENABLED=true
COPY --from=spi --chown=keycloak:keycloak /freedriver-sms-otp.jar /opt/keycloak/providers/freedriver-sms-otp.jar
COPY --chown=keycloak:keycloak keycloak/themes/freedriver /opt/keycloak/themes/freedriver
# Fixed mtime so optimized starts see the same provider that kc.sh build indexed.
RUN touch -m --date=@1743465600 /opt/keycloak/providers/freedriver-sms-otp.jar \
 && /opt/keycloak/bin/kc.sh build
