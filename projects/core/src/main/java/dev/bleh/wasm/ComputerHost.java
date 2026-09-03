package dev.bleh.wasm;

import java.util.List;

/** Capabilities supplied to a boot disk. No Lua implementation details cross this interface. */
public interface ComputerHost {
    HostResult call(String method, List<Object> arguments);
    HostResult resume(String token, List<Object> event);
    byte[] bootData();
    default boolean isInterrupted() { return false; }
}
