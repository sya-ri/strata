#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D IndexSampler;
layout(location = 0) noperspective in vec2 canvasUv;
layout(location = 0) out vec4 fragColor;
int indexAt(ivec2 position) {
    ivec4 bytes = ivec4(floor(texelFetch(IndexSampler, position, 0) * 255.0 + 0.5));
    return bytes.x | (bytes.y << 8) | (bytes.z << 16) | (bytes.w << 24);
}
void main() {
    ivec2 targetExtent = ivec2(indexAt(ivec2(0, 2)), indexAt(ivec2(1, 2))) - 1;
    ivec2 pixel = clamp(ivec2(floor(canvasUv * vec2(targetExtent))), ivec2(0), targetExtent - 1);
    ivec2 source = ivec2(indexAt(ivec2(pixel.x, 0)), indexAt(ivec2(pixel.y, 1))) - 1;
    if (any(lessThan(source, ivec2(0)))) {
        fragColor = vec4(0.0);
    } else {
        fragColor = texelFetch(InSampler, source, 0);
    }
}
