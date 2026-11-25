package com.archivex.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HatchModeScreen(
    archiveName: String,
    archiveSize: String,
    archiveUri: Uri?,
    onChooseArchive: () -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit
) {
    var confirmReclaim by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF090B14)).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HATCH MODE", color = Color(0xFFAA8BFF), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("Verified ZIP reclamation", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B2A))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Storage, contentDescription = null, tint = Color(0xFF69E3DB), modifier = Modifier.size(28.dp))
                Text("SOURCE ARCHIVE", color = Color(0xFF9AA4B9), fontSize = 10.sp, letterSpacing = 1.3.sp)
                Text(archiveName, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(archiveSize, color = Color(0xFF9AA4B9), fontSize = 12.sp)
                OutlinedButton(onClick = onChooseArchive, modifier = Modifier.fillMaxWidth()) {
                    Text(if (archiveUri == null) "Choose archive for preflight" else "Change selected archive")
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2035)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("PRE-FLIGHT STATUS", color = Color(0xFFD8C9FF), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
                Text(if (archiveUri == null) "Waiting for an archive" else "ZIP preflight available — destructive confirmation required", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (archiveUri == null)
                        "Choose a ZIP archive to begin the Hatch workflow. Standard extraction remains available."
                    else
                        "First implementation supports ZIP/ZIP64 only. It verifies each extracted file and the unchanged source before attempting a final in-place reclaim. The source is not modified until all outputs pass verification.",
                    color = Color(0xFFC7C0D4), fontSize = 12.sp, lineHeight = 18.sp
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B2A))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = Color(0xFF69E3DB))
                    Spacer(Modifier.width(8.dp))
                    Text("DESTRUCTIVE OPERATION SAFETY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text("This first engine uses a conservative all-at-once commit: all files are extracted and CRC-checked first, then the same source document is truncated. It does not yet reclaim space progressively per file. Providers that cannot safely expose a local read/write file descriptor are rejected.", color = Color(0xFF9AA4B9), fontSize = 12.sp, lineHeight = 18.sp)
                HorizontalDivider(color = Color(0xFF30364A))
                Text("Supported format: ZIP / ZIP64 · Provider capability checked at runtime", color = Color(0xFFFFC879), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Hatch permanently consumes the selected original archive after successful verification. Make sure the destination has enough free space. Standard Mode keeps the original untouched.", color = Color(0xFF9AA4B9), fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        Button(onClick = { if (archiveUri != null) confirmReclaim = true }, enabled = archiveUri != null, modifier = Modifier.fillMaxWidth()) { Text("Choose destination & start Hatch") }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Back to workspace") }
        if (confirmReclaim) {
            AlertDialog(
                onDismissRequest = { confirmReclaim = false },
                title = { Text("Consume the original archive?") },
                text = { Text("Hatch will extract and verify the ZIP contents, then attempt to truncate the selected original archive. This is destructive and cannot be undone. Continue only if you accept losing the original archive.") },
                confirmButton = { TextButton(onClick = { confirmReclaim = false; onStart() }) { Text("I understand — continue") } },
                dismissButton = { TextButton(onClick = { confirmReclaim = false }) { Text("Cancel") } }
            )
        }
    }
}
