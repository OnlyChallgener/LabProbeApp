# LabProbe v0.9.17 build91 补丁覆盖说明

在 App 项目根目录执行：

```bash
cp app/src/main/kotlin/com/labprobe/app/MainActivity.kt ./app/src/main/kotlin/com/labprobe/app/MainActivity.kt
cp app/src/main/kotlin/com/labprobe/app/RoamingChart.kt ./app/src/main/kotlin/com/labprobe/app/RoamingChart.kt
cp app/build.gradle.kts ./app/build.gradle.kts
cp RELEASE_v0.9.17_build91.md ./RELEASE_v0.9.17_build91.md
```

提交并发版：

```bash
git add .
git commit -m "add roaming reports compact ui and faster sampling"
git push origin main

git tag v0.9.17-build91
git push origin v0.9.17-build91
```

如果 tag 已存在：

```bash
git tag -d v0.9.17-build91
git push origin --delete v0.9.17-build91
git tag v0.9.17-build91
git push origin v0.9.17-build91
```
