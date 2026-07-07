# LabProbe v0.9.17 build104 完整覆盖修复包

用途：从稳定的 build101 漫游入口回退版重新打包，修复 build102/103 混合覆盖导致的大量 Kotlin unresolved reference 编译错误。

重要：这次需要先删除旧源码目录再复制，避免旧文件和新文件混编。

1. 备份 D:\\Github\\LabProbeApp
2. 删除 D:\\Github\\LabProbeApp\\app\\src\\main\\kotlin\\com\\labprobe\\app 整个 app 文件夹
3. 把本包内所有内容覆盖到 D:\\Github\\LabProbeApp
4. 检查 versionCode=104 和 AppVersion.CODE=104
5. git add . && git commit && git push && tag
