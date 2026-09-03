/* Default boot disk: Lua inside a generic computer ABI. */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#include "bootstrap.h"

#define EXPORT(name) __attribute__((export_name(name)))
#define IMPORT(name) __attribute__((import_module("cc"), import_name(name)))
#define LIMIT 1048576
#define VALUES 16384

IMPORT("boot_data") extern int host_boot_data(void);
IMPORT("call") extern int host_call(const void *, int);
IMPORT("resume") extern int host_resume(const void *, int);
IMPORT("read_response") extern int host_read_response(void *, int);

static lua_State *root, *thread;
static unsigned char request_buffer[LIMIT], response_buffer[LIMIT], result_buffer[LIMIT];
static int result_length;
typedef struct {
    unsigned char *data;
    int length, position, remaining;
    const void *active[33];
} Buffer;

static void check(Buffer *b, lua_State *L, int count) {
    if (count < 0 || count > b->length - b->position) luaL_error(L, "Computer ABI message exceeds bounds");
}
static void write_bytes(Buffer *b, lua_State *L, const void *data, int count) {
    check(b, L, count); memcpy(b->data + b->position, data, count); b->position += count;
}
static void write32(Buffer *b, lua_State *L, uint32_t value) { write_bytes(b, L, &value, 4); }
static uint32_t read32(Buffer *b, lua_State *L) {
    uint32_t value; check(b, L, 4); memcpy(&value, b->data + b->position, 4); b->position += 4; return value;
}
static int read8(Buffer *b, lua_State *L) { check(b, L, 1); return b->data[b->position++]; }
static void write8(Buffer *b, lua_State *L, unsigned char value) { write_bytes(b, L, &value, 1); }

static void encode(Buffer *b, lua_State *L, int index, int depth) {
    if (depth > 32 || --b->remaining < 0) luaL_error(L, "Computer ABI value complexity limit exceeded");
    if (!lua_checkstack(L, 4)) luaL_error(L, "Lua stack limit exceeded");
    index = lua_absindex(L, index);
    switch (lua_type(L, index)) {
        case LUA_TNIL: write8(b, L, 0); break;
        case LUA_TBOOLEAN: write8(b, L, lua_toboolean(L, index) ? 2 : 1); break;
        case LUA_TNUMBER: {
            double number = lua_tonumber(L, index);
            write8(b, L, 3); write_bytes(b, L, &number, 8); break;
        }
        case LUA_TSTRING: {
            size_t length; const char *string = lua_tolstring(L, index, &length);
            if (length > LIMIT) luaL_error(L, "Computer ABI string too large");
            write8(b, L, 4); write32(b, L, length); write_bytes(b, L, string, length); break;
        }
        case LUA_TTABLE: {
            const void *identity = lua_topointer(L, index);
            for (int i = 0; i < depth; i++) if (b->active[i] == identity) luaL_error(L, "Cyclic guest table");
            b->active[depth] = identity;
            write8(b, L, 5);
            int count_offset = b->position, count = 0;
            write32(b, L, 0);
            lua_pushnil(L);
            while (lua_next(L, index)) {
                encode(b, L, -2, depth + 1); encode(b, L, -1, depth + 1);
                lua_pop(L, 1); count++;
            }
            memcpy(b->data + count_offset, &count, 4);
            b->active[depth] = NULL;
            break;
        }
        default: luaL_error(L, "Cannot pass %s through computer ABI", luaL_typename(L, index));
    }
}

static void decode(Buffer *b, lua_State *L, int depth) {
    if (depth > 32 || --b->remaining < 0) luaL_error(L, "Computer ABI value complexity limit exceeded");
    if (!lua_checkstack(L, 4)) luaL_error(L, "Lua stack limit exceeded");
    switch (read8(b, L)) {
        case 0: lua_pushnil(L); break;
        case 1: lua_pushboolean(L, 0); break;
        case 2: lua_pushboolean(L, 1); break;
        case 3: {
            double number; check(b, L, 8); memcpy(&number, b->data + b->position, 8); b->position += 8;
            lua_pushnumber(L, number); break;
        }
        case 4: {
            uint32_t length = read32(b, L); check(b, L, length);
            lua_pushlstring(L, (const char *)b->data + b->position, length); b->position += length; break;
        }
        case 5: {
            uint32_t count = read32(b, L);
            if (count > VALUES) luaL_error(L, "Computer ABI table too large");
            lua_newtable(L);
            for (uint32_t i = 0; i < count; i++) { decode(b, L, depth + 1); decode(b, L, depth + 1); lua_rawset(L, -3); }
            break;
        }
        case 6: {
            uint32_t length = read32(b, L); check(b, L, length);
            lua_newtable(L);
            lua_pushlstring(L, (const char *)b->data + b->position, length); b->position += length;
            lua_setfield(L, -2, "__bleh_function"); break;
        }
        default: luaL_error(L, "Unknown computer ABI value tag");
    }
}

