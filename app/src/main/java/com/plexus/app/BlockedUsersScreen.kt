package com.plexus.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PlexusBlockConfirmation(contact: ContactEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block ${contact.displayName}?") },
        text = { Text("They won't be able to send you new connection requests or messages.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Block", color = Color(0xFFFF8A80)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Color(0xFF161A17), titleContentColor = Color.White,
        textContentColor = Color(0xFF949A96)
    )
}

@Composable
internal fun PlexusBlockedUsersScreen(contacts: List<ContactEntity>, onBack: () -> Unit, onUnblock: (ContactEntity) -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFF050505)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("‹", color = Color.White, fontSize = 28.sp) }
            Text("Blocked users", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (contacts.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No blocked users", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("People you block will appear here.", color = Color(0xFF949A96))
            }
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(contacts, key = { it.nodeId }) { contact ->
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF101311)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(contact.displayName, modifier = Modifier.weight(1f), color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onUnblock(contact) }) { Text("Unblock", color = Color(0xFF4DFF8B)) }
                    }
                }
            }
        }
    }
}
