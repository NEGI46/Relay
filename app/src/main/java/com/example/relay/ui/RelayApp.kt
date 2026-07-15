package com.example.relay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.relay.domain.DeliveryPresentation
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.SafetyPayload
import com.example.relay.domain.SafetyState
import com.example.relay.domain.StatusChangePayload
import com.example.relay.domain.SupplyKind
import com.example.relay.domain.SupplyPayload
import com.example.relay.permissions.AndroidNearbyPermissionGate
import com.example.relay.service.RelayCommunicationService

@Composable
fun RelayApp(viewModel: RelayViewModel, deviceId: String) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val deliveries by viewModel.deliveryStates.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionGate = remember { AndroidNearbyPermissionGate(context) }
    val activity = remember(context) { context.findActivity() }
    var explainPermissions by rememberSaveable { mutableStateOf(false) }

    fun startCommunication() {
        RelayCommunicationService.start(context, com.example.relay.domain.OperatingMode.RELAY, state.role)
            ?.let(viewModel::reportError)
    }

    LaunchedEffect(permissionGate.canUseNearby(), state.transportRunning) {
        if (permissionGate.canUseNearby() && !state.transportRunning) startCommunication()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (permissionGate.canUseNearby()) {
            startCommunication()
        } else {
            val permanentlyDenied = result.filterValues { !it }.keys.any { permission ->
                activity?.shouldShowRequestPermissionRationale(permission) == false
            }
            viewModel.reportError(if (permanentlyDenied) "権限が無効です。設定からRelayの権限を許可してください。" else "通信に必要な権限が許可されませんでした。")
        }
    }

    MaterialTheme {
        if (explainPermissions) {
            AlertDialog(
                onDismissRequest = { explainPermissions = false },
                title = { Text("災害通信を開始") },
                text = { Text("近くのRelay端末を自動で探し、情報を保存・中継します。正確な位置情報は取得しません。") },
                confirmButton = {
                    Button(onClick = {
                        explainPermissions = false
                        val missing = permissionGate.missingPermissions()
                        if (missing.isEmpty()) startCommunication() else permissionLauncher.launch(missing.toTypedArray())
                    }) { Text("許可して開始") }
                },
                dismissButton = { OutlinedButton(onClick = { explainPermissions = false }) { Text("あとで") } },
            )
        }

        when (state.screen) {
            RelayScreen.HOME -> HomeScreen(
                state = state,
                count = messages.size,
                start = { if (permissionGate.canUseNearby()) startCommunication() else explainPermissions = true },
                stop = { RelayCommunicationService.stop(context) },
                safety = { viewModel.navigate(RelayScreen.SAFETY_FORM) },
                supply = { viewModel.navigate(RelayScreen.SUPPLY_FORM) },
                navigate = viewModel::navigate,
            )
            RelayScreen.SAFETY_FORM -> SafetyForm(viewModel)
            RelayScreen.SUPPLY_FORM -> SupplyForm(viewModel)
            RelayScreen.REGIONAL -> RegionalScreen(messages, deviceId, deliveries, viewModel::navigate)
            else -> SettingsScreen(
                state = state,
                openAppSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                openBluetoothSettings = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                navigate = viewModel::navigate,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun HomeScreen(
    state: RelayUiState,
    count: Int,
    start: () -> Unit,
    stop: () -> Unit,
    safety: () -> Unit,
    supply: () -> Unit,
    navigate: (RelayScreen) -> Unit,
) {
    MainScaffold(RelayScreen.HOME, navigate) { modifier ->
        LazyColumn(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Relay", style = MaterialTheme.typography.headlineLarge) }
            item {
                Card(Modifier.fillMaxWidth().semantics { contentDescription = "災害通信の状態" }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when {
                                state.transportRunning && state.connectedPeers > 0 -> "近くの端末と情報を交換中"
                                state.transportRunning -> "周囲の端末を探しています"
                                else -> "災害通信は停止中です"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(if (state.transportRunning) "自動で接続・中継しています" else "開始すると自動で通信します")
                        Text("保存中の情報: ${count}件")
                        Text("接続中の端末: ${state.connectedPeers}台")
                        Button(
                            onClick = if (state.transportRunning) stop else start,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(if (state.transportRunning) "通信を停止" else "災害通信を開始") }
                    }
                }
            }
            item { Button(onClick = safety, modifier = Modifier.fillMaxWidth().height(68.dp)) { Text("無事・避難状況を登録") } }
            item { Button(onClick = supply, modifier = Modifier.fillMaxWidth().height(68.dp)) { Text("不足している物資を登録") } }
            item { OutlinedButton(onClick = { navigate(RelayScreen.REGIONAL) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("地域情報を見る（${count}件）") } }
            state.lastError?.let { error -> item { Card(Modifier.fillMaxWidth()) { Text("通信状態: $error", Modifier.padding(14.dp)) } } }
        }
    }
}

@Composable
private fun SafetyForm(viewModel: RelayViewModel) {
    var selected by rememberSaveable { mutableStateOf(SafetyState.SAFE) }
    var companions by rememberSaveable { mutableIntStateOf(0) }
    var location by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    FormScaffold("安否・避難状況", viewModel) {
        Text("現在の状態", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SafetyState.entries) { option ->
            if (selected == option) Button(onClick = { selected = option }) { Text(option.label()) }
            else OutlinedButton(onClick = { selected = option }) { Text(option.label()) }
        } }
        NumberChooser("同行者数", companions, 0, 99) { companions = it }
        OutlinedTextField(location, { location = it.take(100) }, label = { Text("おおまかな場所（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.take(280) }, label = { Text("短いメモ（任意）") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.createSafety(selected, companions, location, note) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("保存する") }
    }
}

@Composable
private fun SupplyForm(viewModel: RelayViewModel) {
    var selected by rememberSaveable { mutableStateOf(SupplyKind.WATER) }
    var count by rememberSaveable { mutableIntStateOf(1) }
    var location by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var other by rememberSaveable { mutableStateOf("") }
    FormScaffold("不足物資", viewModel) {
        Text("必要な物資", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SupplyKind.entries) { option ->
            if (selected == option) Button(onClick = { selected = option }) { Text(option.label()) }
            else OutlinedButton(onClick = { selected = option }) { Text(option.label()) }
        } }
        if (selected == SupplyKind.OTHER) OutlinedTextField(other, { other = it.take(50) }, label = { Text("物資名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        NumberChooser("必要数", count, 1, 9999) { count = it }
        OutlinedTextField(location, { location = it.take(100) }, label = { Text("おおまかな場所（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.take(280) }, label = { Text("短いメモ（任意）") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.createSupply(selected, count, location, note, other.takeIf { selected == SupplyKind.OTHER }) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("保存する") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormScaffold(title: String, viewModel: RelayViewModel, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { OutlinedButton(onClick = { viewModel.navigate(RelayScreen.HOME) }) { Text("戻る") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { content() } }
    }
}

@Composable
private fun NumberChooser(label: String, value: Int, min: Int, max: Int, update: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = { update((value - 1).coerceAtLeast(min)) }) { Text("−") }
        Text("$label\n$value", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { update((value + 1).coerceAtMost(max)) }) { Text("＋") }
    } }
}

@Composable
private fun RegionalScreen(messages: List<RelayMessage>, deviceId: String, deliveries: Map<String, DeliveryPresentation>, navigate: (RelayScreen) -> Unit) {
    MainScaffold(RelayScreen.REGIONAL, navigate) { modifier ->
        LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("地域情報", style = MaterialTheme.typography.headlineMedium) }
            if (messages.isEmpty()) item { Text("保存されている情報はありません") }
            items(messages, key = { it.messageId }) { MessageCard(it, deviceId, deliveries[it.messageId]) }
        }
    }
}

@Composable
private fun MessageCard(message: RelayMessage, deviceId: String, delivery: DeliveryPresentation?) {
    val location = when (val payload = message.payload) {
        is SafetyPayload -> payload.approximateLocation.ifBlank { "場所未入力" }
        is SupplyPayload -> payload.approximateLocation.ifBlank { "場所未入力" }
        is StatusChangePayload -> "対象: ${payload.targetMessageId.take(12)}"
    }
    val deliveryLabel = when (delivery) {
        DeliveryPresentation.GATEWAY_RECEIVED -> "Gatewayへ保存済み"
        DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED -> "中継拠点へ保存済み（未認証）"
        DeliveryPresentation.PEER_RECEIVED -> "近くの端末へ保存済み（最終配信ではありません）"
        DeliveryPresentation.NEARBY_PAYLOAD_COMPLETE -> "転送完了（保存確認前）"
        else -> "転送待ち"
    }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (message.messageType.name == "SAFETY") "安否情報" else "物資不足情報", style = MaterialTheme.typography.titleMedium)
        Text("状態: ${message.status.name} / 中継: ${message.hopCount}/${message.maxHopCount}")
        Text("場所: $location")
        Text(if (message.originDeviceId == deviceId) "自分が登録" else "他の端末から受信")
        Text(deliveryLabel)
    } }
}

@Composable
private fun SettingsScreen(state: RelayUiState, openAppSettings: () -> Unit, openBluetoothSettings: () -> Unit, navigate: (RelayScreen) -> Unit) {
    MainScaffold(RelayScreen.SETTINGS, navigate) { modifier ->
        LazyColumn(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("設定", style = MaterialTheme.typography.headlineMedium) }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Relayは近くの端末を自動で探して情報を中継します。")
                Text("接続先の登録や確認コードの入力は必要ありません。")
                Text(if (state.transportRunning) "災害通信: 動作中" else "災害通信: 停止中")
            } } }
            item { OutlinedButton(onClick = openBluetoothSettings, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth設定を開く") } }
            item { OutlinedButton(onClick = openAppSettings, modifier = Modifier.fillMaxWidth()) { Text("Relayの権限設定を開く") } }
        }
    }
}

@Composable
private fun MainScaffold(selected: RelayScreen, navigate: (RelayScreen) -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(bottomBar = { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(RelayScreen.HOME to "ホーム", RelayScreen.REGIONAL to "地域情報", RelayScreen.SETTINGS to "設定").forEach { (screen, label) ->
            if (selected == screen) Button(onClick = { navigate(screen) }, modifier = Modifier.weight(1f)) { Text(label) }
            else OutlinedButton(onClick = { navigate(screen) }, modifier = Modifier.weight(1f)) { Text(label) }
        }
    } }) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

private fun SafetyState.label() = when (this) {
    SafetyState.SAFE -> "無事"
    SafetyState.INJURED -> "けがあり"
    SafetyState.EVACUATING -> "避難中"
    SafetyState.AT_SHELTER -> "避難所に到着"
}

private fun SupplyKind.label() = when (this) {
    SupplyKind.WATER -> "水"
    SupplyKind.FOOD -> "食料"
    SupplyKind.MEDICINE -> "薬"
    SupplyKind.BLANKET -> "毛布"
    SupplyKind.POWER -> "電源"
    SupplyKind.HYGIENE -> "衛生用品"
    SupplyKind.OTHER -> "その他"
}
