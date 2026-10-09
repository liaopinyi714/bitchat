package com.bitchat.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.cloudflare.TopicRelayState

/** Reuses the terminal-like upstream chrome without a location directory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicChannelsSheet(onDismiss: () -> Unit, viewModel: ChatViewModel) {
    val channels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val statuses by TopicRelayState.status.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("channels", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(12.dp))
            Text("Create or join by name. Public topics can be joined by anyone who knows the name. Protected topics require a password.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("#channel") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("password (optional)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                if (viewModel.joinChannel(name, password.takeIf { it.isNotEmpty() })) { password = ""; onDismiss() }
            }, enabled = name.trim().isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("create / join") }
            Text("Set or change your channel password with /pass. There is no global channel list.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { viewModel.switchToChannel(null); onDismiss() }) { Text("nearby mesh") }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(channels.sorted(), key = { it }) { channel ->
                    Row(Modifier.fillMaxWidth().clickable { viewModel.switchToChannel(channel); onDismiss() }.padding(vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(channel, fontFamily = FontFamily.Monospace)
                            Text(statusLabel(statuses[channel]), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { viewModel.joinChannel(channel) }) { Text("reconnect") }
                        TextButton(onClick = { viewModel.leaveChannel(channel) }) { Text("leave") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun statusLabel(status: String?): String = when (status) {
    "connected" -> "online"
    "connecting" -> "connecting"
    "password_required" -> "password required"
    "offline", "disconnected" -> "offline · nearby mesh available"
    null -> "not connected"
    else -> "connection failed · reconnect"
}
