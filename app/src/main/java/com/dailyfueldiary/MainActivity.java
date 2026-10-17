package com.dailyfueldiary;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.widget.Toast;
import java.util.Calendar;

public class MainActivity extends Activity {
    private WebView web;
    private static final int PICK_EXPORT=100, PICK_IMPORT=101;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("dailyfuel","Daily food reminder",NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Reminder to log your meals");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient());
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }
    public class Bridge {
        @JavascriptInterface public void setReminder(int hour,int minute,boolean enabled) {
            getSharedPreferences("dailyfuel", MODE_PRIVATE).edit()
                .putBoolean("reminder_enabled", enabled)
                .putInt("reminder_hour", hour)
                .putInt("reminder_minute", minute)
                .apply();
            if (!enabled) {
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this,42,new Intent(MainActivity.this,ReminderReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                ((AlarmManager)getSystemService(ALARM_SERVICE)).cancel(pi); return;
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8));
            }
            AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:"+getPackageName())));
            }
            Calendar c=Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY,Math.max(0,Math.min(23,hour))); c.set(Calendar.MINUTE,Math.max(0,Math.min(59,minute))); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0);
            if(c.getTimeInMillis()<=System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR,1);
            Intent i=new Intent(MainActivity.this,ReminderReceiver.class);
            PendingIntent pi=PendingIntent.getBroadcast(MainActivity.this,42,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,c.getTimeInMillis(),pi); else am.setExact(AlarmManager.RTC_WAKEUP,c.getTimeInMillis(),pi);
        }
        @JavascriptInterface public void exportBackup(String json) {
            getPreferences(MODE_PRIVATE).edit().putString("pending_export",json).apply();
            runOnUiThread(() -> {
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json"); i.putExtra(Intent.EXTRA_TITLE,"daily-fuel-backup.json"); startActivityForResult(i,PICK_EXPORT);
            });
        }
        @JavascriptInterface public void importBackup() {
            runOnUiThread(() -> {
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i,PICK_IMPORT);
            });
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        try {
            Uri uri=data.getData();
            if(requestCode==PICK_EXPORT) {
                String json=getPreferences(MODE_PRIVATE).getString("pending_export","{}");
                try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                Toast.makeText(this,"Backup exported",Toast.LENGTH_SHORT).show();
            } else if(requestCode==PICK_IMPORT) {
                StringBuilder sb=new StringBuilder();
                try(java.io.InputStream in=getContentResolver().openInputStream(uri); java.io.BufferedReader r=new java.io.BufferedReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)sb.append(line);}
                web.evaluateJavascript("window.importBackupFromNative("+org.json.JSONObject.quote(sb.toString())+")",null);
                Toast.makeText(this,"Backup imported",Toast.LENGTH_SHORT).show();
            }
        } catch(Exception e){Toast.makeText(this,"Could not process backup: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    @Override public void onBackPressed(){ if(web!=null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
