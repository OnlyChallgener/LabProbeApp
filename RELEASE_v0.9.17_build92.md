# LabProbe v0.9.17 build92 · Build Fix

## 修复

- 修复 build91 中 MainActivity.kt 的 Kotlin 正则字符串转义错误。
- 修复 `Regex("丢包\s*(\d+)")` 在 Kotlin 字符串中被解析为非法 escape 的问题。
- 本版本不改 Hub / Router Agent / API。

## 说明

如果 build91 的 GitHub Actions 已失败，可直接使用 build92 发版，避免反复删除失败标签。
