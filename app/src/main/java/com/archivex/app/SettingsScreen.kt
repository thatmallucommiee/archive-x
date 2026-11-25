package com.archivex.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(context: Context, onClose: () -> Unit) {
    val prefs = remember { context.getSharedPreferences("archive_x_settings", Context.MODE_PRIVATE) }
    var defaultMode by remember { mutableStateOf(prefs.getString("default_mode", "Standard") ?: "Standard") }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF090B14)).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Settings, null, tint = Color(0xFFAA8BFF), modifier = Modifier.size(27.dp))
            Spacer(Modifier.width(10.dp))
            Text("SETTINGS", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
        }
        SettingsGroup("EXTRACTION") {
            Text("Default extraction mode", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("Hatch remains gated until verified in-place extraction and recovery are implemented.", color = Color(0xFF9AA4B9), fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = defaultMode == "Standard", onClick = {
                    defaultMode = "Standard"; prefs.edit().putString("default_mode", defaultMode).apply()
                }, label = { Text("Standard") })
                FilterChip(selected = defaultMode == "Hatch", onClick = {
                    defaultMode = "Hatch"; prefs.edit().putString("default_mode", defaultMode).apply()
                }, label = { Text("Hatch") })
            }
        }
        SettingsGroup("STORAGE & PRIVACY") {
            Text("Storage access", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("ARCHIVE X uses folders and files that you explicitly select through Android's document picker. It does not require root access.", color = Color(0xFF9AA4B9), fontSize = 11.sp, lineHeight = 17.sp)
            HorizontalDivider(color = Color(0xFF30364A))
            Text("Processing", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("Archive processing is performed on-device. Files are not uploaded by this app.", color = Color(0xFF9AA4B9), fontSize = 11.sp)
        }
        SettingsGroup("ABOUT") {
            Text("ARCHIVE X", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Version 0.1.0 · Android archive workspace", color = Color(0xFF9AA4B9), fontSize = 11.sp)
            Text("ZIP/TAR extraction and basic file handoff are under active development. PS4 PKG extraction, full file operations and genuine Hatch reclamation are not yet implemented.", color = Color(0xFF9AA4B9), fontSize = 11.sp, lineHeight = 17.sp)
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B2A)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Color(0xFF69E3DB), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Color(0xFF9AA4B9), fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
