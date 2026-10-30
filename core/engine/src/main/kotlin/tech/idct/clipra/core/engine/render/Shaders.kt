package tech.idct.clipra.core.engine.render

import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.Transitions

/** GLSL ES 1.0 programs of the layer compositor. */
internal object Shaders {
    /** The number [TRANSITION] uses for [type]: its place in `Transitions.all`. */
    fun transitionIndex(type: String): Int = Transitions.all.indexOf(type)

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
     * A clip's colour grade (#28), a line-by-line port of `ColorMath`: exposure and white
     * balance gains, contrast, saturation, the eight HSL ranges, a LUT (its slices laid out in a
     * grid of tiles, blended between two slices) at its strength, and the vignette. Left of
     * [uSplit] the picture shows as it was, for the Color board's before and after.
     */
    const val GRADE = PRECISION + """
        uniform sampler2D uTex;
        uniform sampler2D uLut;
        uniform vec3 uGain;
        uniform float uContrast;
        uniform float uSaturation;
        uniform int uHasHsl;
        uniform vec3 uH0;
        uniform vec3 uH1;
        uniform vec3 uH2;
        uniform vec3 uH3;
        uniform vec3 uH4;
        uniform vec3 uH5;
        uniform vec3 uH6;
        uniform vec3 uH7;
        uniform int uHasLut;
        uniform float uLutSize;
        uniform vec2 uLutGrid;
        uniform vec3 uLutMin;
        uniform vec3 uLutMax;
        uniform float uLutStrength;
        uniform float uVignette;
        uniform float uSplit;
        varying vec2 vTex;

        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

        float rangeDegrees(int i) {
            if (i == 0) return 0.0;
            if (i == 1) return 30.0;
            if (i == 2) return 60.0;
            if (i == 3) return 120.0;
            if (i == 4) return 180.0;
            if (i == 5) return 225.0;
            if (i == 6) return 270.0;
            return 315.0;
        }

        vec3 rangeShift(int i) {
            if (i == 0) return uH0;
            if (i == 1) return uH1;
            if (i == 2) return uH2;
            if (i == 3) return uH3;
            if (i == 4) return uH4;
            if (i == 5) return uH5;
            if (i == 6) return uH6;
            return uH7;
        }

        vec3 hslToRgb(float h, float s, float l) {
            vec3 k = clamp(abs(mod(h / 60.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
            return l + s * (k - 0.5) * (1.0 - abs(2.0 * l - 1.0));
        }

        vec3 hsl(vec3 c) {
            float mx = max(c.r, max(c.g, c.b));
            float mn = min(c.r, min(c.g, c.b));
            float chroma = mx - mn;
            if (chroma <= 0.0) return c;
            float l = (mx + mn) / 2.0;
            float s = chroma / max(1.0 - abs(2.0 * l - 1.0), 0.000001);
            float h;
            if (mx == c.r) {
                h = 60.0 * mod((c.g - c.b) / chroma, 6.0);
            } else if (mx == c.g) {
                h = 60.0 * ((c.b - c.r) / chroma + 2.0);
            } else {
                h = 60.0 * ((c.r - c.g) / chroma + 4.0);
            }
            int i = 7;
            for (int j = 0; j < 8; j++) {
                if (h >= rangeDegrees(j)) i = j;
            }
            int next = i == 7 ? 0 : i + 1;
            float end = next == 0 ? 360.0 : rangeDegrees(next);
            float t = (h - rangeDegrees(i)) / (end - rangeDegrees(i));
            float f = clamp(chroma * 4.0, 0.0, 1.0);
            vec3 shift = mix(rangeShift(i), rangeShift(next), t) * f;
            float hue = mod(h + shift.x * 30.0, 360.0);
            float sat = clamp(s * (1.0 + shift.y), 0.0, 1.0);
            float lum = clamp(l * (1.0 + 0.5 * shift.z), 0.0, 1.0);
            return hslToRgb(hue, sat, lum);
        }

        vec3 lutSlice(float index, vec2 rg) {
            float column = mod(index, uLutGrid.x);
            float row = floor(index / uLutGrid.x);
            vec2 uv = (vec2(column, row) * uLutSize + rg + 0.5) / (uLutGrid * uLutSize);
            return texture2D(uLut, uv).rgb;
        }

        vec3 lut(vec3 c) {
            vec3 p = clamp((c - uLutMin) / (uLutMax - uLutMin), 0.0, 1.0) * (uLutSize - 1.0);
            float slice = min(floor(p.b), uLutSize - 2.0);
            return mix(lutSlice(slice, p.rg), lutSlice(slice + 1.0, p.rg), p.b - slice);
        }

        void main() {
            vec4 source = texture2D(uTex, vTex);
            if (vTex.x < uSplit) {
                gl_FragColor = source;
                return;
            }
            vec3 c = source.rgb * uGain;
            c = (c - 0.5) * uContrast + 0.5;
            float luma = dot(c, LUMA);
            c = clamp(luma + (c - luma) * uSaturation, 0.0, 1.0);
            if (uHasHsl == 1) c = hsl(c);
            if (uHasLut == 1) c = mix(c, lut(c), uLutStrength);
            if (uVignette != 0.0) {
                vec2 d = (vTex - 0.5) * 2.0;
                float f = smoothstep(0.3, 1.0, length(d) / sqrt(2.0));
                if (uVignette > 0.0) {
                    c *= 1.0 - 0.75 * uVignette * f;
                } else {
                    c += (1.0 - c) * -uVignette * 0.5 * f;
                }
            }
            gl_FragColor = vec4(clamp(c, 0.0, 1.0), source.a);
        }
    """

