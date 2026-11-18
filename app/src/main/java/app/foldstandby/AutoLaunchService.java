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
 *  - 잠금이 풀려 있을 때는 손에 들고 쓰는 것과 구분해야 하므로, 살짝 벌려(20~100도)
 *    힌지가 위로 가게 가로로 세운 채 가만히 두었을 때만 엽니다.
 *  - (선택) 잠금 상태에서 충전기를 연결하면 스탠바이를 엽니다.
 * 화면이 꺼져 있는 동안에는 센서를 듣지 않아 배터리를 거의 쓰지 않습니다.
 * 스스로 화면을 띄우려면 '다른 앱 위에 표시' 권한이 허용되어 있어야 합니다.
 */
public class AutoLaunchService extends Service implements SensorEventListener {

    static final String PREFS = "standby";
    static final String KEY_HINGE = "auto_hinge";
    static final String KEY_CHARGE = "auto_charge";
    static final String KEY_UNLOCKED = "auto_unlocked";

    private static final String CHANNEL = "auto_launch";
    private static final int NOTIFICATION_ID = 1;
    private static final float MIN_ANGLE = 20f;   // 살짝만 벌려 세운 경우도 포함
    private static final float MAX_ANGLE = 150f;
    private static final float MAX_ANGLE_UNLOCKED = 100f;
    /** 힌지 방향(y축)으로 걸리는 중력이 이보다 작으면 힌지가 수평, 곧 가로로 세운 자세입니다. */
    private static final float MAX_HINGE_TILT = 4.5f;
    private static final float MAX_MOVE = 1.5f;
    private static final long SETTLE_MS = 1500L;
    /** 들어서 닫힌 뒤 다시 켤 때는 더 엄격하게: 거의 움직이지 않은 채로 연속 두 번(약 3초) 확인합니다. */
    private static final float STRICT_MOVE = 0.5f;
    private static final int STRICT_COUNT = 2;
    private static volatile boolean rearmRequested = false;

    /** 스탠바이가 '들거나 옮겨서' 닫혔을 때 부릅니다. 다시 세워두면 켜지도록 준비합니다. */
    static void rearm() {
        rearmRequested = true;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SensorManager sensors;
    private Sensor hingeSensor;
    private Sensor accelSensor;
    private PowerManager power;
    private KeyguardManager keyguard;
    private SharedPreferences prefs;

    private boolean listening = false;
    private boolean receiverOn = false;
    private boolean armed = true;       // 한 번 띄운 뒤에는 각도가 범위를 벗어나거나 화면이 다시 켜질 때까지 쉬기
    private boolean waiting = false;
    private int stillNeeded = 0;        // 0보다 크면 가만히 있는지를 이만큼 연속으로 확인한 뒤에 엽니다
    private int stillCount = 0;
    private float angle = -1f;
    private float angleAtStart = -1f;
    private boolean hasAccel = false;
    private float ax, ay, az;          // 지금 중력 방향
    private float sx, sy, sz;          // 기다리기 시작할 때의 중력 방향

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
            accelSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
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
        hasAccel = false;
        if (listening && accelSensor != null) {
            sensors.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
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
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ACCELEROMETER) {
            ax = event.values[0];
            ay = event.values[1];
            az = event.values[2];
            hasAccel = true;
            if (rearmRequested) {
                rearmRequested = false;
                armed = true;
                stillNeeded = STRICT_COUNT;
                stillCount = 0;
                waiting = false;
                handler.removeCallbacks(settle);
                if (inRange(angle)) {
                    waitAgain();
                }
            }
            return;
        }
        if (type != Sensor.TYPE_HINGE_ANGLE) {
            return;
        }
        angle = event.values[0];
        if (!inRange(angle)) {
            armed = true;
            stillNeeded = 0;
            stillCount = 0;
            waiting = false;
            handler.removeCallbacks(settle);
            return;
        }
        if (armed && !waiting) {
            waitAgain();
        }
    }

    private void waitAgain() {
        waiting = true;
        angleAtStart = angle;
        sx = ax;
        sy = ay;
        sz = az;
        handler.postDelayed(settle, SETTLE_MS);
    }

    /** 잠금이 풀린 상태에서: 살짝 벌려 가로로 세운 채 가만히 있는지. */
    private boolean standingStill(float maxMove, float maxAngle) {
        if (angle > maxAngle) {
            return false;
        }
        if (accelSensor == null) {
            return true; // 확인할 센서가 없으면 각도만 봅니다.
        }
        if (!hasAccel || Math.abs(ay) > MAX_HINGE_TILT) {
            return false;
        }
        float dx = ax - sx;
        float dy = ay - sy;
        float dz = az - sz;
        return dx * dx + dy * dy + dz * dz < maxMove * maxMove;
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
            waitAgain(); // 아직 움직이는 중이면 한 번 더 기다립니다.
            return;
        }
        if (MainActivity.visible || System.currentTimeMillis() < MainActivity.pickingUntil) {
            if (stillNeeded > 0) {
                waitAgain(); // 방금 닫히는 중일 수 있으니 조금 뒤 다시 봅니다.
                return;
            }
            armed = false; // 이미 떠 있거나, 이미지 고르는 창을 띄워 둔 동안에는 끼어들지 않습니다.
            return;
        }
        if (power != null && !power.isInteractive()) {
            return; // 화면이 꺼져 있으면 열지 않습니다. 다시 켜질 때 새로 확인합니다.
        }
        boolean locked = isLocked();
        boolean strict = stillNeeded > 0;
        if (!locked && !prefs.getBoolean(KEY_UNLOCKED, true)) {
            return;
        }
        if (!locked || strict) {
            if (!standingStill(strict ? STRICT_MOVE : MAX_MOVE, locked ? MAX_ANGLE : MAX_ANGLE_UNLOCKED)) {
                stillCount = 0;
                waitAgain(); // 손에 들고 있거나 아직 내려놓는 중이면 조금 뒤 다시 봅니다.
                return;
            }
            if (strict && ++stillCount < stillNeeded) {
                waitAgain();
                return;
            }
        }
        stillNeeded = 0;
        stillCount = 0;
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
