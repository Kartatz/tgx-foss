/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 01/12/2016
 */
package org.thunderdog.challegram.helper;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.navigation.ActivityResultHandler;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.ActivityPermissionResult;

import me.vkryl.core.lambda.CancellableRunnable;

public class LocationHelper implements ActivityResultHandler {
  // static API

  public interface LocationCallback {
    void onLocationResult (int errorCode, @Nullable Location location);
  }

  public interface LocationPermissionRequester {
    void requestPermissions (boolean skipAlert, Runnable onCancel, ActivityPermissionResult handler);
  }

  public static LocationHelper requestLocation (BaseActivity context, long timeout, boolean allowResolution, boolean needBackground, final @NonNull LocationCallback callback) {
    final LocationHelper helper = new LocationHelper(context, new LocationHelper.LocationChangeListener() {
      @Override
      public void onLocationResult (LocationHelper context, @NonNull String arg, @Nullable Location location) {
        context.destroy();
        callback.onLocationResult(ERROR_CODE_NONE, location);
      }

      @Override
      public void onLocationRequestFailed (LocationHelper context, int errorCode, @NonNull String arg, Location savedLocation) {
        context.destroy();
        callback.onLocationResult(errorCode, savedLocation);
      }
    }, true, needBackground);
    helper.receiveLocation("", null, timeout, allowResolution);
    return helper;
  }

  // dynamic API

  public interface LocationChangeListener {
    void onLocationResult (LocationHelper context, @NonNull String arg, @Nullable Location location);
    void onLocationRequestFailed (LocationHelper context, int errorCode, @NonNull String arg, @Nullable Location savedLocation);
  }

  private final Context context;
  private final LocationChangeListener listener;
  private final boolean allowCached, needBackground;
  private LocationPermissionRequester permissionRequester;

  public LocationHelper (Context context, LocationChangeListener listener, boolean allowCached, boolean needBackground) {
    this.context = context;
    this.listener = listener;
    this.allowCached = allowCached;
    this.needBackground = needBackground;
  }

  private String arg;
  private long timeout;
  private boolean[] lastSignal;

  public void setPermissionRequester(LocationPermissionRequester permissionRequester) {
    this.permissionRequester = permissionRequester;
  }

  public void checkLocationPermission (@NonNull String arg, @Nullable BaseActivity activity) {
    this.arg = arg;
    this.timeout = -1;
    if (this.lastSignal != null) {
      this.lastSignal[0] = true;
    }
    receiveLocationInternal(activity != null ? activity : UI.getContext(context), true, true, false);
  }

  public void receiveLocation (@NonNull String arg, @Nullable BaseActivity activity, long timeout, boolean allowResolution) {
    receiveLocation(arg, activity, timeout, allowResolution, false);
  }

  public void receiveLocation (@NonNull String arg, @Nullable BaseActivity activity, long timeout, boolean allowResolution, boolean skipPermissionAlert) {
    this.arg = arg;
    this.timeout = timeout;
    if (this.lastSignal != null) {
      this.lastSignal[0] = true;
    }
    receiveLocationInternal(activity != null ? activity : UI.getContext(context), allowResolution, false, skipPermissionAlert);
  }

  public void cancel () {
    this.arg = null;
    this.timeout = 0;
    if (this.lastSignal != null) {
      this.lastSignal[0] = true;
    }
  }

  public static final int ERROR_CODE_NONE = 0;
  public static final int ERROR_CODE_PERMISSION = -1;
  public static final int ERROR_CODE_RESOLUTION = -2;
  public static final int ERROR_CODE_TIMEOUT = -3;
  public static final int ERROR_CODE_UNKNOWN = -4;
  public static final int ERROR_CODE_PERMISSION_CANCEL = -5;

