package com.mapbox.android.telemetry;

import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.PersistableBundle;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class GeofenceJobService extends JobService implements Callback {
  private static final String LOG_TAG = "GeofenceJob";
  private static final int JOB_ID = 2;
  private JobParameters currentParams;
  private ArrayList<Location> locations;
  private boolean locationOn;
  private GeofenceManager geofenceManager;
  private String accessToken;
  private String userAgent;
  private FusedLocationProviderClient fusedLocationClient;

  @RequiresApi(api = Build.VERSION_CODES.N)
  public static void schedule(Context context, String userAgent, String accessToken) {
    Log.e(LOG_TAG, "userAgent5: " + userAgent);
    PersistableBundle bundle = new PersistableBundle();
    bundle.putString("userAgent", userAgent);
    bundle.putString("accessToken", accessToken);

    ComponentName component = new ComponentName(context, GeofenceJobService.class);
    JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, component)
      .setMinimumLatency(1)
      .setOverrideDeadline(1)
      .setExtras(bundle);

    JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (jobScheduler != null) {
      jobScheduler.schedule(builder.build());
    }
  }

  @SuppressLint("MissingPermission")
  @Override
  public boolean onStartJob(JobParameters params) {
    currentParams = params;
    locations = new ArrayList<Location>();
    userAgent = params.getExtras().getString("userAgent");
    accessToken = params.getExtras().getString("accessToken");

    Log.e(LOG_TAG, "userAgent6: " + userAgent);

    locationOn = true;
    geofenceManager = new GeofenceManager(getApplicationContext());

    final LocationCallback locationCallback = new LocationCallback() {
      @Override
      public void onLocationResult(LocationResult locationResult) {
        Log.e(LOG_TAG, "onLocationResult");
        if (locationResult == null) {
          return;
        }
        for (Location location : locationResult.getLocations()) {
          Log.e(LOG_TAG, "Location received: " + location);
          locations.add(location);

          if (!locationOn) {
            fusedLocationClient.removeLocationUpdates(this);

            //generate new geofence
            Log.e(LOG_TAG, "add geofence");
            geofenceManager.addGeofence(location);

            sendLocation(locations);
          }
        }
      }
    };

    Log.e(LOG_TAG, "fusedLocationClient");
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(getApplicationContext());
    LocationRequest locationRequest = LocationRequest.create();
    locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    locationRequest.setInterval(1000);
    locationRequest.setFastestInterval(1000);

    fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);

    startTimer();

    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return false;
  }

  private void startTimer() {
    new CountDownTimer(20000, 1000) {

      @Override
      public void onTick(long millisUntilFinished) {

      }

      @Override
      public void onFinish() {
        Log.e(LOG_TAG, "timer finished");
        locationOn = false;
      }
    }.start();
  }

  private void sendLocation(List<Location> locations) {
    Log.e(LOG_TAG, "send location");
    final List<Event> events = new ArrayList<>(locations.size());

    for (Location location : locations) {
      LocationEvent locationEvent = createLocationEvent(location);
      events.add(locationEvent);
    }

    final Callback callback = this;

    new Thread(new Runnable() {
      public void run() {
        TelemetryClient telemetryClient = createTelemetryClient();
        telemetryClient.sendEvents(events, callback);
      }
    }).start();
  }

  private LocationEvent createLocationEvent(Location location) {
    MapboxTelemetry.applicationContext = getApplicationContext();

    double latitudeScaled = round(location.getLatitude());
    double longitudeScaled = round(location.getLongitude());
    double longitudeWrapped = wrapLongitude(longitudeScaled);

    LocationEvent locationEvent = new LocationEvent("test-1-Geofence", latitudeScaled, longitudeWrapped);
    locationEvent.setAccuracy((float) Math.round(location.getAccuracy()));
    locationEvent.setAltitude((double) Math.round(location.getAltitude()));

    return locationEvent;
  }

  private double round(double value) {
    return new BigDecimal(value).setScale(7, BigDecimal.ROUND_DOWN).doubleValue();
  }

  private double wrapLongitude(double longitude) {
    double wrapped = longitude;
    if ((longitude < -180) || (longitude > 180)) {
      wrapped = wrap(longitude, -180, 180);
    }
    return wrapped;
  }

  private double wrap(double value, double min, double max) {
    double delta = max - min;

    double firstMod = (value - min) % delta;
    double secondMod = (firstMod + delta) % delta;

    return secondMod + min;
  }

  private TelemetryClient createTelemetryClient() {
    Log.e(LOG_TAG, "userAgent7: " + userAgent);
    String userAgentTelemetry = "MapboxEventsAndroid/3.1.0/geofence";
    TelemetryClientFactory telemetryClientFactory = new TelemetryClientFactory(accessToken, userAgentTelemetry,
      new Logger());
    TelemetryClient telemetryClient = telemetryClientFactory.obtainTelemetryClient(getApplicationContext());
    return telemetryClient;
  }

  @Override
  public void onFailure(Call call, IOException e) {
    Log.e(LOG_TAG,"call failed: " + e);
    jobFinished(currentParams, false);
  }

  @Override
  public void onResponse(Call call, Response response) throws IOException {
    Log.e(LOG_TAG,"job finished: " + response);
    jobFinished(currentParams, false);
  }
}