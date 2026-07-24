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
 * File created on 19/06/2022, 02:06.
 */
package org.thunderdog.challegram.util;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.ConnectionListener;
import org.thunderdog.challegram.telegram.FileUpdateListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibContext;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import me.vkryl.android.AppInstallationUtil;
import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableBool;
import me.vkryl.core.reference.ReferenceList;
import tgx.td.Td;

public class AppUpdater implements FileUpdateListener, ConnectionListener {
  public interface Listener {
    void onAppUpdateStateChanged (@State int state, @State int oldState, boolean isApk);
    default void onAppUpdateDownloadProgress (long bytesDownloaded, long totalBytesToDownload) { }
  }

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    State.NONE,
    State.CHECKING,
    State.AVAILABLE,
    State.DOWNLOADING,
    State.READY_TO_INSTALL,
    State.INSTALLING
  })
  public @interface State {
    int NONE = 0; // No info whether there's an update
    int CHECKING = 1; // Checking whether there's any update
    int AVAILABLE = 2; // Available, but doing nothing
    int DOWNLOADING = 3; // Downloading an update
    int READY_TO_INSTALL = 4; // Update is downloaded and is ready to be installed
    int INSTALLING = 5;
  }

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    FlowType.NONE,
    FlowType.TELEGRAM_CHANNEL
  })
  public @interface FlowType {
    int
      NONE = 0,
      TELEGRAM_CHANNEL = 1;
  }

  private final BaseActivity context;
  private final ReferenceList<Listener> listeners;

  private boolean updateOffered;

  private long bytesDownloaded, totalBytesToDownload;
  @Nullable
  private String displayVersion, commit;
  @FlowType
  private int flowType;

  private Tdlib telegramChannelTdlib;
  private Tdlib.UpdateFileInfo telegramChannelFile;

  @State
  private int state = State.NONE;

  public AppUpdater (BaseActivity context) {
    this.context = context;
    this.listeners = new ReferenceList<>();
  }

  @State
  public int state () {
    return state;
  }

  @FlowType
  public int flowType () {
    return flowType;
  }

  public long totalBytesToDownload () {
    return totalBytesToDownload;
  }

  @Nullable
  public String displayVersion () {
    return displayVersion;
  }

  @Nullable
  public String commit () {
    return commit;
  }

  public long bytesDownloaded () {
    return bytesDownloaded;
  }

  public void addListener (Listener listener) {
    listeners.add(listener);
  }

  public void removeListener (Listener listener) {
    listeners.remove(listener);
  }

  public void checkForUpdates () {
    if (state == State.NONE) {
      checkForTelegramChannelUpdates();
    } else if (state == State.AVAILABLE) {
      offerUpdate();
    }
  }

  public static AppInstallationUtil.PublicMarketUrls publicMarketUrls () {
    return new AppInstallationUtil.PublicMarketUrls(
      BuildConfig.DOWNLOAD_URL,
      BuildConfig.GOOGLE_PLAY_URL,
      BuildConfig.GALAXY_STORE_URL,
      BuildConfig.HUAWEI_APPGALLERY_URL,
      BuildConfig.AMAZON_APPSTORE_URL
    );
  }

  public static AppInstallationUtil.DownloadUrl getDownloadUrl (@Nullable String serverSuggestedDownloadUrl) {
    @AppInstallationUtil.InstallerId int installerId = AppInstallationUtil.getInstallerId(UI.getAppContext());
    AppInstallationUtil.PublicMarketUrls publicMarketUrls = publicMarketUrls();
    return publicMarketUrls.toDownloadUrl(installerId, serverSuggestedDownloadUrl);
  }

  private void setState (@State int state) {
    if (this.state != state) {
      final int oldState = this.state;
      this.state = state;
      for (Listener listener : listeners) {
        listener.onAppUpdateStateChanged(state, oldState, flowType == FlowType.TELEGRAM_CHANNEL);
      }
    }
  }

  private void checkForTelegramChannelUpdates () {
    setState(State.CHECKING);
    Tdlib tdlib = context.hasTdlib() ? context.currentTdlib() : null;
    if (tdlib == null || tdlib.context().inRecoveryMode() || !tdlib.isAuthorized()) {
      onUpdateUnavailable();
      return;
    }
    if (BuildConfig.EXPERIMENTAL || (!AppInstallationUtil.allowInAppTelegramUpdates(UI.getAppContext()) && !tdlib.hasUrgentInAppUpdate())) {
      onUpdateUnavailable();
      return;
    }
    tdlib.findUpdateFile(updateFile -> {
      RunnableBool act = updateFileLoadedAndExists -> tdlib.ui().post(() -> {
        if (updateFile != null) {
          this.telegramChannelTdlib = tdlib;
          this.telegramChannelFile = updateFile;
          TdApi.File file = updateFile.document.document;
          tdlib.listeners().addFileListener(file.id, this);
          onUpdateAvailable(FlowType.TELEGRAM_CHANNEL,
            file.local.downloadedSize,
            file.expectedSize,
            updateFile.version,
            updateFile.commit,
            updateFileLoadedAndExists
          );
        } else {
          onUpdateUnavailable();
        }
      });
      if (updateFile != null) {
        tdlib.files().isFileLoadedAndExists(updateFile.document.document, act);
      } else {
        act.runWithBool(false);
      }
    });
  }

  private boolean offerTelegramChannelUpdate () {
    switch (Settings.instance().getAutoUpdateMode()) {
      case Settings.AUTO_UPDATE_MODE_PROMPT: {
        ViewController<?> c = context.navigation().getCurrentStackItem();
        if (c != null && c.isFocused()) {
          final long bytesToDownload = totalBytesToDownload() - bytesDownloaded();
          final String displayVersion = displayVersion();
          final String commit = commit();
          ViewController.Options.Builder b = new ViewController.Options.Builder()
            .info(Lang.getMarkdownString(c,
              !StringUtils.isEmpty(displayVersion) ? R.string.AppUpdateAvailableVersionPrompt : R.string.AppUpdateAvailablePrompt,
              (target, argStart, argEnd, argIndex, needFakeBold) -> argIndex != 1 ? Lang.boldCreator().onCreateSpan(target, argStart, argEnd, argIndex, needFakeBold) : null,
              Strings.buildSize(bytesToDownload), displayVersion
            ))
            .item(new ViewController.OptionItem(R.id.btn_update, Lang.getString(R.string.DownloadUpdate), ViewController.OptionColor.BLUE, R.drawable.baseline_system_update_24));
          final String changesUrl = commit != null && !BuildConfig.COMMIT.equals(commit) ? BuildConfig.REMOTE_URL + "/compare/" + BuildConfig.COMMIT + "..." + commit : null;
          if (changesUrl != null) {
            b.item(new ViewController.OptionItem(R.id.btn_sourceCode, Lang.getString(R.string.UpdateSourceChanges), ViewController.OptionColor.NORMAL, R.drawable.baseline_code_24));
          }
          b.cancelItem();
          c.showOptions(b.build(), (optionItemView, id) -> {
            if (id == R.id.btn_update) {
              downloadUpdate();
            } else if (id == R.id.btn_sourceCode) {
              UI.openUrl(changesUrl);
            }
            return true;
          });
          return true;
        }
        break;
      }
      case Settings.AUTO_UPDATE_MODE_ALWAYS: {
        downloadUpdate();
        break;
      }
      case Settings.AUTO_UPDATE_MODE_WIFI_ONLY: {
        if (telegramChannelTdlib.networkType() instanceof TdApi.NetworkTypeWiFi) {
          downloadUpdate();
        } else {
          telegramChannelTdlib.listeners().subscribeToConnectivityUpdates(this);
          return true;
        }
        break;
      }
      case Settings.AUTO_UPDATE_MODE_NEVER: {
        break;
      }
    }
    return false;
  }

  @Override
  public void onConnectionTypeChanged (TdApi.NetworkType type) {
    if (type instanceof TdApi.NetworkTypeWiFi) {
      telegramChannelTdlib.listeners().unsubscribeFromConnectivityUpdates(this);
      checkForUpdates();
    }
  }

  @Override
  public void onUpdateFile (TdApi.UpdateFile updateFile) {
    telegramChannelTdlib.ui().post(() -> {
      TdApi.File currentFile = telegramChannelFile.document.document;
      if (updateFile.file.id == currentFile.id) {
        Td.copyTo(updateFile.file, currentFile);
        if (TD.isFileLoadedAndExists(updateFile.file)) {
          onUpdateAvailable(FlowType.TELEGRAM_CHANNEL, updateFile.file.local.downloadedSize, updateFile.file.expectedSize, telegramChannelFile.version, telegramChannelFile.commit, true);
        } else if (TD.isFileLoading(updateFile.file)) {
          onUpdateDownloading();
          onUpdateDownloadProgress(updateFile.file.local.downloadedSize, updateFile.file.expectedSize);
        } else {
          onUpdateDownloadCanceled();
        }
      } else {
        telegramChannelTdlib.listeners().removeFileListener(updateFile.file.id, this);
      }
    });
  }

  private void onUpdateAvailable (@FlowType int flowType, long bytesDownloaded, long totalBytesToDownload, @Nullable String displayVersion, @Nullable String commit, boolean readyToInstall) {
    this.flowType = flowType;
    this.bytesDownloaded = bytesDownloaded;
    this.totalBytesToDownload = totalBytesToDownload;
    this.displayVersion = displayVersion;
    this.commit = commit;
    if (readyToInstall) {
      setState(State.READY_TO_INSTALL);
    } else {
      setState(State.AVAILABLE);
      offerUpdate();
    }
  }

  public void offerUpdate () {
    if (!updateOffered) {
      switch (flowType) {
        case FlowType.NONE:
          // Do nothing.
          break;
        case FlowType.TELEGRAM_CHANNEL: {
          updateOffered = offerTelegramChannelUpdate();
          break;
        }
      }
    }
  }

  public void downloadUpdate () {
    if (state != State.AVAILABLE) {
      return;
    }
    switch (flowType) {
      case FlowType.NONE:
        // Do nothing.
        break;
      case FlowType.TELEGRAM_CHANNEL: {
        // TODO add tdlib reference & show progress
        telegramChannelTdlib.files().downloadFile(telegramChannelFile.document.document);
        break;
      }
    }
  }

  private void onUpdateDownloadProgress (long bytesDownloaded, long totalBytesToDownload) {
    this.bytesDownloaded = bytesDownloaded;
    this.totalBytesToDownload = totalBytesToDownload;
    for (Listener listener : listeners) {
      listener.onAppUpdateDownloadProgress(bytesDownloaded, totalBytesToDownload);
    }
  }

  public void installUpdate () {
    if (state != State.READY_TO_INSTALL) {
      return;
    }
    switch (flowType) {
      case FlowType.NONE:
        // Do nothing.
        break;
      case FlowType.TELEGRAM_CHANNEL: {
        // TODO guide on how to allow installing APKs
        UI.openFile(new TdlibContext(context, telegramChannelTdlib), telegramChannelFile.document.fileName, new File(telegramChannelFile.document.document.local.path), telegramChannelFile.document.mimeType, 0);
        break;
      }
    }
  }

  private void onUpdateDownloading () {
    if (state != State.AVAILABLE)
      return;
    setState(State.DOWNLOADING);
  }

  private void onUpdateDownloadCanceled () {
    if (state != State.DOWNLOADING && state != State.READY_TO_INSTALL)
      return;
    setState(State.AVAILABLE);
  }

  private void onUpdateUnavailable () {
    setState(State.NONE);
  }
}
