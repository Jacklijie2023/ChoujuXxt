package `fun`.kirari.hanako.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 手机相册照片条目。 */
private data class PhonePhoto(
    val uri: Uri,
    val bucketName: String,
    val dateTakenMillis: Long
)

/** 查询照片（按时间倒序；可按相册名过滤）。需有相册读取权限。 */
private fun queryPhotos(context: Context, album: String?): List<PhonePhoto> {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN
    )
    val selection = album?.let { "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" }
    val selectionArgs = album?.let { arrayOf(it) }
    val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
    return runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    add(
                        PhonePhoto(
                            uri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            ),
                            bucketName = cursor.getString(bucketColumn) ?: "",
                            dateTakenMillis = cursor.getLong(dateColumn)
                        )
                    )
                }
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

/** 查询相册分类名（去重，保持出现顺序）。 */
private fun queryAlbums(context: Context): List<String> {
    val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
    return runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val buckets = LinkedHashSet<String>()
            while (cursor.moveToNext()) {
                cursor.getString(column)?.takeIf { it.isNotBlank() }?.let(buckets::add)
            }
            buckets.toList()
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

/** 照片缩略图：API 29+ 走 [android.content.ContentResolver.loadThumbnail]，否则走 Thumbnails。 */
@Composable
private fun PhotoThumb(photo: PhonePhoto, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, photo.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver
                        .loadThumbnail(photo.uri, Size(256, 256), null)
                        .asImageBitmap()
                } else {
                    val id = photo.uri.lastPathSegment?.toLongOrNull()
                    val thumb = id?.let {
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver,
                            it,
                            MediaStore.Images.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                    thumb?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 输入区展开面板内的功能项（拍照 / 相册 / 本地文件）。 */
@Composable
private fun GalleryActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 输入区「+」展开后的功能面板（图 4/5 形态）。
 *
 * - 功能行：拍照 / 相册 / 本地文件三项等宽并排、间距一致；点击由宿主真实拉起
 *   系统相机（ACTION_IMAGE_CAPTURE）/ 系统相册选择器（PickVisualMedia）/ 系统文件选择器（OpenDocument）。
 * - 相册浏览（主界面，图 5）：照片上方不展示相册分类标题，仅展示两行照片；
 *   向上拖动照片区拉出「抽屉式」全相册，抽屉顶部居中标题可点击切换手机相册分类，
 *   向下滑动收起抽屉回到本面板初始状态。
 * - 图片仅浏览与选择，不发送给 AI（多模态能力未接入）。
 *
 * @param onCamera 宿主提供的「拍照」回调，负责真实拉起系统相机。
 * @param onGallery 宿主提供的「相册」回调，负责真实拉起系统相册选择器。
 * @param onFile 宿主提供的「本地文件」回调，负责真实拉起系统文件选择器。
 * @param overlaySafe 迷你窗（overlay Service 宿主，无 Activity token）时为 true：
 *   相册分类改用窗口内内联列表（ModalBottomSheet / DropdownMenu 都会创建新窗口触发 BadTokenException）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatInputActionPanel(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    modifier: Modifier = Modifier,
    overlaySafe: Boolean = false
) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<PhonePhoto>>(emptyList()) }
    var albums by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAlbumMenu by remember { mutableStateOf(false) }
    // 主界面（图 5）：向上拖动照片区拉出「抽屉式」全相册。
    var drawerOpen by remember { mutableStateOf(false) }

    // 照片多选：点击切换选中态（照片条与抽屉共用同一选中集合）。
    val togglePhoto: (String) -> Unit = { key ->
        selectedUris = if (key in selectedUris) selectedUris - key else selectedUris + key
    }

    LaunchedEffect(hasPermission, selectedAlbum) {
        if (!hasPermission) {
            photos = emptyList()
            return@LaunchedEffect
        }
        photos = withContext(Dispatchers.IO) { queryPhotos(context, selectedAlbum) }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            albums = withContext(Dispatchers.IO) { queryAlbums(context) }
        } else {
            albums = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // 三个功能项等宽并排、间距一致（参考图：拍照 / 相册 / 本地文件）。
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            GalleryActionTile(
                icon = Icons.Default.CameraAlt,
                label = "拍照",
                onClick = { onCamera() },
                modifier = Modifier.weight(1f)
            )
            GalleryActionTile(
                icon = Icons.Default.PhotoLibrary,
                label = "相册",
                onClick = { onGallery() },
                modifier = Modifier.weight(1f)
            )
            GalleryActionTile(
                icon = Icons.Default.Folder,
                label = "本地文件",
                onClick = { onFile() },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (!hasPermission) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "相册权限",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Text(
                            "授权后可浏览手机照片并选择图片（仅选择，不发送）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onRequestPermission) {
                        Text("允许访问")
                    }
                }
            }
        } else if (overlaySafe) {
            // 迷你窗（overlay Service 宿主，无 Activity token）：沿用窗口内内联分类列表 + 网格。
            // ModalBottomSheet / DropdownMenu 都会创建新窗口，在迷你窗里会触发 BadTokenException，故不在此使用。
            Column {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showAlbumMenu = !showAlbumMenu }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        selectedAlbum ?: "全部照片",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "选择相册",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (showAlbumMenu) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 2.dp
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            AlbumOptionRow(
                                label = "全部照片",
                                selected = selectedAlbum == null,
                                onClick = {
                                    selectedAlbum = null
                                    showAlbumMenu = false
                                }
                            )
                            albums.forEach { album ->
                                AlbumOptionRow(
                                    label = album,
                                    selected = selectedAlbum == album,
                                    onClick = {
                                        selectedAlbum = album
                                        showAlbumMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (photos.isEmpty()) {
                EmptyPhotos()
            } else {
                PhotoGrid(
                    photos = photos,
                    selectedUris = selectedUris,
                    onToggle = togglePhoto
                )
            }
        } else {
            // 主界面（图 5 层级 0）：照片上方不展示相册分类标题，仅展示两行照片；
            // 向上拖动照片区拉出「抽屉式」全相册。
            if (photos.isEmpty()) {
                EmptyPhotos()
            } else {
                DragUpPhotoStrip(
                    photos = photos.take(8),
                    selectedUris = selectedUris,
                    onToggle = togglePhoto,
                    onPullUp = { drawerOpen = true }
                )
            }
        }

        if (selectedUris.isNotEmpty()) {
            PhotoSelectionHint(selectedUris.size)
        }
    }

    // 抽屉式全相册（仅主界面）：顶部居中分类标题（点击切换相册）+ 全量照片网格；
    // 向下滑动由 ModalBottomSheet 自身收起，回到「+」面板初始状态（图 5）。
    if (!overlaySafe && drawerOpen) {
        ModalBottomSheet(
            onDismissRequest = { drawerOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AlbumDrawerContent(
                photos = photos,
                albums = albums,
                selectedAlbum = selectedAlbum,
                selectedUris = selectedUris,
                onSelectAlbum = { selectedAlbum = it },
                onToggle = togglePhoto
            )
        }
    }
}

/** 单一相册分类选项行（内联列表用）。 */
@Composable
private fun AlbumOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "当前相册",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 可选中的照片单元格（照片条与抽屉共用）：点击切换选中，选中显示遮罩 + 勾选。 */
@Composable
private fun SelectablePhotoCell(
    photo: PhonePhoto,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
    ) {
        PhotoThumb(photo = photo, modifier = Modifier.fillMaxSize())
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** 照片网格（4 列，可内部滚动）。 */
@Composable
private fun PhotoGrid(
    photos: List<PhonePhoto>,
    selectedUris: Set<String>,
    onToggle: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(photos, key = { it.uri.toString() }) { photo ->
            val key = photo.uri.toString()
            SelectablePhotoCell(
                photo = photo,
                selected = key in selectedUris,
                onToggle = { onToggle(key) }
            )
        }
    }
}

/**
 * 主界面「+」面板层级 0 的照片条（图 5）：不展示相册分类标题，仅 2 行照片；
 * 向上拖动（不滚动）达阈值后回调 [onPullUp] 拉出抽屉式全相册。
 */
@Composable
private fun DragUpPhotoStrip(
    photos: List<PhonePhoto>,
    selectedUris: Set<String>,
    onToggle: (String) -> Unit,
    onPullUp: () -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 48.dp.toPx() }
    var dragAccum by remember { mutableStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragAccum = 0f
                        triggered = false
                    },
                    onDragCancel = { dragAccum = 0f },
                    onDragEnd = { dragAccum = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        if (!triggered) {
                            dragAccum += dragAmount
                            if (dragAccum <= -thresholdPx) {
                                triggered = true
                                onPullUp()
                            }
                        }
                    }
                )
            }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(photos, key = { it.uri.toString() }) { photo ->
                val key = photo.uri.toString()
                SelectablePhotoCell(
                    photo = photo,
                    selected = key in selectedUris,
                    onToggle = { onToggle(key) }
                )
            }
        }
        // 上拉把手：提示此处可向上拖动拉出全相册。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}

