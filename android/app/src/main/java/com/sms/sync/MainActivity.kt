package com.sms.sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sms.sync.network.WebSocketClient
import com.sms.sync.service.SyncForegroundService
import com.sms.sync.ui.QrScannerView
import com.sms.sync.ui.theme.SmssyncTheme
import com.sms.sync.util.XiaomiHelper

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "部分权限被拒绝，可能影响功能", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        requestBatteryOptimizationExemption()

        WebSocketClient.init(this)

        val prefs = getSharedPreferences("sms_sync", MODE_PRIVATE)
        val savedServer = prefs.getString("server_base", "") ?: ""
        val savedRoomId = prefs.getString("room_id", "") ?: ""
        val savedToken = prefs.getString("token", "") ?: ""
        if (savedServer.isNotBlank() && savedRoomId.isNotBlank() && savedToken.isNotBlank()) {
            WebSocketClient.connectWithParams(savedServer, savedRoomId, savedToken)
            SyncForegroundService.start(this)
        }

        setContent {
            SmssyncTheme {
                MainScreen()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.RECEIVE_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.READ_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGetImage::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sms_sync", android.content.Context.MODE_PRIVATE) }
    val isMiui = remember { XiaomiHelper.isMiui() }
    val connected by WebSocketClient.connected.collectAsState()

    val isPaired = remember { mutableStateOf(prefs.getString("token", "")?.isNotBlank() == true) }
    var pairStatus by remember { mutableStateOf("") }
    var pairingServer by remember { mutableStateOf("") }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Re-check camera permission when composable resumes
    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        WebSocketClient.onPairResult = { success, message ->
            if (success) {
                val parts = message.split("|", limit = 2)
                if (parts.size == 2) {
                    prefs.edit()
                        .putString("token", parts[0])
                        .putString("room_id", parts[1])
                        .putString("server_base", pairingServer)
                        .apply()
                    isPaired.value = true
                    pairStatus = ""
                    SyncForegroundService.start(context)
                }
            } else {
                pairStatus = "配对失败：$message"
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("SMS Sync", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Status Card ──
            StatusCard(isPaired = isPaired.value, connected = connected)

            if (!isPaired.value) {
                // ── Scan QR to Pair ──
                Text(
                    "扫描配对",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (hasCameraPermission) {
                            QrScannerView { server, pairCode ->
                                pairStatus = "配对中..."
                                pairingServer = server
                                WebSocketClient.pairWithCode(server, pairCode, Build.MODEL)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Outlined.CameraAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "需要相机权限才能扫码",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }

                        if (pairStatus.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                pairStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (pairStatus.contains("失败"))
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "打开电脑端 SMS Sync，扫描屏幕上的二维码即可配对",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // ── Paired: Disconnect Button ──
                FilledTonalButton(
                    onClick = {
                        WebSocketClient.disconnect()
                        SyncForegroundService.stop(context)
                        prefs.edit().clear().apply()
                        isPaired.value = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("取消配对")
                }
            }

            // ── Permissions Section ──
            Text(
                "权限设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column {
                    SettingsRow(
                        icon = Icons.Outlined.Notifications,
                        title = "通知监听权限",
                        subtitle = "接收其他应用的验证码通知",
                        onClick = { XiaomiHelper.openNotificationListenerSettings(context) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow(
                        icon = Icons.Outlined.BatteryAlert,
                        title = "关闭电池优化",
                        subtitle = "防止系统杀掉后台服务",
                        onClick = { XiaomiHelper.openBatterySettings(context) }
                    )
                    if (isMiui) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRow(
                            icon = Icons.Outlined.PhoneAndroid,
                            title = "自启动权限",
                            subtitle = "小米设备需要额外开启",
                            onClick = { XiaomiHelper.openAutoStartSettings(context) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(isPaired: Boolean, connected: Boolean) {
    val containerColor = when {
        !isPaired -> MaterialTheme.colorScheme.surfaceContainerLow
        connected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }

    val statusText = when {
        !isPaired -> "未配对"
        connected -> "已连接"
        else -> "连接断开"
    }

    val statusDesc = when {
        !isPaired -> "扫描电脑端二维码完成配对"
        connected -> "正在监听验证码，收到后自动同步到电脑"
        else -> "正在尝试重新连接..."
    }

    val statusIcon = when {
        !isPaired -> Icons.Outlined.QrCodeScanner
        connected -> Icons.Outlined.CheckCircle
        else -> Icons.Outlined.ErrorOutline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !isPaired -> MaterialTheme.colorScheme.surfaceContainerHighest
                            connected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    statusIcon,
                    contentDescription = null,
                    tint = when {
                        !isPaired -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onPrimary
                    },
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    statusDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
