package com.example.pixelcolor.presentation

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pixelcolor.data.ArtworkEntity
import com.example.pixelcolor.data.Template
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

@Composable
fun GalleryScreen(
    uiState: PixelUiState,
    onTemplateClick: (Template) -> Unit,
    onArtworkClick: (ArtworkEntity) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Pixel Color",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Шаблоны") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Мои работы") })
        }

        if (selectedTab == 0) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.templates, key = { it.id }) { template ->
                    val filledMap = uiState.progressMap[template.id] ?: emptyMap()
                    val total = template.totalColorableCells.coerceAtLeast(1)

                    var correctCount = 0
                    filledMap.forEach { (key, color) ->
                        val (r, c) = key.split("_").map { it.toInt() }
                        if (template.grid[r][c] == color) correctCount++
                    }
                    val percent = ((correctCount * 100) / total).coerceIn(0, 100)

                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onTemplateClick(template) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            MiniTemplatePreview(
                                template = template,
                                filledCells = filledMap,
                                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = template.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                        }
                    }
                }
            }
        } else {
            if (uiState.artworks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Нет сохранённых работ.") }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(uiState.artworks, key = { it.id }) { artwork ->
                        val bitmap = remember(artwork.filePath) { BitmapFactory.decodeFile(artwork.filePath) }
                        Card(modifier = Modifier.fillMaxWidth().clickable { onArtworkClick(artwork) }) {
                            Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(130.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
                                Text(text = artwork.templateName, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MiniTemplatePreview(template: Template, filledCells: Map<String, Int>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(4.dp)) {
        val cellW = size.width / template.cols
        val cellH = size.height / template.rows
        for (r in 0 until template.rows) {
            for (c in 0 until template.cols) {
                val num = template.grid.getOrNull(r)?.getOrNull(c) ?: 0
                if (num > 0) {
                    val appliedColor = filledCells["${r}_${c}"]
                    val hex = template.palette[appliedColor ?: num] ?: "#000000"
                    val baseColor = parseComposeColor(hex)
                    drawRect(
                        color = if (appliedColor == num) baseColor else baseColor.copy(alpha = 0.2f),
                        topLeft = Offset(c * cellW, r * cellH),
                        size = Size(cellW, cellH)
                    )
                }
            }
        }
    }
}

@Composable
fun ColoringScreen(
    template: Template,
    filledCells: Map<String, Int>,
    activeColorNumber: Int,
    isCompleted: Boolean,
    statusMessage: String?,
    sessionTimeSeconds: Long, // Добавлено время
    onBack: () -> Unit,
    onSelectColor: (Int) -> Unit,
    onCellTap: (Int, Int, Boolean) -> Unit,
    onSavePng: (Int) -> Unit,
    onReset: () -> Unit,
    completedColors: Set<Int>
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Форматирование таймера
    val minutes = sessionTimeSeconds / 60
    val seconds = sessionTimeSeconds % 60
    val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    var correctCount = 0
    filledCells.forEach { (key, color) ->
        val (r, c) = key.split("_").map { it.toInt() }
        if (template.grid[r][c] == color) correctCount++
    }
    val progressFraction = (correctCount.toFloat() / template.totalColorableCells.coerceAtLeast(1)).coerceIn(0f, 1f)

    // Состояние режима работы (Рисование или Навигация)
    var isMoveMode by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onBack) { Text("←") }

                    // Блок с прогрессом и таймером
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "${(progressFraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = "⏱ $timeString", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }

                    // Переключатель режима
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { isMoveMode = !isMoveMode },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isMoveMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(if (isMoveMode) "✋ Лупа" else "🖌 Кисть")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Сброс") }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progressFraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
            }
        }

        if (isCompleted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎉 Раскрашено за $timeString!", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSavePng(1) }) { Text("1×") }
                        Button(onClick = { onSavePng(2) }) { Text("2×") }
                    }
                }
            }
        }

        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    PixelGridView(template, filledCells, activeColorNumber, isMoveMode, onCellTap)
                }
                ColorPaletteSidebar(palette = template.palette, activeColorNumber = activeColorNumber, completedColors = completedColors, onSelectColor = onSelectColor)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PixelGridView(template, filledCells, activeColorNumber, isMoveMode, onCellTap)
                }
                ColorPaletteBottomBar(palette = template.palette, activeColorNumber = activeColorNumber, completedColors = completedColors, onSelectColor = onSelectColor)
            }
        }
    }
}

