/*
 * Copyright (c) 2014 The MITRE Corporation, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this work except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mitre.svmp.apprtc;

import android.os.AsyncTask;
import android.os.Binder;
import android.os.HandlerThread;
import android.util.Log;
import com.google.protobuf.InvalidProtocolBufferException;
import de.tavendo.autobahn.WebSocket;
import de.tavendo.autobahn.WebSocketConnection;
import de.tavendo.autobahn.WebSocketException;
import de.tavendo.autobahn.WebSocketOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.json.JSONException;
import org.json.JSONObject;
import org.mitre.svmp.activities.AppRTCActivity;
import org.mitre.svmp.auth.AuthData;
import org.mitre.svmp.client.R;
import org.mitre.svmp.common.ConnectionInfo;
import org.mitre.svmp.common.Constants;
import org.mitre.svmp.common.DatabaseHandler;
import org.mitre.svmp.common.SessionInfo;
import org.mitre.svmp.common.StateMachine;
import org.mitre.svmp.common.StateMachine.STATE;
import org.mitre.svmp.net.SSLConfig;
import org.mitre.svmp.performance.PerformanceTimer;
import org.mitre.svmp.protocol.SVMPProtocol.AuthResponse;
import org.mitre.svmp.protocol.SVMPProtocol.Request;
import org.mitre.svmp.protocol.SVMPProtocol.Response;
import org.mitre.svmp.services.SessionService;

/**
 * @author Joe Portner
 *
 * Negotiates signaling for chatting with apprtc.appspot.com "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.
 *
 * Now extended to act as a Binder object between a Service and an Activity.
 *
 * To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once that's done call sendMessage() and wait for the
 * registered handler to be called with received messages.
 */
public class AppRTCClient extends Binder implements Constants {
  private static final String TAG = AppRTCClient.class.getName();

  // service and activity objects
  private StateMachine machine;
  private SessionService service = null;
  private AppRTCActivity activity = null;

  // common variables
  private ConnectionInfo connectionInfo;
  private SessionInfo sessionInfo;
  private DatabaseHandler dbHandler;
  private boolean init = false; // switched to 'true' when activity first binds
  private boolean proxying = false; // switched to 'true' upon state machine change

  // performance instrumentation
  private PerformanceTimer performance;
  WebSocket.WebSocketConnectionObserver observer = new WebSocket.WebSocketConnectionObserver() {
    private boolean hasVMREADY;

    @Override public void onOpen() {
      Log.i(TAG, "WebSocket connected.");
      machine.setState(STATE.CONNECTED,
          R.string.appRTC_toast_socketConnector_success); // AUTH -> CONNECTED
      // wait for VMREADY
    }

    @Override public void onClose(WebSocketCloseNotification code, String reason) {
      if (proxying || machine.getState() == STATE.AUTH || machine.getState() == STATE.CONNECTED) {
        // either we were disconnected unexpectedly, or the connection was never successfully established
        // we haven't called disconnect(), this was an error; log this as an Error message and change state
        changeToErrorState();
        Log.e(TAG, "WebSocket disconnected: " + code.toString() + ", " + reason);
      } else // we called disconnect(), this was intentional; log this as an Info message
      {
        Log.i(TAG, "WebSocket disconnected.");
      }
    }

    @Override public void onTextMessage(String payload) {
    }

    @Override public void onRawTextMessage(byte[] payload) {
    }

    @Override public void onBinaryMessage(byte[] payload) {
      try {
        Response data = Response.parseFrom(payload);
        Log.d(TAG, "Received incoming message object of type " + data.getType().name());
        onResponse(data);
      } catch (InvalidProtocolBufferException e) {
        Log.e(TAG, "Unable to parse protobuf:", e);
        changeToErrorState();
      }
    }

    private void onResponse(Response data) {
      if (data.getType() == Response.ResponseType.ERROR) {
        Log.e(TAG, "Received ERROR message");
        int error = hasVMREADY ? R.string.appRTC_toast_connection_finish
            : R.string.appRTC_toast_svmpReadyWait_fail;
        machine.setState(STATE.ERROR, error);
      } else if (!hasVMREADY) // we are in the CONNECTED state, waiting for VMREADY
      {
        onResponseCONNECTED(data);
      } else // we are in the RUNNING state
      {
        onResponseRUNNING(data);
      }
    }

    // STEP 3: CONNECTED -> RUNNING, Receive VMREADY message
    private void onResponseCONNECTED(Response data) {
      int error = R.string.appRTC_toast_connection_finish; // generic error message

      // generate a status code
      Response.ResponseType type = data.getType();
      if (type == Response.ResponseType.VMREADY) {
        error = 0;
      } else if (type == Response.ResponseType.AUTH
          && data.getAuthResponse().getType() == AuthResponse.AuthResponseType.AUTH_FAIL) {
        error = R.string.appRTC_toast_svmpAuthenticator_fail;
      }
      // any other message type throws us into an error state

      // act on the status code
      if (error == 0) { // success
        hasVMREADY = true;
        machine.setState(STATE.RUNNING, R.string.appRTC_toast_svmpReadyWait_success); // CONNECTED -> RUNNING
        proxying = true;

        // callbacks to the service and activity to let them know the connection has started
        service.onOpen();
        if (isBound()) activity.onOpen();

        // start taking performance measurements
        performance.start();
      } else // fail with the appropriate error message
      {
        machine.setState(STATE.ERROR, error);
      }
    }

    // STEP 4: RUNNING
    private void onResponseRUNNING(final Response data) {
      boolean consumed = service.onMessage(data);
      if (!consumed && isBound()) {
        activity.runOnUiThread(new Runnable() {
          public void run() {
            activity.onMessage(data);
          }
        });
      }
    }
  };
  // variables for networking
  private boolean useSSL;
  private SSLConfig sslConfig;
  private Socket socket;
  private SocketHandlerThread socketHandlerThread;
  private WebSocketConnection webSocket;

