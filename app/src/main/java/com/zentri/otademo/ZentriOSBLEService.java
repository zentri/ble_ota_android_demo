/*
 * Copyright (C) 2015, Zentri, Inc. All Rights Reserved.
 *
 * The Zentri BLE Android Libraries and Zentri BLE example applications are provided free of charge
 * by Zentri. The combined source code, and all derivatives, are licensed by Zentri SOLELY for use
 * with devices manufactured by Zentri, or devices approved by Zentri.
 *
 * Use of this software on any other devices or hardware platforms is strictly prohibited.
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AS IS AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */
package com.zentri.otademo;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.os.Handler;

import com.zentri.zentri_ble.BLECallbacks;
import com.zentri.zentri_ble.BLEHandlerAPI;
import com.zentri.zentri_ble.BLEHandlerSingleton;
import com.zentri.zentri_ble_ota.FirmwareVersion;
import com.zentri.zentri_ble_ota.OTACallbacks;
import com.zentri.zentri_ble_ota.OTAManager;
import com.zentri.zentri_ble_ota.OTAState;
import com.zentri.zentri_ble_ota.OTAStatus;

import java.util.regex.Pattern;

public class ZentriOSBLEService extends Service implements BLECallbacks, OTACallbacks
{
    public static final String ACTION_CONNECTED = "com.zentri.otademo.ACTION_CONNECTED";
    public static final String ACTION_DISCONNECTED = "com.zentri.otademo.ACTION_DISCONNECTED";

    public static final String ACTION_SCAN_RESULT = "com.zentri.otademo.ACTION_SCAN_RESULT";
    public static final String ACTION_CURRENT_VERSION_UPDATE = "com.zentri.otademo.ACTION_CURRENT_VERSION_UPDATE";
    public static final String ACTION_UPDATE_VERSION_UPDATE = "com.zentri.otademo.ACTION_UPDATE_VERSION_UPDATE";
    public static final String ACTION_PROGRESS_MAX_UPDATE = "com.zentri.otademo.ACTION_PROGRESS_MAX_UPDATE";
    public static final String ACTION_PROGRESS_UPDATE = "com.zentri.otademo.ACTION_PROGRESS_UPDATE";
    public static final String ACTION_STATUS_UPDATE = "com.zentri.otademo.ACTION_STATUS_UPDATE";
    public static final String ACTION_UPDATE_COMPLETE = "com.zentri.otademo.ACTION_UPDATE_COMPLETE";
    public static final String ACTION_ERROR = "com.zentri.otademo.ACTION_ERROR";
    public static final String ACTION_TIMEOUT_CONNECT = "com.zentri.otademo.ACTION_TIMEOUT_CONNECT";
    public static final String ACTION_TIMEOUT_DISCONNECT = "com.zentri.otademo.ACTION_TIMEOUT_DISCONNECT";

    public static final String EXTRA_VERSION = "EXTRA_VERSION";
    public static final String EXTRA_NAME = "EXTRA_NAME";
    public static final String EXTRA_STATUS = "EXTRA_STATUS";
    public static final String EXTRA_COLOR = "EXTRA_COLOR";
    public static final String EXTRA_PROGRESS = "EXTRA_PROGRESS";
    public static final String EXTRA_ERROR = "EXTRA_ERROR";
    public static final String EXTRA_FINISH = "EXTRA_FINISH";

    public static final boolean TIMEOUT_ENABLED = true;

    //auto reconnect feature does not work on all devices
    private static final boolean AUTO_RECONNECT = true;
    private static final boolean FINISH_ON_CLOSE = true;
    private static final boolean DISABLE_TX_NOTIFY = true;
    private static final String FILENAME_LATEST = "";

    public static final int SERVICES_NONE = 0;
    public static final int SERVICES_ZENTRIOS_ONLY = 1;
    public static final int SERVICES_OTA_ONLY = 2;
    public static final int SERVICES_ALL = 3;

    private static final long DELAY_RECONNECT_MS = 500;
    private static final long TIMEOUT_CONNECT_MS = 5000;
    private static final long TIMEOUT_DISCONNECT_MS = 10000;

    private static final int ERROR_MSG_NONE = 0;

    private static final String PATTERN_MAC_ADDRESS = "(\\p{XDigit}{2}:){5}\\p{XDigit}{2}";

    private final String TAG = ZentriOSBLEService.class.getSimpleName();

    private final int mStartMode = START_NOT_STICKY;
    private final IBinder mBinder = new LocalBinder();
    boolean mAllowRebind = true;
    private OTAManager mOTAManager;
    private BLEHandlerAPI mBLEHandler;

