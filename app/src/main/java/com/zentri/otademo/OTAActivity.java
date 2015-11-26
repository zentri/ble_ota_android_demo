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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.zentri.otademo.ZentriOSBLEService.Status;

public class OTAActivity extends AppCompatActivity
{
    private static final String TAG = OTAActivity.class.getSimpleName();

    private static final boolean MODE_INDETERMINATE = true;
    private static final boolean VISIBLE = true;
    private static final boolean BUTTON_ENABLED = true;
    private static final boolean FINISH_ON_CLOSE = true;

    private static final boolean DISABLE_TX_NOTIFY = true;

    private static final long TIMEOUT_SERVICE_CON_MS = 5000;

    private static final int MSG_ID_NONE = 0;

    private ServiceConnection mConnection;
    private ZentriOSBLEService mService;
    private boolean mBound = false;
    private boolean mUnbinding = false;

    private LocalBroadcastManager mLocalBroadcastManager;
    private BroadcastReceiver mBroadcastReceiver;
    private IntentFilter mReceiverIntentFilter;

    private Handler mHandler;
    private Runnable mServiceConnectionTimeoutTask;

    private TextView mCurrentVersion;
    private TextView mUpdateVersion;
    private TextView mStatus;
    private ProgressBar mProgressBar;
    private Button mButton;
    private Dialog mCurrentDialog;

    private boolean mSettingsDialogOpen = false;

