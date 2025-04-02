package com.dulno.tagwriter;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;
import net.bplearning.ntag424.DnaCommunicator;
import net.bplearning.ntag424.command.ChangeKey;
import net.bplearning.ntag424.command.GetKeyVersion;
import net.bplearning.ntag424.command.SetCapabilities;
import net.bplearning.ntag424.constants.Permissions;
import net.bplearning.ntag424.encryptionmode.AESEncryptionMode;
import net.bplearning.ntag424.encryptionmode.LRPEncryptionMode;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Map;

import static net.bplearning.ntag424.CommandResult.PERMISSION_DENIED;
import static net.bplearning.ntag424.constants.Permissions.ACCESS_KEY0;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
  private enum Environment {
    PRODUCTION,
    STAGING;

    public boolean isProduction() {
      return this == PRODUCTION;
    }

    public boolean isStaging() {
      return this == STAGING;
    }
  }

  private final Environment environment = Environment.STAGING;
  private DnaCommunicator dnaC = new DnaCommunicator();
  private NfcAdapter mNfcAdapter;
  private IsoDep isoDep;
  private byte[] tagIdByte;
  private JSONObject qrCodeContent;
  private AlertDialog previousDialog;
  private final ActivityResultLauncher<ScanOptions> launcher =
    registerForActivityResult(new ScanContract(), this::processScanResult);

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    EdgeToEdge.enable(this);
    setContentView(R.layout.activity_main);
    mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
    findViewById(R.id.btn_scan).setOnClickListener(v -> startScan());
  }

  private void startScan() {
    var options = new ScanOptions();
    options.setOrientationLocked(false);
    options.setPrompt("");
    options.setBeepEnabled(false);
    options.setCaptureActivity(CaptureActivity.class);
    launcher.launch(options);
  }

  private void processScanResult(ScanIntentResult result) {
    if(result.getContents() == null) {
      return;
    }
    try {
      qrCodeContent = new JSONObject(result.getContents());
      alert("Scan was successful", "Now put the phone to the tag you want to set up");
    } catch (Exception exception) {
      alert("ERROR", "An error has occurred while scanning the qr code. Did you really scan the code from the panel?");
    }
  }

  @Override
  public void onTagDiscovered(Tag tag) {
    if (qrCodeContent == null) {
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
      var options = new Bundle();
      options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
      mNfcAdapter.enableReaderMode(this,
        this,
        NfcAdapter.FLAG_READER_NFC_A |
          NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK |
          NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
        options);
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (mNfcAdapter != null) {
      mNfcAdapter.disableReaderMode(this);
    }
  }

  private void runWorker() {
    var worker = new Thread(() -> {
      try {
        dnaC = new DnaCommunicator();
        try {
          dnaC.setTransceiver((bytesToSend) -> isoDep.transceive(bytesToSend));
        } catch (NullPointerException npe) {
          alert("ERROR", "Please tap a tag before running any tests, aborted", true);
          return;
        }
        dnaC.beginCommunication();
        var currentMasterKey = hexToBytes(qrCodeContent.getString("currentMasterKey"));
        if (!authenticate(currentMasterKey)) {
          alert("ERROR", "Authentication with the tag has failed. Presumably the key from our system is not the most up-to-date of the tag", true);
          return;
        }
        var newMasterKey = updateMasterKey(currentMasterKey);
        storeNewMasterKey(newMasterKey);
        if (!authenticate(newMasterKey)) {
          alert("ERROR", "Authentication failed when activating LRP. This is extremely unusual. Please contact the developers", true);
          return;
        }
        enableLRP();
        sendSetupResponse(newMasterKey);
        qrCodeContent = null;
        alert("Success", "You have successfully set up the tag");
        vibrate(500, 100);
      } catch (Exception exception) {
        alert("ERROR", exception.getMessage(), true);
        exception.printStackTrace();
      }
    });
    worker.start();
  }

  private boolean authenticate(byte[] currentMasterKey) throws Exception {
    if (AESEncryptionMode.authenticateEV2(dnaC, ACCESS_KEY0, currentMasterKey)) {
      return true;
    }
    if (dnaC.getLastCommandResult().status2 != PERMISSION_DENIED) {
      return false;
    }
    return LRPEncryptionMode.authenticateLRP(dnaC, ACCESS_KEY0, currentMasterKey);
  }

  private byte[] updateMasterKey(byte[] currentMasterKey) throws Exception {
    var newMasterKey = new byte[16];
    var random = new SecureRandom();
    random.nextBytes(newMasterKey);
    ChangeKey.run(dnaC, ACCESS_KEY0, currentMasterKey, newMasterKey,
      GetKeyVersion.run(dnaC, Permissions.ACCESS_KEY0));
    return newMasterKey;
  }

  private void storeNewMasterKey(byte[] newMasterKey) throws Exception {
    var file = new File(getFilesDir(), "dulno_tagwriter_log.txt");
    try (var writer = new FileWriter(file, true)) {
      writer.append(System.currentTimeMillis() + "/" +
        qrCodeContent.getString("stamp") + "/" + bytesToHex(tagIdByte) + ": " +
        bytesToHex(newMasterKey) + "\n");
    }
  }

  private void enableLRP() throws Exception {
    SetCapabilities.run(dnaC, true);
  }

  private static final String SETUP_RESPONSE_URL = "https://%s/v1/stamp/setup/complete/";

  private void sendSetupResponse(byte[] newMasterKey) throws Exception {
    var url = new URL(String.format(SETUP_RESPONSE_URL,
      environment.isProduction() ? "team.dulno.com" : "pub.dulno.dev"));
    var connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true);
    var content = Map.of("stamp", qrCodeContent.getString("stamp"), "token",
      qrCodeContent.getString("token"), "masterKey", bytesToHex(newMasterKey),
      "uid", bytesToHex(tagIdByte));
    var body = new JSONObject(content).toString();
    try (var os = connection.getOutputStream()) {
      var input = body.getBytes("utf-8");
      os.write(input, 0, input.length);
    }
    connection.getResponseCode();
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
      var builder = new AlertDialog.Builder(this);
      builder.setTitle(title);
      if (!message.isEmpty()) {
        builder.setMessage(message);
      }
      builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
      previousDialog = builder.show();
    });
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

  private String bytesToHex(byte[] bytes) {
    if (bytes == null) {
      return "";
    }
    var result = new StringBuffer();
    for (var b : bytes) {
      result.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
    }
    return result.toString();
  }

  private byte[] hexToBytes(String s) {
    try {
      int len = s.length();
      byte[] data = new byte[len / 2];
      for (int i = 0; i < len; i += 2) {
        data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
          + Character.digit(s.charAt(i + 1), 16));
      }
      return data;
    } catch (Exception exception) {
      return null;
    }
  }
}
