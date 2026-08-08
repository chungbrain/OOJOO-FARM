package com.oojoo.farm.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Prefs
import org.json.JSONArray
import android.net.Uri
import android.widget.VideoView
import java.io.File

data class GalleryItem(val path: String, val slaveName: String, val createdAt: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(nav: NavController) {
    val ctx = LocalContext.current
    val items = remember {
        val arr = JSONArray(Prefs.galleryItems(ctx))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GalleryItem(o.optString("path"), o.optString("slaveName"), o.optString("createdAt"))
        }.reversed() // 최신순
    }
    var selectedPath by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("📷 사진첩", color = Color.White, fontWeight = FontWeight.Black) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p)) {
            // 선택된 영상 재생 영역
            selectedPath?.let { path ->
                Card(Modifier.fillMaxWidth().height(240.dp).padding(20.dp).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            VideoView(context).apply {
                                setVideoURI(Uri.fromFile(File(path)))
                                setOnPreparedListener { it.isLooping = true; start() }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton(text = "목록으로", onClick = { selectedPath = null }, modifier = Modifier.weight(1f))
                }
            }

            if (items.isEmpty() && selectedPath == null) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📹", fontSize = 56.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("저장된 영상이 없어요!", color = OojooTheme.Muted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Farmer 카메라로 촬영하면 여기에 저장됩니다.", color = OojooTheme.Muted2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else if (selectedPath == null) {
                Text("🎬 저장된 영상 (${items.size}개)", Modifier.padding(20.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = OojooTheme.Ink)
                LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items) { item ->
                        Card(
                            Modifier.fillMaxWidth()
                                .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
                                .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape)
                                .clip(OojooTheme.CardShape)
                                .clickable { selectedPath = item.path },
                            shape = OojooTheme.CardShape,
                            colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(52.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(2.dp, OojooTheme.Ink, RoundedCornerShape(14.dp))
                                        .background(OojooTheme.GreenBg),
                                    contentAlignment = Alignment.Center
                                ) { Text("🎬", fontSize = 26.sp) }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.slaveName, fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 15.sp)
                                    Text(item.createdAt, color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("📹 3초 영상", color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("▶", fontSize = 24.sp, color = OojooTheme.Green)
                            }
                        }
                    }
                }
            }
        }
    }
}
