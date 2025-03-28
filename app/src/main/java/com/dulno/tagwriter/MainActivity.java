package com.dulno.tagwriter;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import net.bplearning.ntag424.DnaCommunicator;
import net.bplearning.ntag424.constants.Ntag424;
import net.bplearning.ntag424.encryptionmode.AESEncryptionMode;
import net.bplearning.ntag424.encryptionmode.LRPEncryptionMode;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import static net.bplearning.ntag424.CommandResult.PERMISSION_DENIED;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY0;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
  private DnaCommunicator dnaC = new DnaCommunicator();
  private com.google.android.material.textfield.TextInputEditText output;
  private NfcAdapter mNfcAdapter;
  private IsoDep isoDep;
  private byte[] tagIdByte;
  private String qrCodeScan = "";

  private final ActivityResultLauncher<ScanOptions> launcher = registerForActivityResult(
    new ScanContract(),
    result -> {
      if(result.getContents() != null) {
        qrCodeScan = result.getContents();
        Toast.makeText(this, result.getContents(), Toast.LENGTH_LONG).show();
      }
    }
  );

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    EdgeToEdge.enable(this);
    setContentView(R.layout.activity_main);

    output = findViewById(R.id.etOutput);

    mNfcAdapter = NfcAdapter.getDefaultAdapter(this);

    findViewById(R.id.btn_scan).setOnClickListener(v -> startScan());
  }

  private void writeToUiAppend(TextView textView, String message) {
    runOnUiThread(() -> {
      String oldString = textView.getText().toString();
      if (TextUtils.isEmpty(oldString)) {
        textView.setText(message);
      } else {
        String newString = message + "\n" + oldString;
        textView.setText(newString);
        System.out.println(message);
      }
    });
  }

  @Override
  public void onTagDiscovered(Tag tag) {

    if (qrCodeScan.isEmpty()) {
      return;
    }

    writeToUiAppend(output, "NFC tag discovered");

    isoDep = null;
    try {
      isoDep = IsoDep.get(tag);
      if (isoDep != null) {
        // Make a Vibration
        vibrateShort();

        runOnUiThread(() -> {
          output.setText("");
        });

        isoDep.connect();
        if (!isoDep.isConnected()) {
          writeToUiAppend(output, "Could not connect to the tag, aborted");
          isoDep.close();
          return;
        }

        writeToUiAppend(output, "NFC tag connected");

        runWorker();
      }

    } catch (IOException e) {
      writeToUiAppend(output, "ERROR: IOException " + e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      writeToUiAppend(output, "ERROR: Exception " + e.getMessage());
      e.printStackTrace();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (mNfcAdapter != null) {

      Bundle options = new Bundle();
      // Work around for some broken Nfc firmware implementations that poll the card too fast
      options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);

      // Enable ReaderMode for NFC A card type and disable platform sounds
      // the option NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK is set
      // so the reader won't try to get a NDEF message
      mNfcAdapter.enableReaderMode(this,
        this,
        NfcAdapter.FLAG_READER_NFC_A |
          NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK |
          NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
        options);
    }
  }

    /*
        private void runWorker() {
        Log.d(TAG, "Change Master Key Worker");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    dnaC = new DnaCommunicator();
                    try {
                        dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
                    } catch (NullPointerException npe) {
                        writeToUiAppend(output, "Please tap a tag before running any tests, aborted");
                        return;
                    }
                    dnaC.setLogger((info) -> Log.d(TAG, "Communicator: " + info));
                    dnaC.beginCommunication();

                    // Authenticate with Master Key (Key 00h)
                    success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
                    if (!success) {
                        writeToUiAppend(output, "Master Key Authentication FAILURE");
                        return;
                    }
                    writeToUiAppend(output, "Master Key Authentication SUCCESS");

                    // Change Master Key
                    byte[] newMasterKey = Utils.hexStringToByteArray("00000000000000000000000000000000");

                    try {
                        int appKeyVersion = GetKeyVersion.run(dnaC, Permissions.ACCESS_KEY0);
                        ChangeKey.run(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY, newMasterKey, appKeyVersion);
                    } catch (IOException e) {
                        Log.e(TAG, "ChangeKey IOException: " + e.getMessage());
                        writeToUiAppend(output, "Change Master Key Error, Operation aborted");
                        return;
                    }
                    writeToUiAppend(output, "Master Key Change SUCCESS");
                    try {
                        SetCapabilities.run(dnaC, true);
                    } catch (IOException e) {
                        Log.e(TAG, "ChangeKey IOException: " + e.getMessage());
                        writeToUiAppend(output, "LRP Error, Operation aborted");
                        return;
                    }
                    writeToUiAppend(output, "LRP Enabled");
                } catch (IOException e) {
                    Log.e(TAG, "Exception: " + e.getMessage());
                    writeToUiAppend(output, "Exception: " + e.getMessage());
                }
                writeToUiAppend(output, "== FINISHED ==");
                vibrateShort();
            }
        });
        worker.start();
    }
     */
    @Override
    protected void onPause() {
      super.onPause();
      if (mNfcAdapter != null) {
        mNfcAdapter.disableReaderMode(this);
      }
    }

  private void runWorker() {
    Thread worker = new Thread(new Runnable() {
      @Override
      public void run() {
        boolean success = false;
        try {
          dnaC = new DnaCommunicator();
          try {
            dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
          } catch (NullPointerException npe) {
            writeToUiAppend(output, "Please tap a tag before running any tests, aborted");
            return;
          }
          dnaC.beginCommunication();

          /**
           * These steps are running - assuming that all keys are 'default' keys filled with 16 00h values
           * 1) Authenticate with Application Key 00h in AES mode
           * 2) If the authentication in AES mode fails try to authenticate in LRP mode
           * 3) Get the real tag UID by calling GetCardUid
           * 4) Write an URL template to file 02 with PICC (Uid and/or Counter) plus CMAC
           * 5) Get existing file settings for file 02
           * 6) Save the modified file settings back to the tag, using the key derivation
           */

          // authentication
          boolean isLrpAuthenticationMode = false;

          success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
          if (success) {
            writeToUiAppend(output, "AES Authentication SUCCESS");
          } else {
            // if the returnCode is '919d' = permission denied the tag is in LRP mode authentication
            if (dnaC.getLastCommandResult().status2 == PERMISSION_DENIED) {
              // try to run the LRP authentication
              success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
              if (success) {
                writeToUiAppend(output, "LRP Authentication SUCCESS");
                isLrpAuthenticationMode = true;
              } else {
                writeToUiAppend(output, "LRP Authentication FAILURE");
                writeToUiAppend(output, "Authentication not possible, Operation aborted");
                return;
              }
            } else {
              // any other error, print the error code and return
              writeToUiAppend(output, "AES Authentication FAILURE");
              return;
            }
          }

          var content = new JSONObject(qrCodeScan);

          var url = new URL("https://pub.dulno.dev/v1/stamp/setup/complete/");
          var conn = (HttpURLConnection) url.openConnection();
          conn.setRequestMethod("POST");
          conn.setRequestProperty("Content-Type", "application/json");
          conn.setDoOutput(true);
          String jsonInput = new JSONObject(Map.of("stamp", content.getString("stamp"),
            "token", content.getString("token"), "masterKey",
            content.getString("currentMasterKey"), "uid", bytesToHex(tagIdByte))).toString();
          writeToUiAppend(output, jsonInput);
          try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonInput.getBytes("utf-8");
            os.write(input, 0, input.length);
          }
          int responseCode = conn.getResponseCode();
          writeToUiAppend(output, responseCode + "");
        } catch (Exception e) {
          writeToUiAppend(output, "Exception: " + e.getMessage());
        }
        writeToUiAppend(output, "== FINISHED ==");
        vibrateShort();
      }
    });
    worker.start();
  }

  private String bytesToHex(byte[] bytes) {
    if (bytes == null) return "";
    StringBuffer result = new StringBuffer();
    for (byte b : bytes)
      result.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
    return result.toString();
  }

  private void vibrateShort() {
    // Make a Sound
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(500, 100));
    } else {
      Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
      v.vibrate(50);
    }
  }

  private void startScan() {
    ScanOptions options = new ScanOptions();
    options.setOrientationLocked(false);
    options.setPrompt("");
    options.setBeepEnabled(false);
    options.setCaptureActivity(CaptureActivity.class);
    launcher.launch(options);
  }
}
