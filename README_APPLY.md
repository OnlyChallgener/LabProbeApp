# LabProbe build100 完整覆盖包

这是基于 main 的完整覆盖包。

覆盖到 `D:\Github\LabProbeApp` 后检查：

```bash
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "safeHasWifiRoamingPermissions" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "fun Context.findActivity" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

应显示 versionCode = 100 / CODE = 100，并且能找到权限辅助函数。