  private static int checkLocationPermissions (Context context, boolean needBackground) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      String[] permissions;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Config.REQUEST_BACKGROUND_LOCATION && needBackground) {
        permissions = new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
      } else {
        permissions = new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
      }
      for (String permission : permissions) {
        int status = context.checkSelfPermission(permission);
        if (status != PackageManager.PERMISSION_GRANTED)
          return status;
      }
    }
    return PackageManager.PERMISSION_GRANTED;
  }

  private void receiveLocationInternal (final BaseActivity activity, final boolean allowResolution, final boolean onlyCheck, final boolean skipAlert) {
    final boolean[] sendStatus = new boolean[1];
    lastSignal = sendStatus;
    final Context context = activity != null ? activity : this.context;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (checkLocationPermissions(context, needBackground) != PackageManager.PERMISSION_GRANTED) {
        if (allowResolution) {
          if (activity != null) {
            Runnable onCancel = () -> onReceiveLocationFailure(ERROR_CODE_PERMISSION_CANCEL);

            ActivityPermissionResult callback = (code, permissions, grantResults, grantCount) -> {
              if (sendStatus[0]) {
                return;
              }
              if (grantCount == permissions.length) {
                receiveLocationInternal(activity, true, onlyCheck, skipAlert);
              } else {
                onReceiveLocationFailure(ERROR_CODE_PERMISSION);
              }
            };

            if (permissionRequester != null) {
              permissionRequester.requestPermissions(skipAlert, onCancel, callback);
            } else {
              activity.requestLocationPermission(needBackground, skipAlert, onCancel, callback);
            }
          }
        } else {
          onReceiveLocationFailure(ERROR_CODE_PERMISSION);
        }
        return;
      }
    }

    if (onlyCheck) {
      onReceiveLocation(null);
      return;
    }

    receiveLocationViaManager(sendStatus);
  }

  @Override
  public void onActivityResult (int requestCode, int resultCode, Intent data) {
    // No-op: Google Play Services location-settings resolution is unavailable in the FOSS build.
  }

  private void receiveLocationViaManager (final boolean[] signal) {
    if (signal[0]) {
      return;
    }
    if (checkLocationPermissions(context, needBackground) != PackageManager.PERMISSION_GRANTED) {
      onReceiveLocationFailure(ERROR_CODE_PERMISSION);
      return;
    }

    try {
      final LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
      if (manager == null) {
        onReceiveLocationFailure(ERROR_CODE_UNKNOWN);
        return;
      }
      final CancellableRunnable[] timeout = new CancellableRunnable[1];
      final android.location.LocationListener listener = new android.location.LocationListener() {
        @Override
        public void onLocationChanged (@NonNull Location location) {
          timeout[0].cancel();
          try {
            manager.removeUpdates(this);
          } catch (SecurityException ignored) { }
          catch (Throwable t) {
            Log.e("LocationManager.removeUpdates filed. Probable resource leak", t);
          }
          if (!signal[0]) {
            signal[0] = true;
            onReceiveLocation(location);
          }
        }

        @Override
        public void onStatusChanged (String provider, int status, Bundle extras) { }

        @Override
        public void onProviderEnabled (String provider) { }

        @Override
        public void onProviderDisabled (String provider) { }
      };
      timeout[0] = new CancellableRunnable() {
        @Override
        public void act () {
          if (!signal[0]) {
            signal[0] = true;
            Location location = allowCached ? U.getLastKnownLocation(context, true) : null;
            if (location != null) {
              onReceiveLocation(location);
            } else {
              onReceiveLocationFailure(ERROR_CODE_TIMEOUT);
            }
          }
        }
      };
      UI.post(timeout[0], this.timeout);
      manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1, 0, listener);
      manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1, 0, listener);
    } catch (SecurityException ignored) {
      signal[0] = true;
      onReceiveLocationFailure(ERROR_CODE_PERMISSION);
    } catch (Throwable t) {
      signal[0] = true;
      Log.w("Error occurred", t);
      onReceiveLocationFailure(ERROR_CODE_UNKNOWN);
    }
  }

  private void onReceiveLocation (@Nullable Location location) {
    Log.v("Location successfully received");
    if (arg != null) {
      if (location != null) {
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        float accuracy = location.getAccuracy();
        Settings.instance().saveLastKnownLocation(latitude, longitude, accuracy);
        listener.onLocationResult(this, arg, location);
      } else {
        listener.onLocationResult(this, arg, null);
      }
    }
  }

  private void onReceiveLocationFailure (int code) {
    Log.v("Location receive failure, code: %d", code);
    if (arg != null) {
      Location savedLocation = allowCached ? getLastKnownLocation(context, false) : null;
      listener.onLocationRequestFailed(this, code, arg, savedLocation);
    }
  }

  public static Location getLastKnownLocation (Context context, boolean allowGoogleClient) {
    Settings.LastLocation lastLocation = Settings.instance().getLastKnownLocation();
    if (lastLocation != null) {
      Location location = new Location("network");
      location.setLatitude(lastLocation.latitude);
      location.setLongitude(lastLocation.longitude);
      location.setAccuracy(lastLocation.zoomOrAccuracy);
      return location;
    }
    return null;
  }

  public void destroy () {
  }
}
