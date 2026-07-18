package com.example.relay.ui.rescue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun RescueFlow(
    state: RescueUiState,
    callbacks: RescueCallbacks,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (state.screen) {
        RescueScreen.HOME -> RescueHomeScreen(callbacks, onExit, modifier)
        RescueScreen.REQUEST_FORM -> RescueRequestFormScreen(state, callbacks, modifier)
        RescueScreen.BROADCASTING -> RescueBroadcastingScreen(state.broadcast, callbacks, modifier)
        RescueScreen.COURIER_INVENTORY -> CourierInventoryScreen(state.courierItems, state.courierAutomation, callbacks, modifier)
        RescueScreen.SAFETY_PRIVACY -> SafetyPrivacyScreen(callbacks, modifier)
    }
}

@Composable
private fun RescueHomeScreen(callbacks: RescueCallbacks, onExit: () -> Unit, modifier: Modifier = Modifier) {
    RescuePage(title = "救助・情報リレー", modifier = modifier, showBack = true, onBack = onExit) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            Text("できることを選んでください", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("通信できない時も、近くのRelay端末が情報を運びます。")
            LargeActionButton("助けを求める") { callbacks.onNavigate(RescueScreen.REQUEST_FORM) }
            LargeActionButton("運んでいる情報を見る") { callbacks.onNavigate(RescueScreen.COURIER_INVENTORY) }
            OutlinedButton(onClick = { callbacks.onNavigate(RescueScreen.SAFETY_PRIVACY) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(16.dp)) {
                Text("安全とプライバシー")
            }
        }
    }
}

@Composable
internal fun LargeActionButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RescuePage(title: String, modifier: Modifier = Modifier, showBack: Boolean = true, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            if (showBack) OutlinedButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp).heightIn(min = 56.dp)) { Text("戻る") }
        })
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content(Modifier.fillMaxSize()) } }
}

@Composable
internal fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
