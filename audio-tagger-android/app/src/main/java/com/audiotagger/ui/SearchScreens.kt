package com.audiotagger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.audiotagger.AlbumDetails
import com.audiotagger.AlbumResult
import com.audiotagger.ApplyOption
import com.audiotagger.EditorViewModel
import com.audiotagger.RemoteTrack
import com.audiotagger.Source
import com.audiotagger.TagField
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: EditorViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val doSearch = { keyboard?.hide(); vm.search() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Buscar álbum online") },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.searchArtist,
                        onValueChange = { vm.searchArtist = it },
                        label = { Text("Artista") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = vm.searchAlbum,
                        onValueChange = { vm.searchAlbum = it },
                        label = { Text("Álbum") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                            Source.entries.forEachIndexed { i, s ->
                                SegmentedButton(
                                    selected = vm.source == s,
                                    onClick = { vm.changeSource(s) },
                                    shape = SegmentedButtonDefaults.itemShape(i, Source.entries.size),
                                ) { Text(s.label) }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = doSearch) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Buscar")
                        }
                    }
                    if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    vm.searchError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(vm.results, key = { it.source.name + it.id }) { album ->
                AlbumResultRow(album, localCount = vm.tracks.size, onClick = { vm.chooseAlbum(album) })
            }
        }
    }
}

@Composable
private fun AlbumResultRow(album: AlbumResult, localCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = album.thumbUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(album.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val info = listOfNotNull(
                    album.year,
                    "${album.trackCount} faixas",
                    album.kind?.takeIf { !it.equals("album", true) },
                ).joinToString(" · ")
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (album.trackCount == localCount) {
                    Text(
                        "Mesmo número de faixas que os seus arquivos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MatchScreen(vm: EditorViewModel) {
    val d = vm.details ?: return
    val matched = vm.assignment.values.count { it != null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conferir faixas") },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::applyAlbum,
                    enabled = vm.options.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                ) { Text("Aplicar nos arquivos") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { AlbumHeader(d) }
            item {
                Text("O que copiar", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApplyOption.entries.forEach { o ->
                        FilterChip(selected = o in vm.options, onClick = { vm.toggleOption(o) }, label = { Text(o.label) })
                    }
                }
                Text(
                    "Álbum, ano, gênero e capa vão para todos os arquivos. Título, artista e números só para os arquivos " +
                        "ligados a uma faixa abaixo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text(
                    "$matched de ${vm.tracks.size} arquivos ligados a uma faixa",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(vm.tracks, key = { it.id }) { t ->
                val idx = vm.assignment[t.id]
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(t.fileName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val current = listOfNotNull(
                            t.trackNo?.let { "nº $it" },
                            t[TagField.TITLE].takeIf { it.isNotBlank() },
                            formatDuration(t.durationSec).takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        Text(
                            "Agora: ${current.ifBlank { "sem tags" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RemotePicker(d, idx, onPick = { vm.assign(t.id, it) })
                        val r = idx?.let { d.tracks.getOrNull(it) }
                        if (r != null && t.durationSec > 0 && r.durationSec > 0 && abs(t.durationSec - r.durationSec) > 10) {
                            Text(
                                "Duração diferente (${formatDuration(t.durationSec)} × ${formatDuration(r.durationSec)}) — confira",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(d: AlbumDetails) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = d.album.coverUrl ?: d.album.thumbUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(d.album.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(d.album.artist, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(d.year, d.genre, "${d.tracks.size} faixas", if (d.discTotal > 1) "${d.discTotal} discos" else null, d.album.source.label)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun label(d: AlbumDetails, r: RemoteTrack): String {
    val n = if (d.discTotal > 1) "${r.disc}.${r.track.toString().padStart(2, '0')}" else r.track.toString().padStart(2, '0')
    val dur = formatDuration(r.durationSec)
    return "$n  ${r.title}" + if (dur.isNotEmpty()) "  ($dur)" else ""
}

@Composable
private fun RemotePicker(d: AlbumDetails, selected: Int?, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (selected != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { open = true },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected?.let { d.tracks.getOrNull(it) }?.let { "→ " + label(d, it) } ?: "Não alterar este arquivo",
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Não alterar este arquivo") }, onClick = { open = false; onPick(null) })
            d.tracks.forEachIndexed { i, r ->
                DropdownMenuItem(text = { Text(label(d, r)) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}
