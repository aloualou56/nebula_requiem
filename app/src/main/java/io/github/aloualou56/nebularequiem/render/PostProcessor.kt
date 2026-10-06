package io.github.aloualou56.nebularequiem.render

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.RecordingCanvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import kotlin.math.ceil
import kotlin.math.max

/**
 * §10 POST-PROCESSING on the GPU. The world is recorded into a RenderNode; a RenderEffect graph
 * then applies:
 *   - Bloom: AGSL c² (a soft threshold) → Gaussian blur, blended
 *     onto the frame with PLUS (createBlendModeEffect), all inside one effect graph.
 *   - Radial chromatic aberration: AGSL resamples R scaled up and B scaled down about the centre
 *     (fringes grow with distance from the optical axis), plus the hard digital "glitch" shift.
 *   - Reduced render resolution for lower quality tiers: the scene node renders into a smaller
 *     compositing layer that is scaled up (the tier's resolution cap / pixel budget).
 * Everything degrades gracefully: if any of it is unavailable the world is drawn directly.
 */
class PostProcessor {
    private val scene = RenderNode("nebula-scene")
    private val post = RenderNode("nebula-post")
    var supported = true
        private set
    private var threshold: RuntimeShader? = null
    private var ca: RuntimeShader? = null
    private var bloomEffect: RenderEffect? = null
    private var bloomSigma = -1f
    private var w = 1; private var h = 1

    init {
        try {
            threshold = RuntimeShader(THRESHOLD_AGSL)
            ca = RuntimeShader(CA_AGSL)
        } catch (e: Throwable) {
            Log.w("NebulaRequiem", "Post-processing shaders unavailable", e)
            supported = false
        }
    }

    private fun bloom(sigma: Float): RenderEffect? {
        if (bloomEffect != null && kotlin.math.abs(sigma - bloomSigma) < 0.25f) return bloomEffect
        val th = threshold ?: return null
        return try {
            val chain = RenderEffect.createChainEffect(
                RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP),
                RenderEffect.createRuntimeShaderEffect(th, "content")
            )
            RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 0f), chain, BlendMode.PLUS).also { bloomEffect = it; bloomSigma = sigma }
        } catch (e: Throwable) { supported = false; null }
    }

    /** Begin recording the world at `rs` × the surface resolution (pixel coordinates stay full-res). */
    fun beginScene(width: Int, height: Int, rs: Float): RecordingCanvas {
        w = width; h = height
        val sw = max(1, ceil(width * rs).toInt()); val sh = max(1, ceil(height * rs).toInt())
        scene.setPosition(0, 0, sw, sh)
        scene.pivotX = 0f; scene.pivotY = 0f
        val scaled = rs < 0.999f
        scene.scaleX = if (scaled) width.toFloat() / sw else 1f
        scene.scaleY = if (scaled) height.toFloat() / sh else 1f
        scene.setUseCompositingLayer(scaled, null)
        val c = scene.beginRecording(sw, sh)
        if (scaled) c.scale(sw.toFloat() / width, sh.toFloat() / height)
        return c
    }

    fun endScene() { scene.endRecording() }

    /**
     * Draw the recorded scene to `target` with the requested effects.
     * @param bloomSigma blur radius in pixels, or 0 for no bloom
     * @param caK relative R/B scale (edge shift / half width), 0 for none
     * @param caShift horizontal glitch offset in pixels
     */
    fun composite(target: Canvas, bloomSigma: Float, caK: Float, caShift: Float) {
        post.setPosition(0, 0, w, h)
        val pc = post.beginRecording(w, h)
        try { pc.drawRenderNode(scene) } finally { post.endRecording() }
        var effect: RenderEffect? = if (bloomSigma > 0f) bloom(bloomSigma) else null
        val sh = ca
        if (sh != null && (caK > 1e-4f || kotlin.math.abs(caShift) > 0.5f)) {
            try {
                sh.setFloatUniform("center", w / 2f, h / 2f)
                sh.setFloatUniform("k", caK)
                sh.setFloatUniform("shift", caShift)
                val caEffect = RenderEffect.createRuntimeShaderEffect(sh, "content")
                effect = if (effect != null) RenderEffect.createChainEffect(caEffect, effect) else caEffect
            } catch (e: Throwable) { supported = false }
        }
        post.setRenderEffect(effect)
        target.drawRenderNode(post)
    }

    companion object {
        /** c² keeps highlights and crushes mid-tones; the bloom gain (0.45 + 0.3) is folded in. */
        const val THRESHOLD_AGSL = """
            uniform shader content;
            half4 main(float2 p) {
                half4 c = content.eval(p);
                half3 t = c.rgb * c.rgb * 0.75;
                return half4(t, 1.0);
            }
        """

        /** R sampled from a (1+k)-scaled copy, B from a (1−k)-scaled copy, about the centre. */
        const val CA_AGSL = """
            uniform shader content;
            uniform float2 center;
            uniform float k;
            uniform float shift;
            half4 main(float2 p) {
                float2 d = p - center;
                half4 g = content.eval(p);
                half r = content.eval(center + (d - float2(shift, 0.0)) / (1.0 + k)).r;
                half b = content.eval(center + (d + float2(shift, 0.0)) / (1.0 - k)).b;
                return half4(r, g.g, b, 1.0);
            }
        """
    }
}