/** 空相册占位。 */
@Composable
private fun EmptyPhotos() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "暂无照片",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 已选照片数量提示。 */
@Composable
private fun PhotoSelectionHint(count: Int) {
    Text(
        "已选 $count 张（图片发送即将接入）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** 抽屉式全相册内容：顶部居中分类标题（点击展开相册列表切换）+ 全量照片网格。 */
@Composable
private fun AlbumDrawerContent(
    photos: List<PhonePhoto>,
    albums: List<String>,
    selectedAlbum: String?,
    selectedUris: Set<String>,
    onSelectAlbum: (String?) -> Unit,
    onToggle: (String) -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        // 顶部居中分类标题：点击切换手机相册分类。
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { pickerOpen = !pickerOpen }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    selectedAlbum ?: "全部照片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "切换相册"
                )
            }
        }

        AnimatedVisibility(visible = pickerOpen) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    AlbumOptionRow(
                        label = "全部照片",
                        selected = selectedAlbum == null,
                        onClick = {
                            onSelectAlbum(null)
                            pickerOpen = false
                        }
                    )
                    albums.forEach { album ->
                        AlbumOptionRow(
                            label = album,
                            selected = selectedAlbum == album,
                            onClick = {
                                onSelectAlbum(album)
                                pickerOpen = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (photos.isEmpty()) {
            EmptyPhotos()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(photos, key = { it.uri.toString() }) { photo ->
                    val key = photo.uri.toString()
                    SelectablePhotoCell(
                        photo = photo,
                        selected = key in selectedUris,
                        onToggle = { onToggle(key) }
                    )
                }
            }
        }

        if (selectedUris.isNotEmpty()) {
            PhotoSelectionHint(selectedUris.size)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 需要相册读取权限的权限名（按系统版本选择）。 */
internal fun photoReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/** 是否已授予相册读取权限。 */
internal fun hasPhotoReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, photoReadPermission()) ==
        PackageManager.PERMISSION_GRANTED
