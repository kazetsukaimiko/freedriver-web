# Keycloak providers

The SMS OTP authenticator JAR is not stored in git.

The image build (`keycloak/Dockerfile`) compiles `keycloak/sms-otp-spi` and copies the JAR to:

`/opt/keycloak/providers/freedriver-sms-otp.jar`

That directory is the Keycloak provider mount path. `kc.sh build` runs in the image so the provider is on the classpath. Do not commit a JAR here, and do not put AWS credentials in this directory or in the Keycloak process.
