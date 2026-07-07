# Apply build108

Delete old source folder first to avoid mixed Kotlin source versions:

```bash
cd /d/Github/LabProbeApp
rm -rf app/src/main/kotlin/com/labprobe/app
```

Then copy all contents from `build108_main` into `D:\Github\LabProbeApp` and overwrite.

Verify:

```bash
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "测试总结已保存" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```
