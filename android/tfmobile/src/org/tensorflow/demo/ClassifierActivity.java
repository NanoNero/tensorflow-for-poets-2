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


import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.Toast;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Vector;
import org.tensorflow.demo.OverlayView.DrawCallback;
import org.tensorflow.demo.env.BorderedText;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.env.Logger;

public class ClassifierActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();
  private int pattern = 0;
  private boolean savePhoto = false;



  // These are the settings for the original v1 Inception model. If you want to
  // use a model that's been produced from the TensorFlow for Poets codelab,
  // you'll need to set IMAGE_SIZE = 299, IMAGE_MEAN = 128, IMAGE_STD = 128,
  // INPUT_NAME = "Mul", and OUTPUT_NAME = "final_result".
  // You'll also need to update the MODEL_FILE and LABEL_FILE paths to point to
  // the ones you produced.
  //
  // To use v3 Inception model, strip the DecodeJpeg Op from your retrained
  // model first:
  //
  // python strip_unused.py \
  // --input_graph=<retrained-pb-file> \
  // --output_graph=<your-stripped-pb-file> \
  // --input_node_names="Mul" \
  // --output_node_names="final_result" \
  // --input_binary=true

  /* Inception V3
  private static final int INPUT_SIZE = 299;
  private static final int IMAGE_MEAN = 128;
  private static final float IMAGE_STD = 128.0f;
  private static final String INPUT_NAME = "Mul:0";
  private static final String OUTPUT_NAME = "final_result";
  */

  private static final int INPUT_SIZE = 224;
  private static final int IMAGE_MEAN = 128;
  private static final float IMAGE_STD = 128;
  private static final String INPUT_NAME = "input";
  private static final String OUTPUT_NAME = "final_result";

  private static final String MODEL_FILE = "file:///android_asset/graph.pb";
  private static final String LABEL_FILE = "file:///android_asset/labels.txt";

  private static final boolean SAVE_PREVIEW_BITMAP = false;

  private static final boolean MAINTAIN_ASPECT = true;

  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);

  private Classifier classifier;

  private int frameNumber = 1;

  private Integer sensorOrientation;

  private int previewWidth = 0;
  private int previewHeight = 0;
  private byte[][] yuvBytes;
  private int[] rgbBytes = null;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;

  private Bitmap cropCopyBitmap;

  private boolean computing = false;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private ResultsView resultsView;

  private BorderedText borderedText;

  private long lastProcessingTimeMs;

  public Button btnNO;
  public Button btnYES;

  @Override
  protected int getLayoutId() {
    return R.layout.camera_connection_fragment;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  private static final float TEXT_SIZE_DIP = 10;

  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    classifier =
        TensorFlowImageClassifier.create(
            getAssets(),
            MODEL_FILE,
            LABEL_FILE,
            INPUT_SIZE,
            IMAGE_MEAN,
            IMAGE_STD,
            INPUT_NAME,
            OUTPUT_NAME);

    resultsView = (ResultsView) findViewById(R.id.results);
    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    final Display display = getWindowManager().getDefaultDisplay();
    final int screenOrientation = display.getRotation();

    LOGGER.i("Sensor orientation: %d, Screen orientation: %d", rotation, screenOrientation);

    sensorOrientation = rotation + screenOrientation;

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbBytes = new int[previewWidth * previewHeight];
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            INPUT_SIZE, INPUT_SIZE,
            sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    yuvBytes = new byte[3][];

    addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            renderDebug(canvas);
          }
        });
  }

  @Override
  public void onImageAvailable(final ImageReader reader) {
    Image image = null;

    try {
      image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (computing) {
        image.close();
        return;
      }
      computing = true;

      Trace.beginSection("imageAvailable");

      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);

      final int yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();
      ImageUtils.convertYUV420ToARGB8888(
          yuvBytes[0],
          yuvBytes[1],
          yuvBytes[2],
          previewWidth,
          previewHeight,
          yRowStride,
          uvRowStride,
          uvPixelStride,
          rgbBytes);

      image.close();
    } catch (final Exception e) {
      if (image != null) {
        image.close();
      }
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }

    rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
        new Runnable() {
          @Override
          public void run() {
            final long startTime = SystemClock.uptimeMillis();
            final List<Classifier.Recognition> results = classifier.recognizeImage(croppedBitmap);
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            resultsView.setResults(results);
            requestRender();
            computing = false;

            if (frameNumber == 1){
              frameNumber = 0;
              try {
                //------------- CAM ACTION CALLED HERE -----------
                frameNumber = 0;
                CamAction(results, cropCopyBitmap);
              } catch (FileNotFoundException e) {
                e.printStackTrace();
              }
            }
            else
              frameNumber = 1;
          }
        });

    Trace.endSection();
  }

  @Override
  public void onSetDebug(boolean debug) {
    classifier.enableStatLogging(debug);
  }

  private void renderDebug(final Canvas canvas) {
    if (!isDebug()) {
      return;
    }
    final Bitmap copy = cropCopyBitmap;
    if (copy != null) {
      final Matrix matrix = new Matrix();
      final float scaleFactor = 2;
      matrix.postScale(scaleFactor, scaleFactor);
      matrix.postTranslate(
          canvas.getWidth() - copy.getWidth() * scaleFactor,
          canvas.getHeight() - copy.getHeight() * scaleFactor);
      canvas.drawBitmap(copy, matrix, new Paint());

      final Vector<String> lines = new Vector<String>();
      if (classifier != null) {
        String statString = classifier.getStatString();
        String[] statLines = statString.split("\n");
        for (String line : statLines) {
          lines.add(line);
        }
      }

      lines.add("Frame: " + previewWidth + "x" + previewHeight);
      lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
      lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
      lines.add("Rotation: " + sensorOrientation);
      lines.add("Inference time: " + lastProcessingTimeMs + "ms");

      borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
    }
  }

  private void CamAction(final List<Classifier.Recognition> results, Bitmap bitmap) throws FileNotFoundException {
    if(help.getText().equals("Return"))
      return;

    CameraConnectionFragment CA = (CameraConnectionFragment) getFragmentManager().findFragmentById(R.id.container);
    if(savePhoto){
      CA.takePhoto(bitmap);
      Toast.makeText(this, "Photo saved successfully!", Toast.LENGTH_SHORT).show();
      savePhoto = false;
    }

    for (final Classifier.Recognition recog : results) {

      if (this.popupWindowOn) {

        if (recog.getTitle().contentEquals("thumbs down")) {
          if (recog.getConfidence() > 0.3) {
            pattern = pattern * 10 + 1;
            btnNO.performClick();
          }
          return;
        }
        if (recog.getTitle().contentEquals("thumbs up")) {
          if (recog.getConfidence() > 0.7) {
            pattern = pattern * 10 + 2;
            btnYES.performClick();
            Toast.makeText(this, "File deleted successfully!", Toast.LENGTH_SHORT).show();
          }
          return;
        }
      } else {
        switch (recog.getTitle()) {

          case "l sign":
            if (recog.getConfidence() > 0.8) {
              pattern = pattern * 10 + 0;
              rotateImage();
            }
            break;

          case "palm":
            if (recog.getConfidence() > 0.7) {
              pattern = pattern * 10 + 3;
              removePicture();
            }
            break;

          case "none":
            Toast.makeText(this, "Perform Gesture!", Toast.LENGTH_SHORT).show();
            break;

          case "fist":
            pattern = pattern * 10 + 4;
            if (this.imageOn) return;

            if (recog.getConfidence() > 0.8) {
              savePhoto = true;
              try {
                Toast.makeText(this, "Smile!", Toast.LENGTH_SHORT).show();
                TimeUnit.SECONDS.sleep(2);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
            }
            break;

          case "pinch":
            if(imageOn)
              return;

            if (recog.getConfidence() > 0.5) {
              pattern = pattern * 10 + 5;
              onResume_just_ran = true;
              seePicture();
            }
            break;

          case "point right":
            if (recog.getConfidence() > 0.55) {
              pattern = pattern * 10 + 6;
              navigatePictures(0);//Forwards
            }
            break;

          case "point left":
            if (recog.getConfidence() > 0.65) {
              pattern = pattern * 10 + 7;
              navigatePictures(1); //Backwards
            }
            break;

          case "scissors":
            if (recog.getConfidence() > 0.4 && !popupWindowOn) {
              pattern = pattern * 10 + 8;
              deleteRequest();
            }
            break;
        }
      }
    }
    System.out.println(pattern);
    if (pattern > 1000)
      pattern %= 1000;
  }

  public void deleteRequest() {
    if (!this.imageOn) return;

    popupWindowOn = true;

    LayoutInflater layoutInflater
            = (LayoutInflater) getBaseContext()
            .getSystemService(LAYOUT_INFLATER_SERVICE);
    View popupView = layoutInflater.inflate(R.layout.pop_layout, null);
    final PopupWindow popupWindow = new PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);

    btnNO = (Button) popupView.findViewById(R.id.NO);
    btnNO.setOnClickListener(new Button.OnClickListener() {

      @Override
      public void onClick(View v) {
        if(popupWindow.isShowing()){
          popupWindow.dismiss();
          popupWindowOn = false;
        }
        System.out.println(popupWindowOn + " no");
        try {
          TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    });

    btnYES = (Button) popupView.findViewById(R.id.YES);
    btnYES.setOnClickListener(new Button.OnClickListener() {

      @Override
      public void onClick(View v) {
        if(popupWindow.isShowing()){
          popupWindow.dismiss();
          popupWindowOn = false;
        }
        System.out.println(popupWindowOn + " yes");
        deletePicture();
        try {
          TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    });
    popupWindow.showAtLocation(popupView, Gravity.CENTER, -220, -40);

  }

}