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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
 * - 功能行：拍照（占位）/ 相册（无权限时请求权限）/ 本地文件（占位）。
 * - 相册浏览：顶部「全部照片 ▼」选择相册分类，下方照片网格（多选勾选）。
 * - 图片仅浏览与选择，不发送给 AI（多模态能力未接入）。
 *
 * @param overlaySafe 迷你窗（overlay Service 宿主，无 Activity token）时为 true：
 *   相册分类改用窗口内内联列表，不用 DropdownMenu（创建新窗口会触发 BadTokenException）。
 */
@Composable
internal fun ChatInputActionPanel(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    overlaySafe: Boolean = false
) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<PhonePhoto>>(emptyList()) }
    var albums by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            GalleryActionTile(Icons.Default.CameraAlt, "拍照") {
                Toast.makeText(context, "拍照功能正在接入", Toast.LENGTH_SHORT).show()
            }
            GalleryActionTile(Icons.Default.PhotoLibrary, "相册") {
                if (!hasPermission) onRequestPermission()
            }
            GalleryActionTile(Icons.Default.Folder, "本地文件") {
                Toast.makeText(context, "本地文件功能正在接入", Toast.LENGTH_SHORT).show()
            }
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
        } else {
            // 「全部照片」+ 相册分类：
            // 主界面（Activity 宿主）用 DropdownMenu 弹窗；
            // 迷你窗（overlay Service 宿主，无 Activity token）用窗口内内联列表，避免 BadTokenException。
            if (overlaySafe) {
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedAlbum = null
                                            showAlbumMenu = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        "全部照片",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selectedAlbum == null) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "当前相册",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                albums.forEach { album ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedAlbum = album
                                                showAlbumMenu = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            album,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (selectedAlbum == album) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "当前相册",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showAlbumMenu = true }
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
                    DropdownMenu(
                        expanded = showAlbumMenu,
                        onDismissRequest = { showAlbumMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部照片") },
                            onClick = {
                                selectedAlbum = null
                                showAlbumMenu = false
                            }
                        )
                        albums.forEach { album ->
                            DropdownMenuItem(
                                text = { Text(album) },
                                onClick = {
                                    selectedAlbum = album
                                    showAlbumMenu = false
                                }
                            )
                        }
                    }
                }
            }

            if (photos.isEmpty()) {
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
            } else {
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
                        val selected = key in selectedUris
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedUris = if (selected) {
                                        selectedUris - key
                                    } else {
                                        selectedUris + key
                                    }
                                }
                        ) {
                            PhotoThumb(
                                photo = photo,
                                modifier = Modifier.fillMaxSize()
                            )
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
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        ),
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
                }
            }

            if (selectedUris.isNotEmpty()) {
                Text(
                    "已选 ${selectedUris.size} 张（图片发送即将接入）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
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
