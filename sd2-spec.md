# SD2 Language Specification v0.8

## 1. Overview

SD2 (Structured Data Description Language) is a context-free, declarative language for describing structured data and domain-specific languages. It serves as a meta-format like XML, JSON, and YAML.

Design Goals:
- Context-free parsing without schema information
- Human-readable with explicit line continuation
- Support for generic types and qualified names
- Extensible through domain-specific keywords
- Clean embedding of foreign code blocks
- Minimal reserved words (only `true`, `false`, `null`)

## 2. Lexical Structure

### 2.1 Character Set and Line Endings

- UTF-8 encoding (mandatory, BOM ignored if present)
- Line endings normalized: `\n`, `\r\n`, `\r` → `NEWLINE` token
- NEWLINE is a significant token: The lexer does not discard it (except inside multiline strings/foreign code where it's part of content)
- NEWLINE is not considered generic whitespace for parsing purposes
- Trailing whitespace before NEWLINE ignored

### 2.2 Comments

```sd2
// Line comment until NEWLINE
/* Block comment, may span lines */
```

Comment placement:
- Comments do not break the requirement for NEWLINE after attributes
- Comments between tokens are treated as whitespace (except where NEWLINE is required)
- Comments are valid in qualifier lines: `| with A, B // comment is allowed`

### 2.3 Identifiers

- Simple identifier: `[A-Za-z_][A-Za-z0-9_-]*`
- Backtick identifier: `` `content` `` — any characters except backtick and NEWLINE; no escape mechanism; preserves spaces
- Qualified name: `name_part ('.' name_part)*` where each part is simple or backtick

Examples:
```sd2
server-01                   // simple
`complex name`              // backtick
com.example.Service         // qualified
`my company`.auth.Service   // mixed qualified
```

### 2.4 Reserved Words and Literals

Reserved words: `true`, `false`, `null` (cannot be used as simple identifiers).
To use them as names, use backticks (except where backticks are prohibited, see below):
```sd2
field `null` : String
```

Backtick restrictions:
- Element keywords must be simple identifiers (no backticks)
- Qualifier names must be simple identifiers (no backticks)
- Namespace names (after `.`) must be simple identifiers (no backticks)

Literals:
- Integer: `[+-]?[0-9]+(_[0-9]+)*` | `0x[0-9A-Fa-f]+(_[0-9A-Fa-f]+)*` | `0b[01]+(_[01]+)*`
  - `+`/`-` prefixes are not allowed with `0x` and `0b`
  - Underscores `_` allowed as digit separators
- Float: `[+-]?[0-9]+(_[0-9]+)*\.[0-9]+(_[0-9]+)*([eE][+-]?[0-9]+(_[0-9]+)*)?` | `[+-]?[0-9]+(_[0-9]+)*[eE][+-]?[0-9]+(_[0-9]+)*`
- Boolean: `true` | `false`
- Null: `null`
- String: `"..."` with escapes: `\"`, `\\`, `\n`, `\t`, `\r`, `\u{HEX+}`

### 2.5 Foreign Code Constructors

Foreign code blocks may be optionally prefixed by a constructor identifier immediately followed by `@` (no whitespace). The identifier can be a simple identifier, a qualified name, or a backtick identifier. Reserved words (`true`, `false`, `null`) cannot be used as constructors.

Examples:
```sd2
script = sh@'echo ok'
query = db.postgresql@"SELECT 1"
legacy = `custom-shell`@'do stuff'
```

Errors:
- E4003 — Whitespace not allowed between constructor and '@'
- E4004 — Reserved word cannot be used as constructor

## 3. Elements

### 3.1 Element Structure

```
[annotations] keyword [identifier] [: type] [qualifiers] [body]
```

Components:
- keyword: Domain-specific element type (interpreted by position). Must be a simple identifier (no backticks)
- identifier: Optional instance name (cannot be `true`, `false`, or `null`)
- type: Optional type declaration with generics
- qualifiers: Behavioral modifiers (inline or continued)
- body: Optional `{ ... }` containing attributes and sub-elements

Positional interpretation:
```sd2
server server : Server     // keyword="server", id="server", type="Server"
task : Task                // keyword="task", no id, type="Task"
database                   // keyword="database", no id, no type
```

Standalone elements:
```sd2
database
server api
task : Task
```

### 3.2 Type Declarations

```sd2
field items : List<String>
repository users : BaseRepository<User, UUID>
cache data : Map<String, List<Permission>>
service api : com.example.RestService<Request, Response>
```

### 3.3 Qualifiers

Common patterns:
- `extends Parent`
- `with Mixin1, Mixin2`
- `implements Interface1, Interface2`
- `when Condition`

Rules:
- All qualifiers must have arguments (qualified names or lists)
- Flag-style qualifiers without arguments are not permitted

```sd2
service auth : AuthService implements auth.OAuth2Provider, security.Auditable
// Invalid: qualifier without args
field email : String unique   // E2101
```

Line continuation for qualifiers (`|` in column 1):
```sd2
server api : LoadBalancer
| extends base.servers.SecureServer
| with monitoring.Health, monitoring.Metrics
| implements scaling.AutoScalable {
    port = 8080
}
```

### 3.4 Line Continuation Rules

- `|` must appear immediately after NEWLINE (column 1)
- Only valid for continuing element qualifiers
- Cannot appear between qualifier and its arguments
- Cannot be used for keywords, identifiers, or type declarations
- Body `{` must be on same line as last qualifier/header
- Misuse produces E1002/E1004

## 4. Values

### 4.1 Primitives

Numbers:
```sd2
count = 42
price = 19.99
large = 1_000_000
hex = 0xFF_00_AA
binary = 0b1010_1100
scientific = 1.5e-10
negative = -42
positive = +42
```

Booleans and Null:
```sd2
enabled = true
debug = false
middleName = null
```

Strings:
```sd2
name = "My Application"
path = "C:\\Program Files\\App"

template = """
    <html>
        <body>{{content}}</body>
    </html>
"""

description = """
    This is a long description that \\
    continues without newline \\
    between these lines.
"""
```

### 4.2 Collections

Lists:
```sd2
ports = [8080, 8443, 9090]
environments = ["dev", "staging", "prod"]
mixed = [42, "text", true, null]
nested = [[1, 2], [3, 4]]
empty = []
trailing = [1, 2, 3,]
```

Maps:
```sd2
config = {
    host = "localhost",
    port = 5432,
    ssl = true,
    password = null
}

responses = {
    success = "OK",
    [200] = "Success",
    [404] = "Not Found",
    ["Content-Type"] = "application/json",
    ["null"] = "String key 'null'",
    [true] = "Boolean key",
    [null] = "Null key"
}

empty = {}
trailing = {key = "value",}
```

Map key guidelines:
- Avoid `null` as a primitive key unless domain requires it
- Use `["null"]` for a string key containing "null"
- Reserved words as keys must use bracket notation

### 4.3 Tabular Arrays (Compact Rows)

Tabular arrays are syntax sugar for lists of uniform rows. They come in three forms and always desugar to existing SD2 values (lists, maps, and constructors).

#### 4.3.1 Ad-hoc Maps

```sd2
codedValues = {(name, code)} [
    ("Not informed", 0),
    ("Active", 1),
    ("Inactive", 2),
]
```

Desugars to:
```sd2
codedValues = [
    {name = "Not informed", code = 0},
    {name = "Active",       code = 1},
    {name = "Inactive",     code = 2},
]
```

Schema rules (E8001 — invalid tabular map schema):
- Field list must contain only simple identifiers (no backticks)
- At least one field is required
- No duplicate field names

#### 4.3.2 Typed Positional (Tuple-Constructor)

```sd2
points = Point(_, _) [
    (10, 20),
    (30, 40),
]
```

Desugars to:
```sd2
points = [
    Point(10, 20),
    Point(30, 40),
]
```

Schema rules (E8002 — invalid tabular positional schema):
- Constructor name must be a valid qualified name
- All schema parameters must be `_` (underscore placeholders)
- At least one placeholder is required

#### 4.3.3 Typed Named (Map-Constructor)

```sd2
codedValues = CodedValue {(name, code)} [
    ("Not informed", 0),
    ("Active", 1),
]
```

Desugars to:
```sd2
codedValues = [
    CodedValue {name = "Not informed", code = 0},
    CodedValue {name = "Active",       code = 1},
]
```

Schema rules (E8003 — invalid tabular named schema):
- Constructor name must be a valid qualified name
- Field list must contain only simple identifiers (no backticks)
- At least one field is required
- No duplicate field names

#### 4.3.4 Rows and Layout

Rows are always tuples:
```sd2
values = {(name, code)} [
    ("A", 1),
    ("B", 2),
]
```

Row rules:
- Each row must be a tuple (E8005)
- The number of values in each row must match the schema arity (E8004)
- Trailing commas are allowed between rows and inside tuples
- Empty arrays are allowed: `{(name, code)} []`, `Point(_, _) []`, `CodedValue {(name, code)} []`

Layout rule:
- The opening `[` of a tabular array must be on the same physical line as the schema; placing `[` on the next line is an error (E1006)

Semantics:
- Tabular arrays are pure syntax sugar; implementations treat them as if they had been rewritten to lists plus maps/constructors as shown above.

### 4.4 Tuples and Constructors

- Tuple literal: `(v1, v2, ..., vn)` with `n ≥ 0`
  - Single-element tuples can be written as `(x)`; trailing comma is optional
  - Empty tuples `()` are allowed
- Map-constructor: `QualifiedName { attribute = value ... }` (named arguments)
- Tuple-constructor: `QualifiedName(v1, v2, ..., vn)` (positional arguments)

Same-line requirements:
- `{` of a map-constructor must be on the same line as its name
- `(` of a tuple-constructor must be on the same line as its name

Examples:
```sd2
center = (-25.43, -49.27)
one = (42)
retry = policy { attempts = 3 }
cache = storage.cache.Redis { host = "localhost" }
point = Point(10, 20)
color = RGB(255, 128, 0)
createdAt = instant("2024-03-15T14:30:00Z")
```

Constructor bodies (map-constructors) are attribute lists only; namespaces and sub-elements are not permitted within constructors.

### 4.5 Foreign Code

```sd2
regex = @'^\d{4}-\d{2}-\d{2}$'
sql = @"SELECT * FROM users WHERE id = ?"
json = @[{"key": "value"}]
script = @{console.log('hello');}

template = @"""
    <div class="container">
        {{content}}
    </div>
"""

code = @'''
    function process(data) {
        return data.filter(x => x.active);
    }
'''

query = @[[[
    SELECT u.name, p.title 
    FROM users u 
    JOIN posts p ON u.id = p.author_id
]]]

config = @{{{
    {
        "database": {
            "host": "localhost",
            "port": 5432
        }
    }
}}}
```

Rules:
- Choose delimiters that don't conflict with content
- No escape mechanism; use triple form if needed
- Content inside delimiters is preserved exactly (including newlines in triple form)
- Single-line forms cannot contain their delimiter character

#### Constructor Form

Foreign code blocks can optionally be prefixed with a constructor identifier with no whitespace before '@'. This explicitly specifies the type/interpretation of the foreign content.

Examples:
```sd2
script = sh@'echo "Hello"'
code = python@"print('Hello')"
query = sql@"SELECT * FROM users"

setup = scripts.bash@'./init.sh'
validate = lang.python3@"validate()"
migrate = db.postgresql@"ALTER TABLE ..."

legacy = `my-shell`@'custom commands'
```

Constructor rules:
- No whitespace between constructor and '@' (E4003)
- Constructor can be simple, qualified, or backtick identifier
- Reserved words cannot be used as constructors (E4004)
- Interpretation is domain-specific and determined by schema/context

### 4.6 Temporal Constructors

SD2 define um conjunto de construtores temporais padronizados. Implementações devem reconhecê‑los e validar formato e componentes de forma consistente.

- `date(string)` — Data de calendário, formato `YYYY-MM-DD`.
- `time(string)` — Hora do dia, formato `HH:MM:SS[.SSSSSSSSS]` (fração opcional até 9 dígitos).
- `instant(string)` — Ponto absoluto no tempo, formato `YYYY-MM-DD'T'HH:MM:SS[.SSSSSSSSS](Z|±HH:MM)` (offset obrigatório).
- `duration(string)` — Duração absoluta (ISO‑8601 restrita): `P[nD][T[nH][nM][n(.f)S]]`.
  - Apenas D/H/M/S; `D` = 24 horas (86400s); fração de segundos até 9 dígitos.
- `period(string)` — Período de calendário (ISO‑8601 restrito): `P[nY][nM][nW][nD]`.
  - Não aceita `T` nem H/M/S; `W` = 7 dias de calendário.

Regras adicionais:
- `instant`: offset é obrigatório (usar `Z` para UTC ou `±HH:MM`).
- `time`/`instant`/`duration`: frações de segundos com no máximo 9 dígitos.
- `duration`: não permite Y/M(month)/W; requer ao menos um componente (zeros são aceitos, p.ex. `PT0S`).
- `period`: não permite `T`, H/M/S; requer ao menos um componente (zeros são aceitos, p.ex. `P0D`).

Semântica de D:
- Em `duration`, `P1D` = exatamente 24 horas (86400s).
- Em `period`, `P1D` = 1 dia de calendário (pode variar com DST).

## 5. Body Structure

### 5.1 Body Contents

```sd2
element {
    // 1. Direct attributes
    name = "value"
    port = 8080

    // 2. Namespaces and sub-elements
    .config {
        setting = true
        validator defaultValidator { enabled = true }
    }

    child first { }

    .monitoring { interval = duration("PT30S") }

    child second { }
}
```

Ordering rules:
1) Direct attributes must appear before any namespaces or sub-elements
2) Namespaces and sub-elements can be interleaved after attributes
3) Within namespaces: same rules apply recursively

