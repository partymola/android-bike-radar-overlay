// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.launch

// `permissions` is the list of runtime perms to request. Empty means the
// special overlay permission, routed via Settings intent. We group
// BLUETOOTH_SCAN + BLUETOOTH_CONNECT into one "Nearby devices" card because
// Android 12+ treats them as one permission group and grants them with a
// single system prompt.
internal data class PermissionSpec(
    val permissions: List<String>,
    val title: String,
    val rationale: String,
    val required: Boolean,
)

internal val PERMISSIONS = buildList {
    add(PermissionSpec(
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
        "Nearby devices",
        "Scan for and connect to your radar and dashcam over Bluetooth.",
        required = true,
    ))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionSpec(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            "Notifications",
            "Show a silent status notification while the service runs.",
            required = true,
        ))
    }
    add(PermissionSpec(
        emptyList(),
        "Draw over other apps",
        "Draw the radar overlay on top of your cycling app. Without this the alerts still play, but you won't see the overlay.",
        required = false,
    ))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(prefs: Prefs, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onFinished) { Text("Skip") }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = false,
            ) { page ->
                when (page) {
                    0 -> PermissionsStep(
                        onNext = { scope.launch { pagerState.animateScrollToPage(1) } },
                    )
                    1 -> HaCredentialsStep(
                        onNext = {
                            if (hasRadarBond(ctx)) onFinished()
                            else scope.launch { pagerState.animateScrollToPage(2) }
                        },
                        onSkip = {
                            if (hasRadarBond(ctx)) onFinished()
                            else scope.launch { pagerState.animateScrollToPage(2) }
                        },
                    )
                    2 -> PairingStep(prefs = prefs, onFinish = onFinished)
                }
            }
        }
    }
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requiredGranted = remember(refresh) {
        PERMISSIONS.filter { it.required }.all { isSpecGranted(ctx, it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A few system permissions so the app can connect, stay running, and show alerts.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PERMISSIONS.forEach { spec ->
            PermissionCard(
                spec = spec,
                refresh = refresh,
                onRefresh = { refresh++ },
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onNext,
            enabled = requiredGranted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Next")
        }
    }
}

@Composable
internal fun PermissionCard(
    spec: PermissionSpec,
    refresh: Int,
    onRefresh: () -> Unit,
) {
    val ctx = LocalContext.current
    val granted = remember(refresh, spec.permissions) {
        isSpecGranted(ctx, spec)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefresh() }

    val containerColor = when {
        granted -> MaterialTheme.colorScheme.primaryContainer
        !spec.required -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when {
        granted -> MaterialTheme.colorScheme.onPrimaryContainer
        !spec.required -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(spec.title, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(spec.rationale, style = MaterialTheme.typography.bodySmall)
                if (!spec.required && !granted) {
                    Text(
                        "Optional - overlay disabled if denied.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (granted) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(24.dp),
                )
            } else {
                OutlinedButton(
                    onClick = {
                        if (spec.permissions.isEmpty()) {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}"),
                                )
                            )
                        } else {
                            launcher.launch(spec.permissions.toTypedArray())
                        }
                    },
                ) {
                    Text(if (spec.required) "Grant" else "Enable")
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun hasRadarBond(ctx: Context): Boolean {
    return try {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        val adapter = mgr.adapter ?: return false
        if (!adapter.isEnabled) return false
        adapter.bondedDevices?.any { dev ->
            val n = dev.name?.lowercase() ?: ""
            n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
        } == true
    } catch (_: Throwable) { false }
}

internal fun isSpecGranted(ctx: Context, spec: PermissionSpec): Boolean {
    return if (spec.permissions.isEmpty()) {
        Settings.canDrawOverlays(ctx)
    } else {
        spec.permissions.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
private fun HaCredentialsStep(onNext: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Home Assistant", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Publish ride and battery telemetry to HA for logging, dashboards, and pre-ride reminders. Skip if you don't use HA.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip for now") }
            Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Next") }
        }
    }
}

@Composable
private fun PairingStep(prefs: Prefs, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var ownership by remember { mutableStateOf(prefs.dashcamOwnership) }
    var dashcamMac by remember { mutableStateOf(prefs.dashcamMac) }
    var dashcamDisplayName by remember { mutableStateOf(prefs.dashcamDisplayName) }
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pair your radar", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pairing happens in Android's Bluetooth settings, not in this app. " +
            "Put the radar in pair mode (check the manual if unsure), then tap below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open Bluetooth settings")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Front dashcam", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Got a Bluetooth dashcam? The overlay shows its battery and " +
                        "flags when it stops broadcasting.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                when (ownership) {
                    DashcamOwnership.YES -> {
                        Text(
                            dashcamDisplayName?.let { "Selected: $it" }
                                ?: "Select which paired device is your dashcam.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showPicker = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (dashcamMac == null) "Pick device" else "Change")
                            }
                            TextButton(onClick = {
                                ownership = DashcamOwnership.NO
                                prefs.dashcamOwnership = DashcamOwnership.NO
                                prefs.dashcamMac = null
                                prefs.dashcamDisplayName = null
                                prefs.dashcamWarnWhenOff = false
                            }) { Text("I don't have one") }
                        }
                    }
                    DashcamOwnership.NO -> {
                        Text(
                            "You said you don't have a dashcam. You can change this " +
                                "later in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = {
                            ownership = DashcamOwnership.YES
                            prefs.dashcamOwnership = DashcamOwnership.YES
                        }) { Text("Actually, I do — set it up") }
                    }
                    DashcamOwnership.UNANSWERED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    ownership = DashcamOwnership.YES
                                    prefs.dashcamOwnership = DashcamOwnership.YES
                                    showPicker = true
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Set it up") }
                            OutlinedButton(
                                onClick = {
                                    ownership = DashcamOwnership.NO
                                    prefs.dashcamOwnership = DashcamOwnership.NO
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("I don't have one") }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text("Finish")
        }
    }

    if (showPicker) {
        DashcamPickerDialog(
            currentMac = dashcamMac,
            onDismiss = { showPicker = false },
            onConfirm = { mac, name ->
                dashcamMac = mac
                dashcamDisplayName = name
                prefs.dashcamMac = mac
                prefs.dashcamDisplayName = name
                if (mac != null) {
                    // Default the warn switch on once a device is picked during
                    // onboarding — the user just opted in to the feature by
                    // selecting one, so they almost certainly want the warning.
                    prefs.dashcamWarnWhenOff = true
                }
                showPicker = false
            },
        )
    }
}
