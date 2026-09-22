package dev.s7a.strata.projection

/**
 * Strict positional schema reader used at the transport and extension boundaries.
 * Consumers must call [finish] so extra fields cannot silently alter protocol meaning.
 */
public class ProjectionFields(
    value: ProjectionValue,
) {
    private val fields: List<ProjectionValue> = requireNotNull(value as? ProjectionValue.Sequence) { "Expected projection fields." }.values
    private var position: Int = 0

    /**
     * Reads one schema field, rejecting truncation.
     */
    public fun value(): ProjectionValue {
        require(position < fields.size) { "Missing projection field." }
        return fields[position++]
    }

    /**
     * Reads one signed integer.
     */
    public fun long(): Long = requireNotNull(value() as? ProjectionValue.Integer) { "Expected projection integer." }.value

    /**
     * Reads one integer within the caller's accepted range.
     */
    public fun int(range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int {
        val value = long()
        require(value in range.first.toLong()..range.last.toLong()) { "Projection integer is outside its range." }
        return value.toInt()
    }

    /**
     * Reads one UTF-8 text field already validated by the binary codec.
     */
    public fun text(): String = requireNotNull(value() as? ProjectionValue.Text) { "Expected projection text." }.value

    /**
     * Reads one boolean field.
     */
    public fun flag(): Boolean = requireNotNull(value() as? ProjectionValue.Flag) { "Expected projection boolean." }.value

    /**
     * Reads one finite real field.
     */
    public fun real(): Double = requireNotNull(value() as? ProjectionValue.Real) { "Expected projection real." }.value

    /**
     * Reads one nested field sequence.
     */
    public fun values(): List<ProjectionValue> = requireNotNull(value() as? ProjectionValue.Sequence) { "Expected projection sequence." }.values

    /**
     * Rejects additional undeclared fields.
     */
    public fun finish() {
        require(position == fields.size) { "Unexpected projection fields." }
    }
}
