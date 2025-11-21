# SD2 in 10 Minutes

This tour introduces SD2 step‑by‑step, from basic elements to advanced features like constructors, foreign code, namespaces, and tabular arrays. It is intentionally practical and leaves full details to `sd2-spec.md` and `WHY.md`.

---

## 1. Elements and Attributes

The core building block in SD2 is an **element**:

```sd2
service api {
  host = "localhost"
  port = 8080
}
```

- `service` is the **keyword** (a domain‑specific kind of element).
- `api` is the **identifier** (optional).
- Inside `{ ... }` you define **attributes** (`host`, `port`) as key/value pairs.

Attributes are simple assignments:

```sd2
host = "localhost"
port = 8080
debug = true
timeoutSeconds = 30
```

Values can be numbers, strings, booleans, `null`, lists, maps, tuples, constructors, or foreign code (covered later).

---

## 2. Nested Elements

Elements can appear inside other elements to represent structure:

```sd2
service api {
  host = "localhost"
  port = 8080

  route listUsers {
    path = "/users"
    method = "GET"
  }

  route createUser {
    path = "/users"
    method = "POST"
  }
}
```

Here `route` is just another keyword. Nested elements make the tree structure explicit without relying on indentation or implicit rules.

---

## 3. Types and Simple Qualifiers

Elements can declare types and **qualifiers** in their header:

```sd2
job cleanup : ScheduledJob daily {
  description = "Clean old data"
}
```

- `: ScheduledJob` is a type declaration.
- `daily` is a qualifier with no arguments in this example domain (the spec allows qualifiers with arguments like `implements a.b, c.d`).

Qualifiers are domain‑specific modifiers interpreted by your tools. SD2 just enforces their syntactic shape.

---

## 4. Constructors (Typed Values)

Constructors give names to complex values and let tooling attach semantics:

```sd2
job cleanup {
  start   = instant("2025-01-20T03:00:00Z")
  window  = duration("PT1H")
  repeat  = cron("0 3 * * *")
  center  = Point(-25.43, -49.27)
}
```

All of these are **values**:

- `instant("...")` and `duration("...")` can be handled by built‑in temporal logic.
- `cron("0 3 * * *")` and `Point(-25.43, -49.27)` are just user‑defined constructors.

Constructors come in two forms:

```sd2
// Positional
center = Point(-25.43, -49.27)

// Named arguments (map‑constructor)
retryPolicy = policy {
  attempts = 3
  backoff  = "exponential"
}
```

SD2 itself does not know what `Point` or `policy` mean; your host application registers handlers for those names.

---

## 5. Lists, Maps, and Tuples

You can model structured configuration with a small set of value types:

```sd2
config app {
  // Lists
  ports = [8080, 8443, 9090]
  envs  = ["dev", "staging", "prod"]

  // Maps
  labels = {
    team = "api",
    tier = "backend",
  }

  // Tuples
  center = (-25.43, -49.27)
}
```

- Lists: `[value, value, ...]`
- Maps: `{ key = value, ... }`
- Tuples: `(a, b, c)` (used both directly and inside constructors).

These are enough to represent most JSON/YAML‑style data while still fitting cleanly into SD2’s grammar.

---

## 6. Embedding Foreign Code

SD2 can embed other languages as **foreign code blocks**. Content is treated as opaque by SD2 and can be processed by your tools.

```sd2
job nightlyBackup {
  schedule   = cron@"0 3 * * *"
  connection = db.postgresql@"postgres://user:pass@db:5432/app"

  script = sh@'''
    pg_dump "$CONNECTION" > /backups/backup.sql
  '''
}
```

- `cron@'...'` and `db.postgresql@"..."` tag foreign strings with constructors (`cron`, `db.postgresql`).
- `sh@'''...'''` embeds a multi‑line shell script without worrying about escaping.

Foreign blocks make it practical to keep queries, scripts, and templates next to the configuration that uses them.

---

## 7. Namespaces (Extension Slots)

Namespaces are lightweight nested scopes written as `.name { ... }`. They are best used for **cross‑cutting concerns** or **extensions**, not for every kind of nesting.

```sd2
service api {
  host = "localhost"
  port = 8080

  .security {
    ssl  = true
    cert = "/path/to/cert"
  }

  .monitoring {
    interval = duration("PT30S")
  }
}
```

Guidelines:

- Prefer **elements** (`security { ... }`) for core domain concepts.
- Use **namespaces** (`.security { ... }`) when you want an optional, extensible section that can appear on many element types without adding new keywords or identifiers.

Internally, namespaces provide an extra scope for uniqueness rules while keeping syntax compact.

---

## 8. Tabular Arrays

Tabular arrays are compact syntax for lists of uniform rows. They are useful for domain tables or configuration with many similar entries.

### 8.1 Ad‑hoc Maps

```sd2
domain statusDomain {
  codedValues = {(name, code)} [
    ("Not informed", 0),
    ("Active",       1),
    ("Inactive",     2),
  ]
}
```

This desugars to:

```sd2
codedValues = [
  { name = "Not informed", code = 0 },
  { name = "Active",       code = 1 },
  { name = "Inactive",     code = 2 },
]
```

### 8.2 Typed Rows

You can also produce rows as constructors:

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

Tabular arrays are pure syntax sugar: implementations can treat them as if they had already been expanded.

---

## 9. Annotations and Document Metadata

Annotations attach metadata to elements or entire documents without changing the core structure.

Element annotations:

```sd2
#[deprecated(reason = "use v2")]
#[since("2.1.0")]
api users : RestAPI { }
```

Document annotations (must appear before the first element):

```sd2
##[version("0.8")]
##[plugin("org.jetbrains.compose")]
```

You can define your own annotation names and interpret them in tooling (e.g., code generators, linters, build systems).

---

## 10. Putting It All Together

Here is a small but realistic SD2 configuration that combines several features from this tour:

```sd2
service api {
  host = "localhost"
  port = 8080

  route listUsers {
    path   = "/users"
    method = "GET"
  }

  job cleanup {
    start   = instant("2025-01-20T03:00:00Z")
    window  = duration("PT1H")
    script  = sh@'./cleanup.sh'
  }

  .monitoring {
    interval = duration("PT30S")
  }
}
```

Next steps:

- Read `WHY.md` for design rationale and comparisons to JSON/YAML/XML.
- Read `sd2-spec.md` for the complete, precise language definition.
- Experiment with your own small DSLs by choosing keywords (`service`, `job`, `policy`, `domain`) and constructors (`duration`, `cron`, `Point`, your own types).

