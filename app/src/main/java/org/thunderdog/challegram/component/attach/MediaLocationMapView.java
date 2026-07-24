/*
 * This file is a part of Telegram X
 * Copyright (c) 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 21/10/2016
 *
 * FOSS adaptation: Google Maps replaced with OSMDroid (org.osmdroid:osmdroid-android).
 */
package org.thunderdog.challegram.component.attach;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeId;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.ActivityPermissionResult;
import org.thunderdog.challegram.widget.CircleButton;
import org.thunderdog.challegram.widget.ShadowView;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.lambda.CancellableRunnable;

public class MediaLocationMapView extends FrameLayoutFix implements View.OnClickListener, ActivityPermissionResult {
  public interface Callback {
    void onLocationUpdate (Location location, boolean custom, boolean gpsLocated, boolean preventRequest, boolean isSmallZoom);
    void onForcedLocationReset ();
  }

  private static final int FLAG_PAUSED = 0x01;
  private static final int FLAG_DESTROYED = 0x02;
  private static final int FLAG_SCHEDULE_INIT = 0x04;
  private static final int FLAG_CREATED = 0x08;
  private static final int FLAG_RESUMED = 0x10;

  // Data
  private Callback callback;
  private int flags;

  private boolean userMovingLocation;
  private boolean didFirstMove;
  private boolean ignoreMyLocation;
  private Location lastMyLocation;
  private Location currentLocation;

  // Children

  private MapView mapView;
  private ImageView pinView;
  private ImageView pinXView;
  private CircleButton myLocationButton;

  private @Nullable MyLocationNewOverlay myLocationOverlay;

  public static int getMapHeight (boolean big) {
    int defaultSize = Screen.dp(150f);
    return big ? Math.max(Screen.smallestSide() - HeaderView.getSize(false) - Screen.dp(60f), defaultSize) : defaultSize;
  }

  public MediaLocationMapView (Context context) {
    super(context);
  }

  private MediaLocationPointView locationPointView;

  public void init (ViewController<?> themeProvider, MediaLocationPointView pointView, boolean big) {
    locationPointView = pointView;

    int mapHeight = getMapHeight(big);

    int mapPadding = 0; // Screen.dp(30f);
    FrameLayoutFix.LayoutParams params;
    params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, mapHeight + mapPadding * 2);
    params.topMargin = -mapPadding;
    mapView = new MapView(getContext()) {
      @Override
      public boolean onInterceptTouchEvent (MotionEvent ev) {
        switch (ev.getAction()) {
          case MotionEvent.ACTION_DOWN: {
            onMapTouchDown();
            break;
          }
          case MotionEvent.ACTION_MOVE: {
            onMapTouchMove();
            break;
          }
          case MotionEvent.ACTION_UP: {
            onMapTouchUp();
            break;
          }
        }
        return super.onInterceptTouchEvent(ev);
      }
    };
    mapView.setLayoutParams(params);
    mapView.setMultiTouchControls(true);
    mapView.setBuiltInZoomControls(false);
    mapView.setTileSource(TileSourceFactory.MAPNIK);
    addView(mapView);

    pinXView = new ImageView(getContext());
    pinXView.setScaleType(ImageView.ScaleType.CENTER);
    pinXView.setImageResource(R.drawable.baseline_close_18);
    pinXView.setColorFilter(Theme.getColor(ColorId.icon, ThemeId.BLUE));
    pinXView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    addView(pinXView);

    params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
    params.bottomMargin = Screen.dp(18f);
    pinView = new ImageView(getContext());
    pinView.setImageResource(R.drawable.ic_map_pin_44);
    pinView.setLayoutParams(params);
    pinView.setAlpha(0f);
    addView(pinView);
    updatePin();

    // My button

    int padding = Screen.dp(4f);
    params = FrameLayoutFix.newParams(Screen.dp(40f) + padding * 2, Screen.dp(40f) + padding * 2, Gravity.RIGHT | Gravity.BOTTOM);
    params.bottomMargin = Screen.dp(16f) - padding;
    params.rightMargin = Screen.dp(16f) - padding;


