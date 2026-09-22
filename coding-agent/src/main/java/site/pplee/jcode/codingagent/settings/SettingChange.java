package site.pplee.jcode.codingagent.settings;

import java.util.Objects;
import java.util.Optional;

/** Typed KEEP, SET, or REMOVE operation for one settings field. */
public final class SettingChange<T> {
    private static final SettingChange<?> KEEP = new SettingChange<>(Operation.KEEP, null);
    private static final SettingChange<?> REMOVE = new SettingChange<>(Operation.REMOVE, null);

    private final Operation operation;
    private final T value;

    private SettingChange(Operation operation, T value) {
        this.operation = operation;
        this.value = value;
    }

    @SuppressWarnings("unchecked")
    public static <T> SettingChange<T> keep() {
        return (SettingChange<T>) KEEP;
    }

    public static <T> SettingChange<T> set(T value) {
        return new SettingChange<>(Operation.SET, Objects.requireNonNull(value, "value must not be null"));
    }

    @SuppressWarnings("unchecked")
    public static <T> SettingChange<T> remove() {
        return (SettingChange<T>) REMOVE;
    }

    public Operation operation() {
        return operation;
    }

    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    public enum Operation {
        KEEP,
        SET,
        REMOVE
    }
}
