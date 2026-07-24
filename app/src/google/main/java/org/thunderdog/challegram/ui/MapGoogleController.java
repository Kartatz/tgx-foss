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
 * File created on 08/03/2018
 *
 * FOSS adaptation: Google Maps replaced with OSMDroid (org.osmdroid:osmdroid-android).
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.dialogs.ChatView;
import org.thunderdog.challegram.loader.ImageCache;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageLoader;
import org.thunderdog.challegram.loader.Watcher;
import org.thunderdog.challegram.loader.WatcherReference;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccentColor;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Letters;

import java.util.List;

import me.vkryl.core.lambda.Destroyable;
import tgx.td.MessageId;

final class MapGoogleController extends MapController<MapView, MapGoogleController.MarkerData> {
  private static final double DEFAULT_ZOOM_LEVEL = 16.0;
  private static final double CLICK_ZOOM_LEVEL = 17.0;

  public MapGoogleController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  protected MapView createMapView (Context context, int marginBottom) {
    MapView mapView = new MapView(context) {
      private double downLat, downLng;
      @Override
      public boolean onTouchEvent (MotionEvent ev) {
        switch (ev.getAction()) {
          case MotionEvent.ACTION_DOWN: {
            downLat = getMapCenter().getLatitude();
            downLng = getMapCenter().getLongitude();
            break;
          }
          case MotionEvent.ACTION_UP: {
            if (getMapCenter().getLatitude() != downLat || getMapCenter().getLongitude() != downLng) {
              onUserMovedCamera();
            }
            break;
          }
        }
        return super.onTouchEvent(ev);
      }
    };
    mapView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    mapView.setPadding(0, 0, 0, marginBottom);
    mapView.setMultiTouchControls(true);
    mapView.setBuiltInZoomControls(false);
    return mapView;
  }

  private static final float FINISHED_BROADCAST_ALPHA = .6f;

  public class MarkerData implements Watcher, Destroyable {
    public final Tdlib tdlib;
    private final MapView mapView;
    public final Marker marker;

    private final WatcherReference reference = new WatcherReference(this);

    public Canvas canvas;
    public Bitmap bitmap;

    public MarkerData (Tdlib tdlib, MapView mapView, LocationPoint<MarkerData> point) {
      this.tdlib = tdlib;
      this.mapView = mapView;
      marker = new Marker(mapView);
      marker.setRelatedObject(point);
      marker.setAnchor(0.5f, 0.907f);
      marker.setOnMarkerClickListener((m, mv) -> {
        Object tag = m.getRelatedObject();
        if (tag instanceof LocationPoint) {
          onMarkerClick((LocationPoint<MarkerData>) tag);
        }
        return true;
      });
      applyPoint(point);
      mapView.getOverlays().add(marker);
    }

    private void applyPoint (LocationPoint<MarkerData> point) {
      marker.setPosition(new GeoPoint(point.latitude, point.longitude));
      Bitmap bitmap = null;
      if (point.isSelfLocation) {
        TdApi.User user = tdlib.myUser();
        TdlibAccentColor accentColor = tdlib.cache().userAccentColor(user);
        Letters letters = tdlib.cache().userLetters(user);
        TdApi.File avatar = user != null && user.profilePhoto != null ? user.profilePhoto.small : null;
        bitmap = newBitmap(this, accentColor, letters, avatar);
      } else if (point.isLiveLocation && point.message != null) {
        this.isActive = ((TdApi.MessageLiveLocation) point.message.content).expiresIn > 0;
        marker.setAlpha(isActive ? 1f : FINISHED_BROADCAST_ALPHA);
        bitmap = newBitmap(this, point.message);
      }
      if (bitmap != null) {
        marker.setIcon(new BitmapDrawable(UI.getResources(), bitmap));
      }
    }

