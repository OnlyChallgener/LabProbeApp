# LabProbe v0.9.17 build112 完整覆盖说明

1. 建议先删除旧源码目录，避免旧 Kotlin 文件混编：

```bash
cd /d/Github/LabProbeApp
rm -rf app/src/main/kotlin/com/labprobe/app
```

2. 解压本包，把 `build112_main` 文件夹内的所有内容复制覆盖到：

```text
D:\Github\LabProbeApp
```

3. 检查版本：

```bash
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "淡蓝白背景" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

应看到 versionCode = 112 / CODE = 112。

4. 提交发版：

```bash
git add .
git commit -m "polish app background and roaming compact metrics"
git pull --rebase origin main
git push origin main

git tag v0.9.17-build112
git push origin v0.9.17-build112
```
