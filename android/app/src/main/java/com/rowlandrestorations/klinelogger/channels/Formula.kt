package com.rowlandrestorations.klinelogger.channels

import kotlin.math.floor
import kotlin.math.pow

class FormulaException(message: String) : IllegalArgumentException(message)

/**
 * A channel formula from pids.json, compiled once. Covers the Python expression subset that
 * kline.py's PidDef.decode allows:
 *   variables  A B C D E F G H (data bytes), raw, sraw, n, d[i]
 *   operators  + - * / // % ** << >> & | ^ and parentheses
 *   functions  abs min max round int float signed(v, bits) math.*
 */
class Formula(val source: String) {
    private val root: (Env) -> Double = Parser(source).parse()

    fun eval(data: ByteArray): Double = root(Env(data))

    class Env(val data: ByteArray) {
        val n = data.size
        val raw: Double = data.fold(0.0) { acc, b -> acc * 256 + (b.toInt() and 0xFF) }
        val sraw: Double = if (n == 0) 0.0 else signed(raw, 8 * n)
        fun byte(i: Int): Double = if (i in data.indices) (data[i].toInt() and 0xFF).toDouble() else 0.0
    }

    private class Parser(private val text: String) {
        private val tokens = tokenize(text)
        private var pos = 0

        fun parse(): (Env) -> Double {
            if (tokens.isEmpty()) throw FormulaException("Formula is empty")
            val e = bitOr()
            if (pos < tokens.size) throw FormulaException("Unexpected '${tokens[pos]}' in $text")
            return e
        }

        private fun peek() = tokens.getOrNull(pos)
        private fun accept(t: String): Boolean = (peek() == t).also { if (it) pos++ }
        private fun expect(t: String) {
            if (!accept(t)) throw FormulaException("Expected '$t' in $text")
        }

        private fun binary(next: () -> (Env) -> Double, vararg ops: String): (Env) -> Double {
            var left = next()
            while (peek() in ops) {
                val op = tokens[pos++]
                val l = left
                val r = next()
                left = when (op) {
                    "+" -> { e -> l(e) + r(e) }
                    "-" -> { e -> l(e) - r(e) }
                    "*" -> { e -> l(e) * r(e) }
                    "/" -> { e -> l(e) / r(e) }
                    "//" -> { e -> floor(l(e) / r(e)) }
                    "%" -> { e -> val b = r(e); val a = l(e); a - b * floor(a / b) }
                    "<<" -> { e -> (l(e).toLong() shl r(e).toInt()).toDouble() }
                    ">>" -> { e -> (l(e).toLong() shr r(e).toInt()).toDouble() }
                    "&" -> { e -> (l(e).toLong() and r(e).toLong()).toDouble() }
                    "^" -> { e -> (l(e).toLong() xor r(e).toLong()).toDouble() }
                    else -> { e -> (l(e).toLong() or r(e).toLong()).toDouble() }
                }
            }
            return left
        }

        private fun bitOr(): (Env) -> Double = binary(::bitXor, "|")
        private fun bitXor(): (Env) -> Double = binary(::bitAnd, "^")
        private fun bitAnd(): (Env) -> Double = binary(::shift, "&")
        private fun shift(): (Env) -> Double = binary(::additive, "<<", ">>")
        private fun additive(): (Env) -> Double = binary(::multiplicative, "+", "-")
        private fun multiplicative(): (Env) -> Double = binary(::unary, "*", "/", "//", "%")

        private fun unary(): (Env) -> Double = when {
            accept("-") -> unary().let { u -> { e -> -u(e) } }
            accept("+") -> unary()
            else -> power()
        }

        private fun power(): (Env) -> Double {
            val base = primary()
            if (!accept("**")) return base
            val exp = unary()
            return { e -> base(e).pow(exp(e)) }
        }

        private fun primary(): (Env) -> Double {
            val t = peek() ?: throw FormulaException("Formula ends too early: $text")
            pos++
            if (t == "(") {
                val inner = bitOr()
                expect(")")
                return inner
            }
            t.toNumberOrNull()?.let { v -> return { v } }
            if (accept("(")) return call(t, args())
            if (t == "d") {
                expect("[")
                val index = bitOr()
                expect("]")
                return { e -> e.byte(index(e).toInt()) }
            }
            return variable(t)
        }