    private @Nullable Bitmap newBitmap (MarkerData data, TdApi.Message message) {
      TdlibAccentColor accentColor;
      Letters letters;
      TdApi.File avatar;
      switch (message.senderId.getConstructor()) {
        case TdApi.MessageSenderChat.CONSTRUCTOR: {
          TdApi.Chat chat = tdlib.chat(((TdApi.MessageSenderChat) message.senderId).chatId);
          accentColor = tdlib.chatAccentColor(chat);
          letters = tdlib.chatLetters(chat);
          avatar = chat != null && chat.photo != null ? chat.photo.small : null;
          break;
        }
        case TdApi.MessageSenderUser.CONSTRUCTOR: {
          TdApi.User user = tdlib.cache().user(((TdApi.MessageSenderUser) message.senderId).userId);
          accentColor = tdlib.cache().userAccentColor(user);
          letters = tdlib.cache().userLetters(user);
          avatar = user != null && user.profilePhoto != null ? user.profilePhoto.small : null;
          break;
        }
        default:
          throw new IllegalArgumentException(message.senderId.toString());
      }
      return newBitmap(data, accentColor, letters, avatar);
    }

    private Drawable liveBackground;

    private static void drawAvatar (Canvas c, Bitmap bitmap) {
      BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
      Matrix matrix = new Matrix();
      float scale = Screen.dp(52) / (float) bitmap.getWidth();
      matrix.postTranslate(Screen.dp(5), Screen.dp(5));
      matrix.postScale(scale, scale);
      Paint roundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      roundPaint.setShader(shader);
      shader.setLocalMatrix(matrix);
      RectF rect = Paints.getRectF();
      rect.set(Screen.dp(5), Screen.dp(5), Screen.dp(52 + 5), Screen.dp(52 + 5));
      c.drawRoundRect(rect, Screen.dp(26), Screen.dp(26), roundPaint);
    }

    private void drawAvatar (final MarkerData data, Canvas c, TdlibAccentColor accentColor, Letters letters, TdApi.File avatar) {
      int cx = Screen.dp(62) / 2;
      int cy = Screen.dp(62f) / 2;
      int radius = Screen.dp(26f);

      final ImageFile imageFile;
      if (avatar != null) {
        imageFile = new ImageFile(tdlib, avatar);
        imageFile.setSwOnly(true);
        imageFile.setSize(ChatView.getDefaultAvatarCacheSize());
        synchronized (ImageCache.getReferenceCounters()) {
          Bitmap avatarBitmap = ImageCache.instance().getBitmap(imageFile);
          if (U.isValidBitmap(avatarBitmap)) {
            drawAvatar(c, avatarBitmap);
            return;
          }
        }
      } else {
        imageFile = null;
      }

      c.drawCircle(cx, cy, radius, Paints.fillingPaint(accentColor.getPrimaryColor()));
      Paint paint = Paints.getMediumTextPaint(19f, accentColor.getPrimaryContentColor(), letters.needFakeBold);
      float textWidth = Paints.measureLetters(letters, 19f);
      c.drawText(letters.text, cx - textWidth / 2, cy + Screen.dp(6.5f), paint);

      data.requestFile(imageFile);
    }

    private @Nullable Bitmap newBitmap (MarkerData data, TdlibAccentColor accentColor, Letters letters, TdApi.File avatar) {
      Bitmap result = null;
      boolean success = false;
      try {
        if (liveBackground == null) {
          liveBackground = UI.getResources().getDrawable(R.drawable.bg_livepin);
          liveBackground.setBounds(0, 0, Screen.dp(62f), Screen.dp(76f));
        }
        result = Bitmap.createBitmap(Screen.dp(62), Screen.dp(76), Bitmap.Config.ARGB_8888);
        result.eraseColor(0);
        Canvas c = new Canvas(result);
        liveBackground.draw(c);

        data.canvas = c;
        data.bitmap = result;

        drawAvatar(data, c, accentColor, letters, avatar);
        success = true;
      } catch (Throwable t) {
        Log.w(t);
      }
      if (!success && result != null) {
        try {
          result.recycle();
        } catch (Throwable ignored) { }
        result = null;
      }
      return result;
    }

    public void setPosition (LocationPoint<MarkerData> point) {
      marker.setPosition(new GeoPoint(point.latitude, point.longitude));
      setActive(point.message == null || ((TdApi.MessageLiveLocation) point.message.content).expiresIn > 0);
    }

    private boolean isActive = true;

    public void setActive (boolean isActive) {
      if (this.isActive != isActive) {
        this.isActive = isActive;
        marker.setAlpha(isActive ? 1f : FINISHED_BROADCAST_ALPHA);
      }
    }

    public void remove () {
      marker.closeInfoWindow();
      mapView.getOverlays().remove(marker);
    }

