package com.moonlight.material.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonlight.material.R
import com.moonlight.material.core.models.Host
import com.moonlight.material.ui.theme.*

@Composable
fun PairingDialog(
    host: Host,
    pin: String?,
    stageMessage: String?,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        shape = RoundedCornerShape(24.dp),
        containerColor = MidnightBlue,
        title = {
            Text(
                text = "Pair with ${host.name}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.enter_pin_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // PIN display cards
                if (pin != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (char in pin) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CardNavy)
                            ) {
                                Text(
                                    text = char.toString(),
                                    fontSize = 28.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanAccent
                                )
                            }
                        }
                    }
                } else {
                    CircularProgressIndicator(color = CyanAccent)
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = stringResource(R.string.pin_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                if (stageMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stageMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanGlow,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        }
    )
}
