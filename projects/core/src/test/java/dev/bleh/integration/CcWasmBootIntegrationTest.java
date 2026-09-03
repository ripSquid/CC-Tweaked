package dev.bleh.integration;

import dan200.computercraft.core.ComputerContext;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.computer.ComputerEnvironment;
import dan200.computercraft.core.computer.GlobalEnvironment;
import dan200.computercraft.core.filesystem.FileMount;
import dan200.computercraft.core.filesystem.JarMount;
import dan200.computercraft.core.filesystem.MemoryMount;
import dan200.computercraft.core.metrics.MetricsObserver;
import dan200.computercraft.core.terminal.Terminal;
import dev.bleh.computer.CcWasmMachine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the unchanged CC:T BIOS from the CC 26.2 resource jar through the
 * Endive/Wasm ILuaMachine factory. This is intentionally a direct core harness:
 * it does not replace the BIOS with a test Lua program or mock Computer.
 */
public class CcWasmBootIntegrationTest {
    private static final String STARTUP = """
        local api = require("bleh.test")
        assert(api.echo(string.char(0, 255)) == string.char(0, 255))
        parallel.waitForAll(function()
            assert(api.waitCommand() == string.char(255))
        end, function()
            os.queueEvent("bleh_command", 1, "wrong")
            os.queueEvent("bleh_command", 2, string.char(255))
        end)
        local handle = assert(fs.open(\"bleh-wasm-boot.txt\", \"w\"))
        handle.write(\"filesystem-ok\")
        handle.close()
        assert(fs.exists(\"bleh-wasm-boot.txt\"))
        term.write(\"BLEH_WASM_BOOT_READY\")
        local event, payload = os.pullEvent(\"bleh_wasm_test\")
        assert(event == \"bleh_wasm_test\" and payload == \"payload\")
        local first, second = false, false
        parallel.waitForAll(function() sleep(0.05); first = true end,
            function() sleep(0.1); second = true end)
        assert(first and second)
        local check = assert(fs.open("bleh-wasm-boot.txt", "r"))
        assert(check.readAll() == "filesystem-ok")
        check.close()
        local result = assert(fs.open("bleh-wasm-result.bin", "wb"))
        result.write(string.char(0, 255) .. "event-ok")
        result.close()
        term.write(\" BLEH_WASM_EVENT_OK\")
        os.shutdown()
        """;

