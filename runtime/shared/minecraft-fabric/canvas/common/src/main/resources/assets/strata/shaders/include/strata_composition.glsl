// Each invocation writes the entire RGBA8 destination, preserving previous pixels outside coverage.
uint commandWord(int x, int row) {
    uvec4 b = uvec4(floor(texelFetch(IndexSampler, ivec2(x, row), 0) * 255.0 + 0.5));
    return b.x | (b.y << 8) | (b.z << 16) | (b.w << 24);
}
uint factor32(uint channel, int component, int tintRow) {
    uvec4 b = uvec4(floor(texelFetch(FactorSampler, ivec2(int(channel) + component * 256, tintRow), 0) * 255.0 + 0.5));
    return b.x | (b.y << 8) | (b.z << 16) | (b.w << 24);
}
uvec4 rgbaBytes(vec4 color) { return uvec4(floor(color * 255.0 + 0.5)); }
uvec4 argbBytes(uint argb) { return uvec4((argb >> 16) & 255u, (argb >> 8) & 255u, argb & 255u, argb >> 24); }
uvec4 integerOver(uvec4 source, uvec4 destination) {
    if (source.a == 255u) return source;
    if (source.a == 0u) return destination.a == 0u ? uvec4(0u) : destination;
    if (destination.a == 0u) return source;
    uint inverse = 255u - source.a;
    uint denominator = source.a * 255u + destination.a * inverse;
    // Weights sum to at most 65025, and every rounded channel numerator is below 16613888.
    uvec3 numerator = source.rgb * source.a * 255u + destination.rgb * destination.a * inverse;
    return uvec4((numerator + denominator / 2u) / denominator, (denominator + 127u) / 255u);
}
uvec4 sampledOver(uvec4 source, uvec4 destination, uint cutoff, int tintRow) {
    uint alpha = factor32(source.a, 1, tintRow);
    // Positive finite binary32 bit patterns have the same ordering as their uint representation.
    if (alpha == 0u || alpha < cutoff) return destination;
    uint weight = multiply32(factor32(destination.a, 0, tintRow), factor32(source.a, 2, tintRow));
    uint outputAlpha = add32(alpha, weight);
    uint alphaByte = quantize32(outputAlpha);
    if (alphaByte == 0u) return uvec4(0u);
    uvec3 outputColor;
    for (int channel = 0; channel < 3; channel += 1) {
        uint contribution = multiply32(factor32(source[channel], channel + 3, tintRow), alpha);
        uint background = multiply32(factor32(destination[channel], 0, tintRow), weight);
        outputColor[channel] = quantize32(divide32(add32(contribution, background), outputAlpha));
    }
    return uvec4(outputColor, alphaByte);
}
void main() {
    ivec2 extent = textureSize(DestinationSampler, 0);
    ivec2 pixel = clamp(ivec2(floor(canvasUv * vec2(extent))), ivec2(0), extent - 1);
    int row = compositionCommand * 3;
    uvec4 previous = rgbaBytes(texelFetch(DestinationSampler, pixel, 0));
    ivec2 sourcePixel = ivec2(int(commandWord(pixel.x, row)), int(commandWord(pixel.y, row + 1))) - 1;
    if (any(lessThan(sourcePixel, ivec2(0)))) {
        fragColor = vec4(previous) / 255.0;
        return;
    }
    uint kind = commandWord(0, row + 2);
    uvec4 source = kind == 0u ? argbBytes(commandWord(1, row + 2)) : rgbaBytes(texelFetch(InSampler, sourcePixel, 0));
    uvec4 outputColor;
    if (kind == 2u) {
        outputColor = sampledOver(source, previous, commandWord(2, row + 2), int(commandWord(3, row + 2)));
    } else {
        outputColor = integerOver(source, previous);
    }
    fragColor = vec4(outputColor) / 255.0;
}
