package com.opendashcam;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import android.text.TextUtils;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


public class MainActivity extends Activity {

    public static final int MULTIPLE_PERMISSIONS_RESPONSE_CODE = 10;
    private static final int CODE_REQUEST_PERMISSION_TO_MUTE_SYSTEM_SOUND = 10001;
    private static final int CODE_REQUEST_PERMISSION_DRAW_OVER_APPS = 10002;

    String[] permissions = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case CODE_REQUEST_PERMISSION_TO_MUTE_SYSTEM_SOUND:
                //if user has not allowed this permission close the app, otherwise continue
                if (isPermissionToMuteSystemSoundGranted()) {
                    init();
                } else {
                    finish();
                }
                break;
            case CODE_REQUEST_PERMISSION_DRAW_OVER_APPS:
                //if user has not allowed this permission close the app, otherwise continue
                if (Settings.canDrawOverlays(this)) {
                    init();
                } else {
                    finish();
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void init() {
        // Check permissions to draw over apps
        if (!checkDrawPermission()) return;

        //@dmitriy.chernysh:
        //check permission to mute system audio on Android 7 (AudioManager setStreamVolume)
        //java.lang.SecurityException: Not allowed to change Do Not Disturb state
        if (!checkPermissionToMuteSystemSound()) return;

        if (checkPermissions()) {
            startApp();
        }
    }

    private void startApp() {

        if (!isEnoughStorage()) {
            Util.showToastLong(this.getApplicationContext(),
                    "Not enough storage to run the app (Need " + String.valueOf(Util.getQuota())
                            + "MB). Clean up space for recordings.");
        } else {
            // Check if first launch => show tutorial
            // Access shared references file
            SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                    getString(R.string.db_first_launch_complete_flag),
                    Context.MODE_PRIVATE);

            String firstLaunchFlag = sharedPref.
                    getString(getString(R.string.db_first_launch_complete_flag),
                            "null");

            if (TextUtils.isEmpty(firstLaunchFlag)) {
                Intent intent = new Intent(getApplicationContext(), WelcomeActivity.class);
                startActivity(intent);
                finish();
                return;
            }

            // Otherwise

            // Launch navigation app, if settings say so
            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
            if (settings.getBoolean("start_maps_in_background", true)) {
                launchNavigation();
            }

            // Start recording video
            Intent videoIntent = new Intent(getApplicationContext(), BackgroundVideoRecorder.class);
            startService(videoIntent);

            // Start rootView service (display the widgets)
            Intent i = new Intent(getApplicationContext(), WidgetService.class);
            startService(i);
        }

        // Close the activity, we don't have an app window
        finish();
    }

    private boolean checkDrawPermission() {
        // for Marshmallow (SDK 23) and newer versions, get overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                /** if not construct intent to request permission */
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                /** request permission via start activity for result */
                startActivityForResult(intent, CODE_REQUEST_PERMISSION_DRAW_OVER_APPS);

                Toast.makeText(MainActivity.this, "Draw over apps permission needed", Toast.LENGTH_LONG)
                        .show();

                Toast.makeText(MainActivity.this, "Allow and click \"Back\"", Toast.LENGTH_LONG)
                        .show();

                Toast.makeText(MainActivity.this, "Then restart the Open Dash Cam app", Toast.LENGTH_LONG)
                        .show();

                return false;
            }
        }
        return true;
    }


    private boolean checkPermissions() {
        int result;
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            result = ActivityCompat.checkSelfPermission(MainActivity.this, p);
            if (result != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]),
                    MULTIPLE_PERMISSIONS_RESPONSE_CODE);
            return false;
        }
        return true;
    }

    /**
     * Check and ask permission to set "Do not Disturb"
     * Note: it uses in BackgroundVideoRecorder : audio.setStreamVolume()
     *
     * @return True - granted
     */
    private boolean checkPermissionToMuteSystemSound() {

        if (!isPermissionToMuteSystemSoundGranted()) {
            Intent intent = new Intent(
                    android.provider.Settings
                            .ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivityForResult(intent, CODE_REQUEST_PERMISSION_TO_MUTE_SYSTEM_SOUND);
            return false;
        }

        return true;
    }

    private boolean isPermissionToMuteSystemSoundGranted() {
        //Android 7+ needs this permission (but Samsung devices may work without it)
        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)) return true;

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return true;

        return notificationManager.isNotificationPolicyAccessGranted();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MULTIPLE_PERMISSIONS_RESPONSE_CODE:{
                if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                    // permissions granted
                    startApp();
                } else {
                    // permissions not granted
                    Toast.makeText(MainActivity.this, "Permissions denied. The app cannot start.", Toast.LENGTH_LONG)
                            .show();

                    Toast.makeText(MainActivity.this, "Please re-start Open Dash Cam app and grant the requested permissions.", Toast.LENGTH_LONG)
                            .show();

                    finish();
                }
                return;
            }
        }
    }

    /**
     * Starts Google Maps in driving mode.
     */
    private void launchNavigation() {
        String googleMapsPackage = "com.google.android.apps.maps";

        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(googleMapsPackage);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("google.navigation:/?free=1&mode=d&entry=fnls"));
            startActivity(intent);
        } catch (Exception e) {
            return;
        }
    }

    private boolean isEnoughStorage(){
        File videosFolder = Util.getVideosDirectoryPath();
        if (videosFolder == null) return false;

        long appVideosFolderSize = Util.getFolderSize(videosFolder);
        long storageFreeSize = Util.getFreeSpaceExternalStorage(videosFolder);
        //check enough space
        if (storageFreeSize + appVideosFolderSize < (Util.getQuota())) {
            return false;
        }else {
            return true;
        }
    }
}