    @Override
    public void performDestroy () {
      requestFile(null);
    }

    private ImageFile requestedFile;

    public void requestFile (ImageFile imageFile) {
      if (this.requestedFile == null && imageFile == null) {
        return;
      }
      if (this.requestedFile != null && imageFile != null && this.requestedFile.accountId() == imageFile.accountId() && this.requestedFile.getId() == imageFile.getId()) {
        return;
      }
      if (this.requestedFile != null) {
        ImageLoader.instance().removeWatcher(reference);
      }
      this.requestedFile = imageFile;
      if (imageFile != null) {
        ImageLoader.instance().requestFile(imageFile, reference);
      }
    }

    private boolean isRequested (ImageFile file) {
      return this.requestedFile != null && this.requestedFile.getId() == file.getId() && this.requestedFile.accountId() == file.accountId();
    }

    @Override
    public void imageLoaded (final ImageFile file, boolean successful, Bitmap bitmap) {
      if (successful && isRequested(file) && canvas != null && U.isValidBitmap(bitmap)) {
        drawAvatar(canvas, bitmap);
        UI.post(() -> {
          if (isRequested(file)) {
            marker.setIcon(new BitmapDrawable(UI.getResources(), this.bitmap));
            mapView.invalidate();
          }
        });
      }
    }

    @Override
    public void imageProgress (ImageFile file, float progress) { }
  }

  private void onMarkerClick (LocationPoint<MarkerData> point) {
    long chatId = 0;
    long messageId = 0;
    if (point.message != null) {
      chatId = point.message.chatId;
      messageId = point.message.id;
    } else if (point.isSelfLocation) {
      chatId = getArgumentsStrict().chatId;
      TdApi.Message outputMessage = tdlib.cache().findOutputLiveLocationMessage(chatId);
      if (outputMessage != null) {
        messageId = outputMessage.id;
      }
    }
    if (chatId != 0 && messageId != 0) {
      tdlib.ui().openChat(this, chatId, new TdlibUi.ChatOpenParameters().highlightMessage(new MessageId(chatId, messageId)).ensureHighlightAvailable());
    }
  }

  @Override
  protected boolean needBackgroundMapInitialization (@NonNull MapView mapView) {
    return false;
  }

  private boolean mapInitialized;
  private MyLocationNewOverlay myLocationOverlay;

  @Override
  protected void initializeMap (@NonNull MapView mapView, boolean inBackground) {
    if (mapInitialized) {
      return;
    }
    mapInitialized = true;
    try {
      applyMapType(mapType(), mapView);

      List<LocationPoint<MarkerData>> points = pointsOfInterest();
      for (LocationPoint<MarkerData> point : points) {
        if (point.data != null) {
          point.data.setPosition(point);
        } else {
          point.data = new MarkerData(tdlib, mapView, point);
        }
      }
      boolean isSharing = isSharingLiveLocation();
      if (isSharing) {
        LocationPoint<MarkerData> point = myLocation(false);
        if (point != null) {
          if (point.data != null) {
            point.data.setPosition(point);
          } else {
            point.data = new MarkerData(tdlib, mapView, point);
          }
        }
      }
      moveCameraToInitial(mapView, getArgumentsStrict().mode == MODE_DROPPED_PIN);
      mapView.onResume();
      executeScheduledAnimation();
    } catch (Throwable t) {
      Log.w(t);
    }
  }

  private void zoomToBoundsSafe (@NonNull final MapView mapView, final BoundingBox bounds, final boolean animated, final int border) {
    if (mapView.getWidth() != 0 && mapView.getHeight() != 0) {
      try {
        mapView.zoomToBoundingBox(bounds, animated, border);
      } catch (Throwable ignored) { }
      return;
    }
    mapView.addOnFirstLayoutListener((v, left, top, right, bottom) -> {
      try {
        mapView.zoomToBoundingBox(bounds, animated, border);
      } catch (Throwable ignored) { }
    });
  }

