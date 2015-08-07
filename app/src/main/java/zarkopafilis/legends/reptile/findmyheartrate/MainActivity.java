package zarkopafilis.legends.reptile.findmyheartrate;

import android.content.Context;
import android.hardware.Camera;
import android.os.PowerManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


public class MainActivity extends ActionBarActivity {

    public static final String TAG = "HeartRateMonitor";

    static Button calc;
    static TextView heartRate;
    static TextView hint;

    static boolean doCalc = false;

    static final AtomicBoolean processing = new AtomicBoolean(false);

    static SurfaceView preview;
    static SurfaceHolder previewHolder;
    static Camera camera;
    static View image;

    static int averageIndex = 0;
    static int averageArraySize = 4;
    static int[] averageArray = new int[averageArraySize];

    public static enum TYPE {
        GREEN, RED
    };

    static TYPE currentType = TYPE.GREEN;//start with no pulse

    public static TYPE getCurrent() {
        return currentType;
    }

    static int beatsIndex = 0;
    static int beatsArraySize = 3;
    static int[] beatsArray = new int[beatsArraySize];
    static double beats = 0;
    static long startTime = 0;

    PowerManager.WakeLock wakeLock = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        calc = (Button) findViewById(R.id.calcBtn);
        heartRate = (TextView) findViewById(R.id.heartRateField);
        hint = (TextView) findViewById(R.id.hintText);

        preview = (SurfaceView) findViewById(R.id.preview);

