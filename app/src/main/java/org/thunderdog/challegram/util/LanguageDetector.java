package org.thunderdog.challegram.util;

import android.content.Context;

import androidx.annotation.Nullable;

import me.vkryl.core.lambda.RunnableData;

public class LanguageDetector {
  public static void detectLanguage (Context context, String text, RunnableData<String> onSuccess, @Nullable RunnableData<Throwable> onFail) {
    detectLanguage(context, text, onSuccess, onFail, false);
  }

  private static void detectLanguage (Context context, String text, RunnableData<String> onSuccess, @Nullable RunnableData<Throwable> onFail, boolean initializeFirst) {
    if (onFail != null) {
      onFail.runWithData(new IllegalStateException("Language detection is unavailable in the FOSS build."));
    }
  }
}
