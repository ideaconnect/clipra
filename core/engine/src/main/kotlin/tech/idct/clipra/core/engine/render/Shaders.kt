package tech.idct.clipra.core.engine.render

/** GLSL ES 1.0 programs of the layer compositor. */
internal object Shaders {
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
     * Blends a clip layer (uSrc, straight alpha) onto the canvas (uDst) with a separable blend
     * mode: 1 Multiply, 2 Screen. Where the clip is transparent the canvas is unchanged.
     */
    const val BLEND = """
        precision mediump float;
        uniform sampler2D uDst;
        uniform sampler2D uSrc;
        uniform int uMode;
        varying vec2 vTex;
        vec3 blendColor(vec3 b, vec3 s) {
            if (uMode == 1) return b * s;
            if (uMode == 2) return b + s - b * s;
            return s;
        }
        void main() {
            vec4 d = texture2D(uDst, vTex);
            vec4 s = texture2D(uSrc, vTex);
            vec3 c = mix(d.rgb, blendColor(d.rgb, s.rgb), s.a);
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
