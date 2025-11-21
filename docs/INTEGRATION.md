# Using SD2 in Your Tools

This document focuses on how to *consume* SD2 in your own tools and applications, in terms of semantics and best practices rather than low‑level serialization details.

For language details, see `sd2-spec.md`. For a guided tour and examples, see `docs/TOUR.md` and `docs/EXAMPLES.md`.

---

## 1. Element and Namespace Semantics

At a high level, an SD2 document is a sequence of **elements**:

```sd2
service api {
  host = "localhost"
  port = 8080

  route listUsers {
    path   = "/users"
    method = "GET"
  }
}
```

Semantically:

- Each element (`service`, `route`, `job`, `policy`, etc.) is a **domain statement**.
- The keyword (`service`) tells you *what kind* of thing it is.
- The identifier (`api`, `listUsers`) gives it an identity in its scope.
- Attributes are configuration values attached to that statement.
- Nested elements express **structural containment** (e.g., routes inside a service).

Namespaces add **extension scopes** without changing your domain keyword set:

```sd2
service api {
  host = "localhost"
  port = 8080

  .monitoring {
    interval = duration("PT30S")
  }
}
```

Best practices:

- Use elements for primary domain concepts (`service`, `route`, `deployment`, `job`).
- Use namespaces for optional, cross‑cutting concerns that can appear on many kinds of elements (`.monitoring`, `.security`, `.overrides.prod`).
- In your tooling, treat namespaces as additional nested scopes hanging off an element, not as a different data model.

Your in‑memory representation can follow this semantic structure directly (e.g., `Service` objects containing `Route` objects plus an optional `Monitoring` extension), without forcing everything through a generic map/list shape first.

---

## 2. Values and Constructors (Semantics)

SD2 values are deliberately small and explicit:

- Primitives: `int`, `float`, `bool`, `string`, `null`.
- Collections: `list`, `map`, `tuple`.
- Constructors: positional (`Point(1, 2)`) and named (`policy { attempts = 3 }`).
- Foreign code: tagged opaque blocks (`sql@"SELECT 1"`, `sh@'''...'''`).

Semantically:

- Constructors are **typed values**:
  - Temporal constructors (`instant`, `duration`, `date`, `time`, `period`) carry strong date/time semantics.
  - Domain constructors (`Point`, `cron`, `db.postgresql`) carry domain‑specific meaning.
- Foreign code blocks are **opaque programs or templates** associated with your configuration, not strings you should freely rewrite.

Best practices:

- Decide early whether your tool:
  - Treats constructors as first‑class typed values (recommended for temporals and important domain types), or
  - Treats them as tagged, but otherwise opaque, structures.
- For foreign code, keep SD2 responsible only for locating and packaging the code; let specialized tools (SQL engines, shell, template engines) interpret the content.
- Avoid flattening constructors into plain strings where possible; you lose the ability to validate and reason about them.

The Kotlin implementation supports constructor‑aware consumption via a configurable **constructor registry** (see `README.md`), so you can map selected constructors to your own domain objects and keep others raw.

---

## 3. Kotlin Integration (Overview)

The `Sd2.reader` API streams a sequence of events from an SD2 document:

- `StartElement` / `EndElement`
- `Attribute`
- `StartNamespace` / `EndNamespace`
- `StartDocument` / `EndDocument`

You can:

- Handle events manually and build your own in‑memory model (e.g., domain objects that mirror `service`, `job`, `policy`, etc.).
- Use the default constructor registry to get typed temporal values where it makes sense.
- Disable or customize constructor handling to fit your domain semantics.

See the “Library (Kotlin)” section in `README.md` for code snippets that:

- Parse a string or file into events.
- Inspect attributes and resolved values.
- Register custom constructors (e.g., `Point`, domain types).

---

## 4. Interop Strategies

Depending on your ecosystem, common interop patterns include:

- **SD2 as a DSL front‑end**
  - Define a small set of keywords (`service`, `job`, `policy`, `domain`).
  - Map elements + attributes + constructors directly to your own domain objects.
  - Use foreign code blocks as hooks into SQL, shell, templates, etc.

- **Mixed configs**
  - Keep some parts of your system in JSON/YAML.
  - Introduce SD2 for new DSL‑like configurations (workflows, jobs, policies) where structure, temporals, and embedded code matter more.

When you do need to bridge to a simpler format (JSON, YAML, environment variables, etc.), do it with a clear understanding of what semantics you are willing to drop (e.g., treating temporal constructors as strings, or flattening foreign code into plain text). SD2 itself is meant to be the **semantic source of truth** for these richer configurations, not just another syntax for generic key/value blobs.

