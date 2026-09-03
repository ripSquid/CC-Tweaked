-- Guest bootstrap smoke source.
--
-- The host runner supplies:
--   globals = { _HOST = "fixture", _CC_TEST_FN = { __bleh_function = "fn-1" } }
--   methods = { cc_test = { "return_values" } }
-- `cc_test.return_values` returns a table containing another tagged function
-- reference. This exercises both initial-global and returned-value walking.
assert(_HOST == "fixture")
assert(type(_CC_TEST_FN) == "function")
assert(_CC_TEST_FN("initial") == "initial-result")

local values = cc_test.return_values()
assert(type(values.callback) == "function")
assert(values.callback("returned") == "returned-result")

-- These are the compatibility points needed by bios.lua and the first ROM
-- modules, without requiring the fixture to mount an entire ROM tree.
assert(type(utf8.char) == "function")
local smile = utf8.char(0x1F642)
assert(utf8.codepoint(smile) == 0x1F642)
assert(utf8.len("a" .. smile) == 2)
assert(utf8.offset("abc", 1) == 1 and utf8.offset("abc", 3) == 3)
assert(utf8.offset("a" .. smile, -1) == 2)
assert(utf8.offset("abc", 4) == 4 and utf8.offset("abc", 5) == nil)
assert(utf8.offset(smile, 0, 3) == 1)
assert(not pcall(utf8.len, "abc", 0))
assert(not pcall(utf8.codepoint, "abc", 0))
assert(string.gsub(".", "%.", "%%%.") == "%.")
assert(string.match(smile, "^" .. utf8.charpattern .. "$") == smile)
assert(table.move({ "a", "b" }, 1, 2, 2)[2] == "a")
assert(string.rep("x", 2, ":") == "x:x")
assert(math.log(100, 10) == 2)
local loaded = assert(loadstring("return answer"))
setfenv(loaded, { answer = 42 })
assert(loaded() == 42)

return true
