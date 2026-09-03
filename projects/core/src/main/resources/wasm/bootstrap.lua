-- CraftOS guest bootstrap for the Lua Wasm image.
--
-- The source chunk is normally the stock CC:T bios.lua. The only native
-- entrypoints used here are __cc_call and __cc_resume. They return a
-- transport tuple: true, ... for a completed call, false, message for an
-- error, or "__bleh_wait", token, event_filter for a pending MethodResult.
-- Keeping the wait in Lua is important: every coroutine used by parallel.lua
-- then yields its own event filter, just as it does under Cobalt.
local source, chunk_name, methods, initial_globals, initial_modules = ...
local raw_load = load
local raw_gsub = string.gsub
local raw_debug = debug
local raw_type = type
local raw_pairs = pairs
local raw_setmetatable = setmetatable
local pack, unpack = table.pack, table.unpack
local native_call, native_resume = __cc_call, __cc_resume

if raw_type(native_call) ~= "function" or raw_type(native_resume) ~= "function" then
    error("missing CC host transport", 0)
end

-- Host references are normally decoded by the Wasm bridge as native Lua
-- closures. The tagged table form is the stable fallback for objects produced
-- by adapters outside the C bridge:
--
--   { __bleh_host_ref = id, __bleh_host_kind = "function" }
--   { __bleh_host_ref = id, __bleh_host_kind = "object",
--     __bleh_host_methods = { "read", "close" } }
--
-- A function reference calls __handle.<id>(...). An object method calls
-- __handle.<id>(method, ...). These names are private and accepted by the
-- host adapter only for IDs it issued.
local materialize
local function host_call(name, ...)
    local result = pack(native_call(name, ...))
    while result[1] == "__bleh_wait" do
        local token, filter = result[2], result[3]
        local event = pack(coroutine.yield(filter))
        result = pack(native_resume(token, unpack(event, 1, event.n)))
    end
    if result[1] ~= true then
        error(result[2] or "CC host call failed", 2)
    end
    for i = 2, result.n do result[i] = materialize(result[i]) end
    return unpack(result, 2, result.n)
end

local function is_ref(value)
    if raw_type(value) ~= "table" then return end
    -- __bleh_host_ref is canonical. The aliases make this layer tolerant of
    -- adapters which expose the transport's shorter cc_* spelling.
    local id = rawget(value, "__bleh_host_ref") or rawget(value, "__cc_host_ref")
    local short_function_id = rawget(value, "__bleh_function")
    if id == nil and short_function_id ~= nil then id = short_function_id end
    if id == nil then return end
    local kind = rawget(value, "__bleh_host_kind") or rawget(value, "__cc_host_kind") or
        (short_function_id ~= nil and "function") or "function"
    if kind ~= "function" and kind ~= "object" then return end
    return id, kind
end

materialize = function(value, seen)
    if raw_type(value) ~= "table" then return value end
    local id, kind = is_ref(value)
    if id ~= nil then
        if kind == "function" then
            return function(...)
                return host_call("__handle." .. tostring(id), ...)
            end
        end

        -- Handle references are tables so they work with both dot and colon
        -- calls. Known methods are installed eagerly; unknown names resolve
        -- lazily for adapters which omit a method list.
        local object = {}
        local methods_list = rawget(value, "__bleh_host_methods") or rawget(value, "__cc_host_methods")
        local function method(name)
            local fn = function(...)
                local args = pack(...)
                if args[1] == object then
                    table.remove(args, 1)
                    args.n = args.n - 1
                end
                return host_call("__handle." .. tostring(id), name,
                    unpack(args, 1, args.n))
            end
            rawset(object, name, fn)
            return fn
        end
        if raw_type(methods_list) == "table" then
            for _, name in ipairs(methods_list) do
                if raw_type(name) == "string" then method(name) end
            end
        end
        raw_setmetatable(object, {
            __name = "cc.host_object",
            __index = function(_, name)
                if raw_type(name) ~= "string" then return end
                return method(name)
            end,
        })
        return object
    end

    -- Mutating transport tables preserves aliases. `seen` handles cycles,
    -- though normal CC API results are acyclic.
    seen = seen or {}
    if seen[value] then return seen[value] end
    seen[value] = value
    for key, child in raw_pairs(value) do
        rawset(value, key, materialize(child, seen))
    end
    return value
end

-- Initial globals are supplied by the computer adapter. Values may contain
-- native host closures or tagged references, so walk the complete graph.
if raw_type(initial_globals) == "table" then
    local seen = {}
    for name, value in raw_pairs(initial_globals) do
        rawset(_G, name, materialize(value, seen))
    end
end

-- Static APIs remain convenient when the adapter supplies a method allowlist
-- rather than a prebuilt global table. Do not replace native functions.
for module, names in raw_pairs(methods or {}) do
    local target = rawget(_G, module)
    if target == nil then
        target = {}
        rawset(_G, module, target)
    end
    for _, name in ipairs(names) do
        if raw_type(target) == "table" and rawget(target, name) == nil then
            local full_name = module .. "." .. name
            target[name] = function(...) return host_call(full_name, ...) end
        end
    end
