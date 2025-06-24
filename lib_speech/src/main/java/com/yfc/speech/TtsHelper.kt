package com.yfc.speech

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.blankj.utilcode.util.ThreadUtils
import com.iflytek.cloud.ErrorCode
import com.iflytek.cloud.InitListener
import com.iflytek.cloud.SpeechConstant
import com.iflytek.cloud.SpeechError
import com.iflytek.cloud.SpeechEvent
import com.iflytek.cloud.SpeechSynthesizer
import com.iflytek.cloud.SynthesizerListener
import java.io.File
import java.io.RandomAccessFile

class TtsHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner? = null,
    autoCreate: Boolean = true,
) {
    companion object {
        private val TAG: String = TtsHelper::class.simpleName ?: ""
    }

    var speaking: Boolean = false

    private var mTts: SpeechSynthesizer? = null // 语音合成对象
    private var mPercentForBuffering = 0 // 缓冲进度
    private var mPercentForPlaying = 0 // 播放进度
    private var mEngineType: String = SpeechConstant.TYPE_CLOUD // 引擎类型
    private var pcmFile: File? = null

    init {
        if (autoCreate) create()
    }

    fun create() {
        runCatching {
            mTts = SpeechSynthesizer.createSynthesizer(context, mTtsInitListener)

            lifecycleOwner?.lifecycle?.addObserver(defaultLifecycleObserver)
        }.onFailure {
            logE(it)
        }
    }

    private fun checkValid(): Boolean {
        if (null == mTts) {
            // 创建单例失败，与 21001 错误为同样原因，参考 http://bbs.xfyun.cn/forum.php?mod=viewthread&tid=9688
            logE("创建对象失败，请确认 libmsc.so 放置正确，且有调用 createUtility 进行初始化")
            return false
        } else {
            return true
        }
    }

    fun openSetting() {
        if (!checkValid()) return

        runCatching {
            if (SpeechConstant.TYPE_CLOUD == mEngineType) {
//            val intent: Intent = Intent(this@TtsDemo, TtsSettings::class.java)
//            startActivity(intent)
            } else {
                logE("请前往xfyun.cn下载离线合成体验")
            }
        }.onFailure {
            logE(it)
        }
    }

    fun startSpeaking(text: String, voicer: String) {
        if (!checkValid()) return
        if (text.isEmpty() || voicer.isEmpty()) return

        runCatching {
            pcmFile = File(context.cacheDir.absolutePath, "tts_pcmFile.pcm")
            pcmFile?.delete()

            // 设置参数
            setParam(voicer)
            // 合成并播放
            val code: Int = mTts!!.startSpeaking(text, mTtsListener)

            //			/**
//			 * 只保存音频不进行播放接口,调用此接口请注释startSpeaking接口
//			 * text:要合成的文本，uri:需要保存的音频全路径，listener:回调接口
//			*/
//                String path = getExternalFilesDir("msc").getAbsolutePath() + "/tts.pcm";
//                //  synthesizeToUri 只保存音频不进行播放
//                int code = mTts.synthesizeToUri(texts, path, mTtsListener);
            if (code != ErrorCode.SUCCESS) {
                logE("语音合成失败,错误码: $code,请点击网址https://www.xfyun.cn/document/error-code查询解决方案")
            } else {
                speaking = true
            }
        }.onFailure {
            logE(it)
        }
    }

    fun stopSpeaking() {
        if (!checkValid()) return
        runCatching {
            mTts?.stopSpeaking()
        }.onFailure {
            logE(it)
        }
    }

    fun pauseSpeaking() {
        if (!checkValid()) return
        runCatching {
            mTts?.pauseSpeaking()
        }.onFailure {
            logE(it)
        }
    }

    fun resume() {
        if (!checkValid()) return
        runCatching {
            mTts?.resumeSpeaking()
        }.onFailure {
            logE(it)
        }
    }

    fun destroy() {
        runCatching {
            mTts?.stopSpeaking()
            mTts?.destroy()
        }.onFailure {
            logE(it)
        }
    }

    /**
     * 初始化监听。
     */
    private val mTtsInitListener = InitListener { code ->
        logE("InitListener init() code = $code")
        if (code != ErrorCode.SUCCESS) {
            logE("初始化失败,错误码：$code,请点击网址https://www.xfyun.cn/document/error-code查询解决方案")
        } else {
            // 初始化成功，之后可以调用startSpeaking方法
            // 注：有的开发者在onCreate方法中创建完合成对象之后马上就调用startSpeaking进行合成，
            // 正确的做法是将onCreate中的startSpeaking调用移至这里
        }
    }

    /**
     * 合成回调监听。
     */
    private val mTtsListener = object : SynthesizerListener {
        override fun onSpeakBegin() {
            speaking = true
            logE("开始播放")
        }

        override fun onSpeakPaused() {
            speaking = false
            logE("暂停播放")
        }

        override fun onSpeakResumed() {
            speaking = true
            logE("继续播放")
        }

        override fun onBufferProgress(percent: Int, beginPos: Int, endPos: Int, info: String?) {
            // 合成进度
            logE("onBufferProgress percent=$percent")
            mPercentForBuffering = percent
            logE("缓冲进度为${mPercentForBuffering}，播放进度为${mPercentForPlaying}")
        }

        override fun onSpeakProgress(percent: Int, beginPos: Int, endPos: Int) {
            // 播放进度
            logE("onSpeakProgress percent=$percent")
            mPercentForPlaying = percent
            logE("缓冲进度为${mPercentForBuffering}，播放进度为${mPercentForPlaying}")
            logE("beginPos = $beginPos  endPos = $endPos")
        }

        override fun onCompleted(error: SpeechError?) {
            logE("播放完成")
            if (error != null) {
                logE(error.getPlainDescription(true))
            }
            speaking = false
        }

        override fun onEvent(eventType: Int, arg1: Int, arg2: Int, obj: Bundle?) {
            //	 以下代码用于获取与云端的会话id，当业务出错时将会话id提供给技术支持人员，可用于查询会话日志，定位出错原因
            //	 若使用本地能力，会话id为null
            if (SpeechEvent.EVENT_SESSION_ID == eventType) {
                val sid = obj?.getString(SpeechEvent.KEY_EVENT_SESSION_ID)
                logE("onEvent session id =$sid")
            }
            // 当设置 SpeechConstant.TTS_DATA_NOTIFY 为1时，抛出buf数据
            if (SpeechEvent.EVENT_TTS_BUFFER == eventType) {
                val buf = obj?.getByteArray(SpeechEvent.KEY_EVENT_TTS_BUFFER)
                logE("onEvent EVENT_TTS_BUFFER = " + buf?.size)

                if (pcmFile != null && buf != null) {
                    appendFile(pcmFile!!, buf) // 保存文件
                }
            }
        }
    }

    /**
     * 给file追加数据
     */
    private fun appendFile(file: File, buffer: ByteArray) {
        runCatching {
            if (!file.exists()) file.createNewFile()
            val randomFile = RandomAccessFile(file, "rw")
            randomFile.seek(file.length())
            randomFile.write(buffer)
            randomFile.close()
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * 参数设置
     *
     * @return
     */
    private fun setParam(voicer: String) {
        runCatching {
            val mTts = this@TtsHelper.mTts!!

            // 清空参数
            mTts.setParameter(SpeechConstant.PARAMS, null)
            // 根据合成引擎设置相应参数
            if (mEngineType == SpeechConstant.TYPE_CLOUD) {
                mTts.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_CLOUD)
                // 支持实时音频返回，仅在 synthesizeToUri 条件下支持
                mTts.setParameter(SpeechConstant.TTS_DATA_NOTIFY, "1")

                //	mTts.setParameter(SpeechConstant.TTS_BUFFER_TIME,"1");

                // 设置在线合成发音人
                mTts.setParameter(SpeechConstant.VOICE_NAME, voicer)
                //设置合成语速
                mTts.setParameter(SpeechConstant.SPEED, "50")
                //设置合成音调
                mTts.setParameter(SpeechConstant.PITCH, "50")
                //设置合成音量
                mTts.setParameter(SpeechConstant.VOLUME, "50")
            } else {
                mTts.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_LOCAL)
                mTts.setParameter(SpeechConstant.VOICE_NAME, "")
            }

            //设置播放器音频流类型
            mTts.setParameter(SpeechConstant.STREAM_TYPE, "3")
            // 设置播放合成音频打断音乐播放，默认为true
            mTts.setParameter(SpeechConstant.KEY_REQUEST_FOCUS, "false")

            // 设置音频保存路径，保存音频格式支持pcm、wav，设置路径为sd卡请注意WRITE_EXTERNAL_STORAGE权限
            mTts.setParameter(SpeechConstant.AUDIO_FORMAT, "pcm")
            mTts.setParameter(SpeechConstant.TTS_AUDIO_PATH, File.createTempFile("tts", ".pcm").absolutePath)
        }.onFailure {
            logE(it)
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    private fun logE(str: String?) {
        if (!str.isNullOrEmpty()) ThreadUtils.runOnUiThread { Log.e(TAG, str) }
    }

    private fun logE(thr: Throwable?) {
        if (thr != null) ThreadUtils.runOnUiThread { Log.e(TAG, "", thr) }
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////////////////////////////////////////
    private val defaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            super.onResume(owner)

            resume()
        }

        override fun onPause(owner: LifecycleOwner) {
            super.onPause(owner)

            pauseSpeaking()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            super.onDestroy(owner)

            destroy()

            lifecycleOwner?.lifecycle?.removeObserver(this)
        }
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////
}
