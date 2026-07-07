# LabProbe build106 完整覆盖包

将本目录内所有内容覆盖到 `D:\Github\LabProbeApp`。

覆盖后检查：

```bash
cd /d/Github/LabProbeApp
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "WifiRoamingToolEmergencyStable" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

应看到 versionCode = 106、CODE = 106。
