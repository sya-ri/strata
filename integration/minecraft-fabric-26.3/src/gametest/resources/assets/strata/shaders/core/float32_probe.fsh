#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) noperspective in vec2 canvasUv;
layout(location = 0) out vec4 fragColor;
#include <strata:strata_float32.glsl>
uint probeWord(ivec2 position) {
    uvec4 bytes = uvec4(texelFetch(InSampler, position, 0) * 255.0 + 0.5);
    return bytes.r | (bytes.g << 8) | (bytes.b << 16) | (bytes.a << 24);
}
void main() {
    ivec2 extent = textureSize(InSampler, 0) / ivec2(3, 1);
    ivec2 pixel = clamp(ivec2(floor(canvasUv * vec2(extent))), ivec2(0), extent - 1);
    ivec2 inputPixel = ivec2(pixel.x * 3, pixel.y);
    uint operation = probeWord(inputPixel);
    uint a = probeWord(inputPixel + ivec2(1, 0));
    uint b = probeWord(inputPixel + ivec2(2, 0));
    uint result = operation == 0u ? add32(a, b) : operation == 1u ? multiply32(a, b) : operation == 2u ? divide32(a, b) : quantize32(a);
    fragColor = vec4(uvec4(result & 255u, (result >> 8) & 255u, (result >> 16) & 255u, result >> 24)) / 255.0;
}
