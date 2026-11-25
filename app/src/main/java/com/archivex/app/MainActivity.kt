package com.archivex.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var selected by remember { mutableStateOf<Uri?>(null) }
                var report by remember { mutableStateOf("Choose an archive to inspect its contents.") }
                var busy by remember { mutableStateOf(false) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        selected = uri
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        busy = true
                        lifecycleScope.launch {
                            report = withContext(Dispatchers.IO) { ArchiveEngine.inspect(contentResolver, uri) }
                            busy = false
                        }
                    }
                }
                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                    if (tree != null && selected != null) {
                        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        busy = true
                        lifecycleScope.launch {
                            report = withContext(Dispatchers.IO) { ArchiveEngine.extract(contentResolver, selected!!, tree) }
                            busy = false
                        }
                    }
                }
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("ARCHIVE X", style = MaterialTheme.typography.headlineLarge)
                        Text("Archive manager · Android", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("FILE WORKSPACE", style = MaterialTheme.typography.labelLarge)
                                Text(selected?.lastPathSegment ?: "No archive selected")
                                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Open archive") }
                                OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = selected != null && !busy, modifier = Modifier.fillMaxWidth()) { Text("Extract to folder") }
                            }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("ACTIVITY", style = MaterialTheme.typography.labelLarge)
                        LazyColumn { items(report.lines()) { line -> Text(line, style = MaterialTheme.typography.bodyMedium) } }
                        Spacer(Modifier.weight(1f))
                        Text("PKG support is currently metadata-only. Encrypted or protected packages are not decrypted or bypassed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}
