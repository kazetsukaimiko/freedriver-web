package io.freedriver.architecture.fixtures.apiquality;

/** API code. A lambda here is the contract-type exemption on the holding class. */
public interface ApiCallbackHolder {
    static Callback held() {
        return id -> id;
    }
}