end

-- CraftOS's BIOS defines these too, but installing them here makes the guest
-- contract explicit and gives pre-BIOS chunks the same event semantics.
if os then
    os.pullEventRaw = os.pullEventRaw or function(filter)
        return coroutine.yield(filter)
    end
    os.pullEvent = function(filter)
        local event = pack(os.pullEventRaw(filter))
        if event[1] == "terminate" then error("Terminated", 0) end
        return unpack(event, 1, event.n)
    end
end

-- Lua 5.1/5.3 compatibility selected by CC:T. Keep raw functions in locals
-- because these names are deliberately changed below.
_G.unpack = unpack
-- Cobalt retains Lua 5.1's permissive replacement escapes (the ROM's
-- require.searchpath uses "%%%."). PUC 5.2 rejects those escapes.
string.gsub = function(value, pattern, replacement, limit)
    if raw_type(replacement) == "string" then
        replacement = raw_gsub(replacement, "%%(.)", function(c)
            if c == "%" or c:match("%d") then return "%" .. c end
            return c
        end)
    end
    return raw_gsub(value, pattern, replacement, limit)
end
loadstring = function(text, name) return raw_load(text, name, "t", _G) end
load = function(input, name, mode, environment)
    -- CC accepts the old load(chunk, environment) form too.
    if raw_type(name) == "table" and mode == nil and environment == nil then
        environment, name = name, nil
    end
    if mode and not mode:find("t", 1, true) then
        return nil, "Binary chunks are disabled"
    end
    return raw_load(input, name, "t", environment or _G)
end

local function environment_upvalue(fn)
    local index = 1
    while true do
        local name = raw_debug.getupvalue(fn, index)
        if not name then return end
        if name == "_ENV" then return index end
        index = index + 1
    end
end
function getfenv(fn)
    if fn == nil then fn = 1 end
    if raw_type(fn) == "number" then
        if fn == 0 then return _G end
        local info = raw_debug.getinfo(fn + 1, "f")
        if not info then error("invalid level", 2) end
        fn = info.func
    end
    if raw_type(fn) ~= "function" then error("function or level expected", 2) end
    local index = environment_upvalue(fn)
    if not index then return _G end
    local _, environment = raw_debug.getupvalue(fn, index)
    return environment
end
function setfenv(fn, environment)
    if raw_type(environment) ~= "table" then error("table expected", 2) end
    if raw_type(fn) == "number" then
        if fn == 0 then error("cannot change thread environment", 2) end
        local info = raw_debug.getinfo(fn + 1, "f")
        if not info then error("invalid level", 2) end
        fn = info.func
    end
    if raw_type(fn) ~= "function" then error("function or level expected", 2) end
    local index = environment_upvalue(fn)
    if index then raw_debug.upvaluejoin(fn, index, function() return environment end, 1) end
    return fn
end

table.getn = function(t) return #t end
table.maxn = function(t)
    local maximum = 0
    for key in pairs(t) do
        if raw_type(key) == "number" and key > maximum then maximum = key end
    end
    return maximum
end
table.foreach = function(t, fn)
    for key, value in pairs(t) do
        local result = fn(key, value)
        if result ~= nil then return result end
    end
end
table.foreachi = function(t, fn)
    for i = 1, #t do
        local result = fn(i, t[i])
        if result ~= nil then return result end
    end
end
table.create = function() return {} end
table.move = function(from, first, last, target, to)
    to = to or from
    if to == from and target > first then
        for i = last, first, -1 do to[target + i - first] = from[i] end
    else
        for i = first, last do to[target + i - first] = from[i] end
    end
    return to
end
if not coroutine.isyieldable then
    coroutine.isyieldable = function()
        local _, main = coroutine.running()
        return not main
    end
end
string.gfind = string.gmatch
math.mod = math.fmod