        previewHolder = preview.getHolder();
        previewHolder.addCallback(surfaceCallback);
        previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        //prevent sleep
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "DoNotDimScreen");

        calc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calc.setVisibility(View.GONE);
                heartRate.setText("Calculating...");
                hint.setText("Please Wait...");

                Log.d("tag", "acquiring wakelock");

                wakeLock.acquire();

                Log.d("tag", "opening camera");

                camera = Camera.open();

                camera.unlock();
                try {
                    camera.reconnect();
                    camera.setPreviewDisplay(previewHolder);
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.d("tag" , "exc");
                }

                camera.setDisplayOrientation(90);

                camera.setPreviewCallback(previewCallback);

                Camera.Parameters parameters = camera.getParameters();
                List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();

                // You need to choose the most appropriate previewSize for your app
                Camera.Size previewSize = previewSizes.get(previewSizes.size() - 1);

                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);

                camera.setParameters(parameters);

                startTime = System.currentTimeMillis();

                doCalc = true;

                camera.startPreview();

                Log.d("tag" , "calculations started");

               // calc.setVisibility(View.VISIBLE);
                //hint.setText("Place your finger on the camera and press the button");
            }
        });

    }

    private static Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {

        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {

            if(!doCalc) return;

            if (data == null) throw new NullPointerException();//no data?Abort

            //Get size
            Camera.Size size = cam.getParameters().getPreviewSize();

            if (size == null) throw new NullPointerException();//No size = No data. Abort

            if (!processing.compareAndSet(false, true)) return;//?

            //Get image dimensions
            int width = size.width;
            int height = size.height;

            int imgAvg = ImageProccessing.decodeYUV420SPtoRedAvg(data.clone(), height, width);//Get image average..Look at method

            // Log.i(TAG, "imgAvg="+imgAvg);
            if (imgAvg == 0 || imgAvg == 255) {//If its MINIMUM RED (BLACK) = 0/255 or MAXIMUM RED (RED) 255/255 something is wrong.
                processing.set(false);
                return;
            }

            int averageArrayAvg = 0;
            int averageArrayCnt = 0;

            for (int i = 0; i < averageArray.length; i++) {
                if (averageArray[i] > 0) {
                    averageArrayAvg += averageArray[i];
                    averageArrayCnt++;
                }
            }

            int rollingAverage = (averageArrayCnt > 0) ? (averageArrayAvg / averageArrayCnt) : 0;

            TYPE newType = currentType;//Green or red

            if (imgAvg < rollingAverage) {
                newType = TYPE.RED;
                if (newType != currentType) {//Dont count beat if previous frame was red as well
                    beats++;//NEW BEAT!
                }
            } else if (imgAvg > rollingAverage) {//No beat this frame. Feel free to calculate one next frame
                newType = TYPE.GREEN;
            }

            if (averageIndex == averageArraySize) averageIndex = 0;
            averageArray[averageIndex] = imgAvg;
            averageIndex++;

            // Transitioned from one state to another to the same
            if (newType != currentType) {//*//Dont count beat if previous frame was red as well**
                currentType = newType;
                if(image != null) {
                    image.postInvalidate();//throw image to trash
                }
            }

            long endTime = System.currentTimeMillis();

            double totalTimeInSecs = (endTime - startTime) / 1000d;//Calculate total time in seconds

            if (totalTimeInSecs >= 10) {
                double bps = (beats / totalTimeInSecs);//Beats per second
                int dpm = (int) (bps * 60d);//Beats per minute
                if (dpm < 30 || dpm > 180) {//Something went wrong if bpm < 30 or bpm > 180...Are you dead yet?
                    startTime = System.currentTimeMillis();
                    beats = 0;
                    processing.set(false);

                    heartRate.setText("Error");

                    calc.setVisibility(View.VISIBLE);
                    hint.setText("Place your finger on the camera and press the button");

                    doCalc = false;

                    camera.stopPreview();
                    camera.setPreviewCallback(null);
                    camera.stopPreview();
                    camera.release();
                    camera = null;

                    beatsArray = new int[beatsArraySize];
                    beatsArraySize = 3;
                    beatsIndex = 0;

                    currentType = TYPE.GREEN;

                    averageIndex = 0;
                    averageArraySize = 4;
                    averageArray = new int[averageArraySize];
                    return;//Break calculation
                }

                if (beatsIndex == beatsArraySize) beatsIndex = 0;
                beatsArray[beatsIndex] = dpm;
                beatsIndex++;

                int beatsArrayAvg = 0;
                int beatsArrayCnt = 0;

                for (int i = 0; i < beatsArray.length; i++) {
                    if (beatsArray[i] > 0) {
                        beatsArrayAvg += beatsArray[i];
                        beatsArrayCnt++;
                    }
                }

                int beatsAvg = (beatsArrayAvg / beatsArrayCnt);

                heartRate.setText(String.valueOf(beatsAvg));//Print result

                //startTime = System.currentTimeMillis();//calculation end, start again with new time.
                beats = 0;

                calc.setVisibility(View.VISIBLE);
                hint.setText("Place your finger on the camera and press the button");

                doCalc = false;

                camera.stopPreview();
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
                camera = null;

                beatsArray = new int[beatsArraySize];
                beatsArraySize = 3;
                beatsIndex = 0;

                currentType = TYPE.GREEN;

                averageIndex = 0;
                averageArraySize = 4;
                averageArray = new int[averageArraySize];
            }

            processing.set(false);
        }
    };

    //Gets called when something changes
    private static SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                if(camera !=null) {
                    camera.setPreviewDisplay(previewHolder);
                    camera.setPreviewCallback(previewCallback);
                }
            } catch (Throwable t) {
                Log.e("PreviewDemo", "Exception in setPreviewDisplay()", t);
            }
        }

        //Change image format and image size
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height){
            Log.d("tag", "surfaceChanged");
           /* if(camera != null) {
               camera.stopPreview();
            }
            Camera.Parameters parameters = camera.getParameters();
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            Camera.Size size = getSmallestPreviewSize(width, height, parameters);
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
                Log.d(TAG, "Using width=" + size.width + " height=" + size.height);
            }
            camera.setParameters(parameters);
            camera.startPreview();*/
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // Ignore
        }
    };

    private static Camera.Size getSmallestPreviewSize(int width, int height, Camera.Parameters parameters) {
        Camera.Size result = null;

        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size;
                } else {
                    int resultArea = result.width * result.height;
                    int newArea = size.width * size.height;

                    if (newArea < resultArea) result = size;
                }
            }
        }

        return result;
    }

    //Resource cleanup
    @Override
    public void onResume() {
        super.onResume();

        wakeLock.acquire();

        doCalc = false;

        //camera = Camera.open();

        //startTime = System.currentTimeMillis();
    }

    @Override
    public void onPause()
    {//resource cleanup
        super.onPause();

        doCalc = false;

        wakeLock.release();

        if(camera !=null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
