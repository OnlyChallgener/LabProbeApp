# LabProbe build109 覆盖说明

本包是完整覆盖包。为了避免 Kotlin 源码混编，建议先删除旧源码目录：

```bash
cd /d/Github/LabProbeApp
rm -rf app/src/main/kotlin/com/labprobe/app
```

然后把本包目录内的所有内容复制覆盖到：

```text
D:\Github\LabProbeApp
```

检查：

```bash
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "当前测试总结" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

提交：

```bash
git add .
git commit -m "polish roaming compact layout"
git pull --rebase origin main
git push origin main

git tag v0.9.17-build109
git push origin v0.9.17-build109
```
