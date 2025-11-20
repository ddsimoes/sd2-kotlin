# WHY SD2?

SD2 is a small, context‑free language for describing structured data and domain‑specific configuration. It is not meant to replace JSON/YAML/XML everywhere; instead, it targets the space where:

- You want **human‑oriented configuration or metadata**, not just machine output.
- You want a **single format** that scales from hand‑written config to “mini DSLs”.
- You care about **predictable parsing** and **simple tooling**, without giving up expressiveness.

This document focuses on the “why”, not the syntax details.

---

## 1. SD2 in One Page

Conceptually, SD2 gives you:

- **Elements**: tree‑shaped blocks with keywords, ids, types, qualifiers, and nested bodies.
- **A small value model**: primitives, lists, maps, tuples, constructors, foreign code.
- **Constructor hooks**: a registry that can turn constructor calls into typed objects.
- **Temporal constructors**: built‑in, validated date/time/duration types.
- **Tabular arrays**: compact syntax for arrays of uniform rows.
- **Annotations and qualifiers**: metadata and modifiers that don’t clutter your core schema.
- **Foreign code blocks**: safely embed SQL, regexes, templates, scripts, etc.

The design aim is to be:

- **Context‑free**: no indentation sensitivity, no implicit merges, no magic based on schema.
- **Tool‑friendly**: easy to parse, stream, and transform.
- **Host‑agnostic**: SD2 itself doesn’t know about your domain – you define keywords and constructors.

---

## 2. Core Ideas and Design Goals

### 2.1 Meta‑Format, Not a Data Model

SD2 is a **meta‑format**, similar in spirit to XML, but lighter:

- The core language does not prescribe “what a server is” or “what a resource is”.
- You define **element keywords** (`service`, `job`, `domain`) and **constructors** (`duration`, `instant`, `Point`) in your own domain.
- The same syntax can express:
  - Application config,
  - Infrastructure/env config,
  - Workflow definitions,
  - Domain descriptors or schemas,
  - Small, domain‑specific languages.

The benefit: you invest in one set of tooling (parser, formatter, validators) and reuse it for multiple DSLs.

### 2.2 Context‑Free and Predictable

- No indentation‑based rules.
- No schema‑driven parsing or “merge” magic.
- Bodies and constructor delimiters must be on the **same line** as the name (helps tools and humans see structure at a glance).
- The grammar is explicit and small enough to reason about without surprises.

This matters for:

- **Streaming and incremental processing**.
- **Linting and auto‑fixers**.
- Embedding SD2 fragments into other contexts (code generators, templates).

### 2.3 Minimal, Explicit Value Model

The value system is intentionally small:

- Primitives: string, int, float, bool, null.
- Collections: list, map.
- Tuples (positional aggregates).
- Constructors (named arguments or positional).
- Foreign code (opaque blocks).

There is no overloading based on “special” keys or indentation; the few constructs you have cover most configuration needs without additional rules.

---

## 3. When SD2 Is a Good Fit

### 3.1 Human‑Edited Configuration with Structure

Example: a service definition with security and deployment concerns:

```sd2
service api {
    host = "localhost"
    port = 8080

    .security {
        ssl = true
        cert = "/path/to/cert"
        rule cors { origin = "*" }
        rule rateLimit {
            requests = 100
            window = duration("PT1M")
        }
    }

    .deployment {
        replicas = 3
        env = { name = "ENV", value = "prod" }
    }
}
```

Why SD2 works well here:

- Namespaces (`.security`, `.deployment`) keep concerns grouped.
- Constructors (`duration`) are validated, not just strings.
- You can still embed foreign code or scripts where needed.

### 3.2 Infrastructure‑Style Descriptors (Kubernetes/Terraform‑like)

Example: a small infra descriptor:

```sd2
deploy api {
    replicas = 2

    container main {
        image = "example/api:1.0"
        ports = [8080]
    }

    .scaling {
        minReplicas = 2
        maxReplicas = 10
        targetCPU = 70
    }
}
```

Compared to large YAML manifests:

- The structure is explicit via keywords and blocks, not nested maps everywhere.
- Namespaces make it easy to scope advanced concerns without deep nesting.
- You can attach annotations/qualifiers without changing your core schema.

### 3.3 Config + Embedded Foreign Code

Example: SQL and regex side‑by‑side with config:

