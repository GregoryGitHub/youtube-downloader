package com.audiotagger.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import com.audiotagger.Cover
import com.audiotagger.TagField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text field bound to a tag; number fields accept digits only. */
@Composable
fun TagTextField(
    field: TagField,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String = field.label,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (field.numeric) v.filter { it.isDigit() }.take(4) else v) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = !field.multiline,
        minLines = if (field == TagField.LYRICS) 5 else 1,
        maxLines = if (field.multiline) 14 else 1,
        keyboardOptions = if (field.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier,
    )
}

/** Decodes the cover off the main thread, downsampled to about [sizePx]. */
@Composable
fun CoverImage(cover: Cover?, modifier: Modifier, shape: Shape, sizePx: Int = 600, placeholder: String = "Sem capa") {
    val image by produceState<ImageBitmap?>(null, cover) {
        value = cover?.let { c ->
            withContext(Dispatchers.Default) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(c.bytes, 0, c.bytes.size, bounds)
                    var sample = 1
                    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
                    BitmapFactory.decodeByteArray(c.bytes, 0, c.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = "Capa", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Text(
                if (cover == null) placeholder else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun formatDuration(sec: Int): String = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)
