# v0.9.17 build119

- 修复 build118 编译失败：`Suspension functions can only be called within coroutine body`。
- 原因：`readWifiSample()` 内部的本地 `pingTargetOnce()` 不是 suspend，却调用了 suspend 的 `pingOnceAddress()`。
- 处理：改为 `suspend fun pingTargetOnce(...)`。
- 不改功能逻辑。