    private String mDeviceName;
    private boolean mReconnect = false;

    private Status mStatus = Status.INITIALISING;
    private int mError;
    private boolean mFinishOnClose = false;
    private int mProgressMax = 30000;

    private Settings mSettings;

    private Handler mHandler;
    private Runnable mDisconnectTimeoutTask;
    private Runnable mConnectTimeoutTask;
    private Runnable mReconnectDelayTask;
    private Runnable mDisconnectDelayTask;

    private LocalBroadcastManager mBroadcastManager;

    public enum Status
    {
        INITIALISING,
        CHECKING_FOR_UPDATE,
        READY,
        UPDATING,
        UP_TO_DATE,
        ERROR
    }

    public class LocalBinder extends Binder
    {
        ZentriOSBLEService getService()
        {
            // Return this instance of LocalService so clients can call public methods
            return ZentriOSBLEService.this;
        }
    }

    private String mCurrentVersion;
    private String mUpdateVersion;

    @Override
    public void onCreate()
    {
        // The service is being created
        Log.d(TAG, "Creating service");

        mDeviceName = "";
        mHandler = new Handler();
        mBLEHandler = BLEHandlerSingleton.getInstance();
        mBLEHandler.init(this, this);
        mOTAManager = new OTAManager();
        mBroadcastManager = LocalBroadcastManager.getInstance(this);
        initTimeouts();
        mStatus = Status.INITIALISING;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        // The service is starting, due to a call to startService()
        return mStartMode;
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        // A client is binding to the service with bindService()
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent)
    {
        // All clients have unbound with unbindService()
        return mAllowRebind;
    }

    @Override
    public void onRebind(Intent intent)
    {
        // A client is binding to the service with bindService(),
        // after onUnbind() has already been called
    }

    @Override
    public void onDestroy()
    {
        // The service is no longer used and is being destroyed
        Log.d(TAG, "Destroying service");

        if (mBLEHandler != null)
        {
            mBLEHandler.stopBLEScan();
            mBLEHandler.disconnect(mDeviceName, !DISABLE_TX_NOTIFY);
            mBLEHandler.deinit();
        }

        mOTAManager.deinit();
    }

    public boolean initOTAManager()
    {
        sendStatus(Status.INITIALISING);
        return mOTAManager.init(mDeviceName, this);
    }

    /**
     * Used to stop all timeouts on application close
     */
    public void cancelTimeouts()
    {
        Log.d(TAG, "Cancelling timeouts");
        cancelTimeout(mDisconnectTimeoutTask);
        cancelTimeout(mConnectTimeoutTask);
        cancelTimeout(mReconnectDelayTask);
        cancelTimeout(mDisconnectDelayTask);
    }

    public String getCurrentVersion()
    {
        return mCurrentVersion;
    }

    public String getUpdateVersion()
    {
        return mUpdateVersion;
    }

    public int getProgressMax()
    {
        return mProgressMax;
    }

    public int getError()
    {
        return mError;
    }

    public boolean getFinishOnClose()
    {
        return mFinishOnClose;
    }

    public Status getStatus()
    {
        return mStatus;
    }

    public boolean getReconnect()
    {
        return mReconnect;
    }

    public void setReconnect(boolean reconnect)
    {
        mReconnect = reconnect;
    }

    public BLEHandlerAPI getBLEHandler()
    {
        return mBLEHandler;
    }

    public boolean isConnected()
    {
        return mBLEHandler.isConnected(mDeviceName);
    }

    public boolean isUpdateInProgress()
    {
        return mOTAManager.isOTAInProgress();
    }

    public void setSettings(Settings settings)
    {
        mSettings = settings;
    }

    public String getDeviceName()
    {
        return mDeviceName;
    }

    public OTAState getState()
    {
        return mOTAManager.getState();
    }

    public boolean updateStart()
    {
        return mOTAManager.updateStart();
    }

    public boolean updateAbort()
    {
        return mOTAManager.updateAbort();
    }

    public void checkForUpdates(String filename)
    {
        mStatus = Status.CHECKING_FOR_UPDATE;
        sendStatus(mStatus);
        if (!mOTAManager.checkForUpdates(this, filename))
        {
            Log.e(TAG, "Failed to check for updates");
            sendError(R.string.error_get_image_failed, !FINISH_ON_CLOSE);
        }
    }

