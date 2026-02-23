package top.wsdx233.gadgeter.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.wsdx233.gadgeter.R
import top.wsdx233.gadgeter.gadget.GadgetDownloader
import top.wsdx233.gadgeter.patcher.ApkSignerUtil
import top.wsdx233.gadgeter.patcher.ApkUtils
import top.wsdx233.gadgeter.patcher.GadgetInjector
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    apkPath: String,
    onComplete: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var progress by remember { mutableFloatStateOf(0f) }
    var currentTask by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val initTask = stringResource(R.string.initializing)
    val prepTask = stringResource(R.string.preparing_workspace)
    val extractTask = stringResource(R.string.extracting_apk)
    val analyzeTask = stringResource(R.string.analyzing_apk)
    val injectTask = stringResource(R.string.injecting_smali)
    val fridaTask = stringResource(R.string.preparing_frida_gadget)
    val repackTask = stringResource(R.string.repackaging_apk)
    val signTask = stringResource(R.string.signing_apk)
    val compTask = stringResource(R.string.complete)

    LaunchedEffect(Unit) {
        currentTask = initTask
    }

    fun log(msg: String) {
        coroutineScope.launch(Dispatchers.Main) {
            logs.add(msg)
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Initialize
                currentTask = prepTask
                progress = 0.1f
                val workDir = File(context.cacheDir, "work_${System.currentTimeMillis()}")
                workDir.mkdirs()
                val unzippedDir = File(workDir, "unzipped")
                val smaliDir = File(workDir, "smali_out")

                // Pre-read config texts
                val sourceTypeStr = File(context.cacheDir, "gadget_source_type.txt").let { if (it.exists()) it.readText() else "0" }
                val sourceType = sourceTypeStr.toIntOrNull() ?: 0 // 0=Remote, 1=Manual, 2=Built-in
                val soName = File(context.cacheDir, "gadget_so_name.txt").let { if (it.exists()) it.readText() else "libfrida-gadget.so" }.ifBlank { "libfrida-gadget.so" }
                val libNameWithoutExt = soName.removeSuffix(".so")

                // Step 2: Extract APK
                currentTask = extractTask
                progress = 0.2f
                log("Starting unzip of $apkPath")
                ApkUtils.unzip(File(apkPath), unzippedDir, object : ApkUtils.ProgressListener {
                    override fun onProgress(msg: String, fraction: Float) {
                        log(msg)
                        progress = 0.1f + fraction * 0.1f
                    }
                })

                // Step 3: Find App Configuration
                currentTask = analyzeTask
                progress = 0.3f
                log("Parsing AndroidManifest for App Class...")
                val config = ApkUtils.findAppConfig(File(apkPath))
                log("Found application class: ${config.applicationName}")
                val targetClass = config.applicationName ?: config.mainActivityName
                if (targetClass == null) {
                    throw Exception("Could not find Application or MainActivity class to inject.")
                }
                
                val dexFiles = unzippedDir.listFiles { _, name -> name.startsWith("classes") && name.endsWith(".dex") }
                    ?.sorted() ?: emptyList()
                    
                if (dexFiles.isEmpty()) throw Exception("No dex files found in APK!")
                
                var injSuccess = false
                currentTask = injectTask
                for (dexFile in dexFiles) {
                    log("Disassembling ${dexFile.name}...")
                    GadgetInjector.disassembleDex(dexFile, smaliDir)
                    
                    var injected = false
                    
                    val appSmaliPath = config.applicationName?.replace(".", "/")?.plus(".smali")
                    val appSmaliFile = appSmaliPath?.let { File(smaliDir, it) }
                    
                    val mainActSmaliPath = config.mainActivityName?.replace(".", "/")?.plus(".smali")
                    val mainActSmaliFile = mainActSmaliPath?.let { File(smaliDir, it) }

                    // Notice the 3rd param: libNameWithoutExt logic to use the exact name of the library (without '.so', without 'lib' prefix if it's default android loadLibrary format)
                    // System.loadLibrary("frida-gadget") looks for libfrida-gadget.so
                    val loadLibStr = if (soName.startsWith("lib") && soName.endsWith(".so")) soName.substring(3, soName.length - 3) else libNameWithoutExt

                    if (appSmaliFile != null && appSmaliFile.exists()) {
                        log("Found Application smali: $appSmaliPath. Injecting loadLibrary...")
                        injected = GadgetInjector.injectLoadLibrary(appSmaliFile, 1, loadLibStr)
                        if (!injected) {
                            log("Failed to inject into Application. Fallback to MainActivity...")
                        }
                    }

                    if (!injected && mainActSmaliFile != null && mainActSmaliFile.exists()) {
                        log("Found MainActivity smali: $mainActSmaliPath. Injecting loadLibrary...")
                        injected = GadgetInjector.injectLoadLibrary(mainActSmaliFile, 2, loadLibStr) || GadgetInjector.injectLoadLibrary(mainActSmaliFile, 3, loadLibStr)
                        if (!injected) {
                            log("Failed to inject into MainActivity.")
                        }
                    }
                    
                    if (injected) {
                        log("Injected successfully into ${dexFile.name}")
                        injSuccess = true
                        log("Reassembling dex ${dexFile.name}...")
                        val outDex = File(workDir, dexFile.name)
                        GadgetInjector.reassembleDex(smaliDir, outDex)
                        // Replace old dex
                        outDex.copyTo(dexFile, overwrite = true)
                        break
                    } else {
                        log("Target classes not found or injection failed in ${dexFile.name}.")
                    }
                    smaliDir.deleteRecursively()
                    smaliDir.mkdirs()
                }

                if (!injSuccess) {
                    throw Exception("Failed to inject into any dex file!")
                }

                progress = 0.6f

                // Step 4: Download and inject Frida Gadget
                currentTask = fridaTask
                var addedLib = false
                val libDir = File(unzippedDir, "lib")
                if (!libDir.exists()) libDir.mkdirs()

                val configFile = File(context.cacheDir, "gadget_config.json")
                val configContent = if (configFile.exists()) configFile.readText() else ""
                val vFile = File(context.cacheDir, "gadget_version.txt")
                val fridaVersion = if (vFile.exists()) vFile.readText() else "16.2.1"

                val architecturesPresent = libDir.listFiles()?.map { it.name } ?: emptyList()
                
                // Read architecture config
                val archModeFile = File(context.cacheDir, "gadget_arch_mode.txt")
                val archMode = if (archModeFile.exists()) archModeFile.readText().toIntOrNull() ?: 0 else 0
                val archListFile = File(context.cacheDir, "gadget_arch_list.txt")
                val manualArchs = if (archListFile.exists()) archListFile.readText().split(",").filter { it.isNotBlank() } else emptyList()
                
                // Built-in asset is arm (armeabi-v7a) only, so force that architecture
                val archsToFetch = if (sourceType == 2) {
                    listOf("armeabi-v7a")
                } else if (archMode == 1 && manualArchs.isNotEmpty()) {
                    // User manually selected architectures
                    log("Using manually selected architectures: ${manualArchs.joinToString(", ")}")
                    manualArchs
                } else if (architecturesPresent.isEmpty()) {
                    listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                } else {
                    architecturesPresent
                }

                val localGadgetFile = File(context.cacheDir, "gadget_local_path.txt")
                val localGadgetPath = if (localGadgetFile.exists()) localGadgetFile.readText() else null

                for (arch in archsToFetch) {
                    val archDir = File(libDir, arch)
                    archDir.mkdirs()
                    val targetLib = File(archDir, soName)
                    var archAdded = false
                    
                    if (sourceType == 2) {
                        // Built-in
                        // We only provide arm, so we might need to apply it specifically or just copy to all
                        // In reality Built-in app only has arm for testing depending on the instructions, but we can just provide it
                        log("Using built-in asset for $arch...")
                        try {
                            context.assets.open("frida-gadget-17.7.3-android-arm.so").use { input ->
                                FileOutputStream(targetLib).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            archAdded = true
                            log("Added built-in frida gadget to $arch")
                        } catch (e: Exception) {
                            log("Failed built-in for $arch: ${e.message}")
                        }
                    } else if (sourceType == 1 && localGadgetPath?.isNotBlank() == true && File(localGadgetPath).exists()) {
                        // Manual Specifications
                        log("Using custom local frida gadget for $arch...")
                        File(localGadgetPath).copyTo(targetLib, overwrite = true)
                        archAdded = true
                        log("Added custom local frida gadget to $arch")
                    } else if (sourceType == 0) {
                        // Remote
                        log("Downloading gadget for $arch")
                        val downloadedSo = GadgetDownloader.downloadGadget(context, arch, fridaVersion) { msg, fraction ->
                            log(msg)
                            progress = 0.6f + (fraction * 0.2f)
                        }
                        if (downloadedSo != null && downloadedSo.exists()) {
                            downloadedSo.copyTo(targetLib, overwrite = true)
                            archAdded = true
                            log("Added downloaded frida gadget to $arch")
                        } else {
                            log("Skipping gadget for $arch (not downloaded)")
                        }
                    }
                    
                    if (archAdded) {
                        addedLib = true
                        if (configContent.isNotEmpty()) {
                            // If `libfrida-gadget.so` is renamed, then the config needs to be `libNameWOExt.config.so` or `libName.config.so`. 
                            // Official documentation: For `libtarget.so`, the config is `libtarget.config.so`.
                            val configName = libNameWithoutExt + ".config.so"
                            File(archDir, configName).writeText(configContent)
                        }
                    }
                }

                if (!addedLib) {
                    throw Exception("Failed to add any frida gadgets (check source type permissions/availability).")
                }

                progress = 0.8f

                // Step 5: Repackage
                currentTask = repackTask
                log("Repacking APK...")
                val repackedZip = File(workDir, "repacked.apk")
                ApkUtils.zip(unzippedDir, repackedZip, object : ApkUtils.ProgressListener {
                    override fun onProgress(msg: String, fraction: Float) {
                        log(msg)
                        progress = 0.8f + (fraction * 0.1f)
                    }
                })

                // Step 6: Zipalign & Sign
                currentTask = signTask
                progress = 0.9f
                log("Signing APK using apksig...")
                val finalApk = File(context.cacheDir, "patched_gadgeter.apk")
                if (finalApk.exists()) finalApk.delete()
                
                ApkSignerUtil.signApk(repackedZip, finalApk)

                log("Done! Cleaning workspace...")
                workDir.deleteRecursively()

                progress = 1.0f
                currentTask = compTask
                
                withContext(Dispatchers.Main) {
                    onComplete(finalApk.absolutePath)
                }

            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorMessage = e.message ?: "Unknown Error"
                    currentTask = context.getString(R.string.error_occurred)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (errorMessage != null) stringResource(R.string.error_occurred)
                        else stringResource(R.string.processing),
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error State UI
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(tween(500)) + expandVertically(),
                exit = fadeOut(tween(300)) + shrinkVertically()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    // Error Icon
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF44336).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.error_occurred),
                            tint = Color(0xFFF44336),
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.processing_failed),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF44336)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Error message card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF44336).copy(alpha = 0.1f)
                        )
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Start
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Back to Home button
                    Button(
                        onClick = { onError(errorMessage ?: "Unknown Error") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.back_to_home),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Normal processing state UI
            AnimatedVisibility(
                visible = errorMessage == null,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(300))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Shimmer / light-sweep infinite transition
                    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
                    val shimmerOffset by shimmerTransition.animateFloat(
                        initialValue = -1f,
                        targetValue = 2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1800, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "shimmerOffset"
                    )

                    val primaryColor = MaterialTheme.colorScheme.primary
                    val highlightColor = MaterialTheme.colorScheme.inversePrimary

                    AnimatedContent(
                        targetState = currentTask,
                        transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) }
                    ) { task ->
                        val shimmerBrush = Brush.linearGradient(
                            colors = listOf(
                                primaryColor,
                                highlightColor,
                                Color.White,
                                highlightColor,
                                primaryColor
                            ),
                            start = Offset(shimmerOffset * 600f, 0f),
                            end = Offset(shimmerOffset * 600f + 400f, 0f)
                        )
                        Text(
                            text = task,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            style = LocalTextStyle.current.copy(
                                brush = shimmerBrush
                            ),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Log View (always visible)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logs) { logMsg ->
                        val isError = logMsg.startsWith("ERROR:")
                        Text(
                            text = "> $logMsg",
                            color = if (isError) Color(0xFFFF5252) else Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = if (isError) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
