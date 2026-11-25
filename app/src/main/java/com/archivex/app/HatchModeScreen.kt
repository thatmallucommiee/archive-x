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
    onClose: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF090B14)).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HATCH MODE", color = Color(0xFFAA8BFF), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("Progressive archive reclamation", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
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
                Text(if (archiveUri == null) "Waiting for an archive" else "Hatch execution is not enabled in this build", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (archiveUri == null)
                        "Choose a ZIP archive to begin the Hatch workflow. Standard extraction remains available."
                    else
                        "The current engine does not yet have verified ZIP central-directory handling, same-source truncation, CRC-verified checkpoints, or a durable recovery journal. This archive will not be modified.",
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
                Text("A genuine Hatch operation consumes parts of the original archive only after each output has been verified and recovery state has been committed. Simply deleting the source or copying it to temporary storage is not Hatch Mode.", color = Color(0xFF9AA4B9), fontSize = 12.sp, lineHeight = 18.sp)
                HorizontalDivider(color = Color(0xFF30364A))
                Text("Current eligibility: NOT ENABLED", color = Color(0xFFFFC879), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("No data will be deleted or reclaimed from this screen. Use Standard Mode to extract safely while keeping your original archive.", color = Color(0xFF9AA4B9), fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Back to workspace") }
    }
}
