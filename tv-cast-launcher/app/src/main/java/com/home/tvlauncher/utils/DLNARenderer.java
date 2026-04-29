package com.home.tvlauncher.utils;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.home.tvlauncher.LauncherActivity;
import com.home.tvlauncher.VideoPlayerActivity;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

/**
 * 轻量级 DLNA MediaRenderer 实现
 * 使用 SSDP 多播发现 + NanoHTTPD 处理 UPnP SOAP 请求
 */
public class DLNARenderer {

    private static final String TAG = "DLNARenderer";

    private Context context;
    private String deviceName;
    private String uuid;
    private String localIp;
    private int httpPort;

    private SSDPServer ssdpServer;
    private UPnPHttpServer httpServer;

    private String currentURI = "";
    private String currentTitle = "";
    private String transportState = "NO_MEDIA_PRESENT";

    // 播放进度缓存（由 VideoPlayerActivity 定期更新，避免跨线程调用 MediaPlayer）
    private volatile long cachedPosition = 0;
    private volatile long cachedDuration = 0;

    private static DLNARenderer instance;

    public DLNARenderer(Context context, String deviceName) {
        this.context = context.getApplicationContext();
        this.deviceName = deviceName;
        this.uuid = "uuid:" + UUID.nameUUIDFromBytes("HomeTVLauncher".getBytes()).toString();
        this.localIp = NetworkUtils.getIPAddress(context);
        instance = this;
    }

    public static DLNARenderer getInstance() {
        return instance;
    }

    /** 由 VideoPlayerActivity 调用，更新播放进度缓存 */
    public void updatePosition(long position, long duration) {
        this.cachedPosition = position;
        this.cachedDuration = duration;
    }

    /** 由 VideoPlayerActivity 调用，通知播放状态变化 */
    public void setTransportState(String state) {
        this.transportState = state;
        Log.d(TAG, "transportState -> " + state);
    }

    public void start() throws IOException {
        // 找一个可用端口
        ServerSocket ss = new ServerSocket(0);
        httpPort = ss.getLocalPort();
        ss.close();

        // 启动 HTTP 服务器
        httpServer = new UPnPHttpServer(httpPort);
        httpServer.start();
        Log.d(TAG, "UPnP HTTP 服务器启动在端口: " + httpPort);

        // 启动 SSDP 发现服务
        ssdpServer = new SSDPServer();
        ssdpServer.start();
        Log.d(TAG, "SSDP 发现服务已启动");
    }

