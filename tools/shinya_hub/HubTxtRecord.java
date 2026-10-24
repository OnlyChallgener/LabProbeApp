package com.labprobe.shinyahub;

import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded DNS TXT decoding, including compressed names and split strings. */
final class HubTxtRecord {
    static boolean isTxt(String raw){return raw!=null&&raw.trim().toLowerCase(Locale.ROOT).startsWith("txt:");}
    static String domain(String raw) throws Exception {
        String value=raw.trim().replaceFirst("(?i)^txt:[/\\\\]*","");
        if(value.endsWith("/"))value=value.substring(0,value.length()-1);
        if(value.endsWith("."))value=value.substring(0,value.length()-1);
        value=IDN.toASCII(value).toLowerCase(Locale.ROOT);
        if(value.isEmpty()||value.length()>253)throw new Exception("TXT 域名格式无效");
        for(String label:value.split("\\.",-1))if(!label.matches("[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?"))throw new Exception("TXT 域名格式无效");
        return value;
    }
    static String source(String raw) throws Exception{return "txt://"+domain(raw);}
    private static int u16(byte[] b,int p)throws Exception{if(p<0||p+2>b.length)throw new Exception("TXT 解析响应无效");return ((b[p]&255)<<8)|(b[p+1]&255);}
    private static String name(byte[] b,int[] cursor)throws Exception {
        int p=cursor[0],next=-1;StringBuilder result=new StringBuilder();Set<Integer> seen=new HashSet<>();
        for(int steps=0;steps<128;steps++){
            if(p<0||p>=b.length||!seen.add(p))throw new Exception("TXT 解析响应无效");
            int size=b[p++]&255;
            if(size==0){cursor[0]=next<0?p:next;return result.toString().toLowerCase(Locale.ROOT);}
            if((size&192)==192){if(p>=b.length)throw new Exception("TXT 解析响应无效");if(next<0)next=p+1;p=((size&63)<<8)|(b[p]&255);continue;}
            if(size>63||p+size>b.length)throw new Exception("TXT 解析响应无效");
            if(result.length()>0)result.append('.');result.append(new String(b,p,size,StandardCharsets.US_ASCII));p+=size;
            if(result.length()>253)throw new Exception("TXT 解析响应无效");
        }
        throw new Exception("TXT 解析响应无效");
    }
    static List<String> records(byte[] b,String domain)throws Exception {
        if(b.length<12||b.length>65535||(b[2]&128)==0||(b[2]&2)!=0||(b[3]&15)!=0||u16(b,4)!=1)throw new Exception("TXT 域名解析失败，请重试");
        int[] pos={12};if(!name(b,pos).equals(domain)||u16(b,pos[0])!=16||u16(b,pos[0]+2)!=1)throw new Exception("TXT 解析响应无效");pos[0]+=4;
        int count=u16(b,6);if(count>128)throw new Exception("TXT 解析响应过大");
        Map<String,String> aliases=new HashMap<>();Map<String,List<String>> values=new LinkedHashMap<>();
        for(int i=0;i<count;i++){
            String owner=name(b,pos);int type=u16(b,pos[0]),cls=u16(b,pos[0]+2),length=u16(b,pos[0]+8);pos[0]+=10;
            int end=pos[0]+length;if(end>b.length)throw new Exception("TXT 解析响应无效");
            if(cls==1&&type==5){int[] target={pos[0]};String alias=name(b,target);if(target[0]>end)throw new Exception("TXT 解析响应无效");aliases.put(owner,alias);}
            if(cls==1&&type==16){ByteArrayOutputStream text=new ByteArrayOutputStream();int at=pos[0];while(at<end){int size=b[at++]&255;if(at+size>end)throw new Exception("TXT 解析响应无效");text.write(b,at,size);at+=size;}if(text.size()>4096)throw new Exception("TXT 解析响应过大");values.computeIfAbsent(owner,k->new ArrayList<>()).add(new String(text.toByteArray(),StandardCharsets.UTF_8));}
            pos[0]=end;
        }
        List<String> records=new ArrayList<>();Set<String> owners=new HashSet<>();String owner=domain;
        for(int i=0;i<16&&owner!=null&&owners.add(owner);i++){records.addAll(values.getOrDefault(owner,Collections.emptyList()));owner=aliases.get(owner);}
        return records;
    }
    static String address(List<String> records,boolean https)throws Exception {
        Set<String> candidates=new LinkedHashSet<>();
        for(String text:records){String value=text.trim().replaceFirst("(?i)^(hub|url)=","");
            // STUN/DDNS TXT records may label a raw host:port as tcp://. The
            // login screen's HTTPS switch controls TLS for this connection.
            if(value.regionMatches(true,0,"tcp://",0,6))value=value.substring(6);
            if(!value.matches("(?i)(?:https?://)?(?:\\[[0-9a-f:]+\\]|[a-z0-9.-]+)(?::[0-9]{1,5})?/?"))continue;
            try{URI uri=new URI(value.contains("://")?value:(https?"https://":"http://")+value);
                if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()>65535||uri.getPort()==0||uri.getQuery()!=null||uri.getFragment()!=null)continue;
                candidates.add(uri.toString());
            }catch(Exception ignored){}
        }
        if(candidates.isEmpty())throw new Exception("TXT 记录中没有有效的 HUB 地址");
        if(candidates.size()!=1)throw new Exception("TXT 记录包含多个 HUB 地址，请保留一个");
        return candidates.iterator().next();
    }
}
