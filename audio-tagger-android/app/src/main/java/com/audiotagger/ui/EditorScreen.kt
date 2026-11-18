package com.audiotagger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiotagger.EditorViewModel
import com.audiotagger.TagField
import com.audiotagger.Track

private const val MIXED = "(vários) — digite para trocar em todas"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onClose: () -> Unit) {
    var editingId by remember { mutableStateOf<Int?>(null) }
    // null = album cover for every track; otherwise the id of one track.
    var coverTarget by remember { mutableStateOf<Int?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.pickCover(it, coverTarget) }
    }
    val pickImage = { target: Int? ->
        coverTarget = target
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val dirty = vm.dirtyCount
                        Text(
                            "${vm.tracks.size} faixa(s)" + if (dirty > 0) " · $dirty alterada(s)" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Fechar") }
                },
                actions = {
                    IconButton(onClick = vm::openSearch) { Icon(Icons.Default.Search, contentDescription = "Buscar online") }
                    EditorMenu(vm)
                },
            )
        },
        floatingActionButton = {
            if (vm.dirtyCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = vm::save,
                    icon = { Icon(Icons.Default.Done, contentDescription = null) },
                    text = { Text("Salvar (${vm.dirtyCount})") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { AlbumCard(vm, onPickCover = { pickImage(null) }) }
            item {
                Text(
                    "Faixas",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            items(vm.tracks, key = { it.id }) { t ->
                TrackRow(
                    t,
                    showDisc = vm.tracks.any { (it.discNo ?: 1) > 1 },
                    onClick = { editingId = t.id },
                    onUp = { vm.move(t.id, -1) },
                    onDown = { vm.move(t.id, 1) },
                )
            }
        }
    }

    val editing = vm.tracks.firstOrNull { it.id == editingId }
    if (editing != null) {
        TrackSheet(vm, editing, onDismiss = { editingId = null }, onPickCover = { pickImage(editing.id) })
    }
}

@Composable
private fun EditorMenu(vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Mais opções") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { open = false }
            MenuItem("Buscar dados do álbum online", close) { vm.openSearch() }
            MenuItem("Buscar letras das faixas sem letra", close) { vm.fetchLyrics() }
            DropdownMenuItem(
                text = { Text("Preferir letra sincronizada (LRC)") },
                leadingIcon = { Checkbox(checked = vm.preferSynced, onCheckedChange = null) },
                onClick = { vm.togglePreferSynced() },
            )
            HorizontalDivider()
            MenuItem("Numerar na ordem da lista", close) { vm.numberInListOrder() }
            MenuItem("Ordenar por nº da faixa", close) { vm.sortByNumber() }
            MenuItem("Ordenar por nome do arquivo", close) { vm.sortByFileName() }
            MenuItem("Preencher títulos vazios pelo nome do arquivo", close) { vm.titlesFromFileNames() }
            HorizontalDivider()
            MenuItem("Desfazer todas as alterações", close) { vm.revertAll() }
        }
    }
}

@Composable
private fun MenuItem(text: String, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = { close(); action() })
}

@Composable
private fun AlbumCard(vm: EditorViewModel, onPickCover: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                var coverMenu by remember { mutableStateOf(false) }
                Box {
                    CoverImage(
                        vm.commonCover(),
                        Modifier.size(132.dp).clickable { coverMenu = true },
                        RoundedCornerShape(12.dp),
                        placeholder = if (vm.coversDiffer) "Capas diferentes" else "Sem capa",
                    )
                    DropdownMenu(expanded = coverMenu, onDismissRequest = { coverMenu = false }) {
                        DropdownMenuItem(text = { Text("Escolher imagem da galeria") }, onClick = { coverMenu = false; onPickCover() })
                        DropdownMenuItem(text = { Text("Remover capa de todas") }, onClick = { coverMenu = false; vm.setCoverForAll(null) })
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlbumField(vm, TagField.ALBUM, Modifier.fillMaxWidth())
                    AlbumField(vm, TagField.YEAR, Modifier.fillMaxWidth())
                }
            }
            AlbumField(vm, TagField.ALBUM_ARTIST, Modifier.fillMaxWidth())
            AlbumField(vm, TagField.GENRE, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlbumField(vm, TagField.TRACK_TOTAL, Modifier.weight(1f))
                AlbumField(vm, TagField.DISC_TOTAL, Modifier.weight(1f))
            }
            Text(
                "Artista das faixas",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AlbumField(vm, TagField.ARTIST, Modifier.fillMaxWidth(), label = "Artista (todas as faixas)")
            FilledTonalButton(onClick = vm::openSearch, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buscar ano, faixas e capa online")
            }
        }
    }
}

