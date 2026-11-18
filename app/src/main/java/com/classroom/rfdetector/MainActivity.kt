package com.classroom.rfdetector

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.classroom.rfdetector.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15부터는 엣지투엣지가 강제된다. Android 10부터 같은 방식으로 그리도록 먼저 켠다.
        // 창 배경은 Surface가 칠하고, 내용은 safeDrawing 인셋만큼 안쪽에 둔다.
        enableEdgeToEdge()
        setContent { AppRfTheme { App() } }
    }
}

/** 화면은 넷이다: 감독관 화면, 금속 탐지, 한 전파의 위치 찾기, 안내. 금속 탐지는 전파 감시와 별개다. */
@Composable
fun App() {
    val context = LocalContext.current
    val vm = remember(context) { ViewModelProvider(context as ComponentActivity, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext) as T
    })[AppViewModel::class.java] }
    val lifecycle = (context as ComponentActivity).lifecycle
    var guide by rememberSaveable { mutableStateOf(false) }
    var metal by rememberSaveable { mutableStateOf(false) }
    var findKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    val needed = remember { vm.requiredPermissions() }
    fun hasPermissions() = needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var allGranted by remember { mutableStateOf(hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allGranted = hasPermissions()
        if (pendingStart && allGranted) vm.startScan()
        pendingStart = false
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { allGranted = hasPermissions(); vm.checkExamMode() }
            if (event == Lifecycle.Event.ON_START) { vm.resumeFromBackground(); vm.resumeMagnetic() }
            if (event == Lifecycle.Event.ON_STOP) { vm.pauseForBackground(); vm.pauseMagnetic() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // 알림창을 내려 비행기 모드를 바꾸면 화면이 다시 시작되지 않으므로, 창이 다시 앞에 올 때도 확인한다.
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { vm.checkExamMode() }
    val scanning by vm.scanning.collectAsState()
    val magneticOn by vm.magneticActive.collectAsState()
    val simMode by vm.simMode.collectAsState()
    DisposableEffect(scanning, magneticOn) {
        val window = (context as? Activity)?.window
        val keepAwake = scanning || magneticOn
        if (keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val openSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }
    val start = {
        if (simMode || hasPermissions()) vm.startScan()
        else { pendingStart = true; launcher.launch(needed.toTypedArray()) }
    }
    val closeFind = { vm.findTarget.value = null; vm.setGeiger(false); findKey = null }
    // 금속 탐지 화면을 닫으면 센서도 함께 뗀다. 켜 둔 채 감독관 화면으로 돌아갈 수는 없다.
    val closeMetal = { vm.stopMagnetic(); metal = false }
    BackHandler(enabled = guide || metal || findKey != null) {
        when {
            findKey != null -> closeFind()
            metal -> closeMetal()
            else -> guide = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = RfColors.bg) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val sub = guide || metal || findKey != null
            val title = if (findKey != null) "위치 찾기" else if (guide) "안내" else if (metal) "금속 탐지" else "KoriyoSCAN"
            val subtitle = when {
                findKey != null -> "세기로 다가가며 찾기"
                guide -> "이 앱이 보는 것과 못 보는 것"
                metal -> "자성 물체를 찾는 근접 탐지"
                else -> "교실 전파 감시"
            }
            Row(
                Modifier.fillMaxWidth().padding(start = if (sub) 8.dp else RfSpace.xl, end = RfSpace.s, top = RfSpace.s, bottom = RfSpace.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sub) TextButton(
                    onClick = { if (findKey != null) closeFind() else if (metal) closeMetal() else guide = false },
                    modifier = Modifier.semantics { contentDescription = "돌아가기" },
                    contentPadding = PaddingValues(horizontal = RfSpace.s),
                ) { Text("‹", style = MaterialTheme.typography.headlineSmall, color = RfColors.textPrimary) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = RfColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!sub) {
                    TextButton(onClick = { metal = true }, contentPadding = PaddingValues(horizontal = RfSpace.s)) {
                        Text("금속 탐지", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { guide = true }, contentPadding = PaddingValues(horizontal = RfSpace.s)) {
                        Text("안내", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // 머리말과 본문을 색이 아니라 1dp 선으로 나눈다.
            Box(Modifier.fillMaxWidth().height(1.dp).background(RfColors.line))
            Box(Modifier.weight(1f)) {
                val key = findKey
                when {
                    key != null -> FindScreen(vm, key, onBack = { findKey = null })
                    guide -> GuideScreen()
                    metal -> MagneticScreen(vm)
                    else -> GuardScreen(vm, !allGranted, start, { launcher.launch(needed.toTypedArray()) }, openSettings, onFind = { findKey = it })
                }
            }
        }
    }
}
