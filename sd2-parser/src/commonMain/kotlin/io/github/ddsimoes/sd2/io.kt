package io.github.ddsimoes.sd2

// Simple cross-platform character source abstraction

interface Sd2Source {
    fun read(): Int // returns next UTF-16 code unit or -1 for EOF
}

class StringSource(private val s: String) : Sd2Source {
    private var i = 0
    override fun read(): Int = if (i < s.length) s[i++].code else -1
}

