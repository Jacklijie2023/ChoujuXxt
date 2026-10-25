package `fun`.kirari.hanako.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 手机相册照片条目。 */
private data class PhonePhoto(
    val uri: Uri,
    val bucketName: String,
    val dateTakenMillis: Long
)

/** 相册分类信息：分类名 + 该分类首图（最新一张）Uri + 照片总数。 */
private data class AlbumInfo(
    val name: String,
    val coverUri: Uri?,
    val count: Int
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

/** 查询相册分类信息（按拍照时间倒序：每个分类首图取该分类最新一张照片，并累计照片总数）。 */
private fun queryAlbumInfos(context: Context): List<AlbumInfo> {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    )
    val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
    return runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val albums = LinkedHashMap<String, AlbumInfo>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(bucketColumn)?.takeIf { it.isNotBlank() } ?: continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn)
                )
                val existing = albums[name]
                albums[name] = if (existing == null) {
                    // 已按拍摄时间倒序：分类中首次出现的即为最新一张，作为封面。
                    AlbumInfo(name = name, coverUri = uri, count = 1)
                } else {
                    existing.copy(count = existing.count + 1)
                }
            }
            albums.values.toList()
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

/** 照片缩略图：API 29+ 走 [android.content.ContentResolver.loadThumbnail]，否则走 Thumbnails。 */
@Composable
private fun PhotoThumb(photo: PhonePhoto, modifier: Modifier = Modifier) {
    UriThumb(uri = photo.uri, modifier = modifier)
}

/** 按 Uri 加载缩略图（照片单元格与相册分类首图共用）。 */
@Composable
private fun UriThumb(uri: Uri?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver
                            .loadThumbnail(uri, Size(256, 256), null)
                            .asImageBitmap()
                    } else {
                        val id = uri.lastPathSegment?.toLongOrNull()
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
 * @param onFile 宿主提供的「本地文件」回调，负责真实拉起系统文件选择器。
 * @param onOpenAlbumPage 「相册」按钮 / 照片区上拉：打开宿主的全屏相册页（同一个页面）。
 * @param onAddPhoto 照片区点击回调：主界面照片条与迷你窗网格共用，由宿主切换该照片是否在附件区。
 * @param attachedUris 已在附件区的照片 Uri（照片条 / 网格勾选态，迷你窗同此）。
 * @param overlaySafe 迷你窗（overlay Service 宿主，无 Activity token）时为 true：
 *   相册分类改用窗口内内联列表（创建新窗口会触发 BadTokenException）。
 */
@Composable
internal fun ChatInputActionPanel(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onOpenAlbumPage: () -> Unit,
    onAddPhoto: (Uri) -> Unit,
    attachedUris: Set<String>,
    modifier: Modifier = Modifier,
    overlaySafe: Boolean = false
) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<PhonePhoto>>(emptyList()) }
    var albumInfos by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var showAlbumMenu by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission, selectedAlbum) {
        if (!hasPermission) {
            photos = emptyList()
            return@LaunchedEffect
        }
        photos = withContext(Dispatchers.IO) { queryPhotos(context, selectedAlbum) }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            albumInfos = withContext(Dispatchers.IO) { queryAlbumInfos(context) }
        } else {
            albumInfos = emptyList()
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
                onClick = { onOpenAlbumPage() },
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
                            AlbumInfoRow(
                                info = totalAlbumInfo(albumInfos),
                                selected = selectedAlbum == null,
                                onClick = {
                                    selectedAlbum = null
                                    showAlbumMenu = false
                                }
                            )
                            albumInfos.forEach { album ->
                                AlbumInfoRow(
                                    info = album,
                                    selected = selectedAlbum == album.name,
                                    onClick = {
                                        selectedAlbum = album.name
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
                // 点一下即加入/移出输入框附件区（与主界面照片条同一套选中态，宿主负责真正发送）。
                PhotoGrid(
                    photos = photos,
                    selectedUris = attachedUris,
                    onToggle = { key ->
                        photos.firstOrNull { it.uri.toString() == key }?.let { onAddPhoto(it.uri) }
                    }
                )
            }

            if (attachedUris.isNotEmpty()) {
                PhotoSelectionHint(attachedUris.size)
            }
        } else {
            // 主界面「+」面板层级 0（图 3/5）：照片上方不展示相册分类标题，仅展示两行照片；
            // 点一下照片即把它加入输入框顶部附件区（单选式）；向上拖动照片区打开全屏相册页。
            if (photos.isEmpty()) {
                EmptyPhotos()
            } else {
                DragUpPhotoStrip(
                    photos = photos.take(8),
                    attachedUris = attachedUris,
                    onAdd = onAddPhoto,
                    onPullUp = onOpenAlbumPage
                )
            }
        }
    }
}

