package au.buzz.ryzebridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/** Invisible activity: forwards its extras to BleService and finishes. Started from adb, which exempts the
 *  foreground-service start from background restrictions. */
public class CmdActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent svc = new Intent(this, BleService.class);
        Intent in = getIntent();
        if (in != null && in.getExtras() != null) svc.putExtras(in.getExtras());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        finish();
    }
}