  private void applyMapType (int mapType, @NonNull MapView mapView) {
    ITileSource tileSource;
    switch (mapType) {
      case Settings.MAP_TYPE_TERRAIN:
        tileSource = TileSourceFactory.USGS_TOPO;
        break;
      case Settings.MAP_TYPE_SATELLITE:
      case Settings.MAP_TYPE_HYBRID:
        tileSource = TileSourceFactory.USGS_SAT;
        break;
      case Settings.MAP_TYPE_DARK:
      case Settings.MAP_TYPE_DEFAULT:
      default:
        tileSource = TileSourceFactory.MAPNIK;
        break;
    }
    try {
      mapView.setTileSource(tileSource);
    } catch (Throwable ignored) { }
  }

  @Override
  protected void resumeMap (@NonNull MapView mapView) {
    try { mapView.onResume(); } catch (Throwable ignored) { }
  }

  @Override
  protected void pauseMap (@NonNull MapView mapView) {
    try { mapView.onPause(); } catch (Throwable ignored) { }
  }

  @Override
  protected void destroyMap (@NonNull MapView mapView) {
    try { mapView.onPause(); } catch (Throwable ignored) { }
    if (myLocationOverlay != null) {
      try { myLocationOverlay.disableMyLocation(); } catch (Throwable ignored) { }
      myLocationOverlay = null;
    }
  }

  @Override
  protected boolean onBuildDirectionTo (@NonNull MapView mapView, double latitude, double longitude) {
    return false;
  }

  @SuppressWarnings("MissingPermission")
  @Override
  protected boolean displayMyLocation (@NonNull MapView mapView) {
    try {
      if (myLocationOverlay == null) {
        myLocationOverlay = new MyLocationNewOverlay(mapView);
        mapView.getOverlays().add(myLocationOverlay);
      }
      if (context.permissions().canAccessLocation()) {
        myLocationOverlay.enableMyLocation();
        return true;
      }
    } catch (Throwable ignored) { }
    return false;
  }

  @Override
  protected void onApplyMapType (int oldType, int newType) {
    MapView view = mapView();
    if (view != null) {
      applyMapType(newType, view);
    }
  }

  @Override
  protected void onPointOfInterestFocusStateChanged (LocationPoint<MarkerData> point, boolean isFocused) {
    // OSMDroid markers do not expose z-ordering; focus state has no visual side effect here.
  }

  @Override
  protected void onPointOfInterestAdded (LocationPoint<MarkerData> point, int toIndex) {
    MapView view = mapView();
    if (view != null) {
      if (point.data != null) {
        point.data.setPosition(point);
      } else {
        point.data = new MarkerData(tdlib, view, point);
      }
      view.invalidate();
    }
  }

  @Override
  protected void onPointOfInterestRemoved (LocationPoint<MarkerData> point, int fromIndex) {
    if (point.data != null) {
      point.data.remove();
      point.data = null;
    }
    MapView view = mapView();
    if (view != null) {
      view.invalidate();
    }
  }

  @Override
  protected void onPointOfInterestCoordinatesChanged (LocationPoint<MarkerData> point, int index) {
    if (point.data != null) {
      point.data.setPosition(point);
    }
  }

  @Override
  protected void onPointOfInterestActiveStateMightChanged (LocationPoint<MarkerData> point, boolean isActive) {
    if (point.data != null) {
      point.data.setActive(isActive);
    }
  }

  private Double singlePointZoom (@Nullable LocationPoint<MarkerData> specificPoint) {
    MapView view = mapView();
    if (specificPoint != null && view != null) {
      double current = view.getZoomLevelDouble();
      return Math.max(current, CLICK_ZOOM_LEVEL);
    }
    return DEFAULT_ZOOM_LEVEL;
  }

  private void moveCameraToInitial (@NonNull MapView mapView, boolean onlyFocus) {
    LocationPoint<MarkerData> singlePoint = null;
    List<LocationPoint<MarkerData>> pointOfInterests = pointsOfInterest();
    LocationPoint<MarkerData> myLoc = myLocation(true);

    if (myLoc == null) {
      if (pointOfInterests.size() == 1) {
        singlePoint = pointOfInterests.get(0);
      }
    } else {
      if (pointOfInterests.isEmpty()) {
        singlePoint = myLoc;
      }
    }

    IMapController controller = mapView.getController();
    if (singlePoint != null) {
      controller.setCenter(new GeoPoint(singlePoint.latitude, singlePoint.longitude));
      controller.setZoom(DEFAULT_ZOOM_LEVEL);
      return;
    }

    BoundingBox bounds = buildBounds(myLoc, pointOfInterests, onlyFocus);
    if (bounds != null) {
      zoomToBoundsSafe(mapView, bounds, false, Screen.dp(82f));
    } else if (myLoc != null) {
      controller.setCenter(new GeoPoint(myLoc.latitude, myLoc.longitude));
      controller.setZoom(DEFAULT_ZOOM_LEVEL);
    }
  }

