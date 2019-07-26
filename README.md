## EasyRTSPLive-Android介绍 ##

EasyRTSPLive-Android是由[TSINGSEE青犀开放平台](http://open.tsingsee.com "TSINGSEE青犀开放平台")开发和维护的一个完善的行业视频接入网关，拉流IPC摄像机或者NVR硬盘录像机RTSP流转成RTMP推送到阿里云CDN/腾讯云CDN/RTMP流媒体服务器，暂时支持拉取一路RTSP流并以RTMP协议推送发布。

## 工程结构 Project structure ##
	EasyPlayer_Android
	|-EasyPlayer            APP module
	|-library               library module

## 功能特点 ##

- [x] 超低延迟的rtsp播放器；
- [x] 完美支持多窗口多实例播放；
- [x] 支持RTSP TCP/UDP模式切换；
- [x] 支持播放端，buffer设置；
- [x] 秒开播放；
- [x] 支持自定义播放布局;
- [x] 编解码、显示、播放源码全开放，更加灵活;
- [x] 支持播放过程中，'实时静音/取消静音';
- [x] 高效的延时追帧策略；
- [x] [快照]支持播放过程中，**随时快照**；
- [x] [录像]支持播放过程中，**随时录像**；

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

## 技术支持 ##
- 邮件：[support@easydarwin.org](mailto:support@easydarwin.org) 

- QQ交流群：<a href="http://jq.qq.com/?_wv=1027&k=2IDkJId" target="_blank" title="EasyPlayer">**544917793**</a>

> EasyRTSPLive-Android是一款非常稳定的RTSP协议转RTMP协议的行业视频接入网关，全平台支持（包括Windows/Linux 32&64，ARM各种平台，Android，iOS），是技术研发快速迭代的工具，也是安防运维人员进行现场问题排查的得力帮手！各平台版本需要经过授权才能商业使用，商业授权方案可以通过以上渠道进行更深入的技术与合作咨询。

## 获取更多信息 ##
TSINGSEE青犀开放平台：[http://open.tsingsee.com](http://open.tsingsee.com "TSINGSEE青犀开放平台")

Copyright &copy; TSINGSEE.com 2012~2019
