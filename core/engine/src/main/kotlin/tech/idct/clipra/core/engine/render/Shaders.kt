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

    /**
     * Blends a clip layer (uSrc, straight alpha) onto the canvas (uDst) with one of the 16 blend
     * modes, numbered by [blendIndex]. The formulas are those of the W3C Compositing
     * and Blending spec (b the canvas, s the clip); Hue, Color and Luminosity work on luminosity
     * and saturation as it defines them. Where the clip is transparent the canvas is unchanged.
     */
    const val BLEND = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D uDst;
        uniform sampler2D uSrc;
        uniform int uMode;
        varying vec2 vTex;

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

        void main() {
            vec4 d = texture2D(uDst, vTex);
            vec4 s = texture2D(uSrc, vTex);
            vec3 c = mix(d.rgb, clamp(blendColor(d.rgb, s.rgb), 0.0, 1.0), s.a);
            gl_FragColor = vec4(c, s.a + d.a * (1.0 - s.a));
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
