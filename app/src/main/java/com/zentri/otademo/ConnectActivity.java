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

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.zentri.zentri_ble.BLEHandlerAPI;


public class ConnectActivity extends AppCompatActivity
{
    private static final String TAG = ConnectActivity.class.getSimpleName();

    private static final String LOC_PERM = Manifest.permission.ACCESS_COARSE_LOCATION;

    private static final long TIMEOUT_SERVICE_CON = 5000;
    private static final long TIMEOUT_SCAN = 5000;

    private static final int BLE_ENABLE_REQ_CODE = 1;
    private static final int LOC_ENABLE_REQ_CODE = 2;

    private static final boolean FINISH_ON_CLOSE = true;
    private static final boolean AUTO_RECONNECT = true;

    private ServiceConnection mConnection;
    private ZentriOSBLEService mService;
    private boolean mBound = false;
    private boolean mUnbinding = false;

    private LocalBroadcastManager mLocalBroadcastManager;
    private BroadcastReceiver mBroadcastReceiver;
    private IntentFilter mReceiverIntentFilter;

    private Handler mHandler;
    private Runnable mServiceConnectionTimeoutTask;
    private Runnable mScanTimeoutTask;

    private Dialog mLocationEnableDialog;
    private Dialog mPermissionRationaleDialog;
    private ProgressBar mScanProgressBar;
    private Dialog mConnectProgressDialog;
    private DeviceList mDeviceList;
    private Button mScanButton;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connect);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        //Starts service if not running
        startService(new Intent(this, ZentriOSBLEService.class));

        init();
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
        mDeviceList.clear();

        //connect to service
        if (mLocalBroadcastManager != null)
        {
            Intent intent = new Intent(this, ZentriOSBLEService.class);
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            startTimeout(mServiceConnectionTimeoutTask, TIMEOUT_SERVICE_CON);

            mLocalBroadcastManager.registerReceiver(mBroadcastReceiver, mReceiverIntentFilter);
        }
    }

    @Override
    protected void onStop()
    {
        super.onStop();

        cancelTimeout(mScanTimeoutTask);
        dismissConnectingDialog();

        if (mBound)
        {
            stopScan();
            mLocalBroadcastManager.unregisterReceiver(mBroadcastReceiver);
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == BLE_ENABLE_REQ_CODE)
        {
            BLEHandlerAPI handler = mService.getBLEHandler();

            if (handler.isBLEEnabled())
            {
                if (requirementsMet())
                {
                    startScan();
                }
            }
            else
            {
                showErrorDialog(R.string.init_fail_msg, FINISH_ON_CLOSE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults)
    {
        switch (requestCode)
        {
            case LOC_ENABLE_REQ_CODE:
            {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED)
                {
                    //check location services are enabled
                    if (requirementsMet())
                    {
                        startScan();
                    }
                }
                else
                {
                    //show unrecoverable error dialog
                    showErrorDialog(R.string.error_permission_denied, true);
                }
            }
        }
    }

    private void init()
    {
        initGUI();
        initDeviceList();
        initTimeouts();
        initBroadcastManager();
        initServiceConnection();
        initReceiverIntentFilter();
        initBroadcastReceiver();
    }

    private void initGUI()
    {
        mScanProgressBar = (ProgressBar) findViewById(R.id.progressBar);
        mScanButton = (Button) findViewById(R.id.scanButton);
        mScanButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                mDeviceList.clear();
                startScan();
            }
        });
    }

    private void initTimeouts()
    {
        mHandler = new Handler();

        mServiceConnectionTimeoutTask = new Runnable()
        {
            @Override
            public void run()
            {
                showErrorDialog(R.string.error_connection_setup, !FINISH_ON_CLOSE);
            }
        };

        mScanTimeoutTask = new Runnable()
        {
            @Override
            public void run()
            {
                stopScan();
            }
        };
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

                if (requirementsMet())
                {
                    startScan();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0)
            {
                mBound = false;

                if (!mUnbinding)
                {
                    //connection lost unexpectedly
                    showErrorDialog(R.string.error_connection_lost_message, !FINISH_ON_CLOSE);
                }

                mUnbinding = false;
            }
        };
    }

    private void initBroadcastManager()
    {
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(this);
    }

    private void initReceiverIntentFilter()
    {
        mReceiverIntentFilter = new IntentFilter();
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_CONNECTED);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_DISCONNECTED);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_SCAN_RESULT);
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
                    case ZentriOSBLEService.ACTION_CONNECTED:
                        dismissConnectingDialog();
                        startOTAActivity();
                        break;

                    case ZentriOSBLEService.ACTION_DISCONNECTED:

                        break;

                    case ZentriOSBLEService.ACTION_SCAN_RESULT:
                        //add result to list
                        addToList(intent.getStringExtra(ZentriOSBLEService.EXTRA_NAME));
                        break;

                    case ZentriOSBLEService.ACTION_ERROR:
                        //show error dialog
                        break;
                }
            }
        };
    }

    //Only adds to the list if not already in it
    private void addToList(final String name)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mDeviceList.add(name);
            }
        });
    }

    private void showLocationEnableDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mLocationEnableDialog = new AlertDialog.Builder(ConnectActivity.this)
                        .setTitle(R.string.loc_enable_title)
                        .setMessage(R.string.loc_enable_msg)
                        .setPositiveButton(R.string.settings, new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialog, int which)
                            {
                                startLocationEnableIntent();
                            }
                        })
                        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialog, int which)
                            {
                                dialog.dismiss();
                                showErrorDialog(R.string.error_loc_disabled, true);
                            }
                        }).create();
                mLocationEnableDialog.show();
                Resources res = getResources();
                Util.setTitleColour(res, mLocationEnableDialog, R.color.zentri_orange);
                Util.setDividerColour(res, mLocationEnableDialog, R.color.transparent);
            }
        });
    }

    public void showConnectingDialog()
    {
        if (!isFinishing())
        {
            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    mConnectProgressDialog = Util.showProgressDialog(ConnectActivity.this,
                                                                     R.string.progress_title,
                                                                     R.string.progress_message);
                }
            });
        }
    }

    private void startOTAActivity()
    {
        startActivity(new Intent(this, OTAActivity.class));
    }

    private void startBLEEnableIntent()
    {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, BLE_ENABLE_REQ_CODE);
    }

    private void startLocationEnableIntent()
    {
        Log.d(TAG, "Directing user to enable location services");
        Intent enableBtIntent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivityForResult(enableBtIntent, LOC_ENABLE_REQ_CODE);
    }

    private void dismissConnectingDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if (mConnectProgressDialog != null)
                {
                    mConnectProgressDialog.dismiss();
                    mConnectProgressDialog = null;
                }
            }
        });
    }

    private void startProgressBar()
    {
        mScanProgressBar.setIndeterminate(true);
        mScanProgressBar.setVisibility(View.VISIBLE);
    }

    private void stopProgressBar()
    {
        mScanProgressBar.setVisibility(View.INVISIBLE);
    }

    private void enableScanButton()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mScanButton.setEnabled(true);
            }
        });
    }

    private void disableScanButton()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mScanButton.setEnabled(false);
            }
        });
    }

    private void showErrorDialog(int msgID, boolean finishOnClose)
    {
        Util.showErrorDialog(this, R.string.error, msgID, finishOnClose);
    }

    private void showToast(final String msg, final int duration)
    {
        if (!isFinishing())
        {
            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    Toast.makeText(getApplicationContext(), msg, duration).show();
                }
            });
        }
    }

    private void initDeviceList()
    {
        ListView deviceListView = (ListView) findViewById(R.id.listView);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.listitem, R.id.textView);

        initialiseListviewListener(deviceListView);
        mDeviceList = new DeviceList(adapter, deviceListView);
    }

    private void initialiseListviewListener(ListView listView)
    {
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id)
            {
                showConnectingDialog();
                mService.getBLEHandler().connect(mDeviceList.get(position), !AUTO_RECONNECT);
            }
        });
    }

    private void openAboutDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Util.makeAboutDialog(ConnectActivity.this);
            }
        });
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

    private void startScan()
    {
        disableScanButton();
        mService.getBLEHandler().startBLEScan();
        startProgressBar();
        startTimeout(mScanTimeoutTask, TIMEOUT_SCAN);
    }

    private void stopScan()
    {
        enableScanButton();
        stopProgressBar();
        mService.getBLEHandler().stopBLEScan();
    }

    private boolean requestPermissions()
    {
        boolean result = true;

        if (ContextCompat.checkSelfPermission(ConnectActivity.this, LOC_PERM)
                != PackageManager.PERMISSION_GRANTED)
        {
            result = false;

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(ConnectActivity.this, LOC_PERM))
            {

                // Show an explanation to the user
                showPermissionsRationaleDialog();
            }
            else
            {
                ActivityCompat.requestPermissions(ConnectActivity.this,
                        new String[]{LOC_PERM},
                        LOC_ENABLE_REQ_CODE);
            }
        }

        return result;
    }

    /**
     * Checks if requirements for this app to run are met.
     * @return true if requirements to run are met
     */
    private boolean requirementsMet()
    {
        boolean reqMet = false;

        if (!mService.getBLEHandler().isBLEEnabled())
        {
            startBLEEnableIntent();
        }
        else if (!requestPermissions())
        {
        }
        else if (!Util.isPreMarshmallow() && !Util.isLocationEnabled(this))
        {
            showLocationEnableDialog();
        }
        else
        {
            reqMet = true;
        }

        return reqMet;
    }

    private void showPermissionsRationaleDialog()
    {
        mPermissionRationaleDialog = new AlertDialog.Builder(ConnectActivity.this)
                .setTitle(R.string.permission_rationale_title)
                .setMessage(R.string.permission_rationale_msg)
                .setPositiveButton(R.string.enable, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dialog.dismiss();
                        ActivityCompat.requestPermissions(ConnectActivity.this,
                                new String[]{LOC_PERM},
                                LOC_ENABLE_REQ_CODE);
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dialog.dismiss();
                        showErrorDialog(R.string.error_permission_denied, true);
                    }
                }).create();

        mPermissionRationaleDialog.show();
        Resources res = getResources();
        Util.setTitleColour(res, mPermissionRationaleDialog, R.color.zentri_orange);
        Util.setDividerColour(res, mPermissionRationaleDialog, R.color.transparent);
    }
}
