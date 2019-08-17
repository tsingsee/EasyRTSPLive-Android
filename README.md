## EasyRTSPLive-Android介绍 ##
EasyRTSPLive-Android是由[TSINGSEE青犀开放平台](http://open.tsingsee.com "TSINGSEE青犀开放平台")开发和维护的一个完善的行业视频接入网关，拉流IPC摄像机或者NVR硬盘录像机RTSP流转成RTMP推送到阿里云CDN/腾讯云CDN/RTMP流媒体服务器， EasyRTSPLive-Android是一款非常稳定的RTSP协议转RTMP协议的行业视频接入网关，全平台支持（包括Windows/Linux 32&64，ARM各种平台，Android，iOS），是技术研发快速迭代的工具，也是安防运维人员进行现场问题排查的得力帮手！

## 工程结构 Project structure ##
	EasyRTSPLive-Android
	|-EasyPlayer            APP module
	|-library               library module

## 编译及运行 ##
- Android：Android Studio编译。
- EasyPlayer的build.gradle配置授权key
- PlayListActivity可以添加RTSP拉流和RTMP推流的地址
- EasyPlayerClient设置流地址并启动

**注意：本SDK是基于Android Studio3.4.1开发，请及时更新您的IDE**

## 直接试用

https://fir.im/EasyRTSPLive

![EasyRTSPLive-Android](https://github.com/tsingsee/images/blob/master/EasyRTSPLive/fir.easyrtsplive.android.png?raw=true)

## 获取更多信息 ##
TSINGSEE青犀开放平台：[http://open.tsingsee.com](http://open.tsingsee.com "TSINGSEE青犀开放平台")

Copyright &copy; TSINGSEE.com 2012~2019
