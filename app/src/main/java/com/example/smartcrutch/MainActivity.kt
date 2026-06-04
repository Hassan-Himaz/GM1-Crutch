package com.example.smartcrutch

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
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
            val viewModel: DashboardViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            SmartCrutchTheme(darkTheme = uiState.isDarkMode) {
                if (uiState.showSplash) {
                    SplashScreen(onFinish = { viewModel.dismissSplash() })
                } else {
                    MainAppContainer(viewModel)
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onFinish: () -> Unit) {
    var startTextAnimation by remember { mutableStateOf(false) }
    val alphaAnim = animateFloatAsState(
        targetValue = if (startTextAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "alpha"
    )

    LaunchedEffect(Unit) {
        delay(1000) // Quick logo glance
        startTextAnimation = true
        delay(2000) // Professional fade-in
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F2027)), // Deep teal-navy from your logo
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Animated Icon/Logo Container
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                // If you have saved 'app_logo.png' in res/drawable, uncomment the Image below:
                /*
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "StrideQ Logo",
                    modifier = Modifier.fillMaxSize()
                )
                */
                
                // Fallback icon that looks professional
                Icon(
                    imageVector = Icons.Default.AccessibilityNew,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(100.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(40.dp))
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer(alpha = alphaAnim.value)
            ) {
                Text(
                    text = "StrideQ",
                    color = Color.White,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                    style = MaterialTheme.typography.displayLarge
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(3.dp)
                        .background(Color(0xFFE91E63)) // Vibrant accent line from your reference
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Intelligent Recovery",
                    color = AccentGreen,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

@Composable
fun MainAppContainer(viewModel: DashboardViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Permission Launcher
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Handle permissions
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
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
                uiState = uiState,
                onNavigate = { viewModel.navigateTo(it) }
            ) 
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (uiState.showSyncPopup) {
            SyncMessagePopup(
                uiState = uiState,
                onDismiss = { viewModel.dismissSyncPopup() }
            )
        }
        if (uiState.showGaitMismatchPopup) {
            GaitMismatchPopup(
                uiState = uiState,
                onDismiss = { viewModel.dismissGaitMismatchPopup() }
            )
        }
        if (uiState.showPrescriptionPopup) {
            PrescriptionPopup(
                uiState = uiState,
                onSubmit = { crutches, gait, permanently -> 
                    viewModel.submitPrescription(crutches, gait)
                    viewModel.dismissPrescriptionPopup(permanently)
                },
                onDismiss = { viewModel.dismissPrescriptionPopup(it) }
            )
        }
        Box(modifier = Modifier.padding(innerPadding)) {
            when (uiState.currentScreen) {
                Screen.Home -> HomeScreen(uiState)
                Screen.Progress -> ProgressScreen(uiState)
                Screen.Sync -> SyncScreen(
                    uiState = uiState, 
                    onSyncClick = { viewModel.syncData() },
                    onToggleRawStream = { enabled -> viewModel.toggleRawStream(enabled) },
                    onToggleExperimentalDecoding = { enabled -> viewModel.toggleExperimentalDecoding(enabled) },
                    onFetchLocalData = { viewModel.fetchFromNgrok() }
                )
                Screen.Profile -> ProfileScreen(
                    uiState = uiState,
                    onClearData = { viewModel.clearAllLocalData(context) },
                    onToggleDeveloperMode = { viewModel.toggleDeveloperMode(it) },
                    onUpdateGoalSteps = { viewModel.updateGoalSteps(it) },
                    onUpdateWeightLimit = { viewModel.updateWeightLimit(it) },
                    onUpdatePrescribedGait = { viewModel.updatePrescribedGait(it) },
                    onUpdateDetectedGait = { viewModel.updateDetectedGait(it) },
                    onToggleDarkMode = { viewModel.toggleDarkMode(it) }
                )
                Screen.Direct -> if (uiState.isDeveloperModeEnabled) {
                    DirectConnectScreen(
                        uiState = uiState,
                        onPicoRelayToggle = { if (uiState.bleStatus.contains("Advertising") || uiState.isBleConnected) viewModel.stopPicoRelayMode() else viewModel.startPicoRelayMode(context) },
                        onStartRecording = { viewModel.startRecording(context) },
                        onStopRecording = { 
                            viewModel.stopRecording()
                            android.widget.Toast.makeText(context, "Session Saved. History ready to share.", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onLabelChange = { gait, terrain, patient -> viewModel.setBatchLabels(gait, terrain, patient) },
                        onMetadataChange = { age, gender, weight, leg, name -> viewModel.setPatientMetadata(age, gender, weight, leg, name) },
                        onShareClick = { viewModel.shareLatestData(context) }
                    )
                } else {
                    viewModel.navigateTo(Screen.Home)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(uiState: DashboardUiState) {
    var showAchievementsMenu by remember { mutableStateOf(false) }

    if (showAchievementsMenu) {
        AlertDialog(
            onDismissRequest = { showAchievementsMenu = false },
            title = { Text("Your Achievements", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AchievementDetailRow(
                        title = "Consistency King",
                        description = "Maintain your goals for an entire week.",
                        icon = Icons.Default.CalendarMonth,
                        color = AccentPurple
                    )
                    AchievementDetailRow(
                        title = "Perfect Recovery",
                        description = "Reach a recovery score of 100.",
                        icon = Icons.Default.Star,
                        color = AccentGreen
                    )
                    AchievementDetailRow(
                        title = "Step Master",
                        description = "Hit your daily step goal 3 days in a row.",
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        color = AccentBlue
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAchievementsMenu = false }) {
                    Text("CLOSE", color = AccentBlue)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { HeaderSection(uiState.isLiveFeedActive, uiState.patientName) }
        item { ScoreSection(uiState.recoveryScore) }
        item {
            Text(
                text = "TODAY'S METRICS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                fontSize = 18.sp
            )
        }
        item { MetricsGrid(uiState) }
        
        item {
            AchievementsSection(onHeaderClick = { showAchievementsMenu = true })
        }
        
        // New Live Server Feed Section
        if (uiState.isDeveloperModeEnabled) {
            item {
                Text(
                    text = "LIVE SERVER FEED",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
            }
            item { LiveServerFeedSection(uiState.liveLogs) }
        }
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
fun HeaderSection(isLive: Boolean, userName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(AccentBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(45.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Good morning,", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
                val firstName = userName.split(" ").firstOrNull() ?: userName
                Text(firstName, color = MaterialTheme.colorScheme.onSurface, fontSize = 36.sp, fontWeight = FontWeight.Black)
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
fun ScoreSection(score: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Today's score", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(score.toString(), color = AccentGreen, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    Text("/ 100", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(50.dp))
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
                            Text("${(uiState.weightBearing * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("of limit", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                        Text(String.format(java.util.Locale.US, "%,d", uiState.steps), color = MaterialTheme.colorScheme.onSurface, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("/ ${uiState.goalSteps} steps", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
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
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GaitChip("Goal", uiState.selectedGait.ifEmpty { "Swing" }, AccentBlue)
                        GaitChip("Live", uiState.gaitPattern, AccentGreen)
                    }
                },
                footer = when {
                    uiState.gaitPattern == "Not Detected" -> "Awaiting data"
                    uiState.selectedGait.equals(uiState.gaitPattern, ignoreCase = true) -> "Matches prescription"
                    else -> "Does not match prescription"
                },
                footerColor = when {
                    uiState.gaitPattern == "Not Detected" -> MaterialTheme.colorScheme.onSurfaceVariant
                    uiState.selectedGait.equals(uiState.gaitPattern, ignoreCase = true) -> AccentGreen
                    else -> Color.Red
                }
            )
            MetricCard(
                title = "Wrist Strain",
                modifier = Modifier.weight(1f),
                accentColor = if (uiState.wristStrainIndex > 10) Color.Red else AccentGreen,
                content = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.wristStrainIndex.toString(),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = if (uiState.wristStrainIndex > 10) "HIGH" else "LOW",
                            color = if (uiState.wristStrainIndex > 10) Color.Red else AccentGreen,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                },
                footer = if (uiState.wristStrainIndex > 10) "Take a break" else "Safe to continue"
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
    footer: String,
    footerColor: Color = AccentGreen
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accentColor))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                content()
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = footer,
                color = footerColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
fun GaitChip(label: String, value: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                text = value,
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun AchievementsSection(onHeaderClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onHeaderClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ACHIEVEMENTS UNLOCKED", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(5) { index ->
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(AccentPurple.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when(index) {
                            0 -> Icons.AutoMirrored.Filled.DirectionsWalk
                            1 -> Icons.Default.Timer
                            2 -> Icons.Default.Whatshot
                            3 -> Icons.Default.MilitaryTech
                            else -> Icons.Default.EmojiEvents
                        },
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AchievementDetailRow(title: String, description: String, icon: ImageVector, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(description, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun SyncScreen(
    uiState: DashboardUiState, 
    onSyncClick: () -> Unit,
    onToggleRawStream: (Boolean) -> Unit,
    onToggleExperimentalDecoding: (Boolean) -> Unit,
    onFetchLocalData: () -> Unit
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
        Text("Data Synchronization", color = MaterialTheme.colorScheme.onSurface, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(40.dp))
        
        if (uiState.isDeveloperModeEnabled) {
            Text(
                text = "LOCAL DEVELOPMENT",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(start = 8.dp),
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = onFetchLocalData,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Receive Data from Local App", fontWeight = FontWeight.Bold)
                    }
                    if (uiState.lastNgrokData.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.lastNgrokData,
                            color = AccentGreen,
                            fontSize = 12.sp,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (uiState.isDeveloperModeEnabled) {
            Text(
                text = "CAMGENIUM HARVESTER",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(start = 8.dp),
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SyncInfoRow("Status", uiState.syncStatus, if (uiState.syncStatus == "Connected") AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                    SyncInfoRow("Last Synced", uiState.lastSyncTime, MaterialTheme.colorScheme.onSurface)
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "DEBUG SETTINGS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(start = 8.dp),
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            Surface(
                color = MaterialTheme.colorScheme.surface,
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
        } else {
            // If dev mode is off, maybe show a simple message or nothing
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Standard Mode: Automatic background sync enabled.", color = TextSecondary, textAlign = TextAlign.Center)
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        if (uiState.isDeveloperModeEnabled) {
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
                    Text("Sync Now (Camgenium)", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
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
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentGreen,
                checkedTrackColor = AccentGreen.copy(alpha = 0.3f),
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface
            )
        )
    }
}

@Composable
fun SyncInfoRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Historical weight bearing trends",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp
            )
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Weight Bearing Trend",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
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
            MetricAnalysisSection()
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun WeightBearingChart(data: List<Float>, modifier: Modifier = Modifier) {
    val bgColor = MaterialTheme.colorScheme.background
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
                color = bgColor,
                radius = 2.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(x, y)
            )
        }
    }
}

@Composable
fun ChartLegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
fun MetricAnalysisSection() {
    Column {
        Text("METRIC ANALYSIS", style = MaterialTheme.typography.labelLarge, color = TextSecondary, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(12.dp))
        
        Surface(
            color = SurfaceNavy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Weekly Insights", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                
                InsightRow(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    title = "Weight Bearing",
                    description = "Increasing stability",
                    color = AccentGreen
                )
                
                InsightRow(
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    title = "Step Quality",
                    description = "Consistent cadence",
                    color = AccentBlue
                )
            }
        }
    }
}

@Composable
fun InsightRow(icon: ImageVector, title: String, description: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(description, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun ProfileScreen(
    uiState: DashboardUiState, 
    onClearData: () -> Unit,
    onToggleDeveloperMode: (Boolean) -> Unit,
    onUpdateGoalSteps: (Int) -> Unit,
    onUpdateWeightLimit: (Float) -> Unit,
    onUpdatePrescribedGait: (String) -> Unit,
    onUpdateDetectedGait: (String) -> Unit,
    onToggleDarkMode: (Boolean) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditSteps by remember { mutableStateOf(false) }
    var showEditWeight by remember { mutableStateOf(false) }
    var showEditGait by remember { mutableStateOf(false) }
    var showEditDetectedGait by remember { mutableStateOf(false) }

    // Dialog for Steps
    if (showEditSteps) {
        var tempSteps by remember { mutableStateOf(uiState.goalSteps.toString()) }
        AlertDialog(
            onDismissRequest = { showEditSteps = false },
            title = { Text("Edit Step Goal", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = tempSteps,
                    onValueChange = { tempSteps = it },
                    label = { Text("Steps") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    tempSteps.toIntOrNull()?.let { onUpdateGoalSteps(it) }
                    showEditSteps = false
                }) { Text("SAVE", color = AccentGreen) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Dialog for Weight
    if (showEditWeight) {
        var tempWeight by remember { mutableStateOf(uiState.weightLimit.toString()) }
        AlertDialog(
            onDismissRequest = { showEditWeight = false },
            title = { Text("Edit Weight Limit", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = tempWeight,
                    onValueChange = { tempWeight = it },
                    label = { Text("Limit (kg)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    tempWeight.toFloatOrNull()?.let { onUpdateWeightLimit(it) }
                    showEditWeight = false
                }) { Text("SAVE", color = AccentGreen) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Dialog for Gait
    if (showEditGait) {
        var tempGait by remember { mutableStateOf(uiState.selectedGait) }
        AlertDialog(
            onDismissRequest = { showEditGait = false },
            title = { Text("Edit Prescribed Gait", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = tempGait,
                    onValueChange = { tempGait = it },
                    label = { Text("Gait Type") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdatePrescribedGait(tempGait)
                    showEditGait = false
                }) { Text("SAVE", color = AccentGreen) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Dialog for Detected Gait
    if (showEditDetectedGait) {
        var tempGait by remember { mutableStateOf(uiState.gaitPattern) }
        AlertDialog(
            onDismissRequest = { showEditDetectedGait = false },
            title = { Text("Edit Detected Gait (Demo)", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = tempGait,
                    onValueChange = { tempGait = it },
                    label = { Text("Live Gait") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateDetectedGait(tempGait)
                    showEditDetectedGait = false
                }) { Text("SAVE", color = AccentGreen) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete All Local Data?", color = Color.White) },
            text = { Text("This will permanently remove all stored sensor history for all patients on this device.", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearData()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("DELETE ALL", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("CANCEL", color = TextPrimary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { Spacer(modifier = Modifier.height(10.dp)) }
        
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(AccentOrange.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(60.dp))
                }
                Spacer(modifier = Modifier.width(24.dp))
                Column {
                    Text(uiState.patientName, color = MaterialTheme.colorScheme.onSurface, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text("Patient ID: CR-99021", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                }
            }
        }

        item {
            Text("RECOVERY GOALS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GoalEditRow("Daily Step Goal", "${uiState.goalSteps} steps", AccentPurple, onEditClick = { showEditSteps = true })
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                    GoalEditRow("Weight Limit", "${uiState.weightLimit} kg", AccentGreen, onEditClick = { showEditWeight = true })
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                    GoalEditRow("Prescribed Gait", uiState.selectedGait.ifEmpty { "Swing" }, AccentBlue, onEditClick = { showEditGait = true })
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                    GoalEditRow("Detected Gait (Demo)", uiState.gaitPattern, AccentGreen, onEditClick = { showEditDetectedGait = true })
                }
            }
        }

        item {
            Text("SYSTEM SETTINGS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsToggleRow(
                        label = "Developer Mode",
                        description = "Enable advanced diagnostics and connectivity",
                        isEnabled = uiState.isDeveloperModeEnabled,
                        onToggle = onToggleDeveloperMode
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                    SettingsToggleRow(
                        label = "Dark Mode",
                        description = "Switch between light and dark themes",
                        isEnabled = uiState.isDarkMode,
                        onToggle = onToggleDarkMode
                    )
                }
            }
        }

        item {
            Text("HEALTHCARE PROVIDER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(AccentBlue.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.LocalHospital, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(uiState.organizationName, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Dr. Sarah Smith • Orthopedic Dept.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { /* Logout or similar */ },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Log Out", color = TextSecondary, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color.Red)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear All Local Data", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun GoalEditRow(label: String, value: String, color: Color, onEditClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onEditClick) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = color.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun BottomNavBar(uiState: DashboardUiState, onNavigate: (Screen) -> Unit) {
    val currentScreen = uiState.currentScreen
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
        if (uiState.isDeveloperModeEnabled) {
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
        }
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
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            LiveServerFeedSection(listOf("[14:20:01] Connected to server.", "[14:20:05] Received data: 45kg pressure"))
        }
    }
}

@Composable
fun DirectConnectScreen(
    uiState: DashboardUiState,
    onPicoRelayToggle: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onLabelChange: (String, String, String) -> Unit,
    onMetadataChange: (String, String, String, String, String) -> Unit,
    onShareClick: () -> Unit
) {
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
                "PICO RELAY CONNECTION",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "The app acts as a Peripheral for the Nano Central.",
                color = TextSecondary,
                fontSize = 14.sp
            )
            Text(
                "1. Start Pico Relay Mode. 2. Nano will find and connect to this phone.",
                color = AccentOrange.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Status Card
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
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
                            color = if (uiState.isBleConnected) AccentGreen else if (uiState.bleStatus.contains("Advertising")) AccentOrange else TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        if (uiState.isBleConnected) Icons.Default.BluetoothConnected else if (uiState.bleStatus.contains("Advertising")) Icons.Default.BroadcastOnHome else Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        tint = if (uiState.isBleConnected) AccentGreen else if (uiState.bleStatus.contains("Advertising")) AccentOrange else AccentBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Database Export Section (Always accessible)
        if (uiState.patientId.isNotBlank() && !uiState.isRecording) {
            item {
                Button(
                    onClick = onShareClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Default.Email, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Patient History (DB)", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Pico Relay Toggle
        item {
            val isBroadcasting = uiState.bleStatus.contains("Advertising") || uiState.isBleConnected
            Button(
                onClick = onPicoRelayToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBroadcasting) Color.Red.copy(alpha = 0.6f) else AccentBlue
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(if (isBroadcasting) Icons.Default.Stop else Icons.Default.SettingsInputAntenna, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(if (isBroadcasting) "Stop Pico Relay Mode" else "Start Pico Relay Mode")
            }
        }

        // Metadata Section
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("PATIENT METADATA", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    if (!uiState.isBleConnected) {
                        Text("Not Connected (Simulated UI)", color = Color.Red.copy(alpha = 0.5f), fontSize = 10.sp)
                    }
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
                color = MaterialTheme.colorScheme.surface,
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
                    color = if (uiState.isRecording) Color.Red.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
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
            }
        }

        // Real-time Data Card
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
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
                        DataRow("Mag", String.format(java.util.Locale.US, "X: %d, Y: %d, Z: %d", (data["mx"] as? Number)?.toInt() ?: 0, (data["my"] as? Number)?.toInt() ?: 0, (data["mz"] as? Number)?.toInt() ?: 0))
                    } else {
                        Text("Waiting for Pico Relay data...", color = TextSecondary, fontSize = 14.sp)
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
fun MultiAxisChart(history: List<Map<String, Float>>, keys: List<String>, colors: List<Color>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas
        
        val width = size.width
        val height = size.height
        val spacing = width / 99f // Show last 100 pts
        
        keys.forEachIndexed { i, key ->
            val path = Path()
            history.forEachIndexed { index, map ->
                val value = map[key] ?: 0f
                // Normalize roughly (heuristic)
                val normValue = (value + 2f) / 4f // map -2..2 to 0..1
                val x = index * spacing
                val y = height - (normValue.coerceIn(0f, 1f) * height)
                
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[i], style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
fun DeviceItem(device: android.bluetooth.BluetoothDevice, onClick: (android.bluetooth.BluetoothDevice) -> Unit) {
    Surface(
        onClick = { onClick(device) },
        color = SurfaceNavy,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                val name = try { device.name ?: "Unknown Device" } catch (e: SecurityException) { "Unknown Device" }
                Text(name, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(device.address, color = TextSecondary, fontSize = 12.sp)
            }
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
            onValueChange = {},
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
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
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

@Composable
fun VideoTab(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = if (isSelected) AccentBlue.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, AccentBlue) else null,
        modifier = modifier
    ) {
        Text(
            text = label,
            color = if (isSelected) AccentBlue else TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PrescriptionPopup(
    uiState: DashboardUiState,
    onSubmit: (Int, String, Boolean) -> Unit,
    onDismiss: (Boolean) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var crutchCount by remember { mutableIntStateOf(2) }
    var selectedGait by remember { mutableStateOf("") }
    var doNotShowAgain by remember { mutableStateOf(false) }
    var activeVideoTab by remember { mutableStateOf("walking") }

    val gaitOptions = listOf(
        "Swing" to "Non-weight bearing",
        "2-point" to "Both legs partial weight bearing",
        "3-point" to "Partial weight bearing on one leg",
        "4-point" to "Balance issues"
    )

    AlertDialog(
        onDismissRequest = { onDismiss(false) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxSize().padding(16.dp),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 8.dp,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (step == 1) {
                    Text("How many crutches?", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CrutchCountCard("1 Crutch", crutchCount == 1, { crutchCount = 1 }, Modifier.weight(1f))
                        CrutchCountCard("2 Crutches", crutchCount == 2, { crutchCount = 2 }, Modifier.weight(1f))
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    if (crutchCount == 2) {
                        Text("Which gait were you prescribed?", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        gaitOptions.forEach { (gait, description) ->
                            Surface(
                                onClick = { selectedGait = gait },
                                color = if (selectedGait == gait) AccentBlue.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(12.dp),
                                border = if (selectedGait == gait) androidx.compose.foundation.BorderStroke(2.dp, AccentBlue) else null,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(description, color = TextPrimary, fontWeight = FontWeight.Bold)
                                    Text(gait, color = TextSecondary, fontSize = 14.sp, fontStyle = FontStyle.Italic)
                                }
                            }
                        }
                    }
                } else {
                    // Step 2: Tabbed Video Player
                    Text("Recovery Guide", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (crutchCount == 1) "1 Crutch Technique" else "$selectedGait Gait",
                        color = AccentGreen, fontSize = 14.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoTab("WALKING", activeVideoTab == "walking", { activeVideoTab = "walking" }, Modifier.weight(1f))
                        VideoTab("STAIRS UP", activeVideoTab == "stairs_up", { activeVideoTab = "stairs_up" }, Modifier.weight(1f))
                        VideoTab("STAIRS DOWN", activeVideoTab == "stairs_down", { activeVideoTab = "stairs_down" }, Modifier.weight(1f))
                    }
                    
                    YouTubeSection(
                        videoId = when {
                            crutchCount == 1 -> when(activeVideoTab) {
                                "stairs_up" -> "G09Zg3Wl2iE"
                                "stairs_down" -> "XG1z-vA_Y6I"
                                else -> "I_X1FkYvXyI"
                            }
                            selectedGait == "Swing" -> when(activeVideoTab) {
                                "stairs_up" -> "G09Zg3Wl2iE"
                                "stairs_down" -> "XG1z-vA_Y6I"
                                else -> "vD7MvN8N4m4"
                            }
                            selectedGait == "3-point" -> when(activeVideoTab) {
                                "stairs_up" -> "G09Zg3Wl2iE"
                                "stairs_down" -> "XG1z-vA_Y6I"
                                else -> "I_X1FkYvXyI"
                            }
                            else -> "I_X1FkYvXyI"
                        },
                        title = activeVideoTab.replace("_", " ").uppercase()
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = doNotShowAgain,
                            onCheckedChange = { doNotShowAgain = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentBlue)
                        )
                        Text("Do not show this guide again", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (step == 1) {
                        if (crutchCount == 1 || selectedGait.isNotBlank()) step = 2
                    } else {
                        onSubmit(crutchCount, if (crutchCount == 1) "1-Crutch" else selectedGait, doNotShowAgain)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (step == 1) "Next" else "Let's Go!", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (step == 2) {
                TextButton(onClick = { step = 1 }) {
                    Text("Back", color = TextSecondary)
                }
            }
        }
    )
}

@Composable
fun CrutchCountCard(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = if (isSelected) AccentBlue.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, AccentBlue) else null,
        modifier = modifier.height(100.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                if (label.contains("1")) Icons.Default.AccessibilityNew else Icons.AutoMirrored.Filled.DirectionsWalk,
                contentDescription = null,
                tint = if (isSelected) AccentBlue else TextSecondary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, color = if (isSelected) TextPrimary else TextSecondary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun GaitMismatchPopup(uiState: DashboardUiState, onDismiss: () -> Unit) {
    val gait = uiState.selectedGait
    val videoId = when {
        uiState.selectedCrutchCount == 1 -> "I_X1FkYvXyI"
        gait.contains("Swing", ignoreCase = true) -> "vD7MvN8N4m4"
        gait.contains("3-point", ignoreCase = true) -> "I_X1FkYvXyI"
        else -> "I_X1FkYvXyI" // Fallback
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Gait Mismatch!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "You are currently performing a different gait than prescribed. Please refresh the gait you should be doing:",
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Prescribed: $gait",
                    color = AccentBlue,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                YouTubeSection(videoId = videoId, title = "Instructional Video")
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("GOT IT", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}

@Composable
fun SyncMessagePopup(uiState: DashboardUiState, onDismiss: () -> Unit) {
    val gait = uiState.selectedGait
    val videoId = when {
        uiState.selectedCrutchCount == 1 -> "I_X1FkYvXyI"
        gait.contains("Swing", ignoreCase = true) -> "vD7MvN8N4m4"
        gait.contains("3-point", ignoreCase = true) -> "I_X1FkYvXyI"
        else -> "I_X1FkYvXyI" // Fallback
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Clinician Message", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    uiState.syncMessage,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Video Guide for: $gait",
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(12.dp))
                YouTubeSection(videoId = videoId, title = "GAIT INSTRUCTION")
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text("DISMISS", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceNavy
    )
}

@Composable
fun YouTubeSection(videoId: String, title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp)),
            color = Color.Black
        ) {
            var isLoading by remember { mutableStateOf(true) }
            Box(contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webChromeClient = android.webkit.WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                }
                            }
                            
                            // Force Cookie Acceptance
                            val cookieManager = android.webkit.CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                javaScriptCanOpenWindowsAutomatically = true
                                // Using a Desktop User Agent often bypasses "Video unavailable" in WebViews
                                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            }
                            
                            // Directly load the embed URL - this is the most reliable way on Android
                            loadUrl("https://www.youtube.com/embed/${videoId.trim()}?autoplay=1&modestbranding=1&rel=0&hl=en")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (isLoading) {
                    CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    SmartCrutchTheme {
        MainAppContainer(viewModel())
    }
}
