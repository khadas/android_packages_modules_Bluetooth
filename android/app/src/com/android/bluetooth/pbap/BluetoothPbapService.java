/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.pbap;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.IBluetoothPbap;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.sqlite.SQLiteException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.support.annotation.VisibleForTesting;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.bluetooth.IObexConnectionHandler;
import com.android.bluetooth.ObexServerSockets;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.sdp.SdpManager;
import com.android.bluetooth.util.DevicePolicyUtils;

import java.util.ArrayList;
import java.util.List;

public class BluetoothPbapService extends ProfileService implements IObexConnectionHandler {
    private static final String TAG = "BluetoothPbapService";

    /**
     * To enable PBAP DEBUG/VERBOSE logging - run below cmd in adb shell, and
     * restart com.android.bluetooth process. only enable DEBUG log:
     * "setprop log.tag.BluetoothPbapService DEBUG"; enable both VERBOSE and
     * DEBUG log: "setprop log.tag.BluetoothPbapService VERBOSE"
     */

    public static final boolean DEBUG = true;

    public static final boolean VERBOSE = true;

    /**
     * Intent indicating incoming obex authentication request which is from
     * PCE(Carkit)
     */
    static final String AUTH_CHALL_ACTION = "com.android.bluetooth.pbap.authchall";

    /**
     * Intent indicating obex session key input complete by user which is sent
     * from BluetoothPbapActivity
     */
    static final String AUTH_RESPONSE_ACTION = "com.android.bluetooth.pbap.authresponse";

    /**
     * Intent indicating user canceled obex authentication session key input
     * which is sent from BluetoothPbapActivity
     */
    static final String AUTH_CANCELLED_ACTION = "com.android.bluetooth.pbap.authcancelled";

    /**
     * Intent indicating timeout for user confirmation, which is sent to
     * BluetoothPbapActivity
     */
    static final String USER_CONFIRM_TIMEOUT_ACTION =
            "com.android.bluetooth.pbap.userconfirmtimeout";

    /**
     * Intent Extra name indicating session key which is sent from
     * BluetoothPbapActivity
     */
    static final String EXTRA_SESSION_KEY = "com.android.bluetooth.pbap.sessionkey";

    static final String EXTRA_DEVICE = "com.android.bluetooth.pbap.device";

    static final String THIS_PACKAGE_NAME = "com.android.bluetooth";

    static final int MSG_ACQUIRE_WAKE_LOCK = 5004;

    static final int MSG_RELEASE_WAKE_LOCK = 5005;

    static final int MSG_STATE_MACHINE_DONE = 5006;

    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;

    private static final int START_LISTENER = 1;

    static final int USER_TIMEOUT = 2;

    private static final int SHUTDOWN = 4;

    static final int LOAD_CONTACTS = 5;

    private static final int CHECK_SECONDARY_VERSION_COUNTER = 6;

    static final int ROLLOVER_COUNTERS = 7;

    static final int USER_CONFIRM_TIMEOUT_VALUE = 30000;

    static final int RELEASE_WAKE_LOCK_DELAY = 10000;

    private PowerManager.WakeLock mWakeLock;

    private static String sLocalPhoneNum;

    private static String sLocalPhoneName;

    private ObexServerSockets mServerSockets = null;

    private static final int SDP_PBAP_SERVER_VERSION = 0x0102;

    private static final int SDP_PBAP_SUPPORTED_REPOSITORIES = 0x0001;

    private static final int SDP_PBAP_SUPPORTED_FEATURES = 0x021F;

    private int mSdpHandle = -1;

    protected Context mContext;

    private PbapHandler mSessionStatusHandler;
    private HandlerThread mHandlerThread;

    private PbapStateMachine mPbapStateMachine;

    // package and class name to which we send intent to check phone book access permission
    private static final String ACCESS_AUTHORITY_PACKAGE = "com.android.settings";
    private static final String ACCESS_AUTHORITY_CLASS =
            "com.android.settings.bluetooth.BluetoothPermissionRequest";

    private Thread mThreadLoadContacts;

    private Thread mThreadUpdateSecVersionCounter;

