package app.foldstandby;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 휴대폰을 다시 켜거나 앱을 업데이트한 뒤, 자동 실행을 켜 둔 상태라면 감시 서비스를 다시 시작합니다. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(AutoLaunchService.PREFS, Context.MODE_PRIVATE);
        boolean wanted = prefs.getBoolean(AutoLaunchService.KEY_HINGE, false)
                || prefs.getBoolean(AutoLaunchService.KEY_CHARGE, false);
        if (!wanted) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, AutoLaunchService.class));
        } catch (RuntimeException ignored) {
            // 시작이 막히면 다음에 앱을 열 때 다시 시작됩니다.
        }
    }
}