    myLocationButton = new CircleButton(getContext()) {
      @Override
      public boolean onTouchEvent (MotionEvent event) {
        return (event.getAction() != MotionEvent.ACTION_DOWN || (getAlpha() != 0f && !myLocationButtonAnimating)) && super.onTouchEvent(event);
      }
    };
    themeProvider.addThemeInvalidateListener(myLocationButton);
    myLocationButton.init(R.drawable.baseline_gps_fixed_24, 40f, 4f, ColorId.circleButtonOverlay, ColorId.circleButtonOverlayIcon);
    myLocationButton.setId(R.id.btn_gps);
    myLocationButton.setAlpha(0f);
    myLocationButton.setOnClickListener(this);
    myLocationButton.setLayoutParams(params);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      myLocationButton.setAlpha(1f);
    } else {
      checkLocationSettings(false, false);
    }
    addView(myLocationButton);

    // Shadow

    ShadowView shadowView = new ShadowView(getContext());
    shadowView.setSimpleTopShadow(true);
    params = FrameLayoutFix.newParams(shadowView.getLayoutParams());
    params.gravity = Gravity.BOTTOM;
    shadowView.setLayoutParams(params);
    themeProvider.addThemeInvalidateListener(shadowView);
    addView(shadowView);

    // Global

    setBackgroundColor(Theme.placeholderColor());
    themeProvider.addThemeBackgroundColorListener(this, ColorId.placeholder);
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, mapHeight, Gravity.TOP));

    // OSMDroid MapView is ready immediately (no Google Play Services readiness dance).
    // Defer initialization one tick so the host view is attached/ measured first.

    Background.instance().post(() -> UI.post(this::initMap));
  }

  @Override
  public void onClick (View v) {
    if (v.getId() == R.id.btn_gps) {
      checkLocationSettings(true, false);
    }
  }

  public void setCallback (Callback callback) {
    this.callback = callback;
  }

  // Set custom position

  private boolean userForcedLocation;

  public void setMarkerPosition (double lat, double lng) {
    Location location = new Location("network");
    location.setLatitude(lat);
    location.setLongitude(lng);

    userForcedLocation = true;

    setIgnoreMyLocation(true);
    positionMarker(location, defaultZoomLevel());
  }

  private void clearForcedLocation () {
    if (userForcedLocation) {
      userForcedLocation = false;
      if (callback != null) {
        callback.onForcedLocationReset();
      }
    }
  }

  // Animations

  private double touchLatitude, touchLongitude;

  private void onMapTouchDown () {
    getParent().getParent().requestDisallowInterceptTouchEvent(true);
    if (mapView != null) {
      IGeoPoint c = mapView.getMapCenter();
      touchLatitude = c.getLatitude();
      touchLongitude = c.getLongitude();
    }
  }

  private void onMapTouchMove () {
    if (!userMovingLocation) {
      if (mapView == null) {
        return;
      }
      IGeoPoint c = mapView.getMapCenter();
      if (c.getLatitude() == touchLatitude && c.getLongitude() == touchLongitude) {
        return;
      }
      setUserMovingLocation(true);
      setIgnoreMyLocation(true);
      currentLocation = new Location("network");
      currentLocation.setLatitude(c.getLatitude());
      currentLocation.setLongitude(c.getLongitude());
      setShowMyLocationButton(true);
      if (callback != null) {
        callback.onLocationUpdate(currentLocation, true, lastMyLocation != null, userMovingLocation || userForcedLocation, isSmallZoom(currentZoom()));
      }
    }
  }

  private void onMapTouchUp () {
    saveLastLocation();
    getParent().getParent().requestDisallowInterceptTouchEvent(false);
    if (userMovingLocation) {
      setUserMovingLocation(false);
    }
  }

  private float pinFactor;
  private boolean animatingPin;
  private ValueAnimator pinAnimator;

  private void animatePinFactor (float toFactor) {
    if (pinView == null) {
      this.pinFactor = toFactor;
      return;
    }
    if (animatingPin) {
      animatingPin = false;
      if (pinAnimator != null) {
        pinAnimator.cancel();
        pinAnimator = null;
      }
    }
    if (this.pinFactor == toFactor) {
      return;
    }

    animatingPin = true;

    final float fromFactor = this.pinFactor;
    final float factorDiff = toFactor - fromFactor;

    pinAnimator = AnimatorUtils.simpleValueAnimator();
    pinAnimator.setDuration(120l);
    pinAnimator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
    pinAnimator.addUpdateListener(animation -> setPinFactor(fromFactor + factorDiff * AnimatorUtils.getFraction(animation)));
    pinAnimator.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd (Animator animation) {
        animatingPin = false;
        MediaLocationMapView.this.pinAnimator = null;
      }
    });
    pinAnimator.start();
  }

  private void setPinFactor (float factor) {
    if (this.pinFactor != factor && animatingPin) {
      this.pinFactor = factor;
      updatePin();
    }
  }

  private void updatePin () {
    pinView.setTranslationY((float) -Screen.dp(10f) * pinFactor);
    pinXView.setAlpha(pinFactor);
  }

  // Callbacks

  public void onPauseMap () { // called when activity paused or bottom section changed
    if ((flags & FLAG_PAUSED) == 0) {
      flags |= FLAG_PAUSED;
      if ((flags & FLAG_CREATED) != 0) {
        pauseMap();
      }
    }
  }

  private void pauseMap () {
    try {
      mapView.onPause();
    } catch (Throwable ignored) { }
    flags &= ~FLAG_RESUMED;
  }

  private void resumeMap () {
    if ((flags & FLAG_RESUMED) == 0) {
      flags |= FLAG_RESUMED;
      try {
        mapView.onResume();
      } catch (Throwable ignored) { }
    }
  }

  public void onResumeMap () {
    if ((flags & FLAG_PAUSED) != 0) {
      flags &= ~FLAG_PAUSED;
      if ((flags & FLAG_SCHEDULE_INIT) != 0) {
        flags &= ~FLAG_SCHEDULE_INIT;
        initMap();
      } else if ((flags & FLAG_CREATED) != 0) {
        resumeMap();
      }
    }
  }

  public void onDestroyMap () {
    if ((flags & FLAG_DESTROYED) == 0) {
      flags |= FLAG_DESTROYED;
      try {
        if (myLocationOverlay != null) {
          myLocationOverlay.disableMyLocation();
        }
      } catch (Throwable ignored) { }
      try {
        mapView.onPause();
      } catch (Throwable ignored) { }
    }
  }

  // internal

  private void initMap () {
    if ((flags & FLAG_DESTROYED) != 0) {
      return;
    }
    if ((flags & FLAG_PAUSED) != 0) {
      flags |= FLAG_SCHEDULE_INIT;
      return;
    }
    flags |= FLAG_CREATED;
    try {
      setupMap();
    } catch (Throwable t) {
      Log.e("Failed to initialize OSMDroid map", t);
    }
  }

  private void setupMap () {
    pinView.setAlpha(1f);

    if (checkLocationPermission()) {
      enableMyLocation();
    }

    if (currentLocation == null) {
      Location currentLocation = MediaLocationFinder.instance().getLastKnownLocation();
      if (currentLocation != null) {
        positionMarker(currentLocation);
      } else {
        double latitude = 45.924197260584734;
        double longitude = 6.870443522930145;
        double zoomLevel = mapView.getMinZoomLevel();
        Settings.LastLocation location = Settings.instance().getViewedLocation();
        if (location != null) {
          latitude = location.latitude;
          longitude = location.longitude;
          zoomLevel = location.zoomOrAccuracy;
        }
        positionMarker(latitude, longitude, zoomLevel);
      }
    } else {
      positionMarker(currentLocation);
    }

    resumeMap();
  }

  // Callbacks

  private boolean checkLocationPermission () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return getContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    return true;
  }

  private void enableMyLocation () {
    if (mapView == null) {
      return;
    }
    try {
      if (myLocationOverlay == null) {
        myLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(getContext()), mapView) {
          @Override
          public void onLocationChanged (Location location, IMyLocationProvider source) {
            super.onLocationChanged(location, source);
            if (location != null) {
              MediaLocationMapView.this.onMyLocationChange(location);
            }
          }
        };
        mapView.getOverlays().add(myLocationOverlay);
        mapView.invalidate();
      }
      myLocationOverlay.enableMyLocation();
    } catch (Throwable ignored) { }
  }

  private void saveLastLocation () {
    if (mapView != null && currentLocation != null) {
      Settings.instance().setViewedLocation(currentLocation.getLatitude(), currentLocation.getLongitude(), currentZoom());
    }
  }

  private float currentZoom () {
    return mapView != null ? (float) mapView.getZoomLevelDouble() : 0f;
  }

  private double defaultZoomLevel () {
    if (mapView == null) {
      return -1;
    }
    return userForcedLocation ? mapView.getMaxZoomLevel() - 3 : mapView.getMaxZoomLevel() - 5;
  }

  private void positionMarker (Location location) {
    positionMarker(location, defaultZoomLevel());
  }

  private boolean isSmallZoom (double zoomLevel) {
    return mapView == null || zoomLevel < mapView.getMaxZoomLevel() - 10;
  }

  private void positionMarker (Location location, double zoomLevel) {
    positionMarkerInternal(location, zoomLevel);
    setShowMyLocationButton(userForcedLocation);
    if (callback != null) {
      callback.onLocationUpdate(location, userForcedLocation, lastMyLocation != null, userMovingLocation || userForcedLocation, false);
    }
  }

  // buttons

  private boolean myLocationButtonShowing;
  private boolean myLocationButtonAnimating;
  private ValueAnimator myLocationButtonAnimator;
  private float myLocationButtonFactor;

  private void setShowMyLocationButton (boolean show) {
    show = show || locationResolutionRequired;
    if (myLocationButtonShowing != show) {
      myLocationButtonShowing = show;
      animateMyLocationButtonFactor(show ? 1f : 0f);
    }
  }

  private void animateMyLocationButtonFactor (float toFactor) {
    if (myLocationButtonAnimating) {
      myLocationButtonAnimating = false;
      if (myLocationButtonAnimator != null) {
        myLocationButtonAnimator.cancel();
        myLocationButtonAnimator = null;
      }
    }
    if (this.myLocationButtonFactor == toFactor) {
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && !isAttachedToWindow()) {
      this.myLocationButtonFactor = toFactor;
      myLocationButton.setAlpha(toFactor);
      return;
    }
    myLocationButtonAnimating = true;

    final float fromFactor = this.myLocationButtonFactor;
    final float factorDiff = toFactor - fromFactor;

    myLocationButtonAnimator = AnimatorUtils.simpleValueAnimator();
    myLocationButtonAnimator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
    myLocationButtonAnimator.setDuration(150l);
    myLocationButtonAnimator.addUpdateListener(animation -> setMyLocationButtonFactor(fromFactor + factorDiff * AnimatorUtils.getFraction(animation)));
    myLocationButtonAnimator.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd (Animator animation) {
        myLocationButtonAnimating = false;
        myLocationButtonAnimator = null;
      }
    });
    myLocationButtonAnimator.start();
  }

  private void setMyLocationButtonFactor (float factor) {
    if (this.myLocationButtonFactor != factor && myLocationButtonAnimating) {
      this.myLocationButtonFactor = factor;
      myLocationButton.setAlpha(factor);
    }
  }

  private void focusOnMyLocation () {
    setIgnoreMyLocation(false, true);
  }

  private boolean locationResolutionRequired;

  public void onResolutionComplete (boolean isOk) {
    locationPointView.setShowProgress(isOk);
    if (isOk) {
      locationResolutionRequired = false;
      focusOnMyLocation();
    }
  }

  @Override
  public void onPermissionResult (int code, String[] permissions, int[] grantResults, int grantCount) {
    if (permissions.length == grantCount) {
      checkLocationSettings(true, false);
    } else if (!UI.getContext(UI.getContext()).permissions().shouldShowAccessLocationRationale()) {
      Intents.openPermissionSettings();
    }
  }

  public void onLocationPermissionOk () {
    checkLocationSettings(true, false);
  }

  public void checkLocationSettings (final boolean requestedByUser, final boolean disablePrompts) {
    if (UI.getContext(getContext()).checkLocationPermissions(false) != PackageManager.PERMISSION_GRANTED) {
      locationPointView.setShowProgress(false);
      if (requestedByUser && !disablePrompts) {
        ((BaseActivity) getContext()).requestLocationPermission(false, false, this);
      } else {
        setShowMyLocationButton(true);
      }
      return;
    }

    enableMyLocation();

    locationPointView.setShowProgress(false);
    if (requestedByUser) {
      focusOnMyLocation();
    }
  }

  // positioning

  private void positionMarker (double lat, double lng, double zoomLevel) {
    Location location = new Location("network");
    location.setLatitude(lat);
    location.setLongitude(lng);
    positionMarker(location, zoomLevel);
  }

  private void positionMarkerInternal (Location location, double zoomLevel) {
    if (location == null) {
      return;
    }

    currentLocation = location;

    if (userMovingLocation || mapView == null) {
      return;
    }

    IMapController controller = mapView.getController();
    GeoPoint geoPoint = new GeoPoint(location.getLatitude(), location.getLongitude());

    if (didFirstMove) {
      if (userForcedLocation || !ignoreMyLocation) {
        controller.animateTo(geoPoint, zoomLevel, null);
      } else {
        controller.animateTo(geoPoint);
      }
    } else {
      didFirstMove = true;
      controller.setCenter(geoPoint);
      controller.setZoom(zoomLevel);
    }
  }

  public Location getCurrentLocation () {
    return currentLocation;
  }

  private void setUserMovingLocation (boolean moving) {
    if (this.userMovingLocation != moving) {
      this.userMovingLocation = moving;
      animatePinFactor(moving ? 1f : 0f);
      if (!userMovingLocation) {
        postLocationUpdateDelayed();
        saveLastLocation();
      } else {
        clearForcedLocation();
        cancelLocationUpdate();
      }
    }
  }

  private CancellableRunnable updateTask;

  private void postLocationUpdateDelayed () {
    cancelLocationUpdate();
    updateTask = new CancellableRunnable() {
      @Override
      public void act () {
        if (ignoreMyLocation) {
          setShowMyLocationButton(true);
          if (callback != null) {
            callback.onLocationUpdate(currentLocation, true, lastMyLocation != null, userMovingLocation || userForcedLocation, mapView == null || isSmallZoom(currentZoom()));
          }
        }
      }
    };
    postDelayed(updateTask, 400l);
  }

  private void cancelLocationUpdate () {
    if (updateTask != null) {
      updateTask.cancel();
      updateTask = null;
    }
  }

  private void setIgnoreMyLocation (boolean ignoreMyLocation) {
    setIgnoreMyLocation(ignoreMyLocation, false);
  }

  private void setIgnoreMyLocation (boolean ignoreMyLocation, boolean force) {
    if (this.ignoreMyLocation != ignoreMyLocation || force) {
      this.ignoreMyLocation = ignoreMyLocation;
      if (!ignoreMyLocation && lastMyLocation != null) {
        cancelLocationUpdate();
        clearForcedLocation();
        positionMarker(lastMyLocation);
        saveLastLocation();
      }
    }
  }

  private void onMyLocationChange (Location location) {
    lastMyLocation = location;
    if (location != null) {
      Settings.instance().saveLastKnownLocation(location.getLatitude(), location.getLongitude(), location.getAccuracy());
    }
    if (!ignoreMyLocation) {
      setShowMyLocationButton(false);
      positionMarker(location);
      saveLastLocation();
    }
  }
}
