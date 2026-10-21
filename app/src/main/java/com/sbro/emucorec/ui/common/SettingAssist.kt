package com.sbro.emucorec.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sbro.emucorec.R
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape

@Composable
fun SettingHelpButton(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    var showDialog by rememberSaveable(title, description) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(18.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { showDialog = true }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = stringResource(R.string.settings_help_content_description),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            modifier = Modifier.size(14.dp)
        )
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                val scrollState = rememberScrollState()

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .heightIn(max = maxHeight),
                    shape = neonShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 10.dp,
                    shadowElevation = 18.dp,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(neonShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_help_dialog_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = neonShape(18.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.settings_help_dialog_tip),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(shape = neonButtonShape(), 
                            onClick = { showDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsStyledDialog(
    title: String,
    eyebrow: String,
    icon: ImageVector,
    onDismissRequest: () -> Unit,
    edgeToEdgeContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    StyledDialogScaffold(
        onDismissRequest = onDismissRequest,
        eyebrow = eyebrow,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        edgeToEdgeContent = edgeToEdgeContent,
        content = content
    )
}

/** Branded replacement for Material 3 AlertDialog with the same adaptive sizing. */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showEyebrow: Boolean = true,
    showIconContainer: Boolean = true,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = neonShape(30.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    properties: DialogProperties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    ),
    compact: Boolean = false
) {
    StyledDialogScaffold(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        eyebrow = stringResource(R.string.app_name_emucorec),
        showEyebrow = showEyebrow,
        showIconContainer = showIconContainer,
        icon = icon ?: {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = title ?: {
            Text(text = stringResource(R.string.app_name_emucorec))
        },
        shape = shape,
        containerColor = containerColor,
        properties = properties,
        compact = compact
    ) {
        if (text != null) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides
                    MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                    text()
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dismissButton?.invoke()
            confirmButton()
        }
    }
}

@Composable
private fun StyledDialogScaffold(
    onDismissRequest: () -> Unit,
    eyebrow: String,
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showEyebrow: Boolean = true,
    showIconContainer: Boolean = true,
    shape: Shape = neonShape(30.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    properties: DialogProperties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    ),
    compact: Boolean = false,
    edgeToEdgeContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowWidth = with(density) { containerSize.width.toDp() }
    val windowHeight = with(density) { containerSize.height.toDp() }
    val isLandscape = windowWidth > windowHeight
    val maxDialogHeight = if (isLandscape) {
        (windowHeight - 40.dp).coerceAtLeast(250.dp)
    } else {
        (windowHeight - 64.dp).coerceAtLeast(540.dp)
    }
    val dialogWidthFraction = when {
        compact && isLandscape -> 0.78f
        compact -> 0.90f
        isLandscape -> 0.98f
        else -> 0.94f
    }
    val dialogMaxWidth = when {
        compact && isLandscape -> 600.dp
        compact -> 520.dp
        isLandscape -> 1600.dp
        else -> 720.dp
    }
    val contentHorizontalPadding = when {
        compact && isLandscape -> 18.dp
        compact -> 20.dp
        isLandscape -> 20.dp
        else -> 22.dp
    }
    val contentTopPadding = when {
        compact && isLandscape -> 14.dp
        compact -> 18.dp
        isLandscape -> 18.dp
        else -> 22.dp
    }
    val contentBottomPadding = when {
        compact && isLandscape -> 16.dp
        compact -> 20.dp
        isLandscape -> 24.dp
        else -> 28.dp
    }
    val contentSpacing = when {
        compact && isLandscape -> 10.dp
        compact -> 14.dp
        isLandscape -> 14.dp
        else -> 18.dp
    }
    val iconTileSize = when {
        compact && isLandscape -> 48.dp
        compact -> 52.dp
        isLandscape -> 52.dp
        else -> 58.dp
    }
    val iconSize = when {
        compact && isLandscape -> 26.dp
        compact -> 28.dp
        isLandscape -> 28.dp
        else -> 30.dp
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (isLandscape) 10.dp else 14.dp,
                    top = if (isLandscape) 10.dp else 14.dp,
                    end = if (isLandscape) 10.dp else 14.dp,
                    bottom = if (isLandscape) 4.dp else 8.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = modifier
                    .fillMaxWidth(dialogWidthFraction)
                    .widthIn(max = dialogMaxWidth),
                shape = shape,
                color = containerColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxDialogHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = if (edgeToEdgeContent) 0.dp else contentHorizontalPadding,
                            top = contentTopPadding,
                            end = if (edgeToEdgeContent) 0.dp else contentHorizontalPadding,
                            bottom = contentBottomPadding
                        ),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = contentHorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showIconContainer) {
                            Surface(
                                modifier = Modifier.size(iconTileSize),
                                shape = neonShape(18.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier.size(iconSize),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        icon()
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.size(iconTileSize),
                                contentAlignment = Alignment.Center
                            ) {
                                icon()
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (showEyebrow) {
                                Text(
                                    text = eyebrow,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            CompositionLocalProvider(
                                androidx.compose.material3.LocalContentColor provides
                                    MaterialTheme.colorScheme.onSurface
                            ) {
                                ProvideTextStyle(
                                    MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    title()
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = contentHorizontalPadding),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                    )
                    content()
                }
            }
        }
    }
}
