# LabProbe v0.9.17 build94 · roaming crash hotfix

- 修复进入漫游测试页可能因权限请求/系统 Wi-Fi API 异常导致的闪退。
- 权限请求改为延迟安全启动，只请求缺失权限，并捕获启动异常。
- Wi-Fi 连接判断和 Wi-Fi 采样读取增加 SecurityException 保护。
- AndroidManifest 补充 ACCESS_COARSE_LOCATION 兼容部分系统权限返回。
