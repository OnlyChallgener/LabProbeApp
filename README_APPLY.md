# LabProbe build117 覆盖说明

1. 先删除旧源码目录，避免混编：

```bash
cd /d/Github/LabProbeApp
rm -rf app/src/main/kotlin/com/labprobe/app
```

2. 解压 build117，把 `build117_main` 里的所有内容复制覆盖到 `D:\Github\LabProbeApp`。

3. 检查：

```bash
grep -n "versionCode" app/build.gradle.kts
grep -n "const val CODE" app/src/main/kotlin/com/labprobe/app/MainActivity.kt
grep -Rni "切换到黑夜\|darkColorScheme\|isSystemInDarkTheme\|v0.9.15\|build82" app/src/main/kotlin/com/labprobe/app
```

前两条应显示 build117；第三条除图标内部普通颜色命名外不应再出现主题/旧版本残留。
