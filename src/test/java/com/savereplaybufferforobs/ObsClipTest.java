package com.savereplaybufferforobs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okio.ByteString;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObsClipTest
{
    private final Gson gson = new Gson();
    private String error;
    private String sent;
    private final DisplaysExceptions feedback = new DisplaysExceptions()
    {
        public void setObsException(ObsException exception) { error = exception.getMessage(); }
        public void clearObsException() { error = null; }
        public void clearObsException(ObsException exception) { if (exception.getMessage().equals(error)) { error = null; } }
    };
    private final WebSocketClientForObs client = new WebSocketClientForObs(new OkHttpClient(), gson, "localhost", 4455, "", feedback);
    private final WebSocketListenerForObs listener = new WebSocketListenerForObs(client, gson, "", feedback);

    @Test
    public void sendsExactVendorContractAndRejectsInvalidOrDisconnectedRequests() throws Exception
    {
        client.saveClip(60);
        assertTrue(error.contains("not connected"));
        Field socket = WebSocketClientForObs.class.getDeclaredField("webSocket");
        socket.setAccessible(true);
        socket.set(client, new WebSocket()
        {
            public Request request() { return new Request.Builder().url("http://localhost").build(); }
            public long queueSize() { return 0; }
            public boolean send(String text) { sent = text; return true; }
            public boolean send(ByteString bytes) { return false; }
            public boolean close(int code, String reason) { return true; }
            public void cancel() { }
        });
        client.setConnected(true);
        client.saveClip(0);
        assertNull(sent);
        client.saveClip(1234);
        JsonObject envelope = gson.fromJson(sent, JsonObject.class);
        assertEquals(6, envelope.get("op").getAsInt());
        JsonObject request = envelope.getAsJsonObject("d");
        assertEquals("CallVendorRequest", request.get("requestType").getAsString());
        JsonObject data = request.getAsJsonObject("requestData");
        assertEquals("replay-buffer-pro", data.get("vendorName").getAsString());
        assertEquals("SaveClip", data.get("requestType").getAsString());
        assertEquals(1234, data.getAsJsonObject("requestData").get("durationSeconds").getAsInt());
        client.disconnect();
        sent = null;
        client.saveClip(60);
        assertNull(sent);
    }

    @Test
    public void handlesRefusalMissingVendorAndAcceptanceWithoutClaimingCompletion()
    {
        respond("{\"result\":false,\"comment\":\"Unknown vendor\"}", "{}");
        assertTrue(error.contains("Unknown vendor"));
        respond("{\"result\":true}", "{\"responseData\":{\"accepted\":false,\"error\":\"Replay buffer is inactive\"}}");
        assertTrue(error.contains("inactive"));
        listener.onMessage(null, "{\"op\":7,\"d\":{\"requestType\":\"GetReplayBufferStatus\",\"responseData\":{\"outputActive\":true}}}");
        assertTrue(error.contains("inactive")); // Health checks must not erase a save refusal.
        respond("{\"result\":true}", "{\"responseData\":{\"accepted\":true}}");
        assertNull(error);
        respond("{\"result\":true}", "{}");
        assertTrue(error.contains("invalid acceptance"));
    }

    @Test
    public void healthAndClipResultsOnlyClearTheirOwnWarnings()
    {
        respond("{\"result\":false,\"comment\":\"Unknown vendor\"}", "{}");
        health(false);
        assertTrue(error.contains("not active"));
        health(true);
        assertNull(error); // A recovered buffer never leaves the inactive warning stuck.

        health(false);
        respond("{\"result\":true}", "{\"responseData\":{\"accepted\":true}}");
        assertTrue(error.contains("not active")); // A successful clip leaves a real health warning alone.
    }

    private void health(boolean active)
    {
        listener.onMessage(null, "{\"op\":7,\"d\":{\"requestType\":\"GetReplayBufferStatus\",\"responseData\":{\"outputActive\":" + active + "}}}");
    }

    private void respond(String status, String data)
    {
        listener.onMessage(null, "{\"op\":7,\"d\":{\"requestId\":\"runelite-duration-req\",\"requestType\":\"CallVendorRequest\",\"requestStatus\":" + status + ",\"responseData\":" + data + "}}");
    }
}
