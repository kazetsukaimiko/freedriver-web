package io.freedriver.daemon;

final class DeviceRejected extends RuntimeException {

    final int status;

    DeviceRejected(int status) {
        super("device api " + status);
        this.status = status;
    }
}
