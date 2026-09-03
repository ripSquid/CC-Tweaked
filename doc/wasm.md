# Wasm computer and Lua boot disk

`WasmComputer` is the language-independent Java runtime. It loads a Wasm image
with the computer ABI, runs it in Endive 1.0.1, and exchanges API requests and
events. The default `/wasm/boot-lua.wasm` image contains PUC Lua 5.2.4, its GC and
coroutines, `boot.c`, and the embedded `bootstrap.lua` compatibility layer.
The Java runtime never drives a Lua stack or evaluates Lua source itself.

`CcWasmMachine` adapts CC:T's `ILuaMachine` interface and `MachineEnvironment`.
`ComputerCraft.init()` selects it through `ServerContext.luaMachine` for Fabric
server computers. The fork has no dependency on the separate Bleh mod or its
mixin. Other backends retain their existing runtime selection. CC:T
continues to own the computer scheduler, terminal, mounts, peripherals, turtle
commands, and main-thread work. API suppliers receive the original `ILuaContext`.
CraftOS BIOS/ROM resources come from the installed CC:T jar, without modification.

## Computer ABI 1

Exports of the boot image:

| Export | Contract |
| --- | --- |
| `_initialize()` | Initialize the WASI reactor once. |
| `cc_abi_version() -> i32` | Must return 1. |
| `cc_boot() -> i32` | Boot once; returns 0 done, 1 waiting, 2 failed. |
| `cc_alloc(size) -> pointer` | Allocate an event buffer; 0 means allocation failed. |
| `cc_event(pointer, length) -> i32` | Take ownership of the event buffer, free it, resume. |
| `cc_result_pointer/cc_result_length() -> i32` | Read the resulting value vector. |

Imports in module `cc`:

| Import | Contract |
| --- | --- |
| `boot_data() -> length` | Request boot configuration. |
| `call(pointer, length) -> length` | Request `[method, arguments...]`. |
| `resume(pointer, length) -> length` | Resume `[token, eventName, arguments...]`. |
| `read_response(pointer, capacity) -> length` | Copy and consume the outstanding response. |

Only one response may be outstanding. Boot data for the Lua image is
`[biosBytes, chunkName, methodDescriptors, globals, preloadedModules]`.
The module has WASI Preview 1 imports for libc support, with no host mounts,
inherited environment, or standard streams.

Value vectors begin with a little-endian u32 count. Each value has a one-byte
tag: 0 nil, 1 false, 2 true, 3 little-endian f64, 4 byte string (u32 length then
bytes), 5 table (u32 entry count then key/value pairs), 6 host function reference
(u32 ID length then bytes). Lists become tables with 1-based numeric keys.
The CC adapter preserves its byte-string convention. String table keys decode
to Java ISO-8859-1 strings so arbitrary binary keys retain value equality.
Cyclic tables, Lua functions/userdata/threads, and table identity across the
boundary are not supported.

Host calls return `[true, values...]`, `[false, error]`, or
`["__bleh_wait", token, eventFilter]`. On a wait, the Lua trampoline yields inside
the caller's coroutine. CraftOS `parallel` can therefore run other coroutines.
A matching event invokes the saved CC `ILuaCallback`; a callback may wait again
with the same token. Returned file/peripheral objects expose issued function
references through the same import, with no hard-coded turtle command list.

## Build and validation

```sh
./gradlew :core:buildLuaWasm
./gradlew :core:test --tests 'dev.bleh.*'
./gradlew :fabric:runWasmGametest
./gradlew :fabric:jar
```

`projects/core/wasm/build.py` pins Lua 5.2.4 and WASI SDK 34 with SHA-256 checks. The native SDK is
build-time only. It uses standard Wasm exception handling and WASI SDK's
setjmp/longjmp support. Guest library sources are unmodified; unsafe `io`, `os`,
and native module loading are excluded. `bootstrap.lua` installs CC-facing
compatibility functions before loading the BIOS.

`projects/core/wasm/build.sha256` records guest source and output hashes. `verifyLuaWasm` runs
before packaging so editing bootstrap/C cannot silently leave a stale boot image.
On non-Linux-x86-64 systems, set `WASI_SDK_PATH` to an installed SDK 34.

The Fabric jar includes the Wasm module, Java runtime classes and Endive's four
nested dependencies. Install it as the CC:T mod on Minecraft 26.2 with Fabric
Loader and Fabric API; remove the upstream CC:T jar and the standalone Bleh
prototype when using this fork. No native library is needed at runtime.

The port retains the `dev.bleh` Java packages from the tested implementation.
Only the current computer ABI and Lua boot disk are included; the earlier Lua
stack-driven proof of concept is not part of this fork patch.

The dedicated `wasmTest` source set boots a real in-world turtle and types
`verify_turtle` at the standard CraftOS prompt. It checks movement, placement,
inspection, obstruction, fuel and inventory against Minecraft's world state,
and confirms the active machine is `CcWasmMachine`. It deliberately runs apart
from upstream `cctest`, whose managed-computer harness replaces the factory.
Reports: `projects/fabric/build/test-results/wasm-gametest.xml` and
`projects/fabric/build/reports/wasm/turtle-shell.txt`.

## Limits and remaining compatibility work

- 32 MiB linear memory per computer; initial memory 8 MiB, including 1 MiB C stack.
- 200 million Wasm instructions per boot/event, checked outside Lua. CC hard
  abort and Java thread interruption are checked every 4,096 instructions.
  Exceeding the budget stops the machine; resumable scheduler time slicing is
  not implemented. CPU-heavy programs may terminate instead of yielding fairly.
- Transfers: 1 MiB, 16,384 values, nesting depth 32. Pending callbacks: 1,024.
  Host function references: 65,536. References are retained until shutdown;
  repeated creation of handles can exhaust this cap even after closing them.
- Shutdown discards guest memory and callback/function maps; CC closes its
  filesystem resources. Abandoned coroutine callbacks have no individual GC.
- No `string.pack/unpack/packsize`, Cobalt `\u{...}` syntax extensions, or exact
  Cobalt debug/error semantics. UTF-8 and environment helpers are compatibility
  shims. Some C library callbacks cannot yield where Cobalt permits it.
- Built-in CC `pullEvent` callback paths are supported. Arbitrary third-party
  `MethodResult.yield` values/error-level adjustments and guest Lua callbacks
  passed to Java need further ABI work.
- External Wasm programs/boot media, persistent VM snapshots, and broad
  peripheral validation are future work. A headless Minecraft GameTest covers
  the normal turtle shell, real movement, placement, inspection, obstruction,
  refuelling, and inventory preservation (`TurtleWorldGameTest`).

## References and licenses

- [CC:T language compatibility](https://tweaked.cc/reference/feature_compat.html)
- [CC:T machine interface](https://github.com/cc-tweaked/CC-Tweaked/blob/mc-26.2/projects/core/src/main/java/dan200/computercraft/core/lua/ILuaMachine.java)
- [Lua 5.2 manual](https://www.lua.org/manual/5.2/manual.html)
- [WASI SDK exception support](https://github.com/WebAssembly/wasi-sdk/blob/main/SetjmpLongjmp.md)
- [Endive execution limits](https://endive.run/docs/advanced/cpu-limits/)

Lua, WASI libc, and compiler runtime notices are in `META-INF/licenses`.
Endive's nested jars include their own license metadata.
