package app.foldstandby;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * 자동 실행 감시 서비스.
 *  - 잠금 화면이 켜진 상태에서 휴대폰을 벌려 세워(20~150도) 1.5초쯤 그대로 두면 스탠바이를 엽니다.
 *  - (선택) 잠금 상태에서 충전기를 연결하면 스탠바이를 엽니다.
 * 화면이 꺼져 있는 동안에는 센서를 듣지 않아 배터리를 거의 쓰지 않습니다.
 * 스스로 화면을 띄우려면 '다른 앱 위에 표시' 권한이 허용되어 있어야 합니다.
 */
public class AutoLaunchService extends Service implements SensorEventListener {

    static final String PREFS = "standby";
    static final String KEY_HINGE = "auto_hinge";
    static final String KEY_CHARGE = "auto_charge";

    private static final String CHANNEL = "auto_launch";
    private static final int NOTIFICATION_ID = 1;
    private static final float MIN_ANGLE = 20f;   // 살짝만 벌려 세운 경우도 포함
    private static final float MAX_ANGLE = 150f;
    private static final long SETTLE_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SensorManager sensors;
    private Sensor hingeSensor;
    private PowerManager power;
    private KeyguardManager keyguard;
    private SharedPreferences prefs;

    private boolean listening = false;
    private boolean receiverOn = false;
    private boolean armed = true;       // 한 번 띄운 뒤에는 각도가 범위를 벗어나거나 화면이 다시 켜질 때까지 쉬기
    private boolean waiting = false;
    private float angle = -1f;
    private float angleAtStart = -1f;

    private final Runnable settle = new Runnable() {
        @Override
        public void run() {
            onSettled();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                armed = true;
                listen();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                unlisten();
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                if (prefs.getBoolean(KEY_CHARGE, false) && isLocked() && !MainActivity.visible) {
                    launch(MainActivity.AUTO_CHARGE);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sensors != null) {
            hingeSensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        }

        if (!goForeground()) {
            stopSelf();
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverOn = true;

        if (power == null || power.isInteractive()) {
            listen();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean wanted = prefs.getBoolean(KEY_HINGE, false) || prefs.getBoolean(KEY_CHARGE, false);
        if (!wanted) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        // 설정이 바뀌었을 수 있으니 센서 듣기를 새로 맞춥니다.
        unlisten();
        if (power == null || power.isInteractive()) {
            listen();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unlisten();
        if (receiverOn) {
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // 이미 해제된 경우
            }
            receiverOn = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 상시 알림을 달고 포그라운드 서비스로 올라갑니다. 실패하면 false. */
    private boolean goForeground() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL, getString(R.string.channel_auto), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            PendingIntent open = PendingIntent.getActivity(
                    this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(getString(R.string.notif_title))
                    .setContentText(getString(R.string.notif_text))
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void listen() {
        if (listening || sensors == null || hingeSensor == null) {
            return;
        }
        if (!prefs.getBoolean(KEY_HINGE, false)) {
            return;
        }
        listening = sensors.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void unlisten() {
        handler.removeCallbacks(settle);
        waiting = false;
        if (listening && sensors != null) {
            sensors.unregisterListener(this);
        }
        listening = false;
    }

    private boolean inRange(float value) {
        return value >= MIN_ANGLE && value <= MAX_ANGLE;
    }

    private boolean isLocked() {
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE) {
            return;
        }
        angle = event.values[0];
        if (!inRange(angle)) {
            armed = true;
            waiting = false;
            handler.removeCallbacks(settle);
            return;
        }
        if (armed && !waiting) {
            waiting = true;
            angleAtStart = angle;
            handler.postDelayed(settle, SETTLE_MS);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 쓰지 않습니다.
    }

    /** 각도가 범위 안에서 잠시 멈춰 있었는지 확인하고, 맞으면 스탠바이를 엽니다. */
    private void onSettled() {
        waiting = false;
        if (!armed || !inRange(angle) || !prefs.getBoolean(KEY_HINGE, false)) {
            return;
        }
        if (Math.abs(angle - angleAtStart) > 10f) {
            // 아직 움직이는 중이면 한 번 더 기다립니다.
            waiting = true;
            angleAtStart = angle;
            handler.postDelayed(settle, SETTLE_MS);
            return;
        }
        if (MainActivity.visible) {
            armed = false;
            return;
        }
        if (!isLocked() || (power != null && !power.isInteractive())) {
            return; // 잠금 화면이 켜져 있을 때만 엽니다.
        }
        armed = false;
        launch(MainActivity.AUTO_HINGE);
    }

    private void launch(String reason) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_AUTO, reason);
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // 권한이 없으면 시스템이 조용히 막습니다.
        }
    }
}
