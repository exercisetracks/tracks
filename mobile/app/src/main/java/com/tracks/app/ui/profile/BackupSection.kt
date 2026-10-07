// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tracks.app.AppContainer
import com.tracks.app.backup.BackupCrypto
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PasswordField
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.backup.BackupFormatException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * The shortest passphrase accepted. The file is only as strong as this — the
 * KDF slows guessing, it cannot make "1234" hard — and the file may sit on a
 * USB stick or in someone's cloud folder for years.
 */
const val MIN_PASSPHRASE = 10

/** Whether a phone should be nudged to back up: no server, and nothing recent. */
fun backupOverdue(linked: Boolean, lastBackupAtMs: Long?, nowMs: Long, days: Int = 14): Boolean =
    !linked && (lastBackupAtMs == null || nowMs - lastBackupAtMs > days * 86_400_000L)

/**
 * Back up and restore, through the system's document picker.
 *
 * The picker rather than a folder of our own on purpose: the backup exists to
 * outlive this phone, so it has to land somewhere the person chose — a USB
 * stick, a cloud folder, a computer — and the Storage Access Framework is the
 * one place Android lets them choose any of those.
 *
 * Writing is handed to the app container and runs on after this screen, or
 * the app, is left; see `AppContainer.startBackup`.
 */
@Composable
fun BackupSection(container: AppContainer, linked: Boolean, onRestored: () -> Unit) {
    val scope = rememberCoroutineScope()
    val last by container.lastBackupAt.collectAsState()
    val progress by container.backupProgress.collectAsState()
    val result by container.backupResult.collectAsState()
    var target by remember { mutableStateOf<Uri?>(null) }
    val restore = rememberRestoreFlow(container, onRestored)

    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> target = uri }

    val working = progress != null || restore.working
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        Text(
            last?.let { "Last backup: ${formatDay(it)}" } ?: "No backup yet",
            style = MaterialTheme.typography.bodyMedium,
        )
        ButtonRow {
            PrimaryButton("Back up now", onClick = { create.launch("tracks-${LocalDate.now()}.tracksbackup") }, enabled = !working)
            TonalButton("Restore", onClick = restore.start, enabled = !working)
        }
        val status = when {
            progress != null -> "Writing backup… it carries on if you leave the app."
            restore.status != null -> restore.status
            else -> result
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    target?.let { uri ->
        PassphraseDialog(
            restoring = false,
            onDismiss = {
                target = null
                // The picker already created the file; cancelling must not leave it behind.
                scope.launch { container.discardBackup(uri) }
            },
            onConfirm = { pass ->
                target = null
                container.startBackup(uri, pass)
            },
        )
    }
}

/** A restore in progress or just finished, and how to begin one. See [rememberRestoreFlow]. */
class RestoreFlow internal constructor() {
    var working by mutableStateOf(false)
        internal set
    var status by mutableStateOf<String?>(null)
        internal set
    /** Opens the file picker; the passphrase is asked once a file is chosen. */
    var start: () -> Unit = {}
        internal set
}

/**
 * Restoring, on its own: the picker, the passphrase, the restore and its
 * outcome. Settings puts it beside "Back up now"; onboarding's "Restore from
 * a backup" calls [RestoreFlow.start] directly, so the button goes straight
 * to the files rather than to a dialog offering the same button again.
 */
@Composable
fun rememberRestoreFlow(container: AppContainer, onRestored: () -> Unit): RestoreFlow {
    val scope = rememberCoroutineScope()
    val flow = remember { RestoreFlow() }
    var source by remember { mutableStateOf<Uri?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> source = uri }
    flow.start = { open.launch(arrayOf("*/*")) }

    source?.let { uri ->
        PassphraseDialog(
            restoring = true,
            onDismiss = { source = null },
            onConfirm = { pass ->
                source = null
                flow.working = true
                flow.status = "Restoring…"
                scope.launch {
                    flow.status = runCatching {
                        val n = container.restoreBackup(uri, pass)
                        onRestored()
                        "Restored, with $n activity and health files."
                    }.getOrElse { e ->
                        when (e) {
                            is BackupCrypto.WrongPassphraseOrDamaged ->
                                "That passphrase does not open this backup, or the file is damaged."
                            is BackupCrypto.NotABackup, is BackupFormatException -> e.message
                            else -> "Could not restore the backup: ${e.message}"
                        }
                    }
                    pass.fill(' ')
                    flow.working = false
                }
            },
        )
    }
    return flow
}

@Composable
private fun PassphraseDialog(restoring: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = if (restoring) pass.isNotEmpty() else pass.length >= MIN_PASSPHRASE && pass == again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (restoring) "Backup passphrase" else "Choose a passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
                if (!restoring) {
                    Text(
                        "At least $MIN_PASSPHRASE characters. Without it the backup cannot be opened — " +
                            "not by you, and not by anyone else.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PasswordField(pass, { pass = it }, label = "Passphrase", modifier = Modifier.fillMaxWidth())
                if (!restoring) {
                    PasswordField(
                        again, { again = it }, label = "Again", modifier = Modifier.fillMaxWidth(),
                        isError = again.isNotEmpty() && again != pass,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(if (restoring) "Restore" else "Back up", onClick = { onConfirm(pass.toCharArray()) }, enabled = ok)
        },
        dismissButton = { NeutralButton("Cancel", onClick = onDismiss) },
    )
}

/**
 * The nudge: no server, and no backup in 14 days. On the dashboard because
 * that is where someone looks every day; dismissible for this visit, because a
 * nag that cannot be closed trains people to stop reading banners.
 */
@Composable
fun BackupReminder(container: AppContainer, session: Any?, onBackUp: () -> Unit) {
    val last by container.lastBackupAt.collectAsState()
    val linked by produceState(true, session) { value = container.isLinked() }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed || !backupOverdue(linked, last, System.currentTimeMillis())) return
    Surface(
        shape = RoundedCornerShape(Tokens.Card.radius),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Tokens.Card.padding),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2),
        ) {
            Text(
                if (last == null) "This phone holds the only copy of your history."
                else "Your last backup is more than two weeks old.",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Keep an encrypted backup somewhere else, or link a Tracks server.",
                style = MaterialTheme.typography.bodySmall,
            )
            ButtonRow {
                PrimaryButton("Back up", onClick = onBackUp)
                NeutralButton("Not now", onClick = { dismissed = true })
            }
        }
    }
}

private fun formatDay(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))