  // STEP 0: NEW -> STARTED
  public AppRTCClient(SessionService service, StateMachine machine, ConnectionInfo connectionInfo) {
    this.service = service;
    this.machine = machine;
    machine.addObserver(service);
    this.connectionInfo = connectionInfo;

    this.dbHandler = new DatabaseHandler(service);
    this.performance = new PerformanceTimer(service, this, connectionInfo.getConnectionID());

    machine.setState(STATE.STARTED, 0);
  }

  // called from activity
  public void connectToRoom(AppRTCActivity activity) {
    this.activity = activity;
    machine.addObserver(activity);

    // we don't initialize the SocketConnector until the activity first binds; mitigates concurrency issues
    if (!init) {
      init = true;

      int error = 0;
      // determine whether we should use SSL from the EncryptionType integer
      useSSL = connectionInfo.getEncryptionType() == Constants.ENCRYPTION_SSLTLS;

      if (useSSL) {
        sslConfig = new SSLConfig(connectionInfo, activity);
        error = sslConfig.configure();
      }

      if (error == 0) {
        login();
      } else {
        machine.setState(STATE.ERROR, error);
      }
    }
    // if the state is already running, we are reconnecting
    else if (machine.getState() == STATE.RUNNING) {
      activity.onOpen();
    }
  }

  // called from activity
  public void disconnectFromRoom() {
    machine.removeObserver(activity);
    this.activity = null;
  }

  public boolean isBound() {
    return this.activity != null;
  }

  public PerformanceTimer getPerformance() {
    return performance;
  }

  public AppRTCSignalingParameters getSignalingParams() {
    return sessionInfo.getSignalingParams();
  }

  // called from AppRTCActivity
  public void changeToErrorState() {
    machine.setState(STATE.ERROR, R.string.appRTC_toast_connection_finish);
  }

  public void disconnect() {
    proxying = false;

    // we're disconnecting, update the database record with the current timestamp
    dbHandler.close();

    performance.cancel(); // stop taking performance measurements

    // shut down the WebSocket if it's open
    if (webSocket != null && webSocket.isConnected()) webSocket.disconnect();
    if (socketHandlerThread != null) socketHandlerThread.quitSafely();
  }

  public synchronized void sendMessage(Request msg) {
    if (proxying) {
      //webSocket.sendBinaryMessage(msg.toByteArray());
      // VM is expecting a message delimiter (varint prefix) so write a delimited message instead
      try {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        msg.writeDelimitedTo(stream);
        webSocket.sendBinaryMessage(stream.toByteArray());
      } catch (IOException e) {
        Log.e(TAG, "Error writing delimited byte output:", e);
      }
    }
  }

  public void login() {
    // attempt to get any existing auth data JSONObject that's in memory (e.g. made of user input such as password)
    JSONObject jsonObject = AuthData.getJSON(connectionInfo);
    if (jsonObject != null) {
      // execute async HTTP request to the REST auth API
      (new SVMPAuthenticator()).execute(jsonObject);
    } else {
      sessionInfo = dbHandler.getSessionInfo(connectionInfo);
      if (sessionInfo != null) {
        // we've already authenticated, we can connect directly to the proxy
        machine.setState(STATE.AUTH,
            R.string.appRTC_toast_svmpAuthenticator_bypassed); // STARTED -> AUTH
        connect();
      } else {
        Log.e(TAG, "login failed: no auth data or session info found");
        machine.setState(STATE.ERROR, R.string.appRTC_toast_connection_finish);
      }
    }
  }

  // STEP 2: AUTH -> CONNECTED, Connect to the SVMP proxy service
  public void connect() {
    new SocketConnector().execute();
  }

