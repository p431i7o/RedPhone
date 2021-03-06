/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.redphone.call;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import org.thoughtcrime.redphone.audio.AudioException;
import org.thoughtcrime.redphone.audio.CallAudioManager;
import org.thoughtcrime.redphone.crypto.SecureRtpSocket;
import org.thoughtcrime.redphone.crypto.zrtp.MasterSecret;
import org.thoughtcrime.redphone.crypto.zrtp.NegotiationFailedException;
import org.thoughtcrime.redphone.crypto.zrtp.RecipientUnavailableException;
import org.thoughtcrime.redphone.crypto.zrtp.SASInfo;
import org.thoughtcrime.redphone.crypto.zrtp.ZRTPSocket;
import org.thoughtcrime.redphone.monitor.CallMonitor;
import org.thoughtcrime.redphone.monitor.EventStream;
import org.thoughtcrime.redphone.signaling.SessionDescriptor;
import org.thoughtcrime.redphone.signaling.SignalingSocket;
import org.thoughtcrime.redphone.ui.ApplicationPreferencesActivity;
import org.thoughtcrime.redphone.util.AudioUtils;

import java.io.IOException;

/**
 * The base class for both Initiating and Responder call
 * managers, which coordinate the setup of an outgoing or
 * incoming call.
 *
 * @author Moxie Marlinspike
 *
 */

public abstract class CallManager extends Thread {
  private static final String CODEC_NAME = "SPEEX";

  protected final String remoteNumber;
  protected final CallStateListener callStateListener;
  protected final Context context;
  protected final CallMonitor monitor;

  private boolean terminated;
  private boolean loopbackMode;
  private CallAudioManager callAudioManager;
  private SignalManager signalManager;
  private SASInfo sasInfo;
  private boolean muteEnabled;
  private boolean callConnected;

  protected SessionDescriptor sessionDescriptor;
  protected ZRTPSocket zrtpSocket;
  protected SecureRtpSocket secureSocket;
  protected SignalingSocket signalingSocket;

  private EventStream lifecycleMonitor;

  public CallManager(Context context, CallStateListener callStateListener,
                    String remoteNumber, String threadName)
  {
    super(threadName);
    this.remoteNumber      = remoteNumber;
    this.callStateListener = callStateListener;
    this.terminated        = false;
    this.context           = context;
    this.loopbackMode      = ApplicationPreferencesActivity.getLoopbackEnabled(context);
    this.monitor           = new CallMonitor(context);

    initMonitor();
    printInitDebug();
    AudioUtils.resetConfiguration(context);
  }

  private void initMonitor() {
     lifecycleMonitor = monitor.addEventStream("call-setup");
  }

  @Override
  public void run() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

    lifecycleMonitor.emitEvent("call-begin");
    try {
      Log.d( "CallManager", "negotiating..." );
      if (!terminated) {
        callAudioManager = new CallAudioManager(secureSocket, CODEC_NAME, context, monitor);
        callAudioManager.setMute(muteEnabled);
        lifecycleMonitor.emitEvent("start-negotiate");
        zrtpSocket.negotiateStart();
      }

      if (!terminated) {
        lifecycleMonitor.emitEvent("performing-handshake");
        callStateListener.notifyPerformingHandshake();
        zrtpSocket.negotiateFinish();
      }

      if (!terminated) {
        setSecureSocketKeys(zrtpSocket.getMasterSecret());
        sasInfo = zrtpSocket.getSasInfo();
        callStateListener.notifyCallConnected(sasInfo);
      }

      if (!terminated) {
        Log.d("CallManager", "Finished handshake, calling run() on CallAudioManager...");
        callConnected = true;
        callAudioManager.run();
      }

    } catch (RecipientUnavailableException rue) {
      Log.w("CallManager", rue);
      if (!terminated) callStateListener.notifyRecipientUnavailable();
    } catch (NegotiationFailedException nfe) {
      Log.w("CallManager", nfe);
      if (!terminated) callStateListener.notifyHandshakeFailed();
    } catch (AudioException e) {
      Log.w("CallManager", e);
      callStateListener.notifyClientError(e.getClientMessage());
    } catch (IOException e) {
      Log.w("CallManager", e);
      callStateListener.notifyCallDisconnected();
    }
  }

  public void terminate() {
    this.terminated = true;
    lifecycleMonitor.emitEvent("terminate");

    if (monitor != null && sessionDescriptor != null) {
      monitor.startUpload(context, String.valueOf(sessionDescriptor.sessionId));
    }

    if (callAudioManager != null)
      callAudioManager.terminate();

    if (signalManager != null)
      signalManager.terminate();

    if (zrtpSocket != null)
      zrtpSocket.close();
  }

  public SessionDescriptor getSessionDescriptor() {
    return this.sessionDescriptor;
  }

  public SASInfo getSasInfo() {
    return this.sasInfo;
  }

  public void setSasVerified() {
    if (zrtpSocket != null)
      zrtpSocket.setSasVerified();
  }

  protected void processSignals() {
    Log.w("CallManager", "Starting signal processing loop...");
    this.signalManager = new SignalManager(callStateListener, signalingSocket, sessionDescriptor);
  }

  protected abstract void setSecureSocketKeys(MasterSecret masterSecret);

  private void printInitDebug() {
    Context c = context;
    String vName = "unknown";
    try {
        vName = c.getPackageManager().getPackageInfo("org.thoughtcrime.redphone", 0).versionName;
    } catch (NameNotFoundException e) {
    }

    ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo networkInfo = cm.getActiveNetworkInfo();

    monitor.addNominalValue("audio-mode", ApplicationPreferencesActivity.getAudioModeIncall(c));
    monitor.addNominalValue("device", Build.DEVICE);
    monitor.addNominalValue("manufacturer", Build.MANUFACTURER);
    monitor.addNominalValue("android-version", Build.VERSION.RELEASE);
    monitor.addNominalValue("product", Build.PRODUCT);
    monitor.addNominalValue("redphone-version", vName);
    monitor.addNominalValue("network-type", networkInfo == null ? null : networkInfo.getTypeName());
    monitor.addNominalValue("network-subtype", networkInfo == null ? null : networkInfo.getSubtypeName());
    monitor.addNominalValue("network-extra", networkInfo == null ? null : networkInfo.getExtraInfo());
  }

  public void setMute(boolean enabled) {
    muteEnabled = enabled;
    if(callAudioManager != null) {
      callAudioManager.setMute(muteEnabled);
    }
  }

  /**
   * Did this call ever successfully complete SRTP setup
   * @return true if the call connected
   */
  public boolean callConnected() {
    return callConnected;
  }

  ///**********************
  // Methods below are SOA's loopback and testing shims.
  //For loopback operation
  public void doLoopback() throws AudioException, IOException {
    callAudioManager = new CallAudioManager(null, "SPEEX", context, new CallMonitor(context));
    callAudioManager.run();
  }
}
