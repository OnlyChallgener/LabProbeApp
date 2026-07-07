# build98 完整覆盖说明

这是完整工程覆盖包，不是小补丁。

推荐做法：

1. 备份 D:\Github\LabProbeApp
2. 删除或覆盖 D:\Github\LabProbeApp 内全部文件
3. 将本压缩包内 LabProbeApp-main 文件夹里的所有内容复制到 D:\Github\LabProbeApp
4. 执行：

```bash
cd /d/Github/LabProbeApp
git status
git add .
git commit -m "fix roaming entry safe permission request"
git pull --rebase origin main
git push origin main
git tag v0.9.17-build98
git push origin v0.9.17-build98
```

如果 tag 已存在，先删除再重打。
