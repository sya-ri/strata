#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform CanvasCapture { ivec3 CanvasTargetExtent; };
layout(location = 0) noperspective in vec2 canvasUv;
layout(location = 0) out vec4 fragColor;
void main() {
    ivec2 extent = textureSize(InSampler, 0);
    ivec2 targetExtent = CanvasTargetExtent.xy;
    ivec2 destinationPixel = clamp(ivec2(floor(canvasUv * vec2(targetExtent))), ivec2(0), targetExtent - 1);
    ivec2 pixel = ((destinationPixel * 2 + 1) * extent) / (targetExtent * 2);
    fragColor = texelFetch(InSampler, ivec2(pixel.x, extent.y - pixel.y - 1), 0);
}
