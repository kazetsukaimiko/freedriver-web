package io.freedriver.architecture.fixtures.apiquality;

/** Bound, static, unbound and constructor references. One line for each API interface. */
public class RefForms {
    private final Finder repository = new Finder();
    Callback bound = this::lookup;
    Callback other = repository::find;
    static Callback stat = RefForms::staticLookup;
    Pick unbound = Order::id;
    Make made = OrderImpl::new;

    String lookup(String id) {
        return id;
    }

    static String staticLookup(String id) {
        return id;
    }
}