### 5.2 Attributes

- Form: `identifier = value`
- Each terminated by NEWLINE
- Duplicate keys in same scope result in E2001

### 5.3 Namespaces

```sd2
server api {
    port = 8080
    .security {
        ssl = true
        cert = "/path/to/cert"
        rule cors { origin = "*" }
        rule rateLimit { requests = 100, window = duration("PT1M") }
    }
    .deployment {
        .docker { image = "app:latest"; tag = "v1.0.0" }
        environment prod { replicas = 3 }
    }
}
```

Rules:
- Syntax: `.identifier { ... }` (single identifier only)
- Chained namespaces like `.a.b {}` are not supported
- Use nested blocks for hierarchy
- Namespaces create nested scopes for uniqueness rules

## 6. Annotations

### 6.1 Element Annotations

```sd2
#[deprecated(reason = "use v2")]
#[since("2.1.0")]
#[cache(ttl = 300)]
api users : RestAPI { }
```

### 6.2 Document Annotations

```sd2
##[version("0.8")]
##[plugin("org.jetbrains.compose")]
```

Placement: Document annotations (`##[...]`) are only permitted at the top of the document, before the first element.

## 7. Grammar (EBNF)

```ebnf
document        = { doc_annotation } { element } ;

element         = { annotation } keyword [ identifier ] [ type_decl ] [ element_tail ] ;

element_tail    = qualifiers_line { continuation } [ body ]
                | body ;

qualifiers_line = { qualifier } ;
continuation    = NEWLINE "|" { qualifier } ;
qualifier       = simple_ident qualifier_args ;
qualifier_args  = qualified_name | qualified_list ;
qualified_list  = qualified_name { "," qualified_name } ;

type_decl       = ":" type_expr ;
type_expr       = qualified_name [ type_params ] ;
type_params     = "<" type_expr { "," type_expr } ">" ;

identifier      = simple_ident | backtick_ident ;
simple_ident    = LETTER { LETTER | DIGIT | "_" | "-" } ;
backtick_ident  = "`" { ANY_CHAR - "`" - NEWLINE } "`" ;
qualified_name  = identifier { "." identifier } ;
keyword         = simple_ident ;

