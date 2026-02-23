package org.apache.cordova.camera;


import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Locale;            // 추가 필요 (SimpleDateFormat 인자 사용 시)
import java.io.FileDescriptor; // [중요] 추가됨
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLConnection; // guessContentTypeFromName 사용 시 필요
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List; // List 클래스를 사용하기 위해 필요


import android.content.pm.ResolveInfo; // ResolveInfo 클래스를 사용하기 위해 필요
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;
import android.util.Log;
import android.content.ClipData;
import android.provider.OpenableColumns;

import androidx.core.content.FileProvider;

import org.apache.cordova.BuildHelper;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;


public class CameraLauncher extends CordovaPlugin implements MediaScannerConnectionClient {

    private static final int DATA_URL = 0;              // Return base64 encoded string
    private static final int FILE_URI = 1;              // Return file uri (content://media/external/images/media/2 for Android)

    private static final int PHOTOLIBRARY = 0;          // Choose image from picture library (same as SAVEDPHOTOALBUM for Android)
    private static final int CAMERA = 1;                // Take picture from camera
    private static final int SAVEDPHOTOALBUM = 2;       // Choose image from picture library (same as PHOTOLIBRARY for Android)

    private static final int PICTURE = 0;               // allow selection of still pictures only. DEFAULT. Will return format specified via DestinationType
    private static final int VIDEO = 1;                 // allow selection of video only, ONLY RETURNS URL
    private static final int ALLMEDIA = 2;              // allow selection from all media types

    private static final int JPEG = 0;                  // Take a picture of type JPEG
    private static final int PNG = 1;                   // Take a picture of type PNG
    private static final String JPEG_TYPE = "jpg";
    private static final String PNG_TYPE = "png";
    private static final String JPEG_EXTENSION = "." + JPEG_TYPE;
    private static final String PNG_EXTENSION = "." + PNG_TYPE;
    private static final String PNG_MIME_TYPE = "image/png";
    private static final String JPEG_MIME_TYPE = "image/jpeg";
    private static final String HEIC_MIME_TYPE = "image/heic";
    private static final String GET_PICTURE = "Get Picture";
    private static final String GET_VIDEO = "Get Video";
    private static final String GET_All = "Get All";
    private static final String CROPPED_URI_KEY = "croppedUri";
    private static final String IMAGE_URI_KEY = "imageUri";

    private static final String TAKE_PICTURE_ACTION = "takePicture";

    public static final int PERMISSION_DENIED_ERROR = 20;
    public static final int TAKE_PIC_SEC = 0;
    public static final int SAVE_TO_ALBUM_SEC = 1;

    private static final String LOG_TAG = "CameraLauncher";

    //Where did this come from?
    private static final int CROP_CAMERA = 100;

    private static final String TIME_FORMAT = "yyyyMMdd_HHmmss";

    private int mQuality;                   // Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
    private int targetWidth;                // desired width of the image
    private int targetHeight;               // desired height of the image
    private Uri imageUri;                   // Uri of captured image
    private int encodingType;               // Type of encoding to use
    private int mediaType;                  // What type of media to retrieve
    private int destType;                   // Source type (needs to be saved for the permission handling)
    private int srcType;                    // Destination type (needs to be saved for permission handling)
    private boolean saveToPhotoAlbum;       // Should the picture be saved to the device's photo album
    private boolean correctOrientation;     // Should the pictures orientation be corrected
    private boolean orientationCorrected;   // Has the picture's orientation been corrected
    private boolean allowEdit;              // Should we allow the user to crop the image.

    public CallbackContext callbackContext;

    private MediaScannerConnection conn;    // Used to update gallery app with newly-written files
    private Uri scanMe;                     // Uri of image to be added to content store
    private Uri croppedUri;
    private String croppedFilePath;
    private ExifHelper exifData;            // Exif data from source
    private String applicationId;


