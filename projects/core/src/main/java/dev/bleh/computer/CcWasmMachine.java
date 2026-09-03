package dev.bleh.computer;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.ILuaFunction;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaValues;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.core.CoreConfig;
import dan200.computercraft.core.lua.ILuaMachine;
import dan200.computercraft.core.lua.MachineEnvironment;
import dan200.computercraft.core.lua.MachineException;
import dan200.computercraft.core.lua.MachineResult;
import dev.bleh.wasm.ComputerHost;
import dev.bleh.wasm.HostFunctionRef;
import dev.bleh.wasm.HostResult;
import dev.bleh.wasm.WasmComputer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CC:T's ILuaMachine adapter backed by the embedded Lua Wasm computer.
 *
 * <p>The CC API remains the boundary for this machine.  API methods and
 * callbacks execute on the computer thread, while world work continues to be
 * scheduled by the supplied {@link ILuaContext}.  Values are translated at
 * that boundary so Java strings use CC's byte-preserving string convention.</p>
 */
public final class CcWasmMachine implements ILuaMachine {
    private static final int MAX_FUNCTIONS = 65_536;
    private static final int MAX_CALLBACKS = 1_024;
    private final MachineEnvironment environment;
    private final ILuaContext context;
    private final Map<String, FunctionBinding> functions = new LinkedHashMap<>();
    private final Map<String, ILuaCallback> callbacks = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Object> objects = new IdentityHashMap<>();
    private final byte[] bios;
    private final String biosName = "@bios.lua";
    private final WasmComputer computer;

    private int nextFunction;
    private int nextCallback;
    private boolean closed;
    private boolean started;

    public CcWasmMachine(MachineEnvironment environment, InputStream bios) throws IOException, MachineException {
        this.environment = Objects.requireNonNull(environment, "environment");
        context = environment.context();
        this.bios = bios.readAllBytes();
        if (this.bios.length == 0) throw new MachineException("Cannot load empty BIOS");

        var globals = new LinkedHashMap<String, Object>();
        globals.put("_HOST", environment.hostString());
        globals.put("_CC_DEFAULT_SETTINGS", CoreConfig.defaultComputerSettings);
        var methods = new LinkedHashMap<String, Object>();
        var modules = new LinkedHashMap<String, Object>();
        for (var api : environment.apis()) {
            var object = luaObject(api);
            for (var name : api.getNames()) {
                globals.put(name, object);
                addMethods(methods, name, object);
            }
            var module = api.getModuleName();
            if (module != null) {
                modules.put(module, object);
            }
        }

        var bootData = Arrays.asList(this.bios, biosName, methods, globals, modules);
        var host = new ComputerHost() {
            @Override
            public HostResult call(String method, List<Object> args) {
                return invoke(method, args);
            }

            @Override
            public HostResult resume(String token, List<Object> event) {
                return resumeCallback(token, event);
            }

            @Override
            public byte[] bootData() {
                return dev.bleh.wasm.WireCodec.encodeValues(bootData);
            }

            @Override
            public boolean isInterrupted() {
                return environment.timeout().isHardAborted();
            }
        };
        computer = new WasmComputer(host);
    }

    private void addMethods(Map<String, Object> methods, String name, Object object) {
        if (object instanceof Map<?, ?> map) {
            var names = new ArrayList<String>();
            for (var key : map.keySet()) if (key instanceof String s) names.add(s);
            methods.put(name, names);
        }
    }

    @Override
    public MachineResult handleEvent(@Nullable String eventName, @Nullable Object @Nullable [] arguments) {
        if (closed) throw new IllegalStateException("Machine has been closed");
        if (environment.timeout().isHardAborted()) {
            close();
            return MachineResult.TIMEOUT;
        }
        try {
            if (started && eventName == null) return MachineResult.OK;
            var result = !started
                ? startComputer()
                : computer.handleEvent(eventName, adaptResults(arguments).toArray());
            if (environment.timeout().isHardAborted()) {
                close();
                return MachineResult.TIMEOUT;
            }
            if (result.state() == WasmComputer.State.FAILED) {
                close();
                return MachineResult.error(result.error() == null ? "Wasm execution failed" : result.error());
            }
            if (result.state() == WasmComputer.State.DONE) {
                close();
                return MachineResult.OK;
            }
            // WAITING means the guest yielded for an event. PAUSE is reserved
            // for a scheduler time slice, and would make ComputerExecutor call
            // us with a null event and accidentally restart the boot coroutine.
            return MachineResult.OK;
        } catch (RuntimeException exception) {
            close();
            return MachineResult.error(message(exception));
        }
    }

    private WasmComputer.Result startComputer() {
        started = true;
        return computer.start();
    }

    @Override
    public void printExecutionState(StringBuilder out) {
        out.append("Wasm CC machine");
        if (closed) out.append(" (closed)");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        callbacks.clear();
        functions.clear();
        objects.clear();
        computer.close();
    }

    private HostResult invoke(String method, List<Object> args) {
        if (closed) return HostResult.error("Computer is closed");
        var binding = functions.get(functionId(method));
        if (binding == null) return HostResult.error("Unknown CC API function: " + method);
        try {
            var result = binding.method().apply(binding.target(), context, new Arguments(args));
            return methodResult(result);
        } catch (LuaException exception) {
            return HostResult.error(message(exception));
        } catch (RuntimeException exception) {
            return HostResult.error(message(exception));
        }
    }

