package tech.idct.clipra.core.engine.render

import tech.idct.clipra.core.model.BlendMode

/** GLSL ES 1.0 programs of the layer compositor. */
internal object Shaders {
    /** The number [BLEND] uses for [mode]. */
    fun blendIndex(mode: BlendMode): Int = when (mode) {
        BlendMode.Normal -> 0
        BlendMode.Multiply -> 1
        BlendMode.Screen -> 2
        BlendMode.Overlay -> 3
        BlendMode.SoftLight -> 4
        BlendMode.HardLight -> 5
        BlendMode.Darken -> 6
        BlendMode.Lighten -> 7
        BlendMode.Dodge -> 8
        BlendMode.Burn -> 9
        BlendMode.Add -> 10
        BlendMode.Difference -> 11
        BlendMode.Exclusion -> 12
        BlendMode.Hue -> 13
        BlendMode.Color -> 14
        BlendMode.Luminosity -> 15
    }

    /** A quad at [aPos] (NDC), sampling through [uTexMatrix] applied to unit coordinates. */
    const val VERTEX = """
        attribute vec4 aPos;
        attribute vec4 aUnit;
        uniform mat4 uTexMatrix;
        varying vec2 vTex;
        void main() {
            gl_Position = aPos;
            vTex = (uTexMatrix * aUnit).xy;
        }
    """

    /** A decoded video frame (external texture) with an opacity. */
    const val CLIP_EXTERNAL = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTex;
        uniform float uOpacity;
        varying vec2 vTex;
        void main() {
            vec4 c = texture2D(uTex, vTex);
            gl_FragColor = vec4(c.rgb, c.a * uOpacity);
        }
    """

    /** A bitmap (photo, text) with an opacity. */
    const val CLIP_2D = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform float uOpacity;
        varying vec2 vTex;
        void main() {
            vec4 c = texture2D(uTex, vTex);
            gl_FragColor = vec4(c.rgb, c.a * uOpacity);
        }
    """

