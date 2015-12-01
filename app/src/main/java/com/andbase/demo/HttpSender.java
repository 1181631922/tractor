package com.andbase.demo;

import android.text.TextUtils;

import com.andbase.demo.bean.DownloadInfo;
import com.andbase.demo.http.HttpBase;
import com.andbase.demo.http.OKHttp;
import com.andbase.demo.http.request.HttpMethod;
import com.andbase.demo.http.request.HttpRequest;
import com.andbase.demo.http.response.HttpResponse;
import com.andbase.tractor.listener.LoadListener;
import com.andbase.tractor.task.Task;
import com.andbase.tractor.task.TaskPool;
import com.andbase.tractor.utils.LogUtils;
import com.andbase.tractor.utils.Util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;

/**
 * Created by Administrator on 2015/11/16.
 */
public class HttpSender {
    static HttpBase mHttpBase = new OKHttp();


    public static void post(final String url,
                            LinkedHashMap<String, String> headers, final String params,
                            final LoadListener listener, Object... tag) {
        HttpRequest.Builder builder = new HttpRequest.Builder();
        builder.url(url);
        addHeaders(builder, headers);
        builder.setStringParams(params);
        //如无特殊需求，这步可以省去
        builder.contentType("application/x-www-form-urlencoded").charSet("utf-8");
        HttpRequest request = builder.build();
        mHttpBase.post(request, listener, tag);
    }

    public static void post(final String url,
                            LinkedHashMap<String, String> headers, LinkedHashMap<String, Object> params,
                            LoadListener listener, Object... tag) {
        HttpRequest.Builder builder = new HttpRequest.Builder();
        builder.url(url);
        addHeaders(builder, headers);
        builder.setParams(params);
        //如无特殊需求，这步可以省去
        builder.contentType("application/x-www-form-urlencoded").charSet("utf-8");
        HttpRequest request = builder.build();
        mHttpBase.post(request, listener, tag);
    }

    public static void get(String url, LinkedHashMap<String, String> headers, String params,
                           final LoadListener listener, Object... tag) {
        if (!TextUtils.isEmpty(params)) {
            url = url + "?" + params;
        }
        HttpRequest.Builder builder = new HttpRequest.Builder();
        builder.url(url);
        addHeaders(builder, headers);
        mHttpBase.get(builder.build(), listener, tag);
    }

    public static HttpResponse getSync(String url, LinkedHashMap<String, String> headers, String params, Object... tag) {
        if (!TextUtils.isEmpty(params)) {
            url = url + "?" + params;
        }
        HttpRequest.Builder builder = new HttpRequest.Builder();
        builder.url(url).synchron();
        addHeaders(builder, headers);
        return mHttpBase.get(builder.build(), null, tag);
    }

    public static void header(String url, LoadListener listener, Object... tag) {
        HttpRequest.Builder builder = new HttpRequest.Builder();
        builder.url(url).method(HttpMethod.HEAD);
        mHttpBase.request(builder.build(), listener, tag);
    }

    /**
     * 同步请求应当在非ui线程中调用
     *
     * @param url
     * @param tag
     */
    public static HttpResponse headerSync(String url, Object... tag) {
        HttpRequest.Builder builder = new HttpRequest.Builder();
        builder.url(url).synchron().method(HttpMethod.HEAD);
        HttpResponse response = mHttpBase.request(builder.build(), null, tag);
        return response;
    }

