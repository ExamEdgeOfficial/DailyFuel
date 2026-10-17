package com.dailyfueldiary;
import android.app.*;
import android.content.*;
import android.os.Build;
public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context,"dailyfuel") : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_menu_today).setContentTitle("Daily Fuel Diary").setContentText("Take a moment to log today's meals.").setAutoCancel(true);
        ((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(42,b.build());
        java.util.Calendar c=java.util.Calendar.getInstance(); c.add(java.util.Calendar.DAY_OF_YEAR,1);
        Intent i=new Intent(context,ReminderReceiver.class);
        PendingIntent pi=PendingIntent.getBroadcast(context,42,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        if(Build.VERSION.SDK_INT>=23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,c.getTimeInMillis(),pi); else am.setExact(AlarmManager.RTC_WAKEUP,c.getTimeInMillis(),pi);
    }
}
