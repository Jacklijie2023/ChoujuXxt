package `fun`.kirari.hanako.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import `fun`.kirari.hanako.data.ChatMessage
import `fun`.kirari.hanako.data.ChatMessageStatus
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.overlay.MarkdownLatexText
import java.io.File

/** 品牌圆形占位头像：紫色渐变 + 「H」。 */
@Composable
internal fun HanakoAvatar(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "H",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 聊天消息条目（聊天页与迷你窗共用同一实现，保证视觉一致）。
 *
 * - 用户消息：靠右浅色圆角气泡。
 * - AI 消息：靠左，头像 + 「Hanako」名称 + 正文气泡（[MarkdownLatexText]），
 *   下方为快捷 pill（思考模式 / 联网搜索）与操作栏（复制 / 重新生成 / 语音播放），
 *   出错时保留「响应未完整结束 + 重试」。
 *
 * @param compact 迷你窗精简模式：缩小字号、宽度与头像尺寸。
 * @param showQuickActions 是否显示快捷 pill 与操作栏（迷你窗关闭以保持紧凑）。
 * @param onSpeak 「语音播放」回调（由宿主接入系统 TTS），入参为该条 AI 回复正文。
 * @param isReading 该条消息当前是否正在朗读（为 true 时喇叭图标显示声波动画）。
 * @param onStopSpeak 朗读中再次点击喇叭时回调（停止朗读）。
 * @param onImageClick 点击用户气泡里的已发送图片：宿主据此打开全屏查看（参数为本地图片路径）。
 */
@Composable
internal fun ChatMessageItem(
    message: ChatMessage,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onSpeak: (String) -> Unit = {},
    isReading: Boolean = false,
    onStopSpeak: () -> Unit = {},
    compact: Boolean = false,
    showQuickActions: Boolean = true,
    onImageClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (message.role == ChatRole.User) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                modifier = Modifier.widthIn(max = if (compact) 240.dp else 320.dp),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 6.dp
                ),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Column {
                        if (message.imagePaths.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                message.imagePaths.forEach { path ->
                                    AsyncImage(
                                        model = File(path),
                                        contentDescription = "已发送图片",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(if (compact) 84.dp else 108.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            // 点击缩略图打开全屏查看（与相册里看图一致）。
                                            .clickable { onImageClick(path) }
                                    )
                                }
                            }
                        }
                        // 「仅发送图片」的占位文案只为会话标题与历史保留，气泡里已有图片时不再重复展示。
                        val showText = message.content.isNotBlank() &&
                            !(message.imagePaths.isNotEmpty() &&
                                message.content == ChatMessage.IMAGE_ONLY_PLACEHOLDER)
                        if (showText) {
                            if (message.imagePaths.isNotEmpty()) Spacer(modifier = Modifier.height(6.dp))
                            // 长按可进入文字选择（仅对文本生效，图片缩略图仍走点击查看）。
                            SelectionContainer {
                                Text(
                                    text = message.content,
                                    style = if (compact) {
                                        MaterialTheme.typography.bodySmall
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HanakoAvatar(size = if (compact) 20.dp else 28.dp)
            Text(
                text = "Hanako",
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier.widthIn(max = if (compact) 240.dp else 320.dp),
            shape = RoundedCornerShape(
                topStart = 6.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // 长按正文即可进入文字选择并复制任意片段（操作栏里的复制按钮仍然保留，用于整段复制）。
                SelectionContainer {
                    when {
                        message.content.isBlank() && message.status == ChatMessageStatus.Streaming -> Text(
                            text = "正在输入…",
                            style = if (compact) {
                                MaterialTheme.typography.bodySmall
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        message.content.isBlank() -> Text(
                            text = "（无内容）",
                            style = if (compact) {
                                MaterialTheme.typography.bodySmall
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> MarkdownLatexText(
                            content = message.content,
                            style = if (compact) {
                                MaterialTheme.typography.bodySmall
                            } else {
                                MaterialTheme.typography.bodyMedium
                            }
                        )
                    }
                }
            }
        }

        if (showQuickActions && message.status != ChatMessageStatus.Streaming) {
            Row(
                modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "重新生成",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = {
                        if (isReading) onStopSpeak() else onSpeak(message.content)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isReading) {
                        ReadingSpeakerIcon(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "语音播放",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        if (message.status == ChatMessageStatus.Error) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "响应未完整结束",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry) {
                    Text("重试", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * 聊天图片全屏查看层（聊天页与迷你窗共用同一实现）。
 *
 * 迷你窗宿主是 overlay Service，无 Activity token，因此不能用 Dialog，
 * 由宿主把本层叠加在自己根布局的最上层。交互：双指缩放 / 单指平移 / 轻触关闭。
 *
 * @param imagePath 待查看图片的本地文件路径；为 null 时不渲染任何内容（即关闭态）。
 */
@Composable
internal fun ChatImageViewer(imagePath: String?, onDismiss: () -> Unit) {
    val path = imagePath ?: return
    var scale by remember(path) { mutableStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            // 缩放/平移先接事件，静止轻触再交给下方的点击检测（避免互相吞掉事件）。
            .pointerInput(path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(path) {
                detectTapGestures(onTap = { onDismiss() })
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = "查看已发送图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
        Text(
            text = "轻触关闭 · 双指缩放",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

/**
 * 朗读中的声波动画图标：三条竖线按相位依次「从小到大」循环伸缩，
 * 提示当前消息正在朗读（与右上角自动朗读开关共享同一朗读状态）。
 */
@Composable
internal fun ReadingSpeakerIcon(modifier: Modifier = Modifier, tint: Color) {
    val transition = rememberInfiniteTransition(label = "readingSpeaker")
    val bars = listOf(0, 1, 2).map { index ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500, delayMillis = index * 120),
                repeatMode = RepeatMode.Reverse
            ),
            label = "readingBar$index"
        )
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        bars.forEach { bar ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(with(LocalDensity.current) { (18 * bar.value).dp })
                    .background(tint, CircleShape)
            )
        }
    }
}