  // STEP 1: STARTED -> AUTH, Authenticate with the SVMP login REST service
  private class SVMPAuthenticator extends AsyncTask<JSONObject, Void, Integer> {
    private boolean passwordChange;

    @Override protected Integer doInBackground(JSONObject... jsonObjects) {
      int returnVal = R.string.appRTC_toast_socketConnector_fail; // generic error message
      JSONObject jsonRequest = jsonObjects[0];

      passwordChange = jsonRequest.has("newPassword");
      int rPort = connectionInfo.getPort();
      String proto = useSSL ? "https" : "http", rHost = connectionInfo.getHost(),
          // if we're changing our password, use a different API
          api = passwordChange ? "changePassword" : "login", uri =
          String.format("%s://%s:%d/%s", proto, rHost, rPort, api);

      // set up HttpParams
      HttpParams params = new BasicHttpParams();
      HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
      HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);

      // set up ConnectionManager
      SchemeRegistry registry = new SchemeRegistry();
      registry.register(new Scheme(proto,
          useSSL ? sslConfig.getSocketFactory() : PlainSocketFactory.getSocketFactory(), rPort));
      ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, registry);

      // create HttpClient
      DefaultHttpClient httpclient = new DefaultHttpClient(ccm, params);
      HttpPost post = new HttpPost(uri);
      post.setHeader(HTTP.CONTENT_TYPE, "application/json");

