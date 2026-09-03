package dev.bleh.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Version 1 computer ABI: little-endian value vector, with binary-safe strings. */
public final class WireCodec {
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_VALUES = 16_384;
    private WireCodec() {}

    public static byte[] encodeValues(List<?> values) {
        var writer = new Writer();
        writer.u32(values.size());
        for (var value : values) writer.value(value, 0);
        return writer.output.toByteArray();
    }

    public static List<Object> decodeValues(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Host message exceeds byte limit");
        var reader = new Reader(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
        int count = reader.count();
        var values = new ArrayList<Object>(count);
        for (int i = 0; i < count; i++) values.add(reader.value(0));
        if (reader.input.hasRemaining()) throw new IllegalArgumentException("Trailing bytes in host message");
        return values;
    }

    public static String text(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (value instanceof String string) return string;
        throw new IllegalArgumentException("Expected a string");
    }

    private static final class Writer {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        int remaining = MAX_VALUES;

        void put(int value) {
            if (output.size() >= MAX_BYTES) throw new IllegalArgumentException("Host message exceeds byte limit");
            output.write(value);
        }
        void u32(int value) { for (int i = 0; i < 4; i++) put(value >>> (8 * i)); }
        void bytes(byte[] bytes) {
            u32(bytes.length);
            if (bytes.length > MAX_BYTES - output.size()) throw new IllegalArgumentException("Host message exceeds byte limit");
            output.writeBytes(bytes);
        }
        void value(Object value, int depth) {
            if (depth > 32 || --remaining < 0) throw new IllegalArgumentException("Host message exceeds complexity limit");
            if (value == null) put(0);
            else if (value instanceof Boolean b) put(b ? 2 : 1);
            else if (value instanceof Number n) {
                put(3);
                long bits = Double.doubleToRawLongBits(n.doubleValue());
                for (int i = 0; i < 8; i++) put((int) (bits >>> (8 * i)));
            } else if (value instanceof String s) { put(4); bytes(s.getBytes(StandardCharsets.UTF_8)); }
            else if (value instanceof byte[] b) { put(4); bytes(b); }
            else if (value instanceof ByteBuffer buffer) {
                var duplicate = buffer.duplicate();
                if (duplicate.remaining() > MAX_BYTES) throw new IllegalArgumentException("Host buffer exceeds limit");
                var b = new byte[duplicate.remaining()]; duplicate.get(b); put(4); bytes(b);
            } else if (value instanceof HostFunctionRef ref) { put(6); bytes(ref.id().getBytes(StandardCharsets.UTF_8)); }
            else if (value instanceof Map<?, ?> map) {
                if (active.put(value, true) != null) throw new IllegalArgumentException("Cyclic host table");
                put(5); u32(map.size());
                for (var entry : map.entrySet()) {
                    if (entry.getKey() == null) throw new IllegalArgumentException("Nil table key");
                    value(entry.getKey(), depth + 1); value(entry.getValue(), depth + 1);
                }
                active.remove(value);
            } else if (value instanceof List<?> list) {
                if (active.put(value, true) != null) throw new IllegalArgumentException("Cyclic host list");
                put(5); u32(list.size());
                for (int i = 0; i < list.size(); i++) { value(i + 1, depth + 1); value(list.get(i), depth + 1); }
                active.remove(value);
            } else throw new IllegalArgumentException("Unsupported host value: " + value.getClass().getName());
        }
    }

    private static final class Reader {
        final ByteBuffer input;
        int remaining = MAX_VALUES;
        Reader(ByteBuffer input) { this.input = input; }
        int count() {
            int count = input.getInt();
            if (count < 0 || count > MAX_VALUES) throw new IllegalArgumentException("Invalid value count");
            return count;
        }
        byte[] bytes() {
            int size = input.getInt();
            if (size < 0 || size > input.remaining()) throw new IllegalArgumentException("Invalid string size");
            var bytes = new byte[size]; input.get(bytes); return bytes;
        }
        Object value(int depth) {
            if (depth > 32 || --remaining < 0) throw new IllegalArgumentException("Host message exceeds complexity limit");
            return switch (input.get()) {
                case 0 -> null;
                case 1 -> false;
                case 2 -> true;
                case 3 -> input.getDouble();
                case 4 -> bytes();
                case 5 -> {
                    int count = count();
                    var map = new LinkedHashMap<Object, Object>();
                    for (int i = 0; i < count; i++) {
                        Object key = value(depth + 1);
                        // Java map keys need value equality while Lua keys are
                        // arbitrary byte strings, including invalid UTF-8.
                        if (key instanceof byte[] bytes) key = new String(bytes, StandardCharsets.ISO_8859_1);
                        if (key == null) throw new IllegalArgumentException("Nil table key");
                        map.put(key, value(depth + 1));
                    }
                    yield map;
                }
                case 6 -> new HostFunctionRef(new String(bytes(), StandardCharsets.UTF_8));
                default -> throw new IllegalArgumentException("Unknown host value tag");
            };
        }
    }
}
