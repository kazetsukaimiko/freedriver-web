package io.freedriver.architecture.fixtures.apiquality;

import java.util.List;

/** A record that maps a list of a sealed API interface. */
public record ItemBox(List<Item> items) {
    public List<String> names() {
        return items.stream().map(Item::name).toList();
    }
}
