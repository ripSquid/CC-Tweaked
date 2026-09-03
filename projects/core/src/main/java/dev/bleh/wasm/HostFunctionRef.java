package dev.bleh.wasm;

/** An opaque capability; the host must reject IDs it has not issued. */
public record HostFunctionRef(String id) {}
