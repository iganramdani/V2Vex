package com.v2vex.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2vex.client.core.QrCodeEncoder

@Composable
fun QrCodeDialog(
    title: String,
    payload: String,
    onCopyUri: () -> Unit,
    onDismiss: () -> Unit
) {
    val matrix = remember(payload) { QrCodeEncoder.encode(payload) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(232.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp)
                ) {
                    if (matrix != null) {
                        Canvas(modifier = Modifier.size(204.dp)) {
                            val cell = size.width / matrix.size.toFloat()
                            for (y in 0 until matrix.size) {
                                for (x in 0 until matrix.size) {
                                    if (matrix.get(x, y)) {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = Offset(x * cell, y * cell),
                                            size = Size(cell + 0.5f, cell + 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text("URI exceeds QR V10 limit — use Copy URI", color = Color.Black)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = payload.take(80) + if (payload.length > 80) "..." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onCopyUri) { Text("Copy URI") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
