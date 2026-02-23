<div align="center">
  <img src="preview/icon.png" alt="Gadgeter Icon" width="128" height="128" />
  <h1>Gadgeter</h1>
  <p>一款运行在 Android 设备上的自动化 Frida Gadget 注入补丁工具。</p>
</div>

<p align="center">
  <a href="README.md">English</a> | <strong>中文</strong>
</p>

---

**Gadgeter** 是一款 Android 端应用，旨在直接在您的设备上自动将 **Frida Gadget** 注入到任何 APK 中。只需选择一个 APK，Gadgeter 即可完成解包、注入、重打包和重签名，输出一个可以直接安装使用的 Debug 版本！

### 📱 屏幕截图
![Screenshots](preview/screenshots.png)

### ✨ 功能特点
- **纯设备端处理**：全程脱离 PC！从解包到重打包所有的操作全在 Android 设备上本地完成。
- **灵活的应用选择**：可以直接提取已安装的应用，或通过文件选择器（SAF）选择本地的 APK 文件。
- **架构自动检测**：支持自动检测目标架构（如 `arm64-v8a`、`armeabi-v7a` 等），同时也提供手动指定架构的选项。
- **Frida Gadget 管理**：
  - 支持从 GitHub 远程下载并选择指定版本的 Frida。
  - 支持手动指定本地的 `.so` Gadget 文件。
  - 支持使用内置的默认 Gadget。
- **自定义配置**：轻松自定义 `.so` 库名称，或为 Frida Gadget 附加所需的 JSON 配置文件。
- **全自动注入**：自动分析并在 SMALI 层面插入 `System.loadLibrary("frida-gadget")` 调用，同时补全所需的 `INTERNET` 权限。
- **重打包与签名**：将注入后的文件重新打包，并使用内置证书自动完成最终 APK 的签名。

### 🛠️ 核心工作流
1. **解包提取**：解析原始 APK 并解压。
2. **植入依赖**：将 `libfrida-gadget.so` 精准放入对应的 `/lib/<abi>/` 目录。
3. **注入 SMALI**：在应用的执行入口处插入加载 Frida 动态库的代码。
4. **权限处理**：修改 `AndroidManifest.xml` 以确保应用具备网络访问权限。
5. **重打包签名**：整合文件，进行字节对齐与重签名，生成可直接安装的 APK 补丁包。
