import ctypes
import json
import os
import queue
import subprocess
import threading
import tempfile
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext

import tkinter as tk
from tkinter import ttk

SETTINGS_FILENAME = "build_gui_settings.json"
GUI_CONFIG_FILENAME = "build_gui_config.json"


class LunarConsoleBuilderApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("Lunar Console Builder")
        self.root.geometry("800x720")
        self.root.minsize(640, 480)

        self.builder_dir = Path(__file__).parent.resolve()
        self.repo_dir = self.builder_dir.parent

        self.log_queue: queue.Queue = queue.Queue()

        self._create_widgets()
        self._loading_settings = False
        self._load_settings()
        self._wire_auto_save()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close_window)
        self._poll_queue()

    def _create_widgets(self) -> None:
        padx = 10
        pady = 5

        # Unity Editor Path
        tk.Label(self.root, text="Unity Editor Path:").grid(
            row=0, column=0, sticky="w", padx=padx, pady=pady
        )
        self.unity_path_var = tk.StringVar()
        self.entry_unity = tk.Entry(self.root, textvariable=self.unity_path_var)
        self.entry_unity.grid(row=0, column=1, sticky="ew", padx=padx, pady=pady)
        tk.Button(self.root, text="Browse", command=self._browse_unity).grid(
            row=0, column=2, padx=padx, pady=pady
        )

        # JDK Path
        tk.Label(self.root, text="JDK Path:").grid(
            row=1, column=0, sticky="w", padx=padx, pady=pady
        )
        self.jdk_path_var = tk.StringVar()
        self.entry_jdk = tk.Entry(
            self.root, textvariable=self.jdk_path_var, state="disabled"
        )
        self.entry_jdk.grid(row=1, column=1, sticky="ew", padx=padx, pady=pady)

        # SDK Path
        tk.Label(self.root, text="Android SDK Path:").grid(
            row=2, column=0, sticky="w", padx=padx, pady=pady
        )
        self.sdk_path_var = tk.StringVar()
        self.entry_sdk = tk.Entry(
            self.root, textvariable=self.sdk_path_var, state="disabled"
        )
        self.entry_sdk.grid(row=2, column=1, sticky="ew", padx=padx, pady=pady)

        # Configuration
        tk.Label(self.root, text="Configuration:").grid(
            row=3, column=0, sticky="w", padx=padx, pady=pady
        )
        self.config_var = tk.StringVar(value="Full")
        ttk.Combobox(
            self.root, textvariable=self.config_var, values=["Full", "Free"], state="readonly"
        ).grid(row=3, column=1, sticky="ew", padx=padx, pady=pady)

        # Build Type
        tk.Label(self.root, text="Build Type:").grid(
            row=4, column=0, sticky="w", padx=padx, pady=pady
        )
        self.build_type_var = tk.StringVar(value="Release")
        ttk.Combobox(
            self.root, textvariable=self.build_type_var, values=["Release", "Debug"], state="readonly"
        ).grid(row=4, column=1, sticky="ew", padx=padx, pady=pady)

        # Build Target
        tk.Label(self.root, text="Build Target:").grid(
            row=5, column=0, sticky="w", padx=padx, pady=pady
        )
        self.target_var = tk.StringVar(value="Both")
        ttk.Combobox(
            self.root, textvariable=self.target_var, values=["Android", "iOS", "Both"], state="readonly"
        ).grid(row=5, column=1, sticky="ew", padx=padx, pady=pady)

        # Buttons
        btn_frame = tk.Frame(self.root)
        btn_frame.grid(row=6, column=0, columnspan=3, pady=10)

        self.btn_symlinks = tk.Button(
            btn_frame, text="Create Symlinks", command=self._on_create_symlinks
        )
        self.btn_symlinks.pack(side="left", padx=5)

        self.btn_sdk_setup = tk.Button(
            btn_frame, text="Setup Android SDK", command=self._on_setup_android_sdk
        )
        self.btn_sdk_setup.pack(side="left", padx=5)

        self.btn_build = tk.Button(
            btn_frame, text="Build", command=self._on_build
        )
        self.btn_build.pack(side="left", padx=5)

        # Log
        tk.Label(self.root, text="Build Log:").grid(
            row=7, column=0, sticky="w", padx=padx, pady=(10, 0)
        )
        self.log_text = scrolledtext.ScrolledText(
            self.root, height=15, wrap="word", state="normal"
        )
        self.log_text.grid(row=8, column=0, columnspan=3, sticky="nsew", padx=padx, pady=pady)

        # Progressbar
        self.progressbar = ttk.Progressbar(self.root, mode="indeterminate")
        self.progressbar.grid(row=9, column=0, columnspan=3, sticky="ew", padx=padx, pady=pady)

        self.root.columnconfigure(1, weight=1)
        self.root.rowconfigure(8, weight=1)

    def _settings_path(self) -> Path:
        return self.builder_dir / SETTINGS_FILENAME

    def _default_gui_config(self) -> dict:
        return {
            "android_sdk_packages": ["build-tools;35.0.0"],
        }

    def _load_gui_config(self) -> dict:
        """Merge `build_gui_config.json` with defaults (Android SDK packages for sdkmanager)."""
        cfg = self._default_gui_config()
        path = self.builder_dir / GUI_CONFIG_FILENAME
        if not path.is_file():
            return cfg
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return cfg
        raw = data.get("android_sdk_packages")
        if isinstance(raw, list):
            pkgs = [str(p).strip() for p in raw if str(p).strip()]
            if pkgs:
                cfg["android_sdk_packages"] = pkgs
        return cfg

    def _load_settings(self) -> None:
        path = self._settings_path()
        if not path.is_file():
            return
        try:
            raw = path.read_text(encoding="utf-8")
            data = json.loads(raw)
        except (OSError, json.JSONDecodeError):
            return

        self._loading_settings = True
        try:
            unity = (data.get("unity_editor_path") or "").strip()
            jdk = (data.get("jdk_path") or "").strip()
            sdk = (data.get("sdk_path") or "").strip()

            if unity:
                self.unity_path_var.set(unity)
            if jdk:
                self.jdk_path_var.set(jdk)
            if sdk:
                self.sdk_path_var.set(sdk)

            if unity:
                self.entry_jdk.config(state="normal")
                self.entry_sdk.config(state="normal")

            cfg = data.get("configuration")
            if cfg in ("Full", "Free"):
                self.config_var.set(cfg)
            bt = data.get("build_type")
            if bt in ("Release", "Debug"):
                self.build_type_var.set(bt)
            tgt = data.get("build_target")
            if tgt in ("Android", "iOS", "Both"):
                self.target_var.set(tgt)
        finally:
            self._loading_settings = False

    def _wire_auto_save(self) -> None:
        def on_change(*_args: object) -> None:
            if getattr(self, "_loading_settings", False):
                return
            self._save_settings()

        for var in (
            self.unity_path_var,
            self.jdk_path_var,
            self.sdk_path_var,
            self.config_var,
            self.build_type_var,
            self.target_var,
        ):
            var.trace_add("write", on_change)

    def _save_settings(self) -> None:
        if getattr(self, "_loading_settings", False):
            return
        data = {
            "unity_editor_path": self.unity_path_var.get().strip(),
            "jdk_path": self.jdk_path_var.get().strip(),
            "sdk_path": self.sdk_path_var.get().strip(),
            "configuration": self.config_var.get(),
            "build_type": self.build_type_var.get(),
            "build_target": self.target_var.get(),
        }
        try:
            self._settings_path().write_text(
                json.dumps(data, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
        except OSError:
            pass

    def _on_close_window(self) -> None:
        self._save_settings()
        self.root.destroy()

    def _browse_unity(self) -> None:
        path = filedialog.askdirectory(title="Select Unity Editor folder")
        if not path:
            return
        unity_path = Path(path)
        if not unity_path.is_dir():
            messagebox.showerror("Error", "Selected path is not a directory.")
            return

        unity_exe = unity_path / "Editor" / "Unity.exe"
        if not unity_exe.exists():
            candidates = list(unity_path.rglob("Editor/Unity.exe"))
            if candidates:
                unity_path = candidates[0].parent.parent
            else:
                messagebox.showwarning(
                    "Warning",
                    "Could not find Editor\\Unity.exe in the selected folder.\n"
                    "Please select the Unity installation root folder (e.g., C:\\Program Files\\Unity\\Hub\\Editor\\6000.2.6f2).",
                )
                return

        self.unity_path_var.set(str(unity_path))
        self._update_jdk_sdk_paths(unity_path)

    def _update_jdk_sdk_paths(self, unity_path: Path) -> None:
        jdk = unity_path / "Editor" / "Data" / "PlaybackEngines" / "AndroidPlayer" / "OpenJDK"
        sdk = unity_path / "Editor" / "Data" / "PlaybackEngines" / "AndroidPlayer" / "SDK"

        if jdk.exists():
            self.jdk_path_var.set(str(jdk))
        else:
            self.jdk_path_var.set("")
            self._log("[WARN] JDK not found at expected path: " + str(jdk) + "\n")

        if sdk.exists():
            self.sdk_path_var.set(str(sdk))
        else:
            self.sdk_path_var.set("")
            self._log("[WARN] SDK not found at expected path: " + str(sdk) + "\n")

        self.entry_jdk.config(state="normal")
        self.entry_sdk.config(state="normal")

    def _log(self, message: str) -> None:
        self.log_queue.put(("log", message))

    def _start_progress(self) -> None:
        self.log_queue.put(("progress_start", None))

    def _stop_progress(self) -> None:
        self.log_queue.put(("progress_stop", None))

    def _poll_queue(self) -> None:
        try:
            while True:
                msg_type, msg = self.log_queue.get_nowait()
                if msg_type == "log":
                    self.log_text.insert("end", msg)
                    self.log_text.see("end")
                elif msg_type == "progress_start":
                    self.progressbar.start(10)
                elif msg_type == "progress_stop":
                    self.progressbar.stop()
        except queue.Empty:
            pass
        self.root.after(100, self._poll_queue)

    def _validate_paths(self) -> tuple[Path, Path, Path]:
        unity_str = self.unity_path_var.get().strip()
        jdk_str = self.jdk_path_var.get().strip()
        sdk_str = self.sdk_path_var.get().strip()

        if not unity_str:
            raise ValueError("Please select the Unity Editor path.")

        unity_path = Path(unity_str)
        if not unity_path.exists():
            raise ValueError(f"Unity path does not exist: {unity_path}")

        if not jdk_str or not sdk_str:
            raise ValueError("JDK and SDK paths must be set. Select a Unity Editor folder first.")

        jdk_path = Path(jdk_str)
        sdk_path = Path(sdk_str)

        if not jdk_path.exists():
            raise ValueError(f"JDK path does not exist: {jdk_path}")
        if not sdk_path.exists():
            raise ValueError(f"SDK path does not exist: {sdk_path}")

        return unity_path, jdk_path, sdk_path

    # ------------------------------------------------------------------
    # Symlinks
    # ------------------------------------------------------------------
    def _on_create_symlinks(self) -> None:
        target_str = self.unity_path_var.get().strip()
        if not target_str:
            messagebox.showerror("Error", "Please select the Unity Editor path first.")
            return

        target = Path(target_str).resolve()
        if not target.exists():
            messagebox.showerror("Error", f"Selected path does not exist: {target}")
            return

        links = {
            r"C:\Program Files\Unity-Export": target,
            r"C:\Program Files\Unity-Publish": target,
        }

        # Removing junctions/symlinks under Program Files requires elevation — same as mklink.
        # Do not use os.remove() here (WinError 5). Use rmdir inside the elevated cmd.
        cmd_parts: list[str] = []
        for link_str, tgt in links.items():
            link = Path(link_str)
            tgt_q = str(tgt)
            if link.exists():
                if link.is_symlink() or link.is_junction():
                    cmd_parts.append(f'rmdir /q "{link}"')
                else:
                    self._log(
                        f"[WARN] {link} already exists and is not a symlink/junction. Skipping.\n"
                    )
                    continue
            cmd_parts.append(f'mklink /D "{link}" "{tgt_q}"')

        if not cmd_parts:
            messagebox.showinfo("Symlinks", "Nothing to do.")
            return

        if not messagebox.askokcancel(
            "Create symlinks",
            "This will request Administrator permission (UAC).\n\n"
            "It may remove existing Unity-Export / Unity-Publish junctions under "
            '"C:\\Program Files", then create directory links pointing to:\n'
            f"{target}\n\n"
            "Click OK to continue, or Cancel to abort.",
        ):
            return

        full_cmd = " & ".join(cmd_parts)
        try:
            ctypes.windll.shell32.ShellExecuteW(None, "runas", "cmd.exe", f"/c {full_cmd}", None, 1)
        except Exception as e:
            messagebox.showerror("Error", f"Failed to request admin privileges:\n{e}")

    # ------------------------------------------------------------------
    # Android SDK Setup (licenses + packages from build_gui_config.json)
    # ------------------------------------------------------------------
    def _find_sdkmanager(self, sdk_path: Path) -> Path | None:
        candidates = [
            # cmdline-tools (preferred)
            *(sdk_path / "cmdline-tools").rglob("bin/sdkmanager.bat"),
            # legacy tools
            sdk_path / "tools" / "bin" / "sdkmanager.bat",
        ]
        for c in candidates:
            if isinstance(c, Path) and c.exists():
                return c
        return None

    def _on_setup_android_sdk(self) -> None:
        sdk_str = self.sdk_path_var.get().strip()
        if not sdk_str:
            messagebox.showerror("Error", "Please select the Unity Editor path first to auto-detect the SDK.")
            return
        sdk_path = Path(sdk_str)
        if not sdk_path.exists():
            messagebox.showerror("Error", f"SDK path does not exist: {sdk_path}")
            return

        sdkmanager = self._find_sdkmanager(sdk_path)
        if not sdkmanager:
            messagebox.showerror(
                "Error",
                f"sdkmanager.bat not found in:\n{sdk_path}\n\n"
                "Make sure the Unity Android Player is installed.",
            )
            return

        gui_cfg = self._load_gui_config()
        packages = gui_cfg.get("android_sdk_packages") or []
        if not packages:
            messagebox.showerror(
                "Error",
                f"No packages listed under \"android_sdk_packages\" in {GUI_CONFIG_FILENAME}.\n"
                "Add at least one sdkmanager package spec (e.g. build-tools;35.0.0).",
            )
            return

        pkg_lines = "\n".join(f"  • {p}" for p in packages)
        if not messagebox.askokcancel(
            "Android SDK Setup",
            "This will open an elevated Command Prompt (UAC).\n\n"
            "It will:\n"
            "  • Accept Android SDK licenses\n"
            "  • Install package(s) via sdkmanager:\n"
            f"{pkg_lines}\n\n"
            f"(Edit `{GUI_CONFIG_FILENAME}` next to build.py to change this list.)\n\n"
            "Administrator rights are often required because the SDK "
            "may live under Program Files.\n\n"
            "Click OK to continue, or Cancel to abort.",
        ):
            return

        # CMD: bare `y` inside `( )` runs a command named y — use `echo y` instead.
        tmp_dir = Path(tempfile.gettempdir())
        bat_path = tmp_dir / "lunar_console_sdk_setup.bat"

        lines = [
            "@echo off",
            "echo ========== Accepting Android SDK licenses ==========",
            "(",
        ]
        lines.extend(["echo y"] * 60)
        lines.extend([
            f') | "{sdkmanager}" --licenses',
            "echo.",
        ])
        for pkg in packages:
            safe = pkg.replace('"', '""')
            lines.append(f"echo ========== Installing {safe} ==========")
            lines.append(f'"{sdkmanager}" "{pkg}"')
            lines.append("echo.")
        lines.extend([
            "echo ========== Setup complete ==========",
            "pause",
        ])
        bat_path.write_text("\r\n".join(lines), encoding="utf-8")

        try:
            ctypes.windll.shell32.ShellExecuteW(None, "runas", str(bat_path), None, str(tmp_dir), 1)
        except Exception as e:
            messagebox.showerror("Error", f"Failed to start SDK setup:\n{e}")

    # ------------------------------------------------------------------
    # Build
    # ------------------------------------------------------------------
    def _on_build(self) -> None:
        if getattr(self, "_build_running", False):
            messagebox.showwarning("Build", "A build is already running.")
            return

        target = self.target_var.get()
        if not target:
            messagebox.showerror("Error", "Please select a build target.")
            return

        self._build_running = True
        self.btn_build.config(state="disabled")
        self.btn_symlinks.config(state="disabled")
        self.btn_sdk_setup.config(state="disabled")
        self.log_text.delete("1.0", "end")

        t = threading.Thread(target=self._build_thread, args=(target,), daemon=True)
        t.start()

    def _build_thread(self, target: str) -> None:
        try:
            self._start_progress()
            self._log("=== Build started ===\n")

            unity_path, jdk_path, sdk_path = self._validate_paths()
            self._log(f"Unity: {unity_path}\n")
            self._log(f"JDK:   {jdk_path}\n")
            self._log(f"SDK:   {sdk_path}\n")

            config = self.config_var.get()
            build_type = self.build_type_var.get()
            self._log(f"Configuration: {config}\n")
            self._log(f"Build Type:    {build_type}\n")
            self._log(f"Target:        {target}\n\n")

            if target in ("Android", "Both"):
                self._build_android(jdk_path, sdk_path, config, build_type)

            if target in ("iOS", "Both"):
                self._build_ios(config)

            self._log("\n=== Build completed successfully ===\n")
        except Exception as e:
            self._log(f"\n!!! ERROR: {e} !!!\n")
        finally:
            self._stop_progress()
            self._build_running = False
            self.root.after(0, self._restore_ui)

    def _restore_ui(self) -> None:
        self.btn_build.config(state="normal")
        self.btn_symlinks.config(state="normal")
        self.btn_sdk_setup.config(state="normal")

    def _run_command(self, cmd: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
        self._log(f">>> Running: {' '.join(cmd)}\n")
        with subprocess.Popen(
            cmd,
            cwd=str(cwd),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        ) as proc:
            for line in proc.stdout:
                self._log(line)

        if proc.returncode != 0:
            raise RuntimeError(f"Command exited with code {proc.returncode}")
        self._log("<<< Command finished\n")

    def _build_android(self, jdk_path: Path, sdk_path: Path, config: str, build_type: str) -> None:
        android_dir = self.repo_dir / "Native" / "Android" / "LunarConsole"
        gradlew = android_dir / "gradlew.bat"

        if not gradlew.exists():
            raise FileNotFoundError(f"gradlew.bat not found: {gradlew}")

        env = os.environ.copy()
        env["JAVA_HOME"] = str(jdk_path)
        env["ANDROID_HOME"] = str(sdk_path)

        flavour = config
        cmd = [
            str(gradlew),
            ":lunarConsole:clean",
            f":lunarConsole:assemble{flavour}{build_type}",
        ]

        self._log("\n--- Android build ---\n")
        self._run_command(cmd, android_dir, env)

        aar = (
            android_dir
            / "lunarConsole"
            / "build"
            / "outputs"
            / "aar"
            / f"lunarConsole-{flavour.lower()}-{build_type.lower()}.aar"
        )
        if aar.exists():
            self._log(f"\nAAR created: {aar}\n")
        else:
            self._log(f"\n[WARNING] AAR not found at expected path: {aar}\n")

    def _build_ios(self, config: str) -> None:
        config_name = config.lower()
        # Invoke CLI uses hyphens, not underscores (see `invoke --list`)
        cmd = ["invoke", f"_{config_name}", "_build-native-ios"]

        self._log("\n--- iOS build ---\n")
        self._run_command(cmd, self.builder_dir)

        plugin_ios = (
            self.repo_dir / "Project" / "Assets" / "LunarConsole" / "Editor" / "iOS"
        )
        if plugin_ios.exists():
            self._log(f"\niOS plugin copied to: {plugin_ios}\n")
        else:
            self._log(f"\n[WARNING] iOS plugin folder not found: {plugin_ios}\n")


def main() -> None:
    root = tk.Tk()
    LunarConsoleBuilderApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