    /**
     * 下载文件，支持多线程下载
     *
     * @param info     下载信息，url,文件保存路径等
     * @param listener
     * @param tag
     */
    public static void download(final DownloadInfo info, final LoadListener listener, final Object tag) {
        TaskPool.getInstance().execute(new Task(tag, listener) {
            @Override
            public void onRun() {
                notifyStart("开始下载...");
                String url = info.url;
                int threadNum = info.threadNum;
                HttpResponse headResponse = headerSync(url, tag);
                if (headResponse == null) {
                    notifyFail("获取下载文件信息失败");
                    return;
                }
                long filelength = headResponse.getContentLength();
                info.fileLength = filelength;
                final long starttime = System.currentTimeMillis();

                long block = filelength % threadNum == 0 ? filelength / threadNum
                        : filelength / threadNum + 1;
                setFileLength(info.fileDir, info.filename, info.fileLength);
                int freeMemory = ((int) Runtime.getRuntime().freeMemory());// 获取应用剩余可用内存
                int allocated = freeMemory / 6 / threadNum;//给每个线程分配的内存
                info.setTask(this);
                LogUtils.d("spendTime allocated = " + allocated);
                for (int i = 0; i < threadNum; i++) {
                    final long startposition = i * block;
                    final long endposition = (i + 1) * block - 1;
                    info.startPos = startposition;
                    info.endPos = endposition;
                    downBlock(info, allocated, this, tag);
                }
                synchronized (this) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if (info.compeleteSize != info.fileLength) {
                    notifyFail(null);
                    LogUtils.i("download failed! spendTime=" + (System.currentTimeMillis() - starttime));
                } else {
                    LogUtils.i("download finshed! spendTime=" + (System.currentTimeMillis() - starttime));
                }
            }

            @Override
            public void cancelTask() {
                synchronized (this) {
                    this.notify();
                }
            }
        });
    }

    private static void downBlock(final DownloadInfo info, final int allocated, final Task task, final Object tag) {
        final long startposition = info.startPos;
        final long endposition = info.endPos;
        final String url = info.url;
        final String filepath = info.filePath;
        TaskPool.getInstance().execute(new Task() {
            @Override
            public void onRun() {
                LogUtils.d("startposition=" + startposition + ";endposition=" + endposition);
                LinkedHashMap<String, String> header = new LinkedHashMap<>();
                header.put("RANGE", "bytes=" + startposition + "-"
                        + endposition);
                HttpResponse downloadResponse = getSync(url, header, null, null, tag);
                InputStream inStream = null;
                if (downloadResponse != null) {
                    inStream = downloadResponse.getInputStream();
                } else {
                    notifyDownloadFailed(null);
                    return;
                }
                RandomAccessFile accessFile = null;
                try {
                    File saveFile = new File(filepath);
                    accessFile = new RandomAccessFile(saveFile, "rwd");
                    accessFile.seek(startposition);// 设置从什么位置开始写入数据

                    byte[] buffer = new byte[allocated];
//                    byte[] buffer = new byte[1024];
                    int len = 0;
                    while ((len = inStream.read(buffer)) != -1) {
                        accessFile.write(buffer, 0, len);
                        // 实时更新进度
                        LogUtils.d("len=" + len);
                        if (!info.compute(len)) {
                            //当下载任务失败以后结束此下载线程
                            LogUtils.i("停止下载");
                            break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    notifyDownloadFailed(e);
                } finally {
                    Util.closeQuietly(inStream);
                    Util.closeQuietly(accessFile);
                }
            }

            private void notifyDownloadFailed(Exception e) {
                task.notifyFail(e);
                synchronized (task) {
                    task.notify();
                }
            }

            @Override
            public void cancelTask() {

            }
        });

    }

    public static void setFileLength(final String fileDir, final String fileName, long filelength) {
        File dir = new File(fileDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        RandomAccessFile accessFile = null;
        try {
            File downloadFile = new File(fileDir, fileName);
            if (downloadFile.exists()) {
                downloadFile.delete();
            }
            accessFile = new RandomAccessFile(downloadFile, "rwd");
            accessFile.setLength(filelength);// 设置本地文件的长度和下载文件相同
            accessFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Util.closeQuietly(accessFile);
        }
    }

    private static void addHeaders(HttpRequest.Builder builder, LinkedHashMap<String, String> headers) {
        if (headers != null && headers.size() > 0) {
            builder.setHeader(headers);
        }
    }
}