package com.aistudio.agritech.data.model

/**
 * Represents a single detected crop disease instance returned from the detection API.
 *
 * @param classId    Integer index of the detected class.
 * @param className  Human-readable disease label (e.g. "Late_blight").
 * @param score      Confidence score in range [0.0, 1.0].
 * @param x1         Left bounding-box coordinate (in model input space, 0–640).
 * @param y1         Top bounding-box coordinate.
 * @param x2         Right bounding-box coordinate.
 * @param y2         Bottom bounding-box coordinate.
 * @param mask       Optional 160×160 segmentation mask (flat float array); null if not returned.
 */
data class Detection(
    val classId: Int,
    val className: String,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val mask: FloatArray? = null
) {
    companion object {
        /** Canonical ordered disease class names as trained by the YOLOv11 model. */
        val CLASS_NAMES = listOf(
            "Late_blight",
            "Leaf Miner",
            "Magnesium Deficiency",
            "Nitrogen Deficiency",
            "Pottassium Deficiency",   // matches backend main.py spelling exactly
            "Spotted Wilt Virus"
        )
    }

    // FloatArray requires custom equals/hashCode to work correctly in data classes.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Detection) return false
        return classId == other.classId &&
            className == other.className &&
            score == other.score &&
            x1 == other.x1 && y1 == other.y1 &&
            x2 == other.x2 && y2 == other.y2 &&
            mask.contentEquals(other.mask)
    }

    override fun hashCode(): Int {
        var result = classId
        result = 31 * result + className.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + x1.hashCode()
        result = 31 * result + y1.hashCode()
        result = 31 * result + x2.hashCode()
        result = 31 * result + y2.hashCode()
        result = 31 * result + (mask?.contentHashCode() ?: 0)
        return result
    }

    private fun FloatArray?.contentEquals(other: FloatArray?): Boolean {
        if (this == null && other == null) return true
        if (this == null || other == null) return false
        return this.contentEquals(other)
    }
}
