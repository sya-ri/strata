#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) noperspective out vec2 canvasUv;
layout(location = 1) flat out int compositionCommand;
void main() {
    int vertex = gl_VertexIndex % 3;
    vec2 uv = vec2((vertex << 1) & 2, vertex & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    canvasUv = uv;
    compositionCommand = gl_VertexIndex / 3;
}
