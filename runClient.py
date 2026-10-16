"""VS Code's Run Python File button: start the Gradle runClient task."""

from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parent
WRAPPER = ROOT / "gradlew.bat"
FALLBACK = Path.home() / "Tools" / "gradle-8.10.2" / "bin" / "gradle.bat"
gradle = (
    str(WRAPPER)
    if WRAPPER.is_file()
    else shutil.which("gradle.bat") or (str(FALLBACK) if FALLBACK.is_file() else None)
)

if gradle is None:
    print("找不到 Gradle。请安装 Gradle 或将 gradle.bat 加入 PATH。", file=sys.stderr)
    raise SystemExit(1)

command = [gradle, "runClient", "--no-daemon", "--console=plain"]
local_maven = ROOT / ".tools" / "local-maven.init.gradle"
if local_maven.is_file():
    command.extend(("--offline", "-I", str(local_maven)))

print("运行 Gradle runClient…", flush=True)
raise SystemExit(subprocess.call(command, cwd=ROOT))
