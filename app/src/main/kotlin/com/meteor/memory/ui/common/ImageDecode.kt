package com.meteor.memory.ui.common

import android.graphics.BitmapFactory

internal fun sampledImageOptions(bytes: ByteArray, maxEdge: Int): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (longestEdge / sampleSize > maxEdge && sampleSize <= Int.MAX_VALUE / 2) {
        sampleSize *= 2
    }
    return BitmapFactory.Options().apply { inSampleSize = sampleSize }
}
