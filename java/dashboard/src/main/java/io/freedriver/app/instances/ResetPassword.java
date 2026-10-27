package io.freedriver.app.instances;

/** Open's new credential. {@link #toString()} omits the password. The previous password is not on this value. */
public record ResetPassword(String instanceId, String password) {

    @Override
    public String toString() {
        return "ResetPassword{instanceId=" + instanceId + "}";
    }
}
