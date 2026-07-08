# Apply build115

1. 删除旧源码目录，避免混编：
   cd /d/Github/LabProbeApp
   rm -rf app/src/main/kotlin/com/labprobe/app

2. 解压 build115，把 build115_main 里的所有内容复制覆盖到 D:\Github\LabProbeApp

3. 检查：
   grep -n "versionCode" app/build.gradle.kts
   grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt

4. 提交发版：
   git add .
   git commit -m "rollback roaming to stable build with higher version code"
   git pull --rebase origin main
   git push origin main
   git tag v0.9.17-build115
   git push origin v0.9.17-build115
