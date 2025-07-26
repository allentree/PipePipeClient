package us.shandian.giga.get;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import org.schabi.newpipe.streams.io.SharpStream;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.nio.channels.ClosedByInterruptException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import us.shandian.giga.util.Utility;

import static org.schabi.newpipe.BuildConfig.DEBUG;
import static us.shandian.giga.get.DownloadMission.ERROR_HTTP_AUTH;
import static us.shandian.giga.get.DownloadMission.ERROR_HTTP_FORBIDDEN;

import org.schabi.newpipe.extractor.utils.LogUtil;

public class DownloadInitializer extends Thread {
    private final static String TAG = "DownloadInitializer";
    final static int mId = 0;
    private final static int RESERVE_SPACE_DEFAULT = 5 * 1024 * 1024;// 5 MiB
    private final static int RESERVE_SPACE_MAXIMUM = 150 * 1024 * 1024;// 150 MiB

    private final DownloadMission mMission;
    private HttpURLConnection mConn;

    DownloadInitializer(@NonNull DownloadMission mission) {
        mMission = mission;
        mConn = null;
    }

    private void dispose() {
        try {
            mConn.getInputStream().close();
        } catch (Exception e) {
            // nothing to do
        }
    }

    @Override
    public void run() {
        if (mMission.current > 0) mMission.resetState(false, true, DownloadMission.ERROR_NOTHING);

        int retryCount = 0;
        int httpCode = 204;

        while (true) {
            try {
                if (mMission.blocks == null && mMission.current == 0) {
                    // calculate the whole size of the mission
                    long finalLength = 0;
                    long lowestSize = Long.MAX_VALUE;
                    String logMessage = null;

                    for (int i = 0; i < mMission.urls.length && mMission.running; i++) {
                        logMessage = "length=" + mMission.urls.length + ",i=" + i + ",mMission.urls[i]=" + mMission.urls[i];
                        LogUtil.logWithMessage("tree-test03", "" + logMessage);
                        if (mMission.urls[i].startsWith("file://")) {
                            LogUtil.logWithMessage("tree-test03", "find start with file://");
                            File file = new File(mMission.urls[i].substring(7));
                            if (!file.exists()) {
                                //notifyError(DownloadMission.ERROR_FILE_NOT_FOUND);
                                mMission.notifyError(DownloadMission.ERROR_FILE_CREATION, null);
                                //return;
                            }
                            if (!mMission.storage.canWrite()) {
                                LogUtil.logWithMessage("tree-test03", "Storage not writable: " + mMission.storage.getName());
                                mMission.notifyError(DownloadMission.ERROR_PERMISSION_DENIED, null);
                                return;
                            } else {
                                LogUtil.logWithMessage("tree-test03", "Storage is writable: " + mMission.storage.getName());
                            }

                            // print result:
                            // 【original anime MV】III【hololive_宝鐘マリン＆こぼ・かなえる】-en.srt
                            LogUtil.logWithMessage("tree-test03", "0-storage file=" + mMission.storage.getName());
                            // print result:
                            // /storage/emulated/0/Android/data/InfinityLoop1309.NewPipeEnhanced.debug/files/pending_downloads/1753429454000
                            LogUtil.logWithMessage("tree-test03", "metadata=" + mMission.metadata.getAbsolutePath());

                            // read the local subtitle ttml file and write to mMission.storage
                            try (FileInputStream inputStream = new FileInputStream(file);
                                    SharpStream outputStream = mMission.storage.getStream()) {
                                byte[] buffer = new byte[DownloadMission.BUFFER_SIZE];
                                int bytesRead;
                                long totalBytes = 0;
                                while ((bytesRead = inputStream.read(buffer)) != -1) {
                                    outputStream.write(buffer, 0, bytesRead);
                                    //String bufferContent = new String(buffer, 0, bytesRead, "UTF-8");
                                    //LogUtil.logWithMessage("tree-test03", "Buffer content: " + bufferContent);
                                    totalBytes += bytesRead;
                                    mMission.notifyProgress(bytesRead);
                                    //mMission.psAlgorithm = null;
                                }
                                mMission.length = totalBytes;
                                mMission.unknownLength = false;
                                mMission.notifyFinished();
                            }
                            LogUtil.logWithMessage("tree-test03", "Local file copied to: " + mMission.storage.getName());

                            return;
                        }
                        mConn = mMission.openConnection(mMission.urls[i], true, 0, 0);
                        LogUtil.logWithMessage("tree-test03", "after openConnection() will into establishConnection()");
                        mMission.establishConnection(mId, mConn);
                        dispose();

                        if (Thread.interrupted()) return;
                        long length = Utility.getTotalContentLength(mConn);

                        if (i == 0) {
                            httpCode = mConn.getResponseCode();
                            mMission.length = length;
                        }

                        if (length > 0) finalLength += length;
                        if (length < lowestSize) lowestSize = length;
                    }

                    mMission.nearLength = finalLength;

                    // reserve space at the start of the file
                    if (mMission.psAlgorithm != null && mMission.psAlgorithm.reserveSpace) {
                        if (lowestSize < 1) {
                            // the length is unknown use the default size
                            mMission.offsets[0] = RESERVE_SPACE_DEFAULT;
                        } else {
                            // use the smallest resource size to download, otherwise, use the maximum
                            mMission.offsets[0] = lowestSize < RESERVE_SPACE_MAXIMUM ? lowestSize : RESERVE_SPACE_MAXIMUM;
                        }
                    }
                } else {
                    // ask for the current resource length
                    mConn = mMission.openConnection(true, 0, 0);
                    mMission.establishConnection(mId, mConn);
                    dispose();

                    if (!mMission.running || Thread.interrupted()) return;

                    httpCode = mConn.getResponseCode();
                    mMission.length = Utility.getTotalContentLength(mConn);
                }

                if (mMission.length == 0 || httpCode == 204) {
                    mMission.notifyError(DownloadMission.ERROR_HTTP_NO_CONTENT, null);
                    return;
                }

                // check for dynamic generated content
                if (mMission.length == -1 && mConn.getResponseCode() == 200) {
                    mMission.blocks = new int[0];
                    mMission.length = 0;
                    mMission.unknownLength = true;

                    if (DEBUG) {
                        Log.d(TAG, "falling back (unknown length)");
                    }
                } else {
                    // Open again
                    mConn = mMission.openConnection(true, mMission.length - 10, mMission.length);
                    mMission.establishConnection(mId, mConn);
                    dispose();

                    if (!mMission.running || Thread.interrupted()) return;

                    synchronized (mMission.LOCK) {
                        if (mConn.getResponseCode() == 206) {

                            if (mMission.threadCount > 1) {
                                int count = (int) (mMission.length / DownloadMission.BLOCK_SIZE);
                                if ((count * DownloadMission.BLOCK_SIZE) < mMission.length) count++;

                                mMission.blocks = new int[count];
                            } else {
                                // if one thread is required don't calculate blocks, is useless
                                mMission.blocks = new int[0];
                                mMission.unknownLength = false;
                            }

                            if (DEBUG) {
                                Log.d(TAG, "http response code = " + mConn.getResponseCode());
                            }
                        } else {
                            // Fallback to single thread
                            mMission.blocks = new int[0];
                            mMission.unknownLength = false;

                            if (DEBUG) {
                                Log.d(TAG, "falling back due http response code = " + mConn.getResponseCode());
                            }
                        }
                    }

                    if (!mMission.running || Thread.interrupted()) return;
                }

                try (SharpStream fs = mMission.storage.getStream()) {
                    fs.setLength(mMission.offsets[mMission.current] + mMission.length);
                    fs.seek(mMission.offsets[mMission.current]);
                }

                if (!mMission.running || Thread.interrupted()) return;

                if (!mMission.unknownLength && mMission.recoveryInfo != null) {
                    String entityTag = mConn.getHeaderField("ETAG");
                    String lastModified = mConn.getHeaderField("Last-Modified");
                    MissionRecoveryInfo recovery = mMission.recoveryInfo[mMission.current];

                    if (!TextUtils.isEmpty(entityTag)) {
                        recovery.setValidateCondition(entityTag);
                    } else if (!TextUtils.isEmpty(lastModified)) {
                        recovery.setValidateCondition(lastModified);// Note: this is less precise
                    } else {
                        recovery.setValidateCondition(null);
                    }
                }

                mMission.running = false;
                break;
            } catch (InterruptedIOException | ClosedByInterruptException e) {
                return;
            } catch (Exception e) {
                if (!mMission.running || super.isInterrupted()) return;

                if (e instanceof DownloadMission.HttpError && ((((DownloadMission.HttpError) e).statusCode == ERROR_HTTP_FORBIDDEN)
                        || ((DownloadMission.HttpError) e).statusCode == ERROR_HTTP_AUTH)) {
                    // for youtube streams. The url has expired
                    interrupt();
                    mMission.doRecover(ERROR_HTTP_FORBIDDEN);
                    return;
                }

                if (e instanceof IOException && e.getMessage().contains("Permission denied")) {
                    mMission.notifyError(DownloadMission.ERROR_PERMISSION_DENIED, e);
                    return;
                }

                if (retryCount++ > mMission.maxRetry) {
                    Log.e(TAG, "initializer failed", e);
                    mMission.notifyError(e);
                    return;
                }

                Log.e(TAG, "initializer failed, retrying", e);
            }
        }

        mMission.start();
    }

    @Override
    public void interrupt() {
        super.interrupt();
        if (mConn != null) dispose();
    }
}
