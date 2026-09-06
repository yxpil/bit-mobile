// yxpil · BIT Mobile
// 扫码配对：CameraX 预览 + ML Kit 条码识别（BIT1: 密文 → BIT-Crypt 解密 → payload 校验
// → 候选连接）。辅以手动输入连接码（截图外泄场景 / 模拟器无摄像头时同样可用）。
package com.example.bitmodel.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.bitmodel.bit.Attempt
import com.example.bitmodel.bit.BitClient
import com.example.bitmodel.bit.BitCrypt
import com.example.bitmodel.bit.BitStore
import com.example.bitmodel.bit.QrPayload
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

/// 扫码结果状态机：idle → detected(解密/连接中) → paired / failed
sealed interface ScanState {
    data object Idle : ScanState
    data object Connecting : ScanState
    data class Paired(val payload: QrPayload) : ScanState
    data class Failed(val message: String, val attempts: List<Attempt>) : ScanState
}

@Composable
fun ScanScreen(
    store: BitStore,
    onPaired: () -> Unit,
    goChat: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var state by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    var manual by remember { mutableStateOf("") }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    fun handleScanned(raw: String) {
        if (state is ScanState.Connecting) return
        scope.launch {
            state = ScanState.Connecting
            val plain = BitCrypt.decrypt(raw)
            if (plain == null) {
                state = ScanState.Failed("这不是 BIT 连接码（解密失败）。请在桌面端 远程访问 页重新出示二维码。", emptyList())
                return@launch
            }
            val payload = try {
                QrPayload.parse(plain)
            } catch (e: IllegalArgumentException) {
                state = ScanState.Failed(e.message ?: "连接码解析失败", emptyList())
                return@launch
            }
            // 持久化凭据后按 methods 优先级连接
            store.payloadJson = plain
            val client = BitClient()
            state = try {
                client.connect(payload)
                ScanState.Paired(payload)
            } catch (e: BitClient.ConnectException) {
                ScanState.Failed("扫描成功，但无法与桌面端建立连接：", e.attempts)
            } catch (e: Exception) {
                ScanState.Failed("连接失败：${e.message}", emptyList())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text("扫码配对", style = MaterialTheme.typography.titleLarge)
        Text(
            "在桌面端 BIT 的 远程访问 页出示连接二维码，用本机扫描即可完成配对。" +
                "连接码为加密密文（BIT1:），仅本应用可解。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 取景器面板：纯白底 + 黑色取景框（无透明组件，深浅主题下均可可靠识别）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color.White, RoundedCornerShape(24.dp))
                .border(2.dp, Color.Black, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !hasPermission -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "需要相机权限才能扫码",
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                    PillButton("授权相机") { permissionLauncher.launch(Manifest.permission.CAMERA) }
                }
                state is ScanState.Connecting -> Text("正在连接…", color = Color.Black)
                else -> CameraScanView(
                    active = true,
                    onBarcode = ::handleScanned,
                    onError = { cameraError = it },
                )
            }
        }

        cameraError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        when (val s = state) {
            is ScanState.Failed -> {
                BitCard {
                    Text("连接失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    s.attempts.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(ok = a.ok)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "${a.method} ${a.url}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "    ${a.detail}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillOutline("重新扫描") { state = ScanState.Idle }
                        // 配对凭据已存：离开扫码页手动重试网络即可
                        PillButton("前往对话") { goChat() }
                    }
                }
            }
            is ScanState.Paired -> {
                BitCard {
                    Text("配对成功", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "桌面端可直达候选 ${s.payload.directCandidates.size} 个，会话策略 ${s.payload.sidPolicy}。" +
                            "对话内容 ${if (s.payload.retention == "device-only") "仅存本机与桌面端，中继不留存" else "留存策略：" + s.payload.retention}。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PillButton("开始对话") { onPaired() }
                }
            }
            else -> {}
        }

        // 手动输入连接码（模拟器 / 无摄像头 / 从截图提取）
        BitCard {
            Text("手动输入连接码", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("粘贴 BIT1: 开头的连接码") },
                shape = RoundedCornerShape(14.dp),
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                PillButton("连接", enabled = manual.isNotBlank()) { handleScanned(manual.trim()) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/// CameraX + ML Kit 取景器：任一帧检出二维码即回调。active 翻转时重挂载
/// （重新扫描场景下保证回调可再次触发）。
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraScanView(active: Boolean, onBarcode: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    var scanned by remember(active) { mutableStateOf(false) }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        if (lifecycleOwner == null) {
            onError("相机上下文不可用")
            return@DisposableEffect onDispose { }
        }
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            try {
                provider = providerFuture.get()
                val preview = CameraPreview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy: ImageProxy ->
                    if (!scanned) {
                        val media = proxy.image
                        if (media != null) {
                            val input = InputImage.fromMediaImage(
                                media,
                                proxy.imageInfo.rotationDegrees,
                            )
                            scanner.process(input)
                                .addOnSuccessListener { codes ->
                                    val raw = codes.firstOrNull()?.rawValue
                                    if (raw != null && !scanned) {
                                        scanned = true
                                        onBarcode(raw)
                                    }
                                }
                                .addOnFailureListener { onError(it.message ?: "识别失败") }
                        }
                    }
                    proxy.close()
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                onError("相机不可用：${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
            }
            scanner.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
