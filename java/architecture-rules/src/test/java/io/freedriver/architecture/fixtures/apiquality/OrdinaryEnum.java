package io.freedriver.architecture.fixtures.apiquality;

/** An enum with a constructor, a final field and a method. */
public enum OrdinaryEnum {
    RED("r");

    private final String code;

    OrdinaryEnum(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
