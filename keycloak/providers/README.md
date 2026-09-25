# Keycloak providers

`keycloak/Dockerfile` compiles `keycloak/sms-otp-spi`, copies the JAR to `/opt/keycloak/providers/freedriver-sms-otp.jar`, and runs `kc.sh build` so the provider is on the classpath. `.gitignore` covers `*.jar` in this directory.
