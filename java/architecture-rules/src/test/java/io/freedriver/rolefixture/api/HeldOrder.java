package io.freedriver.rolefixture.api;

import java.util.Comparator;

/** An anonymous class that implements no API interface. */
public interface HeldOrder {
    Comparator<String> ORDER = new Comparator<String>() {
        @Override
        public int compare(String left, String right) {
            return 0;
        }
    };
}
