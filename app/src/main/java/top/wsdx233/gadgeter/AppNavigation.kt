package top.wsdx233.gadgeter

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import top.wsdx233.gadgeter.ui.HomeScreen
import top.wsdx233.gadgeter.ui.ProcessingScreen
import top.wsdx233.gadgeter.ui.ResultScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    fun clearCacheDir() {
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                file.deleteRecursively()
            }
            Log.d("AppNavigation", "Cache directory cleared successfully")
        } catch (e: Exception) {
            Log.e("AppNavigation", "Failed to clear cache directory", e)
        }
    }
    
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStartProcessing = { apkPath ->
                    navController.navigate("processing?apkPath=${apkPath}")
                }
            )
        }
        composable("processing?apkPath={apkPath}") { backStackEntry ->
            val apkPath = backStackEntry.arguments?.getString("apkPath") ?: ""
            ProcessingScreen(
                apkPath = apkPath,
                onComplete = { resultApkPath ->
                    navController.navigate("result?apkPath=${resultApkPath}") {
                        popUpTo("home") { inclusive = false }
                    }
                },
                onError = { errorMsg ->
                    // Clear cache on error before going back to home
                    clearCacheDir()
                    navController.popBackStack()
                }
            )
        }
        composable("result?apkPath={apkPath}") { backStackEntry ->
            val apkPath = backStackEntry.arguments?.getString("apkPath") ?: ""
            ResultScreen(
                apkPath = apkPath,
                onBackHome = {
                    // Clear cache when going back to home from result
                    clearCacheDir()
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            )
        }
    }
}