    private Settings mSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ota);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        init();
        mSettings = SettingsManager.loadSettings(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_connect, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle item selection
        switch (item.getItemId())
        {
            case R.id.action_about:
                openAboutDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        if (mLocalBroadcastManager != null)
        {
            Intent intent = new Intent(this, ZentriOSBLEService.class);
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            startTimeout(mServiceConnectionTimeoutTask, TIMEOUT_SERVICE_CON_MS);

            mLocalBroadcastManager.registerReceiver(mBroadcastReceiver, mReceiverIntentFilter);
        }
        else
        {
            setStatusError(getString(R.string.error_connection_setup));
        }
    }

    @Override
    protected void onStop()
    {
        super.onStop();

        if (mService != null)
        {
            mService.cancelTimeouts();

            if (isFinishing() && mService.isConnected())
            {
                Log.d(TAG, "Disconnecting on exit, not disabling TX notify");
                //attempt to clean up state before closing (should be already disconnected now)
                mService.disconnect(!DISABLE_TX_NOTIFY, !ZentriOSBLEService.TIMEOUT_ENABLED);
            }
        }

        if (mBound)
        {
            if (mLocalBroadcastManager != null)
            {
                mUnbinding = true;
                mLocalBroadcastManager.unregisterReceiver(mBroadcastReceiver);
                unbindService(mConnection);
                mBound = false;
            }
            else
            {
                setStatusError(getString(R.string.discon_err_message));
            }
        }

        dismissCurrentDialog();
    }

    @Override
    public void onBackPressed()
    {
        mService.setReconnect(false);

        disconnect();

    }

    private void disconnect()
    {
        showDisconnectDialog();
        mService.disconnect(!DISABLE_TX_NOTIFY, ZentriOSBLEService.TIMEOUT_ENABLED);
    }

    public Settings getSettings()
    {
        return mSettings;
    }

    private void initGUI()
    {
        mCurrentVersion = (TextView) findViewById(R.id.textview_current_version);
        mUpdateVersion = (TextView) findViewById(R.id.textview_update_version);
        mUpdateVersion.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (mService.isUpdateInProgress())
                {
                    showErrorDialog(R.string.update_in_progress_error_msg, !FINISH_ON_CLOSE);
                }
                else
                {
                    showSettingsMenu(mSettings);
                }
            }
        });
        mStatus = (TextView) findViewById(R.id.textview_status);
        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);
        mButton = (Button) findViewById(R.id.button);
        mButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (mService != null)
                {
                    switch (mService.getState())
                    {
                        case INITIALISING:
                            break;
                        case INITIALISED:
                            break;
                        case READING_VERSION:
                            break;
                        case VERSION_READ:
                            break;
                        case CHECKING_FOR_UPDATE:
                            break;

                        case UPDATING_TRANSITION:
                        case UPDATING:
                            mService.updateAbort();
                            break;

                        case READY:
                        case UP_TO_DATE:
                            mService.updateStart();
                            break;

                        case COMPLETE:
                        case ERROR:
                            checkForUpdates();
                            break;
                    }
                }
            }
        });
    }

    private void init()
    {
        initGUI();
        initTimeouts();
        initBroadcastManager();
        initServiceConnection();
        initReceiverIntentFilter();
        initBroadcastReceiver();
    }

    private void initServiceConnection()
    {
        mBound = false;
        mUnbinding = false;

        mConnection = new ServiceConnection()
        {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service)
            {
                Log.d(TAG, "Connected to service");
                cancelTimeout(mServiceConnectionTimeoutTask);

                ZentriOSBLEService.LocalBinder binder = (ZentriOSBLEService.LocalBinder) service;
                mService = binder.getService();
                mBound = true;
                mService.setSettings(mSettings);

                if (!mService.isConnected())
                {
                    Log.d(TAG, "Not connected to device on service bind!");
                    //service was destroyed and re-created, connection lost
                    setStatus(R.string.error_connection_lost_message, R.color.zentri_red);
                    showErrorDialog(R.string.error_connection_lost_message, FINISH_ON_CLOSE);
                }
                else
                {
                    Log.d(TAG, "Connection to device found");
                    //set window title to name of connected device
                    OTAActivity.this.setTitle(mService.getDeviceName());
                    setUIValues();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0)
            {
                mBound = false;

                if (!mUnbinding)
                {
                    //connection lost unexpectedly
                    setStatusError(getString(R.string.error_connection_lost_message));
                }

                mUnbinding = false;
            }
        };
    }

    private void setUIValues()
    {
        mCurrentVersion.setText(mService.getCurrentVersion());
        mUpdateVersion.setText(mService.getUpdateVersion());
        setProgressBarMax(mService.getProgressMax());
        setState(mService.getStatus());

        if (mService.getStatus() == Status.ERROR)
        {
            //show error, missed intent
            int errorId = mService.getError();

            if (errorId != MSG_ID_NONE)
            {
                showErrorDialog(errorId, mService.getFinishOnClose());
            }
        }
    }

    private void initBroadcastManager()
    {
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(this);
    }

    private void initReceiverIntentFilter()
    {
        mReceiverIntentFilter = new IntentFilter();
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_CURRENT_VERSION_UPDATE);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_UPDATE_VERSION_UPDATE);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_PROGRESS_MAX_UPDATE);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_PROGRESS_UPDATE);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_STATUS_UPDATE);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_DISCONNECTED);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_CONNECTED);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_ERROR);
    }

    private void initBroadcastReceiver()
    {
        mBroadcastReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                if (intent == null)
                {
                    Log.e(TAG, "Received null intent!");
                    return;
                }

                String action = intent.getAction();

                Log.d(TAG, "Received event " + action);
                switch (action)
                {
                    case ZentriOSBLEService.ACTION_CURRENT_VERSION_UPDATE:
                        setVersionFromIntent(mCurrentVersion, intent);
                        break;

                    case ZentriOSBLEService.ACTION_UPDATE_VERSION_UPDATE:
                        setVersionFromIntent(mUpdateVersion, intent);
                        break;

                    case ZentriOSBLEService.ACTION_PROGRESS_MAX_UPDATE:
                        mProgressBar.setMax(getProgressFromIntent(intent));
                        break;

                    case ZentriOSBLEService.ACTION_PROGRESS_UPDATE:
                        mProgressBar.setProgress(getProgressFromIntent(intent));
                        break;

                    case ZentriOSBLEService.ACTION_STATUS_UPDATE:
                        setStatusFromIntent(mStatus, intent);
                        break;

                    case ZentriOSBLEService.ACTION_CONNECTED:
                        Log.d(TAG, "Got connected event");
                        //ZentriOSBLEService re-inits OTAManager and reads version etc
                        break;

                    case ZentriOSBLEService.ACTION_DISCONNECTED:
                        Log.d(TAG, "Got disconnected event");
                        OTAActivity.this.finish();
                        break;

                    case ZentriOSBLEService.ACTION_ERROR:
                        onError(getErrorFromIntent(intent), getFinishOnCloseFromIntent(intent));
                        break;
                }
            }
        };
    }

    private void startTimeout(Runnable timeoutTask, long timeout_ms)
    {
        if (mHandler != null)
        {
            mHandler.postDelayed(timeoutTask, timeout_ms);
        }
        else
        {
            Log.d(TAG, "Failed to start timeout, handler was null!");
        }
    }

    private void cancelTimeout(Runnable timeoutTask)
    {
        if (mHandler != null)
        {
            mHandler.removeCallbacks(timeoutTask);//cancel timeout
        }
        else
        {
            Log.d(TAG, "Failed to cancel timeout, handler was null!");
        }
    }

    private void initTimeouts()
    {
        mHandler = new Handler();

        mServiceConnectionTimeoutTask = new Runnable()
        {
            @Override
            public void run()
            {
                mStatus.setTextColor(getResources().getColor(R.color.zentri_red));
                mStatus.setText(R.string.error_connection_timeout);
            }
        };
    }

    private void setStatusError(String status)
    {
        mStatus.setText(status);
        mStatus.setTextColor(getResources().getColor(R.color.zentri_red));
    }

    static void setVersionFromIntent(TextView view, Intent intent)
    {
        view.setText(getStringFromIntent(ZentriOSBLEService.EXTRA_VERSION, intent));
    }

    void setStatusFromIntent(TextView view, Intent intent)
    {
        Status status = (Status) intent.getSerializableExtra(ZentriOSBLEService.EXTRA_STATUS);

        setState(status);
    }

    static int getErrorFromIntent(Intent intent)
    {
        return intent.getIntExtra(ZentriOSBLEService.EXTRA_ERROR, 0);
    }

    static boolean getFinishOnCloseFromIntent(Intent intent)
    {
        return intent.getBooleanExtra(ZentriOSBLEService.EXTRA_FINISH, false);
    }

    static int getProgressFromIntent(Intent intent)
    {
        return intent.getIntExtra(ZentriOSBLEService.EXTRA_PROGRESS, 0);
    }

    static String getStringFromIntent(String name, Intent intent)
    {
        String str = intent.getStringExtra(name);

        if (str != null)
        {
            return str;
        }
        else
        {
            return "";
        }
    }

    public void showDisconnectDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                dismissCurrentDialog();
                mCurrentDialog = Util.showProgressDialog(OTAActivity.this,
                                                         R.string.disconnect_dialog_title,
                                                         R.string.disconnect_dialog_message);
            }
        });
    }

    public void showErrorDialog(int msgId, boolean finishOnClose)
    {
        dismissCurrentDialog();
        mCurrentDialog = Util.showErrorDialog(this, R.string.error, msgId, finishOnClose);
    }

    private void dismissCurrentDialog()
    {
        if (mCurrentDialog != null)
        {
            mCurrentDialog.dismiss();
            mCurrentDialog = null;
        }
    }

    private void setState(Status status)
    {
        Log.d(TAG, "Setting Activity state to " + status.toString());
        int statusMsgID, buttonTextID, statusColor = R.color.black;
        boolean progressMode = MODE_INDETERMINATE;
        boolean progressVisible = VISIBLE;
        boolean buttonEnabled = BUTTON_ENABLED;

        switch (status)
        {
            case INITIALISING:
                statusMsgID = R.string.status_init;
                buttonTextID = R.string.button_check;
                buttonEnabled = !BUTTON_ENABLED;
                break;

            case CHECKING_FOR_UPDATE:
                statusMsgID = R.string.status_check;
                buttonTextID = R.string.button_check;
                buttonEnabled = !BUTTON_ENABLED;
                break;

            case READY:
                statusMsgID = R.string.status_ready;
                progressVisible = !VISIBLE;
                buttonTextID = R.string.button_update;
                break;

            case UPDATING:
                statusMsgID = R.string.status_updating;
                buttonTextID = R.string.button_abort;
                progressMode = !MODE_INDETERMINATE;
                break;

            case UP_TO_DATE:
                statusMsgID = R.string.status_upToDate;
                progressVisible = !VISIBLE;
                buttonTextID = R.string.button_force;
                break;

            case ERROR:
                //handled in event listener
                statusMsgID = R.string.empty;
                statusColor = R.color.zentri_red;
                progressVisible = !VISIBLE;
                buttonTextID = R.string.button_check;
                break;

            default:
                statusMsgID = R.string.empty;
                statusColor = R.color.zentri_red;
                buttonTextID = R.string.button_check;
                setErrorMode(R.string.error_unexpected);
                break;
        }

        setStatus(statusMsgID, statusColor);

        setProgressBar(0);
        setProgressBarMode(progressMode);

        setProgressBarVisible(progressVisible);

        setButtonText(buttonTextID);
        setButtonEnabled(buttonEnabled);
    }

    private void setErrorMode(int msgID)
    {
        mStatus.setTextColor(getResources().getColor(R.color.zentri_red));

        if (msgID != 0)
        {
            mStatus.setText(msgID);
        }
        else
        {
            mStatus.setText(R.string.error_unexpected);
        }

        setProgressBarVisible(!VISIBLE);

        setButtonText(R.string.button_check);
        setButtonEnabled(true);
    }

    public void setStatus(final int id, final int color)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mStatus.setText(id);
                mStatus.setTextColor(getResources().getColor(color));
            }
        });
    }

    public void setProgressBarMax(final int max)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mProgressBar.setMax(max);
            }
        });
    }

    public void setProgressBarMode(final boolean indeterminate)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mProgressBar.setIndeterminate(indeterminate);
            }
        });
    }

    private void setProgressBarVisible(boolean visible)
    {
        final int visibility;

        if (visible)
        {
            visibility = View.VISIBLE;
        }
        else
        {
            visibility = View.INVISIBLE;
        }

        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mProgressBar.setVisibility(visibility);
            }
        });
    }

    public void setProgressBar(final int bytesSent)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mProgressBar.setProgress(bytesSent);
            }
        });
    }

    public void setButtonText(final int id)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mButton.setText(id);
            }
        });
    }

    public void setButtonEnabled(final boolean enabled)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mButton.setEnabled(enabled);
            }
        });
    }

    public void showSettingsMenu(final Settings settings)
    {
        //show dialog to enter firmware version string
        if (!mSettingsDialogOpen)
        {
            mSettingsDialogOpen = true;

            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    AlertDialog.Builder builder = new AlertDialog.Builder(OTAActivity.this);

                    LayoutInflater inflater = getLayoutInflater();
                    final View dialogView = inflater.inflate(R.layout.settings_dialog,
                            (ViewGroup)findViewById(R.id.ota_view_root),
                            false);
                    // Inflate and set the layout for the dialog
                    // Pass null as the parent view because its going in the dialog layout
                    final EditText editText = (EditText) dialogView.findViewById(R.id.settings_editText);
                    editText.setText(settings.getFirmwareFilename());

                    final CheckBox checkBox = (CheckBox) dialogView.findViewById(R.id.settings_use_latest_checkbox);

                    if (settings.useLatest())
                    {
                        editText.setEnabled(false);
                        checkBox.setChecked(true);
                    }
                    else
                    {
                        editText.setEnabled(true);
                        checkBox.setChecked(false);
                    }

                    checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
                    {
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
                        {
                            if (isChecked)
                            {
                                editText.setEnabled(false);
                            }
                            else
                            {
                                editText.setEnabled(true);
                            }
                        }
                    });

                    builder.setView(dialogView)
                            // Add action buttons
                            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener()
                            {
                                @Override
                                public void onClick(DialogInterface dialog, int id)
                                {
                                    Dialog d = (Dialog) dialog;
                                    EditText editText = (EditText) d.findViewById(R.id.settings_editText);
                                    if (editText != null)
                                    {
                                        settings.setFirmwareFilename(editText.getText().toString());
                                        settings.setUseLatest(checkBox.isChecked());

                                        SettingsManager.saveSettings(OTAActivity.this, settings);
                                        //check for updates
                                        checkForUpdates();

                                        mSettingsDialogOpen = false;
                                    }
                                    else
                                    {
                                        Log.d(TAG, "Couldn't get reference to settings editText!");
                                    }
                                }
                            })
                            .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener()
                            {
                                public void onClick(DialogInterface dialog, int id)
                                {
                                    mSettingsDialogOpen = false;
                                    dismissCurrentDialog();
                                }
                            })
                            .setCancelable(false)
                            .setTitle(R.string.settings_title);

                    mCurrentDialog = builder.create();
                    mCurrentDialog.show();

                    Resources res = getResources();
                    Util.setTitleColour(res, mCurrentDialog, R.color.zentri_orange);
                    Util.setDividerColour(res, mCurrentDialog, R.color.transparent);
                }
            });
        }
    }

    private void openAboutDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Util.makeAboutDialog(OTAActivity.this);
            }
        });
    }

    private void checkForUpdates()
    {
        String filename;
        if (mSettings.useLatest())
        {
            filename = "";//library will automatically pick the filename
        }
        else
        {
            filename = String.format("truconnect-%s.bin", mSettings.getFirmwareFilename());
        }

        mService.checkForUpdates(filename);
    }

    private void onError(int msgId, boolean finishOnClose)
    {
        if (msgId != MSG_ID_NONE)
        {
            showErrorDialog(msgId, finishOnClose);
        }
    }
}