    /**
     * One filter (#25) on a clip's picture (straight alpha, upright): it samples the input
     * (plain, in mosaic blocks, with the colours split, or glitched), applies the CSS filter
     * functions as one colour matrix, blends an overlay (gradient or pattern) on top with
     * `uMode`, and mixes the result with the original (`uOriginal`, usually the same input) by
     * strength, inside the mask (#27) when the clip has one. `FilterRecipes` sets the uniforms;
     * positions in the overlay and the mask are in fractions of the picture, y down, as in CSS.
     */
    const val FILTER = PRECISION + BLEND_FUNCTIONS + """
        uniform sampler2D uTex;
        uniform sampler2D uOriginal;
        uniform int uMaskShape;
        uniform vec2 uMaskCentre;
        uniform vec2 uMaskSize;
        uniform float uMaskTurn;
        uniform float uMaskFeather;
        uniform float uMaskRadius;
        uniform float uMaskInvert;
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

        // How much of the effect shows at d: 1 inside the mask, fading over the feather.
        float coverage(vec2 d) {
            if (uMaskShape == 0) return 1.0;
            float aspect = uSize.x / uSize.y;
            float shortSide = min(aspect, 1.0);
            vec2 v = (d - uMaskCentre) * vec2(aspect, 1.0);
            float s = sin(uMaskTurn);
            float c = cos(uMaskTurn);
            vec2 local = vec2(c * v.x + s * v.y, -s * v.x + c * v.y);
            vec2 extent = uMaskSize * vec2(aspect, 1.0) * 0.5;
            float dist;
            if (uMaskShape == 1) {
                float r = min(uMaskRadius * shortSide, min(extent.x, extent.y));
                vec2 q = abs(local) - extent + r;
                dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            } else {
                dist = (length(local / max(extent, vec2(1e-5))) - 1.0) * min(extent.x, extent.y);
            }
            float feather = uMaskFeather * shortSide;
            float inside = feather > 0.0
                ? 1.0 - smoothstep(0.0, feather, dist)
                : (dist <= 0.0 ? 1.0 : 0.0);
            return uMaskInvert > 0.5 ? 1.0 - inside : inside;
        }

        void main() {
            vec4 original = texture2D(uOriginal, vTex);
            vec4 c = sampleInput(vTex);
            vec3 rgb = clamp((uMatrix * vec4(c.rgb, 1.0)).rgb, 0.0, 1.0);
            if (uOverlay != 0) {
                vec4 o = overlayColor(vec2(vTex.x, 1.0 - vTex.y));
                rgb = mix(rgb, clamp(blendColor(rgb, o.rgb), 0.0, 1.0), o.a);
            }
            float amount = uStrength * coverage(vec2(vTex.x, 1.0 - vTex.y));
            gl_FragColor = vec4(mix(original.rgb, rgb, amount), mix(original.a, c.a, amount));
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

    /**
     * One of the 24 transitions (#26), numbered as `Transitions.all`: the outgoing clip (uA) and
     * the incoming one (uB), each drawn alone and placed, combined at `uProgress` (eased). It
     * works inside `uRegion` (left, bottom, width, height in texture coordinates; the whole frame
     * in a project), so motion stays within it. Output is straight alpha, blended like a layer.
     */
    const val TRANSITION = PRECISION + """
        uniform sampler2D uA;
        uniform sampler2D uB;
        uniform float uProgress;
        uniform int uType;
        uniform vec4 uRegion;
        uniform float uAspect;
        uniform float uTime;
        varying vec2 vTex;

        const float PI = 3.14159265;

