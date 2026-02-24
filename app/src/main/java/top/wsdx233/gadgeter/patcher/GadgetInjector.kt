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

    fun injectLoadLibrary(smaliFile: File, fallbackLevel: Int = 1, libName: String = "frida-gadget"): Boolean {
        if (!smaliFile.exists()) return false
        val lines = smaliFile.readLines().toMutableList()

        // 统一使用 <clinit> 静态代码块进行注入，这是最安全的，绝不会引发 Context 错乱
        val clinitIdx = lines.indexOfFirst { it.startsWith(".method") && it.contains("constructor <clinit>()V") }

        if (clinitIdx == -1) {
            // 类中没有静态代码块，直接在文件末尾追加一个
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

        // 已经存在 <clinit>，在开头无损插入
        val regIdx = lines.subList(clinitIdx, lines.size).indexOfFirst {
            it.trimStart().startsWith(".registers") || it.trimStart().startsWith(".locals")
        } + clinitIdx

        if (regIdx >= clinitIdx) {
            val regLine = lines[regIdx].trim()
            val isLocals = regLine.startsWith(".locals")
            val currentRegs = regLine.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

            // 寄存器数量 +1，使用新增出来的最高位寄存器，绝不污染原有变量
            val newRegs = currentRegs + 1
            val targetReg = "v$currentRegs"

            lines[regIdx] = if (isLocals) "    .locals $newRegs" else "    .registers $newRegs"

            val insertIdx = findFirstInstruction(lines, clinitIdx)
            lines.add(insertIdx, "")
            lines.add(insertIdx, "    invoke-static {$targetReg}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V")
            lines.add(insertIdx, "    const-string $targetReg, \"$libName\"")

            smaliFile.writeText(lines.joinToString("\n"))
            return true
        }

        return false
    }

    private fun findFirstInstruction(lines: List<String>, methodStart: Int): Int {
        val skipPrefixes = listOf(".registers", ".locals", ".param", ".prologue", ".line", ".local", ".annotation")
        for (i in (methodStart + 1) until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (skipPrefixes.none { trimmed.startsWith(it) }) return i
        }
        return methodStart + 1
    }
}
