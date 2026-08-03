package com.whiterose.minute.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.whiterose.minute.R
import com.whiterose.minute.core.Alerter
import com.whiterose.minute.core.Beeper
import com.whiterose.minute.core.PulseScheduler
import com.whiterose.minute.core.PulseService
import com.whiterose.minute.data.Prefs
import com.whiterose.minute.data.SettingsStore
import com.whiterose.minute.data.formatHour
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WhiteRoseScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val prefs by store.state.collectAsState()

    // A composition outlives the screen going dark, so every loop below is tied to window
    // focus. Without that they would keep ticking against a black screen for as long as the
    // activity stays alive, which is exactly the kind of quiet drain a watch cannot afford.
    val focused = LocalWindowInfo.current.isWindowFocused

    // Drives the countdown ring while the screen is actually being looked at.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(240L)
        }
    }

    var notificationsAllowed by remember { mutableStateOf(context.hasNotificationPermission()) }
    var batteryUnrestricted by remember { mutableStateOf(context.isBatteryUnrestricted()) }
    var beepMuted by remember { mutableStateOf(false) }
    // These can only change while the user is away in Settings, so re-read on the way back
    // instead of polling for something that is almost never different.
    LaunchedEffect(focused, prefs.alertMode, prefs.bypassDnd) {
        if (!focused) return@LaunchedEffect
        notificationsAllowed = context.hasNotificationPermission()
        batteryUnrestricted = context.isBatteryUnrestricted()
        beepMuted = Beeper.isInaudible(context, prefs)
    }

    // Set when the watch turns out to have no screen that can grant the exemption.
    var batteryScreenMissing by remember { mutableStateOf(false) }
    var notificationsDenied by remember { mutableStateOf(false) }
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
        if (!granted) notificationsDenied = true
    }

    val askForNotifications = {
        // After a refusal the system prompt stops appearing, so send the user somewhere
        // they can actually change it rather than leaving a button that does nothing.
        if (notificationsDenied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            context.openNotificationSettings()
        } else {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val setEnabled: (Boolean) -> Unit = { on ->
        store.setEnabled(on)
        if (on) {
            if (!notificationsAllowed) askForNotifications()
            PulseScheduler.schedule(context)
            PulseService.start(context)
        } else {
            PulseScheduler.cancel(context)
        }
    }

    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    val secondsElapsed = ((now % PulseScheduler.INTERVAL_MS) / 1_000L).toInt()
    val quietNow = prefs.isQuietAt(now)

    AppScaffold {
        val listState = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()

        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Hero(
                        enabled = prefs.enabled,
                        quiet = quietNow,
                        secondsElapsed = secondsElapsed,
                        secondsLeft = 60 - secondsElapsed,
                        quietUntil = formatHour(prefs.quietToHour),
                        onToggle = { setEnabled(!prefs.enabled) },
                    )
                }

                if (!notificationsAllowed) {
                    item {
                        SettingButton(
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            label = stringResource(R.string.notifications_blocked),
                            secondary = stringResource(R.string.notifications_blocked_desc),
                            iconRes = R.drawable.ic_info,
                            onClick = { askForNotifications() },
                        )
                    }
                }

                item {
                    ListHeader(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        Text(stringResource(R.string.section_vibration))
                    }
                }

                item {
                    SettingButton(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = stringResource(R.string.alert_mode),
                        secondary = stringResource(prefs.alertMode.labelRes),
                        iconRes =
                            if (prefs.alertMode.beeps) R.drawable.ic_sound
                            else R.drawable.ic_pulse,
                        onClick = {
                            val next = prefs.alertMode.next()
                            store.setAlertMode(next)
                            Alerter.fire(context, prefs.copy(alertMode = next))
                        },
                    )
                }

                if (prefs.alertMode.beeps && beepMuted) {
                    item {
                        SettingButton(
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            label = stringResource(R.string.muted_title),
                            secondary = stringResource(R.string.muted_desc),
                            iconRes = R.drawable.ic_info,
                            onClick = { context.openSoundSettings() },
                        )
                    }
                }

                item {
                    SliderRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        caption = stringResource(R.string.strength) + " · " +
                            stringResource(prefs.strengthLabelRes),
                        value = prefs.strength,
                        range = 1..Prefs.MAX_STRENGTH,
                        segmented = true,
                        onValueChange = { level ->
                            store.setStrength(level)
                            // Let the wrist judge the new level straight away.
                            Alerter.fire(context, prefs.copy(strength = level))
                        },
                    )
                }

                item {
                    SettingButton(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = stringResource(R.string.pattern),
                        secondary = stringResource(prefs.pattern.labelRes),
                        iconRes = R.drawable.ic_pulse,
                        onClick = {
                            val next = prefs.pattern.next()
                            store.setPattern(next)
                            Alerter.fire(context, prefs.copy(pattern = next))
                        },
                    )
                }

                item {
                    SettingButton(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = stringResource(R.string.length),
                        secondary = stringResource(prefs.length.labelRes),
                        iconRes = R.drawable.ic_clock,
                        onClick = {
                            val next = prefs.length.next()
                            store.setLength(next)
                            Alerter.fire(context, prefs.copy(length = next))
                        },
                    )
                }

                item {
                    SettingButton(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = stringResource(R.string.test_buzz),
                        secondary = stringResource(R.string.feel_it_now),
                        iconRes = R.drawable.ic_pulse,
                        filled = true,
                        onClick = { Alerter.fire(context, prefs) },
                    )
                }

                item {
                    ListHeader(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        Text(stringResource(R.string.section_schedule))
                    }
                }

                item {
                    SwitchRow(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        checked = prefs.quietEnabled,
                        label = stringResource(R.string.quiet_hours),
                        secondary = if (prefs.quietEnabled) {
                            stringResource(
                                R.string.quiet_hours_on,
                                formatHour(prefs.quietFromHour),
                                formatHour(prefs.quietToHour),
                            )
                        } else {
                            stringResource(R.string.quiet_hours_off)
                        },
                        onCheckedChange = store::setQuietEnabled,
                    )
                }

                if (prefs.quietEnabled) {
                    item {
                        SliderRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                            caption = stringResource(R.string.quiet_from) + " " +
                                formatHour(prefs.quietFromHour),
                            value = prefs.quietFromHour,
                            range = 0..23,
                            segmented = false,
                            onValueChange = store::setQuietFrom,
                        )
                    }
                    item {
                        SliderRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                            caption = stringResource(R.string.quiet_to) + " " +
                                formatHour(prefs.quietToHour),
                            value = prefs.quietToHour,
                            range = 0..23,
                            segmented = false,
                            onValueChange = store::setQuietTo,
                        )
                    }
                }

                item {
                    ListHeader(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        Text(stringResource(R.string.section_system))
                    }
                }

                item {
                    SwitchRow(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        checked = prefs.startOnBoot,
                        label = stringResource(R.string.start_on_boot),
                        secondary = stringResource(R.string.start_on_boot_desc),
                        onCheckedChange = store::setStartOnBoot,
                    )
                }

                item {
                    SwitchRow(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        checked = prefs.keepAwake,
                        label = stringResource(R.string.keep_awake),
                        secondary = stringResource(
                            if (prefs.keepAwake) R.string.keep_awake_on_desc
                            else R.string.keep_awake_off_desc
                        ),
                        onCheckedChange = store::setKeepAwake,
                    )
                }

                item {
                    SwitchRow(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        checked = prefs.bypassDnd,
                        label = stringResource(R.string.bypass_dnd),
                        secondary = stringResource(
                            if (prefs.bypassDnd) R.string.bypass_dnd_on_desc
                            else R.string.bypass_dnd_off_desc
                        ),
                        onCheckedChange = store::setBypassDnd,
                    )
                }

                item {
                    SettingButton(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = stringResource(R.string.battery_title),
                        secondary = stringResource(
                            when {
                                batteryUnrestricted -> R.string.battery_unrestricted
                                batteryScreenMissing -> R.string.battery_manual
                                else -> R.string.battery_restricted
                            }
                        ),
                        iconRes = R.drawable.ic_battery,
                        enabled = !batteryUnrestricted,
                        onClick = {
                            batteryScreenMissing = !context.requestBatteryExemption()
                        },
                    )
                }

                item {
                    About(
                        modifier = Modifier.fillMaxWidth(),
                        version = version,
                        buzzCount = prefs.buzzCount,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(
    enabled: Boolean,
    quiet: Boolean,
    secondsElapsed: Int,
    secondsLeft: Int,
    quietUntil: String,
    onToggle: () -> Unit,
) {
    val active = enabled && !quiet
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            MinuteRing(
                active = active,
                secondsElapsed = secondsElapsed,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) Brush.radialGradient(listOf(RoseDeep, RoseShadow))
                        else Brush.radialGradient(listOf(AshHigh, AshLow))
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.ic_rose),
                        contentDescription = stringResource(R.string.toggle_desc),
                        modifier = Modifier.size(if (enabled) 18.dp else 36.dp),
                        alpha = if (enabled) 1f else 0.6f,
                    )
                    if (enabled) {
                        Text(
                            text = secondsLeft.toString(),
                            style = MaterialTheme.typography.displaySmall,
                            color = if (quiet) Muted else Chalk,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = when {
                !enabled -> stringResource(R.string.state_paused)
                quiet -> stringResource(R.string.quiet_until, quietUntil)
                else -> stringResource(R.string.tagline)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Rose else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/** Sixty ticks, one per second, filling up to the next buzz. */
@Composable
private fun MinuteRing(active: Boolean, secondsElapsed: Int, modifier: Modifier) {
    val litColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f - 2.dp.toPx()
        repeat(60) { index ->
            val major = index % 5 == 0
            val angle = Math.toRadians(-90.0 + index * 6.0).toFloat()
            val length = if (major) 9.dp.toPx() else 5.dp.toPx()
            val on = active && index <= secondsElapsed
            val direction = Offset(cos(angle), sin(angle))
            drawLine(
                color = if (on) litColor else dimColor,
                start = centre + direction * (outer - length),
                end = centre + direction * outer,
                strokeWidth = if (major) 2.6.dp.toPx() else 1.8.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = if (on) 1f else 0.5f,
            )
        }
    }
}

@Composable
private fun SettingButton(
    modifier: Modifier,
    transformation: SurfaceTransformation,
    label: String,
    secondary: String?,
    iconRes: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = if (filled) ButtonDefaults.buttonColors()
        else ButtonDefaults.filledTonalButtonColors(),
        transformation = transformation,
        icon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        },
        secondaryLabel = secondary?.let {
            // Two lines: these rows have no trailing control, so there is room, and the
            // battery hint is longer than a single line can hold.
            { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun SwitchRow(
    modifier: Modifier,
    transformation: SurfaceTransformation,
    checked: Boolean,
    label: String,
    secondary: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    // No leading icon here on purpose: the trailing switch already eats width, and on a 40 mm
    // face an icon as well pushes the labels into ellipsis.
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        transformation = transformation,
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun SliderRow(
    modifier: Modifier,
    caption: String,
    value: Int,
    range: IntProgression,
    segmented: Boolean,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueProgression = range,
            segmented = segmented,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun About(modifier: Modifier, version: String, buzzCount: Int) {
    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.about_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = pluralStringResource(R.plurals.buzz_count, buzzCount, buzzCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = stringResource(R.string.about_version, version),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.isBatteryUnrestricted(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

private fun Context.openNotificationSettings() {
    val candidates = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName")),
    )
    for (intent in candidates) {
        if (!leadsSomewhereReal(intent)) continue
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to the next screen this watch build might have.
        } catch (_: SecurityException) {
            // Present but protected on some builds — treat exactly like missing.
        }
    }
}

/**
 * Asks to be exempt from battery optimisation. Returns false when this watch has none of the
 * screens that could grant it, so the caller can say so instead of looking broken.
 *
 * Wear builds are inconsistent here: the per-app prompt is frequently absent, so fall back to
 * the global list and finally to the app's own details page, which every build does have.
 */
private fun Context.openSoundSettings() {
    val candidates = listOf(
        Intent(Settings.ACTION_SOUND_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        if (!leadsSomewhereReal(intent)) continue
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to the next screen this watch build might have.
        } catch (_: SecurityException) {
            // Present but protected on some builds — treat exactly like missing.
        }
    }
}

private fun Context.requestBatteryExemption(): Boolean {
    val candidates = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName")),
    )
    for (intent in candidates) {
        if (!leadsSomewhereReal(intent)) continue
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // This watch hides that screen; fall through to the next.
        } catch (_: SecurityException) {
            // Present but protected on some builds — treat exactly like missing.
        }
    }
    return false
}

/**
 * Wear routes several phone-only Settings screens to Clockwork's FakeSettingsActivity, which
 * accepts the intent and then shows nothing whatsoever. Launching it "succeeds" while looking
 * to the user exactly like a dead button, so treat it as not being there at all.
 */
private fun Context.leadsSomewhereReal(intent: Intent): Boolean {
    @Suppress("DEPRECATION")
    val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    val name = resolved?.activityInfo?.name ?: return false
    return !name.contains("FakeSettings", ignoreCase = true)
}
