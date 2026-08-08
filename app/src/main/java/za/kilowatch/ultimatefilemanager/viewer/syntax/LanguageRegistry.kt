package za.kilowatch.ultimatefilemanager.viewer.syntax

/**
 * Central registry mapping file extensions (and special filenames) to
 * [LanguageDef] instances.  All 30+ languages defined in the spec are
 * listed below.
 *
 * Usage:
 * ```kotlin
 * val lang = LanguageRegistry.detect("build.gradle.kts")
 * if (lang != null) { /* highlight with lang */ }
 * ```
 */
object LanguageRegistry {

    // ── Utility / config languages ─────────────────────────────────────

    val JSON = LanguageDef(
        name = "JSON",
        keywords = emptySet(),
        types = emptySet(),
        builtins = emptySet(),
        booleanLiterals = setOf("true", "false", "null"),
        constants = emptySet(),
        lineComments = emptyList(),
        blockComments = emptyList(),
        stringDelimiters = listOf("\""),
        multiStringDelimiters = emptyList(),
        numericSuffixes = emptyList()
    )

    val YAML = LanguageDef(
        name = "YAML",
        keywords = emptySet(),
        types = emptySet(),
        builtins = emptySet(),
        booleanLiterals = setOf("true", "false", "yes", "no", "on", "off", "null", "~"),
        lineComments = listOf("#"),
        stringDelimiters = listOf("\"", "'"),
        isYaml = true
    )

    val TOML = LanguageDef(
        name = "TOML",
        keywords = emptySet(),
        booleanLiterals = setOf("true", "false"),
        lineComments = listOf("#"),
        stringDelimiters = listOf("\"", "'"),
        hasAnnotations = false
    )

    val Properties = LanguageDef(
        name = "Properties",
        keywords = emptySet(),
        booleanLiterals = setOf("true", "false"),
        lineComments = listOf("#", ";", "!"),
        stringDelimiters = listOf("\""),
        isProperties = true
    )

    val Env = LanguageDef(
        name = "Dotenv",
        keywords = emptySet(),
        booleanLiterals = setOf("true", "false", "null"),
        lineComments = listOf("#"),
        stringDelimiters = listOf("\"", "'"),
        isDotenv = true
    )

    val M3U = LanguageDef(
        name = "M3U Playlist",
        keywords = setOf(
            "#EXTM3U", "#EXTINF", "#EXT-X-STREAM-INF", "#EXT-X-TARGETDURATION",
            "#EXT-X-MEDIA-SEQUENCE", "#EXT-X-KEY", "#EXT-X-PROGRAM-DATE-TIME",
            "#EXT-X-ALLOW-CACHE", "#EXT-X-ENDLIST", "#EXT-X-VERSION",
            "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-MEDIA"
        ),
        lineComments = listOf("#"),
        stringDelimiters = listOf("\"")
    )

    // ── Markup languages ───────────────────────────────────────────────

    val XML = LanguageDef(
        name = "XML",
        keywords = emptySet(),
        booleanLiterals = setOf("true", "false"),
        lineComments = emptyList(),
        blockComments = listOf("<!--" to "-->"),
        stringDelimiters = listOf("\"", "'"),
        isMarkup = true
    )

    val HTML = LanguageDef(
        name = "HTML",
        keywords = emptySet(),
        booleanLiterals = setOf("true", "false"),
        lineComments = emptyList(),
        blockComments = listOf("<!--" to "-->"),
        stringDelimiters = listOf("\"", "'"),
        isMarkup = true
    )

    // ── Style sheets ───────────────────────────────────────────────────

    val CSS = LanguageDef(
        name = "CSS",
        keywords = setOf(
            "import", "media", "keyframes", "font-face", "charset",
            "namespace", "supports", "layer", "container", "scope",
            "include", "mixin", "function", "return", "if", "else",
            "for", "each", "while", "from", "to", "at-root",
            "extend", "warn", "error", "debug", "use", "forward"
        ),
        types = setOf(
            "auto", "inherit", "initial", "unset", "revert",
            "flex", "grid", "block", "inline", "inline-block",
            "none", "hidden", "visible", "scroll", "absolute",
            "relative", "fixed", "sticky", "static",
            "center", "start", "end", "baseline", "stretch",
            "cover", "contain", "repeat", "no-repeat",
            "solid", "dashed", "dotted", "double", "none",
            "serif", "sans-serif", "monospace", "cursive", "fantasy"
        ),
        builtins = setOf(
            "rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklch", "oklab",
            "var", "calc", "min", "max", "clamp", "minmax",
            "url", "attr", "counter", "counters",
            "linear-gradient", "radial-gradient", "conic-gradient",
            "repeat", "translate", "rotate", "scale", "skew",
            "translateX", "translateY", "translateZ",
            "rotateX", "rotateY", "rotateZ",
            "scaleX", "scaleY", "scaleZ",
            "matrix", "matrix3d", "perspective",
            "blur", "brightness", "contrast", "drop-shadow",
            "grayscale", "hue-rotate", "invert", "opacity", "saturate", "sepia",
            "element", "image-set", "cross-fade",
            "env", "constant"
        ),
        booleanLiterals = setOf("true", "false"),
        constants = setOf("transparent", "currentColor"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'")
    )

    // ── Scripting languages ────────────────────────────────────────────

    val JavaScript = LanguageDef(
        name = "JavaScript",
        keywords = setOf(
            "break", "case", "catch", "continue", "debugger", "default",
            "delete", "do", "else", "finally", "for", "function",
            "if", "in", "instanceof", "new", "of", "return",
            "switch", "this", "throw", "try", "typeof", "var",
            "void", "while", "with", "class", "const", "export",
            "extends", "import", "super", "yield", "async", "await",
            "let", "static", "from", "as", "get", "set",
            "implements", "interface", "package", "private",
            "protected", "public", "abstract", "enum", "readonly",
            "declare", "type", "satisfies"
        ),
        types = setOf(
            "number", "string", "boolean", "undefined", "symbol",
            "bigint", "object", "function", "any", "void",
            "never", "unknown", "null", "Promise", "Array",
            "Record", "Map", "Set", "WeakMap", "WeakSet",
            "Date", "RegExp", "Error", "Buffer", "ErrorEvent",
            "Event", "EventTarget", "Node", "Element", "HTMLElement",
            "Document", "Window", "Console", "JSON", "Math",
            "Reflect", "Proxy", "Intl", "DataView", "ArrayBuffer",
            "Uint8Array", "Int8Array", "Uint16Array", "Int16Array",
            "Uint32Array", "Int32Array", "Float32Array", "Float64Array"
        ),
        builtins = setOf(
            "console", "log", "warn", "error", "info", "debug",
            "setTimeout", "setInterval", "clearTimeout", "clearInterval",
            "parseInt", "parseFloat", "isNaN", "isFinite",
            "decodeURI", "encodeURI", "decodeURIComponent", "encodeURIComponent",
            "eval", "String", "Number", "Boolean", "Symbol",
            "Array", "Object", "Map", "Set", "WeakMap", "WeakSet",
            "Promise", "fetch", "require", "module", "exports",
            "process", "global", "globalThis", "Buffer",
            "document", "window", "navigator", "location",
            "Math", "JSON", "Date", "RegExp", "Error",
            "setImmediate", "queueMicrotask",
            "atob", "btoa", "structuredClone"
        ),
        booleanLiterals = setOf("true", "false", "null", "undefined"),
        constants = setOf("NaN", "Infinity"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'", "`"),
        multiStringDelimiters = emptyList(),
        numericSuffixes = listOf("n")
    )

    val TypeScript = JavaScript.copy(
        name = "TypeScript",
        types = JavaScript.types + setOf(
            "string", "number", "boolean", "any", "void",
            "never", "unknown", "null", "undefined", "object",
            "symbol", "bigint", "enum", "tuple", "type",
            "interface", "Record", "Partial", "Required", "Readonly",
            "Pick", "Omit", "Exclude", "Extract", "NonNullable",
            "ReturnType", "Parameters", "InstanceType",
            "ConstructorParameters", "ThisType", "OmitThisParameter",
            "PromiseLike", "Awaited", "Uppercase", "Lowercase",
            "Capitalize", "Uncapitalize"
        ),
        builtins = JavaScript.builtins
    )

    val Python = LanguageDef(
        name = "Python",
        keywords = setOf(
            "False", "None", "True", "and", "as", "assert",
            "async", "await", "break", "class", "continue", "def",
            "del", "elif", "else", "except", "finally", "for",
            "from", "global", "if", "import", "in", "is",
            "lambda", "nonlocal", "not", "or", "pass", "raise",
            "return", "try", "while", "with", "yield", "match",
            "case", "type", "self", "cls"
        ),
        types = setOf(
            "int", "float", "str", "bool", "bytes", "bytearray",
            "list", "tuple", "set", "frozenset", "dict", "complex",
            "range", "slice", "type", "object", "NoneType",
            "Exception", "BaseException", "ValueError", "TypeError",
            "KeyError", "IndexError", "RuntimeError", "IOError",
            "OSError", "StopIteration", "GeneratorExit",
            "Any", "Optional", "Union", "Callable", "Iterable",
            "Iterator", "Generator", "Sequence", "Mapping",
            "Self", "TypeVar", "Generic", "Protocol", "TypedDict",
            "Literal", "Final", "ClassVar", "List", "Dict",
            "Set", "Tuple", "FrozenSet", "Type"
        ),
        builtins = setOf(
            "print", "len", "range", "type", "int", "str", "float",
            "bool", "list", "dict", "set", "tuple", "object",
            "super", "isinstance", "issubclass", "hasattr", "getattr",
            "setattr", "delattr", "dir", "vars", "locals", "globals",
            "open", "close", "read", "write", "input", "eval",
            "exec", "compile", "repr", "str", "format", "bytes",
            "bytearray", "memoryview", "map", "filter", "reduce",
            "zip", "enumerate", "sorted", "reversed", "iter", "next",
            "all", "any", "sum", "min", "max", "abs", "round",
            "pow", "divmod", "hex", "oct", "bin", "ord", "chr",
            "hash", "id", "help", "exit", "quit",
            "staticmethod", "classmethod", "property",
            "__init__", "__str__", "__repr__", "__len__",
            "__getitem__", "__setitem__", "__delitem__",
            "__iter__", "__next__", "__enter__", "__exit__",
            "__call__", "__new__", "__del__", "__eq__",
            "__ne__", "__lt__", "__le__", "__gt__", "__ge__",
            "__hash__", "__contains__", "__add__", "__sub__",
            "__mul__", "__truediv__", "__floordiv__", "__mod__",
            "__pow__", "__and__", "__or__", "__xor__", "__invert__"
        ),
        booleanLiterals = setOf("True", "False", "None"),
        constants = setOf("Ellipsis", "NotImplemented"),
        lineComments = listOf("#"),
        blockComments = listOf("\"\"\"" to "\"\"\"", "'''" to "'''"),
        stringDelimiters = listOf("\"", "'"),
        multiStringDelimiters = listOf("\"\"\"", "'''"),
        numericSuffixes = listOf("j")
    )

    // ── JVM languages ──────────────────────────────────────────────────

    val Kotlin = LanguageDef(
        name = "Kotlin",
        keywords = setOf(
            "as", "as?", "break", "class", "continue", "do",
            "else", "false", "for", "fun", "if", "in",
            "!in", "interface", "is", "!is", "null", "object",
            "package", "return", "super", "this", "throw", "true",
            "try", "typealias", "typeof", "val", "var", "when",
            "while", "abstract", "actual", "annotation", "by",
            "catch", "companion", "const", "crossinline", "data",
            "enum", "expect", "external", "final", "finally",
            "import", "infix", "inline", "inner", "internal",
            "lateinit", "noinline", "open", "operator", "out",
            "override", "private", "protected", "public", "reified",
            "sealed", "suspend", "tailrec", "vararg",
            "init", "constructor", "field", "get", "set",
            "param", "delegate", "where", "value"
        ),
        types = setOf(
            "Int", "Long", "Float", "Double", "Boolean", "String",
            "Char", "Byte", "Short", "Unit", "Nothing", "Any",
            "Any?", "Nothing?", "Array", "List", "MutableList",
            "Set", "MutableSet", "Map", "MutableMap",
            "Collection", "Iterable", "Sequence",
            "Pair", "Triple", "IntRange", "LongRange",
            "CharRange", "ClosedRange", "Comparable",
            "CharSequence", "StringBuilder",
            "Throwable", "Exception", "RuntimeException",
            "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "IndexOutOfBoundsException",
            "Error", "AssertionError", "OutOfMemoryError",
            "Function", "Function0", "Function1", "Function2",
            "CoroutineScope", "CoroutineContext", "Job", "Deferred",
            "Flow", "StateFlow", "MutableStateFlow",
            "SharedFlow", "MutableSharedFlow",
            "LiveData", "MutableLiveData",
            "ViewModel", "AndroidViewModel",
            "Context", "Activity", "Fragment", "View",
            "Intent", "Bundle", "ContentValues",
            "Cursor", "Uri", "File", "InputStream", "OutputStream",
            "ArrayList", "HashMap", "HashSet", "LinkedHashMap",
            "LinkedHashSet", "MutableMap.MutableEntry"
        ),
        builtins = setOf(
            "println", "print", "readln", "readLine", "error", "require",
            "check", "assert", "TODO", "run", "let", "apply",
            "also", "with", "use", "repeat",
            "listOf", "setOf", "mapOf", "mutableListOf",
            "mutableSetOf", "mutableMapOf", "arrayOf",
            "intArrayOf", "longArrayOf", "floatArrayOf",
            "doubleArrayOf", "booleanArrayOf", "charArrayOf",
            "byteArrayOf", "shortArrayOf",
            "emptyList", "emptySet", "emptyMap",
            "sequenceOf", "generateSequence",
            "lazy", "lazyOf", "enumValues", "enumValueOf",
            "synchronized", "hashMapOf", "linkedMapOf",
            "hashSetOf", "linkedSetOf",
            "maxOf", "minOf", "count", "sumOf",
            "withIndex", "rangeTo", "downTo", "step",
            "until", "coerceIn", "coerceAtLeast", "coerceAtMost",
            "measureTimeMillis", "measureNanoTime",
            "compareBy", "compareByDescending",
            "groupBy", "associateBy", "partition",
            "zip", "unzip", "flatten", "flatMap",
            "filter", "map", "forEach", "reduce", "fold",
            "find", "findLast", "first", "last", "single",
            "any", "all", "none", "count", "contains",
            "elementAt", "elementAtOrNull", "elementAtOrElse",
            "distinct", "distinctBy", "intersect", "union", "subtract",
            "sorted", "sortedBy", "sortedDescending", "sortedByDescending",
            "reversed", "shuffled", "take", "takeLast", "drop", "dropLast",
            "chunked", "windowed", "slice",
            "joinToString", "joinTo", "plus", "minus",
            "toInt", "toLong", "toFloat", "toDouble",
            "toString", "toBoolean", "toChar", "toByte", "toShort",
            "or", "and", "xor", "shl", "shr", "ushr",
            "inv", "not", "inc", "dec"
        ),
        booleanLiterals = setOf("true", "false", "null"),
        constants = setOf("PI", "E", "Int.MAX_VALUE", "Int.MIN_VALUE",
            "Long.MAX_VALUE", "Long.MIN_VALUE",
            "Float.MAX_VALUE", "Float.MIN_VALUE",
            "Double.MAX_VALUE", "Double.MIN_VALUE"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'"),
        multiStringDelimiters = listOf("\"\"\""),
        hasAnnotations = true,
        numericSuffixes = listOf("L", "f", "F", "u", "U", "ul", "UL")
    )

    val Java = LanguageDef(
        name = "Java",
        keywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue", "default",
            "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return",
            "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "var", "record", "sealed",
            "permits", "yield", "non-sealed", "module", "requires",
            "exports", "opens", "to", "uses", "provides", "with",
            "transitive"
        ),
        types = setOf(
            "int", "long", "float", "double", "boolean", "char",
            "byte", "short", "void", "String", "Integer", "Long",
            "Float", "Double", "Boolean", "Character", "Byte", "Short",
            "Object", "Class", "System", "Math", "Thread", "Runnable",
            "Exception", "RuntimeException", "Throwable", "Error",
            "IOException", "FileNotFoundException", "NullPointerException",
            "IllegalArgumentException", "IllegalStateException",
            "ArrayIndexOutOfBoundsException", "IndexOutOfBoundsException",
            "ArithmeticException", "ClassCastException",
            "CloneNotSupportedException", "InterruptedException",
            "List", "ArrayList", "LinkedList", "Map", "HashMap",
            "LinkedHashMap", "TreeMap", "Set", "HashSet", "LinkedHashSet",
            "TreeSet", "Queue", "Deque", "ArrayDeque", "PriorityQueue",
            "Collection", "Collections", "Arrays", "Comparator",
            "Comparable", "Iterator", "Iterable", "Optional",
            "StringBuilder", "StringBuffer", "CharSequence",
            "InputStream", "OutputStream", "Reader", "Writer",
            "File", "Path", "Paths", "Files", "URL", "URI",
            "InputStreamReader", "OutputStreamWriter",
            "BufferedReader", "BufferedWriter", "PrintWriter",
            "Scanner", "Formatter", "Random", "UUID",
            "BigInteger", "BigDecimal", "Date", "Calendar",
            "LocalDate", "LocalTime", "LocalDateTime", "Instant",
            "Stream", "IntStream", "LongStream", "DoubleStream",
            "Collector", "Collectors", "Function", "Predicate",
            "Consumer", "Supplier", "UnaryOperator", "BinaryOperator",
            "Callable", "Future", "CompletableFuture",
            "Executor", "ExecutorService", "Executors",
            "ThreadPoolExecutor", "ScheduledExecutorService"
        ),
        builtins = setOf(
            "print", "println", "printf",
            "Math", "abs", "max", "min", "pow", "sqrt", "ceil",
            "floor", "round", "random", "sin", "cos", "tan",
            "toRadians", "toDegrees", "exp", "log", "log10",
            "System", "currentTimeMillis", "nanoTime", "arraycopy",
            "identityHashCode", "gc", "exit",
            "String", "valueOf", "format", "join", "copyValueOf",
            "Integer", "parseInt", "valueOf", "toString", "toHexString",
            "Long", "parseLong", "Float", "parseFloat",
            "Double", "parseDouble", "Boolean", "parseBoolean",
            "Character", "isDigit", "isLetter", "isWhitespace",
            "toUpperCase", "toLowerCase",
            "Arrays", "asList", "sort", "binarySearch", "copyOf",
            "copyOfRange", "fill", "equals", "deepEquals",
            "toString", "deepToString", "stream",
            "Collections", "sort", "binarySearch", "reverse",
            "shuffle", "min", "max", "fill", "copy",
            "unmodifiableList", "unmodifiableSet", "unmodifiableMap",
            "synchronizedList", "synchronizedSet", "synchronizedMap"
        ),
        booleanLiterals = setOf("true", "false", "null"),
        constants = setOf("PI", "E", "Integer.MAX_VALUE", "Integer.MIN_VALUE",
            "Long.MAX_VALUE", "Long.MIN_VALUE",
            "Float.MAX_VALUE", "Float.MIN_VALUE",
            "Float.POSITIVE_INFINITY", "Float.NEGATIVE_INFINITY",
            "Double.MAX_VALUE", "Double.MIN_VALUE",
            "Double.POSITIVE_INFINITY", "Double.NEGATIVE_INFINITY"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        hasAnnotations = true,
        numericSuffixes = listOf("L", "l", "f", "F", "d", "D")
    )

    // ── C-family languages ─────────────────────────────────────────────

    val C = LanguageDef(
        name = "C",
        keywords = setOf(
            "auto", "break", "case", "char", "const", "continue",
            "default", "do", "double", "else", "enum", "extern",
            "float", "for", "goto", "if", "inline", "int",
            "long", "register", "restrict", "return", "short",
            "signed", "sizeof", "static", "struct", "switch",
            "typedef", "union", "unsigned", "void", "volatile", "while",
            "_Bool", "_Complex", "_Imaginary", "_Atomic",
            "_Static_assert", "_Alignas", "_Alignof", "_Generic",
            "_Noreturn", "_Thread_local", "bool", "true", "false",
            "complex"
        ),
        types = setOf(
            "int", "long", "float", "double", "char", "void",
            "short", "unsigned", "signed", "size_t", "ssize_t",
            "int8_t", "int16_t", "int32_t", "int64_t",
            "uint8_t", "uint16_t", "uint32_t", "uint64_t",
            "uintptr_t", "intptr_t", "ptrdiff_t", "wchar_t",
            "FILE", "va_list", "time_t", "clock_t",
            "off_t", "mode_t", "pid_t", "uid_t", "gid_t",
            "socklen_t", "sockaddr", "sockaddr_in",
            "fd_set", "sigset_t", "jmp_buf", "div_t", "ldiv_t"
        ),
        builtins = setOf(
            "printf", "scanf", "fprintf", "sprintf", "snprintf",
            "puts", "gets", "putchar", "getchar", "fgets", "fputs",
            "fopen", "fclose", "fread", "fwrite", "fseek", "ftell",
            "rewind", "fflush", "feof", "ferror", "remove", "rename",
            "tmpfile", "tmpnam",
            "malloc", "calloc", "realloc", "free",
            "memcpy", "memmove", "memset", "memcmp", "memchr",
            "strlen", "strcpy", "strncpy", "strcat", "strncat",
            "strcmp", "strncmp", "strchr", "strrchr", "strstr",
            "strtok", "strspn", "strcspn", "strpbrk",
            "atoi", "atol", "atof", "strtol", "strtoul", "strtod",
            "rand", "srand", "abs", "labs", "div", "ldiv",
            "qsort", "bsearch",
            "exit", "abort", "atexit", "assert",
            "signal", "raise",
            "setjmp", "longjmp",
            "time", "clock", "difftime", "mktime", "localtime", "gmtime",
            "perror", "strerror",
            "isalnum", "isalpha", "iscntrl", "isdigit", "isgraph",
            "islower", "isprint", "ispunct", "isspace", "isupper",
            "isxdigit", "toupper", "tolower"
        ),
        booleanLiterals = setOf("true", "false", "NULL"),
        constants = setOf("EOF", "stdin", "stdout", "stderr",
            "EXIT_SUCCESS", "EXIT_FAILURE",
            "RAND_MAX", "CLOCKS_PER_SEC",
            "SIG_DFL", "SIG_IGN", "SIG_ERR",
            "SIGINT", "SIGTERM", "SIGABRT", "SIGSEGV",
            "SEEK_SET", "SEEK_CUR", "SEEK_END",
            "BUFSIZ", "L_tmpnam", "FILENAME_MAX",
            "CHAR_BIT", "CHAR_MIN", "CHAR_MAX",
            "INT_MIN", "INT_MAX", "LONG_MIN", "LONG_MAX",
            "SHRT_MIN", "SHRT_MAX",
            "FLT_MIN", "FLT_MAX", "DBL_MIN", "DBL_MAX"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        hasPreprocessor = true,
        numericSuffixes = listOf("L", "l", "LL", "ll", "U", "u", "UL", "ul", "f", "F")
    )

    val CPP = C.copy(
        name = "C++",
        keywords = C.keywords + setOf(
            "alignas", "alignof", "and", "and_eq", "asm", "bitand",
            "bitor", "catch", "class", "compl", "concept", "constexpr",
            "const_cast", "co_await", "co_return", "co_yield",
            "decltype", "delete", "dynamic_cast", "explicit", "export",
            "false", "friend", "mutable", "namespace", "new",
            "noexcept", "not", "not_eq", "nullptr", "operator",
            "or", "or_eq", "override", "private", "protected",
            "public", "requires", "reinterpret_cast", "static_assert",
            "static_cast", "template", "this", "throw", "true",
            "try", "typeid", "typename", "using", "virtual", "xor", "xor_eq",
            "final", "import", "module", "export"
        ),
        types = C.types + setOf(
            "bool", "wchar_t", "char16_t", "char32_t", "string",
            "vector", "map", "unordered_map", "set", "unordered_set",
            "list", "forward_list", "deque", "array", "queue",
            "priority_queue", "stack", "pair", "tuple", "optional",
            "variant", "any", "shared_ptr", "unique_ptr", "weak_ptr",
            "function", "bind", "reference_wrapper",
            "string_view", "span", "stringstream",
            "istringstream", "ostringstream",
            "ifstream", "ofstream", "fstream",
            "iterator", "const_iterator", "reverse_iterator",
            "ostream", "istream", "iostream",
            "chrono", "thread", "mutex", "lock_guard",
            "unique_lock", "condition_variable",
            "future", "promise", "packaged_task", "async",
            "type_info", "type_index", "bad_cast", "bad_typeid",
            "exception", "bad_alloc", "bad_array_new_length",
            "logic_error", "runtime_error", "range_error",
            "overflow_error", "underflow_error", "domain_error",
            "invalid_argument", "length_error", "out_of_range",
            "system_error", "error_code", "error_condition"
        ),
        builtins = C.builtins + setOf(
            "cout", "cin", "cerr", "clog", "endl", "flush",
            "make_shared", "make_unique", "make_pair", "make_tuple",
            "static_pointer_cast", "dynamic_pointer_cast",
            "const_pointer_cast", "to_string", "stoi", "stol", "stoll",
            "stof", "stod", "stold",
            "getline", "sort", "find", "count", "lower_bound",
            "upper_bound", "binary_search", "next_permutation",
            "prev_permutation", "reverse", "rotate", "shuffle",
            "copy", "move", "fill", "generate", "transform",
            "replace", "remove", "unique", "accumulate",
            "inner_product", "adjacent_difference", "partial_sum",
            "max_element", "min_element",
            "begin", "end", "cbegin", "cend",
            "rbegin", "rend", "crbegin", "crend",
            "size", "empty", "data", "front", "back",
            "swap", "exchange", "move_if_noexcept",
            "async", "launch"
        ),
        hasAnnotations = false,
        hasPreprocessor = true
    )

    val CSharp = LanguageDef(
        name = "C#",
        keywords = setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case",
            "catch", "char", "checked", "class", "const", "continue",
            "decimal", "default", "delegate", "do", "double", "else",
            "enum", "event", "explicit", "extern", "false", "finally",
            "fixed", "float", "for", "foreach", "goto", "if",
            "implicit", "in", "int", "interface", "internal", "is",
            "lock", "long", "namespace", "new", "null", "object",
            "operator", "out", "override", "params", "private",
            "protected", "public", "readonly", "record", "ref",
            "return", "sbyte", "sealed", "short", "sizeof",
            "stackalloc", "static", "string", "struct", "switch",
            "this", "throw", "true", "try", "typeof", "uint",
            "ulong", "unchecked", "unsafe", "ushort", "using",
            "virtual", "void", "volatile", "while", "var", "async",
            "await", "yield", "partial", "file", "global", "required",
            "scoped", "init", "get", "set", "value", "where",
            "select", "from", "where", "join", "group", "into",
            "let", "orderby", "ascending", "descending", "equals",
            "by", "on", "when", "not", "and", "or",
            "is", "pattern", "with"
        ),
        types = setOf(
            "bool", "byte", "sbyte", "short", "ushort", "int", "uint",
            "long", "ulong", "float", "double", "decimal", "char",
            "string", "object", "dynamic", "void",
            "nint", "nuint", "var",
            "Task", "Task<T>", "ValueTask", "ValueTask<T>",
            "CancellationToken", "CancellationTokenSource",
            "IEnumerable", "IEnumerable<T>", "IEnumerator",
            "ICollection", "ICollection<T>", "IList", "IList<T>",
            "IDictionary", "IDictionary<TKey,TValue>",
            "ISet<T>", "IReadOnlyCollection<T>",
            "IReadOnlyList<T>", "IReadOnlyDictionary<TKey,TValue>",
            "List<T>", "Dictionary<TKey,TValue>", "HashSet<T>",
            "LinkedList<T>", "Queue<T>", "Stack<T>",
            "SortedList<TKey,TValue>", "SortedSet<T>",
            "ObservableCollection<T>",
            "StringBuilder", "StringReader", "StringWriter",
            "Stream", "MemoryStream", "FileStream",
            "StreamReader", "StreamWriter", "BinaryReader", "BinaryWriter",
            "TextReader", "TextWriter",
            "Exception", "InvalidOperationException",
            "ArgumentException", "ArgumentNullException",
            "ArgumentOutOfRangeException", "FormatException",
            "IndexOutOfRangeException", "NullReferenceException",
            "NotImplementedException", "NotSupportedException",
            "InvalidCastException", "OverflowException",
            "DivideByZeroException", "TimeoutException",
            "FileNotFoundException", "IOException",
            "Action", "Func", "Predicate<T>",
            "Nullable<T>", "Span<T>", "ReadOnlySpan<T>",
            "Memory<T>", "ReadOnlyMemory<T>",
            "DateOnly", "TimeOnly", "DateTime", "DateTimeOffset",
            "TimeSpan", "Guid", "Uri", "Version",
            "Regex", "Match", "Group", "Capture"
        ),
        builtins = setOf(
            "Console", "Write", "WriteLine", "Read", "ReadLine",
            "ReadKey", "Beep", "Clear", "ResetColor",
            "Convert", "ToInt32", "ToString", "ToDouble",
            "ToBoolean", "ToDateTime", "ChangeType",
            "Math", "Abs", "Acos", "Asin", "Atan", "Atan2",
            "Ceiling", "Cos", "Cosh", "Exp", "Floor",
            "Log", "Log10", "Max", "Min", "Pow",
            "Round", "Sign", "Sin", "Sinh", "Sqrt",
            "Tan", "Tanh", "Truncate",
            "String", "Format", "Join", "Concat", "Compare",
            "Equals", "IndexOf", "LastIndexOf", "Substring",
            "Replace", "Split", "Trim", "ToUpper", "ToLower",
            "StartsWith", "EndsWith", "Contains", "IsNullOrEmpty",
            "IsNullOrWhiteSpace", "PadLeft", "PadRight",
            "Remove", "Insert", "CopyTo",
            "Environment", "GetEnvironmentVariable",
            "SetEnvironmentVariable", "GetFolderPath",
            "CommandLine", "CurrentDirectory", "Exit",
            "Task", "Run", "Delay", "WhenAll", "WhenAny",
            "FromResult", "FromException", "FromCanceled",
            "Wait", "WaitAll", "WaitAny",
            "Parallel", "For", "ForEach", "Invoke",
            "Enumerable", "Range", "Repeat", "Empty",
            "Where", "Select", "SelectMany", "Aggregate",
            "All", "Any", "Contains", "Count", "Distinct",
            "ElementAt", "First", "FirstOrDefault", "Last",
            "LastOrDefault", "Single", "SingleOrDefault",
            "GroupBy", "Join", "OrderBy", "OrderByDescending",
            "ThenBy", "ThenByDescending",
            "Skip", "SkipWhile", "Take", "TakeWhile",
            "Sum", "Min", "Max", "Average",
            "OfType", "Cast", "ToList", "ToArray",
            "ToDictionary", "ToLookup", "ToHashSet",
            "KeyValuePair", "Create", "CompareTo",
            "nameof", "sizeof", "typeof", "default"
        ),
        booleanLiterals = setOf("true", "false", "null"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'"),
        multiStringDelimiters = listOf("\"\"\""),
        numericSuffixes = listOf("f", "F", "d", "D", "m", "M", "L", "l", "u", "U",
            "ul", "UL", "lu", "LU")
    )

    // ── Database languages ─────────────────────────────────────────────

    val SQL = LanguageDef(
        name = "SQL",
        keywords = setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES",
            "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP",
            "TABLE", "INDEX", "VIEW", "PROCEDURE", "FUNCTION",
            "TRIGGER", "SCHEMA", "DATABASE", "GRANT", "REVOKE",
            "COMMIT", "ROLLBACK", "SAVEPOINT", "BEGIN", "END",
            "TRANSACTION", "LOCK", "UNLOCK",
            "AND", "OR", "NOT", "IN", "BETWEEN", "LIKE", "IS",
            "NULL", "EXISTS", "ALL", "ANY", "SOME",
            "UNIQUE", "DISTINCT", "AS", "ON", "JOIN",
            "INNER", "OUTER", "LEFT", "RIGHT", "FULL", "CROSS",
            "NATURAL", "USING", "ORDER", "BY", "ASC", "DESC",
            "GROUP", "HAVING", "LIMIT", "OFFSET", "TOP",
            "UNION", "INTERSECT", "EXCEPT", "ALL",
            "CASE", "WHEN", "THEN", "ELSE", "END",
            "IF", "ELSE", "WHILE", "FOR", "DECLARE", "PRINT",
            "RETURN", "BREAK", "CONTINUE", "GOTO", "WAITFOR",
            "EXEC", "EXECUTE", "CALL", "DO",
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT",
            "CHECK", "DEFAULT", "AUTO_INCREMENT", "IDENTITY",
            "CASCADE", "RESTRICT", "NO", "ACTION",
            "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT",
            "NUMERIC", "DECIMAL", "FLOAT", "REAL", "DOUBLE",
            "PRECISION", "CHAR", "VARCHAR", "TEXT", "NCHAR",
            "NVARCHAR", "NTEXT", "BLOB", "CLOB", "BINARY",
            "VARBINARY", "DATE", "TIME", "TIMESTAMP", "DATETIME",
            "BOOLEAN", "BIT", "MONEY", "SMALLMONEY",
            "TRUE", "FALSE", "UNKNOWN",
            "COUNT", "SUM", "AVG", "MIN", "MAX",
            "CAST", "CONVERT", "COALESCE", "NULLIF",
            "ISNULL", "IFNULL", "NVL",
            "SUBSTRING", "UPPER", "LOWER", "LENGTH", "TRIM",
            "REPLACE", "CONCAT", "INSTR", "LOCATE",
            "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "MOD",
            "POWER", "SQRT", "EXP", "LN", "LOG", "LOG10",
            "NOW", "CURDATE", "CURTIME", "DATEADD",
            "DATEDIFF", "DATEPART", "EXTRACT", "YEAR", "MONTH",
            "DAY", "HOUR", "MINUTE", "SECOND",
            "ROW_NUMBER", "RANK", "DENSE_RANK", "NTILE",
            "LEAD", "LAG", "FIRST_VALUE", "LAST_VALUE",
            "OVER", "PARTITION", "ROWS", "RANGE", "UNBOUNDED",
            "PRECEDING", "FOLLOWING", "CURRENT", "ROW",
            "WITH", "RECURSIVE", "TEMP", "TEMPORARY",
            "MATERIALIZED", "REFRESH", "VACUUM", "ANALYZE",
            "EXPLAIN", "DESCRIBE", "SHOW", "USE"
        ),
        types = setOf(
            "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT",
            "NUMERIC", "DECIMAL", "FLOAT", "REAL", "DOUBLE",
            "PRECISION", "MONEY", "SMALLMONEY",
            "CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "TEXT", "NTEXT", "CLOB",
            "BINARY", "VARBINARY", "BLOB", "IMAGE",
            "BIT", "BOOLEAN",
            "DATE", "TIME", "DATETIME", "TIMESTAMP", "SMALLDATETIME",
            "YEAR", "INTERVAL",
            "XML", "JSON", "UUID", "UNIQUEIDENTIFIER",
            "ENUM", "SET", "JSONB", "ARRAY", "HSTORE",
            "GEOMETRY", "GEOGRAPHY", "POINT", "LINESTRING", "POLYGON",
            "SERIAL", "BIGSERIAL", "SMALLSERIAL"
        ).map { it.lowercase() }.toSet() + setOf(
            "int", "integer", "bigint", "smallint", "tinyint",
            "numeric", "decimal", "float", "real", "double",
            "money", "smallmoney",
            "char", "varchar", "nchar", "nvarchar", "text", "ntext", "clob",
            "binary", "varbinary", "blob", "image",
            "bit", "boolean",
            "date", "time", "datetime", "timestamp", "smalldatetime",
            "year", "interval",
            "xml", "json", "uuid", "uniqueidentifier",
            "enum", "set", "jsonb", "array", "hstore",
            "geometry", "geography", "point", "linestring", "polygon",
            "serial", "bigserial", "smallserial"
        ),
        builtins = setOf(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE",
            "NULLIF", "CAST", "CONVERT", "SUBSTRING",
            "UPPER", "LOWER", "TRIM", "LENGTH", "REPLACE",
            "CONCAT", "INSTR", "SUBSTR", "NVL", "ISNULL",
            "ABS", "CEIL", "FLOOR", "ROUND", "MOD",
            "POWER", "SQRT", "EXP", "LN", "LOG",
            "NOW", "CURDATE", "CURTIME", "DATEADD",
            "DATEDIFF", "DATEPART", "EXTRACT",
            "ROW_NUMBER", "RANK", "DENSE_RANK", "NTILE",
            "LEAD", "LAG", "FIRST_VALUE", "LAST_VALUE",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP"
        ).map { it.lowercase() }.toSet() + setOf(
            "count", "sum", "avg", "min", "max", "coalesce",
            "nullif", "cast", "convert", "substring",
            "upper", "lower", "trim", "length", "replace",
            "concat", "instr", "substr", "nvl", "isnull",
            "abs", "ceil", "floor", "round", "mod",
            "power", "sqrt", "exp", "ln", "log",
            "now", "curdate", "curtime", "dateadd",
            "datediff", "datepart", "extract",
            "row_number", "rank", "dense_rank", "ntile",
            "lead", "lag", "first_value", "last_value",
            "current_date", "current_time", "current_timestamp"
        ),
        booleanLiterals = setOf("TRUE", "FALSE", "NULL", "UNKNOWN",
            "true", "false", "null", "unknown"),
        lineComments = listOf("--"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("'"),
        numericSuffixes = emptyList()
    )

    // ── Shell / scripting ──────────────────────────────────────────────

    val Shell = LanguageDef(
        name = "Shell",
        keywords = setOf(
            "if", "then", "else", "elif", "fi", "case", "esac",
            "for", "while", "until", "do", "done", "in",
            "select", "repeat", "until", "foreach", "end",
            "function", "return", "break", "continue", "exit",
            "source", "export", "local", "readonly", "unset",
            "declare", "typeset", "set", "unset", "shift",
            "trap", "exec", "eval", "let", "test", "[",
            "[[", "]]", "print", "printf", "echo",
            "read", "sleep", "wait", "kill", "type",
            "alias", "unalias", "bind", "builtin", "command",
            "enable", "help", "ulimit", "umask", "wait",
            "pushd", "popd", "dirs",
            "true", "false", "not"
        ),
        types = emptySet(),
        builtins = setOf(
            "echo", "printf", "read", "cd", "pwd", "ls",
            "cat", "grep", "sed", "awk", "find", "xargs",
            "sort", "uniq", "wc", "head", "tail", "tee",
            "cut", "tr", "paste", "join", "comm",
            "diff", "patch", "cmp",
            "chmod", "chown", "chgrp", "mv", "cp", "rm",
            "mkdir", "rmdir", "ln", "touch", "dd",
            "mount", "umount", "df", "du", "sync",
            "ps", "top", "kill", "killall", "nice", "renice",
            "ping", "traceroute", "netstat", "ss",
            "ssh", "scp", "rsync", "curl", "wget",
            "tar", "gzip", "gunzip", "bzip2", "xz", "zcat",
            "unzip", "zip", "7z", "rar",
            "date", "cal", "bc", "expr", "test",
            "$", "$$", "$!", "$?", "$*", "$@", "$#", "$0"
        ),
        booleanLiterals = setOf("true", "false", "null"),
        lineComments = listOf("#"),
        stringDelimiters = listOf("\"", "'"),
        variablePrefix = '$',
        multiStringDelimiters = listOf("\"\"\"", "'''"),
        hasAnnotations = false
    )

    // ── Documentation ──────────────────────────────────────────────────

    val Markdown = LanguageDef(
        name = "Markdown",
        keywords = emptySet(),
        builtins = emptySet(),
        booleanLiterals = setOf("true", "false"),
        lineComments = emptyList(),
        stringDelimiters = listOf("\"", "'"),
        hasMarkdownHeaders = true,
        hasMarkdownLinks = true,
        isProperties = false
    )

    // ── Build / config ─────────────────────────────────────────────────

    val Gradle = LanguageDef(
        name = "Gradle",
        keywords = setOf(
            "apply", "plugin", "from", "import", "buildscript",
            "repositories", "dependencies", "android", "defaultConfig",
            "buildTypes", "productFlavors", "sourceSets",
            "compileSdk", "minSdk", "targetSdk", "buildToolsVersion",
            "applicationId", "versionCode", "versionName",
            "compileSdkVersion", "minSdkVersion", "targetSdkVersion",
            "mavenCentral", "google", "jcenter", "mavenLocal",
            "implementation", "api", "compileOnly", "runtimeOnly",
            "testImplementation", "androidTestImplementation",
            "debugImplementation", "releaseImplementation",
            "kapt", "annotationProcessor", "ksp",
            "project", "rootProject", "allprojects", "subprojects",
            "task", "tasks", "register", "configure",
            "named", "matching", "all", "each",
            "type", "group", "name", "description",
            "dependsOn", "mustRunAfter", "shouldRunAfter",
            "finalizedBy", "onlyIf", "enabled",
            "outputs", "inputs", "file", "files",
            "fileTree", "zipTree", "tarTree",
            "copy", "delete", "mkdir", "rename",
            "into", "from", "include", "exclude", "filter",
            "doFirst", "doLast", "leftShift",
            "ext", "extra", "set", "get",
            "properties", "hasProperty", "property",
            "this", "true", "false", "null",
            "if", "else", "for", "while", "return", "def",
            "val", "var", "fun", "class", "interface", "object",
            "enum", "data", "sealed", "companion",
            "override", "open", "abstract", "private",
            "inline", "infix", "operator", "suspend"
        ),
        types = setOf(
            "String", "Int", "Long", "Boolean", "File",
            "Project", "Task", "Copy", "Delete", "Sync",
            "Configuration", "Dependency", "DependencySet",
            "RepositoryHandler", "ArtifactHandler",
            "SourceSet", "SourceSetContainer",
            "NamedDomainObjectContainer",
            "ExtensionAware", "ExtraPropertiesExtension",
            "Action", "Closure", "Callable", "Provider",
            "RegularFile", "Directory", "FileCollection",
            "FileTree", "ConfigurableFileTree",
            "TaskContainer", "TaskProvider",
            "Named", "NamedDomainObjectProvider",
            "WorkResult", "WorkResults",
            "Transformer", "Spec", "Ordering",
            "PatternFilterable", "CopySpec",
            "Javadoc", "Jar", "Zip", "Tar", "War",
            "Exec", "JavaExec", "JavaCompile",
            "Test", "JacocoReport", "Ssh",
            "DefaultTask", "Delete"
        ),
        builtins = setOf(
            "println", "print", "file", "files", "fileTree",
            "zipTree", "tarTree", "resources", "uri", "url",
            "logger", "logging", "gradle", "settings",
            "providers", "objects", "project",
            "afterEvaluate", "beforeEvaluate",
            "allprojects", "subprojects", "rootProject",
            "task", "tasks", "register",
            "configurations", "artifacts",
            "repositories", "dependencies",
            "buildscript", "sourceSets",
            "java", "groovy", "scala", "kotlin",
            "check", "build", "assemble", "clean", "test",
            "uploadArchives", "publishing"
        ),
        booleanLiterals = setOf("true", "false", "null"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'"),
        multiStringDelimiters = listOf("\"\"\""),
        hasAnnotations = true,
        numericSuffixes = listOf("L", "f", "F")
    )

    // ── Dynamic / scripting ────────────────────────────────────────────

    val Ruby = LanguageDef(
        name = "Ruby",
        keywords = setOf(
            "BEGIN", "END", "alias", "and", "begin", "break",
            "case", "class", "def", "defined?", "do", "else",
            "elsif", "end", "ensure", "false", "for", "if",
            "in", "module", "next", "nil", "not", "or",
            "redo", "rescue", "retry", "return", "self", "super",
            "then", "true", "undef", "unless", "until", "when",
            "while", "yield", "nil?", "block_given?", "catch",
            "throw", "fail", "raise", "lambda", "proc",
            "private", "protected", "public", "attr_reader",
            "attr_writer", "attr_accessor", "include", "extend",
            "prepend", "require", "load", "autoload",
            "alias_method", "module_function", "refine", "using",
            "defined?", "__FILE__", "__LINE__", "__ENCODING__",
            "then", "in", "unless"
        ),
        types = setOf(
            "String", "Integer", "Float", "Symbol", "Array",
            "Hash", "Range", "Regexp", "Proc", "Lambda",
            "Method", "UnboundMethod", "Binding",
            "Class", "Module", "Object", "BasicObject",
            "Kernel", "NilClass", "TrueClass", "FalseClass",
            "Numeric", "Complex", "Rational",
            "Time", "Date", "DateTime", "File", "Dir",
            "IO", "Socket", "Thread", "Mutex",
            "Exception", "StandardError", "RuntimeError",
            "ArgumentError", "TypeError", "NameError",
            "NoMethodError", "IndexError", "KeyError",
            "RangeError", "ZeroDivisionError", "IOError",
            "LoadError", "SyntaxError", "SystemCallError",
            "Enumerable", "Comparable", "Observable",
            "Forwardable", "Singleton", "Marshal",
            "JSON", "YAML", "ERB", "Rake", "Rack"
        ),
        builtins = setOf(
            "puts", "print", "p", "pp", "printf", "sprintf",
            "gets", "readline", "readlines",
            "require", "load", "autoload", "require_relative",
            "include", "extend", "prepend",
            "attr_reader", "attr_writer", "attr_accessor",
            "alias_method", "module_function",
            "private", "protected", "public",
            "raise", "fail", "catch", "throw",
            "lambda", "proc", "block_given?",
            "send", "public_send", "respond_to?",
            "method", "methods", "instance_methods",
            "new", "allocate", "superclass", "ancestors",
            "is_a?", "kind_of?", "instance_of?",
            "nil?", "empty?", "any?", "all?",
            "map", "collect", "select", "filter", "reject",
            "each", "each_with_index", "each_with_object",
            "reduce", "inject", "fold",
            "find", "detect", "find_all", "grep",
            "sort", "sort_by", "group_by",
            "first", "last", "take", "drop",
            "flatten", "compact", "uniq", "uniq_by",
            "join", "split", "chars", "bytes",
            "upcase", "downcase", "capitalize", "swapcase",
            "strip", "chomp", "chop", "delete", "tr",
            "gsub", "sub", "scan", "match",
            "to_s", "to_i", "to_f", "to_a", "to_h",
            "to_sym", "to_str", "to_int",
            "inspect", "to_s", "object_id", "tap"
        ),
        booleanLiterals = setOf("true", "false", "nil"),
        constants = setOf("__FILE__", "__LINE__", "__ENCODING__",
            "ARGV", "ARGF", "ENV", "DATA", "STDIN", "STDOUT", "STDERR",
            "RUBY_VERSION", "RUBY_PLATFORM", "RUBY_ENGINE",
            "RUBY_DESCRIPTION", "RUBY_RELEASE_DATE",
            "TRUE", "FALSE", "NIL"),
        lineComments = listOf("#"),
        blockComments = listOf("=begin" to "=end"),
        stringDelimiters = listOf("\"", "'"),
        multiStringDelimiters = listOf("<<~", "<<-"),
        variablePrefix = '$'
    )

    val PHP = LanguageDef(
        name = "PHP",
        keywords = setOf(
            "abstract", "and", "array", "as", "break", "callable",
            "case", "catch", "class", "clone", "const", "continue",
            "declare", "default", "die", "do", "echo", "else",
            "elseif", "empty", "enddeclare", "endfor", "endforeach",
            "endif", "endswitch", "endwhile", "enum", "eval",
            "exit", "extends", "final", "finally", "fn", "for",
            "foreach", "function", "global", "goto", "if",
            "implements", "include", "include_once", "instanceof",
            "insteadof", "interface", "isset", "list", "match",
            "namespace", "new", "or", "print", "private",
            "protected", "public", "readonly", "require",
            "require_once", "return", "static", "switch", "throw",
            "trait", "try", "unset", "use", "var", "while", "xor",
            "yield", "yield from",
            "int", "float", "bool", "string", "void", "never",
            "mixed", "iterable", "self", "parent", "static",
            "null", "true", "false"
        ),
        types = setOf(
            "int", "float", "bool", "string", "array", "object",
            "callable", "iterable", "void", "never", "mixed",
            "null", "false", "true",
            "self", "parent", "static",
            "stdClass", "Exception", "Throwable",
            "Error", "TypeError", "ValueError",
            "ArgumentCountError", "DivisionByZeroError",
            "ArithmeticError", "ParseError", "AssertionError",
            "CompileError", "FiberError",
            "PDO", "PDOException", "PDOStatement",
            "DateTime", "DateTimeImmutable", "DateTimeInterface",
            "DateInterval", "DatePeriod", "DateTimeZone",
            "Closure", "Generator", "Fiber",
            "SplFileInfo", "SplFileObject", "DirectoryIterator",
            "FilesystemIterator", "GlobIterator",
            "ArrayIterator", "ArrayObject",
            "Iterator", "IteratorAggregate",
            "Traversable", "Countable", "ArrayAccess",
            "Serializable", "JsonSerializable",
            "Stringable", "UnitEnum", "BackedEnum",
            "ReflectionClass", "ReflectionMethod",
            "ReflectionProperty", "ReflectionFunction"
        ),
        builtins = setOf(
            "echo", "print", "printf", "sprintf", "die", "exit",
            "var_dump", "print_r", "var_export", "debug_backtrace",
            "debug_print_backtrace", "error_log", "trigger_error",
            "set_error_handler", "set_exception_handler",
            "register_shutdown_function",
            "array_map", "array_filter", "array_reduce",
            "array_merge", "array_keys", "array_values",
            "array_push", "array_pop", "array_shift",
            "array_unshift", "array_slice", "array_splice",
            "array_search", "array_key_exists", "in_array",
            "array_unique", "array_reverse", "array_diff",
            "array_intersect", "array_fill", "array_flip",
            "array_combine", "array_chunk", "array_column",
            "array_walk", "array_product", "array_sum",
            "count", "sizeof", "is_array", "is_string",
            "is_int", "is_float", "is_bool", "is_null",
            "is_object", "is_resource", "is_scalar",
            "is_numeric", "is_callable", "is_iterable",
            "isset", "unset", "empty", "strlen",
            "strpos", "strrpos", "str_contains", "str_starts_with",
            "str_ends_with", "substr", "str_replace",
            "strtolower", "strtoupper", "trim", "explode",
            "implode", "join", "preg_match", "preg_replace",
            "preg_split", "preg_match_all",
            "json_encode", "json_decode", "serialize",
            "unserialize", "base64_encode", "base64_decode",
            "file_get_contents", "file_put_contents",
            "fopen", "fclose", "fgets", "fgetcsv",
            "fwrite", "fputs", "feof", "fread",
            "file", "file_exists", "is_file", "is_dir",
            "mkdir", "rmdir", "unlink", "copy", "rename",
            "move_uploaded_file", "filesize", "filemtime",
            "glob", "scandir", "realpath", "pathinfo",
            "dirname", "basename", "extension",
            "header", "headers", "http_response_code",
            "setcookie", "session_start", "session_destroy",
            "date", "time", "strtotime", "mktime",
            "checkdate", "getdate", "localtime",
            "mail", "filter_var", "filter_input",
            "password_hash", "password_verify",
            "hash", "hash_hmac", "md5", "sha1",
            "rand", "mt_rand", "random_int", "random_bytes",
            "defined", "constant", "function_exists",
            "class_exists", "method_exists", "property_exists",
            "get_class", "get_parent_class", "get_called_class"
        ),
        booleanLiterals = setOf("true", "false", "null"),
        lineComments = listOf("//", "#"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'"),
        variablePrefix = '$'
    )

    val Perl = LanguageDef(
        name = "Perl",
        keywords = setOf(
            "if", "else", "elsif", "unless", "while", "until",
            "for", "foreach", "do", "continue", "last", "next",
            "redo", "goto", "return", "die", "exit", "warn",
            "my", "our", "state", "local", "use", "require",
            "no", "package", "sub", "method", "signatures",
            "class", "field", "role", "multi", "proto",
            "async", "await", "coro",
            "try", "catch", "finally",
            "BEGIN", "CHECK", "INIT", "END", "UNITCHECK",
            "format", "formline", "write",
            "open", "close", "print", "printf", "say",
            "split", "join", "map", "grep", "sort",
            "defined", "undef", "exists", "delete",
            "bless", "ref", "tie", "untie",
            "tied", "can", "isa", "does",
            "overload", "import", "export",
            "eval", "exec", "system",
            "given", "when", "default",
            "break", "continue"
        ),
        types = setOf(
            "Scalar", "Array", "Hash", "Reference", "Glob",
            "Code", "Regexp", "IO", "Handle",
            "Fh", "Dir", "Package",
            "undef"
        ),
        builtins = setOf(
            "print", "say", "printf", "sprintf", "die", "warn",
            "chomp", "chop", "chr", "crypt", "hex", "index",
            "lc", "lcfirst", "length", "oct", "ord", "pack",
            "reverse", "rindex", "substr", "uc", "ucfirst",
            "split", "join", "quotemeta", "glob", "tr",
            "y", "pos", "study",
            "abs", "atan2", "cos", "exp", "hex", "int",
            "log", "oct", "rand", "sin", "sqrt", "srand",
            "pop", "push", "shift", "unshift", "splice",
            "delete", "exists", "keys", "values", "each",
            "map", "grep", "sort", "first",
            "defined", "undef", "ref", "bless",
            "open", "close", "read", "write", "print",
            "say", "printf", "eof", "fileno", "flock",
            "getc", "readline", "tell", "seek", "sysopen",
            "sysread", "syswrite", "sysseek",
            "opendir", "closedir", "readdir", "rewinddir",
            "stat", "lstat", "filetest",
            "-r", "-w", "-x", "-o", "-R", "-W", "-X", "-O",
            "-e", "-f", "-d", "-l", "-p", "-S", "-b", "-c",
            "-t", "-u", "-g", "-k", "-T", "-B", "-M", "-A", "-C",
            "chdir", "chmod", "chown", "chroot", "link",
            "mkdir", "rename", "rmdir", "symlink", "unlink",
            "utime", "umask",
            "caller", "dump", "eval", "local", "my", "our",
            "package", "require", "use", "no",
            "import", "export",
            "exec", "fork", "kill", "system", "wait",
            "waitpid", "times", "alarm", "sleep",
            "ioctl", "fcntl", "select", "socket",
            "accept", "bind", "connect", "listen",
            "recv", "send", "setsockopt", "getsockopt",
            "getsockname", "getpeername",
            "gmtime", "localtime", "time", "times",
            "caller", "wantarray"
        ),
        booleanLiterals = setOf("true", "false", "undef"),
        lineComments = listOf("#"),
        stringDelimiters = listOf("\"", "'", "`"),
        variablePrefix = '$'
    )

    val Lua = LanguageDef(
        name = "Lua",
        keywords = setOf(
            "and", "break", "do", "else", "elseif", "end",
            "false", "for", "function", "goto", "if", "in",
            "local", "nil", "not", "or", "repeat", "return",
            "then", "true", "until", "while",
            "type", "print", "pairs", "ipairs", "next",
            "tostring", "tonumber", "rawget", "rawset",
            "rawequal", "setmetatable", "getmetatable",
            "pcall", "xpcall", "select", "unpack",
            "error", "assert", "collectgarbage",
            "dofile", "load", "loadfile", "require",
            "module", "package", "string", "table",
            "math", "io", "os", "debug", "coroutine",
            "_G", "_VERSION",
            "self"
        ),
        types = setOf(
            "nil", "boolean", "number", "string", "function",
            "userdata", "thread", "table",
            "metatable", "array"
        ),
        builtins = setOf(
            "print", "type", "pairs", "ipairs", "next",
            "tostring", "tonumber", "rawget", "rawset",
            "rawequal", "setmetatable", "getmetatable",
            "pcall", "xpcall", "select", "unpack", "pack",
            "error", "assert", "collectgarbage",
            "dofile", "load", "loadfile", "require",
            "string.byte", "string.char", "string.find",
            "string.format", "string.gmatch", "string.gsub",
            "string.len", "string.lower", "string.match",
            "string.rep", "string.reverse", "string.sub",
            "string.upper",
            "table.concat", "table.insert", "table.move",
            "table.pack", "table.remove", "table.sort",
            "table.unpack", "table.cleaar",
            "math.abs", "math.acos", "math.asin", "math.atan",
            "math.atan2", "math.ceil", "math.cos", "math.cosh",
            "math.deg", "math.exp", "math.floor", "math.fmod",
            "math.huge", "math.ldexp", "math.log", "math.log10",
            "math.max", "math.min", "math.modf", "math.pi",
            "math.pow", "math.rad", "math.random",
            "math.randomseed", "math.sin", "math.sinh",
            "math.sqrt", "math.tan", "math.tanh",
            "io.close", "io.flush", "io.input", "io.lines",
            "io.open", "io.output", "io.popen", "io.read",
            "io.stderr", "io.stdin", "io.stdout", "io.tmpfile",
            "io.type", "io.write",
            "os.clock", "os.date", "os.difftime", "os.execute",
            "os.exit", "os.getenv", "os.remove", "os.rename",
            "os.setlocale", "os.time", "os.tmpname",
            "coroutine.create", "coroutine.resume",
            "coroutine.running", "coroutine.status",
            "coroutine.wrap", "coroutine.yield",
            "coroutine.isyieldable", "coroutine.close",
            "debug.debug", "debug.getuservalue",
            "debug.setuservalue", "debug.traceback",
            "_G", "_VERSION"
        ),
        booleanLiterals = setOf("true", "false", "nil"),
        lineComments = listOf("--"),
        blockComments = listOf("--[[" to "]]"),
        stringDelimiters = listOf("\"", "'"),
        multiStringDelimiters = listOf("[[", "[=["),
        variablePrefix = null,
        numericSuffixes = emptyList()
    )

    // ── Modern languages ───────────────────────────────────────────────

    val Go = LanguageDef(
        name = "Go",
        keywords = setOf(
            "break", "case", "chan", "const", "continue", "default",
            "defer", "else", "fallthrough", "for", "func", "go",
            "goto", "if", "import", "interface", "map", "package",
            "range", "return", "select", "struct", "switch", "type",
            "var", "true", "false", "iota", "nil"
        ),
        types = setOf(
            "int", "int8", "int16", "int32", "int64",
            "uint", "uint8", "uint16", "uint32", "uint64",
            "uintptr", "float32", "float64",
            "complex64", "complex128",
            "bool", "string", "byte", "rune", "error",
            "any", "comparable",
            "Slice", "Map", "Chan", "Func",
            "Reader", "Writer", "Closer", "ReadWriter",
            "ReadCloser", "WriteCloser", "ReadWriteCloser",
            "ReaderFrom", "WriterTo", "ReaderAt", "WriterAt",
            "Seeker", "ReadSeeker", "WriteSeeker", "ReadWriteSeeker",
            "Scanner",
            "File", "DirEntry", "FileInfo", "FileMode",
            "PathError", "SyscallError", "LinkError",
            "http.Client", "http.Server", "http.Request",
            "http.Response", "http.ResponseWriter",
            "http.Handler", "http.HandlerFunc",
            "http.Header", "http.Cookie",
            "json.Decoder", "json.Encoder",
            "json.Marshaler", "json.Unmarshaler",
            "json.RawMessage", "json.Token",
            "context.Context", "context.CancelFunc",
            "sync.Mutex", "sync.RWMutex", "sync.WaitGroup",
            "sync.Once", "sync.Cond", "sync.Pool",
            "sync.Map", "sync.Locker",
            "atomic.Value", "atomic.Int32", "atomic.Int64",
            "net.Conn", "net.Listener", "net.Addr",
            "net.TCPConn", "net.TCPAddr", "net.UDPConn",
            "net.UDPAddr", "net.IP", "net.IPAddr",
            "io.EOF", "io.ErrUnexpectedEOF",
            "io.ErrShortWrite", "io.ErrClosedPipe",
            "os.File", "os.DirEntry", "os.FileInfo",
            "os.FileMode", "os.Signal",
            "time.Time", "time.Duration", "time.Location",
            "time.Ticker", "time.Timer", "time.Month", "time.Weekday"
        ),
        builtins = setOf(
            "print", "println", "panic", "recover", "new", "make",
            "append", "copy", "delete", "close", "len", "cap",
            "complex", "real", "imag",
            "min", "max", "clear",
            "errorf", "fmt.Errorf",
            "fmt.Print", "fmt.Println", "fmt.Printf",
            "fmt.Sprintf", "fmt.Fprint", "fmt.Fprintln",
            "fmt.Fprintf", "fmt.Sprint", "fmt.Sprintln",
            "fmt.Sscanf", "fmt.Fscanf", "fmt.Scan",
            "fmt.Scanf", "fmt.Scanln",
            "io.Copy", "io.CopyN", "io.ReadAll", "io.ReadFull",
            "io.WriteString", "io.MultiReader", "io.MultiWriter",
            "io.Pipe", "io.LimitReader", "io.TeeReader",
            "io.NopCloser", "io.Discard",
            "os.Open", "os.Create", "os.OpenFile",
            "os.ReadFile", "os.WriteFile", "os.ReadDir",
            "os.Mkdir", "os.MkdirAll", "os.Remove",
            "os.RemoveAll", "os.Rename", "os.Stat", "os.Chdir",
            "os.Getenv", "os.Setenv", "os.Getwd",
            "os.Exit", "os.Getpid", "os.Hostname",
            "os.Args", "os.Stdin", "os.Stdout", "os.Stderr",
            "strings.Split", "strings.Join", "strings.Replace",
            "strings.ReplaceAll", "strings.Trim", "strings.Fields",
            "strings.Contains", "strings.HasPrefix", "strings.HasSuffix",
            "strings.Index", "strings.LastIndex",
            "strings.ToLower", "strings.ToUpper",
            "strings.Builder", "strings.NewReader",
            "strconv.Atoi", "strconv.Itoa", "strconv.ParseInt",
            "strconv.FormatInt", "strconv.ParseFloat",
            "strconv.FormatFloat", "strconv.ParseBool",
            "strconv.FormatBool", "strconv.Quote",
            "json.Marshal", "json.Unmarshal",
            "json.NewEncoder", "json.NewDecoder",
            "http.Get", "http.Post", "http.Handle",
            "http.HandleFunc", "http.ListenAndServe",
            "http.NewRequest", "http.NewServeMux",
            "time.Now", "time.Since", "time.Until",
            "time.Parse", "time.Sleep", "time.NewTicker",
            "time.After", "time.AfterFunc", "time.NewTimer",
            "context.Background", "context.TODO",
            "context.WithCancel", "context.WithDeadline",
            "context.WithTimeout", "context.WithValue",
            "sync.WaitGroup.Add", "sync.WaitGroup.Done",
            "sync.WaitGroup.Wait", "sync.Mutex.Lock",
            "sync.Mutex.Unlock", "sync.RWMutex.RLock",
            "sync.RWMutex.RUnlock", "sync.Once.Do",
            "atomic.AddInt32", "atomic.LoadInt32",
            "atomic.StoreInt32", "atomic.SwapInt32"
        ),
        booleanLiterals = setOf("true", "false", "nil", "iota"),
        constants = setOf("true", "false", "iota", "nil"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'", "`"),
        multiStringDelimiters = emptyList(),
        numericSuffixes = emptyList()
    )

    val Rust = LanguageDef(
        name = "Rust",
        keywords = setOf(
            "as", "async", "await", "break", "const", "continue",
            "crate", "dyn", "else", "enum", "extern", "false",
            "fn", "for", "if", "impl", "in", "let",
            "loop", "match", "mod", "move", "mut", "pub",
            "ref", "return", "self", "Self", "static", "struct",
            "super", "trait", "true", "type", "union", "unsafe",
            "use", "where", "while", "abstract", "become", "box",
            "do", "final", "macro", "override", "priv", "try",
            "typeof", "unsized", "virtual", "yield",
            "default", "pure", "sizeof", "alignof", "offsetof",
            "raw", "const", "mut",
            "in", "out", "ref",
            "ident", "self", "Self", "super", "crate",
            "macro_rules", "derive", "auto", "default"
        ),
        types = setOf(
            "i8", "i16", "i32", "i64", "i128", "isize",
            "u8", "u16", "u32", "u64", "u128", "usize",
            "f32", "f64", "bool", "char", "String", "str",
            "Vec", "Option", "Result", "Box", "Rc", "Arc",
            "Cell", "RefCell", "Ref", "RefMut",
            "Mutex", "RwLock",
            "HashMap", "HashSet", "BTreeMap", "BTreeSet",
            "LinkedList", "VecDeque", "BinaryHeap",
            "Range", "RangeFrom", "RangeTo", "RangeFull",
            "RangeInclusive", "RangeToInclusive",
            "Iterator", "IntoIterator", "FromIterator",
            "DoubleEndedIterator", "ExactSizeIterator",
            "Fn", "FnMut", "FnOnce", "Clone", "Copy",
            "Debug", "Display", "Eq", "PartialEq",
            "Ord", "PartialOrd", "Hash", "Default",
            "Into", "From", "TryInto", "TryFrom",
            "AsRef", "AsMut", "Deref", "DerefMut",
            "Drop", "Send", "Sync", "Sized",
            "Unpin", "Unsize", "CoerceUnsized",
            "Error", "ToString",
            "Path", "PathBuf", "OsStr", "OsString",
            "CStr", "CString",
            "Duration", "SystemTime", "Instant",
            "IpAddr", "Ipv4Addr", "Ipv6Addr", "SocketAddr",
            "PhantomData", "ManuallyDrop", "MaybeUninit",
            "NonNull", "UnsafeCell", "Pin",
            "dyn", "impl"
        ),
        builtins = setOf(
            "println", "print", "eprintln", "eprint",
            "format", "write", "writeln",
            "panic", "assert", "assert_eq", "assert_ne",
            "debug_assert", "debug_assert_eq", "debug_assert_ne",
            "unreachable", "unimplemented", "todo",
            "dbg", "compile_error",
            "include_str", "include_bytes", "include!",
            "file!", "line!", "column!", "stringify!",
            "concat!", "env!", "option_env!", "cfg!",
            "cfg_attr", "concat_idents",
            "module_path!", "matches!",
            "vec!", "format_args!",
            "write!", "writeln!",
            "eprint!", "eprintln!",
            "print!", "println!", "format!",
            "panic!", "assert!", "assert_eq!", "assert_ne!",
            "debug_assert!", "debug_assert_eq!", "debug_assert_ne!",
            "unreachable!", "unimplemented!", "todo!",
            "dbg!", "compile_error!",
            "include_str!", "include_bytes!", "include!",
            "stringify!", "concat!", "env!", "option_env!",
            "cfg!", "cfg_attr!", "concat_idents!",
            "module_path!", "matches!",
            "vec!", "format_args!",
            "write!", "writeln!",
            "eprint!", "eprintln!",
            "print!", "println!", "format!",
            "String::new", "String::from", "Vec::new",
            "vec", "vec!",
            "Some", "None", "Ok", "Err",
            "Box::new", "Rc::new", "Arc::new",
            "Cell::new", "RefCell::new",
            "Mutex::new", "RwLock::new",
            "HashMap::new", "HashSet::new",
            "BTreeMap::new", "BTreeSet::new",
            "BinaryHeap::new", "VecDeque::new",
            "Iterator::collect", "Iterator::map",
            "Iterator::filter", "Iterator::for_each",
            "Iterator::fold", "Iterator::reduce",
            "Iterator::sum", "Iterator::product",
            "Iterator::count", "Iterator::last",
            "Iterator::nth", "Iterator::skip", "Iterator::take",
            "Iterator::chain", "Iterator::zip",
            "Iterator::enumerate", "Iterator::peekable",
            "Iterator::all", "Iterator::any", "Iterator::find",
            "Iterator::position", "Iterator::max", "Iterator::min",
            "Iterator::cloned", "Iterator::copied",
            "Iterator::flatten", "Iterator::flat_map",
            "Iterator::filter_map", "Iterator::scan",
            "Iterator::skip_while", "Iterator::take_while",
            "Iterator::inspect", "Iterator::cycle",
            "Iterator::partition",
            "std::mem::replace", "std::mem::swap",
            "std::mem::take", "std::mem::drop",
            "std::mem::forget", "std::mem::size_of",
            "std::mem::size_of_val", "std::mem::align_of",
            "std::mem::align_of_val", "std::mem::needs_drop",
            "std::mem::transmute",
            "std::thread::spawn", "std::thread::sleep",
            "std::thread::yield_now", "std::thread::current"
        ),
        booleanLiterals = setOf("true", "false"),
        constants = setOf("true", "false",
            "std::u8::MAX", "std::u8::MIN", "std::i8::MAX", "std::i8::MIN",
            "std::u16::MAX", "std::i16::MAX", "std::u32::MAX", "std::i32::MAX",
            "std::u64::MAX", "std::i64::MAX", "std::u128::MAX", "std::i128::MAX",
            "std::usize::MAX", "std::isize::MAX",
            "std::f32::MAX", "std::f32::MIN", "std::f32::INFINITY",
            "std::f32::NEG_INFINITY", "std::f32::NAN",
            "std::f64::MAX", "std::f64::MIN", "std::f64::INFINITY",
            "std::f64::NEG_INFINITY", "std::f64::NAN"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'"),
        multiStringDelimiters = emptyList(),
        hasAnnotations = true,
        numericSuffixes = listOf("i8", "i16", "i32", "i64", "i128",
            "isize", "u8", "u16", "u32", "u64", "u128", "usize",
            "f32", "f64", "usize", "isize")
    )

    val Swift = LanguageDef(
        name = "Swift",
        keywords = setOf(
            "associatedtype", "async", "await", "as", "break", "case",
            "catch", "class", "continue", "default", "defer", "deinit",
            "do", "else", "enum", "extension", "fallthrough", "false",
            "fileprivate", "for", "func", "get", "guard", "if",
            "import", "in", "indirect", "infix", "init", "inout",
            "internal", "is", "lazy", "let", "macro", "mutating",
            "nil", "nonmutating", "open", "operator", "optional",
            "override", "package", "postfix", "precedencegroup",
            "prefix", "private", "protocol", "public", "repeat",
            "required", "rethrows", "return", "self", "Self",
            "set", "some", "static", "struct", "subscript", "super",
            "switch", "throw", "throws", "true", "try", "typealias",
            "unowned", "var", "where", "while", "willSet", "didSet",
            "_borrowing", "_consuming", "any", "borrowing", "consuming",
            "distributed", "isolated", "nonisolated", "sending",
            "transferring"
        ),
        types = setOf(
            "Int", "Int8", "Int16", "Int32", "Int64",
            "UInt", "UInt8", "UInt16", "UInt32", "UInt64",
            "Float", "Float16", "Float32", "Float64",
            "Double", "CGFloat",
            "Bool", "String", "Character", "Substring",
            "Data", "Date", "DateInterval",
            "URL", "URLRequest", "URLResponse", "URLError",
            "UUID", "IndexPath", "IndexSet",
            "Range", "ClosedRange", "PartialRangeUpTo",
            "PartialRangeThrough", "PartialRangeFrom",
            "Optional", "Array", "Dictionary", "Set",
            "Any", "AnyObject", "AnyHashable",
            "Never", "Void",
            "Error", "LocalizedError", "CustomStringConvertible",
            "CustomDebugStringConvertible", "Hashable",
            "Equatable", "Comparable", "Identifiable",
            "Codable", "Encodable", "Decodable",
            "CaseIterable", "RawRepresentable",
            "Sequence", "Collection", "MutableCollection",
            "BidirectionalCollection", "RandomAccessCollection",
            "RangeReplaceableCollection", "LazySequenceProtocol",
            "IteratorProtocol", "Sequence", "Collection",
            "StringProtocol", "TextOutputStream",
            "TextOutputStreamable",
            "NSError", "NSObject", "NSException",
            "Thread", "RunLoop", "Timer",
            "Notification", "NotificationCenter",
            "Operation", "OperationQueue",
            "Bundle", "ProcessInfo", "FileManager",
            "UserDefaults", "Calendar", "Locale", "TimeZone",
            "JSONEncoder", "JSONDecoder",
            "PropertyListEncoder", "PropertyListDecoder",
            "URLSession", "URLSessionTask", "URLSessionDataTask",
            "URLSessionDownloadTask", "URLSessionUploadTask",
            "HTTPURLResponse", "URLResponse",
            "Task", "TaskPriority", "TaskGroup"
        ),
        builtins = setOf(
            "print", "debugPrint", "dump",
            "fatalError", "precondition", "preconditionFailure",
            "assert", "assertionFailure",
            "abs", "min", "max", "stride",
            "sequence", "zip",
            "type", "sizeof", "toNative",
            "unsafeBitCast", "unsafeDowncast",
            "withUnsafePointer", "withUnsafeMutablePointer",
            "withExtendedLifetime", "withoutActuallyEscaping",
            "numericCast", "bitPattern",
            "debugOnly", "inline",
            "isKnownUniquelyReferenced",
            "autoreleasepool",
            "KeyPath", "WritableKeyPath",
            "ReferenceWritableKeyPath", "PartialKeyPath",
            "map", "flatMap", "compactMap", "filter",
            "reduce", "forEach", "sorted", "sorted",
            "first", "last", "prefix", "suffix",
            "dropFirst", "dropLast", "split",
            "contains", "allSatisfy", "firstIndex",
            "firstIndex", "min", "max",
            "enumerated", "joined", "joined",
            "elementsEqual", "starts",
            "zip", "sequence",
            "compactMap", "flatMap"
        ),
        booleanLiterals = setOf("true", "false", "nil"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        multiStringDelimiters = listOf("\"\"\""),
        numericSuffixes = listOf("u", "U", "f", "F", "i", "I")
    )

    val Dart = LanguageDef(
        name = "Dart",
        keywords = setOf(
            "abstract", "as", "assert", "async", "await", "break",
            "case", "catch", "class", "const", "continue", "covariant",
            "default", "deferred", "do", "dynamic", "else", "enum",
            "export", "extends", "extension", "external", "factory",
            "false", "final", "finally", "for", "function", "get",
            "hide", "if", "implements", "import", "in", "interface",
            "is", "late", "library", "mixin", "new", "null", "on",
            "operator", "part", "required", "rethrow", "return",
            "set", "show", "static", "super", "switch", "sync",
            "this", "throw", "true", "try", "typedef", "var",
            "void", "while", "with", "yield",
            "sealed", "base", "final", "interface", "mixin",
            "record", "when", "inline"
        ),
        types = setOf(
            "int", "double", "num", "bool", "String", "Symbol",
            "Object", "dynamic", "void", "Never", "Null",
            "List", "Set", "Map", "Queue", "Record",
            "Iterable", "Iterator",
            "Comparable", "num",
            "Function", "Rune", "Runes",
            "Future", "FutureOr", "Stream",
            "StreamController", "StreamSubscription",
            "Completer", "Timer",
            "Duration", "DateTime", "Stopwatch",
            "Uri", "UriData",
            "RegExp", "Match", "Pattern",
            "Type", "StackTrace",
            "Error", "Exception", "FormatException",
            "ArgumentError", "RangeError", "IndexError",
            "NoSuchMethodError", "UnsupportedError",
            "UnimplementedError", "StateError",
            "ConcurrentModificationError",
            "IntegerDivisionByZeroException",
            "File", "Directory", "FileSystemEntity",
            "FileSystemException",
            "HttpClient", "HttpClientRequest", "HttpClientResponse",
            "HttpRequest", "HttpResponse",
            "Process", "ProcessResult",
            "InternetAddress", "RawSocket",
            "Socket", "ServerSocket",
            "SecurityContext", "SecureSocket",
            "Random", "SecureRandom",
            "StringBuffer", "StringSink",
            "StringBuilder",
            "Encoding", "Utf8", "Ascii", "Latin1",
            "Base64", "Base64Codec", "Base64Encoder", "Base64Decoder",
            "JsonCodec", "JsonEncoder", "JsonDecoder",
            "Convert", "Codec", "Encoder", "Decoder"
        ),
        builtins = setOf(
            "print", "debugPrint", "println",
            "identical", "identityHashCode",
            "identical",
            "assert", "throw",
            "Future", "Future.value", "Future.error",
            "Future.delayed", "Future.sync", "Future.microtask",
            "Stream.periodic", "Stream.fromIterable",
            "Stream.empty", "Stream.error",
            "Timer.run", "Timer.periodic",
            "DateTime.now", "DateTime.utc",
            "Duration", "Duration.seconds", "Duration.minutes",
            "Duration.hours", "Duration.milliseconds",
            "Duration.microseconds", "Duration.days",
            "RegExp", "RegExp.allMatches",
            "RegExp.firstMatch", "RegExp.hasMatch",
            "Uri.parse", "Uri.https", "Uri.http",
            "Uri.data", "Uri.file",
            "Object.runtimeType", "Object.hashCode",
            "Object.toString", "Object.noSuchMethod",
            "Iterable.map", "Iterable.where",
            "Iterable.fold", "Iterable.reduce",
            "Iterable.forEach", "Iterable.toList",
            "Iterable.toSet", "Iterable.join",
            "Iterable.isEmpty", "Iterable.isNotEmpty",
            "Iterable.first", "Iterable.last",
            "Iterable.length", "Iterable.contains",
            "Iterable.elementAt", "Iterable.single",
            "Iterable.skip", "Iterable.take",
            "Iterable.expand", "Iterable.cast",
            "Iterable.any", "Iterable.every",
            "Iterable.firstWhere", "Iterable.lastWhere",
            "Iterable.singleWhere",
            "Iterable.whereType",
            "Iterable.followedBy",
            "List.generate", "List.filled", "List.of",
            "List.from", "List.unmodifiable",
            "List.empty",
            "Map.fromIterable",
            "Set.from", "Set.of",
            "int.parse", "double.parse",
            "num.parse", "int.tryParse",
            "double.tryParse",
            "DateTime.parse", "DateTime.tryParse",
            "Uri.parse", "Uri.tryParse",
            "jsonDecode", "jsonEncode",
            "base64Decode", "base64Encode",
            "base64UrlDecode", "base64UrlEncode",
            "utf8.decode", "utf8.encode",
            "File", "File.readAsString", "File.readAsBytes",
            "File.writeAsString", "File.writeAsBytes",
            "Directory.current", "Directory.createTemp",
            "Directory.list",
            "Platform.isAndroid", "Platform.isIOS",
            "Platform.operatingSystem", "Platform.version"
        ),
        booleanLiterals = setOf("true", "false", "null"),
        constants = setOf("Pi", "e"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf("\"", "'"),
        multiStringDelimiters = listOf("\"\"\"", "'''"),
        hasAnnotations = true,
        numericSuffixes = emptyList()
    )

    // ── Special filenames ───────────────────────────────────────────────

    val Dockerfile = LanguageDef(
        name = "Dockerfile",
        keywords = setOf(
            "FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE",
            "ENV", "ADD", "COPY", "ENTRYPOINT", "VOLUME", "USER",
            "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK",
            "SHELL", "DOCKER", "BUILD", "PULL",
            "AS", "TARGET", "PLATFORM"
        ).map { it.lowercase() }.toSet() + setOf(
            "from", "run", "cmd", "label", "maintainer", "expose",
            "env", "add", "copy", "entrypoint", "volume", "user",
            "workdir", "arg", "onbuild", "stopsignal", "healthcheck",
            "shell"
        ),
        booleanLiterals = setOf("true", "false"),
        lineComments = listOf("#"),
        stringDelimiters = listOf("\"", "'"),
        variablePrefix = '$'
    )

    // ── All languages map ───────────────────────────────────────────────

    private val ALL_LANGUAGES: Map<String, LanguageDef> = buildMap {
        fun putExt(lang: LanguageDef, vararg exts: String) {
            for (ext in exts) put(ext, lang)
        }
        putExt(JSON,           "json")
        putExt(YAML,           "yaml", "yml")
        putExt(TOML,           "toml")
        putExt(Properties,     "properties", "ini", "cfg", "conf")
        putExt(XML,            "xml", "xsd", "xsl", "xslt", "svg", "plist", "xhtml")
        putExt(HTML,           "html", "htm", "xhtml")
        putExt(CSS,            "css", "scss", "less", "sass")
        putExt(JavaScript,     "js", "mjs", "cjs", "jsx")
        putExt(TypeScript,     "ts", "tsx")
        putExt(Python,         "py", "pyw", "pyx", "pxd", "pyi")
        putExt(Kotlin,         "kt", "kts", "ktm")
        putExt(Java,           "java", "jav", "jsh")
        putExt(C,              "c", "h")
        putExt(CPP,            "cpp", "hpp", "cc", "hh", "cxx", "hxx", "c++", "h++", "cp", "cx")
        putExt(CSharp,         "cs", "csx")
        putExt(SQL,            "sql", "ddl", "dml", "pks", "pkb", "fnc", "prc", "trg", "vw")
        putExt(Shell,          "sh", "bash", "zsh", "ksh", "csh", "tcsh")
        putExt(Markdown,       "md", "markdown", "mdown", "mdwn")
        putExt(Gradle,         "gradle")
        putExt(Ruby,           "rb", "erb", "rhtml", "rxml", "rjs", "rake", "gemspec")
        putExt(PHP,            "php", "phtml", "php3", "php4", "php5", "php7", "phps", "phpt")
        putExt(Go,             "go")
        putExt(Rust,           "rs", "rlib")
        putExt(Lua,            "lua", "wlua")
        putExt(Perl,           "pl", "pm", "t", "pod")
        putExt(Swift,          "swift", "swiftmodule")
        putExt(Dart,           "dart")
        putExt(Dockerfile,     "dockerfile", "containerfile")
        putExt(M3U,            "m3u", "m3u8")
    }.also { map ->
        // Add lowercase-only aliases for all registered extensions
        // (map is immutable after .also {} — this is informational)
    }

    /** Maps extension → LanguageDef, with lowercase-only keys. */
    private val EXTENSION_MAP: Map<String, LanguageDef> = ALL_LANGUAGES

    /** Special filenames (no extension matching) → LanguageDef. */
    private val FILENAME_MAP: Map<String, LanguageDef> = buildMap {
        put("dockerfile", Dockerfile)
        put("containerfile", Dockerfile)
    }

    /**
     * Extensionless dot-config dotfiles that use `KEY=VALUE` syntax
     * (e.g. `.env`, `.npmrc`, `.pylintrc`) → [Env] for dotenv highlighting.
     * Keys are lowercased names with the leading dot stripped.
     */
    private val DOTENV_DOTFILES = setOf(
        "env", "npmrc", "pylintrc", "curlrc", "wgetrc",
        "gemrc", "s3cfg", "editorconfig"
    )

    /**
     * Detects the programming language for [fileName] by checking its
     * extension first, then falling back to an exact filename match, then to
     * dot-config dotfiles (e.g. `.env`, `.npmrc`).
     *
     * @return The matching [LanguageDef], or `null` for unknown/plain-text files.
     */
    fun detect(fileName: String): LanguageDef? {
        val lower = fileName.lowercase()
        // First try extension
        val dot = lower.lastIndexOf('.')
        if (dot >= 0) {
            val ext = lower.substring(dot + 1)
            EXTENSION_MAP[ext]?.let { return it }
        }
        // Fall back to exact filename match (e.g. "Dockerfile")
        FILENAME_MAP[lower]?.let { return it }
        // Dot-config dotfiles (e.g. .env, .env.local, .npmrc)
        if (lower.startsWith(".")) {
            val core = lower.substring(1)
            if (core.startsWith("env.") || core in DOTENV_DOTFILES) {
                return Env
            }
        }
        return null
    }

    /**
     * Returns true if [extension] is a recognised code-file extension.
     * Useful for fast pre-checks before instantiating a full [LanguageDef].
     */
    fun isCodeFile(extension: String): Boolean =
        extension.lowercase() in EXTENSION_MAP
}
