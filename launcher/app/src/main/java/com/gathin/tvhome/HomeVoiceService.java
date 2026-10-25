package com.gathin.tvhome;

import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Holds the voice interaction slot, which offers no assistant: it exists because Android lets the slot's app start
 * activities during the five seconds after the Home key, when every other app is held back. With it,
 * HomeKeyService's stock_home_redirect returns at once instead of after that pause. Use it only on a TV whose slot
 * is empty (`settings get secure voice_interaction_service` prints null), so no assistant is displaced.
 *
 * Enable over adb:
 *   settings put secure voice_interaction_service io.github.tpxcer.reel/com.gathin.tvhome.HomeVoiceService
 */
public class HomeVoiceService extends VoiceInteractionService {

    @Override
    public void onReady() {
        super.onReady();
        ServiceRepair.voiceConnected(this, getClass());
    }

    /** Unbound by Android because another assistant was chosen: don't put this one back. */
    @Override
    public void onShutdown() {
        ServiceRepair.voiceDisconnected(this);
        super.onShutdown();
    }

    /** Android requires the slot to name a recognizer; this one has no speech engine and says so. */
    public static class Recognizer extends RecognitionService {
        @Override
        protected void onStartListening(Intent recognizerIntent, Callback listener) {
            try {
                listener.error(SpeechRecognizer.ERROR_CLIENT);
            } catch (RemoteException gone) {
                // The caller went away first; nothing to report.
            }
        }

        @Override
        protected void onCancel(Callback listener) {
        }

        @Override
        protected void onStopListening(Callback listener) {
        }
    }

    /** A session asked of the slot closes straight away. */
    public static class Sessions extends VoiceInteractionSessionService {
        @Override
        public VoiceInteractionSession onNewSession(Bundle args) {
            return new VoiceInteractionSession(this) {
                @Override
                public void onShow(Bundle showArgs, int showFlags) {
                    super.onShow(showArgs, showFlags);
                    finish();
                }
            };
        }
    }
}
