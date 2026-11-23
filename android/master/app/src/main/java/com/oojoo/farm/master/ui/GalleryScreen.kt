package com.oojoo.farm.master.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import org.json.JSONArray
import android.net.Uri
import android.widget.VideoView
import java.io.File

data class GalleryItem(val path: String, val slaveName: String, val createdAt: String)

/**
 * 사진첩 상세 다이얼로그용 선택 상태.
 */
private data class PhotoPick(
    val photo: PlantPhoto,
    val plantId: String?,
    val title: String
)

/**
 * 사진첩 — Farmer가 마스터에 보낸 사진의 저장소.
 * farmer별(색상 점+온라인) → 식물별 → 2열 썸네일 그리드(480px).
 * 하단에 로컬 저장 3초 영상 목록(기존 기능) 유지.
 */
private sealed interface AlbumRow {
    val key: String
    data class FarmerHeader(override val key: String, val slaveId: String, val name: String, val online: Boolean, val total: Int) : AlbumRow
    data class PlantHeader(override val key: String, val name: String, val count: Int) : AlbumRow
    data class PhotoGrid(override val key: String, val photos: List<PlantPhoto>) : AlbumRow
    data class VideosHeader(override val key: String, val count: Int) : AlbumRow
    data class VideoItem(override val key: String, val item: GalleryItem) : AlbumRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(nav: NavController) {
    val ctx = LocalContext.current
    val S = LocalAppStrings.current
    val api = ApiClient.api

    val videoItems = remember {
        val arr = JSONArray(Prefs.galleryItems(ctx))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GalleryItem(o.optString("path"), o.optString("slaveName"), o.optString("createdAt"))
        }.reversed() // 최신순
    }

    // null = 아직 로딩 중
    var photos by remember { mutableStateOf<List<PlantPhoto>?>(null) }
    var slaves by remember { mutableStateOf<List<Slave>>(emptyList()) }
    LaunchedEffect(Unit) {
        try { photos = api.userPhotos(Session.userId).photos } catch (_: Exception) { photos = emptyList() }
        try { slaves = api.slaves(Session.userId).slaves } catch (_: Exception) {}
    }

