package com.mocyx.basic_client;


import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.IPPROTO_TCP;

import android.app.PendingIntent;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.mocyx.basic_client.bio.BioTcpHandler;
import com.mocyx.basic_client.bio.BioUdpHandler;
import com.mocyx.basic_client.config.Config;
import com.mocyx.basic_client.protocol.tcpip.Packet;
import com.mocyx.basic_client.util.ByteBufferPool;

import org.greenrobot.eventbus.EventBus;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalVPNService extends VpnService {
    private static final String TAG = LocalVPNService.class.getSimpleName();
    private static final String VPN_ADDRESS = "10.0.0.2"; // Only IPv4 support for now
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything

    private ParcelFileDescriptor vpnInterface = null;

    private PendingIntent pendingIntent;

    private BlockingQueue<Packet> deviceToNetworkUDPQueue;
    private BlockingQueue<Packet> deviceToNetworkTCPQueue;
    private BlockingQueue<ByteBuffer> networkToDeviceQueue;
    private ExecutorService executorService;
    private static ConnectivityManager connectivityManager;
    private static PackageManager packageManager;

    @Override
    public void onCreate() {
        super.onCreate();
        setupVPN();
        deviceToNetworkUDPQueue = new ArrayBlockingQueue<Packet>(1000);
        deviceToNetworkTCPQueue = new ArrayBlockingQueue<Packet>(1000);
        networkToDeviceQueue = new ArrayBlockingQueue<>(1000);

        executorService = Executors.newFixedThreadPool(10);
        executorService.submit(new BioUdpHandler(deviceToNetworkUDPQueue, networkToDeviceQueue, this));
        executorService.submit(new BioTcpHandler(deviceToNetworkTCPQueue, networkToDeviceQueue, this));
        //executorService.submit(new NioSingleThreadTcpHandler(deviceToNetworkTCPQueue, networkToDeviceQueue, this));

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        packageManager = getPackageManager();

        executorService.submit(new VPNRunnable(vpnInterface.getFileDescriptor(),
                deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));

        Log.i(TAG, "Started");
    }

    private void setupVPN() {
        try {
            if (vpnInterface == null) {
                Builder builder = new Builder();
                builder.addAddress(VPN_ADDRESS, 32);
                builder.addRoute(VPN_ROUTE, 0);
                builder.addDnsServer(Config.dns);
                if (Config.testLocal) {
                    builder.addAllowedApplication("com.mocyx.basic_client");
                }
                vpnInterface = builder.setSession(getString(R.string.app_name)).setConfigureIntent(pendingIntent).establish();
            }
        } catch (Exception e) {
            Log.e(TAG, "error", e);
            System.exit(0);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdownNow();
        cleanup();
        Log.i(TAG, "Stopped");
    }

    private void cleanup() {
        deviceToNetworkTCPQueue = null;
        deviceToNetworkUDPQueue = null;
        networkToDeviceQueue = null;
        closeResources(vpnInterface);
    }

    // TODO: Move this to a "utils" class for reuse
    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private static class VPNRunnable implements Runnable {
        private static final String TAG = VPNRunnable.class.getSimpleName();

        private FileDescriptor vpnFileDescriptor;

        private BlockingQueue<Packet> deviceToNetworkUDPQueue;
        private BlockingQueue<Packet> deviceToNetworkTCPQueue;
        private BlockingQueue<ByteBuffer> networkToDeviceQueue;

        public VPNRunnable(FileDescriptor vpnFileDescriptor,
                           BlockingQueue<Packet> deviceToNetworkUDPQueue,
                           BlockingQueue<Packet> deviceToNetworkTCPQueue,
                           BlockingQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }


        static class WriteVpnThread implements Runnable {
            FileChannel vpnOutput;
            private BlockingQueue<ByteBuffer> networkToDeviceQueue;

            WriteVpnThread(FileChannel vpnOutput, BlockingQueue<ByteBuffer> networkToDeviceQueue) {
                this.vpnOutput = vpnOutput;
                this.networkToDeviceQueue = networkToDeviceQueue;
            }

            @Override
            public void run() {
                while (true) {
                    try {
                        ByteBuffer bufferFromNetwork = networkToDeviceQueue.take();
                        bufferFromNetwork.flip();
                        Packet packet = new Packet(bufferFromNetwork.duplicate());
                        postPacket(packet, true);
                        while (bufferFromNetwork.hasRemaining()) {
                            int w = vpnOutput.write(bufferFromNetwork);
                        }
                    } catch (Exception e) {
                        Log.i(TAG, "WriteVpnThread fail", e);
                    }
                }

            }
        }

        @Override
        public void run() {
            Log.i(TAG, "Started");
            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();
            Thread t = new Thread(new WriteVpnThread(vpnOutput, networkToDeviceQueue));
            t.start();
            try {
                ByteBuffer bufferToNetwork = null;
                while (!Thread.interrupted()) {
                    bufferToNetwork = ByteBufferPool.acquire();
                    int readBytes = vpnInput.read(bufferToNetwork);

                    if (readBytes > 0) {
                        bufferToNetwork.flip();
                        Packet packet = new Packet(bufferToNetwork);

                        postPacket(packet, false);

                        if (packet.isUDP()) {
                            deviceToNetworkUDPQueue.offer(packet);
                        } else if (packet.isTCP()) {
                            //logPayloadSentTCP(packet);
                            deviceToNetworkTCPQueue.offer(packet);
                        } else {
                            Log.w(TAG, String.format("Unknown packet protocol type %d", packet.ip4Header.protocolNum));
                        }
                    } else {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, e.toString(), e);
            } finally {
                closeResources(vpnInput, vpnOutput);
            }
        }

        private static void postPacket(Packet packet, boolean is_in) throws UnknownHostException {
            StringBuilder sb = new StringBuilder("PACKET: \n");
            sb.append("package: ").append(getPackageName(packet, is_in)).append("\n");
            sb.append("dir: ").append(is_in ? "n2d" : "d2n").append("\n");
            sb.append("protocol: ");
            if(packet.isDNS()) {
                sb.append("DNS ");
            } else if(packet.isHTTPS()) {
                sb.append("HTTPS ");
            } else if(packet.isHTTP()) {
                sb.append("HTTP ");
            } else if (packet.isUDP()) {
                sb.append("UDP ");
            } else if (packet.isTCP()) {
                sb.append("TCP ");
            } else {
                sb.append("unknown ");
            }
            sb.append("\n");
            sb.append("id: ").append(packet.packId).append("\n");
            sb.append("source: ").append(packet.ip4Header.sourceAddress.toString()).append("\n");
            sb.append("destination: ").append(packet.ip4Header.destinationAddress);

            if(packet.isDNS() && !is_in) {
                if(packet.backingBuffer != null && packet.backingBuffer.remaining() > 0) {
                    try {
                        ByteBuffer copiedBuffer = packet.backingBuffer.duplicate();
                        byte[] data = new byte[copiedBuffer.remaining()];
                        copiedBuffer.get(data);
                        copiedBuffer.flip();
                        String packetData = new String(data);
                        packetData = packetData.replaceAll("[^a-zA-Z0-9]", "");
                        sb.append("\n").append("DNS payload url: ").append(packetData);
                    } catch(Exception e) {
                        Log.d("Problem", "This is the problem " + e);
                    }
                }
            }

            if(packet.isHTTP) {
                if(packet.backingBuffer != null && packet.backingBuffer.remaining() > 0) {
                    ByteBuffer copiedBuffer = packet.backingBuffer.duplicate();
                    byte[] data = new byte[copiedBuffer.remaining()];
                    copiedBuffer.get(data);
                    copiedBuffer.flip();
                    String packetData = new String(data);
                    sb.append("\n").append("HTTP payload: ").append(packetData);
                }
            }

            EventBus.getDefault().post(sb.toString());
        }

        private static String getPackageName(Packet packet, boolean is_in) {
            InetSocketAddress remoteInetSocketAddress = null;
            InetSocketAddress localInetSocketAddress = null;

            if (packet.isTCP) {
                remoteInetSocketAddress = new InetSocketAddress(packet.ip4Header.destinationAddress, packet.tcpHeader.destinationPort);
                localInetSocketAddress = new InetSocketAddress(packet.ip4Header.sourceAddress, packet.tcpHeader.sourcePort);
            } else if (packet.isUDP) {
                remoteInetSocketAddress = new InetSocketAddress(packet.ip4Header.destinationAddress, packet.udpHeader.destinationPort);
                localInetSocketAddress = new InetSocketAddress(packet.ip4Header.sourceAddress, packet.udpHeader.sourcePort);
            }

            int uid = INVALID_UID;
            if (packet.isUDP || packet.isTCP) {
                if (is_in)
                    uid = connectivityManager.getConnectionOwnerUid(IPPROTO_TCP, remoteInetSocketAddress, localInetSocketAddress);
                else
                    uid = connectivityManager.getConnectionOwnerUid(IPPROTO_TCP, localInetSocketAddress, remoteInetSocketAddress);
            }

            Log.i(TAG, "UID: " + uid);

            String[] packages = packageManager.getPackagesForUid(uid);

            String name;
            if (packages != null && packages.length >= 1) {
                name = packages[0];
            } else {
                name = "unknown";
            }
            Log.i(TAG, name);
            return name;
        }

        private static void logPayloadSentTCP(Packet packet) {
            try{
                //byte[] data = new byte[packet.backingBuffer.remaining()];
                //packet.backingBuffer.get(data);
                //String packetData = new String(data);
                Log.i("VPN_TCP", "Sent TCP packet with id " + packet.packId + " from port: " + packet.tcpHeader.sourcePort
                        + " to address/port: " + packet.ip4Header.destinationAddress + "/" + packet.tcpHeader.destinationPort
                        + ((packet.tcpHeader.destinationPort == 443) ? " (https) " : "") + " with payload: ");// + packetData);
            }
            catch (Exception e)
            {

            }
        }


        private static void logPayloadRecvdTCP(ByteBuffer bufferFromNetwork) {

            try{
                Packet packet = new Packet(bufferFromNetwork);
                Log.i("VPN_TCP", "Recieved TCP packet with id " + packet.packId + " to port: " + packet.tcpHeader.destinationPort
                        + " from address/port: " + packet.ip4Header.sourceAddress + "/" + packet.tcpHeader.sourcePort
                        + ((packet.tcpHeader.sourcePort == 443) ? " (https) " : "") + " with payload: ");
            }
            catch (Exception e)
            {

            }
        }

    }
}