      try {
        StringEntity entity = new StringEntity(jsonRequest.toString());
        post.setEntity(entity);
        HttpResponse response = httpclient.execute(post);
        int responseCode = response.getStatusLine().getStatusCode();

        if (responseCode == 200) { // "OK", code for AUTH_OK
          // get JSON object
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          response.getEntity().writeTo(out);
          out.close();
          JSONObject jsonResponse = new JSONObject(out.toString());

          // get session info
          String token = jsonResponse.getJSONObject("sessionInfo").getString("token");
          long expires = new Date().getTime() + (1000 * jsonResponse.getJSONObject("sessionInfo")
              .getInt("maxLength"));
          String host = jsonResponse.getJSONObject("server").getString("host");
          String port = jsonResponse.getJSONObject("server").getString("port");
          JSONObject webrtc = jsonResponse.getJSONObject("webrtc");
          sessionInfo = new SessionInfo(token, expires, host, port, webrtc);

          if (sessionInfo.getSignalingParams() != null) returnVal = 0; // success
        } else if (responseCode == 403) { // "Forbidden", code for NEED_PASSWORD_CHANGE
          returnVal = R.string.svmpActivity_toast_needPasswordChange;
        } else if ((responseCode == 400 || responseCode == 401)
            && !passwordChange) { // "Unauthorized", code for AUTH_FAIL
          returnVal = R.string.appRTC_toast_svmpAuthenticator_fail;
        } else if (responseCode == 400
            || responseCode == 401) { // "Unauthorized", code for PASSWORD_CHANGE_FAIL
          returnVal = R.string.appRTC_toast_svmpAuthenticator_passwordChangeFail;
        } else if (responseCode == 404) { // "Not Found"
          returnVal = R.string.appRTC_toast_socketConnector_404;
        }
      } catch (JSONException e) {
        Log.e(TAG, "Failed to parse JSON response:", e);
      } catch (SSLHandshakeException e) {
        String msg = e.getMessage();
        if (msg.contains("java.security.cert.CertPathValidatorException")) {
          // the server's certificate isn't in our trust store
          Log.e(TAG, "Untrusted server certificate!");
          returnVal = R.string.appRTC_toast_socketConnector_failUntrustedServer;
        } else {
          Log.e(TAG, "Error during SSL handshake: " + e.getMessage());
          returnVal = R.string.appRTC_toast_socketConnector_failSSLHandshake;
        }
      } catch (SSLException e) {
        if ("Connection closed by peer".equals(e.getMessage())) {
          // connection failed, we tried to connect using SSL but REST API's SSL is turned off
          Log.e(TAG, "Client encryption is on but server encryption is off:", e);
          returnVal = R.string.appRTC_toast_socketConnector_failSSL;
        } else {
          Log.e(TAG, "SSL error:", e);
        }
      } catch (NoHttpResponseException e) {
        if ("The target server failed to respond".equals(e.getMessage())) {
          // connection failed, we tried to connect without using SSL but REST API's SSL is turned on
          Log.e(TAG, "Client encryption is off but server encryption is on:", e);
          returnVal = R.string.appRTC_toast_socketConnector_failSSL;
        } else {
          Log.e(TAG, "HTTP request failed:", e);
        }
      } catch (IOException e) {
        Log.e(TAG, "HTTP request failed:", e);
      }
      return returnVal;
    }

    @Override protected void onPostExecute(Integer result) {
      if (result == 0) { // success, start the next phase and connect to the SVMP proxy server
        dbHandler.updateSessionInfo(connectionInfo, sessionInfo);
        machine.setState(STATE.AUTH,
            R.string.appRTC_toast_svmpAuthenticator_success); // STARTED -> AUTH
        connect();
      } else {
        // authentication failed, handle appropriately
        machine.setState(STATE.ERROR, result); // STARTED -> ERROR
      }
    }
  }

  private class SocketConnector extends AsyncTask<Void, Void, Integer> {
    @Override protected Integer doInBackground(Void... params) {
      int returnVal = R.string.appRTC_toast_socketConnector_fail; // resID for return message

      try {
        // create the socket for the WebSocketConnection to use
        // we do this here because the Looping and Handling that takes place in the WebSocket code causes
        // the app to freeze when any other processes are launched (such as KeyChain or MemorizingTrustManager)
        javax.net.SocketFactory factory;
        if (useSSL) {
          factory = sslConfig.getSSLContext().getSocketFactory();
        } else {
          factory = javax.net.SocketFactory.getDefault();
        }
        socket =
            factory.createSocket(sessionInfo.getHost(), Integer.parseInt(sessionInfo.getPort()));
        if (useSSL) {
          SSLSocket sslSocket = (SSLSocket) socket;
          sslSocket.setEnabledProtocols(ENABLED_PROTOCOLS);
          sslSocket.setEnabledCipherSuites(ENABLED_CIPHERS);
          sslSocket.startHandshake(); // starts the handshake to verify the cert before continuing
        }
        //socket.setTcpNoDelay(true);

        // if we made it to this point, return a success message
        returnVal = 0;
      } catch (SSLHandshakeException e) {
        String msg = e.getMessage();
        if (msg.contains("java.security.cert.CertPathValidatorException")) {
          // the server's certificate isn't in our trust store
          Log.e(TAG, "Untrusted server certificate!");
          returnVal = R.string.appRTC_toast_socketConnector_failUntrustedServer;
        } else {
          Log.e(TAG, "Error during SSL handshake: " + e.getMessage());
          returnVal = R.string.appRTC_toast_socketConnector_failSSLHandshake;
        }
      } catch (SSLException e) {
        if ("Connection closed by peer".equals(e.getMessage())) {
          // connection failed, we tried to connect using SSL but REST API's SSL is turned off
          Log.e(TAG, "Client encryption is on but server encryption is off:", e);
          returnVal = R.string.appRTC_toast_socketConnector_failSSL;
        } else {
          Log.e(TAG, "SSL error:", e);
        }
      } catch (Exception e) {
        Log.e(TAG, "Exception: " + e.getMessage());
        e.printStackTrace();
      }
      return returnVal;
    }

    @Override protected void onPostExecute(Integer result) {
      if (result != 0) {
        machine.setState(STATE.ERROR, result); // STARTED -> ERROR
      } else {
        // we have to run the WebSocket connection in a HandlerThread to ensure that Looper is prepared
        // properly and that the MemorizingTrustManager doesn't execute on the main UI thread
        socketHandlerThread = new SocketHandlerThread("svmp-websocket-" + new Date().getTime());
        socketHandlerThread.start();
      }
    }
  }

  private class SocketHandlerThread extends HandlerThread {

    public SocketHandlerThread(String name) {
      super(name);
    }

    @Override protected void onLooperPrepared() {
      // set up the WebSocket URI for the svmp-server
      String proto = useSSL ? "wss" : "ws";
      URI uri = URI.create(
          String.format("%s://%s:%s", proto, sessionInfo.getHost(), sessionInfo.getPort()));
      Log.d(TAG, "Socket connecting to " + uri.toString());

      // set up the WebSocket options for the svmp-server
      WebSocketOptions options = new WebSocketOptions();
      options.setMaxFramePayloadSize(
          8 * 128 * 1024); // increase max frame size to handle high-res icons
      HashMap<String, String> headers = new HashMap<String, String>();
      // HACK: JavaScript WebSocket API doesn't allow for custom headers, so we repurpose this header instead
      // We set it here instead of the constructor because this doesn't append a comma suffix
      headers.put("Sec-WebSocket-Protocol", sessionInfo.getToken());
      options.setHeaders(headers);

      // we have the socket and the SSL handshake has completed
      // now establish a WebSocketConnection
      try {
        webSocket = new WebSocketConnection();
        webSocket.connect(socket, uri, null, observer, options);
      } catch (WebSocketException e) {
        Log.e(TAG, "Failed to connect to SVMP proxy:", e);
        machine.setState(STATE.ERROR, R.string.appRTC_toast_socketConnector_fail);
      }
    }
  }
}