    private HostResult resumeCallback(String token, List<Object> event) {
        if (closed) return HostResult.error("Computer is closed");
        var callback = callbacks.get(token);
        if (callback == null) return HostResult.error("Unknown CC callback: " + token);
        try {
            var callbackArgs = new Object[event.size()];
            for (var i = 0; i < event.size(); i++) callbackArgs[i] = Arguments.ccValue(event.get(i));
            var result = callback.resume(callbackArgs);
            var response = methodResult(result, token);
            if (result.getCallback() == null) callbacks.remove(token);
            return response;
        } catch (LuaException exception) {
            callbacks.remove(token);
            return HostResult.error(message(exception));
        } catch (RuntimeException exception) {
            callbacks.remove(token);
            return HostResult.error(message(exception));
        }
    }

    private HostResult methodResult(MethodResult result) {
        return methodResult(result, null);
    }

    private HostResult methodResult(MethodResult result, @Nullable String existingToken) {
        var callback = result.getCallback();
        var raw = result.getResult();
        if (callback == null) return HostResult.completed(adaptResults(raw));

        if (existingToken == null && callbacks.size() >= MAX_CALLBACKS) {
            return HostResult.error("Too many pending CC API callbacks");
        }
        var token = existingToken == null ? "cc-callback-" + nextCallback++ : existingToken;
        callbacks.put(token, callback);
        String filter = null;
        if (raw != null && raw.length == 1 && raw[0] instanceof String s) filter = s;
        return HostResult.await(token, filter);
    }

    private List<Object> adaptResults(@Nullable Object[] values) {
        if (values == null || values.length == 0) return List.of();
        var result = new ArrayList<Object>(values.length);
        var seen = new IdentityHashMap<Object, Object>();
        for (var value : values) result.add(adapt(value, seen));
        return result;
    }

    private Object adapt(@Nullable Object value, IdentityHashMap<Object, Object> seen) {
        if (value == null || value instanceof Boolean || value instanceof Number || value instanceof HostFunctionRef) return value;
        if (value instanceof String string) return bytes(string);
        if (value instanceof byte[]) return value;
        if (value instanceof ByteBuffer buffer) {
            var copy = new byte[buffer.remaining()];
            buffer.duplicate().get(copy);
            return copy;
        }
        var existing = seen.get(value);
        if (existing != null) return existing;
        if (value instanceof ILuaFunction function) {
            var ref = registerFunction((target, ignoredContext, args) -> function.call(args), function, "function");
            seen.put(value, ref);
            return ref;
        }
        if (value instanceof Map<?, ?> map) {
            var output = new LinkedHashMap<Object, Object>();
            seen.put(value, output);
            for (var entry : map.entrySet()) {
                output.put(adapt(entry.getKey(), seen), adapt(entry.getValue(), seen));
            }
            return output;
        }
        if (value instanceof Iterable<?> iterable) {
            var output = new ArrayList<Object>();
            seen.put(value, output);
            for (var child : iterable) output.add(adapt(child, seen));
            return output;
        }
        if (value instanceof Object[] array) {
            var output = new ArrayList<Object>(array.length);
            seen.put(value, output);
            for (var child : array) output.add(adapt(child, seen));
            return output;
        }
        var object = luaObject(value);
        if (!object.isEmpty()) {
            seen.put(value, object);
            return object;
        }
        return null;
    }

    private Map<String, Object> luaObject(Object object) {
        var prior = objects.get(object);
        if (prior instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked") var result = (Map<String, Object>) map;
            return result;
        }
        var result = new LinkedHashMap<String, Object>();
        objects.put(object, result);
        environment.luaMethods().forEachMethod(object, (target, name, method, ignored) ->
            result.put(name, registerFunction(method, target, name)));
        return result;
    }

    private HostFunctionRef registerFunction(dan200.computercraft.core.methods.LuaMethod method, Object target, String name) {
        if (functions.size() >= MAX_FUNCTIONS) throw new IllegalStateException("CC API handle limit exceeded; reboot the computer");
        var id = "f" + nextFunction++;
        functions.put(id, new FunctionBinding(target, method, name));
        return new HostFunctionRef(id);
    }

    private static String functionId(String method) {
        var prefix = "__handle.";
        return method.startsWith(prefix) ? method.substring(prefix.length()) : method;
    }

    private static byte[] bytes(String string) {
        var encoded = LuaValues.encode(string);
        var output = new byte[encoded.remaining()];
        encoded.get(output);
        return output;
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }

    private record FunctionBinding(Object target, dan200.computercraft.core.methods.LuaMethod method, String name) {}

    private static final class Arguments implements IArguments {
        private final List<Object> values;

        Arguments(List<Object> values) {
            this.values = values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
        }

        @Override
        public int count() {
            return values.size();
        }

        @Override
        public Object get(int index) {
            return index >= 0 && index < values.size() ? ccValue(values.get(index)) : null;
        }

        @Override
        public String getType(int index) {
            return LuaValues.getType(get(index));
        }

        @Override
        public IArguments drop(int count) {
            if (count < 0) throw new IllegalArgumentException("count cannot be negative");
            return new Arguments(values.subList(Math.min(count, values.size()), values.size()));
        }

        static Object ccValue(Object value) {
            if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.ISO_8859_1);
            if (value instanceof Map<?, ?> map) {
                var result = new LinkedHashMap<Object, Object>();
                for (var entry : map.entrySet()) result.put(ccValue(entry.getKey()), ccValue(entry.getValue()));
                return result;
            }
            if (value instanceof Iterable<?> iterable) {
                var result = new ArrayList<Object>();
                for (var child : iterable) result.add(ccValue(child));
                return result;
            }
            return value;
        }
    }
}