```sd2
job nightlyBackup {
    schedule = cron@"0 3 * * *"
    connection = db.postgresql@"postgres://user:pass@db:5432/app"

    filter = regex@'^[A-Za-z0-9_-]+$'

    script = sh@'''
        pg_dump "$CONNECTION" > /backups/backup.sql
    '''
}
```

Benefits:

- The foreign code is treated as **opaque content** – SD2 does not try to interpret it.
- Constructors (`cron`, `db.postgresql`, `regex`, `sh`) can be validated or even executed by host tools.
- It keeps all the supporting scripts/queries **co‑located** with the configuration that uses them.

### 3.4 Temporal and Scheduling Data

Example: jobs with start/end windows and retention rules:

```sd2
job cleanup {
    start = instant("2025-01-20T03:00:00Z")
    window = duration("PT1H")
    retention = period("P30D")
}
```

Why this is nicer than strings:

- The parser validates format and components consistently.
- Tools can interpret these values as proper temporal objects (via constructor handlers).
- You avoid ad‑hoc date parsing sprinkled across your codebase.

### 3.5 Tabular Data Inside Config

Example: coded values/domain tables:

```sd2
domain statusDomain {
    name = "Status"
    fieldType = "smallInteger"

    codedValues = {(name, code)} [
        ("Not informed", 0),
        ("Active", 1),
        ("Inactive", 2),
        ("Pending", 3),
        ("Archived", 4),
    ]
}
```

Benefits:

- Compact representation for large tables.
- Still part of the same SD2 document (no separate CSV file).
- Desugars to ordinary values your tools already understand.

---

## 4. Language Features That Matter in Practice

### 4.1 Elements, Namespaces, and Qualifiers

- **Elements** look like:
  ```sd2
  keyword Name : Type qualifier a.b, c.d { ... }
  ```
- **Namespaces**: `.config { ... }` gives you a nested scope without new keywords.
- **Qualifiers** with continuation (`|`) support readable modifier lists:
  ```sd2
  service auth : AuthService
  | implements auth.OAuth2
  | with monitoring.Metrics, logging.Structured {
      enabled = true
  }
  ```

This gives you an expressive “header” for each block, while keeping bodies focused on values and nested elements.

### 4.2 Constructors and Registries

Constructors are just values:

```sd2
timeout = duration("PT30S")
center  = Point(-25.43, -49.27)
```

In the Kotlin implementation, a **constructor registry** can turn these into typed objects, while the language itself stays agnostic:

- Libraries can register handlers (e.g. `duration`, `instant`, `Point`).
- Callers choose whether unknown constructors should be treated as raw data or as errors.
- You can evolve constructor handlers independently of the language.

### 4.3 Temporal Constructors (Built‑In)

Temporal constructors (`date`, `time`, `instant`, `duration`, `period`) are standardized:

- Parsing and validation are consistent.
- Tooling (validators, IDE plugins) can understand them.
- You avoid “just a string” handling for time data.

This is intentionally narrow (no full schema language), but covers a very common pain point in configuration and metadata.

### 4.4 Tabular Arrays

Tabular arrays are new syntax sugar for **arrays of uniform rows**:

- Ad‑hoc maps: `{(name, code)} [ ("A", 1), ("B", 2) ]`.
- Typed positional: `User(_, _, _) [ (1, "alice", "admin"), ... ]`.
- Typed named: `User {(id, email, role)} [ (1, "alice", "admin"), ... ]`.

They are:

- Easier to scan and edit by hand than repeated map literals.
- Fully compatible with existing values (they desugar to list + map/constructors).
- Optional – you can always fall back to explicit structures.

### 4.5 Foreign Code Integration

Foreign code blocks:

- Use explicit delimiters; no escaping.
- Can be annotated with constructors (type tags).
- Preserve content exactly (including newlines in triple form).

This makes SD2 suitable as a “host” for DSLs that need to embed:

- SQL queries,
- Regex patterns,
- HTML/templates,
- Shell commands,
- JSON fragments, etc.

### 4.6 Backtick Identifiers, Reserved Words, and Predictability

- Only `true`, `false`, `null` are reserved.
- Backticks let you use almost any string as an identifier: `` `my field` ``.
- Specific positions (keywords, namespace names, qualifiers) are intentionally limited to **simple identifiers** only, which keeps the structure readable.

The result: you can model odd real‑world names without twisting your schema or resorting to nested key/value lists.

---

## 5. SD2 vs JSON

### 5.1 Where JSON Wins

- Ubiquitous support across languages and platforms.
- Extremely simple, well‑known data model.
- Ideal for machine‑generated data and APIs.

