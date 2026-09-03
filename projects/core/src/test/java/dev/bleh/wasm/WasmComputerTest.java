package dev.bleh.wasm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WasmComputerTest {
    @Test void compatibilityFixtureRunsInsideTheBundledWasmImage() throws Exception {
        byte[] source;
        try (var input = getClass().getResourceAsStream("/wasm/guest-bootstrap-smoke.lua")) {
            assertNotNull(input);
            source = input.readAllBytes();
        }
        var host = new ComputerHost() {
            @Override public byte[] bootData() {
                return WireCodec.encodeValues(Arrays.asList(source, "@fixture.lua",
                    Map.of("cc_test", List.of("return_values")),
                    Map.of("_HOST", "fixture", "_CC_TEST_FN", new HostFunctionRef("fn-1"))));
            }
            @Override public HostResult call(String name, List<Object> args) {
                return switch (name) {
                    case "__handle.fn-1" -> HostResult.completed("initial-result");
                    case "cc_test.return_values" -> HostResult.completed(Map.of("callback", new HostFunctionRef("fn-2")));
                    case "__handle.fn-2" -> HostResult.completed("returned-result");
                    default -> throw new AssertionError(name);
                };
            }
            @Override public HostResult resume(String token, List<Object> event) { throw new AssertionError(token); }
        };
        try (var vm = new WasmComputer(host)) {
            var result = vm.start();
            assertEquals(WasmComputer.State.DONE, result.state(), result.error());
            assertEquals(List.of(true), result.values());
        }
    }

    @Test void bootDiskCallsActualImportsAndResumesEvents() {
        var host = new ComputerHost() {
            int calls;
            @Override public byte[] bootData() {
                return WireCodec.encodeValues(Arrays.asList("""
                    local ok = turtle.forward()
                    assert(ok)
                    local f = turtle.inspect()
                    assert(f() == 'host-object-result')
                    return 'boot-disk-ok'
                    """, "@test.lua", Map.of(), Map.of("turtle", Map.of(
                    "forward", new HostFunctionRef("forward"), "inspect", new HostFunctionRef("inspect")))));
            }
            @Override public HostResult call(String name, List<Object> args) {
                calls++;
                return switch (name) {
                    case "__handle.forward" -> HostResult.await("move1", "turtle_response");
                    case "__handle.inspect" -> HostResult.completed(new HostFunctionRef("file-read"));
                    case "__handle.file-read" -> HostResult.completed("host-object-result");
                    default -> throw new AssertionError(name);
                };
            }
            @Override public HostResult resume(String token, List<Object> event) {
                assertEquals("move1", token);
                assertEquals("turtle_response", WireCodec.text(event.getFirst()));
                assertEquals(7.0, event.get(1));
                return HostResult.completed(true);
            }
        };
        try (var vm = new WasmComputer(host)) {
            var waiting = vm.start();
            assertEquals(WasmComputer.State.WAITING, waiting.state(), waiting.error());
            assertEquals("turtle_response", waiting.filter());
            assertSame(waiting, vm.handleEvent("key", new Object[]{1}));
            var done = vm.handleEvent("turtle_response", new Object[]{7, true});
            assertEquals(WasmComputer.State.DONE, done.state(), done.error());
            assertEquals("boot-disk-ok", WireCodec.text(done.values().getFirst()));
            assertEquals(3, host.calls);
        }
    }

    @Test void wireCodecPreservesBinaryValuesAndNilTuples() {
        var bytes = new byte[]{0, -1, 65};
        var values = WireCodec.decodeValues(WireCodec.encodeValues(Arrays.asList(
            null, true, 42, bytes, new HostFunctionRef("id"), Map.of("nested", List.of(false, 7)), null)));
        assertEquals(7, values.size());
        assertNull(values.getFirst());
        assertNull(values.getLast());
        assertArrayEquals(bytes, (byte[]) values.get(3));
        assertEquals(new HostFunctionRef("id"), values.get(4));
        assertEquals(Map.of("nested", Map.of(1.0, false, 2.0, 7.0)), values.get(5));
        assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeValues(new byte[]{-1,-1,-1,-1}));
        assertThrows(IllegalArgumentException.class, () -> WireCodec.encodeValues(List.of(new byte[WireCodec.MAX_BYTES])));
    }
}
