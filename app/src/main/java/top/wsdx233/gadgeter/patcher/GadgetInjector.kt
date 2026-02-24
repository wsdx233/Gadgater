package top.wsdx233.gadgeter.patcher

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File

object GadgetInjector {

    fun disassembleDex(dexFile: File, outputDir: File) {
        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        val options = BaksmaliOptions()
        Baksmali.disassembleDexFile(dex, outputDir, 4, options)
    }

    fun reassembleDex(smaliDir: File, outputDex: File) {
        val options = SmaliOptions()
        options.outputDexFile = outputDex.absolutePath
        Smali.assemble(options, listOf(smaliDir.absolutePath))
    }

    fun injectLoadLibrary(smaliFile: File, libName: String = "frida-gadget"): Boolean {
        if (!smaliFile.exists()) return false
        val lines = smaliFile.readLines().toMutableList()

        // 1. 查找是否存在 <clinit> 静态代码块
        var clinitStartIdx = -1
        var clinitEndIdx = -1
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith(".method") && line.contains("static") && line.contains("constructor <clinit>()V")) {
                clinitStartIdx = i
            }
            if (clinitStartIdx != -1 && line.startsWith(".end method")) {
                clinitEndIdx = i
                break
            }
        }

        // 2. 如果不存在 <clinit>，最完美的状况！直接在文件末尾无损追加一个。
        if (clinitStartIdx == -1) {
            lines.add("")
            lines.add(".method static constructor <clinit>()V")
            lines.add("    .locals 1")
            lines.add("    const-string v0, \"$libName\"")
            lines.add("    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V")
            lines.add("    return-void")
            lines.add(".end method")
            smaliFile.writeText(lines.joinToString("\n"))
            return true
        }

        // 3. 如果存在 <clinit>，我们需要在开头无损插入
        var regIdx = -1
        var isLocals = false
        var currentRegs = 0

        // 寻找寄存器声明
        for (i in clinitStartIdx until clinitEndIdx) {
            val line = lines[i].trim()
            if (line.startsWith(".locals ")) {
                regIdx = i
                isLocals = true
                currentRegs = line.substringAfter(".locals").trim().toIntOrNull() ?: 0
                break
            } else if (line.startsWith(".registers ")) {
                regIdx = i
                isLocals = false
                currentRegs = line.substringAfter(".registers").trim().toIntOrNull() ?: 0
                break
            }
        }

        if (regIdx == -1) {
            // 极端罕见情况：有 <clinit> 但没有声明寄存器，主动加上
            regIdx = clinitStartIdx
            lines.add(regIdx + 1, "    .locals 1")
            currentRegs = 0
            isLocals = true
            regIdx++
            clinitEndIdx++
        }

        // 4. 安全策略：将寄存器数量 +1，使用新增出来的最高位寄存器！绝不污染原有的 v0, v1
        val newRegs = currentRegs + 1
        val targetReg = "v$currentRegs" // 例如原来是2 (v0, v1)，+1后变成3，我们就用新增的 v2

        // 更新寄存器数量
        lines[regIdx] = if (isLocals) "    .locals $newRegs" else "    .registers $newRegs"

        // 5. 寻找安全的插入点 (跳过 .param, .prologue, .line 等前置伪指令)
        var insertIdx = regIdx + 1
        for (i in (regIdx + 1) until clinitEndIdx) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(".param") || line.startsWith(".prologue") || line.startsWith(".line") || line.startsWith(".annotation")) {
                insertIdx = i + 1
            } else {
                break
            }
        }

        // 6. 插入注入代码
        lines.add(insertIdx, "    invoke-static {$targetReg}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V")
        lines.add(insertIdx, "    const-string $targetReg, \"$libName\"")

        smaliFile.writeText(lines.joinToString("\n"))
        return true
    }
}
