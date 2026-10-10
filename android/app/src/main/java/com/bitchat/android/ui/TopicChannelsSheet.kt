package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.cloudflare.TopicRelayState
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.ui.theme.BitchatFontFamily

/** Upstream sheet, section, card and channel-row components without geographic content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicChannelsSheet(onDismiss: () -> Unit, viewModel: ChatViewModel) {
    val channels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val statuses by TopicRelayState.status.collectAsStateWithLifecycle()
    val peers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var showJoin by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BitchatBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box(Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp)
            ) {
                item {
                    SheetIconSectionHeader(
                        iconRes = R.drawable.ic_spec_range,
                        title = stringResource(R.string.mesh_title),
                        subtitle = stringResource(R.string.mesh_section_subtitle),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AboutHorizontalPadding).padding(top = 10.dp),
                        color = colors.surface, shape = AboutCardShape
                    ) {
                        ChannelOptionRow(
                            title = stringResource(R.string.mesh_label),
                            subtitle = stringResource(R.string.topic_local_subtitle),
                            isSelected = currentChannel == null,
                            participantCount = peers.size, titleColor = colors.secondary,
                            onClick = { viewModel.switchToChannel(null); onDismiss() }
                        )
                    }
                }
                item {
                    SheetIconSectionHeader(
                        iconRes = R.drawable.ic_spec_globe,
                        title = stringResource(R.string.topic_channels_title),
                        subtitle = stringResource(R.string.topic_channels_subtitle),
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
                items(channels.sorted(), key = { it }) { channel ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AboutHorizontalPadding).padding(top = 10.dp),
                        color = colors.surface, shape = AboutCardShape
                    ) {
                        ChannelOptionRow(
                            title = channel,
                            subtitle = stringResource(topicStatusResource(statuses[channel])),
                            isSelected = currentChannel == channel,
                            participantCount = 0, titleColor = colors.primary,
                            trailingContent = {
                                IconButton(onClick = { viewModel.leaveChannel(channel) }) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.topic_leave))
                                }
                            },
                            onClick = { viewModel.joinChannel(channel); onDismiss() }
                        )
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AboutHorizontalPadding).padding(top = 10.dp),
                        color = colors.surface, shape = AboutCardShape
                    ) {
                        ChannelOptionRow(
                            title = stringResource(R.string.topic_join),
                            subtitle = stringResource(R.string.topic_join_hint),
                            isSelected = false, participantCount = 0,
                            leadingIcon = Icons.Filled.Add, onClick = { showJoin = true }
                        )
                    }
                }
            }
            BitchatSheetTopBar(
                onClose = onDismiss, modifier = Modifier.align(Alignment.TopCenter),
                title = { BitchatSheetTitle(stringResource(R.string.topic_channels_title)) }
            )
        }
    }
    if (showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false; password = "" },
            title = { BitchatSheetTitle(stringResource(R.string.topic_join)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = BitchatFontFamily),
                        label = { Text(stringResource(R.string.topic_name), fontFamily = BitchatFontFamily) }
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = BitchatFontFamily),
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(stringResource(R.string.topic_password), fontFamily = BitchatFontFamily) }
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (viewModel.joinChannel(name, password.takeIf { it.isNotEmpty() })) {
                        password = ""; showJoin = false; onDismiss()
                    }
                }) { Text(stringResource(R.string.topic_join), fontFamily = BitchatFontFamily) }
            },
            dismissButton = {
                TextButton(onClick = { password = ""; showJoin = false }) {
                    Text(stringResource(android.R.string.cancel), fontFamily = BitchatFontFamily)
                }
            }
        )
    }
}

private fun topicStatusResource(status: String?): Int = when (status) {
    "connected" -> R.string.topic_online
    "connecting" -> R.string.topic_connecting
    "password_required" -> R.string.topic_password_required
    else -> R.string.topic_offline
}
