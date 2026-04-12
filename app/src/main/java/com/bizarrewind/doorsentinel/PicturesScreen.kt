package com.bizarrewind.doorsentinel

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Colour palette ────────────────────────────────────────────────────────────
private val PicDarkBg          = Color(0xFF0D0F14)
private val PicCardBg          = Color(0xFF171B22)
private val PicAccentBlue      = Color(0xFF4A9EFF)
private val PicAccentGreen     = Color(0xFF34D399)
private val PicAccentRed       = Color(0xFFFF5C5C)
private val PicTextPrimary     = Color(0xFFE8EAF0)
private val PicTextSub         = Color(0xFF8B95A8)
private val PicSelectedBorder  = Color(0xFF4A9EFF)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PicturesScreen(
    photos: List<File>,
    onDeletePhoto: (File) -> Unit,
    onUploadPhoto: (File) -> Unit,
    onExportPhotos: (List<File>) -> Unit
) {
    var selectedFile  by remember { mutableStateOf<File?>(null) }
    val sheetState    = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Multi-select state
    var selectionMode by remember { mutableStateOf(false) }
    val selectedFiles = remember { mutableStateSetOf<File>() }

    // Image viewer state
    var viewerIndex by remember { mutableIntStateOf(-1) }

    // Exit selection mode when photos list becomes empty
    LaunchedEffect(photos.isEmpty()) {
        if (photos.isEmpty()) { selectionMode = false; selectedFiles.clear() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PicDarkBg)
    ) {
        if (photos.isEmpty()) {
            // ── Empty state ────────────────────────────────────────────────────
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📷", fontSize = 56.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "No captures yet",
                    color = PicTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Photos will appear here after a trigger event",
                    color = PicTextSub,
                    fontSize = 13.sp
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Header ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectionMode) {
                        Text(
                            "${selectedFiles.size} selected",
                            color = PicAccentBlue,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        val allSelected = selectedFiles.size == photos.size
                        TextButton(onClick = {
                            if (allSelected) selectedFiles.clear()
                            else { selectedFiles.clear(); selectedFiles.addAll(photos) }
                        }) {
                            Text(
                                if (allSelected) "Deselect All" else "Select All",
                                color = PicAccentBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        TextButton(onClick = { selectionMode = false; selectedFiles.clear() }) {
                            Text("Cancel", color = PicTextSub, fontSize = 12.sp)
                        }
                    } else {
                        Text(
                            "CAPTURES",
                            color = PicAccentBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${photos.size} photo${if (photos.size == 1) "" else "s"}",
                            color = PicTextSub,
                            fontSize = 12.sp
                        )
                    }
                }

                // ── Grid ──────────────────────────────────────────────────────
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement   = Arrangement.spacedBy(10.dp),
                    contentPadding        = PaddingValues(bottom = 100.dp)
                ) {
                    items(photos, key = { it.absolutePath }) { file ->
                        val isSelected = file in selectedFiles
                        val idx = photos.indexOf(file)
                        PhotoCard(
                            file       = file,
                            isSelected = isSelected,
                            selectionMode = selectionMode,
                            onTap      = {
                                if (selectionMode) {
                                    if (isSelected) selectedFiles.remove(file)
                                    else selectedFiles.add(file)
                                    if (selectedFiles.isEmpty()) selectionMode = false
                                } else {
                                    // ── Open image viewer ──
                                    viewerIndex = idx
                                }
                            },
                            onLongPress = {
                                if (!selectionMode) {
                                    if (photos.size > 1) {
                                        selectionMode = true
                                        selectedFiles.add(file)
                                    } else {
                                        selectedFile = file
                                    }
                                } else {
                                    selectedFile = file
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── Multi-select action bar ────────────────────────────────────────────
        AnimatedVisibility(
            visible = selectionMode && selectedFiles.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape  = RoundedCornerShape(20.dp),
                color  = Color(0xFF1E2330),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            onExportPhotos(selectedFiles.toList())
                            selectionMode = false
                            selectedFiles.clear()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PicAccentGreen),
                        shape  = RoundedCornerShape(12.dp)
                    ) {
                        Text("📁 Export (${selectedFiles.size})", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = {
                            val toDelete = selectedFiles.toList()
                            toDelete.forEach { onDeletePhoto(it) }
                            selectedFiles.clear()
                            selectionMode = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PicAccentRed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PicAccentRed.copy(alpha = 0.6f)),
                        shape  = RoundedCornerShape(12.dp)
                    ) {
                        Text("🗑️ Delete", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ── Fullscreen image viewer overlay ────────────────────────────────────
        if (viewerIndex >= 0 && viewerIndex < photos.size) {
            ImageViewerOverlay(
                photos       = photos,
                initialIndex = viewerIndex,
                onDismiss    = { viewerIndex = -1 },
                onDelete     = { file ->
                    onDeletePhoto(file)
                    viewerIndex = -1
                },
                onUpload     = { file -> onUploadPhoto(file) },
                onExport     = { file -> onExportPhotos(listOf(file)) }
            )
        }
    }

    // ── Bottom sheet for long-pressed photo ───────────────────────────────────
    if (selectedFile != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedFile = null },
            sheetState       = sheetState,
            containerColor   = PicCardBg,
            shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            val file = selectedFile!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
                    .animateContentSize()
            ) {
                Text(
                    text = file.name,
                    color = PicTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${formatSize(file.length())}  •  ${formatDate(file.lastModified())}",
                    color = PicTextSub,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        onExportPhotos(listOf(file))
                        selectedFile = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = PicAccentGreen),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Text("📁  Export to Downloads", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        onUploadPhoto(file)
                        selectedFile = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = PicAccentBlue),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Text("☁️  Upload to Firebase", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        onDeletePhoto(file)
                        selectedFile = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = PicAccentRed),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, PicAccentRed.copy(alpha = 0.6f)),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Text("🗑️  Delete from Device", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                TextButton(
                    onClick  = { selectedFile = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel", color = PicTextSub)
                }
            }
        }
    }
}

// ── Photo thumbnail card (async loading) ──────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCard(
    file: File,
    isSelected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) PicSelectedBorder else Color.Transparent,
        animationSpec = tween(150), label = "selectionBorder"
    )

    // ── Async thumbnail loading with LRU cache ──
    var bitmap by remember(file.absolutePath) { mutableStateOf(ThumbnailCache.getFromCache(file)) }
    LaunchedEffect(file.absolutePath, file.lastModified()) {
        if (bitmap == null) {
            bitmap = ThumbnailCache.getThumbnail(file, 180)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(
                width = if (isSelected) 2.5.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick     = onTap,
                onLongClick = onLongPress
            ),
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PicCardBg)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap             = bitmap!!.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                )
            } else {
                // ── Placeholder shimmer ──
                Box(
                    modifier         = Modifier.fillMaxSize().background(Color(0xFF252B38)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = PicAccentBlue.copy(alpha = 0.5f),
                        strokeWidth = 2.dp
                    )
                }
            }

            // Filename label at the bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text     = file.name.substringBefore("_burst").takeLast(8) +
                               "  burst${file.name.substringAfter("_burst").removeSuffix(".jpg")}",
                    color    = PicTextPrimary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Selection checkbox overlay (top-right)
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) PicAccentBlue else Color.Black.copy(alpha = 0.55f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("⋮", color = PicTextSub, fontSize = 14.sp)
                }
            }
        }
    }
}

