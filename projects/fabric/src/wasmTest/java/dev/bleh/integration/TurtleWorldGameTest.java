package dev.bleh.integration;

import dan200.computercraft.api.filesystem.MountConstants;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity;
import dev.bleh.computer.CcWasmMachine;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real block entities, world ticks, the interactive shell, and turtle commands. */
public final class TurtleWorldGameTest {
    @GameTest(structure = "bleh-test:turtle_arena", maxTicks = 6000)
    public void shellMovesAndPlacesWithAWasmTurtle(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        var start = new BlockPos(2, 1, 1);
        helper.setBlock(start, ModRegistry.Blocks.TURTLE_NORMAL.get());
        var turtle = (TurtleBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(start));
        helper.assertTrue(turtle != null, "Turtle block entity was not created");
        turtle.setDirection(Direction.SOUTH);
        turtle.getAccess().setFuelLevel(0);
        turtle.setItem(0, new ItemStack(Items.COAL, 1));
        turtle.setItem(1, new ItemStack(Items.DIRT, 2));
        var serverComputer = turtle.createServerComputer();
        var mount = serverComputer.createRootMount();
        try (var source = getClass().getResourceAsStream("/wasm/verify_turtle.lua");
             var output = mount.openFile("verify_turtle.lua", MountConstants.WRITE_OPTIONS)) {
            if (source == null) throw new IOException("Missing test Lua program");
            var bytes = ByteBuffer.wrap(source.readAllBytes());
            while (bytes.hasRemaining()) output.write(bytes);
        }
        helper.runBeforeTestEnd(serverComputer::shutdown);
        var coreField = ServerComputer.class.getDeclaredField("computer");
        coreField.setAccessible(true);
        var computer = (Computer) coreField.get(serverComputer);
        var terminal = computer.getAPIEnvironment().getTerminal();
        var commandSent = new boolean[1];
        var prompt = new String[1];
        serverComputer.turnOn();

        helper.startSequence().thenWaitUntil(() -> {
            // The game itself ticks the turtle and ServerComputer. Only slow
            // the headless test loop so asynchronous workers get wall time.
            try { Thread.sleep(5); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            var screen = new StringBuilder();
            synchronized (terminal) {
                for (int y = 0; y < terminal.getHeight(); y++) screen.append(terminal.getLine(y)).append('\n');
            }
            if (!commandSent[0] && screen.toString().contains("> ")) {
                assertWasmMachine(helper, computer);
                prompt[0] = screen.toString();
                commandSent[0] = true;
                serverComputer.queueEvent("paste", new Object[]{"verify_turtle"});
                serverComputer.queueEvent("key", new Object[]{257, false});
                serverComputer.queueEvent("key_up", new Object[]{257});
            }
            helper.assertTrue(screen.toString().contains("WASM_TURTLE_OK"),
                "Shell/turtle program has not completed.\n" + screen);

            var end = new BlockPos(2, 1, 3);
            helper.assertBlockPresent(Blocks.AIR, start);
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 1, 2));
            helper.assertBlockPresent(Blocks.DIRT, new BlockPos(1, 1, 2));
            helper.assertBlockPresent(ModRegistry.Blocks.TURTLE_NORMAL.get(), end);
            var moved = (TurtleBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(end));
            helper.assertTrue(moved.getComputerID() == serverComputer.getID(), "Moving lost the computer ID");
            helper.assertTrue(moved.getDirection() == Direction.SOUTH, "Turtle rotation is wrong");
            helper.assertTrue(moved.getAccess().getFuelLevel() == 78, "Movement consumed the wrong fuel");
            helper.assertTrue(moved.getItem(0).isEmpty(), "Refuelling did not consume the coal");
            helper.assertTrue(moved.getItem(1).is(Items.DIRT) && moved.getItem(1).getCount() == 1,
                "Placement did not preserve the remaining inventory");
            helper.assertTrue(moved.getServerComputer() == serverComputer, "Movement replaced the live computer");
            try {
                var result = ByteBuffer.allocate(512);
                try (var file = mount.openForRead("wasm-turtle-result.txt")) { file.read(result); }
                var text = new String(result.array(), 0, result.position(), StandardCharsets.UTF_8);
                helper.assertTrue(text.contains("fuel=78; inventory=ok"), "Missing persisted Lua result: " + text);
                var evidence = Path.of(System.getProperty("bleh.test.evidence", "bleh-turtle-evidence.txt"));
                Files.createDirectories(evidence.toAbsolutePath().getParent());
                Files.writeString(evidence, "PASS: Minecraft 26.2 / CC:T 1.120.2 / Endive\n" +
                    "Runtime: " + CcWasmMachine.class.getName() + "\n" +
                    "Turtle ID: " + serverComputer.getID() + "\n" +
                    "World start: " + helper.absolutePos(start) + "\nWorld end: " + helper.absolutePos(end) + "\n" +
                    "Placed dirt: " + helper.absolutePos(new BlockPos(1, 1, 2)) + "\n" + text +
                    "\n\nBefore input:\n" + prompt[0] + "\nAfter input:\n" + screen);
            } catch (IOException e) { throw new AssertionError("Cannot verify persisted turtle result", e); }
        }).thenSucceed();
    }

    private static void assertWasmMachine(GameTestHelper helper, Computer computer) {
        try {
            var executorField = Computer.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            var executor = executorField.get(computer);
            var machineField = executor.getClass().getDeclaredField("machine");
            machineField.setAccessible(true);
            var machine = machineField.get(executor);
            helper.assertTrue(machine instanceof CcWasmMachine, "Turtle did not use Wasm: " + machine);
        } catch (ReflectiveOperationException e) { throw new AssertionError("Cannot inspect turtle runtime", e); }
    }
}
