# Keycloak 26.3 plus the Freedriver SMS OTP provider at
# /opt/keycloak/providers/freedriver-sms-otp.jar.
# SMS_OTP_SHARED_SECRET comes from the runtime environment.

FROM maven:3.9-eclipse-temurin-21 AS spi
WORKDIR /src
COPY keycloak/sms-otp-spi/pom.xml ./pom.xml
COPY keycloak/sms-otp-spi/src ./src
RUN mvn -B -DskipTests package \
 && cp target/freedriver-sms-otp-spi-1.0.0-SNAPSHOT.jar /freedriver-sms-otp.jar

FROM quay.io/keycloak/keycloak:26.3
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_HTTP_ENABLED=true
COPY --from=spi --chown=keycloak:keycloak /freedriver-sms-otp.jar /opt/keycloak/providers/freedriver-sms-otp.jar
# Fixed mtime so optimized starts see the same provider that kc.sh build indexed.
RUN touch -m --date=@1743465600 /opt/keycloak/providers/freedriver-sms-otp.jar \
 && /opt/keycloak/bin/kc.sh build