    var selectedPath by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<PhotoPick?>(null) }

    val rows = remember(photos, slaves, videoItems) {
        buildAlbumRows(photos ?: emptyList(), slaves, videoItems, S.albumPlantFallback)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(S.gallery, color = Color.White, fontWeight = FontWeight.Black) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p)) {
            // 선택된 영상 재생 영역 (기존 동작 유지)
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
                    GradientButton(text = S.backToList, onClick = { selectedPath = null }, modifier = Modifier.weight(1f))
                }
            }

            if (selectedPath == null) {
                when {
                    photos == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    rows.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📷", fontSize = 56.sp)
                        Spacer(Modifier.height(14.dp))
                        Text(S.albumEmpty, color = OojooTheme.Muted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(S.galleryTip, color = OojooTheme.Muted2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is AlbumRow.FarmerHeader -> {
                                    val slave = slaves.firstOrNull { it.id == row.slaveId }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                        Box(
                                            Modifier.size(14.dp).clip(RoundedCornerShape(50)).border(1.5.dp, OojooTheme.Ink, RoundedCornerShape(50)).background(farmerColorFor(row.slaveId))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(row.name, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = OojooTheme.Ink)
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = OojooTheme.PillShape,
                                            color = if (row.online) OojooTheme.GreenBg else OojooTheme.Line2,
                                            border = BorderStroke(1.dp, if (row.online) OojooTheme.GreenDark else OojooTheme.Muted2)
                                        ) {
                                            Text(
                                                if (row.online) S.albumFarmerOnline else S.albumFarmerOffline,
                                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                color = if (row.online) OojooTheme.GreenDark else OojooTheme.Muted,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text(S.albumPhotosCount.format(row.total), color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                is AlbumRow.PlantHeader -> Row(
                                    Modifier.padding(start = 22.dp, top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🌱", fontSize = 13.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(row.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = OojooTheme.Ink2)
                                    Spacer(Modifier.width(6.dp))
                                    Text(S.albumPhotosCount.format(row.count), color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                is AlbumRow.PhotoGrid -> {
                                    val cellPhotos = row.photos
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        cellPhotos.chunked(2).forEach { pair ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                pair.forEach { photo ->
                                                    val pickTitle = listOfNotNull(photo.plant_name, photo.slave_name).joinToString(" · ")
                                                    Column(Modifier.weight(1f)) {
                                                        AsyncImage(
                                                            model = ApiClient.baseUrl.trimEnd('/') + (photo.thumb_url ?: photo.url),
                                                            contentDescription = photo.taken_at,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .aspectRatio(1f)
                                                                .clip(RoundedCornerShape(12.dp))
                                                                .border(1.5.dp, OojooTheme.Ink, RoundedCornerShape(12.dp))
                                                                .background(OojooTheme.Line2)
                                                                .clickable {
                                                                    pick = PhotoPick(photo, photo.plant_id, pickTitle)
                                                                }
                                                        )
                                                        Text(
                                                            photo.taken_at ?: "",
                                                            color = OojooTheme.Muted,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                                repeat(2 - pair.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                    }
                                }
                                is AlbumRow.VideosHeader -> Row(
                                    Modifier.padding(top = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🎬", fontSize = 14.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${S.savedVideosPrefix}${row.count}${S.savedVideosSuffix}",
                                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink
                                    )
                                }
                                is AlbumRow.VideoItem -> {
                                    val item = row.item
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
                                                Text(S.video3sec, color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
        }
    }

    // 사진 상세 — 전체 크기 + 캡션 + (설정된 RoI 있으면) 오버레이
    pick?.let { sel ->
        var roi by remember { mutableStateOf<RoiBox?>(null) }
        LaunchedEffect(sel.plantId) {
            sel.plantId?.let { pid ->
                try { roi = api.plant(pid).roi } catch (_: Exception) {}
            }
        }
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(sel.title, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column {
                    Box(Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = ApiClient.baseUrl.trimEnd('/') + sel.photo.url,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        roi?.let { b ->
                            Canvas(Modifier.matchParentSize()) { drawRoiOverlay(b) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        listOfNotNull(sel.photo.taken_at, sel.photo.location).joinToString(" · "),
                        color = OojooTheme.Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pick = null }) { Text(S.close) }
            }
        )
    }
}

/**
 * farmer → 식물 → 사진 순으로 행 구성.
 * farmer: slave_id 기준 (이름은 사진의 slave_name, 온라인은 slaves 조회).
 * 식물: plant_id 기준. 사진은 최신순 유지 (백엔드가 DESC 반환).
 */
private fun buildAlbumRows(
    photos: List<PlantPhoto>,
    slaves: List<Slave>,
    videoItems: List<GalleryItem>,
    plantFallback: String
): List<AlbumRow> {
    val out = mutableListOf<AlbumRow>()
    val bySlave = photos.groupBy { it.slave_id ?: "unknown" }
    val slaveOrder = bySlave.keys.sortedBy { id ->
        (slaves.firstOrNull { it.id == id }?.name ?: bySlave.getValue(id).firstOrNull()?.slave_name ?: id)
    }
    for (id in slaveOrder) {
        val group = bySlave.getValue(id)
        val name = slaves.firstOrNull { it.id == id }?.name
            ?: group.firstOrNull()?.slave_name
            ?: id
        out += AlbumRow.FarmerHeader("f/$id", id, name, slaves.firstOrNull { it.id == id }?.online == 1, group.size)
        val byPlant = group.groupBy { it.plant_id ?: "unknown" }
        val plantOrder = byPlant.keys.sortedBy { pid ->
            byPlant.getValue(pid).firstOrNull()?.plant_name ?: plantFallback
        }
        for (pid in plantOrder) {
            val plantPhotos = byPlant.getValue(pid)
            val plantName = plantPhotos.firstOrNull()?.plant_name ?: plantFallback
            out += AlbumRow.PlantHeader("p/$id/$pid", plantName, plantPhotos.size)
            out += AlbumRow.PhotoGrid("g/$id/$pid", plantPhotos)
        }
    }
    if (videoItems.isNotEmpty()) {
        out += AlbumRow.VideosHeader("vh", videoItems.size)
        videoItems.forEach { out += AlbumRow.VideoItem("v/${it.path}", it) }
    }
    return out
}
