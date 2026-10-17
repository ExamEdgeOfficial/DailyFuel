package com.dailyfueldiary;
import android.content.*;
import android.app.*;
import android.os.Build;
public class BootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent) {
   if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
   android.content.SharedPreferences p=context.getSharedPreferences("dailyfuel",Context.MODE_PRIVATE);
   if(!p.getBoolean("reminder_enabled",false)) return;
   java.util.Calendar c=java.util.Calendar.getInstance(); c.set(java.util.Calendar.HOUR_OF_DAY,p.getInt("reminder_hour",20)); c.set(java.util.Calendar.MINUTE,p.getInt("reminder_minute",0)); c.set(java.util.Calendar.SECOND,0);
   if(c.getTimeInMillis()<=System.currentTimeMillis())c.add(java.util.Calendar.DAY_OF_YEAR,1);
   PendingIntent pi=PendingIntent.getBroadcast(context,42,new Intent(context,ReminderReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
   AlarmManager am=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
   if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,c.getTimeInMillis(),pi);else am.setExact(AlarmManager.RTC_WAKEUP,c.getTimeInMillis(),pi);
 }
}
