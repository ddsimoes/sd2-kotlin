# SD2 Example Gallery

This gallery shows small, focused SD2 examples built from real‑world styles (services, infra, jobs, domain tables). Each example highlights a few key language features.

---

## 1. Service Configuration with Monitoring

```sd2
service api {
  host = "localhost"
  port = 8080

  route listUsers {
    path   = "/users"
    method = "GET"
  }

  .security {
    ssl  = true
    cert = "/path/to/cert"
  }

  .monitoring {
    interval = duration("PT30S")
  }
}
```

**What this shows**
- Elements (`service`, `route`) with nested structure.
- Attributes for simple configuration values.
- Namespaces (`.security`, `.monitoring`) as extension slots for cross‑cutting concerns.
- Temporal constructor (`duration`) for typed time data.

---

## 2. Infra‑Style Descriptor (Kubernetes/Terraform‑like)

```sd2
stack infra {
  provider aws {
    region  = "us-east-1"
    profile = "prod"
  }

  module vpc {
    source            = "terraform-aws-modules/vpc/aws"
    cidr              = "10.0.0.0/16"
    azs               = ["us-east-1a", "us-east-1b", "us-east-1c"]
    enable_nat_gateway = true
  }

  module eks {
    source       = "terraform-aws-modules/eks/aws"
    cluster_name = "prod-eks"
    desired_size = 3
    min_size     = 3
    max_size     = 9
  }

  .prod {
    module eks {
      desired_size = 6
      min_size     = 6
      max_size     = 12
    }
  }
}
```

**What this shows**
- Domain‑specific keywords (`stack`, `provider`, `module`) without changing the core language.
- Lists, booleans, and strings for typical infra parameters.
- Namespace `.prod` to override or specialize configuration for a particular environment.

---

## 3. Jobs with Temporal Windows and Scripts

```sd2
job nightlyBackup {
  schedule   = cron("0 3 * * *")
  start      = instant("2025-01-20T03:00:00Z")
  window     = duration("PT1H")

  script = sh@'''
    pg_dump "$CONNECTION" > /backups/backup.sql
  '''
}
```

**What this shows**
- Constructors for temporal data (`instant`, `duration`) and domain‑specific types (`cron`).
- A foreign code block (`sh@'''...'''`) for shell script, preserved as opaque content.
- A single element that mixes typed values and embedded code.

---

## 4. Domain Table with Tabular Arrays

```sd2
domain statusDomain {
  name      = "Status"
  fieldType = "smallInteger"

  codedValues = {(name, code)} [
    ("Not informed", 0),
    ("Active",       1),
    ("Inactive",     2),
    ("Pending",      3),
  ]
}
```

**What this shows**
- A small domain descriptor (`domain statusDomain { ... }`).
- A tabular array that compacts many similar rows.
- How tabular arrays desugar to lists of maps that tools can consume.

Desugared form:

```sd2
codedValues = [
  { name = "Not informed", code = 0 },
  { name = "Active",       code = 1 },
  { name = "Inactive",     code = 2 },
  { name = "Pending",      code = 3 },
]
```

---

## 5. Config Plus Embedded HTTP / SQL

```sd2
api users {
  title   = "Users API"
  version = "1.0.0"
  server  = "https://api.example.com"

  path `/users` {
    get {
      summary = "List users"
      response ok {
        contentType = "application/json"
        schema = @"""
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "id":   { "type": "string" },
      "name": { "type": "string" }
    }
  }
}
"""
      }
    }
  }
}
```

**What this shows**
- Backtick identifiers for paths with special characters (`` path `/users` ``).
- Nested elements modeling an OpenAPI‑style structure.
- Foreign code (JSON) embedded as a triple‑quoted block, without escaping.

---

## 6. Policy / Rules with Foreign Code

```sd2
policy requireOwnerLabel {
  kind  = "K8sRequiredLabels"
  match = { kinds = ["Pod", "Deployment"] }
  parameters = { labels = ["owner", "team"] }

  rego = @'''
    package kubernetes.admission

    deny[msg] {
      input.review.object.metadata.labels["owner"] == null
      msg := "owner label is required"
    }
  '''
}
```

**What this shows**
- A compact policy description as an element with a few attributes.
- Maps and lists for structured parameters.
- A foreign code block (Rego policy) where tooling can send the content to an external engine.

---

These examples are intentionally small and focused. For a deeper explanation of the language and design rationale, continue with:

- `docs/TOUR.md` for a guided introduction.
- `sd2-spec.md` for the complete language specification.
- `WHY.md` for background and comparisons with JSON/YAML/XML.

