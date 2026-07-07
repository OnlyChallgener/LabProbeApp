# build99 完整覆盖说明

把本目录内所有文件复制覆盖到 `D:\Github\LabProbeApp`。

覆盖后检查：

```bash
cd /d/Github/LabProbeApp
grep -n "versionCode" app/build.gradle.kts
grep -n "const val NAME" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "测试准备中" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "正在初始化漫游测试页面" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "正在准备权限" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

正确结果：versionCode = 99；NAME = 0.9.17；CODE = 99；后三条无输出。
