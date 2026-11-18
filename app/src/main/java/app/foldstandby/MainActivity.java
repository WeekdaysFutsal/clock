package app.foldstandby;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ContentUris;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.provider.CalendarContract;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.util.Consumer;
import androidx.window.area.WindowAreaCapability;
import androidx.window.area.WindowAreaController;
import androidx.window.area.WindowAreaInfo;
import androidx.window.area.WindowAreaPresentationSessionCallback;
import androidx.window.area.WindowAreaSessionPresenter;
import androidx.window.java.area.WindowAreaControllerCallbackAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 스탠바이 화면. 화면 그리기는 assets/index.html(웹)이 맡고,
 * 이 클래스는 휴대폰 기능(잠금 화면 위 표시, 화면 켜짐 유지, 센서, 알람 등록)을 웹에 이어 줍니다.
 *
 * 휴대폰을 벌려 세우면(텐트 자세) 켜지는 쪽은 안쪽 화면이라 밖에서 시계가 보이지 않습니다.
 * 그래서 이 자세에서는 "듀얼 스크린" 기능으로 시계를 바깥(커버) 화면에 따로 띄우고,
 * 안쪽 화면은 단색으로 채워 무드등으로 씁니다.
 */
public class MainActivity extends Activity
        implements SensorEventListener, WindowAreaPresentationSessionCallback {

    /** 자동 실행 서비스가 "이미 켜져 있는지" 확인할 때 봅니다. */
    static volatile boolean visible = false;

    static final String EXTRA_AUTO = "auto";
    static final String AUTO_HINGE = "hinge";
    static final String AUTO_CHARGE = "charge";
    static final String KEY_DUAL = "dual_cover";
    /** 바깥 화면 방향. 0: 가로(기본), 1: 가로 반대 방향, 2: 돌리지 않음(세로). */
    static final String KEY_COVER_ROT = "cover_rotation";

    private static final String PAGE = "file:///android_asset/index.html";
    private static final int REQUEST_CALENDAR = 41;
    private static final int REQUEST_IMAGE = 42; // 로고 자리에 넣을 이미지
    private static final int REQUEST_BG = 43;    // 배경에 깔 이미지
    /** 사용자가 고른 이미지. 앱 전용 저장 공간에만 두고, 저장소(코드)에는 넣지 않습니다. */
    private static final String KIND_LOGO = "logo";
    private static final String KIND_BG = "bg";
    private static final int IMAGE_MAX_BYTES = 12 * 1024 * 1024; // 고를 수 있는 원본 크기
    private static final int SVG_MAX_BYTES = 1024 * 1024;
    private static final int IMAGE_MAX_SIDE = 1024;             // 긴 변이 이보다 크면 줄여서 저장
    private static final int BG_MAX_SIDE = 1600;                // 배경은 크게 보이므로 조금 더 크게
    /** 이미지 고르는 창이 떠 있는 동안에는 자동 실행이 끼어들지 않게 합니다. */
    static volatile long pickingUntil = 0L;
    /** 이 각도보다 덜 벌어져 있으면 "세워둔 자세"로 보고 바깥 화면에 시계를 띄웁니다. */
    private static final float TENT_START = 135f;
    private static final float TENT_END = 150f;

    // 세워둔 휴대폰을 들거나 옮기면 스탠바이를 닫습니다.
    static final String KEY_CLOSE_MOVE = "close_on_move";
    private static final float STAND_MIN_ANGLE = 20f;
    private static final float STAND_MAX_ANGLE = 150f;
    private static final float STILL_JITTER = 0.6f;   // 이보다 덜 흔들리면 가만히 놓인 것으로 봅니다(m/s²)
    private static final long STILL_MS = 2000L;       // 이만큼 가만히 있으면 그때의 자세를 기준으로 삼습니다
    private static final float MOVE_COS = 0.927f;     // 기준 자세에서 약 22도 넘게 기울면 든 것으로 봅니다
    private static final long MOVE_MS = 350L;
    private static final float SHAKE_JITTER = 1.8f;   // 계속 이만큼 흔들리면 들고 움직이는 것으로 봅니다
    private static final long SHAKE_MS = 700L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;           // 기본 시계 화면
    private WebView coverWeb;      // 듀얼 스크린일 때 바깥 화면에 띄우는 시계
    private FrameLayout coverBox;  // 바깥 화면 전체를 덮는 틀(이 안에서 시계를 가로로 돌립니다)
    private View moodView;         // 듀얼 스크린일 때 안쪽 화면을 채우는 무드등
    private boolean pageReady = false;
    private boolean coverReady = false;

    private SensorManager sensors;
    private Sensor hingeSensor;
    /** 조도 센서 전부. 폴드는 안쪽 화면용과 바깥 화면용이 따로 있을 수 있습니다. */
    private List<Sensor> lightSensors = new ArrayList<>();
    private final Map<Sensor, Float> luxBySensor = new HashMap<>();
    private AlarmManager alarms;
    private KeyguardManager keyguard;
    private SharedPreferences prefs;

    private boolean started = false;
    private boolean startedByHinge = false;
    private boolean receiverOn = false;
    private float hingeAngle = -1f;
    private float lastLux = -1f;
    private long lastLuxAt = 0L;
    private int batteryLevel = -1;
    private boolean batteryCharging = false;

    // 바깥 화면(듀얼 스크린)
    private WindowAreaControllerCallbackAdapter areaController;
    private boolean areaListening = false;
    private WindowAreaInfo rearArea;
    private WindowAreaCapability.Status rearStatus = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED;
    private WindowAreaSessionPresenter coverSession;
    private boolean coverStarting = false;
    private long coverEndedAt = 0L;

    // 밝기와 무드등
    private float clockBrightness = -1f;
    private boolean moodOn = true;
    private int moodColor = 0xFFFF9A3C;
    private float moodLevel = 0.45f;

    private Sensor accelSensor;
    private boolean closeOnMove = true;
    private boolean hasGravity = false;
    private float gx, gy, gz;          // 천천히 따라가는 중력 방향
    private float jitter = 0f;         // 흔들림 정도
    private long accelAt = 0L;
    private boolean hasBase = false;   // 세워둔 자세를 기준으로 잡았는지
    private float bx, by, bz;
    private long stillSince = 0L, tiltSince = 0L, shakeSince = 0L;

    private final Object imageLock = new Object();
    private final Map<String, String> imageCache = new HashMap<>(); // 웹에 넘기는 data URL. 없으면 아직 읽지 않은 것

    /** 세워둔 휴대폰을 완전히 펴거나 접으면 스탠바이를 닫습니다(세워서 켜진 경우에만). */
    private final Runnable closeIfUnfolded = new Runnable() {
        @Override
        public void run() {
            if (startedByHinge && !isFinishing()) {
                finish();
            }
        }
    };

    private final Runnable dualCheck = new Runnable() {
        @Override
        public void run() {
            updateDual();
        }
    };

    private final Consumer<List<WindowAreaInfo>> areaListener = new Consumer<List<WindowAreaInfo>>() {
        @Override
        public void accept(List<WindowAreaInfo> infos) {
            WindowAreaInfo found = null;
            WindowAreaCapability.Status status = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED;
            if (infos != null) {
                for (WindowAreaInfo info : infos) {
                    if (WindowAreaInfo.Type.TYPE_REAR_FACING.equals(info.getType())) {
                        found = info;
                        WindowAreaCapability capability =
                                info.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA);
                        if (capability != null) {
                            status = capability.getStatus();
                        }
                        break;
                    }
                }
            }
            rearArea = found;
            rearStatus = status;
            // 접고 펴는 순간에는 상태가 잠깐 튀므로, 조금 기다렸다가 판단합니다.
            scheduleDualCheck(350L);
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
            accelSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            List<Sensor> found = sensors.getSensorList(Sensor.TYPE_LIGHT);
            if (found != null) {
                lightSensors = found;
            }
        }

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        web = new WebView(this);
        configureWeb(web, false);

        moodView = new View(this);
        moodView.setBackgroundColor(Color.BLACK);
        moodView.setVisibility(View.GONE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(web);
        root.addView(moodView);
        setContentView(root);
        immersive();

        try {
            areaController = new WindowAreaControllerCallbackAdapter(WindowAreaController.getOrCreate());
        } catch (Throwable t) {
            areaController = null; // 듀얼 스크린을 지원하지 않는 기기
        }

        readIntent(getIntent());
        ensureService();
        web.loadUrl(PAGE);
    }

    /** 시계용 웹뷰 공통 설정. cover가 true면 바깥 화면용입니다. */
    private void configureWeb(WebView view, final boolean cover) {
        view.setBackgroundColor(Color.BLACK);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true; // 길게 눌러도 글자 선택 메뉴가 뜨지 않게
            }
        });
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(100);
        settings.setSupportZoom(false);
        view.addJavascriptInterface(new Bridge(), "Native");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return url == null || !url.startsWith("file:///android_asset/");
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (cover) {
                    coverReady = true;
                } else {
                    pageReady = true;
                }
                send("{\"type\":\"resume\"}");
            }
        });
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
        started = true;
        if (areaController != null && !areaListening) {
            try {
                areaController.addWindowAreaInfoListListener(getMainExecutor(), areaListener);
                areaListening = true;
            } catch (Throwable t) {
                areaListening = false;
            }
        }
        scheduleDualCheck(600L);
    }

    @Override
    protected void onStop() {
        visible = false;
        started = false;
        ui.removeCallbacks(dualCheck);
        closeCover();
        if (areaController != null && areaListening) {
            try {
                areaController.removeWindowAreaInfoListListener(areaListener);
            } catch (Throwable ignored) {
                // 이미 해제된 경우
            }
            areaListening = false;
        }
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
            for (Sensor light : lightSensors) {
                sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (accelSensor != null) {
                sensors.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI);
            }
        }
        closeOnMove = prefs.getBoolean(KEY_CLOSE_MOVE, true);
        hasGravity = false;
        resetStand();
        luxBySensor.clear();
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
        closeCover();
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

    // ───────── 바깥 화면에 시계 띄우기(듀얼 스크린) ─────────

    private void scheduleDualCheck(long delayMs) {
        ui.removeCallbacks(dualCheck);
        ui.postDelayed(dualCheck, delayMs);
    }

    /** 벌려 세운 자세인지. 각도 센서가 없으면 항상 그렇다고 봅니다. */
    private boolean tentPosture() {
        if (hingeAngle < 0f) {
            return true;
        }
        return hingeAngle < (coverSession != null ? TENT_END : TENT_START);
    }

    /** 지금 상황에 맞게 바깥 화면 표시를 시작하거나 끝냅니다. */
    private void updateDual() {
        boolean want = started && !isFinishing()
                && prefs.getBoolean(KEY_DUAL, true) && tentPosture();
        if (coverSession != null) {
            if (!want) {
                closeCover();
            }
            return;
        }
        if (!want || coverStarting || areaController == null || rearArea == null) {
            return;
        }
        if (!WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE.equals(rearStatus)) {
            return;
        }
        long sinceEnd = System.currentTimeMillis() - coverEndedAt;
        if (sinceEnd < 1500L) {
            scheduleDualCheck(1500L - sinceEnd + 50L); // 방금 끝난 직후에는 바로 다시 시작하지 않습니다.
            return;
        }
        coverStarting = true;
        try {
            areaController.presentContentOnWindowArea(rearArea.getToken(), this, getMainExecutor(), this);
        } catch (Throwable t) {
            coverStarting = false;
        }
    }

    @Override
    public void onSessionStarted(WindowAreaSessionPresenter session) {
        coverStarting = false;
        coverSession = session;
        try {
            coverReady = false;
            Context coverContext = session.getContext();
            coverBox = new FrameLayout(coverContext);
            coverBox.setBackgroundColor(Color.BLACK);
            coverWeb = new WebView(coverContext);
            configureWeb(coverWeb, true);
            coverBox.addView(coverWeb);
            coverBox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                        ui.post(fitCover);
                    }
                }
            });
            session.setContentView(coverBox);
            ui.post(fitCover);
            coverWeb.loadUrl(PAGE + "?cover=1");
        } catch (Throwable t) {
            closeCover();
            return;
        }
        showMood(true);
    }

    @Override
    public void onSessionEnded(Throwable t) {
        coverStarting = false;
        cleanupCover();
        // 아직 세워둔 상태라면 잠시 뒤 다시 시도합니다.
        scheduleDualCheck(1600L);
    }

    @Override
    public void onContainerVisibilityChanged(boolean isVisible) {
        // 쓰지 않습니다.
    }

    private void closeCover() {
        WindowAreaSessionPresenter session = coverSession;
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
                // 이미 끝난 경우
            }
        }
        cleanupCover();
    }

    private void cleanupCover() {
        boolean wasActive = coverSession != null || coverWeb != null;
        coverSession = null;
        coverReady = false;
        coverBox = null;
        if (coverWeb != null) {
            try {
                coverWeb.removeJavascriptInterface("Native");
                coverWeb.destroy();
            } catch (Throwable ignored) {
                // 정리 중 오류는 무시합니다.
            }
            coverWeb = null;
        }
        if (wasActive) {
            coverEndedAt = System.currentTimeMillis();
            showMood(false);
        }
    }

    /**
     * 바깥 화면은 휴대폰을 가로로 세워도 스스로 돌아가지 않고 세로 그대로입니다.
     * 그래서 시계 화면을 가로 크기로 만든 뒤 90도 돌려서 틀에 맞춥니다.
     * 폴드는 바깥 화면 왼쪽에 힌지가 있고, 세워두면 힌지가 위로 가므로 왼쪽으로(반시계 방향) 돌립니다.
     */
    private final Runnable fitCover = new Runnable() {
        @Override
        public void run() {
            if (coverBox == null || coverWeb == null) {
                return;
            }
            int w = coverBox.getWidth();
            int h = coverBox.getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            int mode = prefs.getInt(KEY_COVER_ROT, 0);
            boolean rotate = mode != 2 && h > w;
            coverWeb.setLayoutParams(new FrameLayout.LayoutParams(
                    rotate ? h : w, rotate ? w : h, Gravity.CENTER));
            coverWeb.setRotation(rotate ? (mode == 1 ? 90f : -90f) : 0f);
        }
    };

    /** 안쪽 화면을 무드등으로 바꾸거나(on), 다시 시계로 되돌립니다. */
    private void showMood(boolean on) {
        if (moodView == null || web == null) {
            return;
        }
        moodView.setVisibility(on ? View.VISIBLE : View.GONE);
        web.setVisibility(on ? View.GONE : View.VISIBLE);
        applyInnerScreen();
        if (!on) {
            send("{\"type\":\"resume\"}");
        }
    }

    /** 안쪽 화면의 밝기와 무드등 색을 지금 상태에 맞춥니다. */
    private void applyInnerScreen() {
        if (moodView == null) {
            return;
        }
        boolean mood = coverSession != null;
        moodView.setBackgroundColor(moodOn ? moodColor : Color.BLACK);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (mood) {
            lp.screenBrightness = moodOn ? Math.max(0.02f, Math.min(1f, moodLevel)) : 0.01f;
        } else {
            lp.screenBrightness = clockBrightness < 0f
                    ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    : Math.max(0.01f, Math.min(1f, clockBrightness));
        }
        getWindow().setAttributes(lp);
    }

    private String rearStatusName() {
        if (areaController == null || rearArea == null) {
            return "unsupported";
        }
        if (WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE.equals(rearStatus)) {
            return "available";
        }
        if (WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE.equals(rearStatus)) {
            return "active";
        }
        if (WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE.equals(rearStatus)) {
            return "unavailable";
        }
        return "unsupported";
    }

    // ───────── 센서 ─────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_HINGE_ANGLE) {
            float angle = event.values[0];
            boolean wasTent = tentPosture();
            hingeAngle = angle;
            if (wasTent != tentPosture() || coverSession == null) {
                scheduleDualCheck(400L);
            }
            ui.removeCallbacks(closeIfUnfolded);
            if (startedByHinge && (angle < 8f || angle > 165f)) {
                ui.postDelayed(closeIfUnfolded, 1200L);
            }
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            onAccel(event.values[0], event.values[1], event.values[2]);
        } else if (type == Sensor.TYPE_LIGHT) {
            // 세워두면 안쪽 센서는 가려져 어둡게 나옵니다. 그래서 센서들 가운데 가장 밝은 값을 씁니다.
            luxBySensor.put(event.sensor, event.values[0]);
            float lux = 0f;
            for (Float value : luxBySensor.values()) {
                lux = Math.max(lux, value);
            }
            long now = System.currentTimeMillis();
            if (lastLux < 0f || (now - lastLuxAt > 2000L && Math.abs(lux - lastLux) >= 1f)) {
                lastLux = lux;
                lastLuxAt = now;
                send("{\"type\":\"lux\",\"lux\":" + String.format(Locale.US, "%.1f", lux)
                        + ",\"n\":" + lightSensors.size() + "}");
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 쓰지 않습니다.
    }

    private void resetStand() {
        hasBase = false;
        stillSince = 0L;
        tiltSince = 0L;
        shakeSince = 0L;
    }

    /**
     * 세워둔 채 2초쯤 가만히 있으면 그 자세를 기준으로 잡고, 그 뒤 기준에서 크게 기울거나(들기)
     * 계속 흔들리면(들고 이동) 스탠바이를 닫아 원래 화면으로 돌아갑니다.
     */
    private void onAccel(float x, float y, float z) {
        long now = System.currentTimeMillis();
        if (!hasGravity) {
            gx = x;
            gy = y;
            gz = z;
            jitter = 0f;
            accelAt = now;
            hasGravity = true;
            return;
        }
        float dt = Math.max(1f, Math.min(500f, (float) (now - accelAt)));
        accelAt = now;
        float a = dt / (250f + dt);
        float dx = x - gx;
        float dy = y - gy;
        float dz = z - gz;
        gx += a * dx;
        gy += a * dy;
        gz += a * dz;
        jitter += a * ((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - jitter);

        boolean stand = closeOnMove && hingeAngle >= STAND_MIN_ANGLE && hingeAngle <= STAND_MAX_ANGLE;
        if (!stand) {
            resetStand();
            return;
        }
        if (!hasBase) {
            // 힌지가 수평(가로로 세움)이거나 수직(책처럼 세움)인 채로 가만히 있을 때만 기준을 잡습니다.
            float hinge = Math.abs(gy);
            boolean resting = jitter < STILL_JITTER && (hinge < 4.5f || hinge > 9.0f);
            if (!resting) {
                stillSince = 0L;
            } else if (stillSince == 0L) {
                stillSince = now;
            } else if (now - stillSince >= STILL_MS) {
                bx = gx;
                by = gy;
                bz = gz;
                hasBase = true;
                tiltSince = 0L;
                shakeSince = 0L;
            }
            return;
        }
        float dot = gx * bx + gy * by + gz * bz;
        float len = (float) (Math.sqrt(gx * gx + gy * gy + gz * gz) * Math.sqrt(bx * bx + by * by + bz * bz));
        boolean tilted = len > 0f && dot / len < MOVE_COS;
        tiltSince = tilted ? (tiltSince == 0L ? now : tiltSince) : 0L;
        shakeSince = jitter > SHAKE_JITTER ? (shakeSince == 0L ? now : shakeSince) : 0L;
        if ((tiltSince != 0L && now - tiltSince >= MOVE_MS) || (shakeSince != 0L && now - shakeSince >= SHAKE_MS)) {
            resetStand();
            if (!isFinishing()) {
                AutoLaunchService.rearm(); // 다시 세워두면 켜지도록
                finish();
            }
        }
    }

    // ───────── 웹으로 보내기 ─────────

    /** 떠 있는 시계 화면(기본·바깥) 모두에 소식을 보냅니다. */
    private void send(final String json) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                String script = "window.Standby&&window.Standby.onNative(" + json + ");";
                if (web != null && pageReady) {
                    web.evaluateJavascript(script, null);
                }
                if (coverWeb != null && coverReady) {
                    coverWeb.evaluateJavascript(script, null);
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

    // ───────── 캘린더(구글 캘린더 포함, 휴대폰에 동기화된 일정) ─────────

    private boolean calendarAllowed() {
        return checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CALENDAR) {
            send("{\"type\":\"calendar\",\"granted\":" + calendarAllowed() + "}");
        }
    }

    // ───────── 내 이미지(휴대폰에서 고른 이미지) ─────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE && requestCode != REQUEST_BG) {
            return;
        }
        final String kind = requestCode == REQUEST_BG ? KIND_BG : KIND_LOGO;
        pickingUntil = 0L;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (saveImage(uri, kind)) {
                    send("{\"type\":\"image\",\"kind\":\"" + kind + "\",\"ok\":true}");
                } else {
                    sendError("이미지를 넣지 못했습니다. PNG, JPG, SVG 파일인지 확인해 주세요");
                }
            }
        }).start();
    }

    private static String kindOf(String kind) {
        return KIND_BG.equals(kind) ? KIND_BG : KIND_LOGO;
    }

    private File imageFile(String kind) {
        return new File(getFilesDir(), KIND_BG.equals(kind) ? "user_bg" : "user_image");
    }

    private static String mimeKey(String kind) {
        return KIND_BG.equals(kind) ? "user_bg_mime" : "user_image_mime";
    }

    /** 고른 이미지를 앱 전용 공간에 저장합니다. 사진은 긴 변을 줄인 PNG로, SVG는 그대로 둡니다. */
    private boolean saveImage(Uri uri, String kind) {
        final int maxSide = KIND_BG.equals(kind) ? BG_MAX_SIDE : IMAGE_MAX_SIDE;
        try {
            String type = getContentResolver().getType(uri);
            byte[] raw = readAll(uri, IMAGE_MAX_BYTES);
            if (raw == null || raw.length == 0) {
                return false;
            }
            byte[] out;
            String mime;
            if ((type != null && type.contains("svg")) || looksLikeSvg(raw)) {
                if (raw.length > SVG_MAX_BYTES) {
                    return false;
                }
                out = raw;
                mime = "image/svg+xml";
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return false;
                }
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
                if (bitmap == null) {
                    return false;
                }
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                int side = Math.max(w, h);
                if (side > maxSide) {
                    float k = maxSide / (float) side;
                    Bitmap small = Bitmap.createScaledBitmap(bitmap,
                            Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
                    if (small != bitmap) {
                        bitmap.recycle();
                    }
                    bitmap = small;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer);
                bitmap.recycle();
                out = buffer.toByteArray();
                mime = "image/png";
            }
            synchronized (imageLock) {
                FileOutputStream file = new FileOutputStream(imageFile(kind));
                try {
                    file.write(out);
                } finally {
                    file.close();
                }
                prefs.edit().putString(mimeKey(kind), mime).apply();
                imageCache.remove(kind);
            }
            return true;
        } catch (Exception e) {
            return false;
        } catch (OutOfMemoryError e) {
            return false;
        }
    }

    private byte[] readAll(Uri uri, int limit) throws java.io.IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
                if (buffer.size() > limit) {
                    return null;
                }
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static boolean looksLikeSvg(byte[] raw) {
        String head = new String(raw, 0, Math.min(raw.length, 1024), java.nio.charset.StandardCharsets.UTF_8);
        return head.contains("<svg");
    }

    /** 저장해 둔 이미지를 웹 화면이 바로 쓸 수 있는 data URL로 돌려줍니다. 없으면 빈 글자. */
    private String imageDataUrl(String kind) {
        synchronized (imageLock) {
            String cached = imageCache.get(kind);
            if (cached != null) {
                return cached;
            }
            String result = "";
            File file = imageFile(kind);
            String mime = prefs.getString(mimeKey(kind), "");
            if (file.exists() && mime.length() > 0) {
                try {
                    FileInputStream in = new FileInputStream(file);
                    try {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(chunk)) > 0) {
                            buffer.write(chunk, 0, n);
                        }
                        result = "data:" + mime + ";base64," + Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
                    } finally {
                        in.close();
                    }
                } catch (Exception e) {
                    result = "";
                }
            }
            imageCache.put(kind, result);
            return result;
        }
    }

    private void removeImage(String kind) {
        synchronized (imageLock) {
            imageFile(kind).delete();
            prefs.edit().remove(mimeKey(kind)).apply();
            imageCache.put(kind, "");
        }
    }

    /** from~to(밀리초) 사이의 일정을 읽어 JSON 배열 글자로 돌려줍니다. */
    private String readEvents(long from, long to) {
        JSONArray out = new JSONArray();
        if (!calendarAllowed()) {
            return out.toString();
        }
        Cursor cursor = null;
        try {
            Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(uri, from);
            ContentUris.appendId(uri, to);
            String[] columns = {
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.DISPLAY_COLOR
            };
            cursor = getContentResolver().query(uri.build(), columns,
                    CalendarContract.Calendars.VISIBLE + "=1", null,
                    CalendarContract.Instances.BEGIN + " ASC");
            int count = 0;
            while (cursor != null && cursor.moveToNext() && count < 300) {
                JSONObject event = new JSONObject();
                event.put("t", cursor.isNull(0) ? "" : cursor.getString(0));
                event.put("b", cursor.getLong(1));
                event.put("e", cursor.getLong(2));
                event.put("a", cursor.getInt(3) == 1);
                event.put("c", cursor.isNull(4) ? 0 : (cursor.getInt(4) & 0xFFFFFF));
                out.put(event);
                count++;
            }
        } catch (Exception e) {
            // 캘린더를 읽지 못하면 일정 없이 보여 줍니다.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return out.toString();
    }

    /** 잠겨 있으면 먼저 잠금을 풀게 한 뒤 실행합니다. */
    private void whenUnlocked(final Runnable action) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (keyguard != null && keyguard.isKeyguardLocked()) {
                    keyguard.requestDismissKeyguard(MainActivity.this, new KeyguardManager.KeyguardDismissCallback() {
                        @Override
                        public void onDismissSucceeded() {
                            action.run();
                        }
                    });
                } else {
                    action.run();
                }
            }
        });
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

        /** 시계 화면 밝기. 0~1 사이 값이고, 음수면 휴대폰 밝기 설정을 따릅니다. */
        @JavascriptInterface
        public void setBrightness(final double value) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    clockBrightness = (float) value;
                    applyInnerScreen();
                }
            });
        }

        /** 무드등: 켬/끔, 색(#RRGGBB), 밝기(0~1). */
        @JavascriptInterface
        public void setMood(final boolean on, final String color, final double level) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    moodOn = on;
                    moodLevel = (float) level;
                    try {
                        moodColor = Color.parseColor(color);
                    } catch (RuntimeException ignored) {
                        // 색 값이 잘못되면 이전 색을 그대로 씁니다.
                    }
                    applyInnerScreen();
                }
            });
        }

        /** 세워둘 때 시계를 바깥 화면에 띄울지. */
        @JavascriptInterface
        public void setDual(boolean enabled) {
            prefs.edit().putBoolean(KEY_DUAL, enabled).apply();
            scheduleDualCheck(100L);
        }

        /** 바깥 화면 표시 상태: 스위치, 기기 상태, 지금 표시 중인지, 접힘 각도. */
        @JavascriptInterface
        public String dualState() {
            return "{\"enabled\":" + prefs.getBoolean(KEY_DUAL, true)
                    + ",\"status\":\"" + rearStatusName() + "\""
                    + ",\"active\":" + (coverSession != null)
                    + ",\"rot\":" + prefs.getInt(KEY_COVER_ROT, 0)
                    + ",\"angle\":" + Math.round(hingeAngle) + "}";
        }

        /** 바깥 화면 방향. 0: 가로, 1: 가로 반대 방향, 2: 세로. */
        @JavascriptInterface
        public void setCoverRotation(int mode) {
            prefs.edit().putInt(KEY_COVER_ROT, mode).apply();
            ui.post(fitCover);
        }

        /** 캘린더 읽기 권한이 있는지. */
        @JavascriptInterface
        public boolean hasCalendar() {
            return calendarAllowed();
        }

        /** 캘린더 읽기 권한을 요청합니다(휴대폰이 허용 여부를 묻는 창을 띄웁니다). */
        @JavascriptInterface
        public void requestCalendar() {
            whenUnlocked(new Runnable() {
                @Override
                public void run() {
                    requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQUEST_CALENDAR);
                }
            });
        }

        /** from~to(밀리초) 사이의 일정. [{t 제목, b 시작, e 끝, a 종일, c 색}] */
        @JavascriptInterface
        public String events(double from, double to) {
            return readEvents((long) from, (long) to);
        }

        /** 휴대폰에서 고른 이미지(data URL). kind는 "logo"(로고 자리) 또는 "bg"(배경). 없으면 빈 글자. */
        @JavascriptInterface
        public String userImage(String kind) {
            return imageDataUrl(kindOf(kind));
        }

        /** 휴대폰의 파일 고르기 창을 열어 이미지를 하나 고르게 합니다. */
        @JavascriptInterface
        public void pickImage(String kind) {
            final int request = KIND_BG.equals(kind) ? REQUEST_BG : REQUEST_IMAGE;
            whenUnlocked(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    try {
                        pickingUntil = System.currentTimeMillis() + 3 * 60 * 1000L;
                        startActivityForResult(intent, request);
                    } catch (RuntimeException e) {
                        pickingUntil = 0L;
                        sendError("이미지를 고르는 창을 열지 못했습니다");
                    }
                }
            });
        }

        @JavascriptInterface
        public void clearImage(String kind) {
            String k = kindOf(kind);
            removeImage(k);
            send("{\"type\":\"image\",\"kind\":\"" + k + "\"}");
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
                    + ",\"unlocked\":" + prefs.getBoolean(AutoLaunchService.KEY_UNLOCKED, true)
                    + ",\"overlay\":" + Settings.canDrawOverlays(MainActivity.this)
                    + ",\"move\":" + prefs.getBoolean(KEY_CLOSE_MOVE, true)
                    + ",\"sensor\":" + (hingeSensor != null) + "}";
        }

        /** 세워둔 휴대폰을 들거나 옮기면 스탠바이를 닫을지. */
        @JavascriptInterface
        public void setCloseOnMove(boolean enabled) {
            prefs.edit().putBoolean(KEY_CLOSE_MOVE, enabled).apply();
            closeOnMove = enabled;
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

        /** 잠금이 풀려 있을 때도 세워두면 켤지. */
        @JavascriptInterface
        public void setAutoUnlocked(boolean enabled) {
            prefs.edit().putBoolean(AutoLaunchService.KEY_UNLOCKED, enabled).apply();
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
