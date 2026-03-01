package top.wsdx233.gadgeter.patcher

import net.dongliu.apk.parser.ApkFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.FileInputStream

object ApkUtils {
    interface ProgressListener {
        fun onProgress(msg: String, fraction: Float)
    }

    fun unzip(zipFilePath: File, destDirectory: File, listener: ProgressListener) {
        if (!destDirectory.exists()) {
            destDirectory.mkdirs()
        }
        ZipFile(zipFilePath).use { zip ->
            val entries = zip.entries()
            var count = 0
            val total = zip.size()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryDestination = File(destDirectory, entry.name)
                if (entry.isDirectory) {
                    entryDestination.mkdirs()
                } else {
                    entryDestination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        entryDestination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                count++
                if (count % 100 == 0) {
                    listener.onProgress("Unpacking... $count / $total", count.toFloat() / total.toFloat())
                }
            }
            listener.onProgress("Unpacking complete", 1.0f)
        }
    }

    fun zip(sourceDirectory: File, zipFilePath: File, listener: ProgressListener) {
        val files = sourceDirectory.walkTopDown().filter { it.isFile }.toList()
        val total = files.size
        var count = 0
        ZipOutputStream(FileOutputStream(zipFilePath)).use { zipOut ->
            for (file in files) {
                // Ensure STORED for uncompressed files like .so, .png, resources.arsc maybe?
                // For simplicity we will just DEFLATE all, but V2 signature and zipalign might require certain things.
                // It's safer to just DEFLATE everything except some pre-compressed.
                val name = file.toRelativeString(sourceDirectory).replace("\\", "/")
                val entry = ZipEntry(name)
                // If it's a resources.arsc or already compressed media, ideally store.
                if (name == "resources.arsc" || name.endsWith(".png") || name.endsWith(".jpg")) {
                    entry.method = ZipEntry.STORED
                    entry.size = file.length()
                    entry.compressedSize = file.length()
                    val crc = java.util.zip.CRC32()
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192) // 8KB 的缓冲区
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            crc.update(buffer, 0, bytesRead) // 分块更新 CRC
                        }
                        entry.crc = crc.value
                    }
                }

                zipOut.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
                
                count++
                if (count % 100 == 0) {
                    listener.onProgress("Repacking... $count / $total", count.toFloat() / total.toFloat())
                }
            }
            listener.onProgress("Repacking complete", 1.0f)
        }
    }

    fun findAppConfig(apkPath: File): AppConfig {
        var applicationName: String? = null
        var mainActivityName: String? = null
        try {
            ApkFile(apkPath).use { apkFile ->
                val manifestXml = apkFile.manifestXml
                val appClassMatch = Regex("""<application[^>]*android:name="([^"]+)"""").find(manifestXml)
                applicationName = appClassMatch?.groupValues?.get(1)
                
                val activityMatch = Regex("""<activity[^>]*android:name="([^"]+)"""").find(manifestXml)
                mainActivityName = activityMatch?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return AppConfig(applicationName, mainActivityName)
    }

    data class AppConfig(val applicationName: String?, val mainActivityName: String?)
}
