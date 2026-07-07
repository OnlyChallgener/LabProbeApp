# build107 覆盖说明

建议先删除旧源码目录，避免混编：

```bash
cd /d/Github/LabProbeApp
rm -rf app/src/main/kotlin/com/labprobe/app
```

然后把本完整包内所有内容复制覆盖到 `D:\Github\LabProbeApp`。

检查：

```bash
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "当前测试总结" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

发版：

```bash
git add .
git commit -m "restore roaming summary in stable single page"
git pull --rebase origin main
git push origin main

git tag v0.9.17-build107
git push origin v0.9.17-build107
```
