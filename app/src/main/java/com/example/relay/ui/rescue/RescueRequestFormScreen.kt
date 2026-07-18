package com.example.relay.ui.rescue

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency

@Composable
internal fun RescueRequestFormScreen(
    state: RescueUiState,
    callbacks: RescueCallbacks,
    modifier: Modifier = Modifier,
) {
    RescuePage(
        title = "救助要請を作る",
        modifier = modifier,
        onBack = { callbacks.onNavigate(RescueScreen.HOME) },
    ) { contentModifier ->
        val draft = state.draft
        if (draft == null) {
            Column(
                contentModifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("入力の準備ができませんでした", style = MaterialTheme.typography.titleLarge)
                Text("ホームに戻り、もう一度「助けを求める」を押してください。")
                LargeActionButton("←  ホームへ戻る") {
                    callbacks.onNavigate(RescueScreen.HOME)
                }
            }
            return@RescuePage
        }

        RescueScrollableColumn(contentModifier) {
            Text("分かるところだけで大丈夫です。", style = MaterialTheme.typography.bodyLarge)
            PersonCountChooser(draft.personCount) {
                callbacks.onDraftChange(draft.copy(personCount = it))
            }

            SectionTitle("けが・危険")
            BooleanDraftOption("けが人がいる", draft.injured) {
                callbacks.onDraftChange(draft.copy(injured = it))
            }
            BooleanDraftOption("重いけが人がいる", draft.seriouslyInjured) {
                callbacks.onDraftChange(draft.copy(seriouslyInjured = it))
            }
            BooleanDraftOption("歩くのが難しい人がいる", draft.mobilityImpaired) {
                callbacks.onDraftChange(draft.copy(mobilityImpaired = it))
            }
            BooleanDraftOption("高齢者がいる", draft.elderlyPresent) {
                callbacks.onDraftChange(draft.copy(elderlyPresent = it))
            }
            BooleanDraftOption("子どもがいる", draft.childrenPresent) {
                callbacks.onDraftChange(draft.copy(childrenPresent = it))
            }
            BooleanDraftOption("妊娠中の人がいる", draft.pregnantPresent) {
                callbacks.onDraftChange(draft.copy(pregnantPresent = it))
            }
            BooleanDraftOption("医療の助けが必要", draft.medicalSupportRequired) {
                callbacks.onDraftChange(draft.copy(medicalSupportRequired = it))
            }
            BooleanDraftOption("閉じ込められている", draft.trapped) {
                callbacks.onDraftChange(draft.copy(trapped = it))
            }
            BooleanDraftOption("火事・倒壊のおそれがある", draft.fireOrCollapseRisk) {
                callbacks.onDraftChange(draft.copy(fireOrCollapseRisk = it))
            }

            SectionTitle("必要なもの")
            RescueSupportNeed.entries.forEach { need ->
                BooleanDraftOption(need.label(), need in draft.supportNeeds) { checked ->
                    val updated = if (checked) draft.supportNeeds + need else draft.supportNeeds - need
                    callbacks.onDraftChange(draft.copy(supportNeeds = updated))
                }
            }

            SectionTitle("緊急度")
            RescueUrgency.entries.forEach { urgency ->
                SelectionButton(
                    label = urgency.label(),
                    selected = draft.urgency == urgency,
                ) { callbacks.onDraftChange(draft.copy(urgency = urgency)) }
            }

            SectionTitle("届け先・場所")
            OutlinedTextField(
                value = draft.destinationShelterId,
                onValueChange = { callbacks.onDraftChange(draft.copy(destinationShelterId = it.take(128))) },
                label = { Text("届ける避難所") },
                supportingText = { Text("避難所名や番号") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.location?.description.orEmpty(),
                onValueChange = { description ->
                    callbacks.onDraftChange(
                        draft.copy(location = (draft.location ?: com.example.relay.rescue.RescueLocation()).copy(description = description.take(256))),
                    )
                },
                label = { Text("いまいる場所") },
                supportingText = { Text("建物名、階、目印など") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = draft.freeText,
                onValueChange = { callbacks.onDraftChange(draft.copy(freeText = it.take(2_000))) },
                label = { Text("ほかに伝えたいこと") },
                supportingText = { Text("分からなければ空欄で大丈夫です") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            state.formMessage?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text("確認: $message", Modifier.padding(16.dp))
                }
            }
            Button(
                onClick = callbacks::onSubmitRequest,
                enabled = !state.isRequestSubmitting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Text(if (state.isRequestSubmitting) "保存しています…" else "🆘  この内容で助けを求める")
            }
            Text("内容は暗号化されます。運ぶ人には読めません。")
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
            OutlinedButton(
                onClick = { onChange((value - 1).coerceAtLeast(1)) },
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("−  減らす") }
            Text("${value}人", style = MaterialTheme.typography.headlineMedium)
            Button(
                onClick = { onChange((value + 1).coerceAtMost(1_000)) },
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("＋  増やす") }
        }
    }
}

@Composable
private fun BooleanDraftOption(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SelectionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
        role = Role.RadioButton
        this.selected = selected
    }
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text("✓  $label") }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text("○  $label") }
    }
}

private fun RescueUrgency.label(): String = when (this) {
    RescueUrgency.ROUTINE -> "通常：急な危険はない"
    RescueUrgency.URGENT -> "緊急：早く助けが必要"
    RescueUrgency.IMMEDIATE -> "最優先：命の危険がある"
}

private fun RescueSupportNeed.label(): String = when (this) {
    RescueSupportNeed.WATER -> "水"
    RescueSupportNeed.FOOD -> "食べ物"
    RescueSupportNeed.MEDICINE -> "薬・医療"
    RescueSupportNeed.RESCUE_TEAM -> "救助隊"
    RescueSupportNeed.TRANSPORT -> "移動の助け"
}
