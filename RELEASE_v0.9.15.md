# Labprobe v0.9.15 hotfix3

## 本次重点

- 新增 **NAT 行为检测** 页面。
- 按 RFC3489 传统流程展示 `TEST 1 / TEST 2 / TEST 3 / TEST 4`。
- STUN 解析支持：
  - `MAPPED-ADDRESS`
  - `XOR-MAPPED-ADDRESS`
  - `CHANGED-ADDRESS`
  - `OTHER-ADDRESS`
- 结果不再只硬报 NAT1 / NAT3，而是同时展示：
  - 公网映射
  - 本地端点
  - 备用地址
  - 映射行为
  - 过滤行为
  - 传统类型
  - 可信度
- 如果公共 STUN 只支持基础 Binding，APP 会显示“基础 STUN：无法完整分类”，避免误判。
- 工具页改为 One UI 2 列磁贴布局。
- 移除“整张卡片可直接进入”提示。
- 新增工具页网络状态概览卡。

## 版本

- versionName: 0.9.15
- versionCode: 48