    @Test
    void bootsRealBiosWithWasmFactoryAndResumesQueuedEvent() throws Exception {
        var root = new MemoryMount().addFile("startup.lua", STARTUP);
        var environment = new TestEnvironment(root);
        var context = ComputerContext.builder(environment)
            .computerThreads(1)
            .luaFactory(CcWasmMachine::new)
            .build();
        var computer = new Computer(context, environment, new Terminal(240, 24, true), 0);
        computer.addApi(new TestApi());
        var terminal = computer.getAPIEnvironment().getTerminal();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        var wasOn = false;
        var startupReady = false;
        var eventQueued = false;

        try {
            computer.turnOn();
            while (System.nanoTime() < deadline) {
                computer.tick();
                wasOn |= computer.isOn();

                // The marker is emitted immediately before startup.lua waits. Queue
                // only then, so the BIOS/shell cannot consume the event first.
                startupReady |= terminalText(terminal).contains("BLEH_WASM_BOOT_READY");
                if (computer.isOn() && startupReady && !eventQueued) {
                    eventQueued = true;
                    computer.queueEvent("bleh_wasm_test", new Object[]{"payload"});
                }

                if (!computer.isOn() && wasOn) break;
                Thread.sleep(20);
            }

            var output = terminalText(terminal);
            assertTrue(wasOn, "CC computer never turned on\n" + output);
            assertTrue(environment.biosRequested, "Computer did not load CC:T's BIOS resource\n" + output);
            assertTrue(startupReady, "startup.lua did not reach its event wait\n" + output);
            assertTrue(eventQueued, "CC computer never accepted the test event\n" + output);
            assertFalse(computer.isOn(), "startup program did not complete and shut down\n" + output);
            assertTrue(root.exists("bleh-wasm-boot.txt"), "startup.lua did not use the root filesystem\n" + output);
            try (var channel = root.openForRead("bleh-wasm-boot.txt")) {
                var bytes = ByteBuffer.allocate(32);
                channel.read(bytes);
                org.junit.jupiter.api.Assertions.assertEquals("filesystem-ok",
                    new String(bytes.array(), 0, bytes.position(), java.nio.charset.StandardCharsets.US_ASCII));
            }

            // Shutdown clears the terminal. The second file proves the event
            // resumed startup, both parallel timers completed, and binary data survived.
            assertTrue(root.exists("bleh-wasm-result.bin"), "Startup did not finish\n" + output);
            try (var channel = root.openForRead("bleh-wasm-result.bin")) {
                var bytes = ByteBuffer.allocate(32);
                channel.read(bytes);
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new byte[]{0, -1, 'e', 'v', 'e', 'n', 't', '-', 'o', 'k'},
                    java.util.Arrays.copyOf(bytes.array(), bytes.position()));
            }
        } finally {
            computer.shutdown();
            context.ensureClosed(5, TimeUnit.SECONDS);
        }
    }

    private static String terminalText(Terminal terminal) {
        var output = new StringBuilder();
        for (var line = 0; line < terminal.getHeight(); line++) {
            if (line > 0) output.append('\n');
            output.append(terminal.getLine(line));
        }
        return output.toString();
    }

    public static final class TestApi implements dan200.computercraft.api.lua.ILuaAPI {
        @Override public String[] getNames() { return new String[0]; }
        @Override public String getModuleName() { return "bleh.test"; }
        @dan200.computercraft.api.lua.LuaFunction
        public final String echo(String value) { return value; }
        @dan200.computercraft.api.lua.LuaFunction
        public final dan200.computercraft.api.lua.MethodResult waitCommand() {
            return dan200.computercraft.api.lua.MethodResult.pullEvent("bleh_command", event -> {
                if (event.length < 3 || !Double.valueOf(2).equals(event[1])) return waitCommand();
                org.junit.jupiter.api.Assertions.assertEquals("\u00ff", event[2]);
                return dan200.computercraft.api.lua.MethodResult.of(event[2]);
            });
        }
    }

    /** A resource-backed global environment plus an in-memory computer disk. */
    private static final class TestEnvironment implements ComputerEnvironment, GlobalEnvironment {
        private final MemoryMount root;
        private boolean biosRequested;

        private TestEnvironment(MemoryMount root) {
            this.root = root;
        }

        @Override
        public int getDay() {
            return 0;
        }

        @Override
        public double getTimeOfDay() {
            return 0;
        }

        @Override
        public MetricsObserver getMetrics() {
            return MetricsObserver.discard();
        }

        @Override
        public MemoryMount createRootMount() {
            return root;
        }

        @Override
        public String getHostString() {
            return "ComputerCraft 1.120.2 (Bleh Wasm integration test)";
        }

        @Override
        public String getUserAgent() {
            return "ComputerCraft/1.120.2 Bleh-Wasm-Integration-Test";
        }

        @Override
        public dan200.computercraft.api.filesystem.Mount createResourceMount(String domain, String subPath) {
            if (!"computercraft".equals(domain)) return null;
            var source = Computer.class.getProtectionDomain().getCodeSource().getLocation();
            try {
                var file = new File(source.toURI());
                var path = "data/" + domain + "/" + subPath;
                if (file.isFile()) return new JarMount(file, path);
                // Gradle keeps compiled classes and processed resources in
                // different directories when testing CC:T from source.
                var resource = getClass().getClassLoader().getResource(path);
                if (resource == null) throw new IllegalStateException("Missing ROM: " + path);
                return new FileMount(java.nio.file.Path.of(resource.toURI()));
            } catch (URISyntaxException | java.io.IOException exception) {
                throw new IllegalStateException("Cannot mount CC:T ROM", exception);
            }
        }

        @Override
        public InputStream createResourceFile(String domain, String subPath) {
            if ("computercraft".equals(domain) && "lua/bios.lua".equals(subPath)) biosRequested = true;
            return getClass().getClassLoader().getResourceAsStream("data/" + domain + "/" + subPath);
        }
    }
}
