package com.example.relay.ui.rescue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.relay.rescue.CourierRescueItem
import com.example.relay.rescue.RescueSubmissionStatus
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RescueBroadcastingScreen(state: RescueUiState, callbacks: RescueCallbacks, modifier: Modifier = Modifier) {
    val broadcast = state.broadcast
    RescuePage(title = if (broadcast.isActive) "救助要請を自動送信中" else "救助要請を保存しました", modifier = modifier, onBack = { callbacks.onNavigate(RescueScreen.HOME) }) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            Text(if (broadcast.isActive) "自動送信中" else "取消情報を送信中", style = MaterialTheme.typography.headlineSmall)
            Text(broadcast.statusMessage, style = MaterialTheme.typography.bodyLarge)
            StatusCard("近くのRelay端末", "${broadcast.nearbyDeviceCount}台")
            StatusCard("中継した回数", "${broadcast.transferCount}回")
            state.ownRequest?.let { own ->
                StatusCard("GPS位置", "%.5f, %.5f".format(own.latitude, own.longitude))
            }
            Text("アプリを閉じても自動で中継します。止める場合はホームの「救助依頼を取り消す」を使ってください。")
            LargeActionButton("ホームへ戻る") { callbacks.onNavigate(RescueScreen.HOME) }
        }
    }
}

/** Shows courier metadata only. No PC search, submit, retry, or key entry controls exist here. */
@Composable
internal fun CourierInventoryScreen(items: List<CourierRescueItem>, automation: CourierAutomationUiState, callbacks: RescueCallbacks, modifier: Modifier = Modifier) {
    RescuePage(title = "運んでいる情報", modifier = modifier, onBack = { callbacks.onNavigate(RescueScreen.HOME) }) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            Text("内容は表示されません", style = MaterialTheme.typography.titleMedium)
            Text("受信した救助要請は、府中町の避難所PCを見つけると自動で安全に提出されます。あなたの操作は不要です。")
            StatusCard("自動提出", if (automation.isEnabled) automation.statusMessage else "自動提出の準備中です")
            automation.lastDeliveredAtEpochMillis?.let { StatusCard("最後に提出した時刻", formatTime(it)) }
            if (items.isEmpty()) {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("運んでいる要請はありません", style = MaterialTheme.typography.titleLarge)
                    Text("近くの端末から受け取ると、自動で保管して避難所へ運びます。")
                } }
            } else {
                Text("保管中: ${items.size}件", style = MaterialTheme.typography.titleLarge)
                items.forEach { item -> CourierItemCard(item) }
            }
        }
    }
}

@Composable
private fun CourierItemCard(item: CourierRescueItem) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "保管中の救助要請。${item.submissionStatus.label()}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("受信した救助要請", style = MaterialTheme.typography.titleMedium)
            Text("状態: ${item.submissionStatus.label()}")
            Text("送信先: 府中町の救助拠点")
            Text("受信: ${formatTime(item.receivedAtEpochMillis)}")
            Text("期限: ${formatTime(item.expiresAtEpochMillis)}")
        }
    }
}

@Composable
internal fun SafetyPrivacyScreen(callbacks: RescueCallbacks, modifier: Modifier = Modifier) {
    RescuePage(title = "安全とプライバシー", modifier = modifier, onBack = { callbacks.onNavigate(RescueScreen.HOME) }) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            SectionTitle("救助要請を作る方へ")
            Text("位置や健康情報を含む内容は、避難所だけが読めるように暗号化します。")
            SectionTitle("情報を運ぶ方へ")
            Text("受け取った情報の内容、正確な位置、人数は見えません。避難所への提出も自動です。")
            SectionTitle("通信できないとき")
            Text("エラーではありません。近くの中継端末や避難所PCを見つけるまで、情報を安全に保管します。")
            LargeActionButton("ホームへ戻る") { callbacks.onNavigate(RescueScreen.HOME) }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    } }
}

private fun RescueSubmissionStatus.label(): String = when (this) {
    RescueSubmissionStatus.PENDING -> "避難所へ自動提出を待っています"
    RescueSubmissionStatus.IN_TRANSIT -> "避難所へ自動提出中です"
    RescueSubmissionStatus.SHELTER_STORED -> "避難所PCへ提出済みです"
    RescueSubmissionStatus.SHELTER_ACCEPTED -> "避難所で受領されました"
    RescueSubmissionStatus.SHELTER_REJECTED -> "避難所で確認が必要です"
}

private fun formatTime(epochMillis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