    private void logreport(final Throwable t) {
        // 1. 1차 방어: 앱이 종료된 상태면 실행하지 않음
        if (this.cordova == null || this.cordova.getActivity() == null) {
            return;
        }

        // 2. 10초 지연 (Handler 사용)
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // =========================================================
                // 3. 10초 후 실행되는 시점
                // =========================================================
                
                // 2차 방어: 10초 사이에 사용자가 앱을 껐거나 Activity가 사라졌는지 확인
                if (cordova == null || cordova.getActivity() == null || cordova.getActivity().isFinishing()) {
                    return;
                }

                // 4. 실제 무거운 작업(네트워크/파일)은 백그라운드 스레드 풀로 위임
                cordova.getThreadPool().execute(new Runnable() {
                    @Override
                    public void run() {
                        HttpURLConnection conn = null;
                        try {
                            Context context = cordova.getActivity(); // Context 참조

                            // --- [추가] 앱 버전 정보 수집 ---
                            String appVersion = "Unknown";
                            try {
                                PackageManager pm = context.getPackageManager();
                                PackageInfo pInfo = pm.getPackageInfo(context.getPackageName(), 0);
                                // 예: 1.0.3 (Build 12) 형식
                                appVersion = pInfo.versionName + " (Build " + pInfo.versionCode + ")";
                            } catch (Exception e) {
                                // 버전 정보 가져오기 실패 시 무시
                            }

                            // --- (기존 로직) 메모리 정보 수집 ---
                            String memoryInfo = "Memory Info Unavailable";
                            try {
                                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                                ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                                if (activityManager != null) {
                                    activityManager.getMemoryInfo(mi);
                                    Runtime runtime = Runtime.getRuntime();
                                    long totalRAM = mi.totalMem / 1048576L;
                                    long availRAM = mi.availMem / 1048576L;
                                    long maxHeap = runtime.maxMemory() / 1048576L;
                                    long usedHeap = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;

                                    memoryInfo = "RAM: " + availRAM + "/" + totalRAM + "MB, " +
                                                 "HeapUsed: " + usedHeap + "/" + maxHeap + "MB";
                                }
                            } catch (Exception e) {
                                // 메모리 체크 중 에러 무시
                            }

                            // --- (기존 로직) 로그 내용 구성 ---
                            StackTraceElement[] stackTrace = t.getStackTrace();
                            String location = (stackTrace != null && stackTrace.length > 0)
                                    ? stackTrace[0].getFileName() + ":" + stackTrace[0].getLineNumber()
                                    : "Unknown Location";

                            String fullStackTrace = Log.getStackTraceString(t);

                            StringBuilder sb = new StringBuilder();
                            sb.append("===== ERROR REPORT (Delayed 10s) =====\n");
                            sb.append("[Location] ").append(location).append("\n");
                            
                            // [추가] 앱 버전 기록
                            sb.append("[App Version] ").append(appVersion).append("\n");

                            sb.append("[Device] ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
                            sb.append("[Memory] ").append(memoryInfo).append("\n\n");
                            sb.append("[Stack Trace]\n").append(fullStackTrace);


                            // --- (기존 로직) 서버 전송 ---
                            URL url = new URL("https://app0.skindx.net/error.php");
                            conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setConnectTimeout(10000);
                            conn.setReadTimeout(10000);
                            conn.setDoOutput(true);
                            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                            String postData = "msg=" + URLEncoder.encode(sb.toString(), "UTF-8");

                            OutputStream os = conn.getOutputStream();
                            os.write(postData.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            os.close();

                            int responseCode = conn.getResponseCode();

                        } catch (Throwable e) {
                            // 전송 실패 시 조용히 무시
                        } finally {
                            if (conn != null) {
                                try { conn.disconnect(); } catch (Exception ignored) {}
                            }
                        }
                    }
                });
            }
        }, 500); // 지연 시간
    }

    // 1. Wrapper 클래스 정의
    public class StringReportException extends Exception {
        public StringReportException(String message) {
            super(message);
        }
    }

    // 2. String 전용 호출 메서드 추가
    private void logreport(String message) {
        // String을 Exception으로 감싸서 기존 메서드에 전달
        logreport(new StringReportException(message));
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArry of arguments for the plugin.
     * @param callbackContext   The callback id used when calling back into JavaScript.
     * @return                  A PluginResult object with a status and message.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;

        this.applicationId = cordova.getContext().getPackageName();
        this.applicationId = preferences.getString("applicationId", this.applicationId);

        if (action.equals(TAKE_PICTURE_ACTION)) {
            this.srcType = CAMERA;
            this.destType = FILE_URI;
            this.saveToPhotoAlbum = false;
            this.targetHeight = 0;
            this.targetWidth = 0;
            this.encodingType = JPEG;
            this.mediaType = PICTURE;
            this.mQuality = 50;

            //Take the values from the arguments if they're not already defined (this is tricky)
            this.destType = args.getInt(1);
            this.srcType = args.getInt(2);
            this.mQuality = args.getInt(0);
            this.targetWidth = args.getInt(3);
            this.targetHeight = args.getInt(4);
            this.encodingType = args.getInt(5);
            this.mediaType = args.getInt(6);
            this.allowEdit = args.getBoolean(7);
            this.correctOrientation = args.getBoolean(8);
            this.saveToPhotoAlbum = args.getBoolean(9);

            // If the user specifies a 0 or smaller width/height
            // make it -1 so later comparisons succeed
            if (this.targetWidth < 1) {
                this.targetWidth = -1;
            }
            if (this.targetHeight < 1) {
                this.targetHeight = -1;
            }

            // We don't return full-quality PNG files. The camera outputs a JPEG
            // so requesting it as a PNG provides no actual benefit
            if (this.targetHeight == -1 && this.targetWidth == -1 && this.mQuality == 100 &&
                    !this.correctOrientation && this.encodingType == PNG && this.srcType == CAMERA) {
                this.encodingType = JPEG;
            }

            try {
                if (this.srcType == CAMERA) {
                    this.callTakePicture(destType, encodingType);
                }
                else if ((this.srcType == PHOTOLIBRARY) || (this.srcType == SAVEDPHOTOALBUM)) {
                    this.getImage(this.srcType, destType);
                }
            }
            catch (IllegalStateException e)
            {
                callbackContext.error(e.getLocalizedMessage());
                PluginResult r = new PluginResult(PluginResult.Status.ERROR);
                callbackContext.sendPluginResult(r);

                logreport(e);

                return true;
            }
            catch (IllegalArgumentException e)
            {
                callbackContext.error("Illegal Argument Exception");
                PluginResult r = new PluginResult(PluginResult.Status.ERROR);
                callbackContext.sendPluginResult(r);

                logreport(e);

                return true;
            }

            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            callbackContext.sendPluginResult(r);

            return true;
        }
        return false;
    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------

    private String getTempDirectoryPath() {
        File cache = cordova.getActivity().getCacheDir();
        // Create the cache directory if it doesn't exist
        cache.mkdirs();
        return cache.getAbsolutePath();
    }

    /**
     * Take a picture with the camera.
     * When an image is captured or the camera view is cancelled, the result is returned
     * in CordovaActivity.onActivityResult, which forwards the result to this.onActivityResult.
     *
     * The image can either be returned as a base64 string or a URI that points to the file.
     * To display base64 string in an img tag, set the source to:
     * img.src="data:image/jpeg;base64,"+result;
     * or to display URI in an img tag
     * img.src=result;
     *
     * @param returnType        Set the type of image to return.
     * @param encodingType           Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
     */
    public void callTakePicture(int returnType, int encodingType) throws IllegalStateException {

        // CB-10120: The CAMERA permission does not need to be requested unless it is declared
        // in AndroidManifest.xml. This plugin does not declare it, but others may and so we must
        // check the package info to determine if the permission is present.
        boolean manifestContainsCameraPermission = false;

        // write permission is not necessary, unless if we are saving to photo album
        // On API 29+ devices, write permission is completely obsolete and not required.
        boolean manifestContainsWriteExternalPermission = false;

        boolean cameraPermissionGranted = PermissionHelper.hasPermission(this, Manifest.permission.CAMERA);
        boolean writeExternalPermissionGranted = false;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            writeExternalPermissionGranted = PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        else {
            writeExternalPermissionGranted = true;
        }

        try {
            PackageManager packageManager = this.cordova.getActivity().getPackageManager();
            String[] permissionsInPackage = packageManager.getPackageInfo(this.cordova.getActivity().getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (permissionsInPackage != null) {
                for (String permission : permissionsInPackage) {
                    if (permission.equals(Manifest.permission.CAMERA)) {
                        manifestContainsCameraPermission = true;
                    }
                    else if (permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        manifestContainsWriteExternalPermission = true;
                    }
                }
            }
        } catch (NameNotFoundException e) {
            // We are requesting the info for our package, so this should
            // never be caught
        }

        ArrayList<String> requiredPermissions = new ArrayList<>();
        if (manifestContainsCameraPermission && !cameraPermissionGranted) {
            requiredPermissions.add(Manifest.permission.CAMERA);
        }

        if (saveToPhotoAlbum && !writeExternalPermissionGranted) {
            // This block only applies for API 24-28
            // because writeExternalPermissionGranted is always true on API 29+
            if (!manifestContainsWriteExternalPermission) {
                throw new IllegalStateException("WRITE_EXTERNAL_STORAGE permission not declared in AndroidManifest");
            }

            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!requiredPermissions.isEmpty()) {
            PermissionHelper.requestPermissions(this, TAKE_PIC_SEC, requiredPermissions.toArray(new String[0]));
        }
        else {
            takePicture(returnType, encodingType);
        }
    }
public void takePicture(int returnType, int encodingType) {

    this.releaseMemory();

    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

    File photo = createCaptureFile(encodingType);

    // ===============================
    // 1) 물리적 파일 생성 확인
    // ===============================
    try {
        if (!photo.exists()) {
            boolean isCreated = photo.createNewFile();
            if (!isCreated) {
                LOG.e(LOG_TAG, "물리적 파일 생성 실패 (Permission or Path issue)");
                this.failPicture("Capture file creation failed.");
                logreport("Capture file creation failed.");
                return;
            }
        }
    } catch (IOException e) {
        LOG.e(LOG_TAG, "파일 생성 중 예외 발생", e);
        this.failPicture("Error creating capture file: " + e.getMessage());
        logreport(e);
        return;
    }

    // ===============================
    // 2) FileProvider URI 생성
    // ===============================
    this.imageUri = FileProvider.getUriForFile(
            cordova.getActivity(),
            cordova.getActivity().getPackageName() + ".cordova.plugin.camera.provider",
            photo
    );

    // ===============================
    // 3) EXTRA_OUTPUT 지정
    // ===============================
    intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);

    // ===============================
    // 4) URI Permission Flags 부여
    // ===============================
    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

    // ⚠️ setFlags()는 addFlags를 덮어쓰므로 제거함
    // intent.setFlags(...) ❌ 사용하지 않음

    // ===============================
    // 5) 삼성 Android 14+ 대응 ClipData
    // ===============================
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        intent.setClipData(
                ClipData.newUri(
                        cordova.getActivity().getContentResolver(),
                        "camera-output",
                        imageUri
                )
        );
    }

    // ===============================
    // 6) 실행 가능한 모든 카메라 앱에 grantUriPermission
    // ===============================
    List<ResolveInfo> resInfoList =
            cordova.getActivity().getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

    for (ResolveInfo resolveInfo : resInfoList) {

        if (resolveInfo.activityInfo == null) continue;

        String packageName = resolveInfo.activityInfo.packageName;

        cordova.getActivity().grantUriPermission(
                packageName,
                imageUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );
    }

    // ===============================
    // 7) Launch camera safely
    // ===============================
    PackageManager pm = cordova.getActivity().getPackageManager();

    if (intent.resolveActivity(pm) != null) {

        // ===============================
        // ✅ 기기 RAM 확인
        // ===============================
        int delayMs = 150;
        try {
            ActivityManager am =
                    (ActivityManager) cordova.getActivity()
                            .getSystemService(Context.ACTIVITY_SERVICE);

            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long totalRamGB = mi.totalMem / (1024L * 1024L * 1024L);

            // ✅ 6GB 이하만 delay 적용
            if (totalRamGB <= 4) {
                delayMs = 500;
            } else if (totalRamGB <= 6) {
                delayMs = 400;
            }
        } catch (Exception ignored) {
            delayMs = 500; // 안전 fallback
        }

        // ===============================
        // ✅ Delay 후 카메라 실행
        // ===============================
        new Handler(Looper.getMainLooper()).postDelayed(() -> {

            try {
                this.cordova.startActivityForResult(
                        (CordovaPlugin) this,
                        intent,
                        (CAMERA + 1) * 16 + returnType + 1
                );

            } catch (Exception e) {
                LOG.e(LOG_TAG, "Camera launch failed", e);
                this.failPicture("Camera launch failed: " + e.getMessage());
                logreport(e);
            }

        }, delayMs);

    } else {

        LOG.d(LOG_TAG, "Error: No default camera app installed.");
        logreport("No default camera app installed.");
        this.failPicture("No default camera app installed.");
    }
}


    /**
     * Create a file in the applications temporary directory based upon the supplied encoding.
     *
     * @param encodingType of the image to be taken
     * @return a File object pointing to the temporary picture
     */
    private File createCaptureFile(int encodingType) {
        return createCaptureFile(encodingType, "");
    }