### 5.2 Where SD2 Is Better Suited

**Human‑edited configs and DSLs**

- JSON is verbose and lacks comments.
- SD2 supports comments, namespaces, annotations, and qualifiers.
- SD2 elements can encode intent in headers (keywords, types, qualifiers) rather than just shape in nested maps.

**Domain‑aware constructs**

- JSON has no notion of constructors or temporal types – everything is strings, numbers, booleans, or structures.
- SD2 lets you introduce domain constructors (`duration`, `cron`, `Point`) and hook them into tooling.

**Embedded code or templates**

- JSON has no natural syntax for embedding multi‑line code without escaping.
- SD2 foreign blocks and triple‑quoted strings keep embedded content readable.

### 5.3 Trade‑Offs

- If you only need machine‑to‑machine data exchange, JSON is simpler and more widely supported.
- If you need human‑oriented configuration and domain‑specific constructs, SD2 offers more structure with relatively small additional complexity.

---

## 6. SD2 vs YAML

### 6.1 Where YAML Wins

- Very concise for simple hierarchical data.
- Widely used in DevOps and config ecosystems (Kubernetes, GitHub Actions, etc.).

### 6.2 Where SD2 Is Better Suited

**Predictability and tooling**

- YAML has multiple edge cases (indentation, implicit typing, anchors/aliases) that complicate tooling and sometimes surprise users.
- SD2 is indentation‑agnostic and context‑free; all structure is explicit via delimiters and keywords.

**Domain structure**

- YAML documents are primarily nested maps/lists.
- SD2 gives you:
  - Element keywords and ids,
  - Namespaces,
  - Qualifiers and annotations,
  - Constructors and foreign code.

This is closer to a structured DSL than a plain data blob.

**Embedded code and mixed content**

- YAML’s block scalars can embed multi‑line text, but mixing them with richer constructs can be unwieldy.
- SD2’s foreign blocks and constructors provide a well‑defined interface between configuration and code.

### 6.3 Trade‑Offs

- For quick, small configuration files, YAML is often fine – especially in ecosystems that already expect YAML.
- When configurations grow into **mini DSLs** with specific semantics and tooling, SD2’s structure and predictable grammar may age better.

---

## 7. SD2 vs XML

### 7.1 Where XML Wins

- Mature, decades‑old ecosystem (XSD, XPath, XSLT, etc.).
- Strong schema languages and validation tooling.
- Suitable for document‑oriented data with mixed content.

### 7.2 Where SD2 Is Better Suited

**Signal‑to‑noise for configuration**

- XML is verbose; element and attribute names are repeated extensively.
- SD2 reduces syntactic noise:
  - No closing tag names,
  - Compact maps and lists,
  - Tabular arrays for repetitive structures.

**Domain‑specific structure without XML toolchain overhead**

- SD2 gives you a structured tree with a small grammar and no legacy baggage.
- If you don’t need XML’s document‑centric features (mixed text/elements, DTDs, etc.), SD2 is lighter to parse and work with.

**Embedded non‑XML content**

- Embedding scripts, SQL, or other languages into XML often runs into escaping issues.
- SD2 treats foreign code as first‑class, opaque content.

### 7.3 Trade‑Offs

- If you already rely on XML schema tooling, XPath, or XSLT, XML may still be preferable.
- For configuration and DSLs that are not document‑centric, SD2 is typically easier to adopt incrementally.

---

## 8. What SD2 Is *Not*

- Not a replacement for JSON in APIs.
- Not a general document format like HTML or Markdown.
- Not a full schema language (though you can build one on top).
- Not indentation‑based – if you want “Python‑style” configs, SD2 is deliberately different.

Recognizing these boundaries helps keep SD2 small and focused.

---

## 9. When to Consider SD2

SD2 is worth considering if:

- You are designing a **new configuration format** or DSL and don’t want to write/maintain your own parser.
- You want **stronger semantics** (constructors, temporals, tabular arrays) than JSON/YAML typically provide, but still want something approachable.
- You need to **embed foreign code** alongside structured configuration.
- You want a **single, reusable meta‑format** for multiple domain‑specific configs, with consistent tooling.

If your current JSON/YAML setup is working and your configs are small and simple, SD2 may not offer enough benefit to justify migration. But if you feel your configuration is turning into an ad‑hoc language, SD2 gives you a structured foundation to grow on without inventing a brand‑new syntax. 
