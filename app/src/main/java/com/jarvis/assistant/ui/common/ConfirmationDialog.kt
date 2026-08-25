package com.jarvis.assistant.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jarvis.assistant.R
import com.jarvis.assistant.core.ConfirmationRequest
import com.jarvis.assistant.ui.theme.JarvisColors

/**
 * The last thing standing between the model and an irreversible action.
 *
 * Dismissing it by tapping outside counts as declining, not as leaving the
 * question open: an ambiguous gesture must never be read as consent to place a
 * call or send a message.
 */
@Composable
fun ConfirmationDialog(
    request: ConfirmationRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = request.title) },
        text = { Text(text = request.body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = request.confirmLabel, color = JarvisColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(
                    text = stringResource(R.string.confirm_cancel),
                    color = JarvisColors.OnSurfaceMuted,
                )
            }
        },
        containerColor = JarvisColors.SurfaceElevated,
        titleContentColor = JarvisColors.OnBackground,
        textContentColor = JarvisColors.OnBackground,
    )
}