    public boolean disconnect(boolean disableTxNotification, boolean enableTimeout)
    {
        Log.d(TAG, "Disconnecting...");
        if (enableTimeout)
        {
            startTimeout(mDisconnectTimeoutTask, TIMEOUT_DISCONNECT_MS);
        }
        return mBLEHandler.disconnect(mDeviceName, disableTxNotification);
    }

    public boolean reconnect()
    {
        return mBLEHandler.connect(mDeviceName, !AUTO_RECONNECT);
    }

    @Override
    public void onScanResult(String deviceName)
    {
        Log.d(TAG, "onScanResult");

        if (deviceName != null && !Pattern.matches(PATTERN_MAC_ADDRESS, deviceName))
        {
            Intent intent = new Intent(ACTION_SCAN_RESULT);
            intent.putExtra(EXTRA_NAME, deviceName);
            mBroadcastManager.sendBroadcast(intent);
        }
    }

    @Override
    public void onConnect(String deviceName, int servicesSupported)
    {
        mDeviceName = deviceName;

        cancelTimeout(mConnectTimeoutTask);

        if (servicesSupported == SERVICES_ALL || servicesSupported == SERVICES_OTA_ONLY)
        {
            //can upgrade device
            initOTAManager();
            Intent intent = new Intent(ACTION_CONNECTED);
            intent.putExtra(EXTRA_NAME, deviceName);
            mBroadcastManager.sendBroadcast(intent);
        }
        else
        {
            //error - device does not support firmware updates!
            sendError(R.string.error_ota_unsupported, FINISH_ON_CLOSE);
        }
    }

    @Override
    public void onConnectFailed(String deviceName, Result result)
    {
        if (result == Result.CONNECT_FAILURE)
        {
            sendError(R.string.error_connect_fail, FINISH_ON_CLOSE);
        }
        else if (result == Result.SERVICE_DISC_ERROR)
        {
            sendError(R.string.error_service_disc, FINISH_ON_CLOSE);
        }
    }

    @Override
    public void onDisconnect(String deviceName)
    {
        Log.d(TAG, "onDisconnect()");
        cancelTimeout(mDisconnectTimeoutTask);
        Intent intent = new Intent(ACTION_DISCONNECTED);
        intent.putExtra(EXTRA_NAME, deviceName);

        if (mReconnect)
        {
            Log.d(TAG, "Scheduling reconnect");
            mHandler.postDelayed(mReconnectDelayTask, DELAY_RECONNECT_MS);
            //dont send broadcast, we hope to be reconnected soon
        }
        else
        {
            mDeviceName = "";
            mBroadcastManager.sendBroadcast(intent);
        }

    }

    @Override
    public void onDisconnectFailed(String deviceName)
    {
        sendError(R.string.error_disconnect_failed, FINISH_ON_CLOSE);
    }

    @Override
    public void onStringDataRead(String deviceName, String data) {}

    @Override
    public void onBinaryDataRead(String deviceName, byte[] data) {}

    @Override
    public void onStringDataWrite(String deviceName, String data) {}

    @Override
    public void onBinaryDataWrite(String deviceName, byte[] data) {}

    @Override
    public void onModeChanged(String deviceName, int mode) {}

    @Override
    public void onModeRead(String deviceName, int mode) {}

    @Override
    public void onError(String deviceName, BLECallbacks.Error error, String s1)
    {
        onBLEError(error);
    }

    @Override
    public void onFirmwareVersionRead(String deviceName, String version) {}

    /***********************************************************************************************
    * OTA callbacks
    ***********************************************************************************************/
    public void onUpdateInitSuccess(String deviceName)
    {
        Log.d(TAG, "OTA init success");
        mOTAManager.readFirmwareVersion();
        sendStatus(Status.CHECKING_FOR_UPDATE);
    }

    public void onUpdateVersionRead(String deviceName, String version)
    {
        if (version != null)
        {
            Log.d(TAG, "Current version read - " + version);
            mCurrentVersion = version;
        }
        else
        {
            Log.e(TAG, "Current version read, was null!");
            mCurrentVersion = "";
        }

        sendCurrentVersion(mCurrentVersion);

        String filename;

        if (mSettings != null && !mSettings.useLatest())
        {
            filename = String.format("truconnect-%s.bin", mSettings.getFirmwareFilename());
        }
        else
        {
            filename = FILENAME_LATEST;
        }

        sendStatus(Status.CHECKING_FOR_UPDATE);
        mOTAManager.checkForUpdates(this, filename);
    }

