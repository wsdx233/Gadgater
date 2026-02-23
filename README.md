<div align="center">
  <img src="preview/icon.png" alt="Gadgeter Icon" width="128" height="128" />
  <h1>Gadgeter</h1>
  <p>An on-device Android APK patching tool for automating Frida Gadget injection.</p>
</div>

<p align="center">
  <strong>English</strong> | <a href="README_CN.md">中文</a>
</p>

---

**Gadgeter** is a powerful Android application designed to automatically inject the **Frida Gadget** into any APK directly on your device. Whether you want to debug, reverse-engineer, or analyze an app without a rooted device, Gadgeter simplifies the entire process. Just select an APK, and Gadgeter will handle the decoding, injecting, repackaging, and signing—outputting a ready-to-run, debuggable masterpiece!

### 📱 Screenshots
![Screenshots](preview/screenshots.png)

### ✨ Features
- **On-Device Processing**: No PC required. Everything from unpacking to repackaging happens directly on your Android device.
- **App Selection**: Pick an already installed app or choose a local APK file via the Storage Access Framework (SAF).
- **Architecture Auto-Detection**: Automatically detects the target architecture (e.g., `arm64-v8a`, `armeabi-v7a`), with a manual selection override available.
- **Frida Gadget Management**: 
  - Download specific Frida versions remotely from GitHub.
  - Manually specify a local `.so` gadget file.
  - Use the built-in default gadget.
- **Custom Configuration**: Easily customize the `.so` name or provide a JSON configuration file for the Frida Gadget.
- **Automated Injection**: Analyzes and modifies the SMALI code to insert `System.loadLibrary("frida-gadget")` and adds the necessary `INTERNET` permissions.
- **Repackage & Sign**: Automatically repackages the injected components and signs the final APK using a built-in certificate.

### 🛠️ How it works
1. **Decode**: Extracts the APK structure.
2. **Inject**: Places `libfrida-gadget.so` into the right `/lib/<abi>/` folder.
3. **Patch SMALI**: Modifies the application's entry point to load the Frida library.
4. **Permissions**: Patches `AndroidManifest.xml` to ensure internet access.
5. **Re-build & Sign**: Re-assembles everything into a valid, aligned, and signed APK ready for installation.
