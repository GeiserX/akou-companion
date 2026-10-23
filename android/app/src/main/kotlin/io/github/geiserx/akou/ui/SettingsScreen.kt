// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.geiserx.akou.core.client.ServerProbe
import io.github.geiserx.akou.settings.ServerForm
import io.github.geiserx.akou.settings.Settings
import kotlinx.coroutines.launch
import java.util.UUID

/** One server: its URL, its key and a Test (A5 brings several). */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf<UUID?>(null) }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val entry = settings.book().servers.firstOrNull()
        id = entry?.id ?: UUID.randomUUID()
        url = entry?.url ?: ""
        savedKey = entry?.let { settings.key(it.id) }
        key = savedKey ?: ""
        result = entry?.tested?.let(ServerForm::describe)
    }

    /** Saves the typed URL and key; true when the URL is usable. */
    suspend fun save(): Boolean {
        val sid = id ?: return false
        ServerForm.urlProblem(url)?.let { result = it; return false }
        val saved = ServerForm.save(settings.book(), sid, url, key, savedKey)
        settings.save(saved.book)
        if (saved.keyChanged) {
            // A failed save does not count as changed (KeyStore.swift): say so, and test nothing.
            if (!settings.keys.save(key, sid.toString())) {
                result = "The key could not be stored on this phone. Enter it again."
                return false
            }
            savedKey = key.trim().ifEmpty { null }
        }
        if (saved.entry.tested == null) result = null
        return true
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Server", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = {
                scope.launch {
                    // Nothing typed and nothing saved closes; a cleared saved address stays, with the reason.
                    val nothingSaved = url.isBlank() && settings.book().server(id) == null
                    if (nothingSaved || save()) onBack()
                }
            }) { Text("Done") }
        }
        OutlinedTextField(
            value = url, onValueChange = { url = it }, label = { Text("Address") },
            placeholder = { Text("https://akou.example.com") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it }, label = { Text("Key") }, placeholder = { Text("ak_…") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Use https, or plain http only to a private address (your network or Tailscale). The key stays on this phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = !testing,
            onClick = {
                scope.launch {
                    if (!save()) return@launch
                    val sid = id ?: return@launch
                    testing = true
                    result = "Testing…"
                    // The key as saved: a pasted trailing newline must not fail a key that works.
                    val probe = ServerProbe(url.trim(), key.trim())
                    try {
                        val tested = ServerForm.tested(probe.server(), probe.keyInfo())
                        settings.save(ServerForm.tested(settings.book(), sid, tested))
                        result = ServerForm.describe(tested)
                    } catch (e: Exception) {
                        settings.save(ServerForm.tested(settings.book(), sid, null))
                        result = ServerForm.failure(e)
                    } finally {
                        testing = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Test") }
        result?.let { Text(it) }
    }
}
