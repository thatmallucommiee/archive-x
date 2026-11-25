package com.archivex.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF090B14)
private val Panel = Color(0xFF141827)
private val Panel2 = Color(0xFF1B2133)
private val Violet = Color(0xFFAA8BFF)
private val Cyan = Color(0xFF69E3DB)
private val Muted = Color(0xFF9AA4B9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = darkColorScheme(
                primary = Violet,
                secondary = Cyan,
                background = Ink,
                surface = Panel,
                onBackground = Color(0xFFF3F1FA),
                onSurface = Color(0xFFF3F1FA),
                surfaceVariant = Panel2,
                onSurfaceVariant = Muted
            )
            MaterialTheme(colorScheme = scheme) {
                var selected by remember { mutableStateOf<Uri?>(null) }
                var fileName by remember { mutableStateOf("No archive selected") }
                var fileSize by remember { mutableStateOf("Choose a file to get started") }
                var fileType by remember { mutableStateOf("—") }
                var report by remember { mutableStateOf("Your archive details and extraction activity will appear here.") }
                var busy by remember { mutableStateOf(false) }
                var extracted by remember { mutableStateOf(false) }
                var browserRoot by remember { mutableStateOf<DocumentFile?>(null) }
                var currentFolder by remember { mutableStateOf<DocumentFile?>(null) }
                var integrationReport by remember { mutableStateOf("Open or share files with compatible Android applications.") }
                var activePanel by remember { mutableStateOf<String?>(null) }
                var preferredMode by remember { mutableStateOf(getSharedPreferences("archive_x_settings", MODE_PRIVATE).getString("default_mode", "Standard") ?: "Standard") }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val uri = result.data?.data
                    if (result.resultCode == RESULT_OK && uri != null) {
                        selected = uri
                        extracted = false
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                            .recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        val meta = queryMetadata(uri)
                        fileName = meta.first
                        fileSize = meta.second
                        fileType = meta.third
                        busy = true
                        lifecycleScope.launch {
                            report = withContext(Dispatchers.IO) { ArchiveEngine.inspect(contentResolver, uri) }
                            busy = false
                        }
                    }
                }
                val hatchDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                    if (tree != null && selected != null) {
                        runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        busy = true
                        activePanel = null
                        lifecycleScope.launch {
                            report = withContext(Dispatchers.IO) { HatchEngine.run(this@MainActivity, contentResolver, selected!!, tree) }
                            extracted = report.startsWith("HATCH COMPLETE") || report.startsWith("HATCH RECLAIMED")
                            if (extracted) {
                                val root = DocumentFile.fromTreeUri(this@MainActivity, tree)
                                browserRoot = root
                                currentFolder = root
                            }
                            busy = false
                        }
                    }
                }

                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                    if (tree != null && selected != null) {
                        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        busy = true
                        extracted = false
                        lifecycleScope.launch {
                            report = withContext(Dispatchers.IO) { ArchiveEngine.extract(contentResolver, selected!!, tree) }
                            extracted = report.startsWith("Extraction complete")
                            val root = DocumentFile.fromTreeUri(this@MainActivity, tree)
                            browserRoot = root
                            currentFolder = root
                            busy = false
                        }
                    }
                }


                val openFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        integrationReport = IntegrationManager.open(this@MainActivity, uri, contentResolver.getType(uri))
                    }
                }
                val shareFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        integrationReport = IntegrationManager.share(this@MainActivity, uri, contentResolver.getType(uri))
                    }
                }
                val browseFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                    if (tree != null) {
                        runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        val root = DocumentFile.fromTreeUri(this@MainActivity, tree)
                        browserRoot = root
                        currentFolder = root
                    }
                }

                Scaffold(containerColor = Ink) { insets ->
                    Column(
                        Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(15.dp))
                                    .background(Brush.linearGradient(listOf(Violet, Color(0xFF6650C8)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Archive, null, tint = Ink, modifier = Modifier.size(25.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("ARCHIVE X", fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
                                Text("ADVANCED ARCHIVE WORKSPACE", fontSize = 9.sp, color = Muted, letterSpacing = 1.8.sp)
                            }
                            IconButton(onClick = { activePanel = "settings" }) {
                                Icon(Icons.Rounded.Settings, "Settings", tint = Muted)
                            }
                        }

                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF282344), Color(0xFF151D2C), Color(0xFF102D34))))
                                .border(1.dp, Color(0xFF514776), RoundedCornerShape(26.dp))
                                .padding(20.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = Cyan.copy(alpha = .13f), shape = CircleShape) {
                                        Text("  STORAGE INTELLIGENCE  ", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text("v0.1.0", color = Muted, fontSize = 10.sp)
                                }
                                Text("Your files.\nUnder control.", fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                                Text("Inspect, organise and extract archives with precision.", color = Color(0xFFC2C7D5), fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FeaturePill("SAFE EXTRACTION")
                                    FeaturePill("ON-DEVICE")
                                }
                            }
                        }

                        SectionHeading("WORKSPACE", "01")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                                .background(Panel).border(1.dp, Color(0xFF293147), RoundedCornerShape(22.dp))
                                .padding(17.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Storage, null, tint = Violet)
                                Spacer(Modifier.width(9.dp))
                                Text("SELECTED ARCHIVE", fontSize = 11.sp, letterSpacing = 1.1.sp, color = Muted, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                if (selected != null) StatusPill(if (busy) "SCANNING" else "LOADED", if (busy) Cyan else Violet)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Panel2), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Archive, null, tint = Violet, modifier = Modifier.size(25.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(fileName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("$fileSize  ·  $fileType", color = Muted, fontSize = 11.sp)
                                }
                            }
                            Button(
                                onClick = { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                }) },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(15.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Ink)
                            ) {
                                Icon(Icons.Rounded.FolderOpen, null)
                                Spacer(Modifier.width(9.dp))
                                Text(if (selected == null) "Choose archive" else "Change archive", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Rounded.ChevronRight, null)
                            }
                            OutlinedButton(
                                onClick = { if (preferredMode == "Hatch") activePanel = "hatch" else folderPicker.launch(null) },
                                enabled = selected != null && !busy,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(15.dp),
                                border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(Cyan, Violet)))
                            ) {
                                Icon(Icons.Rounded.CloudUpload, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (preferredMode == "Hatch") "Open Hatch Mode" else "Extract to folder")
                            }
                        }


                        SectionHeading("INTEGRATION CENTER", "02")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel)
                                .border(1.dp, Color(0xFF293147), RoundedCornerShape(20.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("USE EXTRACTED OR EXISTING FILES", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                            Text("Open documents, hand APKs to Android's package installer, or share files with compatible apps. Android decides whether an app can accept the file.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                            Button(onClick = { openFilePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Open / install a file")
                            }
                            OutlinedButton(onClick = { shareFilePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Share a file")
                            }
                            Text(integrationReport, color = Color(0xFFC2C8D6), fontSize = 11.sp)
                        }

                        SectionHeading("EXTRACTION MODE", "03")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel)
                                .border(1.dp, Color(0xFF293147), RoundedCornerShape(20.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Standard Mode", fontWeight = FontWeight.Bold)
                                    Text("Keep the original archive untouched.", color = Muted, fontSize = 11.sp)
                                }
                                StatusPill("AVAILABLE", Cyan)
                            }
                            HorizontalDivider(color = Color(0xFF2A3144))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Hatch Mode", fontWeight = FontWeight.Bold, color = Color(0xFFD8C9FF))
                                    Text("Open the dedicated preflight and reclamation workspace.", color = Muted, fontSize = 11.sp)
                                }
                                StatusPill("PREVIEW", Violet)
                            }
                            Text("Inspect eligibility, review the destructive-operation safety rules and see exactly why Hatch execution is gated in this build.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                            OutlinedButton(onClick = { activePanel = "hatch" }, modifier = Modifier.fillMaxWidth()) {
                                Text("Open Hatch Mode")
                            }
                        }


                        SectionHeading("FILE MANAGER", "04")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel)
                                .border(1.dp, Color(0xFF293147), RoundedCornerShape(20.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("BROWSE EXTRACTED CONTENTS", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                            OutlinedButton(onClick = { browseFolderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Choose a folder to browse")
                            }
                            if (currentFolder != null) {
                                Text(currentFolder?.name ?: "Selected folder", color = Cyan, fontWeight = FontWeight.SemiBold)
                                if (currentFolder != browserRoot) {
                                    TextButton(onClick = { currentFolder = currentFolder?.parentFile ?: browserRoot }) {
                                        Text("Go to parent folder")
                                    }
                                }
                                val children = remember(currentFolder) { currentFolder?.listFiles()?.sortedBy { it.name?.lowercase() } ?: emptyList() }
                                if (children.isEmpty()) {
                                    Text("This folder is empty or the provider did not return its contents.", color = Muted, fontSize = 11.sp)
                                } else {
                                    children.take(100).forEach { child ->
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel2).padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(if (child.isDirectory) Icons.Rounded.FolderOpen else Icons.Rounded.Archive, null, tint = Violet)
                                            Spacer(Modifier.width(9.dp))
                                            Text(child.name ?: "Unnamed", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                            TextButton(onClick = {
                                                if (child.isDirectory) currentFolder = child
                                                else integrationReport = IntegrationManager.open(this@MainActivity, child.uri, child.type)
                                            }) { Text(if (child.isDirectory) "Open" else "Use") }
                                            if (!child.isDirectory) {
                                                TextButton(onClick = { integrationReport = IntegrationManager.share(this@MainActivity, child.uri, child.type) }) { Text("Share") }
                                            }
                                        }
                                    }
                                    if (children.size > 100) Text("Showing first 100 items.", color = Muted, fontSize = 10.sp)
                                }
                            } else {
                                Text("Choose the destination folder after extraction to browse its contents here.", color = Muted, fontSize = 11.sp)
                            }
                        }

                        SectionHeading("ARCHIVE INTELLIGENCE", "05")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel)
                                .border(1.dp, Color(0xFF293147), RoundedCornerShape(20.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("SCAN REPORT", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                                Spacer(Modifier.weight(1f))
                                if (busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Cyan)
                                else Icon(if (extracted) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, tint = if (extracted) Cyan else Muted, modifier = Modifier.size(18.dp))
                            }
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan, trackColor = Panel2)
                            Text(report, color = Color(0xFFC2C8D6), fontSize = 12.sp, lineHeight = 18.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = Muted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Private, on-device processing · No upload required", color = Muted, fontSize = 10.sp)
                        }
                        Text("ARCHIVE X  ·  BUILT FOR CONTROL", color = Color(0xFF596278), fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                if (activePanel == "settings") {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { activePanel = null },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Ink
                        ) {
                            SettingsScreen(context = this@MainActivity, onClose = { preferredMode = getSharedPreferences("archive_x_settings", MODE_PRIVATE).getString("default_mode", "Standard") ?: "Standard"; activePanel = null })
                        }
                    }
                }
                if (activePanel == "hatch") {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { activePanel = null },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                            HatchModeScreen(
                                archiveName = fileName,
                                archiveSize = fileSize,
                                archiveUri = selected,
                                onChooseArchive = { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                }) },
                                onStart = { hatchDestinationPicker.launch(null) },
                                onClose = { activePanel = null }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun queryMetadata(uri: Uri): Triple<String, String, String> {
        var name = "Selected archive"
        var size = "Size unavailable"
        var type = contentResolver.getType(uri) ?: "Unknown format"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = c.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = formatBytes(c.getLong(sizeIndex))
            }
        }
        val extension = name.substringAfterLast('.', "").uppercase()
        if (extension.isNotBlank()) type = extension
        return Triple(name, size, type)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}

@Composable
private fun SectionHeading(title: String, number: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(9.dp))
        Text(title, color = Color(0xFFB5BED0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(42.dp).height(1.dp).background(Color(0xFF343B50)))
    }
}

@Composable
private fun FeaturePill(text: String) {
    Surface(color = Color.White.copy(alpha = .08f), shape = RoundedCornerShape(30.dp)) {
        Text(text, color = Color(0xFFD7D2F0), fontSize = 8.sp, letterSpacing = .8.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(30.dp)) {
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
    }
}
