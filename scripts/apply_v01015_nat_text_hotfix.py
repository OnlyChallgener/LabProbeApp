#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"


def apply() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")
    old = '''    replacements.forEach { (old, new) -> text = text.replace(old, new, ignoreCase = true) }
    return text
}'''
    new = '''    replacements.forEach { (old, new) -> text = text.replace(old, new, ignoreCase = true) }
    text = text
        .replace("[Test I#2]", "[测试 I#2]", ignoreCase = true)
        .replace("[Test I]", "[测试 I]", ignoreCase = true)
        .replace("[Test II]", "[测试 II]", ignoreCase = true)
        .replace("[Test III]", "[测试 III]", ignoreCase = true)
        .replace("[Detection]", "[检测]", ignoreCase = true)
        .replace("[Configuration]", "[配置]", ignoreCase = true)
        .replace("[NAT Detection]", "[NAT 检测]", ignoreCase = true)
        .replace("Sending Binding Request", "正在发送绑定请求", ignoreCase = true)
        .replace("Sending ChangePort request", "正在发送更改端口请求", ignoreCase = true)
        .replace("to alternate server", "到备用服务器", ignoreCase = true)
        .replace("Mapped address", "映射地址", ignoreCase = true)
        .replace("Changed address", "变更地址", ignoreCase = true)
        .replace("No response", "无响应", ignoreCase = true)
        .replace("NAT detected", "检测到 NAT", ignoreCase = true)
        .replace("performing further tests", "继续后续测试", ignoreCase = true)
        .replace("Same mapping", "映射一致", ignoreCase = true)
        .replace("consistent mapping behavior", "映射行为稳定", ignoreCase = true)
        .replace("Detection completed successfully", "检测成功完成", ignoreCase = true)
    return text
}'''
    if new not in text:
        if old not in text:
            raise RuntimeError("missing v0.10.15 hotfix pattern: NAT log localization")
        text = text.replace(old, new, 1)

    text = text.replace(
        'Text(item.natType.ifBlank { "未知NAT" }, fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = NativeInk)',
        'Text(natTypeZh(item.natType).takeUnless { it == "--" } ?: "未知NAT", fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = NativeInk)'
    )
    ROUTER_NATIVE.write_text(text, encoding="utf-8")
    print("v0.10.15 NAT Chinese text hotfix applied")


if __name__ == "__main__":
    apply()
