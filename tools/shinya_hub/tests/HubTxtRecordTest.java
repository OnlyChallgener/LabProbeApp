package com.labprobe.shinyahub;
import java.io.*;
import java.util.*;
public class HubTxtRecordTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    interface Case {void run()throws Exception;}
    static void rejected(Case run)throws Exception{try{run.run();throw new AssertionError("accepted invalid DNS/address");}catch(AssertionError e){throw e;}catch(Exception expected){}}
    static void word(ByteArrayOutputStream out,int n){out.write(n>>8);out.write(n);}
    static byte[] name(String value)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(String label:value.split("\\.")){byte[] part=label.getBytes("US-ASCII");out.write(part.length);out.write(part);}out.write(0);return out.toByteArray();}
    static byte[] txt(String... parts)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(String part:parts){byte[] bytes=part.getBytes("UTF-8");out.write(bytes.length);out.write(bytes);}return out.toByteArray();}
    static byte[] rr(String owner,int type,byte[] bytes)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(name(owner));word(out,type);word(out,1);out.write(new byte[]{0,0,0,30});word(out,bytes.length);out.write(bytes);return out.toByteArray();}
    static byte[] packet(byte[]... records)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{0,1,(byte)0x81,(byte)0x80,0,1});word(out,records.length);out.write(new byte[4]);out.write(name("hub.example"));word(out,16);word(out,1);for(byte[] r:records)out.write(r);return out.toByteArray();}
    public static void main(String[] args)throws Exception{
        check(HubTxtRecord.source("txt:\\Hub.Example./").equals("txt://hub.example"));
        check(HubTxtRecord.domain("txt://_hub.example").equals("_hub.example"));
        rejected(()->HubTxtRecord.domain("txt://hub.example:5555"));rejected(()->HubTxtRecord.domain("txt://hub.example/path"));
        byte[] split=packet(rr("hub.example",16,txt("https://47.104.22.0:","63253")));
        check(HubTxtRecord.address(HubTxtRecord.records(split,"hub.example"),false).equals("https://47.104.22.0:63253"));
        check(HubTxtRecord.address(Arrays.asList("tcp://36.157.252.49:5773"),true).equals("https://36.157.252.49:5773"));
        check(HubTxtRecord.address(Arrays.asList("hub=tcp://xy.net86.dynv6.net:5773"),false).equals("http://xy.net86.dynv6.net:5773"));
        byte[] cname=packet(rr("hub.example",5,name("target.example")),rr("foreign.example",16,txt("http://evil.example:99")),rr("target.example",16,txt("192.168.5.46:58543")));
        check(HubTxtRecord.address(HubTxtRecord.records(cname,"hub.example"),false).equals("http://192.168.5.46:58543"));
        rejected(()->HubTxtRecord.address(HubTxtRecord.records(packet(rr("foreign.example",16,txt("http://evil.example"))),"hub.example"),false));
        check(HubTxtRecord.address(Arrays.asList("v=spf1 -all","hub=https://hub.example:58544"),false).equals("https://hub.example:58544"));
        check(HubTxtRecord.address(Arrays.asList("[2001:db8::1]:58544"),true).equals("https://[2001:db8::1]:58544"));
        rejected(()->HubTxtRecord.address(Arrays.asList("http://a.example","http://b.example"),false));
        rejected(()->HubTxtRecord.address(Arrays.asList("https://user:pass@host.example","https://host.example:99999","https://host.example/path"),false));
        rejected(()->HubTxtRecord.records(Arrays.copyOf(split,split.length-1),"hub.example"));
        byte[] loop=split.clone();loop[12]=(byte)0xc0;loop[13]=12;rejected(()->HubTxtRecord.records(loop,"hub.example"));
        System.out.println("TXT records: split values, CNAME scope, IPv4/IPv6, ambiguous addresses and malformed replies verified");
    }
}
