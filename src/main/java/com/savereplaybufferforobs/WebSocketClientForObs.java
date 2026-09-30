package com.savereplaybufferforobs;

import com.google.gson.Gson;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import java.util.Map;

@Slf4j
public class WebSocketClientForObs {
    private final String websocketUrl;
    private final String password;

    private final OkHttpClient client;
    private final Gson gson;

    private WebSocket webSocket;

    private DisplaysExceptions exceptionsDisplay;

    @Setter
    private volatile boolean isConnected;

    private class ObsRequest {
        private final int op = 6;
        private final D d;

        public ObsRequest(String requestType, String requestId, Object requestData) {
            this.d = new D(requestType, requestId, requestData);
        }

        private class D {
            private final String requestType;
            private final String requestId;
            private final Object requestData;

            public D(String requestType, String requestId, Object requestData) {
                this.requestType = requestType;
                this.requestId = requestId;
                this.requestData = requestData;
            }
        }
    }

    public WebSocketClientForObs(OkHttpClient client, Gson gson, String host, int port, String password, DisplaysExceptions exceptionsDisplay) {
        this.client = client;
        this.gson = gson;
        this.websocketUrl = "ws://" + host + ":" + port;
        this.password = password;
        this.exceptionsDisplay = exceptionsDisplay;
    }

    public void makeOBSRequest(String requestType, String requestId, Object requestData) {
        ObsRequest saveReplayBufferRequest = new ObsRequest(requestType, requestId, requestData);
        String jsonPayload = gson.toJson(saveReplayBufferRequest);
        this.webSocket.send(jsonPayload);
    }

    public void saveReplayBuffer() {
        makeOBSRequest("SaveReplayBuffer", "runelite-clip-req", new Object());
    }

    public void saveClip(int durationSeconds) {
        if (!isConnected || webSocket == null) {
            log.warn("Clip not requested: OBS WebSocket is not connected and authenticated");
            exceptionsDisplay.showChatMessage("Your replay clip wasn't saved because OBS isn't connected.");
            return;
        }
        ObsRequest request = new ObsRequest("CallVendorRequest", "runelite-duration-req", Map.of(
                "vendorName", "replay-buffer-pro", "requestType", "SaveClip",
                "requestData", Map.of("durationSeconds", durationSeconds)));
        if (!webSocket.send(gson.toJson(request))) {
            log.warn("Clip request could not be sent to OBS");
            exceptionsDisplay.showChatMessage("Your replay clip wasn't saved because the request couldn't be sent to OBS.");
        } else {
            log.debug("Requested the last {} seconds from Replay Buffer Pro.", durationSeconds);
        }
    }

    public void connect() {
        Request request = new Request.Builder()
                .url(websocketUrl)
                .build();
        this.webSocket = client.newWebSocket(request, new WebSocketListenerForObs(this, gson, password, exceptionsDisplay));
    }

    public void disconnect() {
        isConnected = false;
        if (webSocket != null) {
            this.webSocket.close(1000, "Normal Shutdown");
        }
    }

    public void pingHealth() {
        if (isConnected)
        {
            makeOBSRequest("GetReplayBufferStatus", "runelite-healthcheck-req", new Object());
        }
    }
}
