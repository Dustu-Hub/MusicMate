package com.music.shitmate.playback.audio

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Real-time Karaoke / Vocal Suppressor DSP Processor for ShitMate.
 *
 * Operates directly on interleaved Float32 [AudioBlock] samples in-place.
 * Attenuates center-panned vocals using mid/side decomposition while isolating
 * and preserving low-frequency bass (kick drum, bassline) below ~140 Hz so
 * the rhythm and groove of the track remain intact.
 *
 * When [enabled] is false or [attenuation] is 0.0f, processing self-bypasses with zero overhead.
 */
class VocalSuppressorProcessor : FloatAudioProcessor {

    companion object {
        /** Global state flow controlling whether vocal suppression (Karaoke mode) is active. */
        val enabledFlow = MutableStateFlow(false)

        /** Attenuation factor (0.0f = bypass, 1.0f = full suppression). Default 0.90f. */
        val attenuationFlow = MutableStateFlow(0.90f)

        private const val BASS_CUTOFF_HZ = 140.0f
    }

    var enabled: Boolean = false
    var attenuation: Float = 0.90f

    private var sampleRate: Int = 44100
    private var channelCount: Int = AudioBlock.DEFAULT_CHANNELS

    // 1-pole low-pass filter state for isolating mid-bass
    private var filterState: Float = 0.0f
    private var filterAlpha: Float = 0.0f

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        updateFilterAlpha()
    }

    private fun updateFilterAlpha() {
        if (sampleRate > 0) {
            val dt = 1.0f / sampleRate.toFloat()
            val rc = 1.0f / (2.0f * Math.PI.toFloat() * BASS_CUTOFF_HZ)
            filterAlpha = dt / (rc + dt)
        } else {
            filterAlpha = 0.02f
        }
    }

    override fun process(block: AudioBlock) {
        // Synchronize with reactive state flows
        val isActivelyEnabled = enabledFlow.value || enabled
        val currentAttenuation = attenuationFlow.value.coerceIn(0f, 1f)

        if (!isActivelyEnabled || currentAttenuation <= 0.001f || channelCount < 2 || block.frameCount == 0) {
            return
        }

        val samples = block.samples
        val frameCount = block.frameCount
        val alpha = filterAlpha
        val midGain = 1.0f - currentAttenuation

        var sampleIdx = 0
        var lp = filterState

        for (i in 0 until frameCount) {
            val left = samples[sampleIdx]
            val right = samples[sampleIdx + 1]

            // Mid/Side decomposition
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f

            // Low-pass filter to extract bass from mid
            lp += alpha * (mid - lp)
            val midBass = lp
            val midVocalAndHigh = mid - midBass

            // Attenuate the vocal/mid band while preserving the bass and stereo spread
            val processedMid = midBass + (midVocalAndHigh * midGain)

            // Reconstruct stereo
            samples[sampleIdx] = processedMid + side
            samples[sampleIdx + 1] = processedMid - side

            sampleIdx += channelCount
        }

        filterState = lp
    }

    override fun flush() {
        filterState = 0.0f
    }

    override fun reset() {
        filterState = 0.0f
        sampleRate = 44100
        channelCount = AudioBlock.DEFAULT_CHANNELS
        updateFilterAlpha()
    }
}