    public void onUpdateCheckComplete(String deviceName, boolean isUpToDate, FirmwareVersion version)
    {
        Log.d(TAG, "OTA update check complete!");

        if (isUpToDate)
        {
            mStatus = Status.UP_TO_DATE;
        }
        else
        {
            mStatus = Status.READY;
        }

        if (version != null)
        {
            mUpdateVersion = version.toString();
        }
        else
        {
            Log.e(TAG, "Update version was null!");
            mUpdateVersion = "";
        }

        sendStatus(mStatus);
        sendUpdateVersion(mUpdateVersion);
        mProgressMax = mOTAManager.getUpdateFileSize();
        sendProgressMaxUpdate(mProgressMax);
    }

    public void onUpdateAbort(String deviceName)
    {
        Log.d(TAG, "OTA aborted");
        mStatus = Status.READY;
        sendStatus(mStatus);
    }

    public void onUpdateStart(String deviceName)
    {
        Log.d(TAG, "OTA started");
        mStatus = Status.UPDATING;
        sendStatus(mStatus);
    }

    public void onUpdateComplete(String deviceName)
    {
        Log.d(TAG, "OTA completed successfully!");

        mReconnect = true;

        //ensure last BLE packets are sent
        startTimeout(mDisconnectDelayTask, DELAY_RECONNECT_MS);
    }

    public void onUpdateDataSent(String deviceName, int bytesSent, int bytesRemaining)
    {
        Log.d(TAG, "" + bytesRemaining + "bytes remaining");
        sendProgressUpdate(bytesSent);
    }

    public void onUpdateError(String deviceName, OTAStatus status)
    {
        mStatus = Status.ERROR;
        onOTAError(status);
        sendStatus(mStatus);
    }

    public void onTransitionUpdateRequired(String deviceName)
    {
        //not used
        sendError(R.string.error_transition_unsupported, !FINISH_ON_CLOSE);
    }

    public void onTransitionUpdateComplete(String deviceName)
    {
        //not used
    }

    /**********************************************************************************************/

    private void sendError(int msgId, boolean finishOnClose)
    {
        Intent intent = new Intent(ACTION_ERROR);

        intent.putExtra(EXTRA_ERROR, msgId);
        intent.putExtra(EXTRA_FINISH, finishOnClose);

        mBroadcastManager.sendBroadcast(intent);
    }

    private void sendStatus(Status status)
    {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);

