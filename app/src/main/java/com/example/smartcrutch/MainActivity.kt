package com.example.smartcrutch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.smartcrutch.ui.*
import com.example.smartcrutch.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        enableEdgeToEdge()
        setContent {
            SmartCrutchTheme {
                MainAppContainer()
            }
        }
    }
}

@Composable
fun MainAppContainer(viewModel: DashboardViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Permission Launcher
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ))
        } else {
            permissionLauncher.launch(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    Scaffold(
        bottomBar = { 
            BottomNavBar(
                currentScreen = uiState.currentScreen,
                onNavigate = { viewModel.navigateTo(it) }
            ) 
        },
        containerColor = BackgroundNavy,
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (uiState.currentScreen) {
                Screen.Home -> HomeScreen(uiState)
                Screen.Progress -> ProgressScreen(uiState)
                Screen.Sync -> SyncScreen(
                    uiState = uiState, 
                    onSyncClick = { viewModel.syncData() },
                    onToggleRawStream = { enabled -> viewModel.toggleRawStream(enabled) },
                    onToggleExperimentalDecoding = { enabled -> viewModel.toggleExperimentalDecoding(enabled) }
                )
                Screen.Profile -> ProfileScreen(uiState)
                Screen.Direct -> DirectConnectScreen(
                    uiState = uiState,
                    onScanClick = { viewModel.startBleScan() },
                    onConnectClick = { device: android.bluetooth.BluetoothDevice -> viewModel.connectToDevice(device, context) },
                    onDisconnectClick = { viewModel.disconnectBle() },
                    onStartRecording = { viewModel.startRecording(context) },
                    onStopRecording = { viewModel.stopRecording() },
                    onLabelChange = { gait, terrain, patient -> viewModel.setBatchLabels(gait, terrain, patient) },
                    onMetadataChange = { age, gender, weight, leg, name -> viewModel.setPatientMetadata(age, gender, weight, leg, name) },
                    onShareClick = { viewModel.shareLatestData(context) }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(uiState: DashboardUiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { Spacer(modifier = Modifier.height(10.dp)) }
        item { HeaderSection(uiState.isLiveFeedActive) }
        item { ScoreSection() }
        item {
            Text(
                text = "TODAY'S METRICS",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                letterSpacing = 1.sp
            )
        }
        item { MetricsGrid(uiState) }
        
        // New Live Server Feed Section
        item {
            Text(
                text = "LIVE SERVER FEED",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                letterSpacing = 1.sp
            )
        }
        item { LiveServerFeedSection(uiState.liveLogs) }
        
        item { AchievementsSection() }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun LiveServerFeedSection(logs: List<String>) {
    Surface(
        color = Color.Black.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(150.dp)
    ) {
        if (logs.isEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                Text("Connecting to server...", color = TextSecondary, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs.size) { index ->
                    Text(
                        text = logs[index],
                        color = if (index == 0) AccentGreen else TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun HeaderSection(isLive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(AccentBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(30.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Good morning,", color = TextSecondary, fontSize = 14.sp)
                Text("Alex", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = AccentGreen.copy(alpha = 0.1f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "Recovering Well",
                    color = AccentGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            if (isLive) {
                Text(
                    text = "● SERVER LIVE",
                    color = AccentGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ScoreSection() {
    Surface(
        color = SurfaceNavy,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Today's score", color = TextSecondary, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("87", color = AccentGreen, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("/ 100", color = TextSecondary, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
fun MetricsGrid(uiState: DashboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard(
                title = "Weight Bearing",
                modifier = Modifier.weight(1f),
                accentColor = AccentGreen,
                content = {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp)) {
                        CircularProgressIndicator(
                            progress = { uiState.weightBearing },
                            modifier = Modifier.fillMaxSize(),
                            color = AccentGreen,
                            strokeWidth = 8.dp,
                            strokeCap = StrokeCap.Round,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${(uiState.weightBearing * 100).toInt()}%", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("of limit", color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                },
                footer = "Within safe limit"
            )
            MetricCard(
                title = "Step Count",
                modifier = Modifier.weight(1f),
                accentColor = AccentPurple,
                content = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(String.format(java.util.Locale.US, "%,d", uiState.steps), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("/ ${uiState.goalSteps} steps", color = TextSecondary, fontSize = 12.sp)
                    }
                },
                footer = "${(uiState.steps * 100 / uiState.goalSteps)}% of daily goal"
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard(
                title = "Gait Pattern",
                modifier = Modifier.weight(1f),
                accentColor = AccentBlue,
                content = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GaitChip("Prescribed", "3-Point", AccentBlue)
                        GaitChip("Detected", uiState.gaitPattern, AccentGreen)
                    }
                },
                footer = "Matches prescription"
            )
            val smartTitle = when(uiState.lastReceivedType) {
                "GM1" -> "IMU + Magnetometer"
                "SENSOR" -> "Ambient Status"
                "DIRECT" -> "Direct BLE (GM1)"
                else -> "Data Stream"
            }
            MetricCard(
                title = smartTitle,
                modifier = Modifier.weight(1f),
                accentColor = AccentOrange,
                content = {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (uiState.lastReceivedType) {
                            "GM1" -> {
                                val data = uiState.latestGm1Data ?: emptyMap()
                                Text(
                                    text = String.format(java.util.Locale.US, "A: %.4f, %.4f, %.4f", data["ax_g"], data["ay_g"], data["az_g"]),
                                    color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = String.format(java.util.Locale.US, "G: %.2f, %.2f, %.2f", data["gx_dps"], data["gy_dps"], data["gz_dps"]),
                                    color = TextPrimary, fontSize = 11.sp
                                )
                                Text(
                                    text = String.format(java.util.Locale.US, "M: %d, %d, %d", data["mx_raw"], data["my_raw"], data["mz_raw"]),
                                    color = AccentOrange, fontSize = 11.sp, fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Seq: ${data["seq"]} | 10 pts",
                                    color = TextSecondary, fontSize = 9.sp
                                )
                            }
                            "DIRECT" -> {
                                val data = uiState.directImuData
                                Text(
                                    text = String.format(java.util.Locale.US, "A: %.4f, %.4f, %.4f", data["ax"], data["ay"], data["az"]),
                                    color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = String.format(java.util.Locale.US, "G: %.2f, %.2f, %.2f", data["gx"], data["gy"], data["gz"]),
                                    color = TextPrimary, fontSize = 11.sp
                                )
                                Text(
                                    text = String.format(java.util.Locale.US, "M: %d, %d, %d", data["mx"]?.toInt() ?: 0, data["my"]?.toInt() ?: 0, data["mz"]?.toInt() ?: 0),
                                    color = AccentOrange, fontSize = 11.sp
                                )
                            }
                            "SENSOR" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Thermostat, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                                    Text(String.format(java.util.Locale.US, "%.1f°C", uiState.temperature), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.WaterDrop, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                                    Text(String.format(java.util.Locale.US, "%.1f%% Hum", uiState.humidity), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            else -> {
                                if (uiState.latestNumbers.isEmpty()) {
                                    Text("Waiting for data...", color = TextSecondary, fontSize = 12.sp)
                                } else {
                                    Text(
                                        text = uiState.latestNumbers.joinToString(", "),
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 16.sp
                                    )
                                    Text(
                                        text = "${uiState.latestNumbers.size} values total",
                                        color = TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                },
                footer = when(uiState.lastReceivedType) {
                    "GM1" -> "Full 9-Axis + Seq"
                    "SENSOR" -> "Environmental"
                    else -> "Numbers extracted"
                }
            )
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    footer: String
) {
    Surface(
        color = SurfaceNavy,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accentColor))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                content()
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = footer,
                color = AccentGreen,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
fun GaitChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            color = color.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
        ) {
            Text(
                text = value,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun AchievementsSection() {
    Column {
        Text("ACHIEVEMENTS UNLOCKED", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(5) { index ->
                val color = when(index) {
                    0 -> AccentGreen
                    1 -> AccentBlue
                    2 -> AccentOrange
                    else -> Color.Gray.copy(alpha = 0.3f)
                }
                Box(
                    modifier = Modifier
                        .size(45.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when(index) {
                            0 -> Icons.AutoMirrored.Filled.DirectionsWalk
                            1 -> Icons.Default.Timer
                            2 -> Icons.Default.LocalFireDepartment
                            else -> Icons.Default.Lock
                        },
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SyncScreen(
    uiState: DashboardUiState, 
    onSyncClick: () -> Unit,
    onToggleRawStream: (Boolean) -> Unit,
    onToggleExperimentalDecoding: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Icon(
            Icons.Default.Sync,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Data Synchronization", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(40.dp))
        
        Surface(
            color = SurfaceNavy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SyncInfoRow("Status", uiState.syncStatus, if (uiState.syncStatus == "Connected") AccentGreen else TextSecondary)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                SyncInfoRow("Last Synced", uiState.lastSyncTime, TextPrimary)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "DEBUG SETTINGS",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier.align(Alignment.Start).padding(start = 8.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        Surface(
            color = SurfaceNavy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsToggleRow(
                    label = "Raw Data Stream",
                    description = "View unparsed byte sequences",
                    isEnabled = uiState.isRawStreamEnabled,
                    onToggle = onToggleRawStream
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                SettingsToggleRow(
                    label = "Experimental Binary",
                    description = "Decode <Hffffffhhh format",
                    isEnabled = uiState.isExperimentalDecodingEnabled,
                    onToggle = onToggleExperimentalDecoding
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onSyncClick,
            enabled = !uiState.isSyncing,
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            if (uiState.isSyncing) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("Sync Now", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun SettingsToggleRow(label: String, description: String, isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(description, color = TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentGreen,
                checkedTrackColor = AccentGreen.copy(alpha = 0.3f),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceNavy
            )
        )
    }
}

@Composable
fun SyncInfoRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ProgressScreen(uiState: DashboardUiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { Spacer(modifier = Modifier.height(10.dp)) }
        
        item {
            Text(
                "RECOVERY PROGRESS",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Historical weight bearing trends",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }

        item {
            Surface(
                color = SurfaceNavy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Weight Bearing Trend",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    WeightBearingChart(
                        data = uiState.weightHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ChartLegendItem("Actual Load", AccentGreen)
                        ChartLegendItem("Target Limit", Color.Red.copy(alpha = 0.5f))
                    }
                }
            }
        }

        item {
            MetricAnalysisSection(uiState)
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun WeightBearingChart(data: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas

        val width = size.width
        val height = size.height
        val spacing = width / (data.size - 1)
        
        // Draw Target Line (e.g., at 80% capacity)
        val targetY = height * 0.2f
        drawLine(
            color = Color.Red.copy(alpha = 0.3f),
            start = androidx.compose.ui.geometry.Offset(0f, targetY),
            end = androidx.compose.ui.geometry.Offset(width, targetY),
            strokeWidth = 2.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        )

        // Draw Data Path
        val path = Path().apply {
            data.forEachIndexed { index, value ->
                val x = index * spacing
                val y = height - (value * height)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = AccentGreen,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        
        // Draw fill under path
        val fillPath = Path().apply {
            addPath(path)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        
        drawPath(
            path = fillPath,
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(AccentGreen.copy(alpha = 0.3f), Color.Transparent)
            )
        )
        
        // Draw Points
        data.forEachIndexed { index, value ->
            val x = index * spacing
            val y = height - (value * height)
            drawCircle(
                color = AccentGreen,
                radius = 4.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(x, y)
            )
            drawCircle(
                color = BackgroundNavy,
                radius = 2.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(x, y)
            )
        }
    }
}

@Composable
fun ChartLegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
fun MetricAnalysisSection(uiState: DashboardUiState) {
    Surface(
        color = SurfaceNavy,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Weekly Insights", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            val improvement = if (uiState.steps > 3000) "15%" else "8%"
            InsightRow(
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                title = "Weight Consistency",
                description = "Your weight bearing has improved by $improvement this week.",
                color = AccentGreen
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
            InsightRow(
                icon = Icons.Default.Warning,
                title = "Gait Deviation",
                description = "Detected slight tilt in ${uiState.gaitPattern} steps. Keep crutch vertical.",
                color = AccentOrange
            )
        }
    }
}

@Composable
fun InsightRow(icon: ImageVector, title: String, description: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(description, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun ProfileScreen(uiState: DashboardUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { Spacer(modifier = Modifier.height(10.dp)) }
        
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(AccentOrange.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(50.dp))
                }
                Spacer(modifier = Modifier.width(20.dp))
                Column {
                    Text("Alex Johnson", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Patient ID: CR-99021", color = TextSecondary, fontSize = 14.sp)
                }
            }
        }

        item {
            Text("RECOVERY GOALS", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = SurfaceNavy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GoalEditRow("Daily Step Goal", "${uiState.goalSteps} steps", AccentPurple)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                    GoalEditRow("Weight Limit", "${uiState.weightLimit} kg", AccentGreen)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                    GoalEditRow("Prescribed Gait", uiState.gaitPattern, AccentBlue)
                }
            }
        }

        item {
            Text("HEALTHCARE PROVIDER", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = SurfaceNavy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(AccentBlue.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.LocalHospital, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(uiState.organizationName, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Dr. Sarah Smith • Orthopedic Dept.", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Button(
                onClick = { /* Logout or similar */ },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Log Out", color = Color.Red, fontWeight = FontWeight.Bold)
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun GoalEditRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Text(value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { /* Edit */ }) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = color.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun BottomNavBar(currentScreen: Screen, onNavigate: (Screen) -> Unit) {
    NavigationBar(
        containerColor = SurfaceNavy,
        contentColor = TextSecondary,
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Home") },
            selected = currentScreen == Screen.Home,
            onClick = { onNavigate(Screen.Home) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentGreen,
                selectedTextColor = AccentGreen,
                indicatorColor = AccentGreen.copy(alpha = 0.1f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
            label = { Text("Progress") },
            selected = currentScreen == Screen.Progress,
            onClick = { onNavigate(Screen.Progress) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentPurple,
                selectedTextColor = AccentPurple,
                indicatorColor = AccentPurple.copy(alpha = 0.1f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
            label = { Text("Direct") },
            selected = currentScreen == Screen.Direct,
            onClick = { onNavigate(Screen.Direct) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentBlue,
                selectedTextColor = AccentBlue,
                indicatorColor = AccentBlue.copy(alpha = 0.1f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Sync, contentDescription = null) },
            label = { Text("Sync") },
            selected = currentScreen == Screen.Sync,
            onClick = { onNavigate(Screen.Sync) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentBlue,
                selectedTextColor = AccentBlue,
                indicatorColor = AccentBlue.copy(alpha = 0.1f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.PersonOutline, contentDescription = null) },
            label = { Text("Profile") },
            selected = currentScreen == Screen.Profile,
            onClick = { onNavigate(Screen.Profile) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentOrange,
                selectedTextColor = AccentOrange,
                indicatorColor = AccentOrange.copy(alpha = 0.1f)
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LiveFeedPreview() {
    SmartCrutchTheme {
        Box(modifier = Modifier.background(BackgroundNavy).padding(16.dp)) {
            LiveServerFeedSection(listOf("[14:20:01] Connected to server.", "[14:20:05] Received data: 45kg pressure"))
        }
    }
}

@Composable
fun DirectConnectScreen(
    uiState: DashboardUiState,
    onScanClick: () -> Unit,
    onConnectClick: (android.bluetooth.BluetoothDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onLabelChange: (String, String, String) -> Unit,
    onMetadataChange: (String, String, String, String, String) -> Unit,
    onShareClick: () -> Unit
) {
    val context = LocalContext.current
    var gaitText by remember { mutableStateOf(uiState.batchGait) }
    var terrainText by remember { mutableStateOf(uiState.batchTerrain) }
    var patientIdText by remember { mutableStateOf(uiState.patientId) }
    
    var ageText by remember { mutableStateOf(uiState.patientAge) }
    var genderText by remember { mutableStateOf(uiState.patientGender) }
    var weightText by remember { mutableStateOf(uiState.patientWeight) }
    var legText by remember { mutableStateOf(uiState.injuredLeg) }
    var nameText by remember { mutableStateOf(uiState.patientName) }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { Spacer(modifier = Modifier.height(10.dp)) }
        item {
            Text(
                "DIRECT CONNECTION",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Connect directly to GM1-Node via BLE",
                color = TextSecondary,
                fontSize = 14.sp
            )
            Text(
                "Note: Location must be ON in system settings.",
                color = AccentOrange.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "If device not found: Ensure other Hub/Bridge apps are DISCONNECTED.",
                color = Color.Red.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Status Card
        item {
            Surface(
                color = SurfaceNavy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Connection Status", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            uiState.bleStatus,
                            color = if (uiState.isBleConnected) AccentGreen else TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        if (uiState.isBleConnected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        tint = if (uiState.isBleConnected) AccentGreen else AccentBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        if (uiState.isBleConnected) {
            // Metadata Section
            item {
                Surface(
                    color = SurfaceNavy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("PATIENT METADATA", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        OutlinedTextField(
                            value = patientIdText,
                            onValueChange = { 
                                patientIdText = it
                                onMetadataChange(ageText, genderText, weightText, legText, nameText)
                                onLabelChange(gaitText, terrainText, it)
                            },
                            label = { Text("Associated Patient ID", fontSize = 10.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen,
                                unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f)
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = nameText,
                            onValueChange = { 
                                nameText = it
                                onMetadataChange(ageText, genderText, weightText, legText, it)
                            },
                            label = { Text("Patient Name (Optional)", fontSize = 10.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f)
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = ageText,
                                onValueChange = { 
                                    ageText = it
                                    onMetadataChange(it, genderText, weightText, legText, nameText)
                                },
                                label = { Text("Age", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentBlue,
                                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f)
                                )
                            )
                            DropdownSelector(
                                label = "Gender",
                                options = listOf("Male", "Female", "Other", "Prefer not to say"),
                                selectedOption = genderText,
                                onOptionSelected = {
                                    genderText = it
                                    onMetadataChange(ageText, it, weightText, legText, nameText)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = weightText,
                                onValueChange = { 
                                    weightText = it
                                    onMetadataChange(ageText, genderText, it, legText, nameText)
                                },
                                label = { Text("Weight (kg)", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen,
                                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f)
                                )
                            )
                            DropdownSelector(
                                label = "Injured Leg",
                                options = listOf("Left", "Right", "Both", "None"),
                                selectedOption = legText,
                                onOptionSelected = {
                                    legText = it
                                    onMetadataChange(ageText, genderText, weightText, it, nameText)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Labeling Section
            item {
                Surface(
                    color = SurfaceNavy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("BATCH LABELS", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = gaitText,
                                onValueChange = { 
                                    gaitText = it
                                    onLabelChange(it, terrainText, patientIdText)
                                },
                                label = { Text("Gait Type", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentBlue,
                                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f)
                                )
                            )
                            OutlinedTextField(
                                value = terrainText,
                                onValueChange = { 
                                    terrainText = it
                                    onLabelChange(gaitText, it, patientIdText)
                                },
                                label = { Text("Terrain", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentPurple,
                                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }
            }

            // Recording Controls
            item {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = if (uiState.isRecording) Color.Red.copy(alpha = 0.1f) else SurfaceNavy,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (uiState.isRecording) "Recording Data..." else "Database Logging",
                                    color = if (uiState.isRecording) Color.Red else TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                if (uiState.isRecording) {
                                    Text("Saving to Documents folder", color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                            Button(
                                onClick = { if (uiState.isRecording) onStopRecording() else onStartRecording() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.isRecording) Color.Red else AccentBlue
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(if (uiState.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (uiState.isRecording) "Stop" else "Start Log")
                            }
                        }
                    }
                    
                    if (uiState.patientId.isNotBlank() && !uiState.isRecording) {
                        Button(
                            onClick = onShareClick,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Icon(Icons.Default.Email, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Email Patient History", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Real-time Data Card
            item {
                Surface(
                    color = SurfaceNavy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Current Values", color = TextSecondary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        val data = uiState.directImuData
                        if (data.isNotEmpty()) {
                            DataRow("Accel", String.format(java.util.Locale.US, "X: %.4f, Y: %.4f, Z: %.4f", data["ax"], data["ay"], data["az"]))
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.05f))
                            DataRow("Gyro", String.format(java.util.Locale.US, "X: %.2f, Y: %.2f, Z: %.2f", data["gx"], data["gy"], data["gz"]))
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.05f))
                            DataRow("Mag", String.format(java.util.Locale.US, "X: %d, Y: %d, Z: %d", data["mx"]?.toInt() ?: 0, data["my"]?.toInt() ?: 0, data["mz"]?.toInt() ?: 0))
                        } else {
                            Text("Waiting for notification data...", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Charts Section
            if (uiState.directHistory.isNotEmpty()) {
                item {
                    Text("REAL-TIME GRAPHS", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                }
                item {
                    ChartCard("Accelerometer (g)", uiState.directHistory, listOf("ax", "ay", "az"), listOf(AccentGreen, AccentBlue, AccentOrange))
                }
                item {
                    ChartCard("Gyroscope (dps)", uiState.directHistory, listOf("gx", "gy", "gz"), listOf(AccentPurple, Color.Cyan, Color.Magenta))
                }
                item {
                    ChartCard("Magnetometer (raw)", uiState.directHistory, listOf("mx", "my", "mz"), listOf(AccentOrange, Color.Yellow, Color.Red))
                }
            }

            item {
                Button(
                    onClick = onDisconnectClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect Device", color = Color.Red)
                }
            }
        } else {
            // Scan Section
            item {
                Button(
                    onClick = onScanClick,
                    enabled = !uiState.isScanning,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isScanning) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Scanning...")
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Scan for GM1-Node")
                    }
                }
            }

            item {
                Text("DISCOVERED DEVICES", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            }
            
            items(uiState.discoveredDevices.size) { index ->
                val device = uiState.discoveredDevices[index]
                DeviceItem(device, onConnectClick)
            }
        }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun ChartCard(title: String, history: List<Map<String, Float>>, keys: List<String>, colors: List<Color>) {
    Surface(
        color = SurfaceNavy,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            MultiAxisChart(
                history = history,
                keys = keys,
                colors = colors,
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                keys.forEachIndexed { i, key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(key.uppercase(), color = TextSecondary, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun MultiAxisChart(
    history: List<Map<String, Float>>,
    keys: List<String>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas

        val width = size.width
        val height = size.height
        val spacing = width / (history.size - 1)

        keys.forEachIndexed { keyIndex, key ->
            val color = colors[keyIndex]
            val values = history.map { it[key] ?: 0f }
            
            // Find min/max for scaling this specific chart type
            val minVal = values.minOrNull() ?: 0f
            val maxVal = values.maxOrNull() ?: 1f
            val range = (maxVal - minVal).coerceAtLeast(0.1f)

            val path = Path().apply {
                values.forEachIndexed { index, value ->
                    val x = index * spacing
                    // Normalize value to 0..1 range within the chart height
                    val normalized = (value - minVal) / range
                    val y = height - (normalized * height)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun DeviceItem(device: android.bluetooth.BluetoothDevice, onConnect: (android.bluetooth.BluetoothDevice) -> Unit) {
    Surface(
        color = SurfaceNavy,
        shape = RoundedCornerShape(12.dp),
        onClick = { onConnect(device) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = AccentBlue)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                // Safety for device name access
                val name = try { device.name ?: "Unknown Device" } catch (e: SecurityException) { "Restricted" }
                Text(name, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(device.address, color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

@Composable
fun DropdownSelector(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = { },
            readOnly = true,
            label = { Text(label, fontSize = 10.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f)
            )
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(SurfaceNavy)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = TextPrimary) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun DataRow(label: String, value: String) {
    Column {
        Text(label, color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = TextPrimary, fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    SmartCrutchTheme {
        MainAppContainer()
    }
}
