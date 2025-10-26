package io.github.ddsimoes.sd2

class ParseError(val code: String, message: String, val location: Location) : RuntimeException("$message (line: ${location.line}; column: ${location.column})")

internal class Sd2StreamReader(
    source: Sd2Source,
    private val config: Sd2ReaderConfig,
) : Sd2Reader {
    private val lex = Lexer(source)
    private val buffer = ArrayDeque<Token>()
    private var started = false
    private var ended = false
    private val pendingAnnotations = mutableListOf<Annotation>()
    private var docPhase = true

    // simple stack to manage scopes
    private enum class Scope { DOCUMENT, BODY, ELEMENT, ELEMENT_NO_BODY, NAMESPACE }
    private val scopes = ArrayDeque<Scope>()

    override fun next(): Sd2Event {
        while (true) {
            try {
                if (!started) {
                    started = true
                    scopes.addLast(Scope.DOCUMENT)
                    return Sd2Event.StartDocument(location())
                }

                if (ended) return Sd2Event.EndDocument(location())

                // Skip stray newlines between constructs
                skipNewlines()

                // Misused line continuation outside qualifier context
                if (peek().kind == TKind.PIPE) {
                    throw ParseError("E1004", "line continuation '|' used outside qualifier context", peek().loc)
                }

                // Handle annotations
                if (scopes.lastOrNull() != Scope.BODY) {
                    if (peek().kind == TKind.HASH && lookaheadKind(1) == TKind.BANG) {
                        if (!docPhase) throw ParseError("E1000", "document annotations are only allowed at the top of the document", peek().loc)
                        val ann = parseAnnotation(document = true)
                        return Sd2Event.DocumentAnnotation(ann.name, ann.argsRaw, location())
                    }
                    while (peek().kind == TKind.HASH) {
                        val ann = parseAnnotation(document = false)
                        pendingAnnotations += ann
                        skipNewlines()
                    }
                }

                // End of document handling
                if (peek().kind == TKind.EOF) {
                    ended = true
                    return Sd2Event.EndDocument(location())
                }

                if (scopes.lastOrNull() == Scope.BODY) {
                    return parseBodyItem()
                }

                if (scopes.lastOrNull() == Scope.ELEMENT_NO_BODY) {
                    scopes.removeLast()
                    return Sd2Event.EndElement(location())
                }

                // Any non-annotation construct at top-level ends doc annotation phase
                if (scopes.isEmpty() || scopes.lastOrNull() == Scope.DOCUMENT) {
                    docPhase = false
                }
                return parseElementOrNamespaceAtTop()
            } catch (pe: ParseError) {
                if (config.allowRecovery) {
                    config.onError?.invoke(Sd2Error(pe.code, pe.message ?: "error", pe.location))
                    recover()
                    pendingAnnotations.clear()
                    continue
                } else {
                    throw pe
                }
            }
        }
    }

    override fun close() { /* nothing */ }

    private fun parseElementOrNamespaceAtTop(): Sd2Event {
        // element: keyword IDENT? type? qualifiers? body?
        // namespace cannot appear at top without leading '.' (handled elsewhere)
        val startTok = expectIdentLike("expected element keyword")
        val keyword = Identifier(startTok.text)
        var id: Identifier? = null
        var type: TypeExpr? = null
        val qualifiers = mutableListOf<Qualifier>()
        
        // Optional identifier
        if (peek().kind == TKind.IDENT || peek().kind == TKind.BACKTICK_IDENT) {
            id = Identifier(consume().text)
        }

        // Optional type declaration
        if (peek().kind == TKind.COLON) {
            consume()
            type = parseTypeExpr()
        }

        // Optional qualifiers with possible line continuation
        qualifiers += parseQualifiers()

        // Body must be on same line as last qualifier/header (no intervening NEWLINE)
        if (peek().kind == TKind.NEWLINE && lookaheadKind(1) == TKind.LBRACE) {
            throw ParseError("E1001", "body '{' must be on the same line as header/qualifiers", peek().loc)
        }

        // Optional body
        if (peek().kind == TKind.LBRACE) {
            // open element
            consume() // {
            scopes.addLast(Scope.ELEMENT)
            scopes.addLast(Scope.BODY)
            return Sd2Event.StartElement(keyword, id, type, annotations = pendingAnnotations.toList(), qualifiers = qualifiers, location = startTok.loc).also { pendingAnnotations.clear() }
        }

        // no body, emit start+end element in two steps: first StartElement
        scopes.addLast(Scope.ELEMENT_NO_BODY)
        return Sd2Event.StartElement(keyword, id, type, annotations = pendingAnnotations.toList(), qualifiers = qualifiers, location = startTok.loc).also { pendingAnnotations.clear() }
    }

    private fun parseBodyItem(): Sd2Event {
        // close body?
        when (peek().kind) {
            TKind.PIPE -> {
                throw ParseError("E1004", "line continuation '|' used outside qualifier context", peek().loc)
            }
            TKind.RBRACE -> {
                consume()
                scopes.removeLast() // BODY
                when (scopes.removeLastOrNull()) {
                    Scope.ELEMENT -> return Sd2Event.EndElement(location())
                    Scope.NAMESPACE -> return Sd2Event.EndNamespace(location())
                    else -> throw error("unexpected scope close")
                }
            }
            TKind.DOT -> {
                // .namespace { ... }
                consume()
                val nameTok = expectIdentLike("expected namespace name after '.'")
                expect(TKind.LBRACE, "expected '{' after namespace name")
                scopes.addLast(Scope.NAMESPACE)
                scopes.addLast(Scope.BODY)
                return Sd2Event.StartNamespace(Identifier(nameTok.text), nameTok.loc)
            }
            TKind.BACKTICK_IDENT -> {
                // backticked cannot be an element keyword; must be attribute
                val nameTok = consume()
                expect(TKind.EQUALS, "expected '=' after attribute name")
                val value = parseSimpleValue()
                if (peek().kind == TKind.NEWLINE) {
                    consume()
                } else if (peek().kind != TKind.RBRACE) {
                    throw ParseError("E1000", "expected NEWLINE after attribute value", peek().loc)
                }
                return Sd2Event.Attribute(Identifier(nameTok.text), value, nameTok.loc)
            }
            TKind.IDENT -> {
                // Disambiguate attribute vs element: IDENT '=' -> attribute; otherwise element
                if (lookaheadKind(1) == TKind.EQUALS) {
                    val nameTok = consume()
                    expect(TKind.EQUALS, "expected '=' after attribute name")
                    val value = parseSimpleValue()
                    if (peek().kind == TKind.NEWLINE) {
                        consume()
                    } else if (peek().kind != TKind.RBRACE) {
                        throw ParseError("E1000", "expected NEWLINE after attribute value", peek().loc)
                    }
                    return Sd2Event.Attribute(Identifier(nameTok.text), value, nameTok.loc)
                } else {
                    return parseElementOrNamespaceAtTop()
                }
            }
            else -> {
                // nested element
                return parseElementOrNamespaceAtTop()
            }
        }
    }

    private fun parseSimpleValue(): Sd2Value {
        return when (peek().kind) {
            TKind.STRING -> consume().let { Sd2Value.VString(it.text, it.loc) }
            TKind.BOOL -> {
                val t = consume()
                // Reserved words cannot be used as foreign code constructors directly before '@'
                if (peek().kind == TKind.AT) {
                    val atTok = peek()
                    if (sameLineAdjacent(t, atTok)) {
                        throw ParseError("E4004", "reserved word cannot be used as foreign code constructor", atTok.loc)
                    }
                }
                Sd2Value.VBool(t.text == "true", t.loc)
            }
            TKind.NULL -> {
                val t = consume()
                if (peek().kind == TKind.AT) {
                    val atTok = peek()
                    if (sameLineAdjacent(t, atTok)) {
                        throw ParseError("E4004", "reserved word cannot be used as foreign code constructor", atTok.loc)
                    }
                }
                Sd2Value.VNull(t.loc)
            }
            TKind.INT -> {
                val t = consume()
                val raw = t.text.replace("_", "")
                val v = when {
                    raw.startsWith("0x") || raw.startsWith("0X") -> raw.substring(2).toLong(16)
                    raw.startsWith("0b") || raw.startsWith("0B") -> raw.substring(2).toLong(2)
                    else -> raw.toLong()
                }
                Sd2Value.VInt(v, t.loc)
            }
            TKind.FLOAT -> {
                val t = consume()
                val v = t.text.replace("_", "")
                Sd2Value.VFloat(v.toDouble(), t.loc)
            }
            TKind.LBRACK -> parseList()
            TKind.LBRACE -> parseMap()
            TKind.LPAREN -> parseTupleLiteral()
            TKind.IDENT, TKind.BACKTICK_IDENT -> {
                val startLoc = peek().loc
                val (qn, nameEndTok) = parseQualifiedNameWithLast()
                // Foreign code with constructor: identifier '@' foreign
                if (peek().kind == TKind.AT) {
                    val atTok = peek()
                    if (!sameLineAdjacent(nameEndTok, atTok)) {
                        throw ParseError("E4003", "whitespace not allowed between constructor and '@' in foreign code", atTok.loc)
                    }
                    // consume AT token and build VForeign with constructor
                    val fc = expect(TKind.AT, "expected foreign code content after '@'")
                    return Sd2Value.VForeign(fc.text, fc.loc, constructor = qn)
                }
                if (peek().kind == TKind.NEWLINE && lookaheadKind(1) == TKind.LBRACE) {
                    throw ParseError("E1001", "body '{' must be on the same line as constructor name", peek().loc)
                }
                if (peek().kind == TKind.NEWLINE && lookaheadKind(1) == TKind.LPAREN) {
                    throw ParseError("E1005", "'(' of tuple-constructor must be on the same line as its name", peek().loc)
                }
                if (peek().kind == TKind.LBRACE) {
                    val attrs = parseConstructorBody()
                    // Try constructor resolution when registry present
                    val resolved = attemptResolveConstructor(qn, args = null, attrs = attrs, loc = startLoc)
                    return resolved ?: Sd2Value.VConstructor(qn, attrs, startLoc)
                }
                if (peek().kind == TKind.LPAREN) {
                    val args = parseTupleLikeArguments()
                    // Try constructor resolution when registry present
                    val resolved = attemptResolveConstructor(qn, args = args, attrs = null, loc = startLoc)
                    return resolved ?: Sd2Value.VConstructorTuple(qn, args, startLoc)
                }
                Sd2Value.VQualifiedName(qn.parts.map { it.text }, startLoc)
            }
            TKind.AT -> parseForeignCode()
            else -> throw error("unexpected token in value: ${peek().kind}")
        }
    }

    // ---- Constructor resolution helpers ----
    private fun attemptResolveConstructor(
        name: QualifiedName,
        args: List<Sd2Value>?,
        attrs: Map<String, Sd2Value>?,
        loc: Location,
    ): Sd2Value? {
        val reg = config.constructorRegistry ?: return null
        val entry = reg.handlerFor(name)
        if (entry == null) {
            return when (config.unknownConstructorPolicy) {
                UnknownConstructorPolicy.KeepRaw -> null
                UnknownConstructorPolicy.Error -> throw ParseError("E5001", "unknown constructor: ${name.parts.joinToString(".")}", loc)
            }
        }

        val handler = entry.handler
        val call = ConstructorCall(name = name, args = args ?: emptyList(), attrs = attrs, location = loc)
        val ctx = object : ConstructorContext {
            override fun resolve(value: Sd2Value): Sd2Value = resolveValue(value)
            override fun error(code: String, message: String, at: Location?): Nothing =
                throw ParseError(code, message, at ?: loc)
        }
        val obj = handler.invoke(call, ctx)
        return Sd2Value.VObject(type = entry.type, value = obj, location = loc)
    }

    private fun resolveValue(v: Sd2Value): Sd2Value {
        return when (v) {
            is Sd2Value.VString,
            is Sd2Value.VInt,
            is Sd2Value.VFloat,
            is Sd2Value.VBool,
            is Sd2Value.VNull,
            is Sd2Value.VQualifiedName,
            is Sd2Value.VForeign,
            is Sd2Value.VObject -> v
            is Sd2Value.VTuple -> {
                if (v.items.isEmpty()) v
                else Sd2Value.VTuple(v.items.map { resolveValue(it) }, v.location)
            }
            is Sd2Value.VList -> {
                if (v.items.isEmpty()) v
                else Sd2Value.VList(v.items.map { resolveValue(it) }, v.location)
            }
            is Sd2Value.VMap -> {
                if (v.entries.isEmpty()) v
                else Sd2Value.VMap(v.entries.mapValues { (_, vv) -> resolveValue(vv) }, v.location)
            }
            is Sd2Value.VConstructor -> {
                attemptResolveConstructor(v.name, args = null, attrs = v.attributes, loc = v.location) ?: v
            }
            is Sd2Value.VConstructorTuple -> {
                attemptResolveConstructor(v.name, args = v.args, attrs = null, loc = v.location) ?: v
            }
        }
    }

    private fun parseTupleLiteral(): Sd2Value.VTuple {
        val tok = expect(TKind.LPAREN, "expected '(' for tuple")
        val items = mutableListOf<Sd2Value>()
        skipNewlines()
        if (peek().kind == TKind.RPAREN) {
            // allow empty tuple ()
            consume() // RPAREN
            return Sd2Value.VTuple(items, tok.loc)
        }
        // read first value
        val first = parseSimpleValue()
        items += first
        skipNewlines()
        var sawComma = false
        while (peek().kind == TKind.COMMA) {
            sawComma = true
            consume()
            skipNewlines()
            if (peek().kind == TKind.RPAREN) break // trailing comma
            items += parseSimpleValue()
            skipNewlines()
        }
        expect(TKind.RPAREN, "expected ')' to close tuple")
        // Single-element tuples without trailing comma are now allowed
        return Sd2Value.VTuple(items, tok.loc)
    }

    private fun parseTupleLikeArguments(): List<Sd2Value> {
        expect(TKind.LPAREN, "expected '(' for constructor arguments")
        val items = mutableListOf<Sd2Value>()
        skipNewlines()
        if (peek().kind != TKind.RPAREN) {
            items += parseSimpleValue()
            skipNewlines()
            while (peek().kind == TKind.COMMA) {
                consume()
                skipNewlines()
                if (peek().kind == TKind.RPAREN) break // trailing comma
                items += parseSimpleValue()
                skipNewlines()
            }
        }
        expect(TKind.RPAREN, "expected ')' to close constructor arguments")
        return items
    }

    private fun parseForeignCode(): Sd2Value {
        // Lexer returns a single AT token whose text is the foreign content
        val tok = expect(TKind.AT, "expected foreign code content after '@'")
        return Sd2Value.VForeign(tok.text, tok.loc, constructor = null)
    }

    private fun parseList(): Sd2Value.VList {
        val ltok = expect(TKind.LBRACK, "expected '[' for list")
        val items = mutableListOf<Sd2Value>()
        skipNewlines()
        if (peek().kind != TKind.RBRACK) {
            items += parseSimpleValue()
            skipNewlines()
            while (peek().kind == TKind.COMMA) {
                consume()
                skipNewlines()
                if (peek().kind == TKind.RBRACK) break // trailing comma
                items += parseSimpleValue()
                skipNewlines()
            }
        }
        skipNewlines()
        expect(TKind.RBRACK, "expected ']' to close list")
        return Sd2Value.VList(items, ltok.loc)
    }

    private fun parseMap(): Sd2Value.VMap {
        val mtok = expect(TKind.LBRACE, "expected '{' for map")
        val entries = linkedMapOf<String, Sd2Value>()
        skipNewlines()
        // Empty map
        if (peek().kind == TKind.RBRACE) {
            expect(TKind.RBRACE, "expected '}' to close map")
            return Sd2Value.VMap(entries, mtok.loc)
        }

        // Parse first entry
        fun parseEntry() {
            // key: identifier, string, or bracketed primitive
            val (key, keyLoc) = when (peek().kind) {
                TKind.IDENT -> { val tok = consume(); tok.text to tok.loc }
                TKind.STRING -> { val tok = consume(); tok.text to tok.loc }
                TKind.LBRACK -> {
                    consume()
                    val k = parsePrimitiveKeyAsString()
                    expect(TKind.RBRACK, "expected ']' after primitive map key")
                    k to location()
                }
                else -> throw error("expected map key (identifier, string, or [primitive])")
            }
            // '=' before value is required
            expect(TKind.EQUALS, "expected '=' after map key")
            skipNewlines()
            val value = parseSimpleValue()
            if (entries.containsKey(key)) throw ParseError("E2003", "duplicate key '$key' in map", keyLoc)
            entries[key] = value
        }

        parseEntry()
        skipNewlines()

        // Subsequent entries must be comma-separated; allow trailing comma
        while (peek().kind == TKind.COMMA) {
            consume() // comma
            skipNewlines()
            if (peek().kind == TKind.RBRACE) break // trailing comma
            parseEntry()
            skipNewlines()
        }

        expect(TKind.RBRACE, "expected '}' to close map")
        return Sd2Value.VMap(entries, mtok.loc)
    }

    private fun parsePrimitiveKeyAsString(): String {
        return when (peek().kind) {
            TKind.STRING -> consume().text
            TKind.BOOL -> consume().text
            TKind.NULL -> { consume(); "null" }
            TKind.INT -> consume().text
            TKind.FLOAT -> consume().text
            else -> throw error("expected primitive in map key")
        }
    }

    private fun parseConstructorBody(): Map<String, Sd2Value> {
        expect(TKind.LBRACE, "expected '{' to start constructor body")
        val attrs = linkedMapOf<String, Sd2Value>()
        while (true) {
            when (peek().kind) {
                TKind.RBRACE -> { consume(); return attrs }
                TKind.NEWLINE -> { consume(); continue }
                TKind.IDENT -> {
                    val keyTok = consume()
                    expect(TKind.EQUALS, "expected '=' in constructor attribute")
                    val v = parseSimpleValue()
                    expect(TKind.NEWLINE, "expected NEWLINE after constructor attribute")
                    attrs[keyTok.text] = v
                }
                TKind.EOF -> throw error("unterminated constructor body")
                else -> throw error("unexpected token in constructor body")
            }
        }
    }

    private fun parseAnnotation(document: Boolean): Annotation {
        expect(TKind.HASH, "expected '#' for annotation")
        if (document) expect(TKind.BANG, "expected '!' in document annotation") else if (peek().kind == TKind.BANG) throw ParseError("E1000", "unexpected '!' in element annotation", peek().loc)
        expect(TKind.LBRACK, "expected '[' after # or #!")
        val name = parseQualifiedName()
        // Optional args in parentheses – capture raw text
        var argsRaw: String? = null
        if (peek().kind == TKind.LPAREN) {
            consume() // (
            val sb = StringBuilder()
            var depth = 1
            while (depth > 0) {
                val t = consume()
                when (t.kind) {
                    TKind.LPAREN -> { depth++; sb.append('(') }
                    TKind.RPAREN -> { depth--; if (depth > 0) sb.append(')') }
                    TKind.NEWLINE -> sb.append('\n')
                    TKind.STRING, TKind.IDENT, TKind.BACKTICK_IDENT, TKind.INT, TKind.FLOAT, TKind.BOOL, TKind.NULL -> sb.append(t.text)
                    TKind.COMMA -> sb.append(',')
                    TKind.DOT -> sb.append('.')
                    TKind.COLON -> sb.append(':')
                    TKind.EQUALS -> sb.append('=')
                    TKind.LBRACK -> sb.append('[')
                    TKind.RBRACK -> sb.append(']')
                    TKind.LBRACE -> sb.append('{')
                    TKind.RBRACE -> sb.append('}')
                    TKind.AT -> sb.append('@').append(t.text)
                    else -> {}
                }
                if (t.kind == TKind.EOF) throw ParseError("E1000", "unterminated annotation arguments", location())
            }
            argsRaw = sb.toString()
        }
        expect(TKind.RBRACK, "expected ']' to close annotation")
        return Annotation(name, argsRaw)
    }

    private fun recover() {
        // Consume tokens until a safe boundary: NEWLINE or a closing delimiter or EOF
        var consumed = false
        while (true) {
            val t = peek()
            when (t.kind) {
                TKind.NEWLINE -> { consume(); return }
                TKind.RBRACE, TKind.RBRACK, TKind.RPAREN, TKind.EOF -> return
                else -> { consume(); consumed = true }
            }
        }
    }

    private fun expect(kind: TKind, msg: String): Token {
        val t = consume()
        if (t.kind != kind) throw ParseError("E1000", msg, t.loc)
        return t
    }

    private fun expectIdentLike(msg: String): Token {
        val t = consume()
        if (t.kind != TKind.IDENT) throw ParseError("E1000", msg, t.loc)
        return t
    }

    private fun expectOneOf(msg: String, vararg kinds: TKind): Token {
        val t = consume()
        if (kinds.none { it == t.kind }) throw ParseError("E1000", msg, t.loc)
        return t
    }

    private fun error(msg: String): ParseError = ParseError("E1000", msg, location())

    private fun lastConsumedToken(): Token? = buffered

    private fun sameLineAdjacent(left: Token, right: Token): Boolean {
        if (left.loc.line != right.loc.line) return false
        val leftLen = when (left.kind) {
            TKind.BACKTICK_IDENT -> left.text.length + 2
            else -> left.text.length
        }
        val expectedRightCol = left.loc.column + leftLen
        return right.loc.column == expectedRightCol
    }

    // ---------- helpers for type/qualified names ----------
    private fun parseQualifiedName(): QualifiedName {
        val parts = mutableListOf<Identifier>()
        parts.add(Identifier(expectOneOf("expected identifier", TKind.IDENT, TKind.BACKTICK_IDENT).text))
        while (peek().kind == TKind.DOT) {
            consume()
            parts.add(Identifier(expectOneOf("expected identifier after '.'", TKind.IDENT, TKind.BACKTICK_IDENT).text))
        }
        return QualifiedName(parts)
    }

    private fun parseQualifiedNameWithLast(): Pair<QualifiedName, Token> {
        val parts = mutableListOf<Identifier>()
        var lastTok = expectOneOf("expected identifier", TKind.IDENT, TKind.BACKTICK_IDENT)
        parts.add(Identifier(lastTok.text))
        while (peek().kind == TKind.DOT) {
            consume() // DOT
            lastTok = expectOneOf("expected identifier after '.'", TKind.IDENT, TKind.BACKTICK_IDENT)
            parts.add(Identifier(lastTok.text))
        }
        return QualifiedName(parts) to lastTok
    }

    private fun parseTypeExpr(): TypeExpr {
        val name = parseQualifiedName()
        val args = mutableListOf<TypeExpr>()
        if (peek().kind == TKind.LT) {
            consume()
            args += parseTypeExpr()
            while (peek().kind == TKind.COMMA) { consume(); args += parseTypeExpr() }
            if (peek().kind != TKind.GT) throw ParseError("E5001", "expected '>' to close type parameters", peek().loc)
            consume()
        }
        return TypeExpr(name, args)
    }

    private fun parseQualifiers(): List<Qualifier> {
        val quals = mutableListOf<Qualifier>()
        while (true) {
            if (peek().kind == TKind.IDENT && (lookaheadKind(1) == TKind.IDENT || lookaheadKind(1) == TKind.BACKTICK_IDENT)) {
                val qname = consume().text
                val args = mutableListOf<QualifiedName>()
                args += parseQualifiedName()
                while (peek().kind == TKind.COMMA) { consume(); args += parseQualifiedName() }
                quals += Qualifier(Identifier(qname), args)
                // keep looping for more qualifiers on same line
                continue
            }
            // bare qualifier name without arguments is not allowed in qualifier position
            if (peek().kind == TKind.IDENT) {
                throw ParseError("E2101", "qualifier '${peek().text}' requires arguments", peek().loc)
            }
            // continuation only if next two tokens are NEWLINE and PIPE at column 1
            if (peek().kind == TKind.NEWLINE) {
                val next = lookaheadToken(1)
                if (next != null && next.kind == TKind.PIPE && next.loc.column == 1) {
                    consume() // NEWLINE
                    consume() // PIPE
                    continue
                } else if (next != null && next.kind == TKind.PIPE) {
                    throw ParseError("E1002", "line continuation '|' must be in column 1 immediately after NEWLINE", next.loc)
                }
            }
            break
        }
        return quals
    }

    // ---------- token buffering ----------
    private fun lookaheadKind(distance: Int): TKind? {
        while (buffer.size <= distance) buffer.addLast(lex.next())
        return buffer.elementAtOrNull(distance)?.kind
    }

    private fun lookaheadToken(distance: Int): Token? {
        while (buffer.size <= distance) buffer.addLast(lex.next())
        return buffer.elementAtOrNull(distance)
    }

    private var buffered: Token? = null // maintain compatibility for location()

    private fun consume(): Token {
        if (buffer.isNotEmpty()) return buffer.removeFirst().also { buffered = it }
        val t = lex.next()
        buffered = t
        return t
    }

    private fun peek(): Token {
        if (buffer.isEmpty()) buffer.addLast(lex.next())
        val t = buffer.first()
        buffered = t
        return t
    }

    private fun skipNewlines() {
        while (peek().kind == TKind.NEWLINE) consume()
    }

    private fun location(): Location = when (buffered) {
        null -> Location(1, 1, 0)
        else -> buffered!!.loc
    }
}
