package org.easydarwin.easyrtsplive.activity;

import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import org.easydarwin.easyrtmp.push.EasyRTMP;
import org.easydarwin.easyrtmp.push.InitCallback;
import org.easydarwin.easyrtmp.push.Pusher;
import org.easydarwin.easyrtsplive.BuildConfig;
import org.easydarwin.easyrtsplive.R;
import org.easydarwin.easyrtsplive.data.VideoSource;
import org.easydarwin.easyrtsplive.databinding.ActivityPushBinding;
import org.easydarwin.video.Client;
import org.easydarwin.video.EasyPlayerClient;

import java.text.SimpleDateFormat;
import java.util.Date;

public class PushActivity extends AppCompatActivity {
    private ActivityPushBinding mBinding;

    private String rtsp_url;
    private String rtmp_url;
    private int transportMode;
    private int sendOption;

    protected EasyPlayerClient mStreamRender;
    protected ResultReceiver mResultReceiver;
    protected Pusher mPusher;

    public static final String RTSP_KEY = BuildConfig.RTSP_KEY;
    public static final String RTMP_KEY = BuildConfig.RTMP_KEY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }

        rtsp_url = getIntent().getStringExtra("rtsp_url");
        rtmp_url = getIntent().getStringExtra("rtmp_url");
        transportMode = getIntent().getIntExtra(VideoSource.TRANSPORT_MODE, 0);
        sendOption = getIntent().getIntExtra(VideoSource.SEND_OPTION, 0);

        if (TextUtils.isEmpty(rtsp_url)) {
            finish();
            return;
        }

        // 屏幕保持不暗不关闭
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_push);

        mBinding.toolbarBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 实现TextView的滑动
        mBinding.msgTxt.setMovementMethod(new ScrollingMovementMethod());

        mResultReceiver = new ResultReceiver(new Handler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);
                switch (resultCode) {
                    case EasyPlayerClient.RESULT_VIDEO_SIZE:
                        int width = resultData.getInt(EasyPlayerClient.EXTRA_VIDEO_WIDTH);
                        int height = resultData.getInt(EasyPlayerClient.EXTRA_VIDEO_HEIGHT);
                        PushActivity.this.onEvent(String.format("Video Size: %d x %d", width, height));
                        break;
                    case EasyPlayerClient.RESULT_UNSUPPORTED_AUDIO:
                        new AlertDialog.Builder(PushActivity.this).setMessage("音频格式不支持").setTitle("SORRY").setPositiveButton(android.R.string.ok, null).show();
                        break;
                    case EasyPlayerClient.RESULT_UNSUPPORTED_VIDEO:
                        new AlertDialog.Builder(PushActivity.this).setMessage("视频格式不支持").setTitle("SORRY").setPositiveButton(android.R.string.ok, null).show();
                        break;
                    case EasyPlayerClient.RESULT_EVENT:
                        PushActivity.this.onEvent(resultData.getString("event-msg"));
                        break;
                }
            }
        };

        startRending();
    }

    public void onEvent(String msg) {
        mBinding.msgTxt.append(String.format("[%s]\t%s\n",new SimpleDateFormat("HH:mm:ss").format(new Date()),msg));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // 开始渲染
    protected void startRending() {
        mStreamRender = new EasyPlayerClient(this, RTSP_KEY, null, mResultReceiver);

        mPusher = new EasyRTMP(EasyRTMP.VIDEO_CODEC_H264);
        mStreamRender.setRTMPInfo(mPusher, rtmp_url, RTMP_KEY, new InitCallback() {
            @Override
            public void onCallback(int code) {
                Bundle resultData = new Bundle();
                switch (code) {
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_ACTIVATE_INVALID_KEY:
                        resultData.putString("event-msg", "EasyRTMP 无效Key");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_ACTIVATE_SUCCESS:
                        resultData.putString("event-msg", "EasyRTMP 激活成功");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_CONNECTING:
                        resultData.putString("event-msg", "EasyRTMP 连接中");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_CONNECTED:
                        resultData.putString("event-msg", "EasyRTMP 连接成功");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_CONNECT_FAILED:
                        resultData.putString("event-msg", "EasyRTMP 连接失败");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_CONNECT_ABORT:
                        resultData.putString("event-msg", "EasyRTMP 连接异常中断");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_PUSHING:
                        resultData.putString("event-msg", "EasyRTMP 推流中");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_DISCONNECTED:
                        resultData.putString("event-msg", "EasyRTMP 断开连接");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_ACTIVATE_PLATFORM_ERR:
                        resultData.putString("event-msg", "EasyRTMP 平台不匹配");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_ACTIVATE_COMPANY_ID_LEN_ERR:
                        resultData.putString("event-msg", "EasyRTMP 断授权使用商不匹配");
                        break;
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_ACTIVATE_PROCESS_NAME_LEN_ERR:
                        resultData.putString("event-msg", "EasyRTMP 进程名称长度不匹配");
                        break;
                }

                mResultReceiver.send(EasyPlayerClient.RESULT_EVENT, resultData);
            }
        });

        try {
            mStreamRender.start(rtsp_url,
                    transportMode < 2 ? Client.TRANSTYPE_TCP : Client.TRANSTYPE_UDP,
                    sendOption,
                    Client.EASY_SDK_VIDEO_FRAME_FLAG | Client.EASY_SDK_AUDIO_FRAME_FLAG,
                    "",
                    "",
                    null);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
    }
}