// ── Fullscreen image viewer ───────────────────────────────────────────────────

@Composable
private fun ImageViewerOverlay(
    photos: List<File>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onDelete: (File) -> Unit,
    onUpload: (File) -> Unit,
    onExport: (File) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    val currentFile = photos.getOrNull(pagerState.currentPage) ?: run { onDismiss(); return }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Pager with zoomable images ──
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ZoomableImage(file = photos[page])
        }

        // ── Top bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close button
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onDismiss() },
                color = Color.White.copy(alpha = 0.15f),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    currentFile.name,
                    color = PicTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatSize(currentFile.length())}  •  ${formatDate(currentFile.lastModified())}",
                    color = PicTextSub,
                    fontSize = 11.sp
                )
            }

            Text(
                "${pagerState.currentPage + 1} / ${photos.size}",
                color = PicTextSub,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // ── Bottom action bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Export
            Button(
                onClick = { onExport(currentFile) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PicAccentGreen),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Text("📁", fontSize = 16.sp)
                Spacer(Modifier.width(4.dp))
                Text("Export", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            // Upload
            Button(
                onClick = { onUpload(currentFile) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PicAccentBlue),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Text("☁️", fontSize = 16.sp)
                Spacer(Modifier.width(4.dp))
                Text("Upload", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            // Delete
            OutlinedButton(
                onClick = { onDelete(currentFile) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PicAccentRed),
                border = androidx.compose.foundation.BorderStroke(1.dp, PicAccentRed.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Text("🗑️", fontSize = 16.sp)
                Spacer(Modifier.width(4.dp))
                Text("Delete", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Zoomable image ────────────────────────────────────────────────────────────

@Composable
private fun ZoomableImage(file: File) {
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.absolutePath) {
        bitmap = ThumbnailCache.getFullResolution(file)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offset = if (scale > 1f) {
                                offset + pan
                            } else {
                                Offset.Zero
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                // Double-tap to toggle zoom
                                if (scale > 1.5f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 3f
                                }
                            }
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        } else {
            CircularProgressIndicator(
                color = PicAccentBlue,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576f)
    bytes >= 1_024     -> "%.0f KB".format(bytes / 1_024f)
    else               -> "$bytes B"
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(millis))
