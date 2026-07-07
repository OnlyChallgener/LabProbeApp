# LabProbe v0.9.17 build94 · roaming crash hotfix

覆盖：

```bash
cp app/src/main/kotlin/com/labprobe/app/MainActivity.kt ./app/src/main/kotlin/com/labprobe/app/MainActivity.kt
cp app/src/main/AndroidManifest.xml ./app/src/main/AndroidManifest.xml
cp app/build.gradle.kts ./app/build.gradle.kts
cp RELEASE_v0.9.17_build94.md ./RELEASE_v0.9.17_build94.md
```

提交：

```bash
git add .
git commit -m "fix roaming screen crash on enter"
git push origin main
git tag v0.9.17-build94
git push origin v0.9.17-build94
```
