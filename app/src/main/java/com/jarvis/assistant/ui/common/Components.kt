package com.jarvis.assistant.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.theme.JarvisColors

/**
 * The one surface treatment used everywhere: a translucent panel with a hairline
 * edge lit from the top left. Defined once so the app reads as a single system
 * rather than a collection of screens that each invented their own card.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(JarvisColors.Surface.copy(alpha = 0.75f))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        JarvisColors.Primary.copy(alpha = 0.28f),
                        Color.Transparent,
                        JarvisColors.Secondary.copy(alpha = 0.12f),
                    ),
                ),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = JarvisColors.Primary.copy(alpha = 0.85f),
        modifier = modifier.padding(start = 4.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = JarvisColors.OnBackground,
            navigationIconContentColor = JarvisColors.OnBackground,
            actionIconContentColor = JarvisColors.Primary,
        ),
    )
}

/** A labelled row with optional supporting text and a trailing control. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.OnSurfaceMuted,
                )
            }
        }
        trailing()
    }
}

/** Circular icon button used for the home shortcuts. */
@Composable
fun OrbAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(JarvisColors.SurfaceElevated)
                .border(
                    width = 1.dp,
                    color = JarvisColors.Primary.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(percent = 50),
                )
                .clickable(onClick = onClick),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = JarvisColors.Primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.OnSurfaceMuted,
        )
    }
}
