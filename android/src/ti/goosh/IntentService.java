package ti.goosh;

import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Type;
import java.lang.Math;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import org.appcelerator.titanium.TiApplication;
import org.appcelerator.titanium.util.TiRHelper;

import com.google.android.gms.gcm.GcmListenerService;

public class IntentService extends GcmListenerService {

	private static final String LCAT = "ti.goosh.IntentService";
	private static final AtomicInteger atomic = new AtomicInteger(0);

	@Override
	public void onMessageReceived(String from, Bundle bundle) {
		Log.d(LCAT, "Push notification received from: " + from);
		for (String key : bundle.keySet()) {
			Object value = bundle.get(key);
			Log.d(LCAT, String.format("Notification key : %s => %s (%s)", key, value.toString(), value.getClass().getName()));
		}

		parseNotification(bundle);
	}

	private int getResource(String type, String name) {
		int icon = 0;
		if (name != null) {
			int index = name.lastIndexOf(".");
			if (index > 0) name = name.substring(0, index);
			try {
				icon = TiRHelper.getApplicationResource(type + "." + name);
			} catch (TiRHelper.ResourceNotFoundException ex) {
				Log.e(LCAT, type + "." + name + " not found; make sure it's in platform/android/res/" + type);
			}
		}

		return icon;
	}

	private Bitmap getBitmapFromURL(String src) throws Exception {
		HttpURLConnection connection = (HttpURLConnection)(new URL(src)).openConnection();
		connection.setDoInput(true);
		connection.setUseCaches(false); // Android BUG
		connection.connect();
		return BitmapFactory.decodeStream( new BufferedInputStream( connection.getInputStream() ) );
	}

