package `fun`.kirari.hanako.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.data.ChatMessage
import `fun`.kirari.hanako.data.ChatMessageStatus
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.overlay.MarkdownLatexText

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
 */
@Composable
internal fun ChatMessageItem(
    message: ChatMessage,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    compact: Boolean = false,
    showQuickActions: Boolean = true,
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

        if (showQuickActions && message.status != ChatMessageStatus.Streaming) {
            Row(
                modifier = Modifier.padding(start = 2.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                QuickActionPill("思考模式") {
                    Toast.makeText(context, "「思考模式」功能正在接入", Toast.LENGTH_SHORT).show()
                }
                QuickActionPill("联网搜索") {
                    Toast.makeText(context, "「联网搜索」功能正在接入", Toast.LENGTH_SHORT).show()
                }
            }
            Row(
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onRegenerate, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "重新生成",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = {
                        Toast.makeText(context, "语音播放功能正在接入", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "语音播放",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
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

/** AI 回复下方的品牌色文字快捷入口 pill。 */
@Composable
private fun QuickActionPill(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
