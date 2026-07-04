package com.keplerops.groundcontrol.test.oracle;

import java.util.Objects;
import java.util.function.Supplier;

public record PortImplementation<T>(String name, Supplier<T> factory) {

    public PortImplementation {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("implementation name must not be blank");
        }
        Objects.requireNonNull(factory, "factory");
    }

    public T create() {
        return factory.get();
    }
}