  private @Nullable BoundingBox buildBounds (@Nullable LocationPoint<MarkerData> myLoc, @NonNull List<LocationPoint<MarkerData>> points, boolean onlyFocus) {
    if (myLoc == null && points.isEmpty()) {
      return null;
    }
    double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
    double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
    if (myLoc != null) {
      minLat = Math.min(minLat, myLoc.latitude);
      maxLat = Math.max(maxLat, myLoc.latitude);
      minLng = Math.min(minLng, myLoc.longitude);
      maxLng = Math.max(maxLng, myLoc.longitude);
    }
    if (onlyFocus) {
      if (hasFocusPoint() && !points.isEmpty()) {
        LocationPoint<MarkerData> p = points.get(0);
        minLat = Math.min(minLat, p.latitude);
        maxLat = Math.max(maxLat, p.latitude);
        minLng = Math.min(minLng, p.longitude);
        maxLng = Math.max(maxLng, p.longitude);
      }
    } else {
      for (LocationPoint<MarkerData> p : points) {
        minLat = Math.min(minLat, p.latitude);
        maxLat = Math.max(maxLat, p.latitude);
        minLng = Math.min(minLng, p.longitude);
        maxLng = Math.max(maxLng, p.longitude);
      }
    }
    if (minLat == Double.POSITIVE_INFINITY) {
      return null;
    }
    // Pad bounds a touch so a single point still has breathing room.
    double padLat = meterToLatitude(Screen.dp(111));
    double padLng = meterToLongitude(Screen.dp(111), (minLat + maxLat) / 2);
    return new BoundingBox(maxLat + padLat, maxLng + padLng, minLat - padLat, minLng - padLng);
  }

  private static final double EARTH_RADIUS = 6366198;

  private static double meterToLongitude (double meterToEast, double latitude) {
    double latArc = Math.toRadians(latitude);
    double radius = Math.cos(latArc) * EARTH_RADIUS;
    double rad = meterToEast / radius;
    return Math.toDegrees(rad);
  }

  private static double meterToLatitude (double meterToNorth) {
    double rad = meterToNorth / EARTH_RADIUS;
    return Math.toDegrees(rad);
  }

  @Override
  protected boolean onPositionRequested (@NonNull MapView mapView, @Nullable LocationPoint<MarkerData> point, boolean animated, boolean needBearing, boolean onlyFocus) {
    IMapController controller = mapView.getController();
    if (point != null) {
      GeoPoint target = new GeoPoint(point.latitude, point.longitude);
      double zoom = singlePointZoom(point);
      if (needBearing) {
        try { mapView.setMapOrientation(point.bearing); } catch (Throwable ignored) { }
      }
      if (animated) {
        controller.animateTo(target, zoom, null);
        return true;
      } else {
        controller.setCenter(target);
        controller.setZoom(zoom);
        return false;
      }
    }

    BoundingBox bounds = buildBounds(myLocation(true), pointsOfInterest(), onlyFocus);
    if (bounds != null) {
      zoomToBoundsSafe(mapView, bounds, animated, Screen.dp(82f));
      return animated;
    }
    return false;
  }

  @Override
  protected boolean wouldRememberMapType (int newMapType) {
    switch (newMapType) {
      case Settings.MAP_TYPE_HYBRID:
      case Settings.MAP_TYPE_SATELLITE:
        return false;
    }
    return true;
  }

  @Override
  protected int[] getAvailableMapTypes () {
    return new int[] {
      Settings.MAP_TYPE_DEFAULT,
      Settings.MAP_TYPE_DARK,
      Settings.MAP_TYPE_SATELLITE,
      Settings.MAP_TYPE_TERRAIN
    };
  }

  @Override
  protected boolean onStartPeriodicBearingUpdates (@NonNull MapView mapView) {
    return false;
  }

  @Override
  protected void onFinishPeriodicBearingUpdates (@NonNull MapView mapView) {
  }
}
