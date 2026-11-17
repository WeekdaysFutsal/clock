package app.foldstandby;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * 스탠바이 화면. 화면 그리기는 assets/index.html(웹)이 맡고,
 * 이 클래스는 휴대폰 기능(잠금 화면 위 표시, 화면 켜짐 유지, 센서, 알람 등록)을 웹에 이어 줍니다.
 */
public class MainActivity extends Activity implements SensorEventListener {

    /** 자동 실행 서비스가 "이미 켜져 있는지" 확인할 때 봅니다. */
    static volatile boolean visible = false;

    static final String EXTRA_AUTO = "auto";
    static final String AUTO_HINGE = "hinge";
    static final String AUTO_CHARGE = "charge";

    private static final String PAGE = "file:///android_asset/index.html";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean pageReady = false;

    private SensorManager sensors;
    private Sensor hingeSensor;
    private Sensor lightSensor;
    private AlarmManager alarms;
    private KeyguardManager keyguard;
    private SharedPreferences prefs;

    private boolean startedByHinge = false;
    private boolean receiverOn = false;
    private float lastLux = -1f;
    private long lastLuxAt = 0L;
    private int batteryLevel = -1;
    private boolean batteryCharging = false;

    /** 세워둔 휴대폰을 완전히 펴거나 접으면 스탠바이를 닫습니다(세워서 켜진 경우에만). */
    private final Runnable closeIfUnfolded = new Runnable() {
        @Override
        public void run() {
            if (startedByHinge && !isFinishing()) {
                finish();
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                readBattery(intent);
                send("{\"type\":\"battery\",\"level\":" + batteryLevel + ",\"charging\":" + batteryCharging + "}");
            } else if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(action)) {
                send("{\"type\":\"alarm\",\"next\":" + nextAlarmMillis() + "}");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        prefs = getSharedPreferences(AutoLaunchService.PREFS, Context.MODE_PRIVATE);
        if (sensors != null) {
            hingeSensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
            lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        }

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true; // 길게 눌러도 글자 선택 메뉴가 뜨지 않게
            }
        });
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(100);
        settings.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url == null || !url.startsWith("file:///android_asset/");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                send("{\"type\":\"resume\"}");
            }
        });
        setContentView(web);
        immersive();

        readIntent(getIntent());
        ensureService();
        web.loadUrl(PAGE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
    }

    private void readIntent(Intent intent) {
        startedByHinge = intent != null && AUTO_HINGE.equals(intent.getStringExtra(EXTRA_AUTO));
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        web.onResume();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) {
            sticky = registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            sticky = registerReceiver(receiver, filter);
        }
        receiverOn = true;
        if (sticky != null && Intent.ACTION_BATTERY_CHANGED.equals(sticky.getAction())) {
            readBattery(sticky);
        }

        if (sensors != null) {
            if (hingeSensor != null) {
                sensors.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (lightSensor != null) {
                sensors.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
        lastLux = -1f;
        send("{\"type\":\"resume\"}");
    }

    @Override
    protected void onPause() {
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
        if (receiverOn) {
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // 이미 해제된 경우
            }
            receiverOn = false;
        }
        ui.removeCallbacks(closeIfUnfolded);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            web.removeJavascriptInterface("Native");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            immersive();
        }
    }

    /** 상태 표시줄과 내비게이션 바를 숨겨 화면 전체를 씁니다. */
    private void immersive() {
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** 자동 실행을 켜 둔 상태라면 감시 서비스가 떠 있도록 합니다. */
    private void ensureService() {
        boolean wanted = prefs.getBoolean(AutoLaunchService.KEY_HINGE, false)
                || prefs.getBoolean(AutoLaunchService.KEY_CHARGE, false);
        Intent service = new Intent(this, AutoLaunchService.class);
        try {
            if (wanted) {
                startForegroundService(service);
            } else {
                stopService(service);
            }
        } catch (RuntimeException ignored) {
            // 서비스 시작이 막힌 경우에도 시계 화면은 그대로 씁니다.
        }
    }

    // ───────── 센서 ─────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_HINGE_ANGLE) {
            float angle = event.values[0];
            ui.removeCallbacks(closeIfUnfolded);
            if (startedByHinge && (angle < 8f || angle > 165f)) {
                ui.postDelayed(closeIfUnfolded, 1200L);
            }
        } else if (type == Sensor.TYPE_LIGHT) {
            float lux = event.values[0];
            long now = System.currentTimeMillis();
            if (lastLux < 0f || (now - lastLuxAt > 2000L && Math.abs(lux - lastLux) >= 1f)) {
                lastLux = lux;
                lastLuxAt = now;
                send("{\"type\":\"lux\",\"lux\":" + String.format(Locale.US, "%.1f", lux) + "}");
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 쓰지 않습니다.
    }

    // ───────── 웹으로 보내기 ─────────

    private void send(final String json) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null && pageReady) {
                    web.evaluateJavascript("window.Standby&&window.Standby.onNative(" + json + ");", null);
                }
            }
        });
    }

    private void sendError(String text) {
        send("{\"type\":\"error\",\"text\":\"" + text + "\"}");
    }

    private long nextAlarmMillis() {
        if (alarms == null) {
            return 0L;
        }
        AlarmManager.AlarmClockInfo info = alarms.getNextAlarmClock();
        return info == null ? 0L : info.getTriggerTime();
    }

    private void readBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        batteryLevel = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    /** 다른 앱 화면을 열 때: 잠겨 있으면 먼저 잠금을 풀게 한 뒤 엽니다. */
    private void launchUnlocked(final Intent intent, final String doneMessage) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (keyguard != null && keyguard.isKeyguardLocked()) {
                    keyguard.requestDismissKeyguard(MainActivity.this, new KeyguardManager.KeyguardDismissCallback() {
                        @Override
                        public void onDismissSucceeded() {
                            start(intent, doneMessage);
                        }
                    });
                } else {
                    start(intent, doneMessage);
                }
            }
        });
    }

    private void start(Intent intent, String doneMessage) {
        try {
            startActivity(intent);
            if (doneMessage != null) {
                send(doneMessage);
            }
        } catch (RuntimeException e) {
            sendError("휴대폰에서 이 기능을 열지 못했습니다");
        }
    }

    // ───────── 웹에서 부르는 기능 (index.html의 Native.xxx) ─────────

    private final class Bridge {

        /** 다음 알람 시각(밀리초). 없으면 "0". */
        @JavascriptInterface
        public String nextAlarm() {
            return String.valueOf(nextAlarmMillis());
        }

        /** 휴대폰 기본 시계 앱에 알람을 등록합니다. */
        @JavascriptInterface
        public void setAlarm(int hour, int minute) {
            Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
            intent.putExtra(AlarmClock.EXTRA_HOUR, hour);
            intent.putExtra(AlarmClock.EXTRA_MINUTES, minute);
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, "스탠바이");
            intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            launchUnlocked(intent, "{\"type\":\"alarmSent\"}");
        }

        @JavascriptInterface
        public void showAlarms() {
            launchUnlocked(new Intent(AlarmClock.ACTION_SHOW_ALARMS), null);
        }

        /** 0~1 사이 값. 음수면 휴대폰 밝기 설정을 따릅니다. */
        @JavascriptInterface
        public void setBrightness(final double value) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = value < 0
                            ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                            : (float) Math.max(0.01, Math.min(1.0, value));
                    getWindow().setAttributes(lp);
                }
            });
        }

        @JavascriptInterface
        public String battery() {
            if (batteryLevel < 0) {
                return "null";
            }
            return "{\"level\":" + batteryLevel + ",\"charging\":" + batteryCharging + "}";
        }

        /** 자동 실행 상태: 두 스위치, 권한, 센서 유무. */
        @JavascriptInterface
        public String autoState() {
            return "{\"hinge\":" + prefs.getBoolean(AutoLaunchService.KEY_HINGE, false)
                    + ",\"charge\":" + prefs.getBoolean(AutoLaunchService.KEY_CHARGE, false)
                    + ",\"overlay\":" + Settings.canDrawOverlays(MainActivity.this)
                    + ",\"sensor\":" + (hingeSensor != null) + "}";
        }

        @JavascriptInterface
        public void setAuto(boolean hinge, boolean charge) {
            prefs.edit()
                    .putBoolean(AutoLaunchService.KEY_HINGE, hinge && hingeSensor != null)
                    .putBoolean(AutoLaunchService.KEY_CHARGE, charge)
                    .apply();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    ensureService();
                }
            });
        }

        /** 잠금 화면 위로 스스로 뜨려면 '다른 앱 위에 표시' 권한이 필요합니다. */
        @JavascriptInterface
        public void openOverlaySettings() {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            launchUnlocked(intent, null);
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void close() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }
    }
}