    private const val PRECISION = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
    """

    /**
     * The 16 blend modes as `blendColor(b, s)` for the mode in `uMode`, numbered by
     * [blendIndex]. The formulas are those of the W3C Compositing and Blending spec (b the
     * backdrop, s the source); Hue, Color and Luminosity work on luminosity and saturation as it
     * defines them.
     */
    private const val BLEND_FUNCTIONS = """
        uniform int uMode;
        float overlay(float b, float s) {
            return b <= 0.5 ? 2.0 * b * s : 1.0 - 2.0 * (1.0 - b) * (1.0 - s);
        }
        // Dodge and Burn jump at 0 and 1; the inputs are 8-bit, so within half a level counts.
        const float HALF_LEVEL = 0.5 / 255.0;
        float dodge(float b, float s) {
            if (b <= HALF_LEVEL) return 0.0;
            if (s >= 1.0 - HALF_LEVEL) return 1.0;
            return min(1.0, b / (1.0 - s));
        }
        float burn(float b, float s) {
            if (b >= 1.0 - HALF_LEVEL) return 1.0;
            if (s <= HALF_LEVEL) return 0.0;
            return 1.0 - min(1.0, (1.0 - b) / s);
        }
        float softLight(float b, float s) {
            if (s <= 0.5) return b - (1.0 - 2.0 * s) * b * (1.0 - b);
            float d = b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
            return b + (2.0 * s - 1.0) * (d - b);
        }
        float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
        vec3 clipColor(vec3 c) {
            float l = lum(c);
            float n = min(c.r, min(c.g, c.b));
            float x = max(c.r, max(c.g, c.b));
            if (n < 0.0) c = l + (c - l) * l / (l - n);
            if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l);
            return c;
        }
        vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
        float sat(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
        vec3 setSat(vec3 c, float s) {
            float n = min(c.r, min(c.g, c.b));
            float range = sat(c);
            if (range <= 0.0) return vec3(0.0);
            return (c - n) * s / range;
        }

        vec3 blendColor(vec3 b, vec3 s) {
            if (uMode == 1) return b * s;
            if (uMode == 2) return b + s - b * s;
            if (uMode == 3) {
                return vec3(overlay(b.r, s.r), overlay(b.g, s.g), overlay(b.b, s.b));
            }
            if (uMode == 4) {
                return vec3(softLight(b.r, s.r), softLight(b.g, s.g), softLight(b.b, s.b));
            }
            if (uMode == 5) {
                return vec3(overlay(s.r, b.r), overlay(s.g, b.g), overlay(s.b, b.b));
            }
            if (uMode == 6) return min(b, s);
            if (uMode == 7) return max(b, s);
            if (uMode == 8) return vec3(dodge(b.r, s.r), dodge(b.g, s.g), dodge(b.b, s.b));
            if (uMode == 9) return vec3(burn(b.r, s.r), burn(b.g, s.g), burn(b.b, s.b));
            if (uMode == 10) return min(b + s, vec3(1.0));
            if (uMode == 11) return abs(b - s);
            if (uMode == 12) return b + s - 2.0 * b * s;
            if (uMode == 13) return setLum(setSat(s, sat(b)), lum(b));
            if (uMode == 14) return setLum(s, lum(b));
            if (uMode == 15) return setLum(b, lum(s));
            return s;
        }

    """

    /**
     * Blends a clip layer (uSrc, straight alpha) onto the canvas (uDst) with one of the 16 blend
     * modes. Where the clip is transparent the canvas is unchanged.
     */
    const val BLEND = PRECISION + BLEND_FUNCTIONS + """
        uniform sampler2D uDst;
        uniform sampler2D uSrc;
        varying vec2 vTex;
        void main() {
            vec4 d = texture2D(uDst, vTex);
            vec4 s = texture2D(uSrc, vTex);
            vec3 c = mix(d.rgb, clamp(blendColor(d.rgb, s.rgb), 0.0, 1.0), s.a);
            gl_FragColor = vec4(c, s.a + d.a * (1.0 - s.a));
        }
    """

    /**
     * One filter (#25) on a clip's picture (straight alpha, upright): it samples the input
     * (plain, in mosaic blocks, with the colours split, or glitched), applies the CSS filter
     * functions as one colour matrix, blends an overlay (gradient or pattern) on top with
     * `uMode`, and mixes the result with the input by strength. `FilterRecipes` sets the
     * uniforms; positions in the overlay are in fractions of the picture, y down, as in CSS.
     */
    const val FILTER = PRECISION + BLEND_FUNCTIONS + """
        uniform sampler2D uTex;
        uniform vec2 uSize;
        uniform mat4 uMatrix;
        uniform int uSampling;
        uniform vec2 uBlock;
        uniform float uTime;
        uniform int uOverlay;
        uniform vec4 uGradient;
        uniform vec4 uStops;
        uniform vec4 uColor0;
        uniform vec4 uColor1;
        uniform vec4 uColor2;
        uniform vec4 uColor3;
        uniform float uStrength;
        varying vec2 vTex;

        float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

        vec4 split(vec2 uv, float d) {
            vec4 c = texture2D(uTex, uv);
            c.r = texture2D(uTex, uv - vec2(d, 0.0)).r;
            c.b = texture2D(uTex, uv + vec2(d, 0.0)).b;
            return c;
        }

        vec4 sampleInput(vec2 uv) {
            if (uSampling == 1) return texture2D(uTex, (floor(uv / uBlock) + 0.5) * uBlock);
            if (uSampling == 2) return split(uv, 0.006);
            if (uSampling == 3) {
                // Some of 24 bands slide sideways with split colours, changing 8 times a second.
                float band = floor((1.0 - uv.y) * 24.0);
                float tick = floor(uTime * 8.0);
                float shift = hash(vec2(band, tick)) > 0.7
                    ? (hash(vec2(tick, band)) - 0.5) * 0.08 : 0.0;
                vec2 p = vec2(uv.x + shift, uv.y);
                return shift != 0.0 ? split(p, 0.01) : texture2D(uTex, p);
            }
            if (uSampling == 4) return split(uv, -0.003);
            return texture2D(uTex, uv);
        }

        vec4 premultiplied(vec4 c) { return vec4(c.rgb * c.a, c.a); }

        // Up to four stops, interpolated premultiplied as CSS does.
        vec4 gradientAt(float t) {
            vec4 c0 = premultiplied(uColor0);
            vec4 c1 = premultiplied(uColor1);
            vec4 c2 = premultiplied(uColor2);
            vec4 c3 = premultiplied(uColor3);
            vec4 p = c3;
            if (t <= uStops.x) {
                p = c0;
            } else if (t <= uStops.y) {
                p = mix(c0, c1, (t - uStops.x) / max(uStops.y - uStops.x, 1e-5));
            } else if (t <= uStops.z) {
                p = mix(c1, c2, (t - uStops.y) / max(uStops.z - uStops.y, 1e-5));
            } else if (t <= uStops.w) {
                p = mix(c2, c3, (t - uStops.z) / max(uStops.w - uStops.z, 1e-5));
            }
            return p.a > 0.0 ? vec4(p.rgb / p.a, p.a) : vec4(0.0);
        }

        vec4 overlayColor(vec2 uv) {
            if (uOverlay == 1) {
                // Linear: the gradient line runs corner to corner at its angle.
                vec2 dir = uGradient.xy;
                float span = abs(uSize.x * dir.x) + abs(uSize.y * dir.y);
                return gradientAt(dot((uv - 0.5) * uSize, dir) / span + 0.5);
            }
            if (uOverlay == 2) {
                // Radial, reaching the farthest corner.
                vec2 c = uGradient.xy;
                if (uGradient.z > 0.5) {
                    vec2 radii = max(c, 1.0 - c);
                    return gradientAt(length((uv - c) / radii) / sqrt(2.0));
                }
                vec2 far = max(c, 1.0 - c) * uSize;
                return gradientAt(length((uv - c) * uSize) / length(far));
            }
            if (uOverlay == 3) {
                float f = fract(uv.y * 16.0);
                if (f >= 0.4 && f < 0.5333) return vec4(0.0, 1.0, 0.918, 0.5);
                if (f >= 0.8667) return vec4(1.0, 0.0, 0.361, 0.5);
                return vec4(0.0);
            }
            if (uOverlay == 4) {
                return fract(uv.y * 80.0) < 0.3333 ? vec4(0.0, 0.0, 0.0, 0.4) : vec4(0.0);
            }
            if (uOverlay == 5) {
                float n = hash(floor(uv * uSize) + fract(uTime * 7.31) * 113.0);
                return vec4(vec3(n), 0.35);
            }
            if (uOverlay == 6) {
                float cell = min(uSize.x, uSize.y) / 30.0;
                vec2 local = mod(uv * uSize, cell) - cell * 0.5;
                float d = length(local) / cell;
                return vec4(0.0, 0.0, 0.0, 0.6 * (1.0 - smoothstep(0.24, 0.34, d)));
            }
            return vec4(0.0);
        }

        void main() {
            vec4 original = texture2D(uTex, vTex);
            vec4 c = sampleInput(vTex);
            vec3 rgb = clamp((uMatrix * vec4(c.rgb, 1.0)).rgb, 0.0, 1.0);
            if (uOverlay != 0) {
                vec4 o = overlayColor(vec2(vTex.x, 1.0 - vTex.y));
                rgb = mix(rgb, clamp(blendColor(rgb, o.rgb), 0.0, 1.0), o.a);
            }
            float alpha = mix(original.a, c.a, uStrength);
            gl_FragColor = vec4(mix(original.rgb, rgb, uStrength), alpha);
        }
    """

    /**
     * One direction of a Gaussian blur: 25 taps `uStep` apart (a quarter of the deviation), out
     * to three deviations.
     */
    const val BLUR = PRECISION + """
        uniform sampler2D uTex;
        uniform vec2 uStep;
        varying vec2 vTex;
        void main() {
            vec4 sum = vec4(0.0);
            float total = 0.0;
            for (int i = -12; i <= 12; i++) {
                float x = float(i) * 0.25;
                float w = exp(-0.5 * x * x);
                sum += texture2D(uTex, vTex + uStep * float(i)) * w;
                total += w;
            }
            gl_FragColor = sum / total;
        }
    """

    /** Copies the canvas to the output. */
    const val COPY = """
        precision mediump float;
        uniform sampler2D uTex;
        varying vec2 vTex;
        void main() {
            gl_FragColor = texture2D(uTex, vTex);
        }
    """
}
