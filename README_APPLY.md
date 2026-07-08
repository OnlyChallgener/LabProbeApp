# LabProbe build118 覆盖说明

1. 先删除旧源码目录，避免混编：

```bash
cd /d/Github/LabProbeApp
rm -rf app/src/main/kotlin/com/labprobe/app
```

2. 解压 build118，把 `build118_main` 里的所有内容复制覆盖到 `D:\Github\LabProbeApp`。

3. 检查：

```bash
cd /d/Github/LabProbeApp
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "底部超短超细红线" app/src/main/kotlin/com/labprobe/app/RoamingChart.kt
grep -n "网关+外网" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

应显示：

```text
versionCode = 118
const val CODE = 118
```

4. 发版：

```bash
git status
git add .
git commit -m "polish roaming wave chart and dual ping display"
git pull --rebase origin main
git push origin main

git tag v0.9.17-build118
git push origin v0.9.17-build118
```
