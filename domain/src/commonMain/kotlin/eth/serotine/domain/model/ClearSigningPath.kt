package eth.serotine.domain.model

/**
 * A path of ERC-7730 into a call or a message (docs/clear-signing.md, A3): `params.amountIn`,
 * `orders.[].offerer`, `data.[0:4]`, `#.to` (the arguments' root, as a bare path), or `@.value` (the
 * transaction around the call). A descriptor's `$.metadata` references are resolved when the catalog is
 * read, so none reaches a [ClearSigningPath].
 */
data class ClearSigningPath(val container: Boolean, val steps: List<Step>) {
    /** One step of a path. */
    sealed interface Step {
        /** A member of a tuple or a struct, by its name. */
        data class Member(val name: String) : Step

        /** Every item of an array: `[]`. */
        data object All : Step

        /** One item of an array, from the end when negative: `[0]`, `[-1]`. */
        data class Index(val index: Int) : Step

        /** A range of an array's items or of bytes: `[a:b]`, either end open, negative from the end. */
        data class Slice(val from: Int?, val to: Int?) : Step
    }

    override fun toString(): String = (if (container) "@." else "") + steps.joinToString(".") {
        when (it) {
            is Step.Member -> it.name
            Step.All -> "[]"
            is Step.Index -> "[${it.index}]"
            is Step.Slice -> "[${it.from ?: ""}:${it.to ?: ""}]"
        }
    }

    companion object {
        /** [text] parsed, or null when it is not a path this runtime evaluates (a `$.` reference among them). */
        fun parse(text: String): ClearSigningPath? {
            val container = text.startsWith("@.")
            val body = when {
                container -> text.removePrefix("@.")
                text.startsWith("#.") -> text.removePrefix("#.")
                text.startsWith("$.") -> return null
                else -> text
            }
            val steps = body.split('.').map { segment ->
                when {
                    segment == "[]" -> Step.All
                    INDEX.matches(segment) -> Step.Index(segment.drop(1).dropLast(1).toIntOrNull() ?: return null)
                    SLICE.matches(segment) -> {
                        val (from, to) = segment.drop(1).dropLast(1).split(':')
                        Step.Slice(from.ifEmpty { null }?.toIntOrNull(), to.ifEmpty { null }?.toIntOrNull())
                    }
                    NAME.matches(segment) -> Step.Member(segment)
                    else -> return null
                }
            }
            if (container && (steps.size != 1 || (steps[0] as? Step.Member)?.name !in CONTAINER)) return null
            return ClearSigningPath(container, steps)
        }

        /** What `@.` names: the transaction's sender, recipient, value and chain id. */
        val CONTAINER = setOf("from", "to", "value", "chainId")

        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val INDEX = Regex("\\[-?\\d+]")
        private val SLICE = Regex("\\[-?\\d*:-?\\d*]")
    }
}

/** A value a path reached, with the concrete [location] it was found at (`orders.[2].offerer`). */
data class Reached(val location: String, val value: AbiValue)

/** Evaluates [ClearSigningPath]s against a decoded call or message and the transaction around it. */
class ClearSigningValues(
    /** The call's arguments, or the message, as one tuple. */
    val root: AbiValue.TupleValue,
    /** What `@.` names: `from`, `to`, `value`, `chainId`, each present only where it applies. */
    val container: Map<String, AbiValue>,
) {
    /** Every value [path] reaches, in order; null when it reaches nothing (a name not there, an index out of range). */
    fun evaluate(path: ClearSigningPath): List<Reached>? {
        if (path.container) {
            val name = (path.steps.single() as ClearSigningPath.Step.Member).name
            return container[name]?.let { listOf(Reached("@.$name", it)) }
        }
        var reached = listOf(Reached("", root as AbiValue))
        for (step in path.steps) {
            reached = reached.flatMap { step(it, step) ?: return null }
        }
        return reached
    }

    private fun step(at: Reached, step: ClearSigningPath.Step): List<Reached>? {
        val value = at.value
        fun located(suffix: String, next: AbiValue) = Reached(if (at.location.isEmpty()) suffix else "${at.location}.$suffix", next)
        return when (step) {
            is ClearSigningPath.Step.Member -> {
                val members = (value as? AbiValue.TupleValue)?.members ?: return null
                val member = members.firstOrNull { it.first == step.name } ?: return null
                listOf(located(step.name, member.second))
            }
            ClearSigningPath.Step.All -> {
                val items = (value as? AbiValue.ArrayValue)?.items ?: return null
                items.mapIndexed { i, item -> located("[$i]", item) }
            }
            is ClearSigningPath.Step.Index -> {
                val items = (value as? AbiValue.ArrayValue)?.items ?: return null
                val index = if (step.index < 0) items.size + step.index else step.index
                items.getOrNull(index)?.let { listOf(located("[$index]", it)) }
            }
            is ClearSigningPath.Step.Slice -> when (value) {
                is AbiValue.ArrayValue -> {
                    val range = range(step, value.items.size) ?: return null
                    listOf(located("[${range.first}:${range.last + 1}]", AbiValue.ArrayValue(value.items.slice(range))))
                }
                is AbiValue.BytesValue -> {
                    val bytes = value.bytes
                    val range = range(step, bytes.size) ?: return null
                    listOf(located("[${range.first}:${range.last + 1}]", AbiValue.BytesValue(bytes.sliceArray(range).toHex(), fixed = false)))
                }
                else -> null
            }
        }
    }

    private fun range(slice: ClearSigningPath.Step.Slice, size: Int): IntRange? {
        fun at(index: Int?, open: Int) = when {
            index == null -> open
            index < 0 -> size + index
            else -> index
        }
        val from = at(slice.from, 0)
        val to = at(slice.to, size)
        return if (from in 0..size && to in from..size) from until to else null
    }

    /** Every leaf of the call or the message with its location, in order: what "Other parameters" draws from. */
    fun leaves(): List<Reached> {
        val out = mutableListOf<Reached>()
        fun walk(location: String, value: AbiValue) {
            fun child(suffix: String) = if (location.isEmpty()) suffix else "$location.$suffix"
            when (value) {
                is AbiValue.TupleValue -> value.members.forEachIndexed { i, (name, member) -> walk(child(name ?: "[$i]"), member) }
                is AbiValue.ArrayValue ->
                    if (value.items.isEmpty()) out += Reached(location, value)
                    else value.items.forEachIndexed { i, item -> walk(child("[$i]"), item) }
                else -> out += Reached(location, value)
            }
        }
        walk("", root)
        return out
    }
}