    public void stop() {
        if (ssdpServer != null) {
            ssdpServer.stopServer();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    // ========== SSDP 多播发现服务 ==========

    private class SSDPServer extends Thread {
        private static final String SSDP_ADDR = "239.255.255.250";
        private static final int SSDP_PORT = 1900;
        private volatile boolean running = true;
        private MulticastSocket socket;

        @Override
        public void run() {
            try {
                InetAddress group = InetAddress.getByName(SSDP_ADDR);
                socket = new MulticastSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new java.net.InetSocketAddress(SSDP_PORT));
                socket.joinGroup(group);

                Log.d(TAG, "SSDP socket 绑定成功，端口: " + SSDP_PORT);

                // 发送 SSDP ALIVE 通知（发 3 次，提高可靠性）
                for (int i = 0; i < 3; i++) {
                    sendAlive();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }

                // 启动定期 ALIVE 发送线程（每 60 秒）
                final Thread aliveThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (running) {
                            try {
                                Thread.sleep(60000);
                                if (running) sendAlive();
                            } catch (InterruptedException ignored) {}
                        }
                    }
                });
                aliveThread.setDaemon(true);
                aliveThread.start();

                byte[] buf = new byte[2048];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(packet);
                        String msg = new String(packet.getData(), 0, packet.getLength());
                        if (msg.startsWith("M-SEARCH")) {
                            handleMSearch(packet.getAddress(), packet.getPort(), msg);
                        }
                    } catch (IOException e) {
                        if (running) {
                            Log.e(TAG, "SSDP receive error", e);
                        }
                    }
                }

                aliveThread.interrupt();
                socket.leaveGroup(group);
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "SSDP server error: " + e.getMessage(), e);
            }
        }

        private void sendAlive() {
            try {
                String notify = "NOTIFY * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "CACHE-CONTROL: max-age=1800\r\n" +
                        "LOCATION: http://" + localIp + ":" + httpPort + "/description.xml\r\n" +
                        "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                        "NTS: ssdp:alive\r\n" +
                        "SERVER: Linux/3.0 UPnP/1.0 HomeTVLauncher/1.0\r\n" +
                        "USN: " + uuid + "::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                        "\r\n";

                byte[] data = notify.getBytes();
                InetAddress group = InetAddress.getByName(SSDP_ADDR);
                DatagramPacket packet = new DatagramPacket(data, data.length, group, SSDP_PORT);
                socket.send(packet);

                // 也发送 root device 通知
                String rootNotify = "NOTIFY * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "CACHE-CONTROL: max-age=1800\r\n" +
                        "LOCATION: http://" + localIp + ":" + httpPort + "/description.xml\r\n" +
                        "NT: upnp:rootdevice\r\n" +
                        "NTS: ssdp:alive\r\n" +
                        "SERVER: Linux/3.0 UPnP/1.0 HomeTVLauncher/1.0\r\n" +
                        "USN: " + uuid + "::upnp:rootdevice\r\n" +
                        "\r\n";
                data = rootNotify.getBytes();
                packet = new DatagramPacket(data, data.length, group, SSDP_PORT);
                socket.send(packet);

                Log.d(TAG, "SSDP ALIVE 已发送");
            } catch (Exception e) {
                Log.e(TAG, "发送 SSDP ALIVE 失败", e);
            }
        }

        private void handleMSearch(InetAddress addr, int port, String msg) {
            // 检查是否搜索 MediaRenderer 或所有设备
            if (msg.contains("urn:schemas-upnp-org:device:MediaRenderer") ||
                    msg.contains("ssdp:all") ||
                    msg.contains("upnp:rootdevice") ||
                    msg.contains("urn:schemas-upnp-org:service:AVTransport")) {

                String response = "HTTP/1.1 200 OK\r\n" +
                        "CACHE-CONTROL: max-age=1800\r\n" +
                        "LOCATION: http://" + localIp + ":" + httpPort + "/description.xml\r\n" +
                        "SERVER: Linux/3.0 UPnP/1.0 HomeTVLauncher/1.0\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                        "USN: " + uuid + "::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                        "EXT:\r\n" +
                        "\r\n";

                try {
                    byte[] data = response.getBytes();
                    DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
                    socket.send(packet);
                    Log.d(TAG, "回复 M-SEARCH 到 " + addr.getHostAddress() + ":" + port);
                } catch (Exception e) {
                    Log.e(TAG, "回复 M-SEARCH 失败", e);
                }
            }
        }

        public void stopServer() {
            running = false;
            if (socket != null) {
                socket.close();
            }
        }
    }

    // ========== UPnP HTTP 服务器 ==========

    private class UPnPHttpServer extends NanoHTTPD {

        public UPnPHttpServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            Method method = session.getMethod();

            Log.d(TAG, "HTTP 请求: " + method + " " + uri);

            if ("/description.xml".equals(uri)) {
                return serveDeviceDescription();
            } else if ("/AVTransport/scpd.xml".equals(uri)) {
                return serveAVTransportSCPD();
            } else if ("/RenderingControl/scpd.xml".equals(uri)) {
                return serveRenderingControlSCPD();
            } else if ("/AVTransport/control".equals(uri) && Method.POST.equals(method)) {
                return handleAVTransportControl(session);
            } else if ("/RenderingControl/control".equals(uri) && Method.POST.equals(method)) {
                return handleRenderingControl(session);
            } else if ("/AVTransport/event".equals(uri)) {
                // 处理事件订阅（SUBSCRIBE 请求）
                Log.d(TAG, "AVTransport event 请求, method=" + method);
                Response resp = newFixedLengthResponse(Response.Status.OK, "text/xml", "");
                resp.addHeader("SID", "uuid:" + java.util.UUID.randomUUID().toString());
                resp.addHeader("TIMEOUT", "Second-1800");
                return resp;
            } else if ("/RenderingControl/event".equals(uri)) {
                Log.d(TAG, "RenderingControl event 请求, method=" + method);
                Response resp = newFixedLengthResponse(Response.Status.OK, "text/xml", "");
                resp.addHeader("SID", "uuid:" + java.util.UUID.randomUUID().toString());
                resp.addHeader("TIMEOUT", "Second-1800");
                return resp;
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }

        private Response serveDeviceDescription() {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n" +
                    "  <specVersion><major>1</major><minor>0</minor></specVersion>\n" +
                    "  <device>\n" +
                    "    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\n" +
                    "    <friendlyName>" + deviceName + "</friendlyName>\n" +
                    "    <manufacturer>HomeTVLauncher</manufacturer>\n" +
                    "    <modelName>HomeTVLauncher</modelName>\n" +
                    "    <modelDescription>家庭电视投屏</modelDescription>\n" +
                    "    <UDN>" + uuid + "</UDN>\n" +
                    "    <serviceList>\n" +
                    "      <service>\n" +
                    "        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>\n" +
                    "        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>\n" +
                    "        <SCPDURL>/AVTransport/scpd.xml</SCPDURL>\n" +
                    "        <controlURL>/AVTransport/control</controlURL>\n" +
                    "        <eventSubURL>/AVTransport/event</eventSubURL>\n" +
                    "      </service>\n" +
                    "      <service>\n" +
                    "        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>\n" +
                    "        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>\n" +
                    "        <SCPDURL>/RenderingControl/scpd.xml</SCPDURL>\n" +
                    "        <controlURL>/RenderingControl/control</controlURL>\n" +
                    "        <eventSubURL>/RenderingControl/event</eventSubURL>\n" +
                    "      </service>\n" +
                    "    </serviceList>\n" +
                    "  </device>\n" +
                    "</root>";
            return newFixedLengthResponse(Response.Status.OK, "text/xml", xml);
        }

        private Response serveAVTransportSCPD() {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n" +
                    "  <specVersion><major>1</major><minor>0</minor></specVersion>\n" +
                    "  <actionList>\n" +
                    "    <action><name>SetAVTransportURI</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>Play</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>Pause</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>Stop</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>Seek</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>\n" +
                    "      <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>GetTransportInfo</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>GetPositionInfo</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>\n" +
                    "      <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>\n" +
                    "      <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>\n" +
                    "      <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>\n" +
                    "      <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>\n" +
                    "      <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>\n" +
                    "      <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>\n" +
                    "      <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>GetMediaInfo</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>\n" +
                    "      <argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>\n" +
                    "      <argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>\n" +
                    "      <argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>\n" +
                    "      <argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument>\n" +
                    "      <argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument>\n" +
                    "      <argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "  </actionList>\n" +
                    "  <serviceStateTable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"yes\"><name>TransportState</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>TransportStatus</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>PlaybackStorageMedium</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>RecordStorageMedium</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>RecordMediumWriteStatus</name><dataType>string</dataType></stateVariable>\n" +
                    "  </serviceStateTable>\n" +
                    "</scpd>";
            return newFixedLengthResponse(Response.Status.OK, "text/xml", xml);
        }

        private Response serveRenderingControlSCPD() {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n" +
                    "  <specVersion><major>1</major><minor>0</minor></specVersion>\n" +
                    "  <actionList>\n" +
                    "    <action><name>GetVolume</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>SetVolume</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>\n" +
                    "      <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>GetMute</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>\n" +
                    "      <argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "    <action><name>SetMute</name><argumentList>\n" +
                    "      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>\n" +
                    "      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>\n" +
                    "      <argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>\n" +
                    "    </argumentList></action>\n" +
                    "  </actionList>\n" +
                    "  <serviceStateTable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>\n" +
                    "    <stateVariable sendEvents=\"no\"><name>Mute</name><dataType>boolean</dataType></stateVariable>\n" +
                    "  </serviceStateTable>\n" +
                    "</scpd>";
            return newFixedLengthResponse(Response.Status.OK, "text/xml", xml);
        }

        private Response handleAVTransportControl(IHTTPSession session) {
            try {
                // 读取 POST body
                Map<String, String> body = new java.util.HashMap<String, String>();
                session.parseBody(body);
                String postData = body.get("postData");
                if (postData == null) postData = "";

                String soapAction = session.getHeaders().get("soapaction");
                if (soapAction == null) soapAction = "";
                soapAction = soapAction.replace("\"", "");

                // 提取 action 名称（取 # 后面的部分）
                String actionName = soapAction;
                int hashIdx = soapAction.lastIndexOf("#");
                if (hashIdx >= 0) {
                    actionName = soapAction.substring(hashIdx + 1);
                }

                Log.d(TAG, "AVTransport Action: " + actionName + " (full: " + soapAction + ")");

                // 用精确匹配代替 contains，避免误匹配
                if ("SetAVTransportURI".equals(actionName)) {
                    return handleSetAVTransportURI(postData);
                } else if ("Play".equals(actionName)) {
                    return handlePlay();
                } else if ("Pause".equals(actionName)) {
                    return handlePause();
                } else if ("Stop".equals(actionName)) {
                    return handleStop();
                } else if ("Seek".equals(actionName)) {
                    return handleSeek(postData);
                } else if ("GetTransportInfo".equals(actionName)) {
                    return handleGetTransportInfo();
                } else if ("GetPositionInfo".equals(actionName)) {
                    return handleGetPositionInfo();
                } else if ("GetMediaInfo".equals(actionName)) {
                    return handleGetMediaInfo();
                }

                Log.w(TAG, "未处理的 AVTransport action: " + actionName);
                return soapResponseAVT(actionName, "");
            } catch (Exception e) {
                Log.e(TAG, "处理 AVTransport 请求失败", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml", "");
            }
        }

        private Response handleSetAVTransportURI(String postData) {
            currentURI = extractXmlValue(postData, "CurrentURI");
            // XML 解码 URI（B 站可能对 URL 中的 & 等字符做了 XML 编码）
            currentURI = currentURI.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
            String metaData = extractXmlValue(postData, "CurrentURIMetaData");
            // 元数据可能是 XML 编码的
            metaData = metaData.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");

            currentTitle = "投屏视频";
            if (metaData != null && metaData.contains("dc:title")) {
                String title = extractXmlValue(metaData, "dc:title");
                if (title != null && !title.isEmpty()) {
                    currentTitle = title;
                }
            }

            transportState = "STOPPED";
            Log.d(TAG, "SetAVTransportURI: " + currentURI);
            Log.d(TAG, "Title: " + currentTitle);

            // 通知桌面更新状态
            Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
            statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "准备播放: " + currentTitle);
            context.sendBroadcast(statusIntent);

            return soapResponseAVT("SetAVTransportURI", "");
        }

        private Response handlePlay() {
            Log.d(TAG, "Play: " + currentURI);
            // 先设为 TRANSITIONING，给视频准备时间
            transportState = "TRANSITIONING";

            // 直接用 Intent 启动 VideoPlayerActivity
            Intent videoIntent = new Intent(context, VideoPlayerActivity.class);
            videoIntent.putExtra(LauncherActivity.EXTRA_VIDEO_URL, currentURI);
            videoIntent.putExtra(LauncherActivity.EXTRA_VIDEO_TITLE, currentTitle);
            videoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(videoIntent);

            // 延迟 2 秒后设为 PLAYING
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    if ("TRANSITIONING".equals(transportState)) {
                        transportState = "PLAYING";
                        Log.d(TAG, "状态切换为 PLAYING");
                    }
                }
            }).start();

            Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
            statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "正在播放: " + currentTitle);
            context.sendBroadcast(statusIntent);

            return soapResponseAVT("Play", "");
        }

        private Response handlePause() {
            Log.d(TAG, "Pause");
            transportState = "PAUSED_PLAYBACK";

            Intent controlIntent = new Intent(VideoPlayerActivity.ACTION_CONTROL);
            controlIntent.putExtra(VideoPlayerActivity.EXTRA_COMMAND, VideoPlayerActivity.CMD_PAUSE);
            context.sendBroadcast(controlIntent);

            Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
            statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "已暂停");
            context.sendBroadcast(statusIntent);

            return soapResponseAVT("Pause", "");
        }

        private Response handleStop() {
            Log.d(TAG, "Stop");
            transportState = "STOPPED";

            Intent controlIntent = new Intent(VideoPlayerActivity.ACTION_CONTROL);
            controlIntent.putExtra(VideoPlayerActivity.EXTRA_COMMAND, VideoPlayerActivity.CMD_STOP);
            context.sendBroadcast(controlIntent);

            Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
            statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "等待投屏...");
            context.sendBroadcast(statusIntent);

            return soapResponseAVT("Stop", "");
        }

        private Response handleSeek(String postData) {
            String target = extractXmlValue(postData, "Target");
            Log.d(TAG, "Seek: " + target);

            long position = parseTimeToMillis(target);
            Intent controlIntent = new Intent(VideoPlayerActivity.ACTION_CONTROL);
            controlIntent.putExtra(VideoPlayerActivity.EXTRA_COMMAND, VideoPlayerActivity.CMD_SEEK);
            controlIntent.putExtra(VideoPlayerActivity.EXTRA_SEEK_POSITION, position);
            context.sendBroadcast(controlIntent);

            return soapResponseAVT("Seek", "");
        }

        private Response handleGetTransportInfo() {
            Log.d(TAG, "GetTransportInfo -> " + transportState);
            String body = "<CurrentTransportState>" + transportState + "</CurrentTransportState>\n" +
                    "<CurrentTransportStatus>OK</CurrentTransportStatus>\n" +
                    "<CurrentSpeed>1</CurrentSpeed>";
            return soapResponseAVT("GetTransportInfo", body);
        }

        private Response handleGetPositionInfo() {
            String relTime = formatMillisToTime(cachedPosition);
            String duration = formatMillisToTime(cachedDuration);
            Log.d(TAG, "GetPositionInfo -> pos=" + relTime + " dur=" + duration);

            String body = "<Track>1</Track>\n" +
                    "<TrackDuration>" + duration + "</TrackDuration>\n" +
                    "<TrackMetaData></TrackMetaData>\n" +
                    "<TrackURI>" + escapeXml(currentURI) + "</TrackURI>\n" +
                    "<RelTime>" + relTime + "</RelTime>\n" +
                    "<AbsTime>" + relTime + "</AbsTime>\n" +
                    "<RelCount>2147483647</RelCount>\n" +
                    "<AbsCount>2147483647</AbsCount>";
            return soapResponseAVT("GetPositionInfo", body);
        }

        private Response handleGetMediaInfo() {
            String body = "<NrTracks>1</NrTracks>\n" +
                    "<MediaDuration>00:00:00</MediaDuration>\n" +
                    "<CurrentURI>" + escapeXml(currentURI) + "</CurrentURI>\n" +
                    "<CurrentURIMetaData></CurrentURIMetaData>\n" +
                    "<NextURI></NextURI>\n" +
                    "<NextURIMetaData></NextURIMetaData>\n" +
                    "<PlayMedium>NETWORK</PlayMedium>\n" +
                    "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>\n" +
                    "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
            return soapResponseAVT("GetMediaInfo", body);
        }

        private Response handleRenderingControl(IHTTPSession session) {
            try {
                Map<String, String> body = new java.util.HashMap<String, String>();
                session.parseBody(body);
                String soapAction = session.getHeaders().get("soapaction");
                if (soapAction == null) soapAction = "";
                soapAction = soapAction.replace("\"", "");

                String actionName = soapAction;
                int hashIdx = soapAction.lastIndexOf("#");
                if (hashIdx >= 0) {
                    actionName = soapAction.substring(hashIdx + 1);
                }
                Log.d(TAG, "RenderingControl Action: " + actionName);

                if ("GetVolume".equals(actionName)) {
                    return soapResponseRC("GetVolume", "<CurrentVolume>50</CurrentVolume>");
                } else if ("GetMute".equals(actionName)) {
                    return soapResponseRC("GetMute", "<CurrentMute>0</CurrentMute>");
                } else if ("SetVolume".equals(actionName)) {
                    return soapResponseRC("SetVolume", "");
                } else if ("SetMute".equals(actionName)) {
                    return soapResponseRC("SetMute", "");
                }

                return soapResponseRC(actionName, "");
            } catch (Exception e) {
                Log.e(TAG, "处理 RenderingControl 请求失败", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml", "");
            }
        }

        /** AVTransport SOAP 响应 */
        private Response soapResponseAVT(String actionName, String body) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                    "  <s:Body>\n" +
                    "    <u:" + actionName + "Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    body + "\n" +
                    "    </u:" + actionName + "Response>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>";
            return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"", xml);
        }

        /** RenderingControl SOAP 响应 */
        private Response soapResponseRC(String actionName, String body) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                    "  <s:Body>\n" +
                    "    <u:" + actionName + "Response xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">\n" +
                    body + "\n" +
                    "    </u:" + actionName + "Response>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>";
            return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"", xml);
        }
    }

    // ========== 工具方法 ==========

    private static String extractXmlValue(String xml, String tag) {
        if (xml == null) return "";
        // 尝试带命名空间的标签
        int start = xml.indexOf("<" + tag + ">");
        if (start == -1) {
            start = xml.indexOf("<" + tag + " ");
            if (start != -1) {
                start = xml.indexOf(">", start);
                if (start != -1) start++;
            }
        } else {
            start += tag.length() + 2;
        }
        if (start == -1) return "";

        int end = xml.indexOf("</" + tag + ">", start);
        if (end == -1) return "";

        return xml.substring(start, end).trim();
    }

    private static long parseTimeToMillis(String time) {
        if (time == null) return 0;
        try {
            String[] parts = time.split(":");
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            // 处理可能的小数秒
            double seconds = Double.parseDouble(parts[2]);
            return (long) ((hours * 3600 + minutes * 60 + seconds) * 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatMillisToTime(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