body            = "{" { body_item } "}" ;
body_item       = attribute | namespace | element ;
namespace       = "." simple_ident body ;
attribute       = identifier "=" value NEWLINE ;

value           = primitive | list | map | tuple | constructor_map | constructor_tuple |
                  tabular_array | foreign_code | qualified_name ;

primitive       = number | boolean | string | null_literal ;
null_literal    = "null" ;
number          = integer | float ;

tuple           = "(" [ value { "," value } [ "," ] ] ")" ;

list            = "[" [ value { "," value } [ "," ] ] "]" ;
map             = "{" [ map_entry { "," map_entry } [ "," ] ] "}" ;
map_entry       = map_key "=" value ;
map_key         = identifier | string | "[" primitive "]" ;

tabular_array   = tabular_schema "[" [ tabular_row { "," tabular_row } [ "," ] ] "]" ;
tabular_schema  = "{" "(" identifier { "," identifier } ")" "}"          (* ad-hoc maps *)
                | qualified_name "(" "_" { "," "_" } ")"                 (* typed positional *)
                | qualified_name "{" "(" identifier { "," identifier } ")" "}" ; (* typed named *)
tabular_row     = "(" [ value { "," value } [ "," ] ] ")" ;

constructor_map   = qualified_name body ;
constructor_tuple = qualified_name "(" [ value { "," value } [ "," ] ] ")" ;