private File createCaptureFile(int encodingType, String fileName) {
    if (fileName.isEmpty()) {
        fileName = ".Pic";
    }

    if (encodingType == JPEG) {
        fileName = fileName + JPEG_EXTENSION;
    } else if (encodingType == PNG) {
        fileName = fileName + PNG_EXTENSION;
    } else {
        throw new IllegalArgumentException("Invalid Encoding Type: " + encodingType);
    }

    // getTempDirectoryPath()는 이미 cacheDir를 반환함
    File cacheDir = new File(getTempDirectoryPath(), "org.apache.cordova.camera");
    
    // 핵심 수정: mkdir() 대신 mkdirs()를 사용하여 중간 경로까지 모두 생성 시도
    if (!cacheDir.exists()) {
        boolean created = cacheDir.mkdirs(); 
        if (!created) {
            LOG.e(LOG_TAG, "디렉토리 생성 실패: " + cacheDir.getAbsolutePath());
            // 실패 시 fallback으로 기본 캐시 디렉토리 사용
            return new File(getTempDirectoryPath(), fileName);
        }
    }

    return new File(cacheDir, fileName);
}

    /**
     * Get image from photo library.
     *
     * @param srcType           The album to get image from.
     * @param returnType        Set the type of image to return.
     */
    // TODO: Images selected from SDCARD don't display correctly, but from CAMERA ALBUM do!
    // TODO: Images from kitkat filechooser not going into crop function
    public void getImage(int srcType, int returnType) {
        this.releaseMemory();

        Intent intent = new Intent();
        String title = GET_PICTURE;
        croppedUri = null;
        croppedFilePath = null;
        if (this.mediaType == PICTURE) {
            intent.setType("image/*");
            if (this.allowEdit) {
                intent.setAction(Intent.ACTION_PICK);
                intent.putExtra("crop", "true");
                if (targetWidth > 0) {
                    intent.putExtra("outputX", targetWidth);
                }
                if (targetHeight > 0) {
                    intent.putExtra("outputY", targetHeight);
                }
                if (targetHeight > 0 && targetWidth > 0 && targetWidth == targetHeight) {
                    intent.putExtra("aspectX", 1);
                    intent.putExtra("aspectY", 1);
                }
                File croppedFile = createCaptureFile(JPEG);
                croppedFilePath = croppedFile.getAbsolutePath();
                croppedUri = Uri.fromFile(croppedFile);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, croppedUri);
            } else {
                intent.setAction(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
            }
        } else if (this.mediaType == VIDEO) {
            intent.setType("video/*");
            title = GET_VIDEO;
            intent.setAction(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        } else if (this.mediaType == ALLMEDIA) {
            // I wanted to make the type 'image/*, video/*' but this does not work on all versions
            // of android so I had to go with the wildcard search.
            intent.setType("*/*");
            title = GET_All;
            intent.setAction(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }
        if (this.cordova != null) {
            this.cordova.startActivityForResult((CordovaPlugin) this, Intent.createChooser(intent,
                    new String(title)), (srcType + 1) * 16 + returnType + 1);
        }
    }

    /**
     * Brings up the UI to perform crop on passed image URI
     *
     * @param picUri
     */
    private void performCrop(Uri picUri, int destType, Intent cameraIntent) {
        try {
            Intent cropIntent = new Intent("com.android.camera.action.CROP");
            // indicate image type and Uri
            cropIntent.setDataAndType(picUri, "image/*");
            // set crop properties
            cropIntent.putExtra("crop", "true");

            // indicate output X and Y
            if (targetWidth > 0) {
                cropIntent.putExtra("outputX", targetWidth);
            }
            if (targetHeight > 0) {
                cropIntent.putExtra("outputY", targetHeight);
            }
            if (targetHeight > 0 && targetWidth > 0 && targetWidth == targetHeight) {
                cropIntent.putExtra("aspectX", 1);
                cropIntent.putExtra("aspectY", 1);
            }
            // create new file handle to get full resolution crop
            croppedFilePath = createCaptureFile(this.encodingType, System.currentTimeMillis() + "").getAbsolutePath();
            croppedUri = Uri.parse(croppedFilePath);
            cropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cropIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cropIntent.putExtra("output", croppedUri);

            // start the activity - we handle returning in onActivityResult

            if (this.cordova != null) {
                this.cordova.startActivityForResult((CordovaPlugin) this,
                        cropIntent, CROP_CAMERA + destType);
            }
        } catch (ActivityNotFoundException anfe) {
            LOG.e(LOG_TAG, "Crop operation not supported on this device");
            try {
                processResultFromCamera(destType, cameraIntent);
            } catch (IOException e) {
                e.printStackTrace();
                LOG.e(LOG_TAG, "Unable to write to file");
                logreport(e);
            }
            
        }
    }



/**
     * Applies all needed transformation to the image received from the camera.
     *
     * @param destType          In which form should we return the image
     * @param intent            An Intent, which can return result data to the caller.
     */
// [최종 수정] processResultFromCamera (HONOR/SDK 33 레이스 컨디션 해결 버전)
private void processResultFromCamera(int destType, Intent intent) throws IOException {

    if (cordova.getActivity() == null || cordova.getActivity().isFinishing()) {
        LOG.w(LOG_TAG, "Activity is finishing, aborting processResultFromCamera");
        return; 
    }        
    
    // 1. 기본 타겟 설정
    Uri sourceUri = this.imageUri; 

    ContentResolver resolver = cordova.getActivity().getContentResolver();
    boolean sourceExists = false;

    // =========================================================================
    // 1단계 & 2단계 통합: 원본 및 Fallback 파일 존재 확인 (강화된 Retry)
    // =========================================================================
    
    // [보강] 우선 기존 sourceUri에 대해 최대 3.2초간(400ms * 8) 재시도 루프를 돕니다.
    // 많은 제조사 기기에서 파일이 실제로 쓰여지기까지 시간이 걸립니다.
    int retryCount = 0;
    while (retryCount < 8 && !sourceExists) {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(sourceUri, "r")) {
            if (pfd != null && pfd.getStatSize() > 0) {
                sourceExists = true;
                LOG.d(LOG_TAG, "Source file found on attempt " + (retryCount + 1));
            }
        } catch (Exception e) {
            // 아직 파일이 없거나 접근 불가
        }

        if (!sourceExists) {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            retryCount++;
        }
    }

    // [보강] 만약 원본 Uri로 실패했다면, Intent Data(Fallback)를 확인하고 재검증합니다.
    if (!sourceExists) {
        LOG.w(LOG_TAG, "Original sourceUri failed. Checking intent fallback...");
        
        Uri fallbackUri = (intent != null) ? intent.getData() : null;
        
        if (fallbackUri != null) {
            int fallbackRetry = 0;
            while (fallbackRetry < 3 && !sourceExists) { // Fallback에 대해서도 짧게 재시도
                try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(fallbackUri, "r")) {
                    if (pfd != null && pfd.getStatSize() > 0) {
                        sourceUri = fallbackUri; // 성공 시 sourceUri 교체
                        sourceExists = true;
                        LOG.d(LOG_TAG, "Recovered using Fallback URI.");
                    }
                } catch (Exception e) {
                    // 실패 시 대기
                }
                
                if (!sourceExists) {
                    try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                    fallbackRetry++;
                }
            }
        }
    }


    // =========================================================================
    // [보강] 3단계 (최후의 수단): 캐시 폴더 강제 스캔 및 EXIF 2차 검증 (Heuristic Fallback)
    // =========================================================================
    if (!sourceExists) {
        LOG.w(LOG_TAG, "Intent fallback also failed. Scanning cache directory as last resort...");
        
        File cameraCacheDir = new File(cordova.getActivity().getCacheDir(), "org.apache.cordova.camera");
        File rootCacheDir = cordova.getActivity().getCacheDir();
        File[] searchDirs = { cameraCacheDir, rootCacheDir };
        
        long now = System.currentTimeMillis();
        File bestCandidate = null;
        long bestTime = 0;

        // 1. 파일 시스템 기반 1차 필터링 (빠른 탐색)
        for (File dir : searchDirs) {
            if (dir != null && dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.isFile()) continue;
                        
                        // OS 파일 수정 시간이 20초(20000ms) 이내인지 1차 확인
                        long fileAge = now - f.lastModified();
                        if (fileAge >= 0 && fileAge < 20000) {
                            
                            // Bounds만 읽어 해상도 2000x2000 이상인지 확인 (OOM 방지)
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inJustDecodeBounds = true;
                            BitmapFactory.decodeFile(f.getAbsolutePath(), options);
                            
                            if (options.outWidth >= 2000 || options.outHeight >= 2000) {
                                if (f.lastModified() > bestTime) {
                                    bestTime = f.lastModified();
                                    bestCandidate = f;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // 2. 찾아낸 단 1개의 파일에 대해 EXIF 촬영 시간 2차 검증
        if (bestCandidate != null) {
            boolean isExifValid = false;
            try {
                ExifInterface exif = new ExifInterface(bestCandidate.getAbsolutePath());
                // 원본 촬영 시간 (우선순위 1)
                String dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                // 수정 시간 (우선순위 2 - Original이 없는 기기 대비)
                if (dateTime == null) {
                    dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME);
                }
                
                if (dateTime != null) {
                    // EXIF 시간 포맷: "yyyy:MM:dd HH:mm:ss"
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US);
                    java.util.Date exifDate = sdf.parse(dateTime);
                    
                    if (exifDate != null) {
                        long diff = Math.abs(now - exifDate.getTime());
                        if (diff <= 20000) { // EXIF 기준 20초 이내 확인
                            isExifValid = true;
                            LOG.d(LOG_TAG, "EXIF datetime verified successfully. Diff: " + diff + "ms");
                        } else {
                            LOG.w(LOG_TAG, "EXIF time is too old. Diff: " + diff + "ms. Rejecting file.");
                        }
                    }
                } else {
                    // EXIF 데이터가 아예 없는 경우 (일부 커스텀 롬 카메라 앱)
                    // 파일 시스템 시간과 해상도 조건을 통과했으므로 유효한 것으로 간주
                    LOG.w(LOG_TAG, "No EXIF datetime found, but trusting file system time.");
                    isExifValid = true; 
                }
            } catch (Exception e) {
                LOG.e(LOG_TAG, "Failed to parse EXIF for fallback candidate", e);
                // 파싱 에러 시에도 최후의 수단이므로 일단 신뢰하여 진행
                isExifValid = true; 
            }

            // 3. 모든 검증을 통과했다면 sourceUri 부활!
            if (isExifValid) {
                LOG.w(LOG_TAG, "BINGO! Found and verified matching captured file: " + bestCandidate.getAbsolutePath());
                sourceUri = FileProvider.getUriForFile(
                        cordova.getActivity(),
                        cordova.getActivity().getPackageName() + ".cordova.plugin.camera.provider",
                        bestCandidate
                );
                sourceExists = true;
            } else {
                bestCandidate = null; // 조건 미달 시 후보 탈락
            }
        }
    }

    // -------------------------------------------------------------------------
    // 진짜 최종적으로 실패한 경우 (기존 코드)
    // -------------------------------------------------------------------------
    if (!sourceExists) {
        LOG.e(LOG_TAG, "All attempts to find the captured file failed.");
        logreport("Unable to find the captured file. (Cache scan & EXIF verify failed)");
        this.failPicture("Unable to find the captured file.");
        return;
    }

    // 최종적으로 실패한 경우
    if (!sourceExists) {
        LOG.e(LOG_TAG, "All attempts to find the captured file failed.");
        logreport("Unable to find the captured file. (HONOR/SDK33 Patch applied)");
        this.failPicture("Unable to find the captured file.");
        return;
    }    

    // =========================================================================
    // 3단계: Exif & Rotation 준비
    // =========================================================================
    int rotate = 0;
    ExifHelper exif = new ExifHelper();
    
    if (this.encodingType == JPEG) {
        try (InputStream exifInput = resolver.openInputStream(sourceUri)) {
            if (exifInput != null) {
                exif.createInFile(exifInput);
                exif.readExifData();
                rotate = exif.getOrientation();
            }
        } catch (Exception e) {
            LOG.w(LOG_TAG, "Failed to read Exif: " + e.getMessage());
            rotate = 0;
        }
    }

    Bitmap bitmap = null;
    Uri galleryUri = null;

    // =========================================================================
    // 4단계: 비트맵 로딩
    // =========================================================================
    try { Thread.sleep(300); } catch (InterruptedException e) {}

    try {
        bitmap = GetScaledAndRotatedBitmapFromUri(sourceUri);
    } catch (Exception e) {
        LOG.e(LOG_TAG, "Failed to decode bitmap: " + e.getMessage());
        bitmap = null;
    }

    // Retry Logic
    if (bitmap == null) {
        try {
            LOG.d(LOG_TAG, "Retry #1 decoding...");
            Thread.sleep(500); 
            bitmap = GetScaledAndRotatedBitmapFromUri(sourceUri);
        } catch (Exception e) {
            LOG.e(LOG_TAG, "Failed to decode bitmap: " + e.getMessage());
            bitmap = null;
        }
    }
    if (bitmap == null) {
        try {
            LOG.d(LOG_TAG, "Retry #2 decoding...");
            Thread.sleep(1000); 
            bitmap = GetScaledAndRotatedBitmapFromUri(sourceUri);
        } catch (Exception e) {
            this.failPicture("Error processing image: " + e.getMessage());
            logreport(e);
            return;
        }
    }

    if (bitmap == null) {
        logreport("Unable to create bitmap!");
        this.failPicture("Unable to create bitmap!");
        return;
    }
    
    // =========================================================================
    // 5단계: 결과 저장 및 반환
    // =========================================================================
    if (destType == DATA_URL) {
        this.processPicture(bitmap, this.encodingType);
    }
    else if (destType == FILE_URI) {
        
        

        // =======================================
        // [SAFE] Bitmap 저장 + Fallback + Exif 처리
        // =======================================

        // 1) 기본 파일 생성
        File photoFile = createCaptureFile(this.encodingType, System.currentTimeMillis() + "");
        OutputStream os = null;

        try {
            // -------------------------------------------------
            // 2) 1차 시도: FileOutputStream (정석)
            // -------------------------------------------------
            try {
                os = new FileOutputStream(photoFile);
                LOG.d(LOG_TAG, "Bitmap output stream opened: " + photoFile.getAbsolutePath());
            } catch (Exception e) {
                LOG.w(LOG_TAG, "1차 저장 실패(FileOutputStream): " + e.getMessage());
                os = null;
            }

            // -------------------------------------------------
            // 3) 2차 fallback: 내부 cacheDir로 우회
            // -------------------------------------------------
            if (os == null) {

                LOG.w(LOG_TAG, "OutputStream is null. Falling back to internal cache.");

                File cacheDir = cordova.getActivity().getCacheDir();

                String ext = (this.encodingType == JPEG) ? ".jpg" : ".png";
                String fallbackName = "fallback_" + System.currentTimeMillis() + ext;

                File fallbackFile = new File(cacheDir, fallbackName);

                os = new FileOutputStream(fallbackFile);

                // fallback 성공 시 파일 교체
                photoFile = fallbackFile;

                LOG.d(LOG_TAG, "Fallback stream opened successfully: " + fallbackFile.getAbsolutePath());
            }

            // -------------------------------------------------
            // 4) Bitmap compress 저장
            // -------------------------------------------------
            CompressFormat compressFormat =
                    getCompressFormatForEncodingType(this.encodingType);

            boolean ok = bitmap.compress(compressFormat, this.mQuality, os);

            if (!ok) {
                LOG.e(LOG_TAG, "Bitmap compress returned false.");
                this.failPicture("Bitmap compress failed.");
                return;
            }

        } catch (Exception e) {

            LOG.e(LOG_TAG, "Bitmap 저장 중 예외 발생", e);
            logreport(e);
            this.failPicture("Failed to save bitmap: " + e.getMessage());
            return;

        } finally {

            // -------------------------------------------------
            // 5) Stream close 안전 처리
            // -------------------------------------------------
            try {
                if (os != null) os.close();
            } catch (Exception ignore) {
            }
        }

        // -------------------------------------------------
        // 6) Exif 복원 (JPEG만)
        // -------------------------------------------------
        if (this.encodingType == JPEG) {

            try {
                String exifPath = photoFile.getAbsolutePath();

                if (rotate != ExifInterface.ORIENTATION_NORMAL) {
                    exif.resetOrientation();
                }

                exif.createOutFile(exifPath);
                exif.writeExifData();

                LOG.d(LOG_TAG, "Exif restored successfully: " + exifPath);

            } catch (Exception e) {
                LOG.w(LOG_TAG, "Exif restore failed: " + e.getMessage());
            }
        }

        // -------------------------------------------------
        // 7) 최종 반환 URI는 FileProvider로 (Android 10+ 안전)
        // -------------------------------------------------

        Uri finalFileUri = FileProvider.getUriForFile(
                cordova.getActivity(),
                cordova.getActivity().getPackageName() + ".cordova.plugin.camera.provider",
                photoFile
        );

        this.callbackContext.success(finalFileUri.toString());
        
        
    }

    // =========================================================================
    // 6단계: Cleanup
    // =========================================================================
    // [지적 3 반영] bitmap.recycle() 제거 (GC에 위임)
    // [지적 5 반영] cleanup에 bitmap 전달하지 않음 (null 전달)
    
    // 원본 임시 파일만 정리 시도
    this.cleanup(this.imageUri, galleryUri, null);
}  
    



    private CompressFormat getCompressFormatForEncodingType(int encodingType) {
        return encodingType == JPEG ? CompressFormat.JPEG : CompressFormat.PNG;
    }



    /**
     * Converts output image format int value to string value of mime type.
     * @return String String value of mime type or empty string if mime type is not supported
     */
    private String getMimetypeForEncodingType() {
        if (encodingType == PNG) return PNG_MIME_TYPE;
        if (encodingType == JPEG) return JPEG_MIME_TYPE;
        return "";
    }

private String outputModifiedBitmap(Bitmap bitmap, Uri uri, String mimeTypeOfOriginalFile)
        throws IOException {

    // 1. 경로 계산
    String realPath = FileHelper.getRealPath(uri, this.cordova);
    String fileName = calculateModifiedBitmapOutputFileName(mimeTypeOfOriginalFile, realPath);

    File cacheDir = new File(getTempDirectoryPath());

    // 2. Temp 디렉토리 보장 (mkdirs 실패 체크 포함)
    if (!cacheDir.exists()) {
        boolean created = cacheDir.mkdirs();
        if (!created) {
            throw new IOException("Failed to create temp directory: " + cacheDir.getAbsolutePath());
        }
    }

    File destFile = new File(cacheDir, fileName);
    String modifiedPath = destFile.getAbsolutePath();

    CompressFormat compressFormat =
            getCompressFormatForEncodingType(this.encodingType);

    // 3. Bitmap 저장 (try-with-resources로 완전 안전)
    try (OutputStream os =
                 new BufferedOutputStream(new FileOutputStream(destFile))) {

        boolean success = bitmap.compress(compressFormat, this.mQuality, os);

        // flush는 close 전에 명시적으로 수행
        os.flush();

        if (!success) {
            throw new IOException("Bitmap.compress() returned false: " + modifiedPath);
        }

    } catch (IOException e) {

        // 실패 시 찌꺼기 파일 삭제 시도
        if (destFile.exists()) {
            boolean deleted = destFile.delete();
            if (!deleted) {
                LOG.w(LOG_TAG, "Failed to delete broken output file: " + modifiedPath);
            }
        }

        throw e; // 상위 Retry/Fallback 로직으로 전달
    }

    // 4. 파일 크기 sanity check (compress 성공해도 0 byte 방지)
    long fileSize = destFile.length();
    if (fileSize < 100) { // 최소 크기 기준 (너무 작으면 비정상)
        boolean deleted = destFile.delete();
        LOG.w(LOG_TAG,
                "Compressed file too small (" + fileSize + " bytes). Deleted=" + deleted);

        throw new IOException("Output file is invalid (too small): " + modifiedPath);
    }

    // 5. Exif 복사 (파일이 정상 생성된 경우에만 수행)
    if (exifData != null && this.encodingType == JPEG) {
        try {
            if (this.correctOrientation && this.orientationCorrected) {
                exifData.resetOrientation();
            }

            exifData.createOutFile(modifiedPath);
            exifData.writeExifData();

        } catch (IOException exifError) {

            // Exif 실패는 치명적이지 않으므로 경고만 남김
            LOG.w(LOG_TAG,
                    "Failed to preserve Exif metadata: " + exifError.getMessage());

        } finally {
            // 상황에 따라 메모리 정리 가능
            // exifData = null;
        }
    }

    return modifiedPath;
}



    private String calculateModifiedBitmapOutputFileName(String mimeTypeOfOriginalFile, String realPath) {
        if (realPath == null) {
            return "modified" + getExtensionForEncodingType();
        }
        String fileName = realPath.substring(realPath.lastIndexOf('/') + 1);
        if (getMimetypeForEncodingType().equals(mimeTypeOfOriginalFile)) {
            return fileName;
        }
        // if the picture is not a jpeg or png, (a .heic for example) when processed to a bitmap
        // the file extension is changed to the output format, f.e. an input file my_photo.heic could become my_photo.jpg
        return fileName.substring(fileName.lastIndexOf(".") + 1) + getExtensionForEncodingType();
    }

    private String getExtensionForEncodingType() {
        return this.encodingType == JPEG ? JPEG_EXTENSION : PNG_EXTENSION;
    }

 
    /**
     * Applies all needed transformation to the image received from the gallery.
     *
     * @param destType In which form should we return the image
     * @param intent   An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */

private void safeSleep(int ms) {
    try {
        Thread.sleep(ms);
    } catch (InterruptedException ignored) {}
}
     
private void processResultFromGallery(int destType, Intent intent) {
    // 1. ThreadPool을 사용해 즉시 백그라운드 작업으로 넘깁니다.
    cordova.getThreadPool().execute(() -> {
        this.releaseMemory();
        safeSleep(300); 
        try {
            processResultFromGallery_ex(destType, intent);
        } catch (Throwable t) {
            // Throwable로 OOM까지 완벽히 방어합니다.
            LOG.e(LOG_TAG, "Final failure", t);

            Activity act = cordova.getActivity();
            if (act != null && !act.isFinishing()) {
                act.runOnUiThread(() ->
                   CameraLauncher.this.failPicture("Error processing gallery image: " + t.getLocalizedMessage())
                );
            }
            logreport(t);
        }
    });
}

private boolean waitForFileStable(File f, long timeoutMs) {
    long start = System.currentTimeMillis();
    long lastSize = -1;

    while (System.currentTimeMillis() - start < timeoutMs) {
        long size = f.length();
        if (size > 0 && size == lastSize) return true;
        lastSize = size;
        try { Thread.sleep(100); } catch (Exception ignored) {}
    }
    return false;
}





// 내부 클래스 (변경 없음)
private static class CopyResult {
    Uri uri;
    String mime;
}

/**
 * Google Photos 등의 원격 이미지를 로컬 캐시로 안전하게 복사
 */
private CopyResult safeCopyGooglePhotosToCache(ContentResolver resolver, Uri uri, File cacheDir) throws IOException {

    CopyResult result = new CopyResult();

    // safeCopy 내부 1번 항목 수정 제안
    String mime = resolver.getType(uri);
    if (mime == null || mime.isEmpty() || mime.equals("*/*")) {
        mime = FileHelper.getMimeType(uri.toString(), this.cordova); // 기존 헬퍼 활용
    }
    if (mime == null) mime = "image/jpeg"; // 최종 fallback


    // 2. 확장자 결정
    String ext = ".jpg";
    try {
        String dExt = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (dExt != null) ext = "." + dExt;
    } catch (Exception e) {
        // MimeTypeMap 에러 시 기본 .jpg 유지
    }

    File tempFile = new File(cacheDir, "gp_" + System.currentTimeMillis() + "_cached" + ext);

    try (InputStream in = resolver.openInputStream(uri);
         FileOutputStream out = new FileOutputStream(tempFile)) {

        if (in == null) throw new IOException("InputStream is null");

        byte[] buf = new byte[8192];
        int len;
        
        long total = 0;
        long maxSize = 50 * 1024 * 1024;

        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
            total += len;

            if (total > maxSize) {
                throw new IOException("File too large or stream stuck");
            }
        }
        
        
        out.flush();
    } catch (IOException e) {
        // 복사 중 에러 발생 시 불완전한 파일 삭제
        if (tempFile.exists()) tempFile.delete();
        throw e;
    }

    // 파일 사이즈 검증 (100바이트 미만 등 비정상 파일 필터링)
    if (tempFile.length() < 100) { 
        tempFile.delete(); 
        throw new IOException("File transfer incomplete (too small)");
    }

    Activity act = cordova.getActivity();
    if (act == null || act.isFinishing()) {
        if (tempFile.exists()) tempFile.delete();
        throw new IOException("Activity lost during copy");
    }

    // 권한 확인 (Authority 매칭 확인 필수)
    String authority = act.getPackageName() + ".cordova.plugin.camera.provider";
    result.uri = FileProvider.getUriForFile(act, authority, tempFile);
    result.mime = mime;

    return result;
}


private void processResultFromGallery_ex(int destType, Intent intent) {

    // 1. 유효성 검사
    if (cordova.getActivity() == null || cordova.getActivity().isFinishing()) return;

    Uri uri = intent.getData();
    if (uri == null && intent.getClipData() != null) {
        uri = intent.getClipData().getItemAt(0).getUri();
    }
    if (uri == null) {
        logreport("No Uri returned from gallery");
       this.failPicture("No Uri returned from gallery");
        return;
    }


    String uriString = uri.toString();
    Uri originalUri = uri;
    ContentResolver resolver = cordova.getActivity().getContentResolver();

    if (uri != null && "content".equals(uri.getScheme())) {
        try {
            // 안드로이드 시스템에 이 URI의 권한을 유지해달라고 요청 (SDK 36 대응)
            int takeFlags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION);
            resolver.takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException e) {
            LOG.w(LOG_TAG, "Persistable permission not granted (normal for some pickers)");
        }
    }

    // 2. MIME Type 확인 (한 번만 수행하여 계속 재사용)
    String mimeType = resolver.getType(uri);
    if (mimeType == null) mimeType = FileHelper.getMimeType(uriString, this.cordova);

    // 3. 복사 필요 여부 확인
    File cacheDir = cordova.getActivity().getCacheDir();
    String scheme = uri.getScheme();


    boolean isGooglePhotos =
            uri.getAuthority() != null &&
            uri.getAuthority().startsWith("com.google.android.apps.photos");

    Uri cachedUri = null;
    if (isGooglePhotos) {
        LOG.w(LOG_TAG, "Google Photos URI detected → using SAFE copy to cache");

        try {
            CopyResult copy = safeCopyGooglePhotosToCache(resolver, uri, cacheDir);

            cachedUri = copy.uri;
            uri = cachedUri;
        
            mimeType = copy.mime;
            uriString = uri.toString();
            scheme = uri.getScheme();
            
            // ✅ 이제 Google Photos 아님
            isGooglePhotos = false;


        } catch (Exception e) {
            LOG.e(LOG_TAG, "Google Photos SAFE copy failed → fallback original", e);
            logreport(e);

            // fallback
            uri = originalUri;
            uriString = originalUri.toString();
        }
    }


    boolean isInternal = "file".equalsIgnoreCase(scheme) && uri.getPath() != null && uri.getPath().startsWith(cacheDir.getAbsolutePath());
    //boolean needCopy = !isInternal; // content://, http://, 외부 file:// 모두 복사 대상
    boolean isMediaStore =
            uriString.startsWith("content://media/");
    boolean needCopy = !isInternal
            && !isGooglePhotos;
    //        && !isMediaStore;
    if (uri.getAuthority() != null &&
        uri.getAuthority().equals(cordova.getActivity().getPackageName() + ".cordova.plugin.camera.provider")) {
        needCopy = false;
    }
    

    if (needCopy) {
        LOG.d(LOG_TAG, "Copying external resource to local cache...");

        String ext = ".jpg";
        if (mimeType != null) {
            String dExt = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (dExt != null) ext = "." + dExt;
        }

        File tempFile = new File(cacheDir, System.currentTimeMillis() + "_cached" + ext);

        try {
            // [Standard Copy] 블로킹 I/O (완료될 때까지 대기함)
            copyUriToFile(resolver, uri, tempFile);

            // 복사 성공 시 URI 교체
            uri = FileProvider.getUriForFile(cordova.getActivity(), cordova.getActivity().getPackageName() + ".cordova.plugin.camera.provider", tempFile);
            uriString = uri.toString();

            String newMime = resolver.getType(uri);

            if (newMime == null) {
                newMime = URLConnection.guessContentTypeFromName(tempFile.getName());
            }
            if (newMime != null) mimeType = newMime;
            
        
        } catch (Exception e) {
            // [Log Check] 복사 실패는 치명적이지 않음(Fallback함) -> 그래도 로그는 남김
            LOG.w(LOG_TAG, "Copy failed (" + e.getMessage() + "), using original URI.");
            logreport(e); // 에러 리포트 전송
            
            uri = originalUri;
            uriString = originalUri.toString();
        }
    }

    // =========================================================================
    // [강화된 HEIC 판별 로직]
    // =========================================================================
    boolean isHeic = false;

    // 1. MIME Type을 통한 1차 판별 (가장 빠름)
    if (mimeType != null) {
        String m = mimeType.toLowerCase();
        isHeic = m.contains("heic") || m.contains("heif");
    }

    // 2. 파일 확장자를 통한 2차 판별 (MIME이 null이거나 일반 octet-stream일 경우)
    if (!isHeic) {
        String fileName = getFileNameFromUri(uri);
        if (fileName != null) {
            String lowerName = fileName.toLowerCase();
            isHeic = lowerName.endsWith(".heic") || lowerName.endsWith(".heif") || lowerName.endsWith(".hif");
        }
    }

    // 3. 파일 시그니처(Magic Bytes)를 통한 3차 판별 (가장 확실함)
    // 최신 삼성 기기에서 MIME이나 확장자가 불분명하게 넘어올 때를 대비
    if (!isHeic) {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is != null) {
                byte[] header = new byte[12];
                if (is.read(header) >= 12) {
                    String signature = new String(header, 4, 8, StandardCharsets.UTF_8);
                    // HEIC/HEIF 파일은 4~12바이트 지점에 ftypheic, ftypheix, ftypmif1 등을 포함함
                    if (signature.contains("ftypheic") || signature.contains("ftypheix") || 
                        signature.contains("ftypmif1") || signature.contains("ftypmsf1") ||
                        signature.contains("ftyphevc") || signature.contains("ftyphevx")) {
                        isHeic = true;
                        LOG.d(LOG_TAG, "HEIC detected by file signature (Magic Bytes)");
                    }
                }
            }
        } catch (Exception e) {
            LOG.w(LOG_TAG, "Failed to check file signature for HEIC", e);
        }
    }

    if (isHeic) {
        // 원래 해상도 저장
        final int origW = this.targetWidth;
        final int origH = this.targetHeight;

        try {
            try {
                // 1차 변환 시도
                String jpegPath = convertHeicToJpeg(uri);
                safeSuccess(jpegPath);
                return;

            } catch (Throwable firstError) { // Exception과 Error(OOM 포함) 모두 캐치
                LOG.w(LOG_TAG, "HEIC 1st convert failed (OOM or Exception) → retry with 900x900", firstError);

                // 메모리 정리 + delay (OOM 발생 직후이므로 매우 중요)
                this.releaseMemory();
                safeSleep(500);

                // retry용 해상도 제한
                this.targetWidth  = Math.min(origW, 900);
                this.targetHeight = Math.min(origH, 900);

                try {
                    // 2차 변환 시도
                    String jpegPath = convertHeicToJpeg(uri);
                    safeSuccess(jpegPath);
                    return;

                } catch (Throwable secondError) {
                    LOG.e(LOG_TAG, "HEIC convert retry failed again", secondError);

                    String errorMsg = (secondError instanceof OutOfMemoryError) 
                                      ? "Memory limit exceeded during HEIC conversion" 
                                      : secondError.getMessage();
                    
                   this.failPicture("Error converting HEIC: " + errorMsg);
                    logreport(secondError);
                    return;
                }
            }
        } finally {
            // ⭐ 반드시 원복
            this.targetWidth = origW;
            this.targetHeight = origH;
        }
    }    
    
    // 0. 변환 불필요 시 빠른 리턴 (Exif 읽기 전에 수행하여 성능 최적화)
    if (this.targetHeight == -1 && this.targetWidth == -1 &&
        destType == FILE_URI && !this.correctOrientation &&
        getMimetypeForEncodingType().equalsIgnoreCase(mimeType)) {
        safeSuccess(uriString);
        return;
    }

    // Exif 읽기
    if (!isGooglePhotos && this.encodingType == JPEG && this.exifData == null) {
        try (InputStream exifInput = resolver.openInputStream(uri)) {
            if (exifInput != null) {
                this.exifData = new ExifHelper();
                this.exifData.createInFile(exifInput); 
                this.exifData.readExifData();
            }
        } catch (Exception e) {
            LOG.w(LOG_TAG, "Failed to read Exif data", e);
            // Exif 실패는 logreport까지 할 필요는 없음 (선택사항)
        }
    }

    Bitmap bitmap = null;

    /** 1차 시도 (현재 uri: cachedUri or normal uri) */
    try {
        bitmap = GetScaledAndRotatedBitmapFromUri(uri);
    } catch (Exception e) {
        LOG.w(LOG_TAG, "Decode failed (1st)", e);
    }

    /** ✅ cachedUri였다면 → originalUri로 fallback */
    if (bitmap == null && cachedUri != null) {

        LOG.w(LOG_TAG, "Cache decode failed → retry original URI");

        uri = originalUri;
        uriString = uri.toString();

        try {
            bitmap = GetScaledAndRotatedBitmapFromUri(uri);
        } catch (Exception e) {
            LOG.e(LOG_TAG, "Original decode retry failed", e);
        }
    }

    /** 마지막 실패 처리 */
    if (bitmap == null) {
        logreport("Unable to create bitmap!");
       this.failPicture("Unable to create bitmap!");
        return;
    }


    // 결과 처리
    if (destType == DATA_URL) {
        this.processPicture(bitmap, this.encodingType);
    } else if (destType == FILE_URI) {
        try {
            // [1차 시도]
            String modifiedPath = this.outputModifiedBitmap(bitmap, uri, mimeType);
            //this.callbackContext.success("file://" + modifiedPath + "?" + System.currentTimeMillis());
            safeSuccess("file://" + modifiedPath + "?" + System.currentTimeMillis());

        } catch (Exception e) {
            // [1차 실패] -> 300ms 대기 후 딱 한 번만 재시도
            try {
                safeSleep(300);
                // [2차 시도]
                String modifiedPath = this.outputModifiedBitmap(bitmap, uri, mimeType);
                safeSuccess("file://" + modifiedPath + "?" + System.currentTimeMillis());

            } catch (Exception finalEx) {
                // [2차 실패] -> 이제 진짜 에러 처리
                logreport(finalEx); 
                this.failPicture("Error retrieving image: " + finalEx.getLocalizedMessage());
            }
        }        
        
    }

    if (bitmap != null) {
        bitmap.recycle();
    }
        
}




