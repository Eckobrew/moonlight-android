package com.moonlight.material.ui.hosts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonlight.material.R
import com.moonlight.material.ui.theme.*

@Composable
fun AddHostDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, address: String, port: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47989") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MidnightBlue,
        title = {
            Text(
                text = stringResource(R.string.add_host_prompt),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.add_host_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.host_name_optional)) },
                    placeholder = { Text(stringResource(R.string.host_name_hint)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardNavy,
                        focusedLabelColor = CyanAccent,
                        unfocusedLabelColor = TextSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.ip_address_hostname)) },
                    placeholder = { Text(stringResource(R.string.ip_hint)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardNavy,
                        focusedLabelColor = CyanAccent,
                        unfocusedLabelColor = TextSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.port_optional)) },
                    placeholder = { Text(stringResource(R.string.port_hint)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardNavy,
                        focusedLabelColor = CyanAccent,
                        unfocusedLabelColor = TextSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portInt = port.toIntOrNull() ?: 47989
                    onAdd(name.trim(), address.trim(), portInt)
                },
                enabled = address.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
            ) {
                Text(stringResource(R.string.add_host), color = DeepNavy, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        }
    )
}
