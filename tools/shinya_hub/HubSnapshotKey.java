package com.labprobe.shinyahub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticated server/account identity survives changing relay endpoints. */
final class HubSnapshotKey {
    static String hash(String scope,String resource)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((scope+"\n"+resource).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b&255));return out.toString();
    }
    static String id(String hub,String account,String pin,String base,String token,String resource)throws Exception {
        return hub.isEmpty()||account.isEmpty()?legacy(hub,account,pin,base,token,resource):hash("hub-snapshot-v2\n"+hub+"/"+account+"\n"+pin,resource);
    }
    static String legacy(String hub,String account,String pin,String base,String token,String resource)throws Exception {
        return hash(base+"\n"+(hub.isEmpty()?token:hub+"/"+account)+"\n"+pin,resource);
    }
}
