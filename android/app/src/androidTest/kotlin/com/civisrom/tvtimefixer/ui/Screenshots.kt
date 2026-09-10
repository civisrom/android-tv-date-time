package com.civisrom.tvtimefixer.ui

import android.graphics.Bitmap

/** Semantic assertions can pass even when the emulator renderer produces a blank frame. */
internal fun checkScreenshotContent(bitmap: Bitmap) {
    val background = bitmap.getPixel(0, 0)
    check((0 until bitmap.height step 16).any { y ->
        (0 until bitmap.width step 16).any { x -> bitmap.getPixel(x, y) != background }
    }) { "The screenshot is a solid color; UI rendering was not verified" }
}
