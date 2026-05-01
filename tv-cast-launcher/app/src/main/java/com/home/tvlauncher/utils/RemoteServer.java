package com.home.tvlauncher.utils;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.home.tvlauncher.LauncherActivity;

import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 手机遥控 HTTP 服务器
 * 手机扫描二维码后打开网页，输入 URL 提交后电视播放该链接
 */
public class RemoteServer extends NanoHTTPD {

    private static final String TAG = "RemoteServer";
    private static final int PORT = 8899;

    private Context context;
    private static RemoteServer instance;

    public RemoteServer(Context context) {
        super(PORT);
        this.context = context.getApplicationContext();
        instance = this;
    }

    public static RemoteServer getInstance() {
        return instance;
    }

    public static int getPort() {
        return PORT;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Log.d(TAG, "请求: " + method + " " + uri);

        if ("/remote".equals(uri) && Method.GET.equals(method)) {
            return serveRemotePage();
        } else if ("/play".equals(uri) && Method.POST.equals(method)) {
            return handlePlay(session);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
    }

    /** 手机端遥控页面 */
    private Response serveRemotePage() {
        String html = "<!DOCTYPE html>\n"
            + "<html lang=\"zh\">\n"
            + "<head>\n"
            + "<meta charset=\"UTF-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\">\n"
            + "<title>电视遥控</title>\n"
            + "<style>\n"
            + "*{margin:0;padding:0;box-sizing:border-box}\n"
            + "body{font-family:-apple-system,system-ui,sans-serif;background:#000;color:#fff;"
            + "min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;"
            + "padding:24px}\n"
            + "h1{font-size:22px;font-weight:600;margin-bottom:8px}\n"
            + "p.sub{font-size:14px;color:#888;margin-bottom:32px}\n"
            + ".card{background:#1c1c1e;border-radius:16px;padding:24px;width:100%;max-width:400px}\n"
            + "label{font-size:14px;color:#aaa;display:block;margin-bottom:8px}\n"
            + "input[type=url],input[type=text]{width:100%;padding:14px 16px;border-radius:10px;"
            + "border:1px solid #333;background:#2c2c2e;color:#fff;font-size:16px;outline:none;"
            + "-webkit-appearance:none}\n"
            + "input:focus{border-color:#0a84ff}\n"
            + "button{width:100%;padding:14px;border:none;border-radius:10px;background:#0a84ff;"
            + "color:#fff;font-size:17px;font-weight:600;margin-top:16px;cursor:pointer;"
            + "-webkit-appearance:none}\n"
            + "button:active{background:#0070e0}\n"
            + "button:disabled{background:#333;color:#666}\n"
            + ".msg{text-align:center;margin-top:16px;font-size:14px;min-height:20px}\n"
            + ".msg.ok{color:#30d158}\n"
            + ".msg.err{color:#ff453a}\n"
            + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "<h1>电视遥控</h1>\n"
            + "<p class=\"sub\">输入视频链接，电视将立即播放</p>\n"
            + "<div class=\"card\">\n"
            + "<label>视频网址</label>\n"
            + "<input id=\"url\" type=\"url\" placeholder=\"https://...\" autocomplete=\"off\" autocapitalize=\"off\">\n"
            + "<button id=\"btn\" onclick=\"send()\">确定</button>\n"
            + "<div id=\"msg\" class=\"msg\"></div>\n"
            + "</div>\n"
            + "<script>\n"
            + "function send(){\n"
            + "  var u=document.getElementById('url').value.trim();\n"
            + "  if(!u){document.getElementById('msg').className='msg err';"
            + "document.getElementById('msg').textContent='请输入网址';return}\n"
            + "  var btn=document.getElementById('btn');btn.disabled=true;btn.textContent='发送中...';\n"
            + "  var xhr=new XMLHttpRequest();\n"
            + "  xhr.open('POST','/play',true);\n"
            + "  xhr.setRequestHeader('Content-Type','application/x-www-form-urlencoded');\n"
            + "  xhr.onload=function(){\n"
            + "    btn.disabled=false;btn.textContent='确定';\n"
            + "    if(xhr.status===200){\n"
            + "      document.getElementById('msg').className='msg ok';\n"
            + "      document.getElementById('msg').textContent='已发送到电视';\n"
            + "      document.getElementById('url').value='';\n"
            + "    }else{\n"
            + "      document.getElementById('msg').className='msg err';\n"
            + "      document.getElementById('msg').textContent='发送失败';\n"
            + "    }\n"
            + "    setTimeout(function(){document.getElementById('msg').textContent=''},3000);\n"
            + "  };\n"
            + "  xhr.onerror=function(){\n"
            + "    btn.disabled=false;btn.textContent='确定';\n"
            + "    document.getElementById('msg').className='msg err';\n"
            + "    document.getElementById('msg').textContent='网络错误';\n"
            + "  };\n"
            + "  xhr.send('url='+encodeURIComponent(u));\n"
            + "}\n"
            + "</script>\n"
            + "</body>\n"
            + "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
    }

    /** 处理手机提交的播放请求 */
    private Response handlePlay(IHTTPSession session) {
        try {
            Map<String, String> body = new HashMap<String, String>();
            session.parseBody(body);

            // NanoHTTPD 对 form-urlencoded 的解析
            Map<String, String> params = session.getParms();
            String url = params.get("url");

            if (url == null || url.trim().isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                        "application/json", "{\"error\":\"missing url\"}");
            }

            url = url.trim();
            Log.d(TAG, "收到播放请求: " + url);

            // 通过广播通知 LauncherActivity 播放视频
            Intent intent = new Intent(LauncherActivity.ACTION_START_VIDEO);
            intent.putExtra(LauncherActivity.EXTRA_VIDEO_URL, url);
            intent.putExtra(LauncherActivity.EXTRA_VIDEO_TITLE, "手机推送");
            context.sendBroadcast(intent);

            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", "{\"ok\":true}");

        } catch (Exception e) {
            Log.e(TAG, "处理播放请求失败", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /** 启动服务器 */
    public void startServer() {
        try {
            start();
            Log.d(TAG, "RemoteServer 已启动，端口: " + PORT);
        } catch (Exception e) {
            Log.e(TAG, "RemoteServer 启动失败", e);
        }
    }

    /** 停止服务器 */
    public void stopServer() {
        stop();
        Log.d(TAG, "RemoteServer 已停止");
    }
}
