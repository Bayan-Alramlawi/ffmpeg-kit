/*
 * Copyright (c) 2018-2022 Taner Sener
 *
 * This file is part of FFmpegKit.
 *
 * FFmpegKit is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FFmpegKit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FFmpegKit.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.arthenica.ffmpegkit.flutter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.AbiDetect;
import com.arthenica.ffmpegkit.AbstractSession;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.FFprobeSession;
import com.arthenica.ffmpegkit.Level;
import com.arthenica.ffmpegkit.LogRedirectionStrategy;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationJsonParser;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.Packages;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Session;
import com.arthenica.ffmpegkit.SessionState;
import com.arthenica.ffmpegkit.Signal;
import com.arthenica.ffmpegkit.Statistics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener;

public class FFmpegKitFlutterPlugin implements FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler, EventChannel.StreamHandler, ActivityResultListener {

    public static final String LIBRARY_NAME = "ffmpeg-kit-flutter";
    public static final String PLATFORM_NAME = "android";

    private static final String METHOD_CHANNEL = "flutter.arthenica.com/ffmpeg_kit";
    private static final String EVENT_CHANNEL = "flutter.arthenica.com/ffmpeg_kit_event";

    // LOG CLASS
    public static final String KEY_LOG_SESSION_ID = "sessionId";
    public static final String KEY_LOG_LEVEL = "level";
    public static final String KEY_LOG_MESSAGE = "message";

    // STATISTICS CLASS
    public static final String KEY_STATISTICS_SESSION_ID = "sessionId";
    public static final String KEY_STATISTICS_VIDEO_FRAME_NUMBER = "videoFrameNumber";
    public static final String KEY_STATISTICS_VIDEO_FPS = "videoFps";
    public static final String KEY_STATISTICS_VIDEO_QUALITY = "videoQuality";
    public static final String KEY_STATISTICS_SIZE = "size";
    public static final String KEY_STATISTICS_TIME = "time";
    public static final String KEY_STATISTICS_BITRATE = "bitrate";
    public static final String KEY_STATISTICS_SPEED = "speed";

    // SESSION CLASS
    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_SESSION_CREATE_TIME = "createTime";
    public static final String KEY_SESSION_START_TIME = "startTime";
    public static final String KEY_SESSION_COMMAND = "command";
    public static final String KEY_SESSION_TYPE = "type";
    public static final String KEY_SESSION_MEDIA_INFORMATION = "mediaInformation";

    // SESSION TYPE
    public static final int SESSION_TYPE_FFMPEG = 1;
    public static final int SESSION_TYPE_FFPROBE = 2;
    public static final int SESSION_TYPE_MEDIA_INFORMATION = 3;

    // EVENTS
    public static final String EVENT_LOG_CALLBACK_EVENT = "FFmpegKitLogCallbackEvent";
    public static final String EVENT_STATISTICS_CALLBACK_EVENT = "FFmpegKitStatisticsCallbackEvent";
    public static final String EVENT_COMPLETE_CALLBACK_EVENT = "FFmpegKitCompleteCallbackEvent";

    // REQUEST CODES
    public static final int READABLE_REQUEST_CODE = 10000;
    public static final int WRITABLE_REQUEST_CODE = 20000;

    // ARGUMENT NAMES
    public static final String ARGUMENT_SESSION_ID = "sessionId";
    public static final String ARGUMENT_WAIT_TIMEOUT = "waitTimeout";
    public static final String ARGUMENT_ARGUMENTS = "arguments";
    public static final String ARGUMENT_FFPROBE_JSON_OUTPUT = "ffprobeJsonOutput";
    public static final String ARGUMENT_WRITABLE = "writable";

    private static final int asyncConcurrencyLimit = 10;

    private final AtomicBoolean logsEnabled;
    private final AtomicBoolean statisticsEnabled;
    private final ExecutorService asyncExecutorService;

    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private io.flutter.plugin.common.MethodChannel.Result lastInitiatedIntentResult;
    private Context context;
    private Activity activity;
    private FlutterPlugin.FlutterPluginBinding flutterPluginBinding;
    private ActivityPluginBinding activityPluginBinding;

    private EventChannel.EventSink eventSink;
    private final FFmpegKitFlutterMethodResultHandler resultHandler;

    public FFmpegKitFlutterPlugin() {
        this.logsEnabled = new AtomicBoolean(false);
        this.statisticsEnabled = new AtomicBoolean(false);
        this.asyncExecutorService = Executors.newFixedThreadPool(asyncConcurrencyLimit);
        this.resultHandler = new FFmpegKitFlutterMethodResultHandler();

        Log.d(LIBRARY_NAME, String.format("FFmpegKitFlutterPlugin created %s.", this));
    }

    // Legacy registration method removed
//    public static void registerWith(final io.flutter.plugin.common.PluginRegistry.Registrar registrar) {
//        final Context context = (registrar.activity() != null) ? registrar.activity() : registrar.context();
//        if (context == null) {
//            Log.w(LIBRARY_NAME, "FFmpegKitFlutterPlugin can not be registered without a context.");
//            return;
//        }
//        FFmpegKitFlutterPlugin plugin = new FFmpegKitFlutterPlugin();
//        plugin.init(registrar.messenger(), context, registrar.activity(), null);
//    }

    protected void registerGlobalCallbacks() {
        FFmpegKitConfig.enableFFmpegSessionCompleteCallback(this::emitSession);
        FFmpegKitConfig.enableFFprobeSessionCompleteCallback(this::emitSession);
        FFmpegKitConfig.enableMediaInformationSessionCompleteCallback(this::emitSession);

        FFmpegKitConfig.enableLogCallback(log -> {
            if (logsEnabled.get()) {
                emitLog(log);
            }
        });

        FFmpegKitConfig.enableStatisticsCallback(statistics -> {
            if (statisticsEnabled.get()) {
                emitStatistics(statistics);
            }
        });
    }

    @Override
    public void onAttachedToEngine(@NonNull final FlutterPlugin.FlutterPluginBinding flutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding;
    }

    @Override
    public void onDetachedFromEngine(@NonNull final FlutterPlugin.FlutterPluginBinding binding) {
        this.flutterPluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding activityPluginBinding) {
        Log.d(LIBRARY_NAME, String.format("FFmpegKitFlutterPlugin %s attached to activity %s.", this, activityPluginBinding.getActivity()));
        init(
                flutterPluginBinding.getBinaryMessenger(),
                flutterPluginBinding.getApplicationContext(),
                activityPluginBinding.getActivity(),
                activityPluginBinding
        );
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding activityPluginBinding) {
        onAttachedToActivity(activityPluginBinding);
    }

    @Override
    public void onDetachedFromActivity() {
        uninit();
        Log.d(LIBRARY_NAME, "FFmpegKitFlutterPlugin detached from activity.");
    }

    @Override
    public void onListen(final Object arguments, final EventChannel.EventSink eventSink) {
        this.eventSink = eventSink;
        Log.d(LIBRARY_NAME, String.format("FFmpegKitFlutterPlugin %s started listening to events on %s.", this, eventSink));
    }

    @Override
    public void onCancel(final Object arguments) {
        this.eventSink = null;
        Log.d(LIBRARY_NAME, "FFmpegKitFlutterPlugin stopped listening to events.");
    }

    @Override
    public boolean onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        Log.d(LIBRARY_NAME, String.format("selectDocument completed with requestCode: %d, resultCode: %d, data: %s.", requestCode, resultCode, data == null ? null : data.toString()));

        if (requestCode == READABLE_REQUEST_CODE || requestCode == WRITABLE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                if (data == null) {
                    resultHandler.successAsync(lastInitiatedIntentResult, null);
                } else {
                    final Uri uri = data.getData();
                    resultHandler.successAsync(lastInitiatedIntentResult, uri == null ? null : uri.toString());
                }
            } else {
                resultHandler.errorAsync(lastInitiatedIntentResult, "SELECT_CANCELLED", String.valueOf(resultCode));
            }
            return true;
        } else {
            Log.i(LIBRARY_NAME, String.format("FFmpegKitFlutterPlugin ignored unsupported activity result for requestCode: %d.", requestCode));
            return false;
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull io.flutter.plugin.common.MethodChannel.Result result) {
        final Integer sessionId = call.argument(ARGUMENT_SESSION_ID);
        final Integer waitTimeout = call.argument(ARGUMENT_WAIT_TIMEOUT);
        final List<String> arguments = call.argument(ARGUMENT_ARGUMENTS);
        final String ffprobeJsonOutput = call.argument(ARGUMENT_FFPROBE_JSON_OUTPUT);
        final Boolean writable = call.argument(ARGUMENT_WRITABLE);

        switch (call.method) {
            case "abstractSessionGetEndTime":
                if (sessionId != null) {
                    abstractSessionGetEndTime(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "abstractSessionGetDuration":
                if (sessionId != null) {
                    abstractSessionGetDuration(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "abstractSessionGetAllLogs":
                if (sessionId != null) {
                    abstractSessionGetAllLogs(sessionId, waitTimeout, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "abstractSessionGetLogs":
                if (sessionId != null) {
                    abstractSessionGetLogs(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "abstractSessionGetAllLogsAsString":
                if (sessionId != null) {
                    abstractSessionGetAllLogsAsString(sessionId, waitTimeout, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "abstractSessionGetState":
                if (sessionId != null) {
                    abstractSessionGetState(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "abstractSessionGetReturnCode":
                if (sessionId != null) {
                    abstractSessionGetReturnCode(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "abstractSessionGetFailStackTrace":
                if (sessionId != null) {
                    abstractSessionGetFailStackTrace(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "thereAreAsynchronousMessagesInTransmit":
                if (sessionId != null) {
                    abstractSessionThereAreAsynchronousMessagesInTransmit(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "getArch":
                getArch(result);
                break;
            case "ffmpegSession":
                if (arguments != null) {
                    ffmpegSession(arguments, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_ARGUMENTS", "Invalid arguments array.");
                }
                break;
            case "ffmpegSessionGetAllStatistics":
                if (sessionId != null) {
                    ffmpegSessionGetAllStatistics(sessionId, waitTimeout, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "ffmpegSessionGetStatistics":
                if (sessionId != null) {
                    ffmpegSessionGetStatistics(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "ffprobeSession":
                if (arguments != null) {
                    ffprobeSession(arguments, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_ARGUMENTS", "Invalid arguments array.");
                }
                break;
            case "mediaInformationSession":
                if (arguments != null) {
                    mediaInformationSession(arguments, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_ARGUMENTS", "Invalid arguments array.");
                }
                break;
            case "getMediaInformation":
                if (sessionId != null) {
                    getMediaInformation(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "mediaInformationJsonParserFrom":
                if (ffprobeJsonOutput != null) {
                    mediaInformationJsonParserFrom(ffprobeJsonOutput, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_FFPROBE_JSON_OUTPUT", "Invalid ffprobe json output.");
                }
                break;
            case "mediaInformationJsonParserFromWithError":
                if (ffprobeJsonOutput != null) {
                    mediaInformationJsonParserFromWithError(ffprobeJsonOutput, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_FFPROBE_JSON_OUTPUT", "Invalid ffprobe json output.");
                }
                break;
            case "enableRedirection":
                enableRedirection(result);
                break;
            case "disableRedirection":
                disableRedirection(result);
                break;
            case "enableLogs":
                enableLogs(result);
                break;
            case "disableLogs":
                disableLogs(result);
                break;
            case "enableStatistics":
                enableStatistics(result);
                break;
            case "disableStatistics":
                disableStatistics(result);
                break;
            case "setFontconfigConfigurationPath":
                final String path = call.argument("path");
                if (path != null) {
                    setFontconfigConfigurationPath(path, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_PATH", "Invalid path.");
                }
                break;
            case "setFontDirectory": {
                final String fontDirectory = call.argument("fontDirectory");
                final Map<String, String> fontNameMap = call.argument("fontNameMap");
                if (fontDirectory != null) {
                    setFontDirectory(fontDirectory, fontNameMap, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_FONT_DIRECTORY", "Invalid font directory.");
                }
                break;
            }
            case "setFontDirectoryList": {
                final List<String> fontDirectoryList = call.argument("fontDirectoryList");
                final Map<String, String> fontNameMap = call.argument("fontNameMap");
                if (fontDirectoryList != null) {
                    setFontDirectoryList(fontDirectoryList, fontNameMap, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_FONT_DIRECTORY_LIST", "Invalid font directory list.");
                }
                break;
            }
            case "registerNewFFmpegPipe":
                registerNewFFmpegPipe(result);
                break;
            case "closeFFmpegPipe":
                final String ffmpegPipePath = call.argument("ffmpegPipePath");
                if (ffmpegPipePath != null) {
                    closeFFmpegPipe(ffmpegPipePath, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_PIPE_PATH", "Invalid ffmpeg pipe path.");
                }
                break;
            case "getFFmpegVersion":
                getFFmpegVersion(result);
                break;
            case "isLTSBuild":
                isLTSBuild(result);
                break;
            case "getBuildDate":
                getBuildDate(result);
                break;
            case "setEnvironmentVariable":
                final String variableName = call.argument("variableName");
                final String variableValue = call.argument("variableValue");
                if (variableName != null && variableValue != null) {
                    setEnvironmentVariable(variableName, variableValue, result);
                } else if (variableValue != null) {
                    resultHandler.errorAsync(result, "INVALID_NAME", "Invalid environment variable name.");
                } else {
                    resultHandler.errorAsync(result, "INVALID_VALUE", "Invalid environment variable value.");
                }
                break;
            case "ignoreSignal":
                final Integer signal = call.argument("signal");
                if (signal != null) {
                    ignoreSignal(signal, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SIGNAL", "Invalid signal value.");
                }
                break;
            case "ffmpegSessionExecute":
                if (sessionId != null) {
                    ffmpegSessionExecute(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "ffprobeSessionExecute":
                if (sessionId != null) {
                    ffprobeSessionExecute(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "mediaInformationSessionExecute":
                if (sessionId != null) {
                    mediaInformationSessionExecute(sessionId, waitTimeout, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "asyncFFmpegSessionExecute":
                if (sessionId != null) {
                    asyncFFmpegSessionExecute(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "asyncFFprobeSessionExecute":
                if (sessionId != null) {
                    asyncFFprobeSessionExecute(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "asyncMediaInformationSessionExecute":
                if (sessionId != null) {
                    asyncMediaInformationSessionExecute(sessionId, waitTimeout, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "getLogLevel":
                getLogLevel(result);
                break;
            case "setLogLevel":
                final Integer level = call.argument("level");
                if (level != null) {
                    setLogLevel(level, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_LEVEL", "Invalid level value.");
                }
                break;
            case "getSessionHistorySize":
                getSessionHistorySize(result);
                break;
            case "setSessionHistorySize":
                final Integer sessionHistorySize = call.argument("sessionHistorySize");
                if (sessionHistorySize != null) {
                    setSessionHistorySize(sessionHistorySize, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SIZE", "Invalid session history size value.");
                }
                break;
            case "getSession":
                if (sessionId != null) {
                    getSession(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "getLastSession":
                getLastSession(result);
                break;
            case "getLastCompletedSession":
                getLastCompletedSession(result);
                break;
            case "getSessions":
                getSessions(result);
                break;
            case "clearSessions":
                clearSessions(result);
                break;
            case "getSessionsByState":
                final Integer state = call.argument("state");
                if (state != null) {
                    getSessionsByState(state, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION_STATE", "Invalid session state value.");
                }
                break;
            case "getLogRedirectionStrategy":
                getLogRedirectionStrategy(result);
                break;
            case "setLogRedirectionStrategy":
                final Integer strategy = call.argument("strategy");
                if (strategy != null) {
                    setLogRedirectionStrategy(strategy, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_LOG_REDIRECTION_STRATEGY", "Invalid log redirection strategy value.");
                }
                break;
            case "messagesInTransmit":
                if (sessionId != null) {
                    messagesInTransmit(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "getPlatform":
                getPlatform(result);
                break;
            case "writeToPipe":
                final String input = call.argument("input");
                final String pipe = call.argument("pipe");
                if (input != null && pipe != null) {
                    writeToPipe(input, pipe, result);
                } else if (pipe != null) {
                    resultHandler.errorAsync(result, "INVALID_INPUT", "Invalid input value.");
                } else {
                    resultHandler.errorAsync(result, "INVALID_PIPE", "Invalid pipe value.");
                }
                break;
            case "selectDocument":
                final String title = call.argument("title");
                final String type = call.argument("type");
                final List<String> extraTypes = call.argument("extraTypes");
                final String[] extraTypesArray = (extraTypes != null) ? extraTypes.toArray(new String[0]) : null;
                if (writable != null) {
                    selectDocument(writable, title, type, extraTypesArray, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_WRITABLE", "Invalid writable value.");
                }
                break;
            case "getSafParameter":
                final String uri = call.argument("uri");
                final String openMode = call.argument("openMode");
                if (uri != null && openMode != null) {
                    getSafParameter(uri, openMode, result);
                } else if (uri != null) {
                    resultHandler.errorAsync(result, "INVALID_OPEN_MODE", "Invalid openMode value.");
                } else {
                    resultHandler.errorAsync(result, "INVALID_URI", "Invalid uri value.");
                }
                break;
            case "cancel":
                cancel(result);
                break;
            case "cancelSession":
                if (sessionId != null) {
                    cancelSession(sessionId, result);
                } else {
                    resultHandler.errorAsync(result, "INVALID_SESSION", "Invalid session id.");
                }
                break;
            case "getFFmpegSessions":
                getFFmpegSessions(result);
                break;
            case "getFFprobeSessions":
                getFFprobeSessions(result);
                break;
            case "getMediaInformationSessions":
                getMediaInformationSessions(result);
                break;
            case "getPackageName":
                getPackageName(result);
                break;
            case "getExternalLibraries":
                getExternalLibraries(result);
                break;
            default:
                resultHandler.notImplementedAsync(result);
                break;
        }
    }

    protected void uninit() {
        uninitMethodChannel();
        uninitEventChannel();

        if (this.activityPluginBinding != null) {
            this.activityPluginBinding.removeActivityResultListener(this);
        }

        this.context = null;
        this.activity = null;
        this.activityPluginBinding = null;

        Log.d(LIBRARY_NAME, "FFmpegKitFlutterPlugin uninitialized.");
    }

    protected void uninitMethodChannel() {
        if (methodChannel == null) {
            Log.i(LIBRARY_NAME, "FFmpegKitFlutterPlugin method channel was already uninitialised.");
            return;
        }
        methodChannel.setMethodCallHandler(null);
        methodChannel = null;
    }

    protected void uninitEventChannel() {
        if (eventChannel == null) {
            Log.i(LIBRARY_NAME, "FFmpegKitFlutterPlugin event channel was already uninitialised.");
            return;
        }
        eventChannel.setStreamHandler(null);
        eventChannel = null;
    }

    // --- The rest of the helper methods (toMap, toList, etc.) remain unchanged ---

    protected static long toLong(final Date date) {
        return (date != null) ? date.getTime() : 0;
    }

    protected static int toInt(final Level level) {
        return (level == null) ? Level.AV_LOG_TRACE.getValue() : level.getValue();
    }

    protected static Map<String, Object> toMap(final Session session) {
        if (session == null) {
            return null;
        }
        final Map<String, Object> sessionMap = new HashMap<>();
        sessionMap.put(KEY_SESSION_ID, session.getSessionId());
        sessionMap.put(KEY_SESSION_CREATE_TIME, toLong(session.getCreateTime()));
        sessionMap.put(KEY_SESSION_START_TIME, toLong(session.getStartTime()));
        sessionMap.put(KEY_SESSION_COMMAND, session.getCommand());
        if (session.isFFmpeg()) {
            sessionMap.put(KEY_SESSION_TYPE, SESSION_TYPE_FFMPEG);
        } else if (session.isFFprobe()) {
            sessionMap.put(KEY_SESSION_TYPE, SESSION_TYPE_FFPROBE);
        } else if (session.isMediaInformation()) {
            final MediaInformationSession mediaInformationSession = (MediaInformationSession) session;
            final MediaInformation mediaInformation = mediaInformationSession.getMediaInformation();
            if (mediaInformation != null) {
                sessionMap.put(KEY_SESSION_MEDIA_INFORMATION, toMap(mediaInformation));
            }
            sessionMap.put(KEY_SESSION_TYPE, SESSION_TYPE_MEDIA_INFORMATION);
        }
        return sessionMap;
    }

    // ... (Other helper methods such as toMap for Log, Statistics, JSON objects, etc.)

    protected void emitLog(final com.arthenica.ffmpegkit.Log log) {
        final HashMap<String, Object> logMap = new HashMap<>();
        logMap.put(EVENT_LOG_CALLBACK_EVENT, toMap(log));
        resultHandler.successAsync(eventSink, logMap);
    }

    protected void emitStatistics(final Statistics statistics) {
        final HashMap<String, Object> statisticsMap = new HashMap<>();
        statisticsMap.put(EVENT_STATISTICS_CALLBACK_EVENT, toMap(statistics));
        resultHandler.successAsync(eventSink, statisticsMap);
    }

    protected void emitSession(final Session session) {
        final HashMap<String, Object> sessionMap = new HashMap<>();
        sessionMap.put(EVENT_COMPLETE_CALLBACK_EVENT, toMap(session));
        resultHandler.successAsync(eventSink, sessionMap);
    }
}