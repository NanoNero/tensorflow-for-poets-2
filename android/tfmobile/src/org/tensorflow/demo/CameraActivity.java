/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.demo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image.Plane;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup.LayoutParams;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;


import org.tensorflow.demo.env.Logger;


import static android.widget.Toast.makeText;


public abstract class CameraActivity extends Activity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();
  public boolean onResume_just_ran = false;

  private static final int PERMISSIONS_REQUEST = 1;

  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

  private boolean debug = false;

  private Handler handler;
  private HandlerThread handlerThread;

  public ImageView imageView;

  public boolean imageOn = false;
  public int currentPosition = 0;
  public boolean popupWindowOn = false;
  private int rotationAngle = 0;
  public Button help;

  @Override
  protected void onCreate(final Bundle savedInstanceState) {

    LOGGER.d("onCreate " + this);
    super.onCreate(null);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    setContentView(R.layout.activity_camera);
    imageView = (ImageView) findViewById(R.id.imageView);
    help = (Button) findViewById(R.id.infobutton);

    help.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        FrameLayout Container = (FrameLayout) findViewById(R.id.container);
        FrameLayout imageFrame = (FrameLayout) findViewById(R.id.imageFrame);
        ScrollView HelpView = (ScrollView) findViewById(R.id.helpview);
        RecognitionScoreView resultsView = (RecognitionScoreView) findViewById(R.id.results);

        new Thread() {
          public void run() {
            try {
              runOnUiThread(new Runnable() {
                @Override
                public void run() {
                  if(help.getText().equals(" ? ")){
                    imageFrame.setVisibility(View.INVISIBLE);
                    resultsView.setVisibility(View.INVISIBLE);
                    Container.setVisibility(View.INVISIBLE);
                    HelpView.setVisibility(View.VISIBLE);
                    help.setText("Return");
                }
                else {
                    if(imageOn) {
                      imageFrame.setVisibility(View.VISIBLE);
                    }
                    else {
                      resultsView.setVisibility(View.VISIBLE);
                      Container.setVisibility(View.VISIBLE);
                    }
                    Container.setVisibility(View.VISIBLE);
                    HelpView.setVisibility(View.INVISIBLE);
                    help.setText(" ? ");
                  }
                  }
                });
              Thread.sleep(300);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
        }.start();
      }
    });

    if (hasPermission()) {
      setFragment();
    } else {
      requestPermission();
    }
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);
    super.onStart();
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    onResume_just_ran = true;
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    if (onResume_just_ran) {
      super.onPause();
      onResume_just_ran = false;
      return;
    }

    if (!isFinishing()) {
      LOGGER.d("Requesting finish");
      finish();
    }

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    super.onDestroy();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  public void onRequestPermissionsResult(
          final int requestCode, final String[] permissions, final int[] grantResults) {
    switch (requestCode) {
      case PERMISSIONS_REQUEST: {
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
          setFragment();
        } else {
          requestPermission();
        }
      }
    }
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) || shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
        makeText(CameraActivity.this, "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show();
      }
      requestPermissions(new String[]{PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
    }
  }

  protected void setFragment() {
    final Fragment fragment =
            CameraConnectionFragment.newInstance(
                    new CameraConnectionFragment.ConnectionCallback() {
                      @Override
                      public void onPreviewSizeChosen(final Size size, final int rotation) {
                        CameraActivity.this.onPreviewSizeChosen(size, rotation);
                      }
                    },
                    this,
                    getLayoutId(),
                    getDesiredPreviewFrameSize());

    getFragmentManager()
            .beginTransaction()
            .replace(R.id.container, fragment)
            .commit();
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  public boolean isDebug() {
    return debug;
  }

  public void requestRender() {
    final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
    if (overlay != null) {
      overlay.postInvalidate();
    }
  }

  public void addCallback(final OverlayView.DrawCallback callback) {
    final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
    if (overlay != null) {
      overlay.addCallback(callback);
    }
  }

  public void onSetDebug(final boolean debug) {
  }

  @Override
  public boolean onKeyDown(final int keyCode, final KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
      debug = !debug;
      requestRender();
      onSetDebug(debug);
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

  protected abstract int getLayoutId();

  protected abstract Size getDesiredPreviewFrameSize();

  public void seePicture() {

    ArrayList<String> filepaths = getFilePaths();

    new Thread() {
      public void run() {
        try {
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              FrameLayout Container = (FrameLayout) findViewById(R.id.container);
              FrameLayout imageFrame = (FrameLayout) findViewById(R.id.imageFrame);
              TextView textView = (TextView) findViewById(R.id.textView);

              RecognitionScoreView resultsView = (RecognitionScoreView) findViewById(R.id.results);
              resultsView.setVisibility(View.INVISIBLE);
              //Container.setVisibility(View.INVISIBLE);
              //Made changes here

              FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(200, 240);
              params.gravity = Gravity.END;
              Container.setLayoutParams(params);
              Container.requestLayout();

              Bitmap bmp = BitmapFactory.decodeFile(filepaths.get(0));
              imageView.setImageBitmap(bmp);
              textView.setText(Integer.toString(currentPosition+1)+"/"+ filepaths.size());
              imageFrame.setVisibility(View.VISIBLE);
            }
          });
          Thread.sleep(500);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }.start();
    imageOn = true;
    currentPosition = 0;
  }

  public void removePicture() {
    new Thread() {
      public void run() {
        try {
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              FrameLayout Container = (FrameLayout) findViewById(R.id.container);
              FrameLayout imageFrame = (FrameLayout) findViewById(R.id.imageFrame);
              imageFrame.setVisibility(View.INVISIBLE);
              imageView.setImageDrawable(null);
              RecognitionScoreView resultsView = (RecognitionScoreView) findViewById(R.id.results);
              resultsView.setVisibility(View.VISIBLE);
              //Container.setVisibility(View.VISIBLE);
              //Made changes here
              Container.getLayoutParams().height = LayoutParams.MATCH_PARENT;
              Container.getLayoutParams().width = LayoutParams.MATCH_PARENT;
              Container.requestLayout();
            }
          });
          Thread.sleep(300);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }.start();
    imageOn = false;
    currentPosition = 0;
  }

  public ArrayList<String> getFilePaths() {
    File directory = null;
    ArrayList<String> filePaths = null;
    String path = Environment.getExternalStorageDirectory().getAbsolutePath();
    path = path + "/saved_images";

    directory = new File(path);

    // check for directory
    if (directory.isDirectory()) {
      // getting list of file paths
      File[] listFiles = null;
      listFiles = directory.listFiles();

      // Check for count
      if (listFiles.length > 0) {

        filePaths = new ArrayList<String>();
        for (int i = 0; i < listFiles.length; i++) {
          String filePath = listFiles[i].getAbsolutePath();
          filePaths.add(filePath);

        }
        //Sort Files here
        Collections.sort(filePaths, new Comparator<String>(){
          @Override
          public int compare(String s1, String s2)
          {
            String[] s1Values = s1.split("\\.");
            String[] s2Values = s2.split("\\.");
            //compare the year
            if(s1Values.length >= 3 && s2Values.length >= 3)
            {
              int compare = s1Values[2].compareTo(s2Values[2]);
              if(compare != 0) return compare;
            }
            //compare the month
            if(s1Values.length >= 2 && s2Values.length >= 2)
            {
              int compare = s1Values[1].compareTo(s2Values[1]);
              if(compare != 0) return compare;
            }
            //compare the day
            if(s1Values.length >= 1 && s2Values.length >= 1)
            {
              int compare = s1Values[0].compareTo(s2Values[0]);
              if(compare != 0) return compare;
            }
            return 0;
          }
        });
        Collections.reverse(filePaths);
      } else {
        // image directory is empty
        makeText(this, " Directory is empty. Please load some images in it !",
                Toast.LENGTH_LONG).show();
      }

    } else {
      AlertDialog.Builder alert = new AlertDialog.Builder(this);
      alert.setTitle("Error!");
      alert.setMessage(" directory path is not valid! Please set the image directory name AppConstant.java class");
      alert.setPositiveButton("OK", null);
      alert.show();
    }

    return filePaths;
  }

  public void deletePicture() {

    ArrayList<String> filepaths = getFilePaths();
    File fdelete = new File(filepaths.get(currentPosition));
    filepaths.remove(currentPosition);

    if (!fdelete.exists()) return;
    new Thread() {
      public void run() {
        try {
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              imageView.setImageBitmap(null);

              if (filepaths.size() == 0)
                  removePicture();        //Stop seeing pictures, go to camera preview
                else {
                  if (currentPosition <= filepaths.size() - 1) {
                    Bitmap bmp = BitmapFactory.decodeFile(filepaths.get(currentPosition));
                    imageView.setImageBitmap(bmp);
                    TextView textView = (TextView) findViewById(R.id.textView);
                    textView.setText(Integer.toString(currentPosition+1)+"/"+ filepaths.size());
                  } else {
                    currentPosition = filepaths.size() - 1;
                    Bitmap bmp = BitmapFactory.decodeFile(filepaths.get(currentPosition));
                    imageView.setImageBitmap(bmp);
                    TextView textView = (TextView) findViewById(R.id.textView);
                    textView.setText(Integer.toString(currentPosition+1)+"/"+ filepaths.size());
                  }
                }
            }
          });
          Thread.sleep(100);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }.start();
    popupWindowOn = false;
    fdelete.delete();
    System.out.println("file Deleted");

  }

  public void navigatePictures(int direction) {
    if (!imageOn) return;

    ArrayList<String> filepaths = new ArrayList();
    filepaths = getFilePaths();
    ArrayList<String> finalFilepaths = filepaths;
    new Thread() {
      public void run() {
        try {
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              rotationAngle = 0;
              //Moving forward
              if (direction == 0 && currentPosition < finalFilepaths.size() - 1) {
                currentPosition++;
                Bitmap bmp = BitmapFactory.decodeFile(finalFilepaths.get(currentPosition));
                imageView.setImageBitmap(bmp);
                imageView.setRotation(rotationAngle);
                TextView textView = (TextView) findViewById(R.id.textView);
                textView.setText(Integer.toString(currentPosition+1)+"/"+ finalFilepaths.size());
              }
              //Moving backward
              else if (direction == 1 && currentPosition > 0) {
                currentPosition--;
                Bitmap bmp = BitmapFactory.decodeFile(finalFilepaths.get(currentPosition));
                imageView.setImageBitmap(bmp);
                TextView textView = (TextView) findViewById(R.id.textView);
                textView.setText(Integer.toString(currentPosition+1)+"/"+ finalFilepaths.size());
                if(rotationAngle != 0){
                  imageView.setRotation(imageView.getRotation() - rotationAngle);
                  rotationAngle = 0;
                }
              } else {
                System.out.println("No Pics");
                //Toast.makeText(this, "No more pictures!", Toast.LENGTH_SHORT).show();
              }
            }
          });
          Thread.sleep(300);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }.start();

  }

  public void rotateImage(){
    if(!imageOn) return;

    new Thread() {
      public void run() {
        try {
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              rotationAngle += 90;
              imageView.setRotation(imageView.getRotation() + rotationAngle);
              if(rotationAngle % 360 == 0)
                rotationAngle = 0;
            }
          });
          Thread.sleep(300);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }.start();
  }
}
