# Unity symlinks
## MacOS
- `ln -sfn /Applications/Unity/Hub/Editor/2019.4.41f1 /Applications/Unity-Publish`
- `ln -sfn /Applications/Unity/Hub/Editor/6000.2.6f2 /Applications/Unity-Export`
- Or the **Create Symlinks** button in `build.py` (both links point to the selected editor)
## Linux
- TBD
## Windows
- `mklink /D "C:\Program Files\Unity-Publish" "C:\Program Files\Unity\Hub\Editor\2019.4.41f1"`
- `mklink /D "C:\Program Files\Unity-Export" "C:\Program Files\Unity\Hub\Editor\6000.2.6f2"`

# JDK
## MacOS
Not needed: `build.py` uses Unity's bundled `PlaybackEngines/AndroidPlayer/OpenJDK`
## Linux
TBD
## Windows
- `winget install Microsoft.OpenJDK.17`
- `setx JAVA_HOME "C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot" -m`

# Android SDK
## MacOS
- Not needed: `build.py` uses Unity's bundled `PlaybackEngines/AndroidPlayer/SDK`, **Setup Android SDK** accepts licenses and installs packages in place
## Linux
- TBD
## Windows
- `setx ANDROID_HOME "%USERPROFILE%\AppData\Local\Android\Sdk" -m`
- `setx PATH "%PATH%;%ANDROID_HOME%\platform-tools" -m`
- Note: if using the bundled Unity Android SDK, license acceptance and build-tools installation may be required. The `build.py` GUI script can handle this automatically.

# GUI Build Script (Windows / macOS)
On macOS don't use the system/Xcode `python3`: it ships deprecated Tk 8.5 and the window renders blank (only buttons visible). Use Homebrew Python in a venv:
```
brew install python-tk@3.13
python3.13 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python build.py
```
`invoke` is run as `python -m invoke`, so it doesn't need to be on `PATH`.

From the `Builder/` directory (after `pip install -r requirements.txt` if needed):

1. Run `python build.py`.
2. Click **Browse** and select the Unity Editor install folder. JDK and Android SDK paths are filled in automatically from that installation.
3. **Create symlinks** — confirm the dialog, then approve UAC. This creates `C:\Program Files\Unity-Export` and `C:\Program Files\Unity-Publish` pointing at the selected editor (required for the Rake/Invoke tooling).
4. If Gradle reports missing Android licenses or SDK packages (e.g. `build-tools`), click **Setup Android SDK** — confirm the dialog, then complete the elevated command prompt. Packages to install via `sdkmanager` are listed in `build_gui_config.json` (`android_sdk_packages`; default is only `build-tools;35.0.0`).
5. Set **Configuration** (Full/Free), **Build Type** (Release/Debug), and **Build Target** (Android / iOS / Both), then click **Build**.
6. **Export Unity Package** — uses the current **Configuration** (Full or Free) and runs `invoke export-unity-package-full` or `export-unity-package-free` (clean, native Android/iOS build, Unity export). Requires the same Unity/JDK/SDK selection as **Build**, because Gradle runs during export.

Handles:
- Unity Editor selection
- Symlink creation
- JDK / Android SDK auto-detection
- Android SDK setup (licenses + packages from `build_gui_config.json`)
- Building Android / iOS targets
- Exporting `.unitypackage` via Invoke (default output paths under `Builder/temp/packages/`)
- Persists paths and build options in `Builder/build_gui_settings.json` (gitignored)

# Builder
Python 3.13.5