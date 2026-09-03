#!/usr/bin/env python3
"""Build a pinned Lua 5.2.4 reactor; native tools are needed only at build time."""
from pathlib import Path
import hashlib
import os
import platform
import subprocess
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".cache"
LUA_SHA = "b9e2e4aad6789b3b63a056d442f7b39f0ecfca3ae0f1fc0ae4e9614401b69f4b"
SDK_SHA = "b761e3a0721dbae9c09a0059e5fdb2bf917d1b4a8a7b430fb3b5aafb0984b2c4"


def unpack(url, checksum):
    CACHE.mkdir(parents=True, exist_ok=True)
    archive = CACHE / url.rsplit("/", 1)[-1]
    if not archive.exists():
        print(f"Downloading {url}", flush=True)
        temporary = archive.with_suffix(".download")
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(archive)
    if hashlib.file_digest(archive.open("rb"), "sha256").hexdigest() != checksum:
        raise RuntimeError(f"Checksum mismatch: {archive}")
    with tarfile.open(archive) as tar:
        tar.extractall(CACHE, filter="data")


def main():
    unpack("https://www.lua.org/ftp/lua-5.2.4.tar.gz", LUA_SHA)
    if os.environ.get("WASI_SDK_PATH"):
        sdk = Path(os.environ["WASI_SDK_PATH"]).resolve()
    else:
        if platform.system() != "Linux" or platform.machine() != "x86_64":
            raise SystemExit("Set WASI_SDK_PATH to an installed WASI SDK 34 on this platform.")
        sdk = CACHE / "wasi-sdk-34.0-x86_64-linux"
        if not sdk.exists():
            unpack("https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-34/"
                   "wasi-sdk-34.0-x86_64-linux.tar.gz", SDK_SHA)
    source = CACHE / "lua-5.2.4" / "src"
    excluded = {"lua.c", "luac.c", "linit.c", "liolib.c", "loslib.c", "loadlib.c"}
    sources = sorted(str(p) for p in source.glob("*.c") if p.name not in excluded)
    output = HERE.parent / "src/main/resources/wasm/boot-lua.wasm"
    output.parent.mkdir(parents=True, exist_ok=True)
    flags = [
        str(sdk / "bin/clang"), "-O2", "-I" + str(source),
        "-mexec-model=reactor", "-mllvm", "-wasm-enable-sjlj",
        "-mllvm", "-wasm-use-legacy-eh=false", "-lsetjmp",
        "-Wl,-z,stack-size=1048576", "-Wl,--initial-memory=8388608",
        "-Wl,--max-memory=33554432", "-Wl,--strip-debug"
    ]
    bootstrap = (HERE.parent / "src/main/resources/wasm/bootstrap.lua").read_bytes()
    (CACHE / "bootstrap.h").write_text("static const unsigned char bootstrap[] = {" +
        ",".join(str(b) for b in bootstrap) + "};\n")
    boot_output = output
    subprocess.run([*flags, "-I" + str(CACHE), str(HERE / "boot.c"), *sources,
                    "-o", str(boot_output)], check=True)
    print(f"Built {boot_output} ({boot_output.stat().st_size:,} bytes)")
    inputs = [HERE / "build.py", HERE / "boot.c",
              output.with_name("bootstrap.lua"), boot_output]
    (HERE / "build.sha256").write_text("".join(
        hashlib.sha256(path.read_bytes()).hexdigest() + "  " +
        str(path.relative_to(HERE.parent)) + "\n" for path in inputs))


if __name__ == "__main__":
    main()
