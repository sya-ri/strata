// IEEE-754 binary32 arithmetic for nonnegative normal finite values and positive zero.
// Sampled source-over intermediates remain finite and below 256; its smallest nonzero product is
// (1 / 255)^4, so this kernel never requires subnormal, signed, infinite or NaN inputs.
// GLSL 1.50 has 32-bit uint. Every product below fits without unsigned overflow;
// all shifts have a count in [0, 31], including sticky alignment of remote exponents.
uint pack32(uint mantissa, int exponent) {
    if (mantissa == 0x1000000u) {
        mantissa >>= 1;
        exponent += 1;
    }
    return (uint(exponent) << 23) | (mantissa & 0x7fffffu);
}
uint shiftSticky(uint value, uint distance) {
    if (distance == 0u) return value;
    if (32u <= distance) return uint(value != 0u);
    uint mask = (1u << distance) - 1u;
    return (value >> distance) | uint((value & mask) != 0u);
}
uint add32(uint a, uint b) {
    if (a == 0u) return b;
    if (b == 0u) return a;
    uint largest = max(a, b);
    uint smallest = min(a, b);
    int exponent = int(largest >> 23);
    uint distance = uint(exponent - int(smallest >> 23));
    uint m = ((largest & 0x7fffffu) | 0x800000u) << 3;
    uint n = ((smallest & 0x7fffffu) | 0x800000u) << 3;
    // The sum is less than 2^28, including guard, round and sticky bits.
    uint sum = m + shiftSticky(n, distance);
    if (0x8000000u <= sum) {
        sum = shiftSticky(sum, 1u);
        exponent += 1;
    }
    uint mantissa = sum >> 3;
    uint remainder = sum & 7u;
    if (4u < remainder || (remainder == 4u && (mantissa & 1u) != 0u)) mantissa += 1u;
    return pack32(mantissa, exponent);
}
uint multiply32(uint a, uint b) {
    if (a == 0u || b == 0u) return 0u;
    if (a == 0x3f800000u) return b;
    if (b == 0x3f800000u) return a;
    uint m = (a & 0x7fffffu) | 0x800000u;
    uint n = (b & 0x7fffffu) | 0x800000u;
    uint m0 = m & 4095u;
    uint m1 = m >> 12;
    uint n0 = n & 4095u;
    uint n1 = n >> 12;
    // p0 < 2^24, middle < 2^25, high < 2^24; no uint multiplication overflows.
    uint p0 = m0 * n0;
    uint middle = m0 * n1 + m1 * n0 + (p0 >> 12);
    uint low = (p0 & 4095u) | ((middle & 4095u) << 12);
    uint high = m1 * n1 + (middle >> 12);
    bool upper = (high & 0x800000u) != 0u;
    int exponent = int(a >> 23) + int(b >> 23) - 127 + int(upper);
    uint mantissa = upper ? high : (high << 1) | (low >> 23);
    uint remainder = upper ? low : low & 0x7fffffu;
    uint halfway = upper ? 0x800000u : 0x400000u;
    if (halfway < remainder || (remainder == halfway && (mantissa & 1u) != 0u)) mantissa += 1u;
    return pack32(mantissa, exponent);
}
uint divide32(uint a, uint b) {
    if (a == 0u) return 0u;
    // Opaque destinations produce exactly 1.0 alpha; division then preserves every input bit.
    if (b == 0x3f800000u) return a;
    uint numerator = (a & 0x7fffffu) | 0x800000u;
    uint denominator = (b & 0x7fffffu) | 0x800000u;
    int exponent = int(a >> 23) - int(b >> 23) + 127;
    if (numerator < denominator) {
        numerator <<= 1;
        exponent -= 1;
    }
    uint remainder = numerator - denominator;
    uint mantissa = 0x800000u;
    // Remainder < denominator < 2^24; each doubling remains below 2^25.
    for (int bit = 22; 0 <= bit; bit -= 1) {
        remainder <<= 1;
        if (denominator <= remainder) {
            remainder -= denominator;
            mantissa |= 1u << uint(bit);
        }
    }
    uint doubled = remainder << 1;
    if (denominator < doubled || (doubled == denominator && (mantissa & 1u) != 0u)) mantissa += 1u;
    return pack32(mantissa, exponent);
}
uint quantize32(uint value) {
    if (value == 0u) return 0u;
    uint scaled = multiply32(value, 0x437f0000u); // exact binary32 255.0
    int exponent = int(scaled >> 23) - 127;
    if (exponent < -1) return 0u;
    uint distance = uint(23 - exponent); // 16 through 24 for [0.5, 255]
    uint mantissa = (scaled & 0x7fffffu) | 0x800000u;
    return min(255u, (mantissa + (1u << (distance - 1u))) >> distance);
}
