# LabProbe v0.9.17 build113 覆盖说明

本包用于完整覆盖当前 LabProbeApp main 工程。

## 覆盖方式

建议先删除旧源码目录，避免旧文件混编：

```bash
cd /d/Github/LabProbeApp
rm -rf app/src/main/kotlin/com/labprobe/app
```

然后把本包 `build113_main` 文件夹内的全部内容复制到：

```text
D:\Github\LabProbeApp
```

选择全部覆盖。

## 检查

```bash
cd /d/Github/LabProbeApp
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "WindowCompat.setDecorFitsSystemWindows" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "fun RoamMiniStat" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

应看到 build 113 和相关函数。

## 发版

```bash
git status
git add .
git commit -m "fix app background status bar and roaming metric cards"
git pull --rebase origin main
git push origin main

git tag v0.9.17-build113
git push origin v0.9.17-build113
```