-- Lua 5.3's utf8 library is used by CC:T's ROM modules. This implementation
-- validates continuation bytes and scalar range, matching the useful Cobalt
-- surface without adding a native library to the Wasm image.
if not utf8 then
    local utf8lib = { charpattern = "[\0-\x7F\xC2-\xF4][\x80-\xBF]*" }
    local function decode(s, position)
        local c = s:byte(position)
        if not c then return nil, position end
        if c < 0x80 then return c, position + 1 end
        local width, value, minimum
        if c >= 0xC2 and c <= 0xDF then width, value, minimum = 2, c - 0xC0, 0x80
        elseif c >= 0xE0 and c <= 0xEF then width, value, minimum = 3, c - 0xE0, 0x800
        elseif c >= 0xF0 and c <= 0xF4 then width, value, minimum = 4, c - 0xF0, 0x10000
        else error("invalid UTF-8 code", 3) end
        for i = 2, width do
            local next_byte = s:byte(position + i - 1)
            if not next_byte or next_byte < 0x80 or next_byte > 0xBF then error("invalid UTF-8 code", 3) end
            value = value * 0x40 + next_byte - 0x80
        end
        if value < minimum or value > 0x10FFFF or value >= 0xD800 and value <= 0xDFFF then error("invalid UTF-8 code", 3) end
        return value, position + width
    end
    function utf8lib.char(...)
        local out = {}
        for i = 1, select("#", ...) do
            local code = select(i, ...)
            if raw_type(code) ~= "number" or code < 0 or code > 0x10FFFF or code % 1 ~= 0 or code >= 0xD800 and code <= 0xDFFF then error("value out of range", 2) end
            if code < 0x80 then out[i] = string.char(code)
            elseif code < 0x800 then out[i] = string.char(0xC0 + math.floor(code / 0x40), 0x80 + code % 0x40)
            elseif code < 0x10000 then out[i] = string.char(0xE0 + math.floor(code / 0x1000), 0x80 + math.floor(code / 0x40) % 0x40, 0x80 + code % 0x40)
            else out[i] = string.char(0xF0 + math.floor(code / 0x40000), 0x80 + math.floor(code / 0x1000) % 0x40, 0x80 + math.floor(code / 0x40) % 0x40, 0x80 + code % 0x40) end
        end
        return table.concat(out)
    end
    function utf8lib.codes(s, ignore_invalid)
        local position = 1
        local iterator = function()
            if position > #s then return end
            local start = position
            local ok, code, next_position = pcall(decode, s, position)
            if not ok then
                if not ignore_invalid then error(code, 2) end
                code, next_position = s:byte(position), position + 1
            end
            position = next_position
            return start, code
        end
        return iterator, s, 0
    end
    function utf8lib.codepoint(s, first, last, ignore_invalid)
        first, last = first or 1, last or first or 1
        if first < 0 then first = #s + first + 1 end
        if last < 0 then last = #s + last + 1 end
        if first < 1 or last > #s then error("position out of bounds", 2) end
        local out, position = {}, first
        while position <= last do
            local ok, code, next_position = pcall(decode, s, position)
            if not ok then
                if not ignore_invalid then error(code, 2) end
                code, next_position = s:byte(position), position + 1
            end
            out[#out + 1], position = code, next_position
        end
        return unpack(out)
    end
    function utf8lib.len(s, first, last, ignore_invalid)
        first, last = first or 1, last or #s
        if first < 0 then first = #s + first + 1 end
        if last < 0 then last = #s + last + 1 end
        if first < 1 or first > #s + 1 or last > #s then error("position out of bounds", 2) end
        local count, position = 0, first
        while position <= last do
            local ok, _, next_position = pcall(decode, s, position)
            if not ok then
                if not ignore_invalid then return nil, position end
                next_position = position + 1
            end
            count, position = count + 1, next_position
        end
        return count
    end
    function utf8lib.offset(s, n, position)
        if raw_type(n) ~= "number" or n % 1 ~= 0 then error("number has no integer representation", 2) end
        position = position or (n >= 0 and 1 or #s + 1)
        if position < 1 or position > #s + 1 then return nil end
        local first_byte = s:byte(position)
        if n == 0 then
            if position > #s then return #s + 1 end
            while position > 1 and s:byte(position) >= 0x80 and s:byte(position) <= 0xBF do position = position - 1 end
            return position
        end
        if first_byte and first_byte >= 0x80 and first_byte <= 0xBF then error("initial position is a continuation byte", 2) end
        if n > 0 then
            -- The current byte is the first codepoint for n == 1.
            for _ = 2, n do
                if position > #s then return nil end
                local _, next_position = decode(s, position)
                position = next_position
            end
            return position
        end
        for _ = 1, -n do
            position = position - 1
            while position > 1 and s:byte(position) >= 0x80 and s:byte(position) <= 0xBF do position = position - 1 end
            if position < 1 then return nil end
        end
        return position
    end
    utf8 = utf8lib
end

-- Keep only debug operations needed by ROM code and environment shims.
local guest_registry = { _LOADED = materialize(initial_modules or {}) }
debug = {
    getregistry = function() return guest_registry end,
    getlocal = raw_debug.getlocal,
    getinfo = raw_debug.getinfo,
    traceback = raw_debug.traceback,
    getmetatable = raw_debug.getmetatable,
    getupvalue = raw_debug.getupvalue,
    upvaluejoin = raw_debug.upvaluejoin,
}

-- WASI has no guest filesystem, native module loader, bytecode loader, or
-- process control. BIOS installs fs-backed dofile/loadfile before shell runs.
dofile, loadfile, collectgarbage, string.dump = nil, nil, nil, nil

local program, message = raw_load(source, chunk_name, "t", _G)
if not program then error(message, 0) end
return program()