@Composable
private fun AlbumField(vm: EditorViewModel, field: TagField, modifier: Modifier, label: String = field.label) {
    val common = vm.commonValue(field)
    TagTextField(
        field = field,
        value = common ?: "",
        onChange = { vm.setForAll(field, it) },
        placeholder = if (common == null) MIXED else null,
        label = label,
        modifier = modifier,
    )
}

@Composable
private fun TrackRow(t: Track, showDisc: Boolean, onClick: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val number = buildString {
                if (showDisc) append(t.discNo ?: "?").append('.')
                append(t.trackNo?.toString()?.padStart(2, '0') ?: "--")
            }
            Text(
                number,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(if (showDisc) 52.dp else 34.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.dirty) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(t.displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val info = listOf(t[TagField.ARTIST], formatDuration(t.durationSec), t.format)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                Text(info, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (t.folder.isNotEmpty()) "${t.folder}/" else "") + t.fileName +
                        if (t[TagField.LYRICS].isNotBlank()) "  · letra ✓" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column {
                IconButton(onClick = onUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir")
                }
                IconButton(onClick = onDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descer")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSheet(vm: EditorViewModel, t: Track, onDismiss: () -> Unit, onPickCover: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var coverMenu by remember { mutableStateOf(false) }
                Box {
                    CoverImage(t.cover, Modifier.size(72.dp).clickable { coverMenu = true }, RoundedCornerShape(8.dp), sizePx = 200)
                    DropdownMenu(expanded = coverMenu, onDismissRequest = { coverMenu = false }) {
                        DropdownMenuItem(text = { Text("Trocar capa desta faixa") }, onClick = { coverMenu = false; onPickCover() })
                        DropdownMenuItem(text = { Text("Remover capa desta faixa") }, onClick = { coverMenu = false; vm.setTrackCover(t.id, null) })
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(formatDuration(t.durationSec), t.format).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagTextField(TagField.TRACK, t[TagField.TRACK], { vm.setField(t.id, TagField.TRACK, it) }, Modifier.weight(1f))
                TagTextField(TagField.TRACK_TOTAL, t[TagField.TRACK_TOTAL], { vm.setField(t.id, TagField.TRACK_TOTAL, it) }, Modifier.weight(1f), label = "de")
                TagTextField(TagField.DISC, t[TagField.DISC], { vm.setField(t.id, TagField.DISC, it) }, Modifier.weight(1f))
                TagTextField(TagField.DISC_TOTAL, t[TagField.DISC_TOTAL], { vm.setField(t.id, TagField.DISC_TOTAL, it) }, Modifier.weight(1f), label = "de")
            }
            for (f in listOf(TagField.TITLE, TagField.ARTIST, TagField.ALBUM, TagField.ALBUM_ARTIST)) {
                TagTextField(f, t[f], { vm.setField(t.id, f, it) }, Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagTextField(TagField.YEAR, t[TagField.YEAR], { vm.setField(t.id, TagField.YEAR, it) }, Modifier.weight(1f))
                TagTextField(TagField.GENRE, t[TagField.GENRE], { vm.setField(t.id, TagField.GENRE, it) }, Modifier.weight(2f))
            }
            TagTextField(TagField.COMPOSER, t[TagField.COMPOSER], { vm.setField(t.id, TagField.COMPOSER, it) }, Modifier.fillMaxWidth())
            TagTextField(TagField.COMMENT, t[TagField.COMMENT], { vm.setField(t.id, TagField.COMMENT, it) }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Letra", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (t[TagField.LYRICS].isNotBlank()) {
                    TextButton(onClick = { vm.setField(t.id, TagField.LYRICS, "") }) { Text("Limpar") }
                }
                OutlinedButton(onClick = { vm.fetchLyrics(setOf(t.id), overwrite = true) }) { Text("Buscar letra") }
            }
            TagTextField(TagField.LYRICS, t[TagField.LYRICS], { vm.setField(t.id, TagField.LYRICS, it) }, Modifier.fillMaxWidth())
            if (t.dirty) {
                TextButton(
                    onClick = {
                        TagField.entries.forEach { f -> vm.setField(t.id, f, t.original[f].orEmpty()) }
                        vm.setTrackCover(t.id, t.originalCover)
                    },
                ) { Text("Desfazer alterações desta faixa") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
