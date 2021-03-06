package com.savhascelik.exampleyolov5withncnn;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.extensions.HdrImageCaptureExtender;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceManager;

import com.google.common.util.concurrent.ListenableFuture;
import com.gyf.immersionbar.BarHide;
import com.gyf.immersionbar.ImmersionBar;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static final String detectMethodsIntentName = "DETECT_METHOD_INTENT";
    private static final String TAG = "MainActivity";
    private static final int settings_result_code = 2;
    private ExecutorService executor = null;
    private final int REQUEST_CODE_PERMISSIONS = 1001;
    //    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE"};
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};

    private ProcessCameraProvider cameraProvider = null;
    private NCNNDetector ncnnDetector = null;
    private static final HashMap<String, String> detectNetwork = new HashMap<String, String>() {{

        put(YoloV5Ncnn.class.getName(), "YOLOv5");

    }};
    //settings
    private boolean useGPU = false;

    // UI
    PreviewView mPreviewView;
    Overlay overlay;
    TextView textViewFPS;
    TextView textViewNetwork;
    LinearLayout ll_settings;
    LinearLayout ll_github;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //UI

        mPreviewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.overlay);
        textViewFPS = findViewById(R.id.textView_FPS);
        textViewNetwork = findViewById(R.id.textView_Network);
        ll_settings = findViewById(R.id.ll_settings);
        ll_settings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.putExtra(detectMethodsIntentName, detectNetwork);
            startActivity(intent);
        });
        ll_github = findViewById(R.id.ll_github);
        ll_github.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/savhascelik"));
            startActivity(intent);
        });
        TextView ncnn_version = findViewById(R.id.tv_ncnn_version);
        ncnn_version.setText(NCNNDetector.get_ncnn_version());


        PreferenceManager.setDefaultValues(this, R.xml.root_preferences, false);
        Log.d(TAG, "On Create");

        //to show left drawer, disable gesture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {//android 10 ????????????
            ArrayList<Rect> rs = new ArrayList<Rect>();

            Rect r = new Rect();
            this.getWindowManager().getDefaultDisplay().getRectSize(r);
            r.right = r.right / 4;
            r.bottom = r.bottom * 6 / 8;
            rs.add(r);
            overlay.setSystemGestureExclusionRects(rs);
        }

        if (allPermissionsGranted()) {
            startCamera(); //start camera if permission has been granted by user
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {

        new Thread() {
            @Override
            public void run() {
                super.run();
                updateSettings();
                // Use less
                // runOnUiThread(() -> {
                //     ImmersionBar.with(MainActivity.this).hideBar(BarHide.FLAG_HIDE_BAR).init();
                // });
            }
        }.start();
        super.onResume();
    }

    private void updateSettings() {
        // Load  Preference
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        this.useGPU = sharedPreferences.getBoolean(this.getResources().getString(R.string.useGPU), false);
        String networkClassName = sharedPreferences.getString(this.getResources().getString(R.string.method_index), null);
        if (networkClassName == null) {
            Optional<String> s = detectNetwork.keySet().stream().findFirst();
            if (s.isPresent()) {
                networkClassName = s.get();
            }
        }

        //Choose a new detector in settings or first init detector
        if (ncnnDetector == null || !ncnnDetector.getClass().getName().equals(networkClassName)) {
            try {
                NCNNDetector tempDetector = (NCNNDetector) (Class.forName(networkClassName).newInstance());
                String s = detectNetwork.getOrDefault(networkClassName, "");
                runOnUiThread(() -> {
                    textViewNetwork.setText(s);
                });
                boolean ret_init = tempDetector.Init(getAssets());
                if (!ret_init) {
                    Log.e(TAG, "NCNN Detector Init failed");
                } else {
                    ncnnDetector = tempDetector;
                }
            } catch (IllegalAccessException | InstantiationException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDestroy() {
        waitCameraProcessFinished();
        //image analyse????????????????????????????????????libc?????????????????????
        super.onDestroy();
    }


    private void startCamera() {

        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);

            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));
    }

    //    static int i = 0;
    ImageAnalysis imageAnalysis = null;

    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {

        Preview preview = new Preview.Builder()
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        executor = Executors.newSingleThreadExecutor();
        imageAnalysis.setAnalyzer(executor, image -> {
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            // insert your code here.
            Bitmap bitmap = mPreviewView.getBitmap();
//            Log.i(TAG,"Bitmap width:"+bitmap.getWidth());
//            Log.i(TAG,"Bitmap height:"+bitmap.getHeight());
            if (bitmap != null && ncnnDetector != null) {
//                Log.d(TAG, "before detect");
                long tick = System.nanoTime();
                NCNNDetector.Obj[] objs = ncnnDetector.Detect(bitmap, this.useGPU);
                long tock = System.nanoTime();
//                Log.d(TAG, "after detect");`
                double fps = 1.0e9 / (tock - tick);
                runOnUiThread(() -> {
                    overlay.drawRects(objs);
                    textViewFPS.setText(String.format("%4.2f", fps));
                });
            }
            image.close();
        });

        ImageCapture.Builder builder = new ImageCapture.Builder();

        //Vendor-Extensions (The CameraX extensions dependency in build.gradle)
        HdrImageCaptureExtender hdrImageCaptureExtender = HdrImageCaptureExtender.create(builder);

        // Query if extension is available (optional).
        if (hdrImageCaptureExtender.isExtensionAvailable(cameraSelector)) {
            // Enable the extension if available.
            hdrImageCaptureExtender.enableExtension(cameraSelector);
        }

        final ImageCapture imageCapture = builder
                .setTargetRotation(this.getWindowManager().getDefaultDisplay().getRotation())
                .build();

        preview.setSurfaceProvider(mPreviewView.createSurfaceProvider());

        Camera camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageAnalysis, imageCapture);

    }

    //will catch libc NULL pointer error if not wait for detection finished

    private void waitCameraProcessFinished() {
        if (executor != null) {
            executor.shutdownNow();
            //imageAnalysis.clearAnalyzer();
            while (!executor.isTerminated()) {
            }
            executor = null;
        }
        //CameraX.unbindAll();
    }

    private Bitmap rotateBitmap(Bitmap origin, float alpha) {
        if (origin == null) {
            return null;
        }
        int width = origin.getWidth();
        int height = origin.getHeight();
        Matrix matrix = new Matrix();
        matrix.setRotate(alpha);
        // ????????????????????????
        Bitmap newBM = Bitmap.createBitmap(origin, 0, 0, width, height, matrix, false);
        if (newBM.equals(origin)) {
            return newBM;
        }
        origin.recycle();
        return newBM;
    }

    private void YUV2Bitmap() {

    }

    public String getBatchDirectoryName() {

        String app_folder_path = "";
        app_folder_path = Environment.getExternalStorageDirectory().toString() + "/images";
        File dir = new File(app_folder_path);
        if (!dir.exists() && !dir.mkdirs()) {

        }

        return app_folder_path;
    }

    private boolean allPermissionsGranted() {

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                this.finish();
            }
        }
    }
}