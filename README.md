# SD2 (Kotlin) — SD2 Language v0.8

SD2 is a context‑free, declarative language for describing structured data and DSLs. This repository provides:
- `sd2-parser`: a Kotlin Multiplatform streaming parser for SD2 v0.8
- `tools`: a formatter and validator with a small CLI

See the full language specification in `sd2-spec.md`.

## Highlights (v0.8)
- Significant `NEWLINE`; explicit qualifier continuation with `|` in column 1
- Identifiers with backticks when needed; reserved words: `true`, `false`, `null`
- Values: numbers, booleans, null, strings, lists, maps, foreign code
- Tuples: `(a, b, c)`; single‑element as `(x,)` (empty `()` is invalid)
- Constructors:
  - Map‑constructor: `Name { key = value }`
  - Tuple‑constructor: `Name(a, b, c)` (positional)
- Same‑line rule for constructors: `{`/`(` must be on the same line as the name
- Maps: entries must be comma‑separated; trailing comma is allowed

Example
```
#![version("0.8")]

service api : com.example.RestService<Request, Response>
| implements auth.OAuth2Provider, security.Auditable {
  host = "localhost"
  port = 8080

  // Tuple
  center = (-25.43, -49.27)
  one = (42,)

  // Constructors
  timeout = duration { seconds = 30 }
  createdAt = datetime("2024-03-15T14:30:00Z")

  .security {
    ssl = true
    window = duration("PT1M")
  }
}
```

## Modules

- `sd2-parser`: Kotlin Multiplatform library exposing a streaming reader API and a light value model for materialized mode.
- `tools`: Formatter (`Sd2Formatter`) and Validator (`Sd2Validator`) with a CLI.

## Build and Test

- Run tests:
  - `./gradlew :sd2-parser:jvmTest`
  - `./gradlew :tools:test`
- Run the CLI:
  - `./gradlew :tools:run --args 'validate path/file.sd2'`
  - `./gradlew :tools:run --args 'validate --recover path/file.sd2'`
  - `./gradlew :tools:run --args 'format path/file.sd2'`
  - `./gradlew :tools:run --args 'format --in-place path/file.sd2'`

CLI usage
```
Usage:
  sd2 format <input.sd2> [output.sd2]
  sd2 validate <input.sd2>

Examples:
  sd2 format path/file.sd2
  sd2 format path/file.sd2 path/out.sd2
  sd2 format --in-place path/file.sd2
  sd2 validate path/file.sd2
  sd2 validate --recover path/file.sd2
```

## Parser API (Kotlin)

```
import io.github.ddsimoes.sd2.*

val input = """
widget Button {
  text = "Click"
  color = theme.primary
  point = Point(10, 20)
}
""".trimIndent()

val r = Sd2.reader(StringSource(input))
while (true) {
  when (val e = r.next()) {
    is Sd2Event.StartDocument -> {}
    is Sd2Event.StartElement -> println("element ${e.keyword} id=${e.id?.text}")
    is Sd2Event.Attribute -> println("attr ${e.name.text} = ${e.value}")
    is Sd2Event.EndDocument -> break
    else -> {}
  }
}
```

## Formatter and Validator (Library)

```
import io.github.ddsimoes.sd2.tools.*

val formatted = Sd2Formatter.format(input)
val issues = Sd2Validator.validate(formatted)           // first error (if any)
val allIssues = Sd2Validator.validateAll(formatted)     // collect all errors (recovery)
```

## Errors (selected)
- `E1001` — `{` of a constructor must be on the same line as its name
- `E1002` — Line continuation `|` must be in column 1 after NEWLINE
- `E1004` — Line continuation `|` used outside qualifier context
- `E1005` — `(` of a tuple‑constructor must be on the same line as its name
- `E1011` — Parentheses without a comma do not form a tuple; use `(x,)`
- `E2001` — Duplicate attribute in the same scope
- `E2002` — Attribute after namespace/sub‑element
- `E2003` — Duplicate key in map literal
- `E2004` — Duplicate element (same keyword + same identifier)
- `E5001` — Missing `>` to close type parameters
- `E6002` — Newline in backtick identifier
- `E7001` — Signed hex/binary integers are not allowed

Full details in `sd2-spec.md`.

## License

This project is licensed under the terms of the `LICENSE` file.

## Contributing

Issues and PRs are welcome. Please align with the spec (`sd2-spec.md`), keep changes minimal and focused, and include tests.
