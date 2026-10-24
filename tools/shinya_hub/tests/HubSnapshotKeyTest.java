package com.labprobe.shinyahub;

public final class HubSnapshotKeyTest {
    public static void main(String[] args)throws Exception {
        String resource="project-a/router-a/penetration";
        String first=HubSnapshotKey.id("hub-a","user-a","cert-a","https://47.104.22.0:1/","old-token",resource);
        if(!first.equals(HubSnapshotKey.id("hub-a","user-a","cert-a","https://36.157.252.49:2/","rotated-token",resource)))throw new AssertionError("Relay/token rotation lost snapshot");
        for(String value:new String[]{HubSnapshotKey.id("hub-b","user-a","cert-a","https://47.104.22.0:1/","old-token",resource),HubSnapshotKey.id("hub-a","user-b","cert-a","https://47.104.22.0:1/","old-token",resource),HubSnapshotKey.id("hub-a","user-a","cert-b","https://47.104.22.0:1/","old-token",resource),HubSnapshotKey.id("hub-a","user-a","cert-a","https://47.104.22.0:1/","old-token","project-b/router-a/penetration")})if(first.equals(value))throw new AssertionError("Snapshot leaked across identity/project");
        if(HubSnapshotKey.legacy("hub-a","user-a","cert-a","https://host:1/","a",resource).equals(HubSnapshotKey.legacy("hub-a","user-a","cert-a","https://host:2/","a",resource)))throw new AssertionError("Legacy migration key changed");
        System.out.println("Snapshot retains relay/token rotations and isolates server/account/certificate/project");
    }
}