/*
private void safeSuccess(final String msg) {

    final CallbackContext cb = callbackContext;
    if (cb == null) return;
    if (cb.isFinished()) return;

    Activity act = cordova.getActivity();
    if (act != null) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.success(msg);
                } catch (Throwable ignore) {}
            }
        });
    } else {
        try {
            cb.success(msg);
        } catch (Throwable ignore) {}
    }
}
*/

private void safeSuccess(final String msg) {
if (this.callbackContext!=null)
this.callbackContext.success(msg);
}



/**
 * 캐시 디렉토리 내의 오래된 gp_ 임시 파일들을 삭제합니다.
 */
private void cleanupOldGooglePhotosQuirkFiles() {
    try {
        File cacheDir = this.cordova.getActivity().getCacheDir();
        File[] files = cacheDir.listFiles();
        if (files == null) return;

        long now = System.currentTimeMillis();
        // 생성된 지 24시간 이상 된 파일은 삭제 (안전한 처리를 위해)
        long expirationThreshold = 24 * 60 * 60 * 1000; 

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("gp_") && name.contains("_cached")) {
                // 1. 오래된 파일이거나 2. 현재 작업 중이 아닐 것으로 판단되는 파일 삭제
                if ((now - file.lastModified()) > expirationThreshold) {
                    if (file.delete()) {
                        LOG.d(LOG_TAG, "Deleted old gp cache file: " + name);
                    }
                }
            }
        }
    } catch (Exception e) {
        LOG.w(LOG_TAG, "Error cleaning up gp cache files", e);
    }
}







