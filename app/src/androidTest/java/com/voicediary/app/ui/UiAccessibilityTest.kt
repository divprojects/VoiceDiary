package com.voicediary.app.ui

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicediary.app.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiAccessibilityTest {

    @Test
    fun actionAndAccentColorsMeetAaContrast() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertContrast(context.getColor(R.color.ios_light_blue), context.getColor(R.color.ios_light_surface))
        assertContrast(context.getColor(R.color.ios_dark_blue), context.getColor(R.color.ios_dark_surface))
        assertContrast(context.getColor(R.color.ios_red), context.getColor(R.color.ios_light_surface))
        assertContrast(context.getColor(R.color.ios_dark_red), context.getColor(R.color.ios_dark_surface))

        val white = Color.WHITE
        val black = Color.BLACK
        assertContrast(white, context.getColor(R.color.ios_light_blue))
        assertContrast(black, context.getColor(R.color.ios_dark_blue))
        assertContrast(white, context.getColor(R.color.ios_red))
        assertContrast(black, context.getColor(R.color.ios_dark_red))
    }

    private fun assertContrast(foreground: Int, background: Int) {
        val first = luminance(foreground)
        val second = luminance(background)
        val ratio = (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
        assertTrue("Expected contrast >= 4.5:1, got $ratio", ratio >= 4.5)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }

        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }
}