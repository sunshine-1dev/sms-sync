package com.sms.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sms.sync.network.WebSocketClient
import com.sms.sync.service.SyncForegroundService
import com.sms.sync.ui.theme.SmssyncTheme
import com.sms.sync.util.XiaomiHelper

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "部分权限被拒绝，短信监听可能无法正常工作", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        setContent {
            SmssyncTheme {
                MainScreen()
            }
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sms_sync", android.content.Context.MODE_PRIVATE) }
    val isMiui = remember { XiaomiHelper.isMiui() }

    var serverAddress by remember { mutableStateOf(prefs.getString("server_address", "ws://192.168.1.100:18080/ws") ?: "") }
    val connected by WebSocketClient.connected.collectAsState()

    var showPairDialog by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var pairStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        WebSocketClient.onPairResult = { success, message ->
            if (success) {
                prefs.edit().putString("token", message).apply()
                pairStatus = "配对成功！"
                showPairDialog = false
            } else {
                pairStatus = "配对失败：$message"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("验证码同步") },
                actions = {
                    val statusColor = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    Text(
                        text = if (connected) "已连接" else "未连接",
                        color = statusColor,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = serverAddress,
                onValueChange = { serverAddress = it },
                label = { Text("服务器地址") },
                placeholder = { Text("ws://192.168.1.100:18080/ws") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !connected
            )

            if (!connected) {
                Button(
                    onClick = {
                        prefs.edit().putString("server_address", serverAddress).apply()
                        val token = prefs.getString("token", "") ?: ""
                        WebSocketClient.connect(serverAddress, token)
                        SyncForegroundService.start(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("连接")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        WebSocketClient.disconnect()
                        SyncForegroundService.stop(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("断开连接")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            OutlinedButton(
                onClick = { showPairDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("与电脑配对")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("权限设置", style = MaterialTheme.typography.titleSmall)

            OutlinedButton(
                onClick = { XiaomiHelper.openNotificationListenerSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开启通知监听权限")
            }

            OutlinedButton(
                onClick = { XiaomiHelper.openBatterySettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关闭电池优化")
            }

            if (isMiui) {
                OutlinedButton(
                    onClick = { XiaomiHelper.openAutoStartSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开启自启动（小米）")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text("与电脑配对") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请输入电脑端显示的 6 位配对码")
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { if (it.length <= 6) pairCode = it },
                        label = { Text("配对码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pairStatus.isNotBlank()) {
                        Text(pairStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pairCode.length == 6) {
                            pairStatus = "配对中..."
                            WebSocketClient.sendPairRequest(pairCode)
                        }
                    },
                    enabled = pairCode.length == 6
                ) {
                    Text("配对")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPairDialog = false
                    pairCode = ""
                    pairStatus = ""
                }) {
                    Text("取消")
                }
            }
        )
    }
}
