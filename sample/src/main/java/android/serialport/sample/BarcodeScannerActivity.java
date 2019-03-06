/*
 * Copyright (C) 2019 Hsinko Yu <hsinkoyu@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.serialport.sample;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.serialport.SerialPortHandler;
import android.text.InputFilter;
import android.text.Spanned;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class BarcodeScannerActivity extends Activity {

    private static final String TAG = "BarcodeScannerActivity";

    public static final String BCS_TTY_DEVICE = "/dev/ttyMSM1";
    public static final int BCS_TTY_BAUDRATE = 115200;
    public static final int BCS_POWERED_ON_INIT_TIME_MS = 1500;
    /* The PWRDWN signal goes high ~1.2s after the wake up event occurs. */
    public static final int BCS_WAKE_UP_TIME_MS = 1200;

    public static final int BCS_TRIGGER_MODE_MANUAL_TRIGGER = 0;
    public static final int BCS_TRIGGER_MODE_SERIAL_TRIGGER = 1;
    public static final int BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER = 2;
    public static final int BCS_TRIGGER_MODE_PRESENTATION_MODE = 3;
    public static final int BCS_TRIGGER_MODE_STREAMING_PRESENTATION_MODE = 4;

    public static final int BCS_TTY_ACK_TIMEOUT_MS = 200;
    public static final int BCS_TTY_READ_BUFFER_SIZE = 512;
    public static final int READ_TIME_OUT_DEFAULT = 30;

    /* local messages */
    private static final int MSG_BASE = 0x1000;
    private static final int MSG_PERFORM_SCANNING_ACTIVATION = MSG_BASE + 0;

    private static SerialPortHandler mBCS = null;
    private Handler mHandler; /* UI thread handler */
    private int mReadTimeOut = -1;
    private int mLastTriggeredMode = -1;
    private TextView mBarcode;
    private ScanningDialog mScanningDlg;
    private WaitingDialog mInitializingDlg;
    private WaitingDialog mWakeupDlg;

    private String byteArrayToPrintableString(byte[] array, int length) {
        StringBuilder sb = new StringBuilder();

        if (array != null) {
            if (length > array.length)
                length = array.length;
            for (int i = 0; i < length; i++) {
                if (array[i] >= 0 && array[i] <= 0x1f) {
                    /* nonprintable characters */
                    final String[] chars = new String[] {"NUL", "SOH", "STX", "ETX", "EOT", "ENQ",
                            "ACK", "BEL", "BS", "TAB", "LF", "VT", "FF", "CR", "SO", "SI", "DLE",
                            "DC1", "DC2", "DC3", "DC4", "NAK", "SYN", "ETB", "CAN", "EM", "SUB",
                            "ESC", "FS", "GS", "RS", "US"};
                    sb.append("[" + chars[array[i]] + "]");
                } else {
                    sb.append((char)array[i]);
                }
            }
        }

        return sb.toString();
    }

    /*
     * the default value for the settings for reference
     *
     * [MSG_WHAT_WRITE_AND_READ] -> [SYN]M[CR]IMGPWR^.
     * [MSG_WHAT_WRITE_AND_READ] <- IMGPWR1[ACK].
     *
     * [MSG_WHAT_WRITE_AND_READ] -> [SYN]M[CR]SDRTIM^.
     * [MSG_WHAT_WRITE_AND_READ] <- SDRTIM1[ACK].
     *
     * [MSG_WHAT_WRITE_AND_READ] -> [SYN]M[CR]232LPT?.
     * [MSG_WHAT_WRITE_AND_READ] <- 232LPT0[ACK].
     *
     * [MSG_WHAT_WRITE_AND_READ] -> [SYN]M[CR]232LPT*.
     * [MSG_WHAT_WRITE_AND_READ] <- 232LPT0-300[ACK].
     *
     * [MSG_WHAT_WRITE_AND_READ] -> [SYN]M[CR]TRGLPT?.
     * [MSG_WHAT_WRITE_AND_READ] <- TRGLPT120[ACK].
     *
     * [MSG_WHAT_WRITE_AND_READ] -> [SYN]M[CR]TRGLPT*.
     * [MSG_WHAT_WRITE_AND_READ] <- TRGLPT0-300[ACK].
     */
    private void postPoweredOn() {
        final int delayed = BCS_POWERED_ON_INIT_TIME_MS;
        byte[] cmd;
        Message msg;

        /* to enter Standby mode while in Idle mode for 30 seconds */
        cmd = new byte[] {0x16, 'M', 0x0d, '2', '3', '2', 'L', 'P', 'T', '3', '0', '!'};
        msg = mBCS.getHandler().obtainMessage(
                SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                SerialPortHandler.MSG_ARG2_UNUSED,
                cmd);
        mBCS.getHandler().sendMessageDelayed(msg, delayed);

        /*
         * Power Off mode is entered when the menu command TRGLPT expires while in
         * Manual Low Power Trigger mode (TRGMOD2).
         */
        cmd = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'L', 'P', 'T', '3', '0', '!'};
        msg = mBCS.getHandler().obtainMessage(
                SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                SerialPortHandler.MSG_ARG2_UNUSED,
                cmd);
        mBCS.getHandler().sendMessageDelayed(msg, delayed);

        /* reset the default value */
        mReadTimeOut = -1;
        mLastTriggeredMode = -1;
    }

    private boolean isPowered() {
        try {
            FileInputStream signals = new FileInputStream("/sys/devices/soc/soc:n668x_db_platform/signals");
            try {
                byte[] content = new byte[128];
                int n = signals.read(content, 0, content.length);
                if (n > 0) {
                    String s = new String(content, 0, n);
                    int x = s.indexOf("V_IN=");
                    if (x != -1) {
                        if (s.charAt(x + 5) == '1')
                            return true;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            signals.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    private void switchPower(boolean on) {
        if (isPowered() == on)
            return;

        /* "V_IN=1" to supply power (3.3V) */
        try {
            FileOutputStream signals = new FileOutputStream("/sys/devices/soc/soc:n668x_db_platform/signals");
            String s = "V_IN=" + (on ? "1" : "0");
            try {
                signals.write(s.getBytes("US-ASCII"));
            } catch (IOException e) {
                e.printStackTrace();
            }
            signals.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        /* wait for initialization */
        if (on) {
            mInitializingDlg.show();
            postPoweredOn();
        }
    }

    public boolean onOptionsItemSelected(MenuItem item){
        Log.v(TAG, "onOptionsItemSelected");
        finish();
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_barcode_scanner);

        /* action bar */
        ActionBar actionBar = getActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setIcon(new ColorDrawable(getResources().getColor(android.R.color.transparent)));

        /* communication interface */
        try {
            openSerialPort();
            mBCS.setReadBufferSize(BCS_TTY_READ_BUFFER_SIZE);
            mBCS.setRspTimeout(BCS_TTY_ACK_TIMEOUT_MS);
        } catch (IOException e) {
            e.printStackTrace();
            finish();
            return;
        }

        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case SerialPortHandler.MSG_WHAT_READ_RESULT: {
                        String barcode = new String((byte[])msg.obj);
                        if (!mScanningDlg.isPresentationMode()) {
                            mScanningDlg.dismiss();
                        } else {
                            mScanningDlg.incPresentationScannedCount();
                            barcode = "#" + mScanningDlg.getPresentationScannedCount() + ": " + barcode;
                        }
                        mBarcode.setText(barcode);
                        break;
                    }
                    case SerialPortHandler.MSG_WHAT_RSP: {
                        byte[] rsp = (byte[]) msg.obj;
                        mBarcode.setText(byteArrayToPrintableString(rsp, rsp.length));
                        break;
                    }
                    case MSG_PERFORM_SCANNING_ACTIVATION: {
                        findViewById(R.id.activate).performClick();
                        break;
                    }
                    default:
                        break;
                }
            }
        };
        mBCS.setClientHandler(mHandler);

        mBarcode = findViewById(R.id.barcode);
        mScanningDlg = new ScanningDialog(this);
        mInitializingDlg = new WaitingDialog(this, "\nInitializing\n\nPlease wait...\n",
                BCS_POWERED_ON_INIT_TIME_MS);
        mWakeupDlg = new WaitingDialog(this, "\nWaking up from power off mode\n\nPlease wait...\n",
                BCS_WAKE_UP_TIME_MS);
        ((Spinner)findViewById(R.id.trigger_mode)).setSelection(BCS_TRIGGER_MODE_SERIAL_TRIGGER);

        Switch power = findViewById(R.id.power_switch);
        power.setChecked(isPowered());
        power.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                switchPower(isChecked);
                findViewById(R.id.activate).setEnabled(isChecked);
                findViewById(R.id.cmd_send).setEnabled(isChecked);
            }
        });

        EditText timeout = findViewById(R.id.read_time_out);
        timeout.setText(Integer.toString(READ_TIME_OUT_DEFAULT));
        timeout.setSelection(timeout.getText().length());
        timeout.setFilters(new InputFilter[] { new MinMaxFilter(0, 300) });

        Button activate = findViewById(R.id.activate);
        activate.setEnabled(isPowered());
        activate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Spinner trigger_mode = findViewById(R.id.trigger_mode);
                boolean isPowerDown = false;

                /* is in standby or in power off mode */
                try {
                    FileInputStream signals = new FileInputStream("/sys/devices/soc/soc:n668x_db_platform/signals");
                    try {
                        byte[] content = new byte[128];
                        int n = signals.read(content, 0, content.length);
                        if (n > 0) {
                            String s = new String(content, 0, n);
                            int x = s.indexOf("nPWRDWN=");
                            if (x != -1) {
                                if (s.charAt(x + 8) == '0')
                                    isPowerDown = true;
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    signals.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (isPowerDown) {
                    if (mLastTriggeredMode == BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER) {
                        /* power off mode*/
                        mWakeupDlg.show();
                        Thread trigger = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    FileOutputStream signals = new FileOutputStream("/sys/devices/soc/soc:n668x_db_platform/signals");
                                    try {
                                        signals.write("nTRIG=0".getBytes("US-ASCII"));
                                        Thread.sleep(BCS_WAKE_UP_TIME_MS);
                                        signals.write("nTRIG=1".getBytes("US-ASCII"));
                                        Log.v(TAG, "exit power off mode");
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    signals.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                        trigger.start();
                        /* UI thread sleeps for a while to let trigger thread run */
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        Message msg = mHandler.obtainMessage(MSG_PERFORM_SCANNING_ACTIVATION);
                        mHandler.sendMessageDelayed(msg, BCS_WAKE_UP_TIME_MS);
                        return;
                    } else {
                        /* standby mode */
                        try {
                            FileOutputStream signals = new FileOutputStream("/sys/devices/soc/soc:n668x_db_platform/signals");
                            try {
                                signals.write("nTRIG=0".getBytes("US-ASCII"));
                                /* 1.1 milliseconds defined in device specification */
                                Thread.sleep(2);
                                signals.write("nTRIG=1".getBytes("US-ASCII"));
                                Log.v(TAG, "exit standby mode");
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            signals.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                switch (trigger_mode.getSelectedItemPosition()) {
                    case BCS_TRIGGER_MODE_MANUAL_TRIGGER: {
                        EditText timeout = findViewById(R.id.read_time_out);
                        int val = Integer.parseInt(timeout.getText().toString());

                        if (mReadTimeOut != val) {
                            byte[] prefix = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'S', 'T', 'O'};
                            byte[] data;
                            byte[] storage = new byte[] {'!'};
                            try {
                                String timeout_ms = timeout.getText().toString() + "000";
                                data = timeout_ms.getBytes("US-ASCII");
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                                return;
                            }
                            byte[] cmd = new byte[prefix.length + data.length + storage.length];
                            System.arraycopy(prefix, 0, cmd, 0, prefix.length);
                            System.arraycopy(data, 0, cmd, prefix.length, data.length);
                            System.arraycopy(storage, 0, cmd, prefix.length + data.length, storage.length);

                            Message msg = mBCS.getHandler().obtainMessage(
                                    SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                    SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                    SerialPortHandler.MSG_ARG2_UNUSED,
                                    cmd);
                            mBCS.getHandler().sendMessage(msg);

                            mReadTimeOut = val;
                        }

                        if (mLastTriggeredMode == BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER) {
                            byte[] cmd = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'M', 'O', 'D', '0', '!'};
                            Message msg = mBCS.getHandler().obtainMessage(
                                    SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                    SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                    SerialPortHandler.MSG_ARG2_UNUSED,
                                    cmd);
                            mBCS.getHandler().sendMessage(msg);
                        } else {
                            /* the other four trigger modes were finally in TRGMOD0 */
                        }

                        /* start reader thread prior to scanning */
                        mScanningDlg.setTimeout(mReadTimeOut * 1000);
                        mScanningDlg.setTriggeredMode(BCS_TRIGGER_MODE_MANUAL_TRIGGER);
                        mBarcode.setText("");
                        mScanningDlg.show();

                        /* an active low signal from the nTRIG pin of the host interface connector */
                        try {
                            FileOutputStream signals = new FileOutputStream("/sys/devices/soc/soc:n668x_db_platform/signals");
                            try {
                                signals.write("nTRIG=0".getBytes("US-ASCII"));
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                            }
                            signals.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        break;
                    }
                    case BCS_TRIGGER_MODE_SERIAL_TRIGGER: {
                        EditText timeout = findViewById(R.id.read_time_out);
                        int val = Integer.parseInt(timeout.getText().toString());

                        if (mReadTimeOut != val) {
                            byte[] prefix = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'S', 'T', 'O'};
                            byte[] data;
                            byte[] storage = new byte[] {'!'};
                            try {
                                String timeout_ms = timeout.getText().toString() + "000";
                                data = timeout_ms.getBytes("US-ASCII");
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                                return;
                            }
                            byte[] cmd = new byte[prefix.length + data.length + storage.length];
                            System.arraycopy(prefix, 0, cmd, 0, prefix.length);
                            System.arraycopy(data, 0, cmd, prefix.length, data.length);
                            System.arraycopy(storage, 0, cmd, prefix.length + data.length, storage.length);

                            Message msg = mBCS.getHandler().obtainMessage(
                                    SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                    SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                    SerialPortHandler.MSG_ARG2_UNUSED,
                                    cmd);
                            mBCS.getHandler().sendMessage(msg);

                            mReadTimeOut = val;
                        }

                        if (mLastTriggeredMode == BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER) {
                            byte[] cmd = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'M', 'O', 'D', '0', '!'};
                            Message msg = mBCS.getHandler().obtainMessage(
                                    SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                    SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                    SerialPortHandler.MSG_ARG2_UNUSED,
                                    cmd);
                            mBCS.getHandler().sendMessage(msg);
                        } else {
                            /* the other four trigger modes were finally in TRGMOD0 */
                        }

                        /* start reader thread prior to scanning */
                        mScanningDlg.setTimeout(mReadTimeOut * 1000);
                        mScanningDlg.setTriggeredMode(BCS_TRIGGER_MODE_SERIAL_TRIGGER);
                        mBarcode.setText("");
                        mScanningDlg.show();

                        /* run activation command */
                        byte[] cmd = new byte[] {0x16, 'T', 0x0d};
                        Message msg = mBCS.getHandler().obtainMessage(
                                SerialPortHandler.MSG_WHAT_WRITE,
                                SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                SerialPortHandler.MSG_ARG2_UNUSED,
                                cmd);
                        mBCS.getHandler().sendMessage(msg);

                        break;
                    }
                    case BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER: {
                        EditText timeout = findViewById(R.id.read_time_out);
                        int val = Integer.parseInt(timeout.getText().toString());

                        if (mReadTimeOut != val) {
                            byte[] prefix = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'S', 'T', 'O'};
                            byte[] data;
                            byte[] storage = new byte[] {'!'};
                            try {
                                String timeout_ms = timeout.getText().toString() + "000";
                                data = timeout_ms.getBytes("US-ASCII");
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                                return;
                            }
                            byte[] cmd = new byte[prefix.length + data.length + storage.length];
                            System.arraycopy(prefix, 0, cmd, 0, prefix.length);
                            System.arraycopy(data, 0, cmd, prefix.length, data.length);
                            System.arraycopy(storage, 0, cmd, prefix.length + data.length, storage.length);

                            Message msg = mBCS.getHandler().obtainMessage(
                                    SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                    SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                    SerialPortHandler.MSG_ARG2_UNUSED,
                                    cmd);
                            mBCS.getHandler().sendMessage(msg);

                            mReadTimeOut = val;
                        }

                        if (mLastTriggeredMode != BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER) {
                            byte[] cmd = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'M', 'O', 'D', '2', '!'};
                            Message msg = mBCS.getHandler().obtainMessage(
                                    SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                    SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                    SerialPortHandler.MSG_ARG2_UNUSED,
                                    cmd);
                            mBCS.getHandler().sendMessage(msg);
                        }

                        /* start reader thread prior to scanning */
                        mScanningDlg.setTimeout(mReadTimeOut * 1000);
                        mScanningDlg.setTriggeredMode(BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER);
                        mBarcode.setText("");
                        mScanningDlg.show();

                        /* an active low signal from the nTRIG pin of the host interface connector */
                        try {
                            FileOutputStream signals = new FileOutputStream("/sys/devices/soc/soc:n668x_db_platform/signals");
                            try {
                                signals.write("nTRIG=0".getBytes("US-ASCII"));
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                            }
                            signals.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        break;
                    }
                    case BCS_TRIGGER_MODE_PRESENTATION_MODE: {
                        /* set mode and the scanning is automatically triggered */
                        byte[] cmd = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'M', 'O', 'D', '3', '!'};
                        Message msg = mBCS.getHandler().obtainMessage(
                                SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                SerialPortHandler.MSG_ARG2_UNUSED,
                                cmd);
                        mBCS.getHandler().sendMessage(msg);

                        /* start reader thread */
                        mScanningDlg.setTimeout(0);
                        mScanningDlg.setTriggeredMode(BCS_TRIGGER_MODE_PRESENTATION_MODE);
                        mScanningDlg.resetPresentationScannedCount();
                        mBarcode.setText("");
                        mScanningDlg.show();

                        break;
                    }
                    case BCS_TRIGGER_MODE_STREAMING_PRESENTATION_MODE: {
                        /* set mode and the scanning is automatically triggered */
                        byte[] cmd = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'M', 'O', 'D', '8', '!'};
                        Message msg = mBCS.getHandler().obtainMessage(
                                SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                SerialPortHandler.MSG_ARG2_UNUSED,
                                cmd);
                        mBCS.getHandler().sendMessage(msg);

                        /* start reader thread */
                        mScanningDlg.setTimeout(0);
                        mScanningDlg.setTriggeredMode(BCS_TRIGGER_MODE_STREAMING_PRESENTATION_MODE);
                        mScanningDlg.resetPresentationScannedCount();
                        mBarcode.setText("");
                        mScanningDlg.show();

                        break;
                    }
                    default:
                        break;
                }

                mLastTriggeredMode = trigger_mode.getSelectedItemPosition();
            }
        });

        Button send = findViewById(R.id.cmd_send);
        send.setEnabled(isPowered());
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int i, j;
                CharSequence t = ((EditText)findViewById(R.id.cmd_text)).getText();
                char[] text = new char[t.length()];
                char c, c1, c2, c3;
                int y1, y2;
                for (i = 0, j = 0; i < t.length(); i++, j++) {
                    c = t.charAt(i);
                    if (c == '\\') {
                        if ((i + 3) < t.length()) {
                            c1 = t.charAt(i + 1);
                            c2 = t.charAt(i + 2);
                            c3 = t.charAt(i + 3);
                            if ((c1 == 'x') &&
                                    ((c2 >= '0' && c2 <= '9') ||
                                            (c2 >= 'a' && c2 <= 'f') ||
                                            (c2 >= 'A' && c2 <= 'F')) &&
                                    ((c3 >= '0' && c3 <= '9') ||
                                            (c3 >= 'a' && c3 <= 'f') ||
                                            (c3 >= 'A' && c3 <= 'F'))) {
                                if (c2 >= 'a')
                                    y1 = c2 - 'a' + 10;
                                else if (c2 >= 'A')
                                    y1 = c2 - 'A' + 10;
                                else
                                    y1 = c2 - '0';

                                if (c3 >= 'a')
                                    y2 = c3 - 'a' + 10;
                                else if (c3 >= 'A')
                                    y2 = c3 - 'A' + 10;
                                else
                                    y2 = c3 - '0';
                                c = (char) (y1 * 16 + y2);
                                i += 3;
                            }
                        }
                    }
                    text[j] = c;
                }

                byte[] cmd = new String(text, 0, j).getBytes();
                Message msg = mBCS.getHandler().obtainMessage(
                        SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                        SerialPortHandler.MSG_ARG1_RSP_TO_SENDER,
                        SerialPortHandler.MSG_ARG2_UNUSED,
                        cmd);
                mBCS.getHandler().sendMessage(msg);

                mBarcode.setText("");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeSerialPort();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private void openSerialPort() throws IOException {
        if (mBCS == null) {
            mBCS = new SerialPortHandler(BCS_TTY_DEVICE, BCS_TTY_BAUDRATE);
        }
    }

    private void closeSerialPort() {
        if (mBCS != null) {
            mBCS.close();
            mBCS = null;
        }
    }

    private class ScanningDialog extends AlertDialog {
        private static final String TAG = "ScanningDialog";

        private final View mLayout = getLayoutInflater().inflate(R.layout.dialog_barcode_scanning, null);
        private TextView mCountDown;
        private CountDownTimer mTimer;
        private Button mDeactivate;
        private int mTimeout = READ_TIME_OUT_DEFAULT * 1000;
        private int mTriggeredMode;
        private int mPresentationScannedCount;

        private ScanningDialog(Context thiz) {
            super(thiz);
            setView(mLayout);
            setCancelable(false);

            mCountDown = mLayout.findViewById(R.id.scanning_countdown);
            mTimer = resetCountDown(mTimeout);
            mDeactivate = mLayout.findViewById(R.id.deactivate);
            mDeactivate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    /* dismiss is performed first to terminate the reader thread */
                    dismiss();

                    switch (mTriggeredMode) {
                        case BCS_TRIGGER_MODE_MANUAL_TRIGGER: {
                            /* what to do has already been done on dismiss() */
                            break;
                        }
                        case BCS_TRIGGER_MODE_SERIAL_TRIGGER: {
                            /* run deactivation command */
                            byte[] cmd = new byte[] {0x16, 'U', 0x0d};
                            Message msg = mBCS.getHandler().obtainMessage(
                                    SerialPortHandler.MSG_WHAT_WRITE,
                                    SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                    SerialPortHandler.MSG_ARG2_UNUSED,
                                    cmd);
                            mBCS.getHandler().sendMessage(msg);
                            break;
                        }
                        case BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER: {
                            /* what to do has already been done on dismiss() */
                            break;
                        }
                        case BCS_TRIGGER_MODE_PRESENTATION_MODE:
                        case BCS_TRIGGER_MODE_STREAMING_PRESENTATION_MODE: {
                            /*
                             *  Setting trigger mode to Serial Trigger (default configuration) will stop
                             *  current presentation scanning.
                             */
                            byte[] cmd = new byte[] {0x16, 'M', 0x0d, 'T', 'R', 'G', 'M', 'O', 'D', '0', '!'};
                            Message msg = mBCS.getHandler().obtainMessage(
                                    SerialPortHandler.MSG_WHAT_WRITE_AND_READ,
                                    SerialPortHandler.MSG_ARG1_NO_RSP_TO_SENDER,
                                    SerialPortHandler.MSG_ARG2_UNUSED,
                                    cmd);
                            mBCS.getHandler().sendMessage(msg);
                            break;
                        }
                        default:
                            break;
                    }
                }
            });
        }

        private CountDownTimer resetCountDown(int timeout) {
            return new CountDownTimer(timeout, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    String text = "(" + String.format("%d", (millisUntilFinished / 1000) + 1) + ")";
                    mCountDown.setText(text);
                }

                @Override
                public void onFinish() {
                    dismiss();
                }
            };
        }

        private void setTimeout(int timeout) {
            mTimer.cancel();
            mTimer = resetCountDown(timeout);
            mTimeout = timeout;
        }

        private void setTriggeredMode(int triggeredMode) { mTriggeredMode = triggeredMode; }

        private void resetPresentationScannedCount() {
            mPresentationScannedCount = 0;
        }

        private void incPresentationScannedCount() {
            mPresentationScannedCount++;
        }

        private int getPresentationScannedCount() {
            return mPresentationScannedCount;
        }

        private boolean isPresentationMode() {
            return mTriggeredMode == BCS_TRIGGER_MODE_PRESENTATION_MODE ||
                    mTriggeredMode == BCS_TRIGGER_MODE_STREAMING_PRESENTATION_MODE;
        }

        public void show() {
            Log.v(TAG, "show()");
            if (!isShowing()) {
                /* messaging stuff fist */
                Message msg = mBCS.getHandler().obtainMessage(SerialPortHandler.MSG_WHAT_READ);
                mBCS.getHandler().sendMessage(msg);

                /* UI stuff */
                super.show();
                if (mTimeout > 0) {
                    mCountDown.setVisibility(View.VISIBLE);
                    mTimer.start();
                } else {
                    mCountDown.setVisibility(View.GONE);
                }
                Log.v(TAG, "showed");
            }
        }

        public void dismiss() {
            Log.v(TAG, "dismiss()");
            if (isShowing()) {
                if (mTriggeredMode == BCS_TRIGGER_MODE_MANUAL_TRIGGER ||
                        mTriggeredMode == BCS_TRIGGER_MODE_LOW_POWER_MANUAL_TRIGGER) {
                    /* when get scanned, cancelled or timeout, we have to manually restore the nTRIG pin */
                    try {
                        FileOutputStream signals = new FileOutputStream("/sys/devices/soc/soc:n668x_db_platform/signals");
                        try {
                            signals.write("nTRIG=1".getBytes("US-ASCII"));
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                        signals.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                /* messaging stuff fist */
                Message msg = mBCS.getHandler().obtainMessage(SerialPortHandler.MSG_WHAT_READ_TERMINATION);
                mBCS.getHandler().sendMessage(msg);

                /* UI stuff */
                super.dismiss();
                mTimer.cancel();
                Log.v(TAG, "dismissed");
            }
        }
    }

    private class WaitingDialog extends AlertDialog {
        private static final String TAG = "WaitingDialog";

        private final View mLayout = getLayoutInflater().inflate(R.layout.dialog_waiting, null);
        private TextView mWaitText = mLayout.findViewById(R.id.please_wait);
        private int mWaitTime = 1000;

        public WaitingDialog(Context thiz, String waitText, int waitMS) {
            super(thiz);
            mWaitText.setText(waitText);
            mWaitTime = waitMS;
            setView(mLayout);
            setCancelable(false);
        }

        public void setWaitTime(int waitMS) {
            mWaitTime = waitMS;
        }

        public void setWaitText(String waitText) {
            mWaitText.setText(waitText);
        }

        public void show() {
            super.show();
            new CountDownTimer(mWaitTime, 100) {
                @Override
                public void onTick(long millisUntilFinished) {
                }

                @Override
                public void onFinish() { dismiss(); }
            }.start();
        }

        public void dismiss()
        {
            super.dismiss();
        }
    }

    private class MinMaxFilter implements InputFilter {

        private int mIntMin, mIntMax;

        public MinMaxFilter(int minValue, int maxValue) {
            this.mIntMin = minValue;
            this.mIntMax = maxValue;
        }

        public MinMaxFilter(String minValue, String maxValue) {
            this.mIntMin = Integer.parseInt(minValue);
            this.mIntMax = Integer.parseInt(maxValue);
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            try {
                int input = Integer.parseInt(dest.toString() + source.toString());
                if (isInRange(mIntMin, mIntMax, input))
                    return null;
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
            }
            return "";
        }

        private boolean isInRange(int a, int b, int c) {
            return b > a ? c >= a && c <= b : c >= b && c <= a;
        }
    }
}
