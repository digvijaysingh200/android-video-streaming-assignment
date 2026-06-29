package com.example.androidvideostreamer.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.androidvideostreamer.ui.theme.CardDark
import com.example.androidvideostreamer.ui.theme.DarkBg
import com.example.androidvideostreamer.ui.theme.OnDarkSecondary
import com.example.androidvideostreamer.ui.theme.OutlineColor
import com.example.androidvideostreamer.ui.theme.StatusConnected
import com.example.androidvideostreamer.ui.theme.StatusDisconnected
import com.example.androidvideostreamer.ui.theme.StatusStreaming
import com.example.androidvideostreamer.ui.theme.Teal500
import com.example.androidvideostreamer.viewmodel.StreamingViewModel

// ── Dimensions ───────────────────────────────────────────────────────────────
private val CornerMd = 12.dp
private val CornerLg = 16.dp
private val PadLg    = 20.dp

/**
 * StreamingScreen
 *
 * The single full-screen composable for the app. It is split into:
 *  1. [AppHeader]            — branding bar at the top.
 *  2. [CameraPreviewSection] — fills the remaining space above the panel.
 *  3. [ControlPanel]         — scrollable bottom sheet-style card.
 */
@Composable
fun StreamingScreen(viewModel: StreamingViewModel) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Observe ViewModel state ──────────────────────────────────────────────
    val ipAddress           by viewModel.ipAddress.collectAsStateWithLifecycle()
    val port                by viewModel.port.collectAsStateWithLifecycle()
    val connectionStatus    by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val isConnected         by viewModel.isConnected.collectAsStateWithLifecycle()
    val isStreaming         by viewModel.isStreaming.collectAsStateWithLifecycle()
    val isCameraPermGranted by viewModel.isCameraPermissionGranted.collectAsStateWithLifecycle()

    // ── Stable PreviewView ───────────────────────────────────────────────────
    val previewView = remember { PreviewView(context) }

    // Start camera when permission is granted; stop on dispose
    DisposableEffect(isCameraPermGranted) {
        if (isCameraPermGranted) {
            viewModel.cameraManager.startPreview(
                context        = context,
                lifecycleOwner = lifecycleOwner,
                previewView    = previewView,
                frameAnalyzer  = viewModel.frameAnalyzer,  // activates ImageAnalysis
            )
        }
        onDispose { viewModel.cameraManager.stopPreview() }
    }

    // ── Root layout ──────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        AppHeader()

        CameraPreviewSection(
            previewView         = previewView,
            isStreaming         = isStreaming,
            isCameraPermGranted = isCameraPermGranted,
            modifier            = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        ControlPanel(
            ipAddress        = ipAddress,
            port             = port,
            connectionStatus = connectionStatus,
            isConnected      = isConnected,
            isStreaming      = isStreaming,
            onIpChange       = viewModel::onIpAddressChange,
            onPortChange     = viewModel::onPortChange,
            onConnect        = viewModel::connect,
            onDisconnect     = viewModel::disconnect,
            onStartStream    = viewModel::startStreaming,
            onStopStream     = viewModel::stopStreaming,
        )
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF0D2137), Color(0xFF0D1117))
                )
            )
            .padding(horizontal = PadLg, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Icons.Default.Videocam,
                contentDescription = null,
                tint               = Teal500,
                modifier           = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text       = "Video Streamer",
                style      = MaterialTheme.typography.titleLarge,
                color      = Color(0xFFE6EDF3),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── Camera preview ────────────────────────────────────────────────────────────

@Composable
private fun CameraPreviewSection(
    previewView: PreviewView,
    isStreaming: Boolean,
    isCameraPermGranted: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF090D13))
            .then(
                if (isStreaming) Modifier.border(width = 2.dp, color = StatusStreaming)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isCameraPermGranted) {
            AndroidView(
                factory  = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            NoCameraPermissionPlaceholder()
        }

        if (isStreaming) {
            LiveBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun NoCameraPermissionPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector        = Icons.Default.VideocamOff,
            contentDescription = "Camera unavailable",
            tint               = OnDarkSecondary,
            modifier           = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text  = "Camera permission required",
            style = MaterialTheme.typography.bodyMedium,
            color = OnDarkSecondary
        )
    }
}

/** Animated pulsing "● LIVE" badge shown in the top-left corner of the preview. */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue  = 0.9f,
        targetValue   = 1.15f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_scale"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(StatusStreaming)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text       = "LIVE",
            style      = MaterialTheme.typography.labelSmall,
            color      = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Control panel ─────────────────────────────────────────────────────────────

