package com.labprobe.shinyahub;

import android.content.Context;
import android.net.*;
import android.os.Build;
import android.os.CancellationSignal;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Query preferred public resolvers first, then use Android's current network resolver. */
final class HubDns {
    private static final String[] PREFERRED_RESOLVERS={"223.5.5.5","114.114.114.114"};

    static String resolve(Context context,String source,boolean https)throws Exception {
        String domain=HubTxtRecord.domain(source);Exception preferredFailure=null;
        for(String resolver:PREFERRED_RESOLVERS){
            try{
                byte[] answer=queryResolver(context,domain,InetAddress.getByName(resolver));
                return HubTxtRecord.address(HubTxtRecord.records(answer,domain),https);
            }catch(Exception failure){preferredFailure=failure;}
        }
        try{
            byte[] answer=Build.VERSION.SDK_INT>=29?Modern.query(domain):legacy(context,domain);
            return HubTxtRecord.address(HubTxtRecord.records(answer,domain),https);
        }catch(Exception systemFailure){
            if(preferredFailure!=null&&isTimeout(systemFailure)&&!isTimeout(preferredFailure))throw preferredFailure;
            throw systemFailure;
        }
    }

    private static boolean isTimeout(Exception failure){
        String message=failure.getMessage();return message!=null&&message.contains("超时");
    }

    private static byte[] makeQuery(String domain,int id)throws Exception {
        ByteArrayOutputStream query=new ByteArrayOutputStream();query.write(id>>8);query.write(id);
        query.write(new byte[]{1,0,0,1,0,0,0,0,0,0});
        for(String label:domain.split("\\.")){byte[] bytes=label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);query.write(bytes.length);query.write(bytes);}
        query.write(new byte[]{0,0,16,0,1});return query.toByteArray();
    }

    private static Network activeNetwork(Context context){
        ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivity==null)return null;
        if(Build.VERSION.SDK_INT>=23)return connectivity.getActiveNetwork();
        for(Network network:connectivity.getAllNetworks()){
            NetworkInfo info=connectivity.getNetworkInfo(network);if(info!=null&&info.isConnected())return network;
        }
        return null;
    }

    /** Send TXT queries to the requested IPv4 resolver over the active Android network. */
    private static byte[] queryResolver(Context context,String domain,InetAddress server)throws Exception {
        Network active=activeNetwork(context);int id=new java.security.SecureRandom().nextInt(65536);byte[] request=makeQuery(domain,id);
        try(DatagramSocket socket=new DatagramSocket()){
            if(Build.VERSION.SDK_INT>=22&&active!=null)active.bindSocket(socket);
            socket.connect(server,53);socket.setSoTimeout(2500);socket.send(new DatagramPacket(request,request.length));
            byte[] buffer=new byte[65535];DatagramPacket reply=new DatagramPacket(buffer,buffer.length);socket.receive(reply);
            if(reply.getLength()<12||(((buffer[0]&255)<<8)|(buffer[1]&255))!=id)throw new Exception("TXT 域名解析失败，请重试");
            return Arrays.copyOf(buffer,reply.getLength());
        }catch(java.io.IOException failure){throw new Exception("TXT 域名解析超时，请重试",failure);}
    }

    private static final class Modern {
        static byte[] query(String domain)throws Exception {
            for(int attempt=0;attempt<2;attempt++){
                CountDownLatch done=new CountDownLatch(1);byte[][] result={null};Exception[] error={null};CancellationSignal cancellation=new CancellationSignal();
                DnsResolver.getInstance().rawQuery(null,domain,1,16,DnsResolver.FLAG_NO_CACHE_LOOKUP,Runnable::run,cancellation,new DnsResolver.Callback<byte[]>(){
                    public void onAnswer(byte[] answer,int rcode){if(rcode==0)result[0]=answer;else error[0]=new Exception("TXT 域名解析失败，请重试");done.countDown();}
                    public void onError(DnsResolver.DnsException failure){error[0]=new Exception("TXT 域名解析失败，请重试");done.countDown();}
                });
                if(!done.await(2,TimeUnit.SECONDS)){cancellation.cancel();if(attempt==1)throw new Exception("TXT 域名解析超时，请重试");continue;}
                if(error[0]!=null){if(attempt==1)throw error[0];continue;}
                if(result[0]!=null)return result[0];
            }
            throw new Exception("TXT 域名解析失败，请重试");
        }
    }

    private static byte[] legacy(Context context,String domain)throws Exception {
        ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);Network active=activeNetwork(context);
        LinkProperties properties=active==null||connectivity==null?null:connectivity.getLinkProperties(active);
        if(properties==null||properties.getDnsServers().isEmpty())throw new Exception("当前网络没有可用的 DNS");
        int id=new java.security.SecureRandom().nextInt(65536);byte[] bytes=makeQuery(domain,id);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        for(InetAddress server:properties.getDnsServers()){
            long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(remaining<=0)break;
            try(DatagramSocket socket=new DatagramSocket()){
                if(Build.VERSION.SDK_INT>=22)active.bindSocket(socket);socket.connect(server,53);socket.setSoTimeout((int)Math.min(4000,remaining));socket.send(new DatagramPacket(bytes,bytes.length));
                byte[] buffer=new byte[65535];DatagramPacket reply=new DatagramPacket(buffer,buffer.length);socket.receive(reply);
                if(reply.getLength()<12||(((buffer[0]&255)<<8)|(buffer[1]&255))!=id)continue;
                return Arrays.copyOf(buffer,reply.getLength());
            }catch(java.io.IOException ignored){}
        }
        throw new Exception("TXT 域名解析超时，请重试");
    }
}