// [Helper] 표준 파일 복사 함수
private void copyUriToFile(ContentResolver resolver, Uri srcUri, File dstFile) throws IOException {
    try (InputStream in = resolver.openInputStream(srcUri);
         OutputStream out = new FileOutputStream(dstFile)) {
        
        if (in == null) throw new IOException("Source stream is null");

        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        out.flush();
    }
}

private String getFileNameFromUri(Uri uri) {

    if (uri == null) return null;

    ContentResolver resolver = cordova.getActivity().getContentResolver();

    // =========================================================
    // 1) content:// URI → query DISPLAY_NAME
    // =========================================================
    if ("content".equalsIgnoreCase(uri.getScheme())) {

        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index != -1) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }

        } catch (Exception e) {
            LOG.w(LOG_TAG, "getFileNameFromUri query failed: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // =========================================================
    // 2) file:// URI → path 기반 추출
    // =========================================================
    if ("file".equalsIgnoreCase(uri.getScheme())) {
        String path = uri.getPath();
        if (path != null) {
            return new File(path).getName();
        }
    }

    // =========================================================
    // 3) 마지막 fallback → URI string에서 추출
    // =========================================================
    try {
        String last = uri.getLastPathSegment();
        if (last != null && last.contains(".")) {
            return last;
        }
    } catch (Exception ignored) {}

    return null;
}





private boolean waitForUriReady(ContentResolver resolver, Uri uri) {

    long start = System.currentTimeMillis();
    long timeoutMs = 4000;

    long lastSize = -1;
    int stableCount = 0;

    // ✅ FileNotFound 연속 발생 감지
    int fileNotFoundCount = 0;
    final int MAX_NOTFOUND = 2; // 2번이면 바로 종료

    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inJustDecodeBounds = true;

    while (System.currentTimeMillis() - start < timeoutMs) {

        try (ParcelFileDescriptor pfd =
                     resolver.openFileDescriptor(uri, "r")) {

            // 성공했으면 FileNotFound 카운트 reset
            fileNotFoundCount = 0;

            if (pfd == null) {
                continue;
            }

            long currentSize = pfd.getStatSize();

            if (currentSize <= 0) {
                continue;
            }

            // ===============================
            // 1) 파일 크기 stable 2회 연속 확인
            // ===============================
            if (currentSize == lastSize) {
                stableCount++;
            } else {
                stableCount = 0;
            }

            lastSize = currentSize;

            if (stableCount >= 2) {

                FileDescriptor fd = pfd.getFileDescriptor();

                // opts reset
                opts.outWidth = 0;
                opts.outHeight = 0;

                BitmapFactory.decodeFileDescriptor(fd, null, opts);

                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    return true; // ✅ Ready
                }
            }

        }
        catch (FileNotFoundException fnf) {

            fileNotFoundCount++;

            LOG.w(LOG_TAG,
                    "waitForUriReady FileNotFound (" +
                    fileNotFoundCount + "): " + uri);

            /*
            // ✅ 권한 문제는 기다려도 소용없음 → 빠른 종료
            if (fileNotFoundCount >= MAX_NOTFOUND) {
                LOG.e(LOG_TAG,
                        "URI not accessible, abort early: " + uri);
                return false;
            }
            */
        }
        catch (Exception ignored) {
            // 생성 중 lock 상태 등 → 계속 재시도
        }

        // ===============================
        // 짧게 sleep
        // ===============================
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {}
    }

    LOG.w(LOG_TAG, "waitForUriReady timeout: " + uri);
    return false;
}



