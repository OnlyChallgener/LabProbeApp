# LabProbe v0.9.17 build110 应用说明

本包为完整覆盖包。

## 覆盖步骤

1. 在 Git Bash 进入项目目录：

```bash
cd /d/Github/LabProbeApp
rm -rf app/src/main/kotlin/com/labprobe/app
```

2. 解压 build110，把解压后的所有内容复制覆盖到：

```text
D:\Github\LabProbeApp
```

3. 检查：

```bash
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -n "CandidateScanToggle" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
```

应看到 versionCode = 110、CODE = 110、CandidateScanToggle。

## 发版

```bash
git status
git add .
git commit -m "add optional roaming candidate ap scan"
git pull --rebase origin main
git push origin main

git tag v0.9.17-build110
git push origin v0.9.17-build110
```