@Composable
fun PixelGridView(
    template: Template,
    filledCells: Map<String, Int>,
    activeColorNumber: Int,
    isMoveMode: Boolean, // Текущий режим
    onCellTap: (Int, Int, Boolean) -> Unit
) {
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true } }

    // Состояние зума и перемещения
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(4.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        val boardSize = min(maxWidth.value, maxHeight.value).dp

        Canvas(
            modifier = Modifier
                .size(boardSize)
                .background(Color.White)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .border(2.dp, Color.DarkGray)
                .pointerInput(template.id, activeColorNumber, isMoveMode) {
                    if (isMoveMode) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f) // Максимальный зум 8x
                            offset += pan
                        }
                    } else {
                        detectTapGestures { tapOffset ->
                            val col = (tapOffset.x / (size.width / template.cols)).toInt()
                            val row = (tapOffset.y / (size.height / template.rows)).toInt()
                            if (row in 0 until template.rows && col in 0 until template.cols) onCellTap(row, col, false)
                        }
                    }
                }
                .pointerInput(template.id, activeColorNumber, isMoveMode) {
                    if (!isMoveMode) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val pos = change.position
                            val col = (pos.x / (size.width / template.cols)).toInt()
                            val row = (pos.y / (size.height / template.rows)).toInt()
                            if (row in 0 until template.rows && col in 0 until template.cols) onCellTap(row, col, true)
                        }
                    }
                }
        ) {
            val cellW = size.width / template.cols
            val cellH = size.height / template.rows
            textPaint.textSize = min(cellW, cellH) * 0.52f

            for (r in 0 until template.rows) {
                for (c in 0 until template.cols) {
                    val expectedColor = template.grid.getOrNull(r)?.getOrNull(c) ?: 0
                    val topLeft = Offset(c * cellW, r * cellH)
                    val cellSize = Size(cellW, cellH)
                    val key = "${r}_${c}"

                    if (expectedColor > 0) {
                        val appliedColor = filledCells[key]

                        if (appliedColor != null) {
                            val hex = template.palette[appliedColor] ?: "#000000"
                            val baseColor = parseComposeColor(hex)
                            if (appliedColor == expectedColor) {
                                drawRect(color = baseColor, topLeft = topLeft, size = cellSize)
                            } else {
                                drawRect(color = baseColor.copy(alpha = 0.4f), topLeft = topLeft, size = cellSize)
                                textPaint.color = Color(0xFFC62828).toArgb()
                                val centerY = r * cellH + cellH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                                drawContext.canvas.nativeCanvas.drawText(expectedColor.toString(), c * cellW + cellW / 2f, centerY, textPaint)
                            }
                        } else {
                            val bgTint = if (expectedColor == activeColorNumber) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
                            drawRect(color = bgTint, topLeft = topLeft, size = cellSize)

                            textPaint.color = if (expectedColor == activeColorNumber) Color(0xFF0D47A1).toArgb() else Color(0xFF424242).toArgb()
                            val centerY = r * cellH + cellH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                            drawContext.canvas.nativeCanvas.drawText(expectedColor.toString(), c * cellW + cellW / 2f, centerY, textPaint)
                        }
                    }
                    drawRect(color = Color(0xFFBDBDBD), topLeft = topLeft, size = cellSize, style = Stroke(width = 1f))
                }
            }
        }
    }
}

@Composable
fun ColorPaletteBottomBar(palette: Map<Int, String>, activeColorNumber: Int, completedColors: Set<Int>, onSelectColor: (Int) -> Unit) {
    Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(palette.entries.sortedBy { it.key }) { (num, hex) ->
                PaletteCircleButton(number = num, hex = hex, isSelected = num == activeColorNumber, isCompleted = num in completedColors, onClick = { onSelectColor(num) })
            }
        }
    }
}

@Composable
fun ColorPaletteSidebar(palette: Map<Int, String>, activeColorNumber: Int, completedColors: Set<Int>, onSelectColor: (Int) -> Unit) {
    Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(14.dp), modifier = Modifier.width(84.dp).fillMaxHeight()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            items(palette.entries.sortedBy { it.key }) { (num, hex) ->
                PaletteCircleButton(number = num, hex = hex, isSelected = num == activeColorNumber, isCompleted = num in completedColors, onClick = { onSelectColor(num) })
            }
        }
    }
}

@Composable
fun PaletteCircleButton(number: Int, hex: String, isSelected: Boolean, isCompleted: Boolean, onClick: () -> Unit) {
    val bgColor = parseComposeColor(hex)
    val textColor = if ((0.299f * bgColor.red + 0.587f * bgColor.green + 0.114f * bgColor.blue) > 0.6f) Color.Black else Color.White

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(if (isSelected) 54.dp else 44.dp)
            .clip(CircleShape)
            .background(bgColor)
            .alpha(if (isCompleted) 0.3f else 1f) // Тускнеет, если закончен
            .border(width = if (isSelected) 4.dp else 1.5.dp, color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray, shape = CircleShape)
            .clickable(enabled = !isCompleted) { onClick() }
    ) {
        if (isCompleted) {
            Text("✓", color = textColor, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        } else {
            Text(text = number.toString(), color = textColor, fontWeight = FontWeight.Bold, fontSize = if (isSelected) 18.sp else 15.sp)
        }
    }
}

// ЭкранArtworkViewScreen остался без изменений
@Composable
fun ArtworkViewScreen(artwork: ArtworkEntity, onBack: () -> Unit, onShare: (ArtworkEntity) -> Unit, onDelete: (ArtworkEntity) -> Unit) {
    val bitmap = remember(artwork.filePath) { BitmapFactory.decodeFile(artwork.filePath) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack) { Text("←") }
            Text(artwork.templateName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(64.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(2.dp, Color.LightGray, RoundedCornerShape(12.dp)).padding(16.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onShare(artwork) }, modifier = Modifier.weight(1f)) { Text("Поделиться") }
            OutlinedButton(onClick = { onDelete(artwork) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) { Text("Удалить") }
        }
    }
}

private fun parseComposeColor(hex: String): Color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Black }