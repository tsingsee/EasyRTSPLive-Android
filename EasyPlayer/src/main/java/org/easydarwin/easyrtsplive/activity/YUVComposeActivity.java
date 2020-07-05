package org.easydarwin.easyrtsplive.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ResultReceiver;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.WindowManager;

import org.easydarwin.easyrtsplive.R;
import org.easydarwin.easyrtsplive.data.VideoSource;
import org.easydarwin.easyrtsplive.fragments.PlayFragment;
import org.easydarwin.easyrtsplive.fragments.YUVComposeFragment;
import org.easydarwin.easyrtsplive.util.SPUtil;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class YUVComposeActivity extends AppCompatActivity {

    public static final int REQUEST_CAMERA_PERMISSION = 1003;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_yuvcompose);

        Intent intent = getIntent();
        String rtspUrl1 = intent.getStringExtra("rtspUrl1");
        String rtspUrl2 = intent.getStringExtra("rtspUrl2");
        String rtmpUrl = intent.getStringExtra("rtmpUrl");
        int transportMode = getIntent().getIntExtra(VideoSource.TRANSPORT_MODE, 0);
        int sendOption = getIntent().getIntExtra(VideoSource.SEND_OPTION, 0);
        boolean isCamera = intent.getBooleanExtra("isCamera", true);

        YUVComposeFragment fragment = YUVComposeFragment.newInstance(rtmpUrl, rtspUrl1, rtspUrl2,
                transportMode, sendOption, isCamera);
        getSupportFragmentManager().beginTransaction().add(R.id.render_holder, fragment, "first").commit();

        // 动态获取camera和audio权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION);
            return;
        } else {
            // resume
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION: {
                if (grantResults.length > 1
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED) {

                } else {
                    finish();
                }

                break;
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