	private void parseNotification(Bundle bundle) {
		Context context = TiApplication.getInstance().getApplicationContext();
		Boolean appInBackground = !TiApplication.isCurrentActivityInForeground();

		Boolean showNotification = true;

		String jsonData = bundle.getString("data");
		JsonObject data = null;

		try {
			data = (JsonObject) new Gson().fromJson(jsonData, JsonObject.class);
		} catch (Exception ex) {
			Log.e(LCAT, "Error parsing data JSON: " + ex.getMessage());
			return;
		}

		if (data != null && data.has("alert") == true) {
			if (appInBackground) {
				showNotification = true;
			} else {
				if (data.has("force_show_in_foreground")) {
					JsonPrimitive showInFore = data.getAsJsonPrimitive("force_show_in_foreground");
					showNotification = ((showInFore.isBoolean() && showInFore.getAsBoolean() == true));
					appInBackground = showNotification;
				} else {
					showNotification = false;
				}
			}
		} else {
			Log.i(LCAT, "Not showing notification cause missing data.alert");
			showNotification = false;
		}

		if (showNotification) {

			// LaunchIntent
			Intent launcherIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
			launcherIntent.setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_SINGLE_TOP);
			launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
			launcherIntent.setAction(Long.toString(System.currentTimeMillis()));
			launcherIntent.putExtra("tigoosh.notification", jsonData);

			PendingIntent contentIntent = PendingIntent.getActivity(this, (int) bundle.getLong("google.sent_time"), launcherIntent, PendingIntent.FLAG_ONE_SHOT);

			// Start building notification

			NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
			int builder_defaults = 0;
			builder.setContentIntent(contentIntent);
			builder.setAutoCancel(true);
			builder.setPriority(2);

			// Body 

			String alert = null;
			if (data.has("alert")) {
				alert = data.getAsJsonPrimitive("alert").getAsString();
				builder.setContentText(alert);
				builder.setTicker(alert);
			}

			// BigText

			String big_text = null;
			if (data.has("big_text")) {
				NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
				bigTextStyle.bigText( data.getAsJsonPrimitive("big_text").getAsString() );

				if (data.has("big_text_summary")) {
					bigTextStyle.setSummaryText( data.getAsJsonPrimitive("big_text_summary").getAsString() );
				}

				builder.setStyle(bigTextStyle);
			}

			// Icons

			try {
				int smallIcon = this.getResource("drawable", "notificationicon");
				if (smallIcon > 0) {
					builder.setSmallIcon(smallIcon);
				}
			} catch (Exception ex) {
				Log.e(LCAT, "Smallicon exception: " + ex.getMessage());
			}

			// Large icon
			if (data.has("icon")) {
				try {
					Bitmap icon = this.getBitmapFromURL( data.getAsJsonPrimitive("icon").getAsString() );
					builder.setLargeIcon(icon);
				} catch (Exception ex) {
					Log.e(LCAT, "Icon exception: " + ex.getMessage());
				}
			}

			// Color

			if (data.has("color")) {
				try {
					int color = Color.parseColor( data.getAsJsonPrimitive("color").getAsString() );
					builder.setColor( color );
				} catch (Exception ex) {
					Log.e(LCAT, "Color exception: " + ex.getMessage());
				}
			}			

			// Title

			if (data.has("title")) {
				builder.setContentTitle( data.getAsJsonPrimitive("title").getAsString() );
			} else {
				builder.setContentTitle( TiApplication.getInstance().getAppInfo().getName() );
			}

			// Badge

			if (data.has("badge")) {
				int badge = data.getAsJsonPrimitive("badge").getAsInt();
				BadgeUtils.setBadge(context, badge);
				builder.setNumber(badge);
			}

			// Sound 

			if (data.has("sound")) {
				JsonPrimitive sound = data.getAsJsonPrimitive("sound");
				if ( ("default".equals(sound.getAsString())) || (sound.isBoolean() && sound.getAsBoolean() == true) ) {
					builder_defaults |= Notification.DEFAULT_SOUND;
				} else {
					int resource = getResource("raw", data.getAsJsonPrimitive("sound").getAsString());
					builder.setSound( Uri.parse("android.resource://" + context.getPackageName() + "/" + resource) );
				}
			}

			// Vibration

			try {
				if (data.has("vibrate")) {
					//JsonPrimitive vibrate = data.getAsJsonPrimitive("vibrate");
					JsonElement vibrateJson = data.get("vibrate");

					if(vibrateJson.isJsonPrimitive()) {
						JsonPrimitive vibrate = vibrateJson.getAsJsonPrimitive();

						if (vibrate.isBoolean() && vibrate.getAsBoolean() == true) {
							builder_defaults |= Notification.DEFAULT_VIBRATE;
						}
					}
					else if(vibrateJson.isJsonArray()) {
						JsonArray vibrate = vibrateJson.getAsJsonArray();
						
						if(vibrate.size() > 0) {
							long[] pattern = new long[vibrate.size()];
							int i = 0;
							
							for(i = 0; i < vibrate.size(); i++) {
								pattern[i] = vibrate.get(i).getAsLong();
							}

							builder.setVibrate(pattern);
						}
					}
				}
			} catch(Exception ex) {
				Log.e(LCAT, "Vibrate exception: " + ex.getMessage());
			}
			
			// Lights

			try {
				if(data.has("lights"))
				{
					JsonElement lightsJson = data.get("lights");

					if(lightsJson.isJsonObject())
					{
						JsonObject lights = lightsJson.getAsJsonObject();
						int argb = Color.parseColor(lights.get("argb").getAsString());
						int onMs = lights.get("onMs").getAsInt();
						int offMs = lights.get("offMs").getAsInt();

						if(-1 != argb && -1 != onMs && -1 != offMs)
						{
							builder.setLights(argb, onMs, offMs);
						}
					}
				}
				else
				{
					builder_defaults |= Notification.DEFAULT_LIGHTS;
				}
			} catch(Exception ex) {
				Log.e(LCAT, "Lights exception: " + ex.getMessage());
			}

			// Ongoing

			try {
				if(data.has("ongoing"))
				{
					JsonElement ongoingJson = data.get("ongoing");

					if(ongoingJson.isJsonPrimitive())
					{
						Boolean ongoing = ongoingJson.getAsBoolean();
						
						builder.setOngoing(ongoing);
					}
				}
				else
				{
					builder_defaults |= Notification.DEFAULT_LIGHTS;
				}
			} catch(Exception ex) {
				Log.e(LCAT, "Ongoing exception: " + ex.getMessage());
			}

			// Group

			try {
				if(data.has("group"))
				{
					JsonElement groupJson = data.get("group");

					if(groupJson.isJsonPrimitive())
					{
						String group = groupJson.getAsString();
						
						builder.setGroup(group);
					}
				}
				else
				{
					builder_defaults |= Notification.DEFAULT_LIGHTS;
				}
			} catch(Exception ex) {
				Log.e(LCAT, "Group exception: " + ex.getMessage());
			}

			// GroupSummary

			try {
				if(data.has("group_summary"))
				{
					JsonElement groupsumJson = data.get("group_summary");

					if(groupsumJson.isJsonPrimitive())
					{
						Boolean groupsum = groupsumJson.getAsBoolean();
						
						builder.setGroupSummary(groupsum);
					}
				}
				else
				{
					builder_defaults |= Notification.DEFAULT_LIGHTS;
				}
			} catch(Exception ex) {
				Log.e(LCAT, "Group summary exception: " + ex.getMessage());
			}

			// When

			try {
				if(data.has("when"))
				{
					JsonElement whenJson = data.get("when");

					if(whenJson.isJsonPrimitive())
					{
						int when = whenJson.getAsInt();
						
						builder.setWhen(when);
					}
				}
				else
				{
					builder_defaults |= Notification.DEFAULT_LIGHTS;
				}
			} catch(Exception ex) {
				Log.e(LCAT, "When exception: " + ex.getMessage());
			}

			// Only alert once

			try {
				if(data.has("only_alert_once"))
				{
					JsonElement oaoJson = data.get("only_alert_once");

					if(oaoJson.isJsonPrimitive())
					{
						Boolean oao = oaoJson.getAsBoolean();
						
						builder.setOnlyAlertOnce(oao);
					}
				}
				else
				{
					builder_defaults |= Notification.DEFAULT_LIGHTS;
				}
			} catch(Exception ex) {
				Log.e(LCAT, "Only alert once exception: " + ex.getMessage());
			}

			builder.setDefaults(builder_defaults);

			// Build

			// Tag

			String tag = null;
			if (data.has("tag")) {
				tag = data.getAsJsonPrimitive("tag").getAsString();
			}
		
			// Nid
			
			int id = 0;
			if (data.has("id")) {
				// ensure that the id sent from the server is negative to prevent
				// collision with the atomic integer
				id = -1 * Math.abs(data.getAsJsonPrimitive("id").getAsInt());
			} else {
				id = atomic.getAndIncrement();
			}


			NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
			notificationManager.notify(tag, id, builder.build());
		}

		if (!appInBackground && TiGooshModule.getInstance() != null) {
			TiGooshModule.getInstance().sendMessage(jsonData, appInBackground);
		}
	}

}
