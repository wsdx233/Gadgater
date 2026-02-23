package top.wsdx233.gadgeter.ui

import android.util.Log

import android.graphics.drawable.Icon
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import top.wsdx233.gadgeter.R
import top.wsdx233.gadgeter.util.AppExtractor
import top.wsdx233.gadgeter.util.AppInfo
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onStartProcessing: (String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLoadingApps by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var showAppDialog by remember { mutableStateOf(false) }
    
    val defaultConfig = """
{
  "interaction": {
    "type": "listen",
    "port": 27042,
    "on_port_conflict": "fail",
    "on_load": "wait"
  }
}
    """.trimIndent()
    var gadgetConfig by remember { mutableStateOf(defaultConfig) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var fridaVersion by remember { mutableStateOf("17.7.3") }
    var fridaVersions by remember { mutableStateOf(emptyList<String>()) }
    var isFetchingVersions by remember { mutableStateOf(false) }
    var showVersionsDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    var isConfigVisible by remember { mutableStateOf(false) }
    var fridaLocalPath by remember { mutableStateOf("") }
    var customSoName by remember { mutableStateOf("libfrida-gadget.so") }
    
    // Architecture selection: 0 = auto-detect, 1 = manual
    var archMode by remember { mutableIntStateOf(0) }
    val allArchitectures = remember { listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    val selectedArchitectures = remember { mutableStateListOf<String>() }
    var appSearchQuery by remember { mutableStateOf("") }

    fun processWithConfig(apkPath: String) {
        coroutineScope.launch {
            File(context.cacheDir, "gadget_config.json").writeText(gadgetConfig)
            File(context.cacheDir, "gadget_version.txt").writeText(fridaVersion)
            File(context.cacheDir, "gadget_so_name.txt").writeText(customSoName.ifBlank { "libfrida-gadget.so" })
            File(context.cacheDir, "gadget_source_type.txt").writeText(selectedTab.toString())
            
            // Save architecture config
            val archFile = File(context.cacheDir, "gadget_arch_mode.txt")
            archFile.writeText(archMode.toString())
            val archListFile = File(context.cacheDir, "gadget_arch_list.txt")
            if (archMode == 1 && selectedArchitectures.isNotEmpty()) {
                archListFile.writeText(selectedArchitectures.joinToString(","))
            } else {
                if (archListFile.exists()) archListFile.delete()
            }
            
            val gFile = File(context.cacheDir, "gadget_local_path.txt")
            if (selectedTab == 1 && fridaLocalPath.isNotBlank()) {
                gFile.writeText(fridaLocalPath)
            } else {
                if (gFile.exists()) gFile.delete()
            }
            
            onStartProcessing(apkPath)
        }
    }
    
    fun fetchFridaVersions() {
        Log.d("HomeScreen", "fetchFridaVersions: called, current versions empty: ${fridaVersions.isEmpty()}")
        if (fridaVersions.isNotEmpty()) {
            showVersionsDialog = true
            return
        }
        isFetchingVersions = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/frida/frida/releases?per_page=1000")
                Log.d("HomeScreen", "fetchFridaVersions: fetching from ${url}")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "Gadgeter-App")
                val responseCode = conn.responseCode
                Log.d("HomeScreen", "fetchFridaVersions: response code: $responseCode")
                if (responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().readText()
                    Log.d("HomeScreen", "fetchFridaVersions: read ${text.length} chars")
                    val jsonArray = JSONArray(text)
                    val versions = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        val v = jsonArray.getJSONObject(i).getString("tag_name")
                        versions.add(v)
                    }
                    Log.d("HomeScreen", "fetchFridaVersions: found ${versions.size} versions")
                    withContext(Dispatchers.Main) {
                        fridaVersions = versions
                        isFetchingVersions = false
                        showVersionsDialog = true
                        Log.d("HomeScreen", "fetchFridaVersions: dialog should be showing now")
                    }
                } else {
                    Log.e("HomeScreen", "fetchFridaVersions: Failed with code $responseCode")
                    withContext(Dispatchers.Main) { isFetchingVersions = false }
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "fetchFridaVersions: error", e)
                withContext(Dispatchers.Main) { isFetchingVersions = false }
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                val cacheFile = File(context.cacheDir, "selected_app.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                processWithConfig(cacheFile.absolutePath)
            }
        }
    }

    val gadgetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                val cacheFile = File(context.cacheDir, "custom_gadget.so")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                fridaLocalPath = cacheFile.absolutePath
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.inject_gadget_title),
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.inject_gadget_desc),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            val tabs = listOf(
                stringResource(R.string.remote_download),
                stringResource(R.string.manual_specification),
                stringResource(R.string.built_in_app)
            )
            
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally(initialOffsetX = { it }) + fadeIn() togetherWith slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                    } else {
                        slideInHorizontally(initialOffsetX = { -it }) + fadeIn() togetherWith slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                    }
                }
            ) { tab ->
                Column {
                    when (tab) {
                        0 -> {
                            OutlinedTextField(
                                value = fridaVersion,
                                onValueChange = { fridaVersion = it },
                                label = { Text(stringResource(R.string.select_frida_version)) },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { fetchFridaVersions() }) {
                                        if (isFetchingVersions) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Fetch Versions")
                                        }
                                    }
                                },
                                singleLine = true
                            )
                        }
                        1 -> {
                            OutlinedTextField(
                                value = fridaLocalPath,
                                onValueChange = { fridaLocalPath = it },
                                label = { Text(stringResource(R.string.local_gadget_path)) },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { gadgetLauncher.launch("*/*") }) {
                                        Icon(Icons.Default.Search, contentDescription = "Select Local Gadget")
                                    }
                                },
                                singleLine = true
                            )
                        }
                        2 -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Text(
                                    stringResource(R.string.builtin_info),
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // Architecture selection (only for Remote Download and Manual Specification)
            AnimatedVisibility(
                visible = selectedTab != 2,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = stringResource(R.string.arch_selection_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = archMode == 0,
                            onClick = { archMode = 0 },
                            label = { Text(stringResource(R.string.arch_auto_detect)) },
                            leadingIcon = if (archMode == 0) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = archMode == 1,
                            onClick = { archMode = 1 },
                            label = { Text(stringResource(R.string.arch_manual_select)) },
                            leadingIcon = if (archMode == 1) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    AnimatedVisibility(
                        visible = archMode == 1,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                allArchitectures.forEach { arch ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (selectedArchitectures.contains(arch)) {
                                                    selectedArchitectures.remove(arch)
                                                } else {
                                                    selectedArchitectures.add(arch)
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedArchitectures.contains(arch),
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    if (!selectedArchitectures.contains(arch)) selectedArchitectures.add(arch)
                                                } else {
                                                    selectedArchitectures.remove(arch)
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = arch,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = customSoName,
                onValueChange = { customSoName = it },
                label = { Text(stringResource(R.string.custom_so_name)) },
                placeholder = { Text(stringResource(R.string.default_so_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = { isConfigVisible = !isConfigVisible },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isConfigVisible) stringResource(R.string.hide_config) else stringResource(R.string.show_config))
            }
            
            AnimatedVisibility(
                visible = isConfigVisible,
                enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) + expandVertically(),
                exit = fadeOut(spring(stiffness = Spring.StiffnessLow)) + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = gadgetConfig,
                        onValueChange = { gadgetConfig = it },
                        label = { Text(stringResource(R.string.config_json)) },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            ActionCard(
                title = stringResource(R.string.select_local_file),
                subtitle = stringResource(R.string.select_local_file_desc),
                icon = Icons.Default.AddCircle,
                onClick = { fileLauncher.launch("application/vnd.android.package-archive") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            ActionCard(
                title = stringResource(R.string.extract_from_device),
                subtitle = stringResource(R.string.extract_from_device_desc),
                icon = Icons.Default.List,
                onClick = {
                    isLoadingApps = true
                    showAppDialog = true
                    coroutineScope.launch {
                        installedApps = AppExtractor.getInstalledApps(context)
                        isLoadingApps = false
                    }
                }
            )
            
            // Padding at bottom to scroll completely
            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    if (showVersionsDialog) {
        Log.d("HomeScreen", "Drawing VersionsDialog: versions count=${fridaVersions.size}")
        val filteredVersions = fridaVersions.filter { it.contains(searchQuery, ignoreCase = true) }
        ModalBottomSheet(
            onDismissRequest = { showVersionsDialog = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxHeight(0.8f)) {
                Text(stringResource(R.string.select_frida_version), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search_versions)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn {
                    items(filteredVersions) { ver ->
                        ListItem(
                            headlineContent = { Text(ver) },
                            modifier = Modifier.clickable {
                                fridaVersion = ver
                                showVersionsDialog = false
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    }
                }
            }
        }
    }

    if (showAppDialog) {
        val filteredApps = installedApps.filter { 
            it.name.contains(appSearchQuery, ignoreCase = true) || 
            it.packageName.contains(appSearchQuery, ignoreCase = true) 
        }
        ModalBottomSheet(
            onDismissRequest = { showAppDialog = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxHeight(0.9f)) {
                Text(stringResource(R.string.select_installed_app), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    label = { Text(stringResource(R.string.search_apps)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (isLoadingApps) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredApps) { app ->
                            ListItem(
                                headlineContent = { Text(app.name) },
                                supportingContent = { Text(app.packageName) },
                                modifier = Modifier.clickable {
                                    showAppDialog = false
                                    processWithConfig(app.sourceDir)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}
