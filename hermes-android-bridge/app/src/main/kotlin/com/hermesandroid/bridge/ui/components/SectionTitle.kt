package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * Sektions-Titel (v0.12.0) mit echtem TalkBack-Heading (Sektionsnavigation)
 * und Rhythmus-Abstand zur Karte darüber.
 */
@Composable
fun SectionTitle(
    title: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.semantics { heading() },
    ) {
        androidx.compose.foundation.layout.PaddingValues(top = 0.dp)
        androidx.compose.material3.Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = FlipsiColors.TextPrimary,
            modifier = Modifier.paddingFromBaseline(top = 28.dp),
        )
        if (sub != null) {
            androidx.compose.material3.Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.paddingFromBaseline(top = 20.dp),
            )
        }
    }
}