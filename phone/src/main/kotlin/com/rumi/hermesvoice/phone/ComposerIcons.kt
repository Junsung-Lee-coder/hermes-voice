package com.rumi.hermesvoice.phone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The composer's two controls as small built-in vectors (no icon library): a plus to attach and an upward arrow to send. */
internal object ComposerIcons {
    val Plus: ImageVector by lazy { vector("Plus", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }
    val ArrowUp: ImageVector by lazy { vector("ArrowUp", "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z") }

    private fun vector(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}
