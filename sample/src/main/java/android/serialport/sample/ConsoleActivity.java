/*
 * Copyright 2009 Cedric Priscal
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */

package android.serialport.sample;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.util.Log;

public class ConsoleActivity extends SerialPortActivity {

    private static final String TAG = "ConsoleActivity";
    EditText mReception;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.console);

        //		setTitle("Loopback test");
        mReception = (EditText) findViewById(R.id.EditTextReception);

        EditText Emission = (EditText) findViewById(R.id.EditTextEmission);
        Emission.setRawInputType(InputType.TYPE_CLASS_TEXT);
        Emission.setImeOptions(EditorInfo.IME_ACTION_GO);
        Emission.setOnEditorActionListener(new OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_UNSPECIFIED && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int i, j;
                    CharSequence t = v.getText();
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
                    try {
                        mOutputStream.write(new String(text).getBytes(), 0, j);
                        String hex = new String();
                        for (i = 0; i < j; i++) {
                            if (i != 0)
                                hex += ", ";
                            hex += String.format("%02x", (int)text[i]);
                        }
                        Log.v(TAG, "writing hex: '" + hex + "' length = " + j);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    v.setText("");
                }
                return false;
            }
        });
    }

    @Override
    protected void onDataReceived(final byte[] buffer, final int size) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (mReception != null) {
                    mReception.append(new String(buffer, 0, size));
                }
            }
        });
    }
}
