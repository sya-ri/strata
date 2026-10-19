#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D DestinationSampler;
uniform sampler2D IndexSampler;
uniform sampler2D FactorSampler;
layout(location = 0) noperspective in vec2 canvasUv;
layout(location = 1) flat in int compositionCommand;
layout(location = 0) out vec4 fragColor;
#include <strata:strata_float32.glsl>
#include <strata:strata_composition.glsl>