private Bitmap GetScaledAndRotatedBitmapFromUri(Uri uri) throws IOException {
    ContentResolver resolver = this.cordova.getActivity().getContentResolver();

    // ===============================
    // 0) 파일 준비 대기 (race 방지)
    // ===============================
    waitForUriReady(resolver, uri);
    safeSleep(300);

    // ===============================
    // 1) bounds 읽기
    // ===============================
    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inJustDecodeBounds = true;

    try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
        if (pfd == null) {
            // 수정: pfd가 null인 경우 구체적 메시지 전달
            throw new IOException("Failed to open file descriptor (pfd is null) for URI: " + uri);
        }

        BitmapFactory.decodeFileDescriptor(
                pfd.getFileDescriptor(),
                null,
                opts
        );
    }

    if (opts.outWidth <= 0 || opts.outHeight <= 0) {
        // 수정: 이미지 크기를 읽지 못한 경우 (파일 손상 등)
        throw new IOException("Failed to read image bounds. The file might not be an image or is corrupted. URI: " + uri);
    }

    // ===============================
    // 2) EXIF orientation 읽기
    // ===============================
    int rotate = 0;
    if (this.correctOrientation) {
        try (InputStream exifStream = resolver.openInputStream(uri)) {
            if (exifStream != null) {
                ExifInterface exif = new ExifInterface(exifStream);
                int orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                );

                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
            }
        } catch (Exception e) {
            // EXIF 실패는 치명적이지 않으므로 로그만 남기고 진행
            LOG.w(LOG_TAG, "Error reading EXIF data: " + e.getMessage());
        }
    }

    boolean willRotate = (rotate != 0);

    // ===============================
    // 3) sampleSize 계산
    // ===============================
    if (this.targetWidth > 0 && this.targetHeight > 0) {
        opts.inSampleSize = calculateSampleSizeSmart(
                this.cordova.getActivity(),
                opts.outWidth,
                opts.outHeight,
                this.targetWidth,
                this.targetHeight,
                willRotate
        );
    }

    opts.inJustDecodeBounds = false;
    opts.inPreferredConfig = Bitmap.Config.RGB_565;
    opts.inMutable = false;
    opts.inDither = true;

    // ===============================
    // 4) decode + retry (희귀 null 방지)
    // ===============================
    Bitmap bitmap = null;
    String lastExceptionMessage = "";

    for (int attempt = 0; attempt < 3; attempt++) {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) continue;

            boolean oomHappened = false;
            try {
                bitmap = BitmapFactory.decodeFileDescriptor(
                        pfd.getFileDescriptor(),
                        null,
                        opts
                );
            } catch (OutOfMemoryError oom) {
                lastExceptionMessage = "OutOfMemoryError at attempt " + (attempt + 1);
                opts.inSampleSize *= 2;
                oomHappened = true;
            }
            if (oomHappened) continue;
        } catch (IOException e) {
            lastExceptionMessage = e.getMessage();
        }

        if (bitmap != null) break;

        // 실패하면 더 작게 재시도
        opts.inSampleSize *= 2;
        safeSleep(300);
    }

    if (bitmap == null) {
        // 수정: 3번의 시도 후에도 실패한 경우 상세 정보와 함께 throw
        throw new IOException("Failed to decode bitmap after 3 attempts. Last error: " + lastExceptionMessage + ", URI: " + uri);
    }

    // ===============================
    // 5) rotate (OOM 시 재시도)
    // ===============================
    if (rotate != 0) {
        Matrix m = new Matrix();
        m.setRotate(rotate);

        try {
            Bitmap rotated = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    m,
                    true
            );

            if (rotated != bitmap) {
                bitmap.recycle();
                bitmap = rotated;
            }
            this.orientationCorrected = true;

        } catch (OutOfMemoryError oom) {
            // rotate 실패는 치명적이지 않으므로 원본 반환 (기존 로직 유지)
            this.orientationCorrected = false;
            return bitmap;
        }
    }

    return bitmap;
}



private Bitmap decodeWithRetryFD(
        ContentResolver resolver,
        Uri uri,
        BitmapFactory.Options options
) {

    Bitmap bitmap = null;

    for (int attempt = 0; attempt < 3; attempt++) {

        try (ParcelFileDescriptor pfd =
                     resolver.openFileDescriptor(uri, "r")) {

            if (pfd == null) continue;

            bitmap = BitmapFactory.decodeFileDescriptor(
                    pfd.getFileDescriptor(),
                    null,
                    options
            );

        } catch (OutOfMemoryError oom) {
            LOG.w(LOG_TAG,
                    "OOM during decode attempt " + attempt,
                    oom);

            bitmap = null;
        } catch (Exception ignored) {
            bitmap = null;
        }

        if (bitmap != null) return bitmap;

        // 실패하면 더 작게 재시도
        options.inSampleSize *= 2;
        safeSleep(200);
    }

    return null;
}

