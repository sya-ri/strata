#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;
int indexAt(ivec2 position) {
    ivec4 bytes = ivec4(floor(texelFetch(Sampler1, position, 0) * 255.0 + 0.5));
    return bytes.x | (bytes.y << 8) | (bytes.z << 16) | (bytes.w << 24);
}
void main() {
    ivec2 targetExtent = ivec2(indexAt(ivec2(0, 2)), indexAt(ivec2(1, 2))) - 1;
    ivec2 pixel = clamp(ivec2(floor(texCoord0 * vec2(targetExtent))), ivec2(0), targetExtent - 1);
    ivec2 source = ivec2(indexAt(ivec2(pixel.x, 0)), indexAt(ivec2(pixel.y, 1))) - 1;
    if (any(lessThan(source, ivec2(0)))) {
        fragColor = vec4(0.0);
    } else {
        fragColor = texelFetch(Sampler0, source, 0) * vertexColor * ColorModulator;
    }
}

