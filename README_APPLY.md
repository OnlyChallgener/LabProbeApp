# LabProbe build105 完整覆盖说明

把本目录内所有内容复制到 `D:\Github\LabProbeApp`，选择全部覆盖。

覆盖后检查：

```bash
cd /d/Github/LabProbeApp
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "fun WifiRoamingScreen" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "fun WifiRoamingTool" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

应看到 versionCode/CODE 为 105，并且 WifiRoamingScreen 调用完整 WifiRoamingTool。
