# SD2 Format (Draft)

Status: DRAFT / alpha. The format and tooling may change.

This repository contains a Kotlin parser and small tools for the SD2 data format. The goal is a readable, minimal syntax to describe structured data.

For the full specification see `sd2-spec.md`. This README focuses on the core format with simple examples.

Basics
- A document is a sequence of elements.
- Each element has a keyword, optional identifier, and a body in braces.
- Bodies contain attributes (`name = value`).
- Newlines are significant: each attribute ends with a newline.

Quick Example
```
service api {
  host = "localhost"
  port = 8080
}
```

Elements
- Syntax: `keyword [identifier] { ... }`
- Identifiers that need escaping can be written with backticks: `` `my id` ``

Attributes
- Inside a body: `name = value` on its own line.
- Primitive values: integers, floats, booleans, null, and strings.

Values
- Numbers: `0`, `42`, `3.14`
- Booleans and null: `true`, `false`, `null`
- Strings: `"hello"` (use `\n`, `\t`, etc. as needed)
- Lists: `[1, 2, 3]` (trailing comma allowed)
- Maps: `{ key = 1, other = 2 }` (comma-separated, trailing comma allowed)
- Tuples: `()`, `(x)`, `(a, b, c)` (single-element without trailing comma is allowed)
- Qualified names: `a.b.c`

Constructors
- Map-constructor (named args): `Name { key = value }`
- Tuple-constructor (positional args): `Name(1, 2, 3)`

Temporal Constructors
- SD2 includes standard temporal constructors with validation and consistent parsing across tools and the default Kotlin reader:
  - `date("YYYY-MM-DD")` — calendar date
  - `time("HH:MM:SS[.fffffffff]")` — time of day, up to 9 fractional digits
  - `instant("YYYY-MM-DD'T'HH:MM:SS[.fffffffff](Z|±HH:MM)")` — point in time with required offset
  - `duration("P[nD][T[nH][nM][n(.f)S]]")` — absolute duration (subset of ISO‑8601)
  - `period("P[nY][nM][nW][nD]")` — calendar period (no time component)

Examples
```
created = date("2025-10-26")
wakeup  = time("07:30:00.123")
when    = instant("2025-10-26T12:00:00Z")
ttl     = duration("PT30S")
sprint  = period("P2W")
```

Validator error codes (selected)
- `E3001` invalid temporal format
- `E3002` empty duration/period (no components)
- `E3003` fractional seconds exceed 9 digits
- `E3004` invalid calendar component in duration
- `E3005` invalid time component in period

Use cases and examples

- Simple configuration
  ```
  app config {
    host = "localhost"
    port = 8080
    db = { host = "db.local", port = 5432 }
    features = ["a", "b"]
  }
  ```

- Docker Compose like
  ```
  compose app {
    version = "3.9"
    service web {
      image = "nginx:latest",
      ports = ["8080:80"]
    }
  }
  ```

- Kubernetes like
  ```
  deploy api {
    replicas = 2
    container {
      image = "example/api:1.0",
      ports = [8080]
    }
  }
  ```

- Terraform like
  ```
  resource bucket {
    versioning {
      enabled = true 
    }
    labels {
      env = "prod", team = "platform"
    }
  }
  ```

Foreign Code (simple)
- Single-line: `pattern = @'^\d+$'`
- Double-quoted: `query = @"SELECT 1"`

Notes
- Bodies and constructor delimiters (`{`, `(`) must be on the same line as the preceding name.
- Map entries must be comma-separated; lists and maps accept a trailing comma.

CLI (tools)
- Format: `./gradlew :tools:run --args 'format path/file.sd2'`
- Validate: `./gradlew :tools:run --args 'validate path/file.sd2'`
  - The validator reports temporal errors like `E3001`, `E3002`, `E3003`, `E3004`, `E3005`.

Library (Kotlin)
```
import io.github.ddsimoes.sd2.*

val input = """
service api {
  host = "localhost"
  port = 8080
  point = Point(10, 20)
}
""".trimIndent()

val r = Sd2.reader(StringSource(input))
while (true) {
  when (val e = r.next()) {
    is Sd2Event.StartElement -> println("element ${e.keyword} id=${e.id?.text}")
    is Sd2Event.Attribute    -> println("attr ${e.name.text} = ${e.value}")
    is Sd2Event.EndDocument  -> break
    else -> {}
  }
}
```

Kotlin value materialization
- By default, the reader resolves known constructors to typed objects (`Sd2Value.VObject`). Temporal constructors resolve to:
  - `temporal.date` → `kotlinx.datetime.LocalDate`
  - `temporal.time` → `kotlinx.datetime.LocalTime`
  - `temporal.instant` → `kotlin.time.Instant`
  - `temporal.duration` → `kotlin.time.Duration`
  - `temporal.period` → `kotlinx.datetime.DateTimePeriod`

Inspecting resolved values
```
when (val v = attr.value) {
  is Sd2Value.VObject -> when (v.type.toString()) {
    "temporal.date"    -> println("date = ${v.value}")
    "temporal.time"    -> println("time = ${v.value}")
    "temporal.instant" -> println("instant = ${v.value}")
    "temporal.duration"-> println("duration = ${v.value}")
    "temporal.period"  -> println("period = ${v.value}")
    else -> println("object ${v.type} = ${v.value}")
  }
  else -> println(v)
}
```

Opting out or customizing
- To keep constructors raw (no materialization), pass a config with `constructorRegistry = null`:
```
val r = Sd2.reader(source, Sd2ReaderConfig(constructorRegistry = null))
```
- To register your own constructors:
```
val b = ConstructorRegistryBuilder()
b.register("Point", "geom.Point") { call, ctx ->
  val (x, y) = call.args
  val xi = (ctx.resolve(x) as Sd2Value.VInt).value
  val yi = (ctx.resolve(y) as Sd2Value.VInt).value
  object { val X = xi; val Y = yi }
}
val r = Sd2.reader(source, Sd2ReaderConfig(constructorRegistry = b.build()))
```

License
- See `LICENSE`.

Advanced Features
- The following features are considered advanced and typically matter for extensible formats (e.g., plugin ecosystems). They are supported by the parser and tools but are not required for simple usage.

- Annotations
  - Document and element annotations add metadata to elements or the whole file.
  - Example:
    ```
    #![version("0.8")]
    
    #[deprecated]
    service api { }
    ```

- Type Declarations and Generics
  - Elements may declare a type after `:`; generic type parameters use `<>`.
  - Example:
    ```
    service api : com.example.RestService<Request, Response> {
      port = 8080
    }
    ```

- Qualifier Continuations (pipe)
  - Some formats use qualifiers after the header; long qualifier lists can be continued on the next line with `|` in column 1.
  - Example:
    ```
    service api : AppService implements auth.OAuth2Provider, logging.Structured {
      host = "localhost"
    }

    // With continuation
    service backend : AppService
    | implements auth.OAuth2Provider, logging.Structured {
      host = "backend.local"
    }
    ```

- Namespaces
  - Namespaces create nested scopes inside bodies; recommended mainly for extensible/plugin use cases.
  - Example:
    ```
    service api {
      host = "localhost"
      .security {
        ssl = true
      }
    }
    ```

- Backtick Identifiers
  - Use backticks for identifiers that include spaces or reserved words.
  - Example:
    ```
    keyword `my id` {
      `null` = "value"
    }
    ```

- Foreign Code with Constructors
  - Prefix foreign content with a constructor to indicate interpretation; no whitespace before `@`.
  - Example:
    ```
    scripts {
      health = sh@'echo ok'
      query  = sql@"SELECT 1"
    }
    ```