        private fun args(): List<(Env) -> Double> {
            val out = mutableListOf<(Env) -> Double>()
            if (accept(")")) return out
            do out += bitOr() while (accept(","))
            expect(")")
            return out
        }

        private fun variable(name: String): (Env) -> Double {
            if (name.length == 1 && name[0] in 'A'..'H') {
                val i = name[0] - 'A'
                return { e -> e.byte(i) }
            }
            return when (name) {
                "raw" -> { e -> e.raw }
                "sraw" -> { e -> e.sraw }
                "n" -> { e -> e.n.toDouble() }
                "math.pi" -> { _ -> Math.PI }
                "math.e" -> { _ -> Math.E }
                else -> throw FormulaException("Unknown name '$name' in $text")
            }
        }

        private fun call(name: String, a: List<(Env) -> Double>): (Env) -> Double {
            fun arity(n: Int) {
                if (a.size != n) throw FormulaException("$name() takes $n argument(s) in $text")
            }
            fun one(f: (Double) -> Double): (Env) -> Double {
                arity(1)
                val x = a[0]
                return { e -> f(x(e)) }
            }
            fun two(f: (Double, Double) -> Double): (Env) -> Double {
                arity(2)
                val x = a[0]
                val y = a[1]
                return { e -> f(x(e), y(e)) }
            }
            return when (name) {
                "abs" -> one { Math.abs(it) }
                "int" -> one { it.toLong().toDouble() }
                "float" -> one { it }
                "min" -> {
                    if (a.isEmpty()) throw FormulaException("min() needs arguments in $text")
                    val f: (Env) -> Double = { e -> a.minOf { it(e) } }
                    f
                }
                "max" -> {
                    if (a.isEmpty()) throw FormulaException("max() needs arguments in $text")
                    val f: (Env) -> Double = { e -> a.maxOf { it(e) } }
                    f
                }
                "round" -> when (a.size) {
                    1 -> one { Math.rint(it) }
                    2 -> two { v, digits -> val m = 10.0.pow(digits); Math.rint(v * m) / m }
                    else -> throw FormulaException("round() takes 1 or 2 arguments in $text")
                }
                "signed" -> two { v, bits -> signed(v, bits.toInt()) }
                "math.sqrt" -> one { Math.sqrt(it) }
                "math.exp" -> one { Math.exp(it) }
                "math.log" -> if (a.size == 2) two { v, base -> Math.log(v) / Math.log(base) } else one { Math.log(it) }
                "math.log10" -> one { Math.log10(it) }
                "math.floor" -> one { Math.floor(it) }
                "math.ceil" -> one { Math.ceil(it) }
                "math.sin" -> one { Math.sin(it) }
                "math.cos" -> one { Math.cos(it) }
                "math.tan" -> one { Math.tan(it) }
                "math.pow" -> two { x, y -> x.pow(y) }
                else -> throw FormulaException("Unknown function '$name' in $text")
            }
        }
    }

    companion object {
        private val TOKEN = Regex(
            """\s*(0[xX][0-9a-fA-F]+|\d+\.?\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?|[A-Za-z_][A-Za-z_0-9.]*|\*\*|//|<<|>>|[-+*/%()\[\],&|^])""",
        )

        private fun tokenize(text: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                if (text[i].isWhitespace()) {
                    i++
                    continue
                }
                val m = TOKEN.matchAt(text, i) ?: throw FormulaException("Can't read '${text.substring(i)}'")
                out += m.groupValues[1]
                i = m.range.last + 1
            }
            return out
        }

        private fun String.toNumberOrNull(): Double? =
            if (startsWith("0x") || startsWith("0X")) substring(2).toLong(16).toDouble()
            else if (first().isDigit() || first() == '.') toDoubleOrNull()
            else null

        fun signed(value: Double, bits: Int): Double {
            if (bits !in 1..63) throw FormulaException("signed() needs 1-63 bits")
            val v = value.toLong() and ((1L shl bits) - 1)
            return (if (v >= 1L shl (bits - 1)) v - (1L shl bits) else v).toDouble()
        }
    }
}