@Composable
private fun ControlPanel(
    ipAddress: String,
    port: String,
    connectionStatus: String,
    isConnected: Boolean,
    isStreaming: Boolean,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(topStart = CornerLg, topEnd = CornerLg),
        colors    = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PadLg),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Connection inputs ────────────────────────────────────────────
            SectionLabel("Connection")

            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppTextField(
                    value         = ipAddress,
                    onValueChange = onIpChange,
                    label         = "IP Address",
                    placeholder   = "e.g. 192.168.1.100",
                    keyboardType  = KeyboardType.Uri,
                    modifier      = Modifier.weight(3f),
                    enabled       = !isConnected
                )
                AppTextField(
                    value         = port,
                    onValueChange = onPortChange,
                    label         = "Port",
                    placeholder   = "8080",
                    keyboardType  = KeyboardType.Number,
                    modifier      = Modifier.weight(1.5f),
                    enabled       = !isConnected
                )
            }

            // ── Connect / Disconnect ─────────────────────────────────────────
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick  = onConnect,
                    enabled  = !isConnected,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(CornerMd),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = Teal500,
                        disabledContainerColor = OutlineColor
                    )
                ) {
                    Text("Connect", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick  = onDisconnect,
                    enabled  = isConnected,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(CornerMd),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = StatusDisconnected,
                        disabledContainerColor = OutlineColor
                    )
                ) {
                    Text("Disconnect", fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Status indicator ─────────────────────────────────────────────
            ConnectionStatusIndicator(
                status      = connectionStatus,
                isConnected = isConnected,
                isStreaming = isStreaming
            )

            // ── Streaming controls ───────────────────────────────────────────
            SectionLabel("Streaming")

            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick  = onStartStream,
                    enabled  = isConnected && !isStreaming,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(CornerMd),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = StatusConnected,
                        disabledContainerColor = OutlineColor
                    )
                ) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        modifier           = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Start Streaming", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick  = onStopStream,
                    enabled  = isStreaming,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(CornerMd),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = StatusStreaming,
                        disabledContainerColor = OutlineColor
                    )
                ) {
                    Icon(
                        Icons.Default.VideocamOff,
                        contentDescription = null,
                        modifier           = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Stop Streaming", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

// ── Reusable sub-components ───────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text          = text.uppercase(),
        style         = MaterialTheme.typography.labelSmall,
        color         = OnDarkSecondary,
        fontWeight    = FontWeight.Bold,
        letterSpacing = TextUnit(1.5f, TextUnitType.Sp)
    )
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value           = value,
        onValueChange   = onValueChange,
        label           = { Text(label) },
        placeholder     = { Text(placeholder) },
        singleLine      = true,
        enabled         = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors          = OutlinedTextFieldDefaults.colors(
            focusedBorderColor      = Teal500,
            unfocusedBorderColor    = OutlineColor,
            focusedLabelColor       = Teal500,
            unfocusedLabelColor     = OnDarkSecondary,
            focusedTextColor        = Color(0xFFE6EDF3),
            unfocusedTextColor      = Color(0xFFE6EDF3),
            disabledTextColor       = OnDarkSecondary,
            disabledBorderColor     = OutlineColor.copy(alpha = 0.5f),
            disabledLabelColor      = OnDarkSecondary.copy(alpha = 0.5f),
            cursorColor             = Teal500,
            focusedContainerColor   = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor  = Color.Transparent,
        ),
        shape    = RoundedCornerShape(CornerMd),
        modifier = modifier
    )
}

@Composable
private fun ConnectionStatusIndicator(
    status: String,
    isConnected: Boolean,
    isStreaming: Boolean
) {
    val dotColor by animateColorAsState(
        targetValue   = when {
            isStreaming -> StatusStreaming
            isConnected -> StatusConnected
            else        -> StatusDisconnected
        },
        animationSpec = tween(400),
        label         = "status_dot_color"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerMd))
            .background(Color(0xFF0D1117))
            .border(1.dp, OutlineColor, RoundedCornerShape(CornerMd))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text  = "Status",
                style = MaterialTheme.typography.labelSmall,
                color = OnDarkSecondary
            )
            AnimatedContent(
                targetState   = status,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label         = "status_text"
            ) { statusText ->
                Text(
                    text       = statusText,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = dotColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
