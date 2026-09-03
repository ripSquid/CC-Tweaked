package dev.bleh.wasm;

import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The computer runtime. It understands the computer ABI, not the language on the boot disk. */
public final class WasmComputer implements AutoCloseable {
    public enum State { WAITING, DONE, FAILED }
    public record Result(State state, String filter, List<Object> values, String error) {
        public Result { values = Collections.unmodifiableList(new ArrayList<>(values)); }
    }

    public static final String DEFAULT_BOOT_IMAGE = "/wasm/boot-lua.wasm";
    private static final long INSTRUCTION_BUDGET = 200_000_000;
    private final ComputerHost host;
    private final WasiPreview1 wasi;
    private Instance instance;
    private byte[] response;
    private long instructions;
    private boolean started;
    private boolean closed;
    private Result result;

    public WasmComputer(ComputerHost host) { this(host, DEFAULT_BOOT_IMAGE); }

    public WasmComputer(ComputerHost host, String bootImageResource) {
        this.host = host;
        wasi = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();
        instructions = INSTRUCTION_BUDGET;
        try (var stream = WasmComputer.class.getResourceAsStream(bootImageResource)) {
            if (stream == null) throw new IOException("Missing boot disk " + bootImageResource);
            var imports = ImportValues.builder().addFunction(wasi.toHostFunctions());
            imports.addFunction(function("boot_data", 0, (vm, args) -> setResponse(host.bootData())));
            imports.addFunction(function("call", 2, (vm, args) -> dispatch(vm, args, false)));
            imports.addFunction(function("resume", 2, (vm, args) -> dispatch(vm, args, true)));
            imports.addFunction(function("read_response", 2, (vm, args) -> {
                if (response == null) throw new IllegalStateException("No pending host response");
                int capacity = (int) args[1];
                if (capacity < response.length) throw new IllegalArgumentException("Response buffer too small");
                vm.memory().write((int) args[0], response);
                int size = response.length;
                response = null;
                return size;
            }));
            instance = Instance.builder(Parser.parse(stream))
                .withImportValues(imports.build()).withStart(false)
                .withUnsafeExecutionListener((instruction, stack) -> {
                    if (--instructions < 0) throw new IllegalStateException("Wasm instruction budget exceeded");
                    if ((instructions & 4095) == 0 && (host.isInterrupted() || Thread.currentThread().isInterrupted())) {
                        throw new IllegalStateException("Computer execution interrupted");
                    }
                }).build();
            call("_initialize");
            if (call("cc_abi_version") != 1) throw new IllegalArgumentException("Unsupported boot disk ABI");
        } catch (IOException | RuntimeException error) {
            wasi.close();
            throw new IllegalStateException("Cannot load Wasm boot disk", error);
        }
    }

    private HostFunction function(String name, int parameters, ImportCall callback) {
        return new HostFunction("cc", name,
            FunctionType.of(Collections.nCopies(parameters, ValType.I32), List.of(ValType.I32)),
            (vm, args) -> new long[]{callback.apply(vm, args)});
    }

    private int setResponse(byte[] bytes) {
        if (bytes.length > WireCodec.MAX_BYTES) throw new IllegalArgumentException("Host response exceeds limit");
        if (response != null) throw new IllegalStateException("Previous host response was not consumed");
        response = bytes;
        return bytes.length;
    }

    private int dispatch(Instance vm, long[] args, boolean continuation) {
        int size = (int) args[1];
        if (size < 4 || size > WireCodec.MAX_BYTES) throw new IllegalArgumentException("Invalid host request size");
        var request = WireCodec.decodeValues(vm.memory().readBytes((int) args[0], size));
        if (request.isEmpty()) throw new IllegalArgumentException("Empty host request");
        String name = WireCodec.text(request.getFirst());
        HostResult reply = continuation
            ? host.resume(name, request.subList(1, request.size()))
            : host.call(name, request.subList(1, request.size()));
        return setResponse(WireCodec.encodeValues(reply.transportValues()));
    }

    public Result start() {
        if (started || closed) throw new IllegalStateException("Computer already started or closed");
        started = true;
        instructions = INSTRUCTION_BUDGET;
        try { return decodeResult((int) call("cc_boot")); }
        catch (RuntimeException error) { return failure(error); }
    }

    public Result handleEvent(String name, Object[] arguments) {
        if (!started || closed) throw new IllegalStateException("Computer is not running");
        if (result.state() != State.WAITING) return result;
        if (result.filter() != null && !result.filter().equals(name) && !"terminate".equals(name)) return result;
        instructions = INSTRUCTION_BUDGET;
        try {
            var event = new ArrayList<Object>();
            event.add(name);
            if (arguments != null) event.addAll(Arrays.asList(arguments));
            byte[] bytes = WireCodec.encodeValues(event);
            int pointer = (int) call("cc_alloc", bytes.length);
            if (pointer == 0) throw new IllegalStateException("Boot disk is out of memory");
            instance.memory().write(pointer, bytes);
            // Ownership transfers to cc_event, which frees the event buffer before resuming.
            return decodeResult((int) call("cc_event", pointer, bytes.length));
        } catch (RuntimeException error) { return failure(error); }
    }

    private Result decodeResult(int status) {
        int size = (int) call("cc_result_length");
        if (size < 4 || size > WireCodec.MAX_BYTES) throw new IllegalArgumentException("Invalid guest result size");
        var values = WireCodec.decodeValues(instance.memory().readBytes((int) call("cc_result_pointer"), size));
        result = switch (status) {
            case 0 -> new Result(State.DONE, null, values, null);
            case 1 -> new Result(State.WAITING,
                !values.isEmpty() && values.getFirst() instanceof byte[] ? WireCodec.text(values.getFirst()) : null,
                List.of(), null);
            default -> new Result(State.FAILED, null, List.of(),
                values.isEmpty() ? "Boot disk failed" : display(values.getFirst()));
        };
        return result;
    }

    private static String display(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    private Result failure(RuntimeException error) {
        return result = new Result(State.FAILED, null, List.of(), error.toString());
    }

    private long call(String export, long... args) {
        long[] values = instance.export(export).apply(args);
        return values == null || values.length == 0 ? 0 : values[0];
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        // Releasing the instance releases all guest memory. Never run guest finalizers on shutdown.
        instance = null;
        response = null;
        wasi.close();
    }

    @FunctionalInterface private interface ImportCall { int apply(Instance vm, long[] args); }
}