        mBroadcastManager.sendBroadcast(intent);
    }

    private void sendCurrentVersion(String version)
    {
        sendIntentWithStringExtra(ACTION_CURRENT_VERSION_UPDATE, EXTRA_VERSION, version);
    }

    private void sendUpdateVersion(String version)
    {
        sendIntentWithStringExtra(ACTION_UPDATE_VERSION_UPDATE, EXTRA_VERSION, version);
    }

    private void sendProgressMaxUpdate(int max)
    {
        sendIntentWithIntExtra(ACTION_PROGRESS_MAX_UPDATE, EXTRA_PROGRESS, max);
    }

    private void sendProgressUpdate(int progress)
    {
        sendIntentWithIntExtra(ACTION_PROGRESS_UPDATE, EXTRA_PROGRESS, progress);
    }

    private void sendIntentWithStringExtra(String action, String extraID, String extra)
    {
        Intent intent = new Intent(action);

        if (extra != null)
        {
            intent.putExtra(extraID, extra);
        }

        mBroadcastManager.sendBroadcast(intent);
    }

    private void sendIntentWithIntExtra(String action, String extraID, int extra)
    {
        Intent intent = new Intent(action);

        intent.putExtra(extraID, extra);

        mBroadcastManager.sendBroadcast(intent);
    }

    private void onOTAError(OTAStatus status)
    {
        mFinishOnClose = false;

        Log.d(TAG, "OTA Error - " + status.toString());

        switch(status.getCode())
        {
            case OTAStatus.FAILED_TO_GET_IMAGE:
                sendUpdateVersion(getString(R.string.update_version_on_error));
                mError = R.string.error_get_image_failed;
                mFinishOnClose = false;
                break;

            case OTAStatus.INIT_FAILED:
                mError = R.string.error_init_failed;
                mFinishOnClose = FINISH_ON_CLOSE;
                break;

            case OTAStatus.COMMUNICATION_ERROR:
                mError = R.string.error_comms;
                break;

            case OTAStatus.NO_INTERNET:
                mError = R.string.error_no_internet;
                break;

            case OTAStatus.ABORT_FAILED:
                mError = R.string.error_abort_failed;
                break;

            case OTAStatus.UNSUPPORTED_COMMAND:
                mError = R.string.error_ota_unsupported;
                break;

            case OTAStatus.ILLEGAL_STATE:
                mError = R.string.error_illegal_state;
                break;

            case OTAStatus.VERIFICATION_FAILED:
                mError = R.string.error_verify_failed;
                break;

            case OTAStatus.INVALID_IMAGE://from device:
                mError = R.string.error_invalid_image;
                break;

            case OTAStatus.INVALID_IMAGE_SIZE:
                mError = R.string.error_invalid_size;
                break;

            case OTAStatus.MORE_DATA:
                mError = R.string.error_more_data;
                break;

            case OTAStatus.INVALID_APPID:
                mError = R.string.error_invalid_app_id;
                break;

            case OTAStatus.INVALID_VERSION:
                mError = R.string.error_invalid_version;
                break;

            default:
                mError = R.string.error_unexpected;
                mFinishOnClose = false;
                break;
        }

        if (mError != ERROR_MSG_NONE)
        {
            sendError(mError, mFinishOnClose);
        }
    }

    private void onBLEError(Error error)
    {
        mFinishOnClose = true;

        Log.d(TAG, "BLE Error - " + error.toString());

        switch(error)
        {
            case CONNECT_WITHOUT_REQUEST:
                //reconnect, no need for error
                mFinishOnClose = false;
                break;

            case DISCONNECT_WITHOUT_REQUEST:
                //connection lost
                mError = R.string.error_connection_lost_message;
                break;

            case INVALID_MODE:
                //internal error - invalid connection mode
                mError = R.string.error_invalid_con_mode;
                break;

            case NO_TX_CHARACTERISTIC:
            case NO_RX_CHARACTERISTIC:
            case NO_MODE_CHARACTERISTIC:
                //cant communicate with device
                mError = R.string.device_error_message;
                break;

            case NO_CONNECTION_FOUND:
                //Connected to service, but device not connected
                mError = R.string.error_connection_lost_message;
                break;

            case NULL_GATT_ON_CALLBACK:
                mError = R.string.error_null_gatt_on_callback;
                break;

            case NULL_CHAR_ON_CALLBACK:
                mError = R.string.error_null_gatt_on_callback;
                break;

            case DATA_READ_FAILED:
                mError = R.string.error_data_read_failed;
                mFinishOnClose = false;
                break;

            case DATA_WRITE_FAILED:
                mError = R.string.error_data_write_failed;
                mFinishOnClose = false;
                break;

            case MODE_READ_FAILED:
                mError = R.string.error_mode_set_read_failed;
                mFinishOnClose = false;
                break;

            case MODE_WRITE_FAILED:
                mError = R.string.error_mode_set_write_failed;
                mFinishOnClose = false;
                break;

            case SET_TX_NOTIFY_FAILED:
                mError = R.string.error_set_tx_notify_failed;
                mFinishOnClose = false;
                break;

            case SET_OTA_CONTROL_NOTIFY_FAILED:
                mError = R.string.error_set_ota_control_notify_failed;
                break;

            case SERVICE_DISCOVERY_FAILED:
                mError = R.string.error_service_disc;
                break;

            case VERSION_READ_FAILED:
                mError = R.string.error_version_read_failed;
                mFinishOnClose = false;
                break;

            default:
                mError = R.string.error_unexpected;
                mFinishOnClose = false;
                break;
        }

        if (mError != ERROR_MSG_NONE)
        {
            sendError(mError, mFinishOnClose);
        }
    }

    private void startTimeout(Runnable task, long delay)
    {
        mHandler.postDelayed(task, delay);
    }

    private void cancelTimeout(Runnable task)
    {
        mHandler.removeCallbacks(task);
    }

    private void initTimeouts()
    {
        mDisconnectTimeoutTask = new Runnable()
        {
            @Override
            public void run()
            {
                //show error
                sendError(R.string.discon_timeout_message, FINISH_ON_CLOSE);
            }
        };

        mConnectTimeoutTask = new Runnable()
        {
            @Override
            public void run()
            {
                //show error
                sendError(R.string.con_timeout_message, FINISH_ON_CLOSE);
            }
        };

        mDisconnectDelayTask = new Runnable()
        {
            @Override
            public void run()
            {
                disconnect(!DISABLE_TX_NOTIFY, TIMEOUT_ENABLED);
            }
        };

        mReconnectDelayTask = new Runnable()
        {
            @Override
            public void run()
            {
                mBLEHandler.connect(mDeviceName, !AUTO_RECONNECT);
                startTimeout(mConnectTimeoutTask, TIMEOUT_CONNECT_MS);
            }
        };
    }

}