package com.audiotagger

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.audiotagger.ui.EditorScreen
import com.audiotagger.ui.MatchScreen
import com.audiotagger.ui.SearchScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App()
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFB39DDB))
        else -> lightColorScheme(primary = Color(0xFF5E35B1))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(vm: EditorViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var confirmClose by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        val msg = vm.message ?: return@LaunchedEffect
        vm.message = null
        snackbar.showSnackbar(msg, withDismissAction = true)
    }

    val requestClose = { if (vm.dirtyCount > 0) confirmClose = true else vm.close() }
    BackHandler(enabled = vm.screen != Screen.HOME) {
        if (vm.screen == Screen.EDITOR) requestClose() else vm.back()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { _ ->
        when (vm.screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.EDITOR -> EditorScreen(vm, onClose = requestClose)
            Screen.SEARCH -> SearchScreen(vm)
            Screen.MATCH -> MatchScreen(vm)
        }
    }

    vm.progress?.let { ProgressDialog(it) }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Descartar alterações?") },
            text = { Text("${vm.dirtyCount} arquivo(s) têm alterações que não foram salvas.") },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; vm.close() }) { Text("Descartar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text("Continuar editando") }
            },
        )
    }
}

@Composable
private fun HomeScreen(vm: EditorViewModel) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::openFolder)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.openFiles(uris)
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.openFiles(listOf(it)) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Editor de Tags", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Corrija capa, ano, álbum, artista, número de faixa e disco e letras das suas músicas. " +
                    "Digite os dados ou busque no iTunes / Deezer pelo nome do álbum.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
                Text("Abrir pasta do álbum")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("audio/*")) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            ) {
                Text("Escolher vários arquivos")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { singlePicker.launch(arrayOf("audio/*")) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            ) {
                Text("Abrir um arquivo")
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "MP3, M4A, FLAC, OGG, WAV, AIFF, WMA e DSF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressDialog(p: Progress) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card {
            Column(Modifier.padding(24.dp).widthIn(min = 240.dp)) {
                Text(p.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                if (p.total > 0) {
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${p.done} de ${p.total}", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