private String convertHeicToJpeg(Uri uri) throws IOException {

    ContentResolver resolver = cordova.getActivity().getContentResolver();

    Bitmap bitmap = null;
    FileOutputStream fos = null;

    int rotationInDegrees = 0;

    try {
        int dstWidth = this.targetWidth;
        int dstHeight = this.targetHeight;

        // =========================================================
        // 0️⃣ URI 준비 대기 (race 방지)
        // =========================================================
        waitForUriReady(resolver, uri);
        safeSleep(50);
        
        // =========================================================
        // 1️⃣ Bounds 읽기 (FD 방식)
        // =========================================================
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        try (ParcelFileDescriptor pfd =
                     resolver.openFileDescriptor(uri, "r")) {

            if (pfd == null)
                throw new IOException("Cannot open file descriptor.");

            BitmapFactory.decodeFileDescriptor(
                    pfd.getFileDescriptor(),
                    null,
                    options
            );
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("Invalid image bounds (corrupted file).");
        }

        // =========================================================
        // 2️⃣ EXIF rotation 읽기 (stream 방식)
        // =========================================================
        if (this.correctOrientation) {
            try (InputStream exifIs = resolver.openInputStream(uri)) {

                if (exifIs != null) {

                    ExifInterface exif = new ExifInterface(exifIs);

                    int orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                    );

                    if (orientation == ExifInterface.ORIENTATION_ROTATE_90)
                        rotationInDegrees = 90;
                    else if (orientation == ExifInterface.ORIENTATION_ROTATE_180)
                        rotationInDegrees = 180;
                    else if (orientation == ExifInterface.ORIENTATION_ROTATE_270)
                        rotationInDegrees = 270;
                }

            } catch (Exception e) {
                LOG.w(LOG_TAG, "Failed to read EXIF. Skipping rotation.", e);
            }
        }

        // =========================================================
        // 3️⃣ inSampleSize 계산
        // =========================================================
        boolean willRotate = (rotationInDegrees != 0);

        if (dstWidth > 0 && dstHeight > 0) {
            options.inSampleSize = calculateSampleSizeSmart(
                    this.cordova.getActivity(), // ✅ Context 추가
                    options.outWidth,
                    options.outHeight,
                    dstWidth,
                    dstHeight,
                    willRotate,
                    true
            );
        } else {
            options.inSampleSize = 1;
        }

        // =========================================================
        // 4️⃣ Decode + Retry (HEIC null/OOM 방지)
        // =========================================================
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inMutable = false;
        options.inDither = true;

        bitmap = decodeWithRetryFD(resolver, uri, options);

        if (bitmap == null) {
            throw new IOException("Bitmap decode returned null after retry.");
        }

        // =========================================================
        // 5️⃣ Rotate (OOM 시 fallback)
        // =========================================================
        if (rotationInDegrees != 0) {

            Matrix matrix = new Matrix();
            matrix.setRotate(rotationInDegrees);

            try {
                Bitmap rotated = Bitmap.createBitmap(
                        bitmap,
                        0, 0,
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        matrix,
                        true
                );

                if (rotated != bitmap) {
                    bitmap.recycle();
                    bitmap = rotated;
                }

            } catch (OutOfMemoryError ignored) {
                //skip
            }
        }

        // =========================================================
        // 6️⃣ JPEG 저장
        // =========================================================
        File outFile = createCaptureFile(JPEG, System.currentTimeMillis() + "");
        // 기존
        // fos = new FileOutputStream(outFile);

        // 변경 (BufferedOutputStream으로 감싸기)
        fos = new FileOutputStream(outFile);
        BufferedOutputStream bos = new BufferedOutputStream(fos); // 버퍼링 추가

        boolean success = bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                this.mQuality,
                bos // fos 대신 bos 전달
        );
        bos.flush(); // 중요: 버퍼 비우기
        // bos.close()는 finally 블록의 fos.close()에 의해 연쇄적으로 처리되거나, 
        // 명시적으로 bos.close()를 호출해주면 좋습니다.

        if (!success) {
            throw new IOException("JPEG compression failed.");
        }

        // bitmap 정리
        bitmap.recycle();
        bitmap = null;

        return "file://" + outFile.getAbsolutePath();

    } finally {

        if (fos != null) {
            try { fos.close(); } catch (IOException ignored) {}
        }

        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}



    /**
     * JPEG, PNG and HEIC mime types (images) can be scaled, decreased in quantity, corrected by orientation.
     * But f.e. an image/gif cannot be scaled, but is can be selected through the PHOTOLIBRARY.
     *
     * @param mimeType The mimeType to check
     * @return if the mimeType is a processable image mime type
     */
    private boolean isImageMimeTypeProcessable(String mimeType) {
        return JPEG_MIME_TYPE.equalsIgnoreCase(mimeType) || PNG_MIME_TYPE.equalsIgnoreCase(mimeType)
               || HEIC_MIME_TYPE.equalsIgnoreCase(mimeType);
    }

    /**
     * Called when the camera view exits.
     *
     * @param requestCode The request code originally supplied to startActivityForResult(),
     * allowing you to identify who this result came from.
     * @param resultCode  The integer result code returned by the child activity through its setResult().
     * @param intent      An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        // Get src and dest types from request code for a Camera Activity
        int srcType = (requestCode / 16) - 1;
        int destType = (requestCode % 16) - 1;

        // If Camera Crop
        /*
        if (requestCode >= CROP_CAMERA) {
            if (resultCode == Activity.RESULT_OK) {
        
            final int finalDestType = requestCode - CROP_CAMERA;
            final Intent finalIntent = intent;

            // ThreadPool을 사용하여 백그라운드에서 실행
            cordova.getThreadPool().execute(() -> {
                try {

                    processResultFromCamera(finalDestType, finalIntent);
                } catch (IOException e) {
                    e.printStackTrace();
                    LOG.e(LOG_TAG, "Unable to write to file");
                    // 에러 발생 시 UI 스레드로 에러 전달 필요 시 runOnUiThread 사용
                    cordova.getActivity().runOnUiThread(() -> {
                       this.failPicture("Unable to write to file");
                    });
                    logreport(e);
                }
                                
            });

            }
    
            else if (resultCode == Activity.RESULT_CANCELED) {
                this.failPicture("No Image Selected");
            }

            // If something else
            else {
                this.failPicture("Did not complete!");
            }
        }
        */
        // If CAMERA
        if (srcType == CAMERA) {
            // If image available
            if (resultCode == Activity.RESULT_OK) {
                    
                if (this.allowEdit) {
                    
                        try {
                                
                                Uri tmpFile = FileProvider.getUriForFile(
                                        cordova.getActivity(),
                                        cordova.getActivity().getPackageName() + ".cordova.plugin.camera.provider",
                                        createCaptureFile(this.encodingType)
                                );
                                performCrop(tmpFile, destType, intent);


                        } catch (Exception e) {
                            e.printStackTrace();
                            this.failPicture("Error capturing image: " + e.getLocalizedMessage());
                            logreport(e);
                        }


                } else {

                    /*
                    // final 캡처
                    final int finalDestType = destType;
                    final Intent finalIntent = intent;

                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> {
                                
                                cordova.getThreadPool().execute(() -> {
                                try {
                                    processResultFromCamera(finalDestType, finalIntent);
                                } catch (Throwable t) {
                                    // 2. [핵심] 여기서 모든 런타임 에러(OOM, NPE 등)를 잡아냄
                                    LOG.e(LOG_TAG, "Fatal error in processResultFromCamera thread", t);
                                   this.failPicture("Error processing camera image: " + t.getMessage());
                                    logreport(t);
                                }                                
                                
                                
                                });
                            }, 50);
                    */

                    // final 캡처
                    final int finalDestType = destType;
                    final Intent finalIntent = intent;

                    // 1. UI 스레드 안정화 대기 (100ms 권장)
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {

                        // 2. 백그라운드 작업 시작
                        cordova.getThreadPool().execute(() -> {
                            try {
                                // 실제 로직 실행 (동기 함수)
                                processResultFromCamera(finalDestType, finalIntent);

                            } catch (Throwable t) {
                                // 3. 런타임 에러(OOM 등) 방어 및 로그 전송
                                LOG.e(LOG_TAG, "Fatal error in processResultFromCamera thread", t);
                                Activity act = cordova.getActivity();
                                if (act != null && !act.isFinishing()) {
                                    act.runOnUiThread(() ->
                                       CameraLauncher.this.failPicture("Error processing camera image: " + t.getLocalizedMessage())
                                    );
                                }                                
                                logreport(t);
                            }
                        });

                    }, 100); // 50ms -> 100ms (저사양 기기 UI 복구 시간 확보)
                    
                    
                }


            }

            // If cancelled
            else if (resultCode == Activity.RESULT_CANCELED) {
                this.failPicture("No Image Selected");
            }

            // If something else
            else {
                this.failPicture("Did not complete!");
                logreport("Did not complete!");
            }
        }
        // If retrieving photo from library
        else if ((srcType == PHOTOLIBRARY) || (srcType == SAVEDPHOTOALBUM)) {
            if (resultCode == Activity.RESULT_OK && intent != null) {
                final Intent i = intent;
                final int finalDestType = destType;
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        cleanupOldGooglePhotosQuirkFiles();
                        try {
                        processResultFromGallery(finalDestType, i);
                        } catch (Throwable t) {
                            // 예상치 못한 모든 에러(OOM 포함)를 JS에 보고
                            LOG.e(LOG_TAG, "Error in processResultFromGallery", t);
                           CameraLauncher.this.failPicture("Error processing image: " + t.getMessage());
                            logreport(t);
                        }
                       
                    }
                });
            } else if (resultCode == Activity.RESULT_CANCELED) {
                this.failPicture("No Image Selected");
            } else {
                this.failPicture("Selection did not complete!");
                logreport("Selection did not complete!");
            }
        }
    }

    private int exifToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
            return 90;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {
            return 180;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
            return 270;
        } else {
            return 0;
        }
    }

    /**
     * Write an inputstream to local disk
     *
     * @param fis - The InputStream to write
     * @param dest - Destination on disk to write to
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void writeUncompressedImage(InputStream fis, Uri dest) throws FileNotFoundException,
                                                                          IOException {
        OutputStream os = null;
        try {
            os = this.cordova.getActivity().getContentResolver().openOutputStream(dest);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception while closing output stream.");
                    logreport(e);
                }
            }
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception while closing file input stream.");
                    logreport(e);
                }
            }
        }
    }
    /**
     * In the special case where the default width, height and quality are unchanged
     * we just write the file out to disk saving the expensive Bitmap.compress function.
     *
     * @param src
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void writeUncompressedImage(Uri src, Uri dest) throws FileNotFoundException,
                                                                  IOException {

        InputStream fis = FileHelper.getInputStreamFromUriString(src.toString(), cordova);

        if (fis == null) {
            throw new IOException("Failed to open input stream from URI: " + src);
        }
    
        writeUncompressedImage(fis, dest);

    }

// =========================================================================
    // [오버로딩] 기존 호출부와의 호환성을 위해 isHeic 기본값을 false로 처리하는 래퍼 메서드
    // 기존에 calculateSampleSizeSmart(...)를 호출하던 곳은 에러 없이 이 메서드를 탑니다.
    // =========================================================================
    public static int calculateSampleSizeSmart(
            Context context,
            int srcWidth, int srcHeight,
            int dstWidth, int dstHeight,
            boolean willRotate
    ) {
        return calculateSampleSizeSmart(context, srcWidth, srcHeight, dstWidth, dstHeight, willRotate, false);
    }

    // =========================================================================
    // [메인 로직] isHeic 파라미터가 추가된 실제 계산 메서드
    // =========================================================================
    public static int calculateSampleSizeSmart(
            Context context,
            int srcWidth, int srcHeight,
            int dstWidth, int dstHeight,
            boolean willRotate,
            boolean isHeic // <-- 추가된 HEIC 판별 파라미터
    ) {
        // 1) Raw 계산 (비율 기준)
        int raw;
        float srcAspect = (float) srcWidth / srcHeight;
        float dstAspect = (float) dstWidth / dstHeight;

        if (srcAspect > dstAspect) {
            raw = srcWidth / dstWidth;
        } else {
            raw = srcHeight / dstHeight;
        }
        if (raw < 1) raw = 1;

        // 2) 무조건 2의 거듭제곱으로 올림 (Ceil)
        int sample = 1;
        while (sample < raw) {
            sample *= 2;
        }

        // 3) 실제 Sample 기준 예상 메모리 계산 (RGB_565 = 2bytes)
        int expectedW = srcWidth / sample;
        int expectedH = srcHeight / sample;
        long bitmapBytes = (long) expectedW * expectedH * 2;

        // Rotate(3배) vs DecodeOnly(2배)
        long requiredBytes = willRotate ? bitmapBytes * 3 : bitmapBytes * 2;

        // =======================================================
        // [핵심 변경점] HEIC vs JPG 메모리 차등 계산
        // HEIC는 압축을 푸는 과정에서 막대한 Native 메모리 스파이크가 발생합니다.
        // 따라서 요구 메모리를 일반 이미지 대비 2.5배 크게 부풀려서 보수적으로 평가합니다.
        // =======================================================
        if (isHeic) {
            requiredBytes = (long) (requiredBytes * 2.5);
        }

        // 4) Heap 여유 계산
        Runtime rt = Runtime.getRuntime();
        long freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());

        // 5) Device 체급 + LowRam 여부 기반 safety 조정
        ActivityManager am =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        int memClass = am.getMemoryClass(); // 예: 128 / 256 / 512
        boolean lowRam = am.isLowRamDevice();

        // 기본 safety (기기 체급 기반)
        float safety;
        if (memClass <= 128) safety = 0.20f;
        else if (memClass <= 256) safety = 0.30f;
        else safety = 0.40f;

        // LowRamDevice면 더 보수적으로
        if (lowRam) {
            safety *= 0.75f;   // 예: 0.30 → 0.225
        }

        // 6) 최종 메모리 평가 및 추가 축소 방어
        if (requiredBytes < freeHeap * safety) {
            // 여유가 있다면 (JPG이거나 기기 스펙이 매우 좋거나)
            return sample;
        } else {
            // HEIC 가중치 때문에 초과했거나 진짜 메모리가 부족하다면 강제로 한 단계 더 축소 (*2)
            // sample을 2배 키우면 결과물 픽셀 면적은 1/4이 되므로 OOM을 완벽히 방어합니다.
            return sample * 2;
        }
    }


    /**
     * Cleans up after picture taking. Checking for duplicates and that kind of stuff.
     *
     * @param newImage
     */
    private void cleanup(Uri oldImage, Uri newImage, Bitmap bitmap) {
        try {
            if (bitmap != null) {
                bitmap.recycle();
            }

            // Clean up initial camera-written image file.
            (new File(FileHelper.stripFileProtocol(oldImage.toString()))).delete();

            // Scan for the gallery to update pic refs in gallery
            if (this.saveToPhotoAlbum && newImage != null) {
                this.scanForGallery(newImage);
            }
        
        } catch (Exception e) {
        // 청소 실패는 치명적이지 않으므로 로그만 남김
        LOG.w(LOG_TAG, "Failed to cleanup: " + e.getMessage());
        }

        System.gc();
    }

    /**
     * Determine if we are storing the images in internal or external storage
     *
     * @return Uri
     */
    private Uri whichContentStore() {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        } else {
            return MediaStore.Images.Media.INTERNAL_CONTENT_URI;
        }
    }

    /**
     * Compress bitmap using jpeg, convert to Base64 encoded string, and return to JavaScript.
     *
     * @param bitmap
     */
    public void processPicture(Bitmap bitmap, int encodingType) {
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        CompressFormat compressFormat = getCompressFormatForEncodingType(encodingType);

        try {
            if (bitmap.compress(compressFormat, mQuality, dataStream)) {
                StringBuilder sb = new StringBuilder()
                    .append("data:")
                    .append(encodingType == PNG ? PNG_MIME_TYPE : JPEG_MIME_TYPE)
                    .append(";base64,");
                byte[] code = dataStream.toByteArray();
                byte[] output = Base64.encode(code, Base64.NO_WRAP);
                sb.append(new String(output));
                this.callbackContext.success(sb.toString());
                output = null;
                code = null;
            }
        } catch (Exception e) {
            this.failPicture("Error compressing image: "+e.getLocalizedMessage());
            logreport(e);
        }
    }

    /**
     * Send error message to JavaScript.
     *
     * @param err
     */
    public void failPicture(String err) {
        if (this.callbackContext!=null)
        this.callbackContext.error(err);
    }









    private void scanForGallery(Uri newImage) {
        this.scanMe = newImage;
        if (this.conn != null) {
            this.conn.disconnect();
        }
        this.conn = new MediaScannerConnection(this.cordova.getActivity().getApplicationContext(), this);
        conn.connect();
    }

    public void onMediaScannerConnected() {
        try {
            this.conn.scanFile(this.scanMe.toString(), "image/*");
        } catch (IllegalStateException e) {
            LOG.e(LOG_TAG, "Can't scan file in MediaScanner after taking picture");
        }

    }

    public void onScanCompleted(String path, Uri uri) {
        this.conn.disconnect();
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, PERMISSION_DENIED_ERROR));
                return;
            }
        }
        switch (requestCode) {
            case TAKE_PIC_SEC:
                takePicture(this.destType, this.encodingType);
                break;
            case SAVE_TO_ALBUM_SEC:
                this.getImage(this.srcType, this.destType);
                break;
        }
    }

    /**
     * Taking or choosing a picture launches another Activity, so we need to implement the
     * save/restore APIs to handle the case where the CordovaActivity is killed by the OS
     * before we get the launched Activity's result.
     */
    public Bundle onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putInt("destType", this.destType);
        state.putInt("srcType", this.srcType);
        state.putInt("mQuality", this.mQuality);
        state.putInt("targetWidth", this.targetWidth);
        state.putInt("targetHeight", this.targetHeight);
        state.putInt("encodingType", this.encodingType);
        state.putInt("mediaType", this.mediaType);
        state.putBoolean("allowEdit", this.allowEdit);
        state.putBoolean("correctOrientation", this.correctOrientation);
        state.putBoolean("saveToPhotoAlbum", this.saveToPhotoAlbum);

        if (this.croppedUri != null) {
            state.putString(CROPPED_URI_KEY, this.croppedFilePath);
        }

        if (this.imageUri != null) {
            state.putString(IMAGE_URI_KEY, this.imageUri.toString());
        }

        return state;
    }

    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        this.destType = state.getInt("destType");
        this.srcType = state.getInt("srcType");
        this.mQuality = state.getInt("mQuality");
        this.targetWidth = state.getInt("targetWidth");
        this.targetHeight = state.getInt("targetHeight");
        this.encodingType = state.getInt("encodingType");
        this.mediaType = state.getInt("mediaType");
        this.allowEdit = state.getBoolean("allowEdit");
        this.correctOrientation = state.getBoolean("correctOrientation");
        this.saveToPhotoAlbum = state.getBoolean("saveToPhotoAlbum");

        if (state.containsKey(CROPPED_URI_KEY)) {
            this.croppedUri = Uri.parse(state.getString(CROPPED_URI_KEY));
        }

        if (state.containsKey(IMAGE_URI_KEY)) {
            this.imageUri = Uri.parse(state.getString(IMAGE_URI_KEY));
        }

        this.callbackContext = callbackContext;
    }

    // [새로 추가할 메서드] 메모리 및 캐시 강제 정리
    private void releaseMemory() {
        // 1. Java Heap 메모리 청소 유도 (OS에 힌트 전달)
        try {
        System.gc();
        System.runFinalization();
        } catch (Exception ignore) {}
        
        if (cordova == null || cordova.getActivity() == null) return;

        // 2. WebView 캐시 정리 (RAM에 상주하는 이미지 리소스 등 해제)
        // WebView 조작은 반드시 UI 스레드에서 해야 함
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // true: 디스크 캐시와 메모리 캐시를 모두 비웁니다.
                    // 이미 loading.html로 왔으므로 이전 페이지 리소스를 다 날려도 안전합니다.
                    if (webView != null) {
                        webView.clearCache(true);
                    }
                } catch (Exception e) {
                    LOG.e(LOG_TAG, "Error clearing webview cache", e);
                }
                
            }
        });
    }

}