package io.github.ddsimoes.sd2

// Core public API for the SD2 streaming parser

interface Sd2Reader {
    fun next(): Sd2Event
    fun close()
}

data class Sd2ReaderConfig(
    val streamValues: Boolean = false,
    val allowRecovery: Boolean = false,
    val onError: ((Sd2Error) -> Unit)? = null,
    // Optional registry of constructor handlers. When provided, the parser will
    // attempt to resolve constructor calls into materialized objects (VObject).
    val constructorRegistry: ConstructorRegistry? = null,
    val unknownConstructorPolicy: UnknownConstructorPolicy = UnknownConstructorPolicy.KeepRaw,
)

data class Location(val line: Int, val column: Int, val offset: Int)

data class Sd2Error(val code: String, val message: String, val location: Location)

sealed class Sd2Event(open val location: Location) {
    data class StartDocument(override val location: Location) : Sd2Event(location)
    data class EndDocument(override val location: Location) : Sd2Event(location)
    data class DocumentAnnotation(val name: QualifiedName, val argsRaw: String?, override val location: Location) : Sd2Event(location)

    data class StartElement(
        val keyword: Identifier,
        val id: Identifier?,
        val type: TypeExpr? = null,
        val annotations: List<Annotation> = emptyList(),
        val qualifiers: List<Qualifier> = emptyList(),
        override val location: Location,
    ) : Sd2Event(location)

    data class EndElement(override val location: Location) : Sd2Event(location)

    data class StartNamespace(
        val name: Identifier,
        override val location: Location,
    ) : Sd2Event(location)
    data class EndNamespace(override val location: Location) : Sd2Event(location)

    data class Attribute(
        val name: Identifier,
        val value: Sd2Value?, // when streamValues=true this will be null and value events would follow (not yet implemented)
        override val location: Location,
    ) : Sd2Event(location)

    // Value streaming events (reserved for future use)
    data class StartList(override val location: Location) : Sd2Event(location)
    data class EndList(override val location: Location) : Sd2Event(location)
    data class StartMap(override val location: Location) : Sd2Event(location)
    data class EndMap(override val location: Location) : Sd2Event(location)
    data class TextChunk(val text: String, override val location: Location) : Sd2Event(location)
}

// Lightweight value model for materialized mode
sealed class Sd2Value(open val location: Location) {
    data class VString(val value: String, override val location: Location) : Sd2Value(location)
    data class VInt(val value: Long, override val location: Location) : Sd2Value(location)
    data class VFloat(val value: Double, override val location: Location) : Sd2Value(location)
    data class VBool(val value: Boolean, override val location: Location) : Sd2Value(location)
    data class VNull(override val location: Location) : Sd2Value(location)
    data class VQualifiedName(val parts: List<String>, override val location: Location) : Sd2Value(location) {
        override fun toString(): String = parts.joinToString(".")
    }
    data class VList(val items: List<Sd2Value>, override val location: Location) : Sd2Value(location)
    data class VMap(val entries: Map<String, Sd2Value>, override val location: Location) : Sd2Value(location)
    data class VConstructor(val name: QualifiedName, val attributes: Map<String, Sd2Value>, override val location: Location) : Sd2Value(location)
    data class VForeign(val content: String, override val location: Location, val constructor: QualifiedName? = null) : Sd2Value(location)
    // v0.7: tuple literal and tuple-constructor (positional)
    data class VTuple(val items: List<Sd2Value>, override val location: Location) : Sd2Value(location)
    data class VConstructorTuple(val name: QualifiedName, val args: List<Sd2Value>, override val location: Location) : Sd2Value(location)
    // Materialized value produced by a registered constructor handler
    data class VObject(val type: QualifiedName, val value: Any, override val location: Location) : Sd2Value(location)
}

// Simple syntax nodes referenced by events
data class Identifier(val text: String) { override fun toString(): String = text }

data class QualifiedName(val parts: List<Identifier>)
data class TypeExpr(val name: QualifiedName, val typeArgs: List<TypeExpr> = emptyList())

data class Qualifier(val name: Identifier, val args: List<QualifiedName>)
data class Annotation(val name: QualifiedName, val argsRaw: String? = null)

// Reader factory
object Sd2 {
    fun reader(
        source: Sd2Source,
        // Out-of-the-box temporals: default config uses the default temporal registry
        config: Sd2ReaderConfig = Sd2ReaderConfig(constructorRegistry = DefaultTemporalRegistry.instance)
    ): Sd2Reader = Sd2StreamReader(source, config)
}

// -------- Constructor Registry API --------

enum class UnknownConstructorPolicy { Error, KeepRaw }

data class ConstructorCall(
    val name: QualifiedName,
    val args: List<Sd2Value> = emptyList(),
    val attrs: Map<String, Sd2Value>? = null,
    val location: Location,
)

fun interface ConstructorHandler<T : Any> {
    fun invoke(call: ConstructorCall, ctx: ConstructorContext): T
}

data class RegisteredConstructor(val type: QualifiedName, val handler: ConstructorHandler<Any>)

interface ConstructorRegistry {
    fun handlerFor(name: QualifiedName): RegisteredConstructor?
}

class ConstructorRegistryBuilder {
    private val map = LinkedHashMap<List<String>, RegisteredConstructor>()

    fun register(name: String, handler: ConstructorHandler<Any>) = register(name, name, handler)

    fun register(name: String, typeTag: String, handler: ConstructorHandler<Any>) {
        val nameParts = name.split('.').filter { it.isNotEmpty() }
        val typeParts = typeTag.split('.').filter { it.isNotEmpty() }
        map[nameParts] = RegisteredConstructor(
            type = QualifiedName(typeParts.map { Identifier(it) }),
            handler = handler,
        )
    }

    fun build(): ConstructorRegistry = object : ConstructorRegistry {
        private val table = map.toMap()
        override fun handlerFor(name: QualifiedName): RegisteredConstructor? = table[name.parts.map { it.text }]
    }
}

interface ConstructorContext {
    fun resolve(value: Sd2Value): Sd2Value
    fun error(code: String, message: String, at: Location? = null): Nothing
}
