#!/usr/bin/env python3
"""Build162 follow-up: normalize legacy/partial DDNS records before UI rendering."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
CONTROL = SRC / "RouterControlUi.kt"
API = SRC / "RouterControlApi.kt"
VERIFIER = ROOT / "scripts/verify_build154_sources.py"


def patch_card(text: str) -> str:
    anchor = '''    val warning=record.status.contains("error",true)||record.status.contains("fail",true)
    var menu by remember(record.serviceId){mutableStateOf(false)}'''
    replacement = '''    val warning=record.status.contains("error",true)||record.status.contains("fail",true)
    val domainText=record.domain.ifBlank{"未命名 DDNS 记录"}
    val providerText=record.provider.ifBlank{"未识别服务商"}
    val interfaceText=record.interfaceName.ifBlank{"wan"}.uppercase(Locale.ROOT)
    var menu by remember(record.serviceId){mutableStateOf(false)}'''
    if replacement not in text:
        if anchor not in text:
            raise RuntimeError("missing build162 DDNS card normalization anchor")
        text = text.replace(anchor, replacement, 1)
    text = text.replace(
        'Text(record.domain,fontSize=11.9.sp',
        'Text(domainText,fontSize=11.9.sp',
        1,
    )
    text = text.replace(
        'Text("${record.provider} · ${if(record.useIpv6)"IPv6" else "IPv4"} · ${record.interfaceName.uppercase()}",fontSize=9.5.sp',
        'Text("$providerText · ${if(record.useIpv6)"IPv6" else "IPv4"} · $interfaceText",fontSize=9.5.sp',
        1,
    )
    return text


def patch_editor(text: str) -> str:
    text = text.replace(
        '''                    initial = editing ?: DdnsRecord(),
                    onBack = { adding = false; editing = null },''',
        '''                    initial = editing ?: DdnsRecord(),
                    externalError = actionError,
                    onBack = { adding = false; editing = null },''',
        1,
    )
    text = text.replace(
        'actionError = it.message.orEmpty()',
        'actionError = it.message.orEmpty().ifBlank { "DDNS 设置未生效，请稍后重试" }',
        1,
    )
    start_marker = "@Composable\nprivate fun DdnsEditorPage("
    end_marker = "\n@Composable\nfun RouterDiagnosticScreen"
    start = text.find(start_marker)
    end = text.find(end_marker, start + len(start_marker))
    if start < 0 or end < 0:
        if "val normalizedInitial=remember(initial)" in text:
            return text
        raise RuntimeError("missing build162 DDNS editor anchor")
    replacement = '''@Composable
private fun DdnsEditorPage(initial:DdnsRecord,externalError:String="",onBack:()->Unit,onSave:(DdnsRecord,String?)->Unit){
    val normalizedInitial=remember(initial){
        initial.copy(
            provider=initial.provider.trim().ifBlank{"aliyun.com"},
            interfaceName=initial.interfaceName.trim().ifBlank{"wan"},
            domain=initial.domain.trim(),
            username=initial.username.trim(),
        )
    }
    var record by remember(normalizedInitial){mutableStateOf(normalizedInitial)}
    var password by remember(normalizedInitial.serviceId){mutableStateOf("")}
    var showPassword by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val providerOptions=remember(record.provider){
        val defaults=listOf("aliyun.com","dnspod.cn","no-ip.com")
        if(record.provider in defaults)defaults else listOf(record.provider)+defaults
    }
    val interfaceOptions=remember(record.interfaceName){
        val defaults=listOf("wan","wan1")
        if(record.interfaceName in defaults)defaults else listOf(record.interfaceName)+defaults
    }
    BackHandler(onBack=onBack)
    RouterFormPage(if(normalizedInitial.serviceId.isBlank())"新增DDNS" else "编辑DDNS","密钥由你输入；留空保持原值",onBack){
        CompactChoice("服务商",record.provider,providerOptions){record=record.copy(provider=it)}
        CompactField("域名 / 记录",record.domain,"例如 rj.lab86@shinya.icu"){record=record.copy(domain=it.take(128))}
        CompactField("用户名 / AccessKey",record.username,"AccessKey ID"){record=record.copy(username=it.take(160))}
        CompactPasswordField(if(normalizedInitial.passwordConfigured)"密码 / Secret（留空保持）" else "密码 / Secret",password,"请输入密钥",showPassword,{showPassword=!showPassword}){password=it.take(256)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){CompactChoice("接口",record.interfaceName,interfaceOptions,Modifier.weight(1f)){record=record.copy(interfaceName=it)};CompactChoice("记录类型",if(record.useIpv6)"IPv6" else "IPv4",listOf("IPv6","IPv4"),Modifier.weight(1f)){record=record.copy(useIpv6=it=="IPv6")}}
        Row(verticalAlignment=Alignment.CenterVertically){Text("启用记录",fontSize=10.5.sp,fontWeight=FontWeight.Bold,color=RouterMuted);Spacer(Modifier.weight(1f));Switch(record.enabled,{record=record.copy(enabled=it)},modifier=Modifier.scale(.85f),colors=SwitchDefaults.colors(checkedTrackColor=RouterCyan))}
        val visibleError=error.ifBlank{externalError}
        if(visibleError.isNotBlank())Text(visibleError,fontSize=10.5.sp,color=RouterRed)
        Button(onClick={error=when{record.domain.isBlank()->"请填写域名";record.username.isBlank()->"请填写账号/AccessKey";normalizedInitial.serviceId.isBlank()&&password.isBlank()->"请填写密码/Secret";else->""};if(error.isBlank())onSave(record,password.ifBlank{null})},modifier=Modifier.fillMaxWidth().height(42.dp),shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterCyan)){Text("保存并同步",fontSize=11.5.sp,fontWeight=FontWeight.Black)}
    }
}
'''
    return text[:start] + replacement.rstrip() + text[end:]


def patch_parser(text: str) -> str:
    start_marker = "internal fun parseDdnsList(data: JSONObject): List<DdnsRecord> {"
    end_marker = "\n\nprivate fun routerDiagnosticTitleZh"
    start = text.find(start_marker)
    end = text.find(end_marker, start + len(start_marker))
    if start < 0 or end < 0:
        if "private fun JSONObject.ddnsText" in text:
            return text
        raise RuntimeError("missing build162 DDNS parser anchor")
    replacement = '''private fun JSONObject.ddnsText(vararg keys:String):String{
    for(key in keys){
        if(!has(key)||isNull(key))continue
        val text=cleanApiText(opt(key)?.toString())
        if(text.isNotBlank())return text
    }
    return ""
}

private fun JSONObject.ddnsFlag(default:Boolean,vararg keys:String):Boolean{
    for(key in keys){
        if(!has(key)||isNull(key))continue
        return when(val value=opt(key)){
            is Boolean->value
            is Number->value.toInt()!=0
            else->when(cleanApiText(value?.toString()).lowercase(Locale.ROOT)){
                "1","true","yes","on","enabled","enable","ipv6"->true
                "0","false","no","off","disabled","disable","ipv4"->false
                else->default
            }
        }
    }
    return default
}

internal fun parseDdnsList(data: JSONObject): List<DdnsRecord> {
    val arr = data.optJSONArray("list") ?: data.optJSONArray("data") ?: data.optJSONArray("records") ?: JSONArray()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { o ->
            DdnsRecord(
                serviceId = o.ddnsText("service", "serviceId", "service_id", "id", "uuid"),
                provider = o.ddnsText("service_name", "serviceName", "provider", "providerName").ifBlank { "aliyun.com" },
                domain = o.ddnsText("domain", "host", "hostname", "record"),
                username = o.ddnsText("username", "user", "accessKey", "accessKeyId", "access_key_id"),
                enabled = o.ddnsFlag(true, "enable", "enabled", "isEnabled"),
                useIpv6 = o.ddnsFlag(true, "use_ipv6", "useIpv6", "ipv6", "ipVersion"),
                interfaceName = o.ddnsText("interface", "interfaceName", "wan", "iface").ifBlank { "wan" },
                status = o.ddnsText("status", "state", "message", "msg"),
                ip = o.ddnsText("ip", "currentIp", "current_ip", "address"),
                passwordConfigured = o.ddnsFlag(false, "passwordConfigured", "password_configured", "hasPassword", "has_password")
            )
        }
    }
}
'''
    return text[:start] + replacement.rstrip() + text[end:]


def patch_verifier(text: str) -> str:
    marker = "    forbid(ROUTER_CONTROL, 'PremiumCard(accent,Modifier.clickable(onClick=onEdit))')\n"
    checks = '''    require(
        ROUTER_CONTROL,
        'val normalizedInitial=remember(initial)',
        'val visibleError=error.ifBlank{externalError}',
        '未识别服务商',
    )
    require(
        ROUTER_API,
        'private fun JSONObject.ddnsText',
        'private fun JSONObject.ddnsFlag',
        'data.optJSONArray("records")',
        '"serviceId", "service_id"',
        '"currentIp", "current_ip"',
    )
'''
    if checks not in text:
        if marker not in text:
            raise RuntimeError("missing build162 field verifier anchor")
        text = text.replace(marker, marker + checks, 1)
    text = text.replace(
        "print('build162 DDNS navigation and isolated action targets verified')",
        "print('build162 DDNS editor isolation and field compatibility verified')",
    )
    return text


def verify() -> None:
    combined = CONTROL.read_text(encoding="utf-8") + "\n" + API.read_text(encoding="utf-8")
    required = (
        "val normalizedInitial=remember(initial)",
        "val visibleError=error.ifBlank{externalError}",
        "未识别服务商",
        "private fun JSONObject.ddnsText",
        "private fun JSONObject.ddnsFlag",
        'data.optJSONArray("records")',
        '"serviceId", "service_id"',
        '"currentIp", "current_ip"',
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build162 DDNS field compatibility missing: {missing}")


def apply() -> None:
    control = CONTROL.read_text(encoding="utf-8")
    control = patch_card(control)
    control = patch_editor(control)
    CONTROL.write_text(control, encoding="utf-8")
    API.write_text(patch_parser(API.read_text(encoding="utf-8")), encoding="utf-8")
    VERIFIER.write_text(patch_verifier(VERIFIER.read_text(encoding="utf-8")), encoding="utf-8")
    verify()
    print("build162 legacy DDNS fields normalized and editor errors contained")


if __name__ == "__main__":
    apply()
