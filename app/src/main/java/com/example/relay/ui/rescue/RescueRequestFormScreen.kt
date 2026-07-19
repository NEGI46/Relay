package com.example.relay.ui.rescue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueSupportNeed

@Composable
internal fun RescueRequestFormScreen(
    state: RescueUiState,
    callbacks: RescueCallbacks,
    modifier: Modifier = Modifier,
) {
    RescuePage(
        title = if ((state.draft?.requestVersion ?: 1) > 1) "救助要請を更新" else "助けを求める",
        modifier = modifier,
        onBack = { callbacks.onNavigate(RescueScreen.HOME) },
    ) { contentModifier ->
        val draft = state.draft
        if (draft == null) {
            Column(contentModifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("入力の準備ができませんでした", style = MaterialTheme.typography.titleLarge)
                LargeActionButton("ホームへ戻る") { callbacks.onNavigate(RescueScreen.HOME) }
            }
            return@RescuePage
        }

        RescueScrollableColumn(contentModifier) {
            Text("人数と現在の状態だけで送れます。GPS位置は送信時に自動取得します。")
            PersonCountChooser(draft.personCount.coerceAtLeast(1)) {
                callbacks.onDraftChange(draft.copy(personCount = it))
            }

            SectionTitle("現在の状態（1つ以上）")
            RescueCondition.entries.forEach { condition ->
                DraftOption(condition.label(), condition in draft.conditions) { checked ->
                    val updated = if (checked) draft.conditions + condition else draft.conditions - condition
                    callbacks.onDraftChange(draft.copy(conditions = updated))
                }
            }

            SectionTitle("補足タグ（任意）")
            RescueSupportNeed.entries.forEach { need ->
                DraftOption(need.label(), need in draft.supportNeeds) { checked ->
                    val updated = if (checked) draft.supportNeeds + need else draft.supportNeeds - need
                    callbacks.onDraftChange(draft.copy(supportNeeds = updated))
                }
            }
            DraftOption("高齢者がいる", draft.elderlyPresent) {
                callbacks.onDraftChange(draft.copy(elderlyPresent = it))
            }
            DraftOption("子どもがいる", draft.childrenPresent) {
                callbacks.onDraftChange(draft.copy(childrenPresent = it))
            }
            DraftOption("妊娠中の人がいる", draft.pregnantPresent) {
                callbacks.onDraftChange(draft.copy(pregnantPresent = it))
            }
            DraftOption("閉じ込め・倒壊・火災の危険", draft.trapped || draft.fireOrCollapseRisk) {
                callbacks.onDraftChange(draft.copy(trapped = it, fireOrCollapseRisk = it))
            }

            SectionTitle("場所の補足（任意）")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("GPS位置を自動添付", style = MaterialTheme.typography.titleMedium)
                    Text("位置が古い場合は取得時刻も一緒に避難所へ伝わります。")
                }
            }
            OutlinedTextField(
                value = draft.location?.description.orEmpty(),
                onValueChange = { description ->
                    callbacks.onDraftChange(
                        draft.copy(
                            location = (draft.location ?: com.example.relay.rescue.RescueLocation())
                                .copy(description = description.take(256)),
                        ),
                    )
                },
                label = { Text("建物名・階・目印") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = draft.freeText,
                onValueChange = { callbacks.onDraftChange(draft.copy(freeText = it.take(2_000))) },
                label = { Text("ほかに伝えたいこと") },
                supportingText = { Text("任意・2000文字まで") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            state.formMessage?.let { message ->
                Card(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(16.dp)) }
            }
            Button(
                onClick = callbacks::onSubmitRequest,
                enabled = !state.isRequestSubmitting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Text(if (state.isRequestSubmitting) "GPSを確認して保存中…" else "この内容で救助を依頼")
            }
            Text("内容と正確な位置は暗号化され、中継する人には見えません。")
        }
    }
}

@Composable
internal fun RescueScrollableColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PersonCountChooser(value: Int, onChange: (Int) -> Unit) {
    SectionTitle("助けが必要な人数")
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("−")
            }
            Text("${value}人", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { onChange((value + 1).coerceAtMost(1_000)) }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("＋")
            }
        }
    }
}

@Composable
private fun DraftOption(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun RescueCondition.label(): String = when (this) {
    RescueCondition.LIFE_THREATENING -> "命の危険がある"
    RescueCondition.INJURED_OR_UNWELL -> "けが・体調不良"
    RescueCondition.MOBILITY_IMPAIRED -> "自力で移動できない"
    RescueCondition.SUPPORT_NEEDED -> "生活・医療の支援が必要"
}

private fun RescueSupportNeed.label(): String = when (this) {
    RescueSupportNeed.WATER -> "水"
    RescueSupportNeed.FOOD -> "食料"
    RescueSupportNeed.MEDICINE -> "薬・医療"
    RescueSupportNeed.RESCUE_TEAM -> "救助隊"
    RescueSupportNeed.TRANSPORT -> "移動支援"
}
