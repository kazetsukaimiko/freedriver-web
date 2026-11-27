package io.freedriver.architecture.fixtures.apiquality;

import java.util.logging.Logger;

/** An exception that logs. */
public class Loud extends Exception {
    public Loud(String message) {
        super(message);
        Logger.getLogger("x").info(message);
    }
}
