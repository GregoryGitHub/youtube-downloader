package com.youtubedownloaderandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private val Red = Color(0xFFFF0000)
private val Muted = Color(0xFF8E8E8E)

/**
 * Asks before tagging a YouTube Music album with iTunes/Deezer data: shows the
 * album found, lets the user pick another candidate, and how many tracks matched.
 */
@Composable
fun AlbumConfirmDialog(
    proposal: AlbumProposal,
    onSelect: (Int) -> Unit,
    onLyricsChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onWithoutMeta: () -> Unit,
    onCancel: () -> Unit,
) {
    val d = proposal.details
    val album = proposal.candidates[proposal.selected]
    val matched = proposal.matches.size
    val total = proposal.info.entries.size

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(0xFF1F1F1F),
        title = { Text("Metadados do álbum", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Encontramos este álbum. Aplicar título, artista, ano, gênero, número das faixas, capa em alta e letras?",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = album.coverUrl ?: album.thumbUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2F2F2F))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(album.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(album.artist, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(d?.year ?: album.year, d?.genre, album.source).joinToString(" · "),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                CandidatePicker(proposal, onSelect)

                when {
                    proposal.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Red, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Carregando faixas...", color = Muted)
                    }
                    proposal.error != null -> Text(proposal.error, color = Color(0xFFFF4E4E), style = MaterialTheme.typography.bodySmall)
                    d != null -> {
                        Text(
                            "$matched de $total faixas do YouTube ligadas às ${d.tracks.size} faixas do álbum" +
                                if (matched < total) " (as outras mantêm o título do YouTube)" else "",
                            color = if (matched == total) Color(0xFF2BA040) else Color(0xFFFFB300),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        val byId = proposal.matches
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                                .background(Color(0xFF0F0F0F), RoundedCornerShape(8.dp)).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            proposal.info.entries.forEach { e ->
                                val t = byId[e.id]?.let { d.tracks.getOrNull(it) }
                                Text(
                                    if (t != null) "${t.track.toString().padStart(2, '0')}  ${t.title}" else "--  ${e.title}",
                                    color = if (t != null) Color.White else Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onLyricsChange(!proposal.includeLyrics) }
                ) {
                    Checkbox(
                        checked = proposal.includeLyrics,
                        onCheckedChange = onLyricsChange,
                        colors = CheckboxDefaults.colors(checkedColor = Red)
                    )
                    Text("Baixar letras (LRCLIB)", color = Color.White)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = d != null && !proposal.loading,
                colors = ButtonDefaults.buttonColors(containerColor = Red)
            ) { Text("Aplicar", color = Color.White, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancelar", color = Muted) }
                TextButton(onClick = onWithoutMeta) { Text("Baixar sem", color = Color.White) }
            }
        }
    )
}

@Composable
private fun CandidatePicker(proposal: AlbumProposal, onSelect: (Int) -> Unit) {
    if (proposal.candidates.size < 2) return
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "Não é este? Escolher outro (${proposal.candidates.size} resultados) ▾",
            color = Red,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            proposal.candidates.forEachIndexed { i, c ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(c.title, fontWeight = if (i == proposal.selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(c.artist, c.year, "${c.trackCount} faixas", c.source).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = { open = false; onSelect(i) }
                )
            }
        }
    }
}