        float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float bottom = mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x);
            float top = mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x);
            return mix(bottom, top, f.y);
        }

        // d: where in the region, 0 to 1, y down. Outside it nothing is drawn.
        vec4 at(sampler2D tex, vec2 d) {
            if (d.x < 0.0 || d.x > 1.0 || d.y < 0.0 || d.y > 1.0) return vec4(0.0);
            return texture2D(tex, uRegion.xy + vec2(d.x, 1.0 - d.y) * uRegion.zw);
        }

        vec4 premul(vec4 c) { return vec4(c.rgb * c.a, c.a); }
        vec4 unpremul(vec4 p) { return p.a > 0.0 ? vec4(p.rgb / p.a, p.a) : vec4(0.0); }
        vec4 blend(vec4 x, vec4 y, float k) { return unpremul(mix(premul(x), premul(y), k)); }
        vec4 over(vec4 top, vec4 bottom) {
            return unpremul(premul(top) + premul(bottom) * (1.0 - top.a));
        }
        vec3 screen(vec3 b, vec3 s, float amount) { return mix(b, b + s - b * s, amount); }

        // A round blur of radius r (fraction of the region's height).
        vec4 blurred(sampler2D tex, vec2 d, float r) {
            vec4 sum = premul(at(tex, d));
            for (int i = 0; i < 12; i++) {
                float angle = float(i) * PI / 6.0;
                vec2 o = vec2(cos(angle) / uAspect, sin(angle)) * r;
                sum += premul(at(tex, d + o)) + premul(at(tex, d + o * 0.5));
            }
            return unpremul(sum / 25.0);
        }

        // A sideways motion blur of length r.
        vec4 smear(sampler2D tex, vec2 d, float r) {
            vec4 sum = vec4(0.0);
            for (int i = -6; i <= 6; i++) {
                sum += premul(at(tex, d + vec2(float(i) / 6.0 * r, 0.0)));
            }
            return unpremul(sum / 13.0);
        }

        // Where to sample for a picture turned clockwise by angle and scaled about the centre.
        vec2 turned(vec2 d, float angle, float scale) {
            vec2 v = (d - 0.5) * vec2(uAspect, 1.0);
            float s = sin(angle);
            float c = cos(angle);
            v = vec2(c * v.x + s * v.y, -s * v.x + c * v.y) / scale;
            return v / vec2(uAspect, 1.0) + 0.5;
        }

        // Distance from a point, in fractions of the region's diagonal.
        float reach(vec2 d, vec2 from) {
            return length((d - from) * vec2(uAspect, 1.0)) / length(vec2(uAspect, 1.0));
        }

        // Shifted bands keep to the picture, repeating its edge.
        vec4 glitchAt(vec2 shifted, float k, float split) {
            vec2 q = clamp(shifted, vec2(split), vec2(1.0 - split));
            vec4 g = blend(at(uA, q), at(uB, q), k);
            g.r = blend(at(uA, q + vec2(split, 0.0)), at(uB, q + vec2(split, 0.0)), k).r;
            g.b = blend(at(uA, q - vec2(split, 0.0)), at(uB, q - vec2(split, 0.0)), k).b;
            return g;
        }

        void main() {
            vec2 local = (vTex - uRegion.xy) / uRegion.zw;
            if (any(lessThan(local, vec2(0.0))) || any(greaterThan(local, vec2(1.0)))) {
                // Outside the region the transition draws nothing.
                gl_FragColor = vec4(0.0);
                return;
            }
            vec2 d = vec2(local.x, 1.0 - local.y);
            float p = uProgress;
            float peak = sin(PI * p);
            float soft = 0.004;
            vec4 black = vec4(0.0, 0.0, 0.0, 1.0);
            vec4 white = vec4(1.0);
            vec4 a = at(uA, d);
            vec4 b = at(uB, d);
            vec4 c = p < 0.5 ? a : b;
            if (uType == 0) {
                c = blend(a, b, p);
            } else if (uType == 1) {
                c = p < 0.5 ? blend(a, black, p * 2.0) : blend(black, b, p * 2.0 - 1.0);
            } else if (uType == 2) {
                c = p < 0.5 ? blend(a, white, p * 2.0) : blend(white, b, p * 2.0 - 1.0);
            } else if (uType == 3) {
                // Wipe left: the incoming clip from the right.
                c = blend(a, b, smoothstep(1.0 - p - soft, 1.0 - p + soft, d.x));
            } else if (uType == 4) {
                // Wipe down: from the top.
                c = blend(a, b, 1.0 - smoothstep(p - soft, p + soft, d.y));
            } else if (uType == 5) {
                // Clock: a hand sweeping clockwise from twelve.
                float t = atan(d.x - 0.5, 0.5 - d.y);
                if (t < 0.0) t += 2.0 * PI;
                c = blend(a, b, 1.0 - smoothstep(p - 0.003, p + 0.003, t / (2.0 * PI)));
            } else if (uType == 6) {
                float r = reach(d, vec2(0.5)) * 2.0;
                c = blend(a, b, 1.0 - smoothstep(p - soft, p + soft, r));
            } else if (uType == 7) {
                // Split: opening from the centre.
                float open = p * 0.5;
                c = blend(a, b, 1.0 - smoothstep(open - soft, open + soft, abs(d.x - 0.5)));
            } else if (uType == 8) {
                c = over(at(uB, d - vec2(1.0 - p, 0.0)), a);
            } else if (uType == 9) {
                c = over(at(uB, d - vec2(0.0, 1.0 - p)), a);
            } else if (uType == 10) {
                c = over(at(uB, d - vec2(1.0 - p, 0.0)), at(uA, d + vec2(p, 0.0)));
            } else if (uType == 11) {
                vec4 zoomed = blurred(uA, 0.5 + (d - 0.5) / (1.0 + 1.5 * p), 0.02 * p);
                c = blend(zoomed, b, smoothstep(0.2, 0.9, p));
            } else if (uType == 12) {
                c = over(at(uB, 0.5 + (d - 0.5) / (0.1 + 0.9 * p)), a);
            } else if (uType == 13) {
                // Whip pan: both race left, smeared, with light streaks.
                float r = 0.08 * peak;
                c = over(smear(uB, d - vec2(1.0 - p, 0.0), r), smear(uA, d + vec2(p, 0.0), r));
                c.rgb = screen(c.rgb, vec3(1.0), step(0.8, fract(d.x * 40.0)) * 0.12 * peak);
            } else if (uType == 14) {
                c = over(at(uB, turned(d, radians(70.0) * (1.0 - p), 0.4 + 0.6 * p)), a);
            } else if (uType == 15) {
                float tick = floor(uTime * 30.0);
                vec2 j = (vec2(hash(vec2(tick, 1.0)), hash(vec2(tick, 2.0))) - 0.5) * 0.06 * peak;
                c = blend(at(uA, d + j), at(uB, d - j), smoothstep(0.3, 0.7, p));
            } else if (uType == 16) {
                // Cube: the outgoing face turns away to the left, the incoming one comes in.
                float edge = 1.0 - p;
                if (d.x < edge) {
                    float x = d.x / max(edge, 1e-4);
                    float squeeze = 1.0 - 0.25 * p * (1.0 - x);
                    c = at(uA, vec2(x, 0.5 + (d.y - 0.5) / squeeze));
                    c.rgb *= 1.0 - 0.5 * p;
                } else {
                    float x = (d.x - edge) / max(p, 1e-4);
                    float squeeze = 1.0 - 0.25 * (1.0 - p) * x;
                    c = at(uB, vec2(x, 0.5 + (d.y - 0.5) / squeeze));
                    c.rgb *= 1.0 - 0.5 * (1.0 - p);
                }
            } else if (uType == 17) {
                float tick = floor(uTime * 20.0);
                float band = floor(d.y * 20.0);
                float shift = hash(vec2(band, tick)) > 0.55
                    ? (hash(vec2(tick, band)) - 0.5) * 0.25 * peak : 0.0;
                c = glitchAt(d + vec2(shift, 0.0), smoothstep(0.45, 0.55, p), 0.02 * peak);
                float f = fract(d.y * 16.0);
                if (f >= 0.4 && f < 0.5333) {
                    c.rgb = screen(c.rgb, vec3(0.0, 1.0, 0.918), 0.5 * peak);
                } else if (f >= 0.8667) {
                    c.rgb = screen(c.rgb, vec3(1.0, 0.0, 0.361), 0.5 * peak);
                }
            } else if (uType == 18) {
                c = blend(a, b, smoothstep(0.4, 0.6, p));
                c.rgb = mix(c.rgb, vec3(1.0), 0.95 * pow(peak, 1.5));
            } else if (uType == 19) {
                float r = 0.03 * peak;
                c = blend(blurred(uA, d, r), blurred(uB, d, r), p);
            } else if (uType == 20) {
                float size = max(0.06 * peak, 0.002);
                vec2 cell = vec2(size / uAspect, size);
                vec2 q = (floor(d / cell) + 0.5) * cell;
                c = blend(at(uA, q), at(uB, q), smoothstep(0.4, 0.6, p));
            } else if (uType == 21) {
                c = blend(a, b, p);
                float r = reach(d, vec2(0.7, 0.3));
                vec3 orange = vec3(1.0, 0.59, 0.24);
                vec3 glow = mix(orange, vec3(1.0, 0.31, 0.31), smoothstep(0.0, 0.4, r));
                c.rgb = screen(c.rgb, glow, (1.0 - smoothstep(0.0, 0.7, r)) * 0.95 * peak);
            } else if (uType == 22) {
                // Film burn: a hot spot moving across, with a ragged edge.
                c = blend(a, b, smoothstep(0.3, 0.7, p));
                float r = reach(d, vec2(0.2 + 0.6 * p, 0.6)) +
                    (noise(d * 8.0 + uTime) - 0.5) * 0.08;
                vec3 hot = vec3(1.0, 0.96, 0.82);
                vec3 orange = vec3(1.0, 0.47, 0.12);
                vec3 ember = vec3(0.47, 0.08, 0.0);
                vec3 heat = r < 0.25
                    ? mix(hot, orange, smoothstep(0.1, 0.25, r))
                    : mix(orange, ember, clamp((r - 0.25) / 0.2, 0.0, 1.0));
                c.rgb = screen(c.rgb, heat, (1.0 - smoothstep(0.45, 0.7, r)) * peak);
            } else if (uType == 23) {
                // Ink: a blot spreading from the lower left.
                vec2 v = (d - vec2(0.38, 0.6)) * vec2(uAspect, 1.0) / vec2(1.4, 1.0);
                float r = length(v) / length(vec2(uAspect, 1.0)) +
                    (noise(d * 6.0) - 0.5) * 0.12 + (noise(d * 18.0) - 0.5) * 0.04;
                float spread = p * 1.3 - 0.1;
                c = blend(a, b, 1.0 - smoothstep(spread - 0.01, spread + 0.01, r));
            }
            gl_FragColor = c;
        }
    """

    /**
     * Cutout (#50, #51): the clip's picture (uTex, upright) keeps what the person mask (uMask,
     * 256 × 256 confidences, top row first) says is a person, as `CutoutMath.alpha` works it out
     * line by line; or, with uKeyed, what is far enough from the key's chroma (uKey), as
     * `CutoutMath.keyAlpha` and `despill` do. Behind (uBehind): 0 lets the layers under the clip
     * show (straight alpha), 1 puts the blurred copy in uBlurred there, 2 the colour uColor.
     */
    const val CUTOUT = PRECISION + """
        uniform sampler2D uTex;
        uniform sampler2D uMask;
        uniform sampler2D uBlurred;
        uniform int uBehind;
        uniform vec3 uColor;
        uniform float uRefine;
        uniform float uHair;
        uniform int uKeyed;
        uniform vec2 uKey;
        uniform float uSimilarity;
        uniform float uSmoothness;
        uniform float uSpill;
        // Stabilization (#54): where the mask, made on the frame as shot, reads from.
        uniform mat4 uMaskMatrix;
        varying vec2 vTex;

        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

        void main() {
            vec4 picture = texture2D(uTex, vTex);
            vec3 rgb = picture.rgb;
            float a;
            if (uKeyed == 1) {
                float y = dot(rgb, LUMA);
                vec2 c = vec2((rgb.b - y) * 0.5389, (rgb.r - y) * 0.6350);
                float d = distance(c, uKey);
                float near = uSimilarity * 0.4;
                a = smoothstep(near, near + 0.01 + uSmoothness * 0.25, d);
                float tint = 1.0 - smoothstep(near, near + uSmoothness * 0.25 + 0.12, d);
                rgb = mix(rgb, vec3(y), uSpill * tint);
            } else {
                // The mask's first row is the top of the picture.
                vec2 shot = (uMaskMatrix * vec4(vTex, 0.0, 1.0)).xy;
                float m = texture2D(uMask, vec2(shot.x, 1.0 - shot.y)).r;
                float centre = 0.5 + 0.15 * uRefine;
                float width = 0.3 - 0.22 * uRefine;
                float firm = smoothstep(centre - width, centre + width, m);
                float soft = smoothstep(0.08, 0.6, m);
                a = max(firm, soft * uHair);
            }
            if (uBehind == 0) {
                gl_FragColor = vec4(rgb, picture.a * a);
            } else if (uBehind == 1) {
                gl_FragColor = vec4(mix(texture2D(uBlurred, vTex).rgb, rgb, a), picture.a);
            } else {
                gl_FragColor = vec4(mix(uColor, rgb, a), picture.a);
            }
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