static int decode_values(lua_State *L, unsigned char *data, int length) {
    Buffer b = {.data = data, .length = length, .remaining = VALUES};
    uint32_t count = read32(&b, L);
    if (count > VALUES || !lua_checkstack(L, count)) luaL_error(L, "Too many computer ABI arguments");
    for (uint32_t i = 0; i < count; i++) decode(&b, L, 0);
    if (b.position != b.length) luaL_error(L, "Trailing computer ABI bytes");
    return count;
}

static int exchange(lua_State *L, int continuation) {
    Buffer b = {.data = request_buffer, .length = LIMIT, .remaining = VALUES};
    int arguments = lua_gettop(L);
    write32(&b, L, arguments);
    for (int i = 1; i <= arguments; i++) encode(&b, L, i, 0);
    int length = continuation ? host_resume(request_buffer, b.position) : host_call(request_buffer, b.position);
    if (length < 4 || length > LIMIT || host_read_response(response_buffer, LIMIT) != length) {
        return luaL_error(L, "Invalid computer ABI response");
    }
    lua_settop(L, 0);
    return decode_values(L, response_buffer, length);
}
static int call_host(lua_State *L) { return exchange(L, 0); }
static int resume_host(lua_State *L) { return exchange(L, 1); }

static int finish(int status) {
    Buffer b = {.data = result_buffer, .length = LIMIT, .remaining = VALUES};
    if (status != LUA_OK && status != LUA_YIELD) {
        /* Keep non-string Lua error objects inside the guest for now. */
        const char *message = lua_tostring(thread, -1);
        if (!message) message = "Lua boot disk failed (non-string error)";
        size_t length = strlen(message);
        write32(&b, thread, 1); write8(&b, thread, 4); write32(&b, thread, length);
        write_bytes(&b, thread, message, length);
    } else {
        int count = lua_gettop(thread);
        write32(&b, thread, count);
        for (int i = 1; i <= count; i++) encode(&b, thread, i, 0);
    }
    result_length = b.position;
    return status == LUA_OK ? 0 : status == LUA_YIELD ? 1 : 2;
}

EXPORT("cc_abi_version") int cc_abi_version(void) { return 1; }
EXPORT("cc_alloc") void *cc_alloc(size_t size) { return size <= LIMIT ? malloc(size) : NULL; }
EXPORT("cc_result_pointer") void *cc_result_pointer(void) { return result_buffer; }
EXPORT("cc_result_length") int cc_result_length(void) { return result_length; }

EXPORT("cc_boot") int cc_boot(void) {
    root = luaL_newstate();
    if (!root) __builtin_trap();
    const luaL_Reg libraries[] = {
        {"_G", luaopen_base}, {LUA_COLIBNAME, luaopen_coroutine},
        {LUA_TABLIBNAME, luaopen_table}, {LUA_STRLIBNAME, luaopen_string},
        {LUA_BITLIBNAME, luaopen_bit32}, {LUA_MATHLIBNAME, luaopen_math},
        {LUA_DBLIBNAME, luaopen_debug}, {NULL, NULL}
    };
    for (const luaL_Reg *lib = libraries; lib->func; lib++) {
        luaL_requiref(root, lib->name, lib->func, 1); lua_pop(root, 1);
    }
    lua_pushcfunction(root, call_host); lua_setglobal(root, "__cc_call");
    lua_pushcfunction(root, resume_host); lua_setglobal(root, "__cc_resume");
    thread = lua_newthread(root);
    int status = luaL_loadbufferx(thread, (const char *)bootstrap, sizeof(bootstrap), "@boot/init.lua", "t");
    if (status != LUA_OK) return finish(status);
    int length = host_boot_data();
    if (length < 4 || length > LIMIT || host_read_response(response_buffer, LIMIT) != length) __builtin_trap();
    int arguments = decode_values(thread, response_buffer, length);
    return finish(lua_resume(thread, NULL, arguments));
}

EXPORT("cc_event") int cc_event(unsigned char *data, int length) {
    lua_settop(thread, 0);
    int arguments = decode_values(thread, data, length);
    free(data);
    return finish(lua_resume(thread, NULL, arguments));
}
