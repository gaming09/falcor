package com.falcor.viewer.data.model

data class DetectionBox(
    val label: String,
    /** Normalized 0..1: left, top, right, bottom. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float? = null
)
