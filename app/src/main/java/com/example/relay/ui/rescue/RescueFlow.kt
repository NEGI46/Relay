package com.example.relay.ui.rescue

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency
import java.text.DateFormat
import java.util.Date

@Composable
fun RescueFlow(
    state: RescueUiState,
    callbacks: RescueCallbacks,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (state.screen) {
        RescueScreen.HOME -> RescueHomeScreen(state, callbacks, onExit, modifier)
        RescueScreen.REQUEST_FORM -> RescueRequestFormScreen(state, callbacks, modifier)
        RescueScreen.BROADCASTING -> RescueBroadcastingScreen(state, callbacks, modifier)
        RescueScreen.COURIER_INVENTORY -> CourierInventoryScreen(state.courierItems, state.courierAutomation, callbacks, modifier)
        RescueScreen.SAFETY_PRIVACY -> SafetyPrivacyScreen(callbacks, modifier)
    }
}

@Composable
private fun RescueHomeScreen(
    state: RescueUiState,
    callbacks: RescueCallbacks,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RescuePage(title = "Relay 救助", modifier = modifier, showBack = true, onBack = onExit) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            Text("今すぐ助けが必要ですか", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("SOSは2秒長押しで、人数不明・命の危険としてGPS位置をすぐ送ります。")
            SosHoldButton(enabled = !state.isRequestSubmitting, onSos = callbacks::onSendSos)
            state.formMessage?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp)) } }
            LargeActionButton("状況を入力して救助を依頼") { callbacks.onNavigate(RescueScreen.REQUEST_FORM) }

            state.ownRequest?.let { request ->
                OwnRequestCard(request, callbacks)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("通信は自動です", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            state.broadcast.transferCount > 0 -> "中継済み。避難所への到達を確認中です。"
                            state.broadcast.isActive -> "周囲のRelay端末を探しています。"
                            else -> "送信可能。依頼後はアプリを閉じても中継します。"
                        },
                    )
                    Text("端末・避難所・再送を選ぶ操作はありません。")
                }
            }
            OutlinedButton(
                onClick = { callbacks.onNavigate(RescueScreen.SAFETY_PRIVACY) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("安全とプライバシー") }
        }
    }
}

@Composable
private fun SosHoldButton(enabled: Boolean, onSos: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var sentForCurrentHold by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) 2_000 else 100),
        finishedListener = { value ->
            if (enabled && holding && value >= 1f && !sentForCurrentHold) {
                sentForCurrentHold = true
                onSos()
            }
        },
        label = "SOS hold progress",
    )
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(shape)
                .background(if (enabled) Color(0xFFB42318) else Color(0xFF8C8C8C))
                .semantics {
                    role = Role.Button
                    onLongClick("2秒長押ししてSOSを送信") {
                        if (enabled) onSos()
                        enabled
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        sentForCurrentHold = false
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", color = Color.White, style = MaterialTheme.typography.displaySmall)
                Text(if (holding) "そのまま長押し" else "2秒長押し", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OwnRequestCard(request: OwnRescueRequestUiState, callbacks: RescueCallbacks) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (request.isCancelled) "自分の救助依頼（取消送信中）" else "自分の救助依頼", style = MaterialTheme.typography.titleLarge)
            Text(request.submissionStatus.statusLabel(), style = MaterialTheme.typography.titleMedium)
            Text("依頼 → 自動中継 → 避難所受信 → 対応中 → 完了")
            MiniLocationMap(request.urgency == RescueUrgency.IMMEDIATE)
            Text("GPS: %.5f, %.5f".format(request.latitude, request.longitude))
            Text("位置更新: ${formatRescueTime(request.locationCapturedAtEpochMillis)}${request.accuracyMeters?.let { "（精度 約${it.toInt()}m）" }.orEmpty()}")
            if (!request.isCancelled) {
                Button(onClick = callbacks::onPrepareUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("状況・人数を更新")
                }
                OutlinedButton(onClick = callbacks::onCancelRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("救助依頼を取り消す")
                }
            }
        }
    }
}

@Composable
private fun MiniLocationMap(isImmediate: Boolean) {
    val marker = if (isImmediate) Color(0xFFB42318) else MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        repeat(5) { index ->
            val x = size.width * index / 4f
            val y = size.height * index / 4f
            drawLine(grid, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height))
            drawLine(grid, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y))
        }
        drawCircle(marker, radius = 12.dp.toPx(), center = center)
        drawCircle(Color.White, radius = 4.dp.toPx(), center = center)
    }
}

@Composable
internal fun LargeActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RescuePage(
    title: String,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBack) OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp).heightIn(min = 56.dp),
                    ) { Text("戻る") }
                },
            )
        },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content(Modifier.fillMaxSize()) } }
}

@Composable
internal fun SectionTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleLarge,
    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
)

private fun RescueSubmissionStatus.statusLabel(): String = when (this) {
    RescueSubmissionStatus.PENDING -> "周囲の端末を探索中"
    RescueSubmissionStatus.IN_TRANSIT -> "避難所へ自動中継中"
    RescueSubmissionStatus.SHELTER_STORED -> "避難所PCが受信済み"
    RescueSubmissionStatus.SHELTER_ACCEPTED -> "避難所が受領済み"
    RescueSubmissionStatus.SHELTER_RESPONDING -> "避難所が対応中"
    RescueSubmissionStatus.SHELTER_COMPLETED -> "対応完了"
    RescueSubmissionStatus.CANCELLED -> "取消確認済み"
    RescueSubmissionStatus.SHELTER_REJECTED -> "避難所で確認が必要"
}

private fun formatRescueTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