foreign_code    = [ identifier ] "@" at_delim content at_close ;

annotation      = "#[" qualified_name [ paren_args ] "]" ;
doc_annotation  = "##[" qualified_name [ paren_args ] "]" ;
paren_args      = "(" { ANY_TOKEN - ")" } ")" ;
```

## 8. Examples

### 8.1 Elements and Namespaces

```sd2
server api {
    port = 8080
    .security {
        ssl = true
        cert = "/path/to/cert"
        rule cors { origin = "*" }
        rule rateLimit { requests = 100, window = duration("PT1M") }
    }
    .monitoring { interval = duration("PT30S") }
}
```

### 8.2 Tuples and Constructors

```sd2
config {
    center = (-25.43, -49.27)
    point = Point(10, 20)
    retry = policy { attempts = 3, backoff = "exponential" }
    color = RGB(255, 128, 0)
}
```

## 9. Errors (selected)

- E1001 — '{' of a constructor must be on the same line as its name
- E1002 — Line continuation '|' must be in column 1 immediately after NEWLINE
- E1004 — Line continuation '|' used outside qualifier context
- E1005 — '(' of a tuple-constructor must be on the same line as its name
- E1006 — '[' of a tabular array must be on the same line as its schema
- E2001 — Duplicate attribute in the same scope
- E2002 — Attribute after namespace/sub-element
- E2003 — Duplicate key in map literal
- E2004 — Duplicate element (same keyword + same identifier) in the same scope
- E5001 — Missing '>' to close type parameters
- E6002 — Newline in backtick identifier
- E7001 — Signed hex/binary integers are not allowed
- E4003 — Whitespace not allowed between constructor and '@' in foreign code
- E4004 — Reserved word cannot be used as foreign code constructor
- E3001 — Invalid temporal format
- E3002 — Empty duration/period (nenhum componente)
- E3003 — Fractional seconds exceed 9 digits
- E3004 — Invalid calendar component in duration (Y/M(month)/W)
- E3005 — Invalid time component in period (T/H/M/S)
- E8001 — Invalid tabular map schema
- E8002 — Invalid tabular positional schema
- E8003 — Invalid tabular named schema
- E8004 — Tabular row arity mismatch
- E8005 — Tabular row must be tuple
