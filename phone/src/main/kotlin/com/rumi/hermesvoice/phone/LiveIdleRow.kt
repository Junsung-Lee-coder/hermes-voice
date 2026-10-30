package com.rumi.hermesvoice.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rumi.hermesvoice.core.settings.SettingsHelp

@Composable
internal fun LiveIdleRow(seconds: Int, save: (String) -> Unit) {
    var draft by remember(seconds) { mutableStateOf(seconds.toString()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Text("Live idle",Modifier.weight(1f))
        OutlinedTextField(draft,{if(it.length<=2 && it.all(Char::isDigit)) draft=it},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
            label={Text("Seconds")},modifier=Modifier.width(88.dp).testTag("live_idle_seconds"))
        TextButton(onClick={save(draft)},modifier=Modifier.testTag("live_idle_apply")){Text("Apply")}
        InfoHelpButton(SettingsHelp.liveIdle())
    }
}
