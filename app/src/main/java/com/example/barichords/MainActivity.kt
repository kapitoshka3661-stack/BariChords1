package com.example.barichords

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private fun fmt(sec: Float): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerRef = remember { arrayOfNulls<MediaPlayer>(1) }

    var segments by remember { mutableStateOf<List<ChordSegment>>(emptyList()) }
    var status by remember { mutableStateOf("Оберіть аудіофайл (mp3, m4a, wav, ogg…)") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0) }
    var durMs by remember { mutableStateOf(1) }
    var shift by remember { mutableStateOf(0) }

    DisposableEffect(Unit) { onDispose { playerRef[0]?.release() } }

    LaunchedEffect(playing) {
        while (playing) {
            playerRef[0]?.let {
                posMs = it.currentPosition
                if (!it.isPlaying) playing = false
            }
            delay(100)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            playerRef[0]?.release(); playerRef[0] = null
            playing = false; segments = emptyList(); posMs = 0; shift = 0
            scope.launch {
                try {
                    progress = 0f; status = "Розбираю пісню…"
                    val result = withContext(Dispatchers.Default) {
                        val (pcm, sr) = AudioDecoder.decode(ctx, uri) { progress = it * 0.3f }
                        ChordRecognizer.analyze(pcm, sr) { progress = 0.3f + it * 0.7f }
                    }
                    segments = result
                    val mp = MediaPlayer().apply { setDataSource(ctx, uri); prepare() }
                    playerRef[0] = mp
                    durMs = mp.duration.coerceAtLeast(1)
                    status = "Готово. Змін акордів: ${result.size}"
                } catch (e: Exception) {
                    status = "Помилка: ${e.message}"
                }
                progress = null
            }
        }
    }

    val posSec = posMs / 1000f
    val curIdx = segments.indexOfLast { it.startSec <= posSec }.coerceAtLeast(0)
    val cur = segments.getOrNull(curIdx)?.chord?.transposed(shift)
    val nxt = segments.drop(curIdx + 1).firstOrNull { it.chord != null }?.chord?.transposed(shift)

    Column(Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = progress == null) { Text("Обрати пісню") }
        Text(status, fontSize = 14.sp)
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }

        if (segments.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(cur?.name ?: "—", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    Text("Далі: ${nxt?.name ?: "—"}", fontSize = 18.sp)
                }
                cur?.let { c -> BariUke.fingering(c)?.let { ChordDiagram(it) } }
            }

            Slider(
                value = posMs.toFloat(), valueRange = 0f..durMs.toFloat(),
                onValueChange = { posMs = it.toInt(); playerRef[0]?.seekTo(it.toInt()) }
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = {
                    val mp = playerRef[0] ?: return@Button
                    if (mp.isPlaying) { mp.pause(); playing = false } else { mp.start(); playing = true }
                }) { Text(if (playing) "Пауза" else "Грати") }
                Text("${fmt(posSec)} / ${fmt(durMs / 1000f)}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { shift-- }) { Text("−") }
                    Text("  транспон. ${if (shift > 0) "+" else ""}$shift  ")
                    OutlinedButton(onClick = { shift++ }) { Text("+") }
                }
            }

            val listState = rememberLazyListState()
            LaunchedEffect(curIdx) { listState.animateScrollToItem((curIdx - 1).coerceAtLeast(0)) }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(segments) { i, seg ->
                    val active = i == curIdx
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { playerRef[0]?.seekTo((seg.startSec * 1000).toInt()); posMs = (seg.startSec * 1000).toInt() }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(fmt(seg.startSec), Modifier.width(60.dp), fontSize = 14.sp)
                        Text(
                            seg.chord?.transposed(shift)?.name ?: "—",
                            fontSize = if (active) 24.sp else 18.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** Діаграма акорда: струни зліва направо D G B E. */
@Composable
fun ChordDiagram(frets: IntArray) {
    val fretted = frets.filter { it > 0 }
    val base = if (fretted.isEmpty() || fretted.max() <= 4) 1 else fretted.min()
    val color = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.Top) {
        if (base > 1) Text("${base}", Modifier.padding(top = 26.dp, end = 4.dp), fontSize = 14.sp)
        Canvas(Modifier.size(width = 90.dp, height = 120.dp)) {
            val pad = 12.dp.toPx(); val top = 24.dp.toPx(); val rows = 5
            val gridH = size.height - top - 4.dp.toPx()
            val rowH = gridH / rows
            val dx = (size.width - 2 * pad) / 3
            for (i in 0..3) drawLine(color, Offset(pad + i * dx, top), Offset(pad + i * dx, top + gridH), 2f)
            for (r in 0..rows) drawLine(color, Offset(pad, top + r * rowH), Offset(size.width - pad, top + r * rowH), if (r == 0 && base == 1) 7f else 2f)
            for (i in 0..3) {
                val x = pad + i * dx
                val f = frets[i]
                if (f == 0) drawCircle(color, 6.dp.toPx(), Offset(x, top - 11.dp.toPx()), style = Stroke(2f))
                else drawCircle(color, 8.dp.toPx(), Offset(x, top + (f - base + 0.5f) * rowH))
            }
        }
    }
}
