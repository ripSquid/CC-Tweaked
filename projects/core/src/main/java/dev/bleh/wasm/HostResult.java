package dev.bleh.wasm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public record HostResult(Kind kind, List<Object> values, String token, String filter, String error) {
    public enum Kind { COMPLETED, AWAIT, ERROR }

    public HostResult {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static HostResult completed(Object... values) { return completed(Arrays.asList(values)); }
    public static HostResult completed(List<Object> values) {
        return new HostResult(Kind.COMPLETED, values, null, null, null);
    }
    public static HostResult await(String token, String filter) {
        return new HostResult(Kind.AWAIT, List.of(), token, filter, null);
    }
    public static HostResult error(String error) {
        return new HostResult(Kind.ERROR, List.of(), null, null, error);
    }

    public List<Object> transportValues() {
        var result = new ArrayList<Object>();
        switch (kind) {
            case COMPLETED -> { result.add(true); result.addAll(values); }
            case ERROR -> { result.add(false); result.add(error); }
            case AWAIT -> { result.add("__bleh_wait"); result.add(token); result.add(filter); }
        }
        return result;
    }
}