/** 「全部照片」分类信息：封面取最新一张（分类列表已按时间倒序），总数取各分类求和。 */
private fun totalAlbumInfo(albums: List<AlbumInfo>): AlbumInfo =
    AlbumInfo(
        name = "全部照片",
        coverUri = albums.firstOrNull()?.coverUri,
        count = albums.sumOf { it.count }
    )

/** 相册分类选项行（图 4）：左侧该分类首图缩略图 + 右侧分类名 + 分类照片总数。 */
@Composable
private fun AlbumInfoRow(
    info: AlbumInfo,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        UriThumb(
            uri = info.coverUri,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                info.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${info.count}张",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
 * 主界面「+」面板层级 0 的照片条（图 3/5）：不展示相册分类标题，仅 2 行照片；
 * 点一下照片 → [onAdd] 把它加入输入框顶部附件区（已加入的显示勾选）；
 * 向上拖动（不滚动）达阈值后回调 [onPullUp] 打开全屏相册页。
  */
@Composable
private fun DragUpPhotoStrip(
    photos: List<PhonePhoto>,
    attachedUris: Set<String>,
    onAdd: (Uri) -> Unit,
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
                    selected = key in attachedUris,
                    onToggle = { onAdd(photo.uri) }
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
        "已选 $count 张，点发送即随消息发出",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/**
 * 全屏相册选择页（图 1 / 图 2）：由「+」面板的「相册」入口或照片区上拉唤起（同一个页面）。
 *
 * - 顶部左上角 × 关闭；顶部居中可点击标题切换手机相册分类。
 * - 功能行：拍照 / 本地文件；下方展示「共 N 项」。
 * - 4 列网格多选，最多 [maxSelectable] 张；选中照片后底部才出现「确认上传（n/max）」按钮。
 * - 窗口内覆盖渲染（Activity 宿主），不使用 ModalBottomSheet：需全屏且与顶部状态栏留间距。
 */
@Composable
internal fun AlbumPickerOverlay(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    maxSelectable: Int = 20
) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<PhonePhoto>>(emptyList()) }
    var albumInfos by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // 已选照片 Uri 列表（保持选择顺序，用于计数与「确认上传」）。
    var selection by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(hasPermission, selectedAlbum) {
        photos = if (hasPermission) {
            withContext(Dispatchers.IO) { queryPhotos(context, selectedAlbum) }
        } else {
            emptyList()
        }
    }
    LaunchedEffect(hasPermission) {
        albumInfos = if (hasPermission) {
            withContext(Dispatchers.IO) { queryAlbumInfos(context) }
        } else {
            emptyList()
        }
    }

    val toggle: (String) -> Unit = { key ->
        selection = when {
            key in selection -> selection - key
            selection.size >= maxSelectable -> {
                Toast.makeText(context, "最多只能选择 $maxSelectable 张照片", Toast.LENGTH_SHORT).show()
                selection
            }
            else -> selection + key
        }
    }

    val gridState = rememberLazyGridState()
    // 下拉收起交互：页面跟随手指向下位移（收缩），松手时若超过阈值则动画滑出关闭，否则回弹。
    val density = LocalDensity.current
    var surfaceHeightPx by remember { mutableStateOf(0) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var closeTriggered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 拖动时瞬时跟随手指，松手后以 220ms 缓动吸附（回弹或滑出关闭）。
    val sheetOffsetY by animateFloatAsState(
        targetValue = dragOffsetY,
        animationSpec = tween(
            durationMillis = if (dragging) 0 else 220,
            easing = FastOutSlowInEasing
        ),
        label = "albumSheetOffsetY"
    )
    // 页面可下拉的最大位移：整页高度（完全滑出后关闭）。
    fun maxDragPx(): Float = surfaceHeightPx.takeIf { it > 0 }?.toFloat() ?: (with(density) { 128.dp.toPx() })
    // 关闭阈值：下拉超过页面高度约 1/3 才自动隐藏，否则回弹。
    fun closeThresholdPx(): Float = maxDragPx() * 1f / 3f

    fun settleSheet() {
        if (closeTriggered) return
        if (dragOffsetY >= closeThresholdPx()) {
            // 过阈值：先滑出页面，再真正关闭（动态收缩关闭效果）。
            closeTriggered = true
            dragOffsetY = maxDragPx()
            scope.launch {
                delay(240)
                onDismiss()
            }
        } else {
            // 未过阈值：回弹恢复。
            dragOffsetY = 0f
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：点击关闭相册页。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight()
                // 与顶部手机导航栏（状态栏）留出间距。
                .statusBarsPadding()
                .padding(top = 12.dp)
                .onSizeChanged { size -> surfaceHeightPx = size.height }
                // 下拉时整页跟随手指向下位移（收缩/关闭的动态效果）。
                // 拖动过程中直接用原始 dragOffsetY 驱动位移，绕过动画的一帧延迟，避免抖动；仅松手后走动画吸附。
                .graphicsLayer { translationY = if (dragging) dragOffsetY else sheetOffsetY },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 全页统一下拉交互：在 Initial 阶段旁路观察，不消费普通滚动事件，网格可正常上下滚动。
                    // 仅当网格位于顶部且手指向下移动时，才识别为「下拉收起」手势并消费位移。
                    // 位移按「手指相对按下点的累计位移 + 当前页面位移」计算，抵消页面位移带来的坐标漂移，
                    // 消除拖动反馈回路导致的抖动，保证页面 1:1 跟随手指。
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                val downY = down.position.y
                                var pulling = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val gridAtTop = gridState.firstVisibleItemIndex == 0 &&
                                        gridState.firstVisibleItemScrollOffset == 0
                                    val fingerDelta = change.position.y - downY
                                    if (!pulling) {
                                        // 关闭动画期间不再响应下拉，避免把正在滑出的页面拉回来。
                                        pulling = !closeTriggered && gridAtTop && fingerDelta > 0
                                        if (pulling) dragging = true
                                    }
                                    if (pulling) {
                                        dragOffsetY = (dragOffsetY + fingerDelta)
                                            .coerceIn(0f, maxDragPx())
                                        change.consume()
                                    }
                                }
                                dragging = false
                                if (pulling) settleSheet()
                            }
                        }
                    }
            ) {
                // 顶部栏：左上角 × 关闭 + 居中可点击标题（切换相册分类）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "关闭相册")
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { menuOpen = !menuOpen }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = selectedAlbum ?: "全部照片",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "切换相册")
                    }
                }

                AnimatedVisibility(visible = menuOpen) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp)
                        ) {
                            AlbumInfoRow(
                                info = totalAlbumInfo(albumInfos),
                                selected = selectedAlbum == null,
                                onClick = {
                                    selectedAlbum = null
                                    menuOpen = false
                                } 
                            )
                            albumInfos.forEach { album ->
                                AlbumInfoRow(
                                    info = album,
                                    selected = selectedAlbum == album.name,
                                    onClick = {
                                        selectedAlbum = album.name
                                        menuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 功能行：拍照 / 本地文件（等宽并排、间距一致）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GalleryActionTile(
                        icon = Icons.Default.CameraAlt,
                        label = "拍照",
                        onClick = onCamera,
                        modifier = Modifier.weight(1f)
                    )
                    GalleryActionTile(
                        icon = Icons.Default.Folder,
                        label = "本地文件",
                        onClick = onFile,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = "共 ${photos.size} 项",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                if (!hasPermission) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("需要相册读取权限", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onRequestPermission) { Text("允许访问") }
                        }
                    }
                } else if (photos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        EmptyPhotos()
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(photos, key = { it.uri.toString() }) { photo ->
                                val key = photo.uri.toString()
                                SelectablePhotoCell(
                                    photo = photo,
                                    selected = key in selection,
                                    onToggle = { toggle(key) }
                                )
                            }
                        }
                    }
                }

                // 仅当选中照片后才出现「确认上传（n/max）」按钮。
                if (selection.isNotEmpty()) {
                    Button(
                        onClick = { onConfirm(selection.map { Uri.parse(it) }) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text("确认上传（${selection.size}/$maxSelectable）")
                    }
                }
            }
        }
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
