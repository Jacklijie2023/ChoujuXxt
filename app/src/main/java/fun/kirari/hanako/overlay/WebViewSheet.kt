package `fun`.kirari.hanako.overlay

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebViewSheet(
    initialUrl: String,
    onClose: () -> Unit,
    onSearch: (String) -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var urlInput by remember { mutableStateOf(initialUrl) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    val bookmarks = remember { mutableStateListOf<BookmarkItem>() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部工具栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮
                IconButton(
                    onClick = { webViewInstance?.goBack() },
                    enabled = canGoBack
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                // 前进按钮
                IconButton(
                    onClick = { webViewInstance?.goForward() },
                    enabled = canGoForward
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
                }
                // URL 输入框
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("输入网址或搜索") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = { onSearch(urlInput) }
                    )
                )
                // 刷新按钮
                IconButton(onClick = { webViewInstance?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
                // 书签按钮
                IconButton(onClick = { showBookmarks = !showBookmarks }) {
                    Icon(Icons.Default.Bookmark, contentDescription = "书签")
                }
                // 关闭按钮
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            // 书签菜单
            DropdownMenu(
                expanded = showBookmarks,
                onDismissRequest = { showBookmarks = false }
            ) {
                if (bookmarks.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("暂无书签") },
                        onClick = { showBookmarks = false }
                    )
                } else {
                    bookmarks.forEach { bookmark ->
                        DropdownMenuItem(
                            text = { Text(bookmark.title) },
                            onClick = {
                                currentUrl = bookmark.url
                                showBookmarks = false
                            }
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("+ 添加当前页面到书签") },
                    onClick = {
                        if (currentUrl.isNotBlank()) {
                            bookmarks.add(BookmarkItem(pageTitle.ifBlank { currentUrl }, currentUrl))
                        }
                        showBookmarks = false
                    }
                )
            }

            // 页面标题
            if (pageTitle.isNotBlank()) {
                Text(
                    text = pageTitle,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    maxLines = 1
                )
            }

            // WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != null) {
                                    currentUrl = url
                                    urlInput = url
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                pageTitle = title ?: ""
                            }
                        }
                        loadUrl(initialUrl)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    if (currentUrl != webView.url) {
                        webView.loadUrl(currentUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private data class BookmarkItem(
    val title: String,
    val url: String
)
