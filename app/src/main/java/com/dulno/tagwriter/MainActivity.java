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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import net.bplearning.ntag424.DnaCommunicator;
import net.bplearning.ntag424.constants.Ntag424;
import net.bplearning.ntag424.encryptionmode.AESEncryptionMode;
import net.bplearning.ntag424.encryptionmode.LRPEncryptionMode;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import static net.bplearning.ntag424.CommandResult.PERMISSION_DENIED;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY0;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
  private DnaCommunicator dnaC = new DnaCommunicator();
  private NfcAdapter mNfcAdapter;
  private IsoDep isoDep;
  private byte[] tagIdByte;
  private String qrCodeScan = "";
  private AlertDialog previousDialog;

  private final ActivityResultLauncher<ScanOptions> launcher = registerForActivityResult(
    new ScanContract(),
    result -> {
      if(result.getContents() != null) {
        qrCodeScan = result.getContents();
        alert("Scan was successful", "Now put the phone to the tag you want to set up");
      }
    }
  );

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    EdgeToEdge.enable(this);
    setContentView(R.layout.activity_main);

    mNfcAdapter = NfcAdapter.getDefaultAdapter(this);

    findViewById(R.id.btn_scan).setOnClickListener(v -> startScan());
  }

  @Override
  public void onTagDiscovered(Tag tag) {
    if (qrCodeScan.isEmpty()) {
      alert("No QR-Code scanned",
        "You must first scan a QR code from the panel to be able to describe a new tag", true);
      return;
    }
    isoDep = null;
    try {
      isoDep = IsoDep.get(tag);
      if (isoDep != null) {
        isoDep.connect();
        if (!isoDep.isConnected()) {
          alert("ERROR", "Could not connect to the tag, aborted", true);
          isoDep.close();
          return;
        }
        tagIdByte = tag.getId();
        runWorker();
      }
    } catch (Exception exception) {
      alert("ERROR", exception.getMessage(), true);
      exception.printStackTrace();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (mNfcAdapter != null) {
      Bundle options = new Bundle();
      options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
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
            alert("ERROR", "Please tap a tag before running any tests, aborted", true);
            return;
          }
          dnaC.beginCommunication();

          boolean isLrpAuthenticationMode = false;

          success = AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
          if (!success) {
            if (dnaC.getLastCommandResult().status2 == PERMISSION_DENIED) {
              success = LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, Ntag424.FACTORY_KEY);
              if (success) {
                isLrpAuthenticationMode = true;
              } else {
                alert("ERROR", "Authentication not possible, Operation aborted", true);
                return;
              }
            } else {
              alert("ERROR", "Authentication not possible, Operation aborted", true);
              return;
            }
          }

          var content = new JSONObject(qrCodeScan);

          var url = new URL("https://pub.dulno.dev/v1/stamp/setup/complete/");
          var connection = (HttpURLConnection) url.openConnection();
          connection.setRequestMethod("POST");
          connection.setRequestProperty("Content-Type", "application/json");
          connection.setDoOutput(true);
          var body = new JSONObject(Map.of("stamp", content.getString("stamp"),
            "token", content.getString("token"), "masterKey",
            content.getString("currentMasterKey"), "uid", bytesToHex(tagIdByte))).toString();
          try (OutputStream os = connection.getOutputStream()) {
            byte[] input = body.getBytes("utf-8");
            os.write(input, 0, input.length);
          }
          connection.getResponseCode();
          qrCodeScan = "";
          alert("Success", "You have successfully set up the tag");
          vibrate(500, 100);
        } catch (Exception exception) {
          alert("ERROR", exception.getMessage(), true);
        }
      }
    });
    worker.start();
  }

  private void alert(String title, String message, boolean vibrate) {
    alert(title, message);
    if (vibrate) {
      vibrate(100, 200);
    }
  }

  private void alert(String title, String message) {
    runOnUiThread(() -> {
      if (previousDialog != null) {
        previousDialog.dismiss();
      }
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setTitle(title);
      if (!message.isEmpty()) {
        builder.setMessage(message);
      }
      builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
      previousDialog = builder.show();
    });
  }

  private String bytesToHex(byte[] bytes) {
    if (bytes == null) return "";
    StringBuffer result = new StringBuffer();
    for (byte b : bytes)
      result.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
    return result.toString();
  }

  private void vibrate(long milliseconds, int amplitude) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(
        VibrationEffect.createOneShot(milliseconds, amplitude));
    } else {
      Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
      v.vibrate(milliseconds);
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
