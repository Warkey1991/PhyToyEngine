#version 450

layout(std430, set = 0, binding = 0) readonly buffer InputBuffer {
    float input_pixels[];
};

layout(push_constant) uniform Presentation {
    uint source_width;
    uint source_height;
    uint output_width;
    uint output_height;
    uint rotation_degrees;
    uint srgb_attachment;
} presentation;

layout(location = 0) out vec4 output_color;

vec3 fetch_encoded(ivec2 coordinate) {
    coordinate = clamp(
        coordinate,
        ivec2(0),
        ivec2(presentation.source_width, presentation.source_height) - ivec2(1));
    int offset = (coordinate.y * int(presentation.source_width) + coordinate.x) * 3;
    return vec3(
        input_pixels[offset],
        input_pixels[offset + 1],
        input_pixels[offset + 2]);
}

vec3 sample_encoded(vec2 source_uv) {
    vec2 pixel = source_uv * vec2(presentation.source_width, presentation.source_height) - 0.5;
    ivec2 base = ivec2(floor(pixel));
    vec2 weight = fract(pixel);
    vec3 top = mix(fetch_encoded(base), fetch_encoded(base + ivec2(1, 0)), weight.x);
    vec3 bottom = mix(
        fetch_encoded(base + ivec2(0, 1)),
        fetch_encoded(base + ivec2(1, 1)),
        weight.x);
    return mix(top, bottom, weight.y);
}

vec3 srgb_decode(vec3 encoded) {
    bvec3 low = lessThanEqual(encoded, vec3(0.04045));
    vec3 linear_low = encoded / 12.92;
    vec3 linear_high = pow((encoded + 0.055) / 1.055, vec3(2.4));
    return mix(linear_high, linear_low, low);
}

void main() {
    vec2 output_uv = gl_FragCoord.xy /
        vec2(presentation.output_width, presentation.output_height);
    bool quarter_turn = presentation.rotation_degrees == 90u ||
        presentation.rotation_degrees == 270u;
    vec2 rotated_size = quarter_turn
        ? vec2(presentation.source_height, presentation.source_width)
        : vec2(presentation.source_width, presentation.source_height);
    float source_aspect = rotated_size.x / rotated_size.y;
    float output_aspect = float(presentation.output_width) /
        float(presentation.output_height);

    vec2 rotated_uv = output_uv;
    if (source_aspect > output_aspect) {
        rotated_uv.x = (rotated_uv.x - 0.5) * output_aspect / source_aspect + 0.5;
    } else {
        rotated_uv.y = (rotated_uv.y - 0.5) * source_aspect / output_aspect + 0.5;
    }

    vec2 source_uv = rotated_uv;
    if (presentation.rotation_degrees == 90u) {
        source_uv = vec2(rotated_uv.y, 1.0 - rotated_uv.x);
    } else if (presentation.rotation_degrees == 180u) {
        source_uv = vec2(1.0 - rotated_uv.x, 1.0 - rotated_uv.y);
    } else if (presentation.rotation_degrees == 270u) {
        source_uv = vec2(1.0 - rotated_uv.y, rotated_uv.x);
    }

    vec3 encoded = clamp(sample_encoded(source_uv), 0.0, 1.0);
    output_color = vec4(
        presentation.srgb_attachment != 0u ? srgb_decode(encoded) : encoded,
        1.0);
}
