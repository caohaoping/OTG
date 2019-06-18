package com.example.android.otg;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.github.mjdev.libaums.UsbMassStorageDevice;
import com.github.mjdev.libaums.fs.FileSystem;
import com.github.mjdev.libaums.fs.UsbFile;
import com.github.mjdev.libaums.server.http.UsbFileHttpServer;
import com.github.mjdev.libaums.server.http.server.AsyncHttpServer;
import com.github.mjdev.libaums.server.http.server.HttpServer;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String ACTION_USB_PERMISSION = "com.example.android.otg.USB_PERMISSION";
    private Handler handler = new Handler();
    private ArrayList<String> container = new ArrayList<>();
    private UsbFileHttpServer fileServer;
    private MediaPlayer mediaPlayer;
    private int index;
    private TextView scanMusicsTv;
    private Button playButton;

    private BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.i(TAG, "usb attached");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.i(TAG, "usb detached");
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                Log.i(TAG, "usb permission");
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) {
                        Log.d(TAG, "granted permission for device " + device.getDeviceName());
                        scanUsbFile();
                    }
                } else {
                    Log.e(TAG, "permission denied for device " + device);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        scanMusicsTv = findViewById(R.id.text);
        final TextView scanStateTv = findViewById(R.id.scanMusic);
        scanStateTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanStateTv.setText("扫描...");
                scanMusicsTv.setText("");
                checkUsbStatus();
            }
        });
        playButton = findViewById(R.id.playMusic);
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playMusic();
            }
        });
        registerUsbReceiver();
    }

    private void scanUsbFile() {
        final UsbMassStorageDevice[] devices = UsbMassStorageDevice.getMassStorageDevices(this);
        Log.i(TAG, "scanUsbFile devices size: " + devices.length);
        if (devices.length > 0) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (UsbMassStorageDevice device : devices) {
                            device.init();
                            FileSystem fs = device.getPartitions().get(0).getFileSystem();
                            final UsbFile root = fs.getRootDirectory();
                            HttpServer server = new AsyncHttpServer(8000); // port 8000
                            fileServer = new UsbFileHttpServer(root, server);
                            fileServer.start();
                            container.clear();
                            scanFiles(root);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

    private void scanFiles(UsbFile root) throws IOException {
        for (final UsbFile file : root.listFiles()) {
            if (file.getName().endsWith(".mp3")) {
                Log.i(TAG,
                        "scanUsbFile file: " + file.getName() + ", path: " + file.getAbsolutePath());
                container.add(file.getAbsolutePath());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        CharSequence text = scanMusicsTv.getText();
                        scanMusicsTv.setText(MessageFormat.format("{0}\npath: {1}",
                                text, file.getAbsolutePath()));
                    }
                });
            }
            if (file.isDirectory()) {
                scanFiles(file);
            }
        }
    }

    private void playMusic() {
        if (container != null && !container.isEmpty()) {
            index = index % container.size();
            final String url = "http://127.0.0.1:8000" + container.get(index);
            Log.i(TAG, "playButton: " + url);
            playButton.setText(String.format("Playing url: %s", url));
            index++;
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    Log.i(TAG, "onCompletion: ");
                    playMusic();
                }
            });
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    Log.i(TAG, "onPrepared: ");
                    mp.start();
                }
            });
            try {
                mediaPlayer.setDataSource(url);
                mediaPlayer.prepareAsync();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void checkUsbStatus() {
        Log.i(TAG, "checkUsbStatus: ");
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager != null) {
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            if (!deviceList.isEmpty()) {
                for (UsbDevice device : deviceList.values()) {
                    Log.d(TAG, "checkUsbStatus: " + device.getDeviceName());
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 10,
                            new Intent(ACTION_USB_PERMISSION), 0);
                    usbManager.requestPermission(device, pendingIntent);
                }
            }
        }
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (usbReceiver != null) {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void unRegisterUsbReceiver() {
        if (usbReceiver != null) {
            unregisterReceiver(usbReceiver);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unRegisterUsbReceiver();
        if (fileServer != null) {
            try {
                fileServer.stop();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


}
