# Keycloak providers

`keycloak/Dockerfile` builds `sms-model`, `sms-api`, and `sms-client` from this checkout with `./mvnw -C -pl sms-client -am package` on a JDK 23 image, shades only those three jars into `freedriver-sms-otp.jar`, and runs `kc.sh build`. The manifest `Implementation-SCM-Revision` is the source commit. Jackson and `jakarta.validation-api` are not in the jar. `.gitignore` covers `*.jar` in this directory.