    private class BluetoothPbapContentObserver extends ContentObserver {
        BluetoothPbapContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            Log.d(TAG, " onChange on contact uri ");
            if (BluetoothPbapUtils.contactsLoaded) {
                if (!mSessionStatusHandler.hasMessages(CHECK_SECONDARY_VERSION_COUNTER)) {
                    mSessionStatusHandler.sendMessage(
                            mSessionStatusHandler.obtainMessage(CHECK_SECONDARY_VERSION_COUNTER));
                }
            }
        }
    }

    private BluetoothPbapContentObserver mContactChangeObserver;

    // process the intent from receiver
    private void parseIntent(final Intent intent) {
        String action = intent.getAction();
        if (DEBUG) {
            Log.d(TAG, "action: " + action);
        }
        if (action == null) {
            return;             // Nothing to do
        }
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
        if (DEBUG) {
            Log.d(TAG, "state: " + state);
        }

        if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (DEBUG) {
                Log.d(TAG, "ACL disconnected for " + device);
            }

            if (mPbapStateMachine != null && mPbapStateMachine.getRemoteDevice().equals(device)) {
                mPbapStateMachine.sendMessage(PbapStateMachine.DISCONNECT);
            }
            return;
        }

        if (action.equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
            int requestType = intent.getIntExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                    BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
            if (requestType != BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS) {
                // this reply is not for us
                return;
            }

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            mSessionStatusHandler.removeMessages(USER_TIMEOUT);

            if (intent.getIntExtra(BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                    BluetoothDevice.CONNECTION_ACCESS_NO)
                    == BluetoothDevice.CONNECTION_ACCESS_YES) {
                if (device != null
                        && intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                    device.setPhonebookAccessPermission(BluetoothDevice.ACCESS_ALLOWED);
                    if (VERBOSE) {
                        Log.v(TAG, "setPhonebookAccessPermission(ACCESS_ALLOWED)");
                    }
                }
                mPbapStateMachine.sendMessage(PbapStateMachine.AUTHORIZED);
            } else {
                if (device != null
                        && intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                    device.setPhonebookAccessPermission(BluetoothDevice.ACCESS_REJECTED);
                    if (VERBOSE) {
                        Log.v(TAG, "setPhonebookAccessPermission(ACCESS_REJECTED)");
                    }
                }
                mPbapStateMachine.sendMessage(PbapStateMachine.REJECTED);
            }
            return;
        }

        if (action.equals(AUTH_RESPONSE_ACTION)) {
            String sessionkey = intent.getStringExtra(EXTRA_SESSION_KEY);
            BluetoothDevice device = intent.getParcelableExtra(EXTRA_DEVICE);
            Message msg = mPbapStateMachine.obtainMessage(PbapStateMachine.AUTH_KEY_INPUT);
            msg.obj = sessionkey;
            mPbapStateMachine.sendMessage(msg);
        } else if (action.equals(AUTH_CANCELLED_ACTION)) {
            BluetoothDevice device = intent.getParcelableExtra(EXTRA_DEVICE);
            mPbapStateMachine.sendMessage(PbapStateMachine.AUTH_CANCELLED);
        } else {
            Log.w(TAG, "Unrecognized intent!");
        }
    }

    private BroadcastReceiver mPbapReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            parseIntent(intent);
        }
    };

    private void closeService() {
        if (VERBOSE) {
            Log.v(TAG, "Pbap Service closeService");
        }

        BluetoothPbapUtils.savePbapParams(this, BluetoothPbapUtils.sPrimaryVersionCounter,
                BluetoothPbapUtils.sSecondaryVersionCounter, BluetoothPbapUtils.sDbIdentifier.get(),
                BluetoothPbapUtils.contactsLastUpdated, BluetoothPbapUtils.totalFields,
                BluetoothPbapUtils.totalSvcFields, BluetoothPbapUtils.totalContacts);

        // exit initSocket early
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        cleanUpServerSocket();

        if (mSessionStatusHandler != null) {
            mSessionStatusHandler.removeCallbacksAndMessages(null);
        }
    }

    private void cleanUpServerSocket() {
        // Step 1, 2: clean up active server session and connection socket
        if (mPbapStateMachine != null) {
            mPbapStateMachine.sendMessage(PbapStateMachine.DISCONNECT);
        }
        // Step 3: clean up SDP record
        cleanUpSdpRecord();
        // Step 4: clean up existing server sockets
        if (mServerSockets != null) {
            mServerSockets.shutdown(false);
            mServerSockets = null;
        }
    }

    private void createSdpRecord() {
        if (mSdpHandle > -1) {
            Log.w(TAG, "createSdpRecord, SDP record already created");
        }
        mSdpHandle = SdpManager.getDefaultManager()
                .createPbapPseRecord("OBEX Phonebook Access Server",
                        mServerSockets.getRfcommChannel(), mServerSockets.getL2capPsm(),
                        SDP_PBAP_SERVER_VERSION, SDP_PBAP_SUPPORTED_REPOSITORIES,
                        SDP_PBAP_SUPPORTED_FEATURES);
        if (DEBUG) {
            Log.d(TAG, "created Sdp record, mSdpHandle=" + mSdpHandle);
        }
    }

    private void cleanUpSdpRecord() {
        if (mSdpHandle < 0) {
            Log.w(TAG, "cleanUpSdpRecord, SDP record never created");
            return;
        }
        int sdpHandle = mSdpHandle;
        mSdpHandle = -1;
        SdpManager sdpManager = SdpManager.getDefaultManager();
        if (DEBUG) {
            Log.d(TAG, "cleanUpSdpRecord, mSdpHandle=" + sdpHandle);
        }
        if (sdpManager == null) {
            Log.e(TAG, "sdpManager is null");
        } else if (!sdpManager.removeSdpRecord(sdpHandle)) {
            Log.w(TAG, "cleanUpSdpRecord, removeSdpRecord failed, sdpHandle=" + sdpHandle);
        }
    }

    private class PbapHandler extends Handler {
        private PbapHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (VERBOSE) {
                Log.v(TAG, "Handler(): got msg=" + msg.what);
            }

            switch (msg.what) {
                case START_LISTENER:
                    mServerSockets = ObexServerSockets.create(BluetoothPbapService.this);
                    if (mServerSockets == null) {
                        Log.w(TAG, "ObexServerSockets.create() returned null");
                        break;
                    }
                    createSdpRecord();
                    // fetch Pbap Params to check if significant change has happened to Database
                    BluetoothPbapUtils.fetchPbapParams(mContext);
                    break;
                case USER_TIMEOUT:
                    Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                    intent.setPackage(getString(R.string.pairing_ui_package));
                    PbapStateMachine stateMachine = (PbapStateMachine) msg.obj;
                    intent.putExtra(BluetoothDevice.EXTRA_DEVICE, stateMachine.getRemoteDevice());
                    intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                            BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
                    sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
                    stateMachine.sendMessage(PbapStateMachine.REJECTED);
                    break;
                case MSG_ACQUIRE_WAKE_LOCK:
                    if (mWakeLock == null) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                "StartingObexPbapTransaction");
                        mWakeLock.setReferenceCounted(false);
                        mWakeLock.acquire();
                        Log.w(TAG, "Acquire Wake Lock");
                    }
                    mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
                    mSessionStatusHandler.sendMessageDelayed(
                            mSessionStatusHandler.obtainMessage(MSG_RELEASE_WAKE_LOCK),
                            RELEASE_WAKE_LOCK_DELAY);
                    break;
                case MSG_RELEASE_WAKE_LOCK:
                    if (mWakeLock != null) {
                        mWakeLock.release();
                        mWakeLock = null;
                    }
                    break;
                case SHUTDOWN:
                    closeService();
                    break;
                case LOAD_CONTACTS:
                    loadAllContacts();
                    break;
                case CHECK_SECONDARY_VERSION_COUNTER:
                    updateSecondaryVersion();
                    break;
                case ROLLOVER_COUNTERS:
                    BluetoothPbapUtils.rolloverCounters();
                    break;
                case MSG_STATE_MACHINE_DONE:
                    mServerSockets.prepareForNewConnect();
                default:
                    break;
            }
        }
    }

    int getConnectionState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (mPbapStateMachine == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mPbapStateMachine.getConnectionState();
    }

    List<BluetoothDevice> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        List<BluetoothDevice> devices = new ArrayList<>();
        if (mPbapStateMachine != null) {
            devices.add(mPbapStateMachine.getRemoteDevice());
        }
        return devices;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();

        if (mPbapStateMachine != null) {
            for (int state : states) {
                if (state == mPbapStateMachine.getConnectionState()) {
                    devices.add(mPbapStateMachine.getRemoteDevice());
                    break;
                }
            }
        }
        return devices;
    }

    static String getLocalPhoneNum() {
        return sLocalPhoneNum;
    }

    static String getLocalPhoneName() {
        return sLocalPhoneName;
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new PbapBinder(this);
    }

    @Override
    protected boolean start() {
        Log.v(TAG, "start()");
        mContext = this;
        mHandlerThread = new HandlerThread("PbapHandlerThread");
        mHandlerThread.start();
        mSessionStatusHandler = new PbapHandler(mHandlerThread.getLooper());
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(AUTH_RESPONSE_ACTION);
        filter.addAction(AUTH_CANCELLED_ACTION);
        BluetoothPbapConfig.init(this);
        registerReceiver(mPbapReceiver, filter);
        try {
            mContactChangeObserver = new BluetoothPbapContentObserver();
            getContentResolver().registerContentObserver(
                    DevicePolicyUtils.getEnterprisePhoneUri(this), false,
                    mContactChangeObserver);
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Illegal state exception, content observer is already registered");
        }

        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            sLocalPhoneNum = tm.getLine1Number();
            sLocalPhoneName = tm.getLine1AlphaTag();
            if (TextUtils.isEmpty(sLocalPhoneName)) {
                sLocalPhoneName = this.getString(R.string.localPhoneName);
            }
        }

        mSessionStatusHandler.sendMessage(mSessionStatusHandler.obtainMessage(LOAD_CONTACTS));
        mSessionStatusHandler.sendMessage(mSessionStatusHandler.obtainMessage(START_LISTENER));
        return true;
    }

    @Override
    protected boolean stop() {
        Log.v(TAG, "stop()");
        if (mContactChangeObserver == null) {
            Log.i(TAG, "Avoid unregister when receiver it is not registered");
            return true;
        }
        try {
            unregisterReceiver(mPbapReceiver);
            getContentResolver().unregisterContentObserver(mContactChangeObserver);
            mContactChangeObserver = null;
        } catch (Exception e) {
            Log.w(TAG, "Unable to unregister pbap receiver", e);
        }
        mSessionStatusHandler.obtainMessage(SHUTDOWN).sendToTarget();
        mHandlerThread.quitSafely();
        return true;
    }

    void disconnect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        synchronized (this) {
            if (mPbapStateMachine != null) {
                mPbapStateMachine.sendMessage(PbapStateMachine.DISCONNECT);
            }
        }
    }

    // Has to be a static class or a memory leak can occur.
    private static class PbapBinder extends IBluetoothPbap.Stub implements IProfileServiceBinder {
        private BluetoothPbapService mService;

        private BluetoothPbapService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "not allowed for non-active user");
                return null;
            }
            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        PbapBinder(BluetoothPbapService service) {
            Log.v(TAG, "PbapBinder()");
            mService = service;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices() {
            if (DEBUG) {
                Log.d(TAG, "getConnectedDevices");
            }
            BluetoothPbapService service = getService();
            if (service == null) {
                return new ArrayList<>(0);
            }
            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            if (DEBUG) {
                Log.d(TAG, "getDevicesMatchingConnectionStates");
            }
            BluetoothPbapService service = getService();
            if (service == null) {
                return new ArrayList<>(0);
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device) {
            if (DEBUG) {
                Log.d(TAG, "getConnectionState: " + device);
            }
            BluetoothPbapService service = getService();
            if (service == null) {
                return BluetoothAdapter.STATE_DISCONNECTED;
            }
            return service.getConnectionState(device);
        }

        @Override
        public void disconnect(BluetoothDevice device) {
            if (DEBUG) {
                Log.d(TAG, "disconnect");
            }
            BluetoothPbapService service = getService();
            if (service == null) {
                return;
            }
            service.disconnect(device);
        }
    }

    @Override
    public boolean onConnect(BluetoothDevice remoteDevice, BluetoothSocket socket) {
        if (remoteDevice == null || socket == null) {
            Log.e(TAG, "onConnect(): Unexpected null. remoteDevice=" + remoteDevice
                    + " socket=" + socket);
            return false;
        }
        mPbapStateMachine = PbapStateMachine.make(this, mHandlerThread.getLooper(), remoteDevice,
                socket,  this, mSessionStatusHandler);
        return true;
    }

    /**
     * Get the phonebook access permission for the device; if unknown, ask the user.
     * Send the result to the state machine.
     * @param stateMachine PbapStateMachine which sends the request
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public void checkOrGetPhonebookPermission(PbapStateMachine stateMachine) {
        BluetoothDevice device = stateMachine.getRemoteDevice();
        int permission = device.getPhonebookAccessPermission();
        if (DEBUG) {
            Log.d(TAG, "getPhonebookAccessPermission() = " + permission);
        }

        if (permission == BluetoothDevice.ACCESS_ALLOWED) {
            stateMachine.sendMessage(PbapStateMachine.AUTHORIZED);
        } else if (permission == BluetoothDevice.ACCESS_REJECTED) {
            stateMachine.sendMessage(PbapStateMachine.REJECTED);
        } else { // permission == BluetoothDevice.ACCESS_UNKNOWN
            // Send an Intent to Settings app to ask user preference.
            Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REQUEST);
            intent.setClassName(BluetoothPbapService.ACCESS_AUTHORITY_PACKAGE,
                    BluetoothPbapService.ACCESS_AUTHORITY_CLASS);
            intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                    BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            intent.putExtra(BluetoothDevice.EXTRA_PACKAGE_NAME, this.getPackageName());
            this.sendOrderedBroadcast(intent, BluetoothPbapService.BLUETOOTH_ADMIN_PERM);
            if (VERBOSE) {
                Log.v(TAG, "waiting for authorization for connection from: " + device);
            }
            /* In case car kit time out and try to use HFP for phonebook
             * access, while UI still there waiting for user to confirm */
            Message msg = mSessionStatusHandler.obtainMessage(BluetoothPbapService.USER_TIMEOUT);
            msg.obj = stateMachine;
            mSessionStatusHandler.sendMessageDelayed(msg, USER_CONFIRM_TIMEOUT_VALUE);
            /* We will continue the process when we receive
             * BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY from Settings app. */
        }
    }

    /**
     * Called when an unrecoverable error occurred in an accept thread.
     * Close down the server socket, and restart.
     */
    @Override
    public synchronized void onAcceptFailed() {
        Log.w(TAG, "PBAP server socket accept thread failed. Restarting the server socket");

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        cleanUpServerSocket();

        if (mSessionStatusHandler != null) {
            mSessionStatusHandler.removeCallbacksAndMessages(null);
        }

        mSessionStatusHandler.sendMessage(mSessionStatusHandler.obtainMessage(START_LISTENER));
    }

    private void loadAllContacts() {
        if (mThreadLoadContacts == null) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    BluetoothPbapUtils.loadAllContacts(mContext,
                            mSessionStatusHandler);
                    mThreadLoadContacts = null;
                }
            };
            mThreadLoadContacts = new Thread(r);
            mThreadLoadContacts.start();
        }
    }

    private void updateSecondaryVersion() {
        if (mThreadUpdateSecVersionCounter == null) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    BluetoothPbapUtils.updateSecondaryVersionCounter(mContext,
                            mSessionStatusHandler);
                    mThreadUpdateSecVersionCounter = null;
                }
            };
            mThreadUpdateSecVersionCounter = new Thread(r);
            mThreadUpdateSecVersionCounter.start();
        }
    }
}
