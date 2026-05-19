package com.example.smartcrutch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
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
                Screen.Progress -> ProgressScreen()
                Screen.Sync -> SyncScreen(uiState, onSyncClick = { viewModel.syncData() })
                Screen.Profile -> ProfileScreen()
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
        item { HeaderSection() }
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
        item { AchievementsSection() }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun HeaderSection() {
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
                        Text(String.format("%,d", uiState.steps), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
            MetricCard(
                title = "Wrist Protection",
                modifier = Modifier.weight(1f),
                accentColor = AccentOrange,
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Wrist strain index", color = TextSecondary, fontSize = 10.sp)
                            val strainText = if (uiState.wristStrain < 0.5f) "LOW" else "HIGH"
                            val strainColor = if (uiState.wristStrain < 0.5f) AccentGreen else Color.Red
                            Text(strainText, color = strainColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(SurfaceNavy).clip(RoundedCornerShape(3.dp))) {
                            Box(modifier = Modifier.fillMaxWidth(uiState.wristStrain).fillMaxHeight().background(AccentGreen))
                        }
                    }
                },
                footer = "Reducing well today"
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
fun SyncScreen(uiState: DashboardUiState, onSyncClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Sync,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Data Synchronization", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Sync your crutch data with the server to keep your progress up to date.",
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(40.dp))
        
        Surface(
            color = SurfaceNavy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SyncInfoRow("Status", uiState.syncStatus, if (uiState.syncStatus == "Connected") AccentGreen else TextSecondary)
                Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                SyncInfoRow("Last Synced", uiState.lastSyncTime, TextPrimary)
            }
        }
        
        Spacer(modifier = Modifier.height(40.dp))
        
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
fun ProgressScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.BarChart, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(100.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Detailed Progress", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Detailed charts and trends will appear here.", color = TextSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
fun ProfileScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(100.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("User Profile", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Manage your recovery goals and personal info.", color = TextSecondary, textAlign = TextAlign.Center)
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
fun DashboardPreview() {
    SmartCrutchTheme {
        MainAppContainer()
    }
}
