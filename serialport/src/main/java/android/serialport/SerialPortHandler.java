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

package android.serialport;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class SerialPortHandler extends SerialPort {

    private static final String TAG = "SerialPortHandler";

    /**
     * arguments of the message from clients
     */
    public static final int MSG_ARG1_NO_RSP_TO_SENDER = 0;
    public static final int MSG_ARG1_RSP_TO_SENDER = 1;
    public static final int MSG_ARG2_UNUSED = 0;

    /**
     * MSG_WHAT_READ - a reader thread creation
     *
     * Once there is a read, there is a MSG_WHAT_READ_RESULT returning to clients.
     */
    public static final int MSG_WHAT_READ = 0;

    /**
     * MSG_WHAT_WRITE - write commands to the serial port device
     *
     * Normally, this message is not to expect a quick response (ack) from the serial port device,
     * so a MSG_WHAT_READ is often delivered before this message.
     */
    public static final int MSG_WHAT_WRITE = 1;

    /**
     * MSG_WHAT_WRITE_AND_READ - a kind of command/response pair
     *
     * This message is not expected to deliver between MSG_WHAT_READ and MSG_WHAT_READ_TERMINATION,
     * or the response is unpredictable.
     */
    public static final int MSG_WHAT_WRITE_AND_READ = 2;

    /**
     * MSG_WHAT_READ_TERMINATION - the reader thread termination
     *
     * To terminate the reader thread which is created by MSG_WHAT_READ
     */
    public static final int MSG_WHAT_READ_TERMINATION = 3;

    /**
     * messages to clients
     */
    public static final int MSG_WHAT_RSP = 0;
    public static final int MSG_WHAT_READ_RESULT = 1;

    /*
     * for handling response timeout, we have our own file streams
     *
     * The original design is using blocking read(). If we want to get out of read(), we can close
     * the file stream and the thread is supposed to get exception and then exits. But in practice, the
     * thread never gets the exception.
     * So, an alternative is to check the file stream available() and then read().
     */
    private FileDescriptor mSerialPortFD;
    private FileInputStream mSerialPortInputStream;
    private FileOutputStream mSerialPortOutputStream;

    private static final int READ_BUFFER_SIZE = 128;
    private static final int READ_RESPONSE_TIMEOUT_MS = 10;

    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private byte[] mReadBuffer;
    private int mReadBufferSize = READ_BUFFER_SIZE;
    private int mReadRspTimeout = READ_RESPONSE_TIMEOUT_MS;
    private int mReadSize;

    /*
     * mReaderThread is created in MSG_WHAT_READ message handler and it exits when
     * MSG_WHAT_READ_TERMINATION message handler closes the input stream or interrupts
     * the reader thread.
     */
    private Thread mReaderThread;

    /* where to send the read result to */
    private Handler mClientHandler;


    public void setClientHandler(Handler h) {
        mClientHandler = h;
    }

    private Handler getClientHandler() {
        return mClientHandler;
    }

    public void setReadBufferSize(int size) {
        mReadBufferSize = size;
        mReadBuffer = new byte[mReadBufferSize];
    }

    public void setRspTimeout(int timeout_ms) {
        mReadRspTimeout = timeout_ms;
    }

    public void flush() {
        super.flush();
        mReadSize = 0;
    }

    public Handler getHandler() {
        return mHandler;
    }

    private String byteArrayToString(byte[] array, int length) {
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

    private class SerialPortHandlerException extends Exception {
        public SerialPortHandlerException(String message) {
            super(message);
        }
    }

    public SerialPortHandler(String devicePath, int baudrate)
            throws SecurityException, IOException {
        super(devicePath, baudrate);
        mSerialPortFD = ((FileInputStream)super.getInputStream()).getFD();
        mSerialPortInputStream = new FileInputStream(mSerialPortFD);
        mSerialPortOutputStream = new FileOutputStream(mSerialPortFD);

        mReadBuffer = new byte[mReadBufferSize];
        mHandlerThread = new HandlerThread("SerialPortHandlerThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_WHAT_WRITE_AND_READ: {
                        try {
                            /* discard old data on serial port */
                            flush();
                            /* a thread waiting for the response */
                            Thread rspReader = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    Log.v(TAG, "[MSG_WHAT_WRITE_AND_READ] RSP reader thread runs");
                                    while (!Thread.currentThread().isInterrupted()) {
                                        try {
                                            if (mSerialPortInputStream.available() > 0) {
                                                mReadSize = mSerialPortInputStream.read(mReadBuffer);
                                                if (mReadSize > 0) {
                                                    Log.v(TAG, "[MSG_WHAT_WRITE_AND_READ] <- " + byteArrayToString(mReadBuffer, mReadSize));
                                                } else {
                                                    throw new SerialPortHandlerException("Exit blocking read() but nothing has been read");
                                                }
                                                return;
                                            }
                                        } catch (SerialPortHandlerException e) {
                                            e.printStackTrace();
                                            return;
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                            Log.e(TAG, "[MSG_WHAT_WRITE_AND_READ] blocked on read() too long and another " +
                                                    "thread closed the channel to let me out.");
                                            /* make input stream available for coming requests */
                                            mSerialPortInputStream = new FileInputStream(mSerialPortFD);
                                            return;
                                        }
                                    }
                                    Log.v(TAG, "[MSG_WHAT_WRITE_AND_READ] interrupted");
                                }
                            });
                            rspReader.start();
                            /* write the command */
                            byte[] cmd = (byte[]) msg.obj;
                            Log.v(TAG,"[MSG_WHAT_WRITE_AND_READ] -> " + byteArrayToString(cmd, cmd.length));
                            mSerialPortOutputStream.write(cmd);
                            /* wait for the response */
                            rspReader.join(mReadRspTimeout);
                            if (rspReader.isAlive()) {
                                Log.e(TAG,"[MSG_WHAT_WRITE_AND_READ] RSP TIMEOUT - interrupting the RSP thread");
                                /*
                                 * the original design
                                 *
                                 * If a thread is blocked in an I/O operation on an interruptible
                                 * channel then another thread may invoke the channel's close method.
                                 * This will cause the blocked thread to receive an
                                 * AsynchronousCloseException.
                                 *
                                 * mSerialPortInputStream.close();
                                 */
                                rspReader.interrupt();
                            }
                            if (msg.arg1 == MSG_ARG1_RSP_TO_SENDER) {
                                byte[] rsp = new byte[mReadSize];
                                System.arraycopy(mReadBuffer, 0, rsp, 0, mReadSize);
                                Message rspMsg = getClientHandler().obtainMessage(
                                        SerialPortHandler.MSG_WHAT_RSP,
                                        rsp);
                                getClientHandler().sendMessage(rspMsg);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                    case MSG_WHAT_WRITE: {
                        try {
                            byte[] cmd = (byte[]) msg.obj;
                            Log.v(TAG,"[MSG_WHAT_WRITE] " + byteArrayToString(cmd, cmd.length));
                            mSerialPortOutputStream.write(cmd);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                    case MSG_WHAT_READ: {
                        /* discard old data on serial port */
                        flush();
                        /* a background thread reading the serial port */
                        mReaderThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                Log.v(TAG, "[MSG_WHAT_READ] background reader thread runs");
                                while (!Thread.currentThread().isInterrupted()) {
                                    try {
                                        if (mSerialPortInputStream.available() > 0) {
                                            mReadSize = mSerialPortInputStream.read(mReadBuffer);
                                            if (mReadSize > 0) {
                                                Log.v(TAG, "[MSG_WHAT_READ] " + byteArrayToString(mReadBuffer, mReadSize));
                                                byte[] rsp = new byte[mReadSize];
                                                System.arraycopy(mReadBuffer, 0, rsp, 0, mReadSize);
                                                Message msg = getClientHandler().obtainMessage(
                                                        SerialPortHandler.MSG_WHAT_READ_RESULT,
                                                        rsp);
                                                getClientHandler().sendMessage(msg);
                                            } else {
                                                throw new SerialPortHandlerException("Exit blocking read() but nothing has been read");
                                            }
                                        }
                                    } catch (SerialPortHandlerException e) {
                                        e.printStackTrace();
                                        return;
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                        mSerialPortInputStream = new FileInputStream(mSerialPortFD);
                                        return;
                                    }
                                }
                                Log.v(TAG, "[MSG_WHAT_READ] interrupted");
                            }
                        });
                        mReaderThread.start();
                        break;
                    }
                    case MSG_WHAT_READ_TERMINATION: {
                        if (mReaderThread.isAlive()) {
                            Log.v(TAG, "[MSG_WHAT_READ_TERMINATION] interrupting the reader thread");
                            mReaderThread.interrupt();
                        } else {
                            Log.v(TAG, "[MSG_WHAT_READ_TERMINATION] the reader thread is not alive");
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        };
    }
}
